package fs2
package interop
package reactive

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams._
import org.log4s._

class StreamUnicastPublisher[F[_], A](val s: Stream[F, A])(implicit AA: Async[F]) extends Publisher[A] {

  private[this] val logger = getLogger(classOf[StreamUnicastPublisher[F, A]])

  def subscribe(subscriber: Subscriber[_ >: A]): Unit = {
    nonNull(subscriber)
    logger.debug(s"$this publisher has received subscriber")
    StreamSubscription(subscriber, s, this.toString).map { sub =>
      subscriber.onSubscribe(sub)
    }.unsafeRunAsync(_ => ())
  }

  private def nonNull[A](a: A): Unit = if(a == null) throw new NullPointerException()
}

object StreamUnicastPublisher {

  def apply[F[_], A](s: Stream[F, A])(implicit A: Async[F]): StreamUnicastPublisher[F, A] = new StreamUnicastPublisher(s)
}
