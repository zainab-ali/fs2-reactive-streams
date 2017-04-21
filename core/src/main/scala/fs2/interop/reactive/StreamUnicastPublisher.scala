package fs2
package interop
package reactive

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams._

/** Implementation of an org.reactivestreams.Publisher.
  *
  * This is used to publish elements from an fs2.Stream to a downstream reactivestreams system.
  * 
  * @see https://github.com/reactive-streams/reactive-streams-jvm#1-publisher-code
  *
  */
final class StreamUnicastPublisher[F[_], A](val s: Stream[F, A])(implicit AA: Async[F]) extends Publisher[A] {

  def subscribe(subscriber: Subscriber[_ >: A]): Unit = {
    nonNull(subscriber)
    StreamSubscription(subscriber, s).map { sub =>
      subscriber.onSubscribe(sub)
    }.unsafeRunAsync(_ => ())
  }

  private def nonNull[A](a: A): Unit = if(a == null) throw new NullPointerException()
}

object StreamUnicastPublisher {

  def apply[F[_], A](s: Stream[F, A])(implicit A: Async[F]): StreamUnicastPublisher[F, A] = new StreamUnicastPublisher(s)
}
