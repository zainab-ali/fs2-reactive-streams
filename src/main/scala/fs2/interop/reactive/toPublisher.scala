package fs2
package interop
package reactive

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams.{Subscriber => RSubscriber, Publisher => RPublisher, Subscription => RSubscription}
import com.typesafe.scalalogging.LazyLogging

// /** An incomplete implementation of an org.reactivestreams.Publisher */
// class Publisher[A](val s: Stream[Task, A], queueSize: Int)(implicit AA: Async[Task]) extends RPublisher[A] with LazyLogging {
//   logger.debug("creating new publisher")

//   def subscribe(subscriber: RSubscriber[_ >: A]): Unit = {
//     val subscription = new Subscription(s, queueSize, subscriber)
//     logger.debug("publisher has received subscriber")
//     subscriber.onSubscribe(subscription)
//   }

//   def run: Unit = s.run.unsafeRunAsync(_ => ())
// }

class UnicastPublisher[A](val s: Stream[Task, A])(implicit AA: Async[Task]) extends RPublisher[A] with LazyLogging {
  logger.debug(s"$this creating new publisher")

  def subscribe(subscriber: RSubscriber[_ >: A]): Unit = {
    val subscription = UnicastPublisher.unicastSubscription(subscriber, s, this).unsafeRun()
    logger.debug(s"$this publisher has received subscriber")
    subscriber.onSubscribe(subscription)
  }
}
//TODO: the subscriber can request an infinite number of elements
/*
 Could have another FSM
 Finite(sub, stream, n)
 Infinite(sub, stream)
 Cancelled(sub, stream)
 Errored(sub, stream)
 Complete(sub, stream)

 I can set this with a Task each time
 So it's a signal
 Which pulls on a queue on request
 queue.dequeue

 We join the stream with the current value of the signal
 So if the signal has a state 

 How do we interface with a stream?

 get match {
   case Infinite(ss) => h.receiveOption(...) Pull.eval(...) output
   case ``
 }
 */

object UnicastPublisher extends LazyLogging {

  sealed trait State
  case object Idle extends State
  case object InfiniteRequests extends State
  case class FiniteRequests(n: Long) extends State
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
            FiniteRequests(n)
          case FiniteRequests(m) =>
            if(m + n > 0) {
              logger.debug(s"$pub adding to existing requests.  Now requesting [$n] elements")
              FiniteRequests(m + n)
            } else { //overflow
              logger.info(s"$pub adding to existing requests to result in an infinite requests.")
              InfiniteRequests
            }
          case InfiniteRequests => InfiniteRequests
          case Cancelled => Cancelled
          case Errored => Errored
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
          ah.receive { case (as, ah) => Pull.output(as) >> go(ah, sh) }
        case (FiniteRequests(n), sh) =>
          logger.debug(s"$pub processing [$n] requests")
          val m = if(n > java.lang.Integer.MAX_VALUE.toLong) {
            logger.debug(s"$pub processing maximium int value")
            java.lang.Integer.MAX_VALUE
          } else n.toInt
          ah.awaitLimit(m).flatMap {
            case (as, ah) =>
              logger.debug(s"$pub processed [${as.size}] of [$n] requests")
              val p = if(as.size.toLong >= n) Pull.eval(signal.set(Idle)) else Pull.eval(signal.set(FiniteRequests(n - as.size.toLong)))
              p >> Pull.output(as) >> go(ah, sh)
          }
        case (Cancelled, _) =>
          logger.debug(s"$pub processing cancellation - terminating stream")
          Pull.done
        case (Errored, _) =>
          logger.debug(s"$pub processing downstream error - terminating stream")
          Pull.done
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
          case (Some(FiniteRequests(_)), sh) =>
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
    s.pull2(signal.discrete)(go)
  }

  def unicastSubscription[F[_], A](sub: RSubscriber[A], stream: Stream[F, A], pub: UnicastPublisher[_])(implicit A: Async[F]): F[UnicastSubscription[F, A]] =
    async.signalOf[F, State](Idle).map { signal =>
      new UnicastSubscription(signal, sub, stream, pub)
    }
}

// /** An incomplete implementation of an org.reactivestreams.Subscription */
// class Subscription[A, AA >: A](s: Stream[Task, A], queueSize: Int, sub: RSubscriber[AA])(implicit AA: Async[Task]) extends RSubscription with LazyLogging {
//   logger.debug(s"creating new subscription for subscriber [$sub]")

//   private val requests: Queue[Task, Boolean] = async.boundedQueue[Task, Boolean](queueSize).unsafeRun()
//   private val halt: Signal[Task, Boolean] = async.signalOf[Task, Boolean](false).unsafeRun()

//   s.zip(requests.dequeueAvailable).interruptWhen(halt).flatMap {
//     case (a, _) =>
//       logger.trace(s"subscription providing an element")
//       sub.onNext(a)
//       Stream.emit(())
//   }.run.unsafeRunAsync({
//     case Right(_) =>
//       logger.debug("subscription stream has finished.  Subscriber is complete")
//       sub.onComplete()
//     case Left(t) =>
//       logger.error(s"received error from publisher [$t]")
//       sub.onError(t)
//   })
  
//   def cancel(): Unit = {
//     logger.debug("subscriber has cancelled subscription")
//     AA.unsafeRunAsync(halt.set(true))(_ => ())
//   }

//   def request(i: Long): Unit = {

//     if(i <= 0) sub.onError(new IllegalArgumentException("invalid argument 3.9"))
//     else {
//       logger.trace(s"subscription received request for [$i] elements")
//         (0 until i.toInt).foreach { _ => requests.enqueue1(true).unsafeRunAsync(_ => ()) }
//       logger.trace("subscription queued requests")
//     }
//   }
// }

