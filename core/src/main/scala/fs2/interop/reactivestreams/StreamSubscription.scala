package fs2
package interop
package reactivestreams

import cats.effect._
import cats.implicits._
import fs2._, async.mutable.{Signal, Queue}
import org.reactivestreams._

import scala.concurrent.ExecutionContext

/** Implementation of a org.reactivestreams.Subscription.
  *
  * This is used by the [[fs2.interop.reactivestreams.StreamUnicastPublisher]] to send elements from a Stream to a downstream reactivestreams system.
  *
  * @see https://github.com/reactive-streams/reactive-streams-jvm#3-subscription-code
  */
final class StreamSubscription[F[_], A](
  requests: Queue[F, StreamSubscription.Request],
  cancelled: Signal[F, Boolean],
  sub: Subscriber[A],
  stream: Stream[F, A]
)(implicit F: Effect[F], ec: ExecutionContext)
    extends Subscription {
  import StreamSubscription._

  def onError(e: Throwable) = F.delay(sub.onError(e)) *> cancelled.set(true)

  def unsafeStart(): Unit = {
    def subscriptionPipe: Pipe[F, A, A] =
      in => {
        def go(s: Stream[F, A]): Pull[F, A, Unit] =
          Pull.eval(requests.dequeue1).flatMap {
            case InfiniteRequests => s.pull.echo
            case FiniteRequests(n) =>
              s.pull.take(n).flatMap {
                case None => Pull.done
                case Some(rem) => go(rem)
              }
          }

        go(in).stream
      }

    // TODO what if the stream successfully completes just as `cancel` is called?
    // I guess that's unavoidable
    val s =
      stream
        .through(subscriptionPipe)
        .interruptWhen(cancelled)
        .evalMap(x => F.delay(sub.onNext(x)))
        .handleErrorWith(e => Stream.eval(onError(e)))
        .onFinalize {
          cancelled.get.ifM(
            ifTrue = F.unit,
            ifFalse = cancelled.set(true) *> F.delay(sub.onComplete)
          )
        }.compile.drain

    async.unsafeRunAsync(s)(_ => IO.unit)
  }

  def cancel(): Unit =
    async.unsafeRunAsync(cancelled.set(true))(_ => IO.unit)

  def request(n: Long): Unit = {
    val request =
      if (n == java.lang.Long.MAX_VALUE) InfiniteRequests.pure[F]
      else if (n > 0) FiniteRequests(n).pure[F]
      else F.raiseError(new IllegalArgumentException(s"3.9 - invalid number of elements [$n]"))

    val prog = cancelled.get
      .ifM(ifTrue = F.unit, ifFalse = request.flatMap(requests.enqueue1).handleErrorWith(onError))

    async.unsafeRunAsync(prog)(_ => IO.unit)
  }
}

object StreamSubscription {

  /** Represents a request to publish elements by a downstream subscriber */
  sealed trait Request

  /** The downstream reactivestreams subscriber has requested an infinite number of elements */
  case object InfiniteRequests extends Request

  /** The downstream subscriber has requested a finite number of elements.
    *
    * @param n the number of elements requested
    */
  case class FiniteRequests(n: Long) extends Request


  def apply[F[_]: Effect, A](sub: Subscriber[A], stream: Stream[F, A])(
    implicit ec: ExecutionContext
  ): F[StreamSubscription[F, A]] =
    async.signalOf[F, Boolean](false).flatMap { cancelled =>
      async.unboundedQueue[F, Request].map { requests =>
        new StreamSubscription(requests, cancelled, sub, stream)
      }
    }
}
