package fs2
package interop
package reactivestreams

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import fs2.Stream._
import fs2.async.mutable._
import org.reactivestreams._

import scala.concurrent.ExecutionContext

/** Implementation of a org.reactivestreams.Subscription.
  *
  * This is used by the [[fs2.interop.reactive.StreamUnicastPublisher]] to send elements from a Stream to a downstream reactivestreams system.
  *
  * @see https://github.com/reactive-streams/reactive-streams-jvm#3-subscription-code
  */
final class StreamSubscription[F[_]: Effect, A](requests: Queue[F, StreamSubscription.Request],
                                                sub: Subscriber[A],
                                                stream: Stream[F, A])
                                               (implicit ec: ExecutionContext) extends Subscription {
  import StreamSubscription._

  async.unsafeRunAsync {
    stream
      .through(subscriptionPipe(requests.dequeueAvailable))
      .map(sub.onNext)
      .run
  } {
    case Left(Cancellation) =>
      IO.unit
    case Left(InvalidNumber(n)) =>
      IO.pure(sub.onError(new IllegalArgumentException(s"3.9 - invalid number of elements [$n]")))
    case Left(err) =>
      IO.pure(sub.onError(err))
    case Right(_) =>
      IO.pure(sub.onComplete())
  }

  def cancel(): Unit =
    async.unsafeRunAsync(requests.enqueue1(Cancelled))(_ => IO.unit)

  def request(n: Long): Unit = {
    val request =
      if (n == java.lang.Long.MAX_VALUE) InfiniteRequests
      else if (n > 0) FiniteRequests(n)
      else InvalidNumber(n)
    async.unsafeRunAsync(requests.enqueue1(request))(_ => IO.unit)
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

  def apply[F[_]: Effect, A](sub: Subscriber[A], stream: Stream[F, A])(implicit ec: ExecutionContext): F[StreamSubscription[F, A]] =
    async.unboundedQueue[F, Request].map { requests =>
      new StreamSubscription(requests, sub, stream)
    }

  def subscriptionPipe[F[_]: Effect, A](requests: Stream[F, Request])(implicit ec: ExecutionContext): Pipe[F, A, A] = {

    def go(as: Stream[F, A],
           rs: Stream[F, Request]): Pull[F, A, Unit] =
      rs.pull.uncons1.flatMap {
        case None =>
          Pull.done
        case Some((request, rs)) => request match {
          case InfiniteRequests =>
            as.pull.unconsAsync.flatMap(aap => rs.pull.unconsAsync.flatMap(rap => goInfinite(aap, rap)))

          case FiniteRequests(n) =>
            as.pull.unconsAsync.flatMap(aap => rs.pull.unconsAsync.flatMap(rap => goFinite(aap, rap, rs, n)))

          case Cancelled              => Pull.fail(Cancellation)
          case err @ InvalidNumber(_) => Pull.fail(err)
        }
      }

    def goFinite(aap: AsyncPull[F, Option[(Segment[A, Unit], Stream[F, A])]],
                 rap: AsyncPull[F, Option[(Segment[Request, Unit], Stream[F, Request])]],
                 rs: Stream[F, Request],
                 n: Long): Pull[F, A, Unit] =
      (aap race rap).pull.flatMap {
        case Left(Some((segment, as))) =>
          Pull.segment(segment.take(n)).flatMap {
            case Left((_, rem)) =>
              as.pull.unconsAsync.flatMap(goFinite(_, rap, rs, rem))
            case Right(rest) =>
              go(as.cons(rest), rs)
          }

        case Right(Some((requests, rs))) =>
          requests.uncons1 match {
            case Left(()) =>
              Pull.done
            case Right((request, rest)) =>
              val asyncPull = rs.cons(rest).pull.unconsAsync
              request match {
                case InfiniteRequests                => asyncPull.flatMap(goInfinite(aap, _))
                case FiniteRequests(m) if m + n > 0L => asyncPull.flatMap(goFinite(aap, _, rs, m + n))
                case FiniteRequests(_)               => asyncPull.flatMap(goInfinite(aap, _))
                case Cancelled                       => Pull.fail(Cancellation)
                case err@InvalidNumber(_)            => Pull.fail(err)
              }
          }

        case Left(None) | Right(None) =>
          Pull.done
      }

    def goInfinite(aap: AsyncPull[F, Option[(Segment[A, Unit], Stream[F, A])]],
                   rap: AsyncPull[F, Option[(Segment[Request, Unit], Stream[F, Request])]]): Pull[F, A, Unit] =
      (aap race rap).pull.flatMap {
        case Left(Some((segment, as))) =>
          Pull.output(segment) >> as.pull.unconsAsync.flatMap(goInfinite(_, rap))

        case Right(Some((requests, rs))) =>
          requests.uncons1 match {
            case Left(()) =>
              Pull.done
            case Right((request, rest)) => request match {
              case InfiniteRequests | FiniteRequests(_) => rs.cons(rest).pull.unconsAsync.flatMap(goInfinite(aap, _))
              case Cancelled                            => Pull.fail(Cancellation)
              case err @ InvalidNumber(_)               => Pull.fail(err)
            }
          }

        case Left(None) | Right(None) =>
          Pull.done
      }

    stream => go(stream, requests).stream
  }
}
