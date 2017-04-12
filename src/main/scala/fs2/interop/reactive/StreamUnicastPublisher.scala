package fs2
package interop
package reactive

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams._
import org.log4s._

class StreamUnicastPublisher[A](val s: Stream[Task, A])(implicit AA: Async[Task]) extends Publisher[A] {

  private[this] val logger = getLogger

  def subscribe(subscriber: Subscriber[_ >: A]): Unit = {
    logger.debug(s"$this publisher has received subscriber")
    val subscription = StreamSubscription(subscriber, s, this.toString).unsafeRun()
    subscriber.onSubscribe(subscription)
  }
}

object StreamUnicastPublisher {

  def apply[A](s: Stream[Task, A])(implicit A: Async[Task]): StreamUnicastPublisher[A] = new StreamUnicastPublisher(s)
}
