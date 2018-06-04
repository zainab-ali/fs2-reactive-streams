package fs2
package interop
package reactivestreams

import cats.effect._
import cats.implicits._
import org.reactivestreams._

import scala.concurrent.ExecutionContext

/** Implementation of an org.reactivestreams.Publisher.
  *
  * This is used to publish elements from an fs2.Stream to a downstream reactivestreams system.
  *
  * @see https://github.com/reactive-streams/reactive-streams-jvm#1-publisher-code
  *
  */
final class StreamUnicastPublisher[F[_]: ConcurrentEffect, A](
  val s: Stream[F, A]
)(implicit timer: Timer[F])
    extends Publisher[A] {

  def subscribe(subscriber: Subscriber[_ >: A]): Unit = {
    nonNull(subscriber)
    async.unsafeRunAsync {
      StreamSubscription(subscriber, s).map { subscription =>
        subscriber.onSubscribe(subscription)
        subscription
      }
    } {
      case Left(err) => IO(err.printStackTrace())
      case Right(subscription) => IO(subscription.unsafeStart)
    }
  }

  private def nonNull[A](a: A): Unit = if (a == null) throw new NullPointerException()
}

object StreamUnicastPublisher {
  def apply[F[_]: ConcurrentEffect, A](
    s: Stream[F, A]
  )(implicit timer: Timer[F]): StreamUnicastPublisher[F, A] =
    new StreamUnicastPublisher(s)
}
