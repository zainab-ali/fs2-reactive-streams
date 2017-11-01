package fs2
package interop
package reactivestreams

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import fs2.Stream._
import fs2.async._
import fs2.async.mutable._
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
  cancelled: Ref[F, Boolean],
  sub: Subscriber[A],
  stream: Stream[F, A]
)(implicit F: Effect[F], ec: ExecutionContext)
    extends Subscription {
  import StreamSubscription._

  def unsafeStart(): Unit =
    async.unsafeRunAsync {
      stream
        .through(subscriptionPipe(requests.dequeueAvailable))
        .map(sub.onNext)
        .run
    } {
      case Left(Cancellation) =>
        IO.unit
      case Left(InvalidNumber(n)) =>
        IO.pure(
          sub.onError(new IllegalArgumentException(s"3.9 - invalid number of elements [$n]"))
        )
      case Left(err) =>
        IO.pure(sub.onError(err))
      case Right(_) =>
        IO.pure(sub.onComplete())
    }

  def cancel(): Unit =
    F.runAsync(cancelled.setSyncPure(true) *> requests.enqueue1(Cancelled))(_ => IO.unit)
      .unsafeRunSync()

  def request(n: Long): Unit = {
    val request =
      if (n == java.lang.Long.MAX_VALUE) InfiniteRequests
      else if (n > 0) FiniteRequests(n)
      else InvalidNumber(n)
    F.runAsync(cancelled.get >>= (c => if (c) F.pure(()) else requests.enqueue1(request)))(
        _ => IO.unit
      )
      .unsafeRunSync
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

  def apply[F[_]: Effect, A](sub: Subscriber[A], stream: Stream[F, A])(
    implicit ec: ExecutionContext
  ): F[StreamSubscription[F, A]] =
    async.refOf[F, Boolean](false).flatMap { cancelled =>
      async.unboundedQueue[F, Request].map { requests =>
        new StreamSubscription(requests, cancelled, sub, stream)
      }
    }

  def subscriptionPipe[F[_]: Effect, A](
    requests: Stream[F, Request]
  )(implicit ec: ExecutionContext): Pipe[F, A, A] = {

    def go(
      aap: AsyncPull[F, Option[(Segment[A, Unit], Stream[F, A])]],
      rap: AsyncPull[F, Option[(Segment[Request, Unit], Stream[F, Request])]]
    ): Pull[F, A, Unit] =
      rap.pull.flatMap {
        case Some((requests, rs)) =>
          requests.uncons1 match {
            case Left(()) =>
              rs.pull.unconsAsync.flatMap(go(aap, _))
            case Right((request, rest)) =>
              request match {
                case InfiniteRequests =>
                  rs.cons(rest).pull.unconsAsync.flatMap(goInfinite(aap, _))
                case FiniteRequests(n) =>
                  rs.cons(rest).pull.unconsAsync.flatMap(goFinite(aap, _, n))
                case Cancelled =>
                  Pull.fail(Cancellation)
                case err @ InvalidNumber(_) =>
                  Pull.fail(err)
              }
          }
        case None =>
          Pull.done
      }

    def goFinite(aap: AsyncPull[F, Option[(Segment[A, Unit], Stream[F, A])]],
                 rap: AsyncPull[F, Option[(Segment[Request, Unit], Stream[F, Request])]],
                 n: Long): Pull[F, A, Unit] =
      (aap race rap).pull.flatMap {
        case Left(Some((segment, as))) =>
          Pull.segment(segment.take(n)).flatMap {
            case Left((_, rem)) =>
              as.pull.unconsAsync.flatMap(goFinite(_, rap, rem))
            case Right(rest) =>
              as.cons(rest).pull.unconsAsync.flatMap(go(_, rap))
          }

        case Right(Some((requests, rs))) =>
          requests.uncons1 match {
            case Left(()) =>
              rs.pull.unconsAsync.flatMap(goFinite(aap, _, n))
            case Right((request, rest)) =>
              val asyncPull = rs.cons(rest).pull.unconsAsync
              request match {
                case InfiniteRequests => asyncPull.flatMap(goInfinite(aap, _))
                case FiniteRequests(m) if m + n > 0L => asyncPull.flatMap(goFinite(aap, _, m + n))
                case FiniteRequests(_) => asyncPull.flatMap(goInfinite(aap, _))
                case Cancelled => Pull.fail(Cancellation)
                case err @ InvalidNumber(_) => Pull.fail(err)
              }
          }

        case Left(None) | Right(None) =>
          Pull.done
      }

    def goInfinite(
      aap: AsyncPull[F, Option[(Segment[A, Unit], Stream[F, A])]],
      rap: AsyncPull[F, Option[(Segment[Request, Unit], Stream[F, Request])]]
    ): Pull[F, A, Unit] =
      (aap race rap).pull.flatMap {
        case Left(Some((segment, as))) =>
          Pull.output(segment) *> as.pull.unconsAsync.flatMap(goInfinite(_, rap))

        case Right(Some((requests, rs))) =>
          requests.uncons1 match {
            case Left(()) => rs.pull.unconsAsync.flatMap(goInfinite(aap, _))
            case Right((request, rest)) =>
              request match {
                case InfiniteRequests | FiniteRequests(_) =>
                  rs.cons(rest).pull.unconsAsync.flatMap(goInfinite(aap, _))
                case Cancelled => Pull.fail(Cancellation)
                case err @ InvalidNumber(_) => Pull.fail(err)
              }
          }

        case Left(None) | Right(None) =>
          Pull.done
      }

    _.pull.unconsAsync.flatMap { aap =>
      requests.pull.unconsAsync.flatMap { rap =>
        go(aap, rap)
      }
    }.stream
  }
}
