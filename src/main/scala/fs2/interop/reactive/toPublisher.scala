package fs2
package interop
package reactive

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams.{Subscriber => RSubscriber, Publisher => RPublisher, Subscription => RSubscription}
import com.typesafe.scalalogging.LazyLogging

class UnicastPublisher[A](val s: Stream[Task, A])(implicit AA: Async[Task]) extends RPublisher[A] with LazyLogging {
  logger.debug(s"$this creating new publisher")

  def subscribe(subscriber: RSubscriber[_ >: A]): Unit = {
    val subscription = UnicastPublisher.unicastSubscription(subscriber, s, this).unsafeRun()
    logger.debug(s"$this publisher has received subscriber")
    subscriber.onSubscribe(subscription)
  }
}

object UnicastPublisher extends LazyLogging {

  sealed trait State
  case object InfiniteRequests extends State
  case class FiniteRequests(n: Long, counter: Long) extends State
  case object Cancelled extends State
  case object Cancellation extends Throwable
  case class InvalidNumber(n: Long) extends Throwable with State

  final class UnicastSubscription[F[_], A](requests: Queue[F, State], sub: RSubscriber[A], stream: Stream[F, A], pub: UnicastPublisher[_])(implicit A: Async[F]) extends RSubscription {

    (stream through demandPipe(requests.dequeueAvailable, pub)).map { a =>
      logger.trace(s"$pub delivering element [$a]")
      sub.onNext(a)
    }.run.unsafeRunAsync {
      case Left(Cancellation) =>
        logger.error(s"$pub finished with cancellation from downstream")
      case Left(InvalidNumber(n)) =>
        logger.error(s"$pub finished with invalid number [$n]")
        sub.onError(new IllegalArgumentException(s"3.9 - invalid number of elements [$n]"))
      case Left(err) =>
        logger.error(s"$pub finished with error [$err]")
        sub.onError(err)
      case Right(_) =>
        logger.info(s"$pub completed normally")
        sub.onComplete()
    }

    def cancel(): Unit = {
      logger.debug(s"$pub cancellation received from downstream")
      requests.enqueue1(Cancelled).unsafeRunAsync(_ => ())
    }
    def request(n: Long): Unit = {
      if(n == java.lang.Long.MAX_VALUE) {
        logger.debug(s"$pub received request for an infinite number of elements")
        requests.enqueue1(InfiniteRequests).unsafeRunAsync(_ => ())
      }
      else if(n > 0) {
        logger.debug(s"$pub received request for [$n] elements")
        requests.enqueue1(FiniteRequests(n, 0)).unsafeRunAsync(_ => ())
      }
      else {
        logger.error(s"$pub received request for an invalid number of elements [$n]")
        requests.enqueue1(InvalidNumber(n)).unsafeRunAsync(_ => ())
      }
    }
  }

  def demandPipe[F[_], A](state: Stream[F, State], pub: UnicastPublisher[_])(implicit AA: Async[F]): Pipe[F, A, A] = { s =>

    def go(ah: Handle[F, A], sh: Handle[F, State]): Pull[F, A, A] =
      sh.receive1 {
        case (InfiniteRequests, sh) =>
          logger.debug(s"$pub processing infinite requests")
          ah.awaitAsync.flatMap { af => sh.await1Async.flatMap { sf => goInfinite(af, sf) }}
        case (FiniteRequests(n, _), sh) =>
          logger.debug(s"$pub processing [$n] requests")
          ah.awaitAsync.flatMap { af => sh.await1Async.flatMap { sf => goFinite(af, sf, sh, n) }}
        case (Cancelled, _) =>
          logger.debug(s"$pub processing cancellation - terminating stream")
          Pull.fail(Cancellation)
        case (i: InvalidNumber, _) =>
          logger.debug(s"$pub processing downstream error - terminating stream")
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
                logger.trace(s"$pub going back to basic go")
                go(ah, sh.push1(s))
              case (None, _) =>
                logger.error(s"$pub request queue was terminated")
                Pull.done
            }}
          else {
            logger.trace(s"$pub ")
            Pull.output(as.take(n.toInt)) >>
            sh.pull.flatMap { _.flatMap {
              case (Some(s), sh) =>
                logger.trace(s"$pub going back to basic go")
                go(ah.push(as.drop(n.toInt)), sh.push1(s))
              case (None, _) =>
                logger.error(s"$pub request queue was terminated")
                Pull.done
            }}
          }
        }
        case Right(sh) => sh.flatMap { case (s, sh) => s match {
          case Some(FiniteRequests(m, _)) =>
            logger.trace(s"$pub has received [$m] more requests.")
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
          case Some(i: InvalidNumber) =>
            logger.trace(s"$pub has received an error from downstream with [$n] requests remaining.")
            Pull.fail(i)
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
            logger.debug(s"$pub received request for a finite number of elements after an infinite number.  Continuing to process infinite requests")
            sh.await1Async.flatMap(goInfinite(ah, _))
          case (Some(Cancelled), _) =>
            logger.debug(s"$pub processing cancellation - terminating stream")
            Pull.fail(Cancellation)
          case (Some(i: InvalidNumber), _) =>
            logger.debug(s"$pub processing downstream error - terminating stream")
            Pull.fail(i)
          case (None, _) =>
            logger.error(s"$pub impossible state! State signal stream has been terminated")
            Pull.done
        }
      }
    val o = state.map { s =>
      logger.trace(s"$pub is emitting state $s")
      s
    }
    s.pull2(o)(go)
  }

  def unicastSubscription[F[_], A](sub: RSubscriber[A], stream: Stream[F, A], pub: UnicastPublisher[_])(implicit A: Async[F]): F[UnicastSubscription[F, A]] =
    async.unboundedQueue[F, State].map { requests =>
      new UnicastSubscription(requests, sub, stream, pub)
    }
}
