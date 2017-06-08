package fs2
package interop
package reactivestreams

import cats.effect._
import cats.implicits._
import fs2.async.mutable._
import org.reactivestreams._

import scala.concurrent.ExecutionContext

/** Implementation of a org.reactivestreams.Subscription.
  *
  * This is used by the [[fs2.interop.reactive.StreamUnicastPublisher]] to send elements from a Stream to a downstream reactivestreams system.
  * 
  * @see https://github.com/reactive-streams/reactive-streams-jvm#3-subscription-code
  */
final class StreamSubscription[F[_], A](requests: Queue[F, StreamSubscription.Request], sub: Subscriber[A], stream: Stream[F, A])(implicit A: Effect[F], ec: ExecutionContext) extends Subscription {
  import StreamSubscription._

  val fa = (stream through subscriptionPipe(requests.dequeueAvailable)).map { a =>
    sub.onNext(a)
  }.run

  async.unsafeRunAsync(fa) {
    case Left(Cancellation) => IO.unit
    case Left(InvalidNumber(n)) =>
      IO.pure(sub.onError(new IllegalArgumentException(s"3.9 - invalid number of elements [$n]")))
    case Left(err) =>
      IO.pure(sub.onError(err))
    case Right(_) =>
      IO.pure(sub.onComplete())
  }

  def cancel(): Unit = {
    async.unsafeRunAsync(requests.enqueue1(Cancelled))(_ => IO.unit)
  }

  def request(n: Long): Unit = {
    if(n == java.lang.Long.MAX_VALUE) {
      async.unsafeRunAsync(requests.enqueue1(InfiniteRequests))(_ => IO.unit)
    }
    else if(n > 0) {
      async.unsafeRunAsync(requests.enqueue1(FiniteRequests(n)))(_ => IO.unit)
    }
    else {
      async.unsafeRunAsync(requests.enqueue1(InvalidNumber(n)))(_ => IO.unit)
    }
  }
}

object StreamSubscription {

  /** Represents an operation by a downstream subscriber */
  sealed trait Request

  /** The downstream reactivestreams subscriber has requested an infinite number of elements */
  case object InfiniteRequests extends Request

  /** The downstream subscriber has requested a finite number of elements.
    *
    * @param n the number of elements requested
    */
  case class FiniteRequests(n: Long) extends Request

  /** The downstream subscriber has cancelled the subscription. */
  case object Cancelled extends Request

  /** Error for a downstream cancellation.  This distinguishes a cancellation from a normal completion. */
  case object Cancellation extends Throwable

  /** The downstream subscriber has requested an invalid number of elements.  This distinguishes a downstream error from an upstream error.
    *
    * @param n the number of elements requested.  This is zero or negative.
    */
  case class InvalidNumber(n: Long) extends Throwable with Request


  def apply[F[_], A](sub: Subscriber[A], stream: Stream[F, A])(implicit A: Effect[F], ec: ExecutionContext): F[StreamSubscription[F, A]] =
    async.unboundedQueue[F, Request].map { requests =>
      new StreamSubscription(requests, sub, stream)
    }

  def subscriptionPipe[F[_], A](state: Stream[F, Request])(implicit AA: Effect[F], ec: ExecutionContext): Pipe[F, A, A] = { s =>

    def go(ah: Handle[F, A], sh: Handle[F, Request]): Pull[F, A, A] =
      sh.receive1 {
        case (InfiniteRequests, sh) =>
          ah.awaitAsync.flatMap { af => sh.await1Async.flatMap { sf => goInfinite(af, sf) }}
        case (FiniteRequests(n), sh) =>
          ah.awaitAsync.flatMap { af => sh.await1Async.flatMap { sf => goFinite(af, sf, sh, n) }}
        case (Cancelled, _) =>
          Pull.fail(Cancellation)
        case (i: InvalidNumber, _) =>
          Pull.fail(i)
      }

    def goFinite(ah: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[A], Handle[F,A])]], sh: ScopedFuture[F, Pull[F, Nothing, (Request, Handle[F, Request])]],
      shh: Handle[F, Request],
      n: Long): Pull[F, A, A] =
      (ah race sh).pull.flatMap {
        case Left(ah) => ah.flatMap { case (as, ah) =>
          if(as.size.toLong < n) Pull.output(as) >> ah.awaitAsync.flatMap(goFinite(_, sh, shh, n - as.size.toLong))
          else if(as.size.toLong == n) Pull.output(as) >>
            sh.pull.flatMap { _.flatMap { case (s, sh) =>
                go(ah, sh.push1(s))
            }}
          else {
            Pull.output(as.take(n.toInt)) >>
            sh.pull.flatMap { _.flatMap { case (s, sh) =>
                go(ah.push(as.drop(n.toInt)), sh.push1(s))
            }}
          }
        }
        case Right(sh) => sh.flatMap { case (s, sh) => s match {
          case FiniteRequests(m) =>
            if(m + n > 0L) {
              sh.await1Async.flatMap { sf => goFinite(ah, sf, sh, m + n) }
            }
            else {
              sh.await1Async.flatMap { sf => goInfinite(ah, sf) }
            }
          case InfiniteRequests =>
            sh.await1Async.flatMap { sf => goInfinite(ah, sf) }
          case err @ InvalidNumber(i) =>
            Pull.fail(err)
          case Cancelled =>
            Pull.fail(Cancellation)
        } }
      }

    def goInfinite(ah: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[A], Handle[F,A])]], sh: ScopedFuture[F, Pull[F, Nothing, (Request, Handle[F, Request])]]): Pull[F, A, A] =
      (ah race sh).pull flatMap {
        case Left(ah) => ah.flatMap {
          case (as, ah) => Pull.output(as) >> ah.awaitAsync.flatMap(goInfinite(_, sh))
        }
        case Right(sh) => sh.flatMap {
          case (InfiniteRequests, sh) =>
            sh.await1Async.flatMap(goInfinite(ah, _))
          case (FiniteRequests(_), sh) =>
            sh.await1Async.flatMap(goInfinite(ah, _))
          case (Cancelled, _) =>
            Pull.fail(Cancellation)
          case (err @ InvalidNumber(i), _) =>
            Pull.fail(err)
        }
      }
    s.pull2(state)(go)
  }
}
