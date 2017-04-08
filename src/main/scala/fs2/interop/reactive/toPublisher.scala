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
  case object Idle extends State
  case object InfiniteRequests extends State
  case class FiniteRequests(n: Long, counter: Long) extends State
  case object Cancelled extends State
  case object Errored extends State

  final class UnicastSubscription[F[_], A](signal: Signal[F, State], sub: RSubscriber[A], stream: Stream[F, A], pub: UnicastPublisher[_])(implicit A: Async[F]) extends RSubscription {

    (stream through demandPipe(signal, pub)).attempt.evalMap {
      case Left(err) =>
        logger.error(s"$pub upstream stream has error [$err]")
        sub.onError(err)
        signal.set(Errored)
      case Right(a) =>
        logger.trace(s"$pub emitting element [$a]")
        sub.onNext(a)
        A.pure(())
    }.onFinalize(signal.get.map {
      case Cancelled =>
        logger.debug(s"$pub upstream stream terminated - cancellation received from downstream")
      case Errored =>
        logger.error(s"$pub upstream stream terminated - error occurred")
      case _ =>
        logger.debug(s"$pub upstream stream finished smoothly")
        sub.onComplete()
    }).run.unsafeRunAsync(_ => ())

    def cancel(): Unit = {
      logger.debug(s"$pub cancellation received from downstream")
      signal.set(Cancelled).unsafeRunAsync(_ => ())
    }
    def request(n: Long): Unit = {
      if(n == java.lang.Long.MAX_VALUE) {
        logger.debug(s"$pub received request for an infinite number of elements")
        signal.set(InfiniteRequests).unsafeRunAsync(_ => ())
      }
      else if(n > 0) {
        logger.debug(s"$pub received request for [$n] elements")
        signal.modify {
          case Idle =>
            logger.debug(s"$pub creating new request for [$n] elements")
            FiniteRequests(n, 0)
          case FiniteRequests(m, c) =>
            logger.debug(s"$pub adding [$n] to existing requests [$m].  Now requesting [${m + n}] elements")
            FiniteRequests(n, c + 1)
          case InfiniteRequests =>
            InfiniteRequests
          case Cancelled =>
            logger.error(s"$pub received request for [$n] elements when cancelled")
            Cancelled
          case Errored =>
            logger.error(s"$pub received request for [$n] elements when errorred")
            Errored
        }.unsafeRunAsync {
          case Left(err) => logger.error(s"$pub modification failed for [$n] elements")
          case Right(_) => ()
        }
      }
      else {
        logger.error(s"$pub received request for an invalid number of elements [$n]")
        sub.onError(new IllegalArgumentException("3.9 - invalid number of elements"))
        signal.set(Errored).unsafeRunAsync(_ => ())
      }
    }
  }

  def demandPipe[F[_], A](signal: Signal[F, State], pub: UnicastPublisher[_])(implicit AA: Async[F]): Pipe[F, A, A] = { s =>

    def go(ah: Handle[F, A], sh: Handle[F, State]): Pull[F, A, A] =
      sh.receive1 {
        case (Idle, sh) =>
          logger.trace(s"$pub idle - no processing necessary")
          go(ah, sh)
        case (InfiniteRequests, sh) =>
          logger.debug(s"$pub processing infinite requests")
          ah.awaitAsync.flatMap { af => sh.await1Async.flatMap { sf => goInfinite(af, sf) }}
        case (FiniteRequests(n, _), sh) =>
          logger.debug(s"$pub processing [$n] requests")
          ah.awaitAsync.flatMap { af => sh.await1Async.flatMap { sf => goFinite(af, sf, sh, n) }}
        case (Cancelled, _) =>
          logger.debug(s"$pub processing cancellation - terminating stream")
          Pull.done
        case (Errored, _) =>
          logger.debug(s"$pub processing downstream error - terminating stream")
          Pull.done
      }

    def goFinite(ah: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[A], Handle[F,A])]], sh: ScopedFuture[F, Pull[F, Nothing, (Option[State], Handle[F, State])]],
      shh: Handle[F, State],
      n: Long): Pull[F, A, A] =
      (ah race sh).pull.flatMap {
        case Left(ah) => ah.flatMap { case (as, ah) =>
          if(as.size.toLong < n) Pull.output(as) >> ah.awaitAsync.flatMap(goFinite(_, sh, shh, n - as.size.toLong))
          else if(as.size.toLong == n) Pull.output(as) >> go(ah, shh)
          else Pull.output(as.take(n.toInt)) >> go(ah.push(as.drop(n.toInt)), shh)
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
          case Some(Errored) =>
            logger.trace(s"$pub has received an error from downstream with [$n] requests remaining.")
            Pull.done
          case Some(Cancelled) =>
            logger.trace(s"$pub has received a cancellation from downstream with [$n] requests remaining.")
            Pull.done
          case Some(Idle) =>
            logger.error(s"$pub has invalid state!")
            sys.error("invalid state!")
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
          case (Some(Idle), _) =>
            logger.error(s"$pub Impossible state! Idle after processing an infinite number of requests")
            Pull.done
          case (Some(Cancelled), _) =>
            logger.debug(s"$pub processing cancellation - terminating stream")
            Pull.done
          case (Some(Errored), _) =>
            logger.debug(s"$pub processing downstream error - terminating stream")
            Pull.done
          case (None, _) =>
            logger.error(s"$pub impossible state! State signal stream has been terminated")
            Pull.done
        }
      }
    val o = signal.discrete.map { s =>
      logger.trace(s"$pub is emitting state $s")
      s
    }
    s.pull2(o)(go)
  }

  def unicastSubscription[F[_], A](sub: RSubscriber[A], stream: Stream[F, A], pub: UnicastPublisher[_])(implicit A: Async[F]): F[UnicastSubscription[F, A]] =
    async.signalOf[F, State](Idle).map { signal =>
      new UnicastSubscription(signal, sub, stream, pub)
    }
}
