package fs2
package interop
package reactive

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams._
import org.log4s._

final class StreamSubscription[F[_], A](requests: Queue[F, StreamSubscription.State], sub: Subscriber[A], stream: Stream[F, A], publisherName: String)(implicit A: Async[F]) extends Subscription {
  import StreamSubscription._

  private[this] val logger: org.log4s.Logger = getLogger(classOf[StreamSubscription[F, A]])

  (stream through demandPipe(requests.dequeueAvailable, publisherName)).map { a =>
    logger.trace(s"$publisherName-$this delivering element [$a]")
    sub.onNext(a)
  }.run.unsafeRunAsync {
    case Left(Cancellation) =>
      logger.info(s"$publisherName-$this finished with cancellation from downstream")
    case Left(InvalidNumber(n)) =>
      logger.error(s"$publisherName-$this an invalid number of elements was requested [$n]")
      sub.onError(new IllegalArgumentException(s"3.9 - invalid number of elements [$n]"))
    case Left(err) =>
      logger.warn(s"$publisherName-$this finished with error [$err]")
      sub.onError(err)
    case Right(_) =>
      logger.info(s"$publisherName-$this completed normally")
      sub.onComplete()
  }

  def cancel(): Unit = {
    logger.debug(s"$publisherName-$this cancellation received from downstream")
    requests.enqueue1(Cancelled).unsafeRunAsync(_ => ())
  }
  def request(n: Long): Unit = {
    if(n == java.lang.Long.MAX_VALUE) {
      logger.trace(s"$publisherName-$this received request for an infinite number of elements")
      requests.enqueue1(InfiniteRequests).unsafeRunAsync(_ => ())
    }
    else if(n > 0) {
      logger.trace(s"$publisherName-$this received request for [$n] elements")
      requests.enqueue1(FiniteRequests(n, 0)).unsafeRunAsync(_ => ())
    }
    else {
      logger.error(s"$publisherName-$this received request for an invalid number of elements [$n]")
      requests.enqueue1(InvalidNumber(n)).unsafeRunAsync(_ => ())
    }
  }
}

object StreamSubscription {
  sealed trait State
  case object InfiniteRequests extends State
  case class FiniteRequests(n: Long, counter: Long) extends State
  case object Cancelled extends State
  case object Cancellation extends Throwable
  case class InvalidNumber(n: Long) extends Throwable with State


  private[this] val logger: org.log4s.Logger = getLogger

  def apply[F[_], A](sub: Subscriber[A], stream: Stream[F, A], pub: String)(implicit A: Async[F]): F[StreamSubscription[F, A]] =
    async.unboundedQueue[F, State].map { requests =>
      new StreamSubscription(requests, sub, stream, pub)
    }

  def demandPipe[F[_], A](state: Stream[F, State], pub: String)(implicit AA: Async[F]): Pipe[F, A, A] = { s =>

    def go(ah: Handle[F, A], sh: Handle[F, State]): Pull[F, A, A] =
      sh.receive1 {
        case (InfiniteRequests, sh) =>
          logger.trace(s"$pub processing infinite requests")
          ah.awaitAsync.flatMap { af => sh.await1Async.flatMap { sf => goInfinite(af, sf) }}
        case (FiniteRequests(n, _), sh) =>
          logger.trace(s"$pub processing [$n] requests")
          ah.awaitAsync.flatMap { af => sh.await1Async.flatMap { sf => goFinite(af, sf, sh, n) }}
        case (Cancelled, _) =>
          logger.debug(s"$pub processing cancellation - terminating stream")
          Pull.fail(Cancellation)
        case (i: InvalidNumber, _) =>
          logger.warn(s"$pub invalid number of elements [$i] requested - terminating stream")
          Pull.fail(i)
      }

    def goFinite(ah: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[A], Handle[F,A])]], sh: ScopedFuture[F, Pull[F, Nothing, (Option[State], Handle[F, State])]],
      shh: Handle[F, State],
      n: Long): Pull[F, A, A] =
      (ah race sh).pull.flatMap {
        case Left(ah) => ah.flatMap { case (as, ah) =>
          logger.trace(s"$pub received [${as.size}] elements of requested [$n]")
          if(as.size.toLong < n) Pull.output(as) >> ah.awaitAsync.flatMap(goFinite(_, sh, shh, n - as.size.toLong))
          else if(as.size.toLong == n) Pull.output(as) >>
            sh.pull.flatMap { _.flatMap {
              case (Some(s), sh) =>
                go(ah, sh.push1(s))
              case (None, _) =>
                logger.error(s"$pub request queue was terminated.  This should never happen.")
                Pull.done
            }}
          else {
            Pull.output(as.take(n.toInt)) >>
            sh.pull.flatMap { _.flatMap {
              case (Some(s), sh) =>
                go(ah.push(as.drop(n.toInt)), sh.push1(s))
              case (None, _) =>
                logger.error(s"$pub request queue was terminated.  This should never happen.")
                Pull.done
            }}
          }
        }
        case Right(sh) => sh.flatMap { case (s, sh) => s match {
          case Some(FiniteRequests(m, _)) =>
            if(m + n > 0L) {
              logger.trace(s"$pub has received finite number of requests [$m].  Adding to existing requests [$n] to request [${m + n}] elements")
              sh.await1Async.flatMap { sf => goFinite(ah, sf, sh, m + n) }
            }
            else {
              logger.trace(s"$pub has requests summing to infinity.  Now processing infinite requests.")
              sh.await1Async.flatMap { sf => goInfinite(ah, sf) }
            }
          case Some(InfiniteRequests) =>
            logger.trace(s"$pub has received an infinite number of requests.")
            sh.await1Async.flatMap { sf => goInfinite(ah, sf) }
          case Some(err @ InvalidNumber(i)) =>
            logger.warn(s"$pub invalid number of elements [$i] requested - terminating stream")
            Pull.fail(err)
          case Some(Cancelled) =>
            logger.trace(s"$pub has received a cancellation from downstream with [$n] requests remaining.")
            Pull.fail(Cancellation)
          case None =>
            logger.error(s"$pub has invalid state!")
            sys.error("invalid state!")
        } }
      }

    def goInfinite(ah: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[A], Handle[F,A])]], sh: ScopedFuture[F, Pull[F, Nothing, (Option[State], Handle[F, State])]]): Pull[F, A, A] =
      (ah race sh).pull flatMap {
        case Left(ah) => ah.flatMap {
          case (as, ah) => Pull.output(as) >> ah.awaitAsync.flatMap(goInfinite(_, sh))
        }
        case Right(sh) => sh.flatMap {
          case (Some(InfiniteRequests), sh) =>
            logger.trace(s"$pub continuing to process infinite requests")
            sh.await1Async.flatMap(goInfinite(ah, _))
          case (Some(FiniteRequests(_, _)), sh) =>
            logger.trace(s"$pub received request for a finite number of elements after an infinite number.  Continuing to process infinite requests")
            sh.await1Async.flatMap(goInfinite(ah, _))
          case (Some(Cancelled), _) =>
            logger.debug(s"$pub processing cancellation - terminating stream")
            Pull.fail(Cancellation)
          case (Some(err @ InvalidNumber(i)), _) =>
            logger.warn(s"$pub invalid number of elements [$i] requested - terminating stream")
            Pull.fail(err)
          case (None, _) =>
            logger.error(s"$pub impossible state! request queue has been terminated")
            Pull.done
        }
      }
    s.pull2(state)(go)
  }
}
