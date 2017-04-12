package fs2
package interop
package reactive

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams._
import com.typesafe.scalalogging.LazyLogging

class StreamUnicastPublisher[A](val s: Stream[Task, A])(implicit AA: Async[Task]) extends Publisher[A] with LazyLogging {
  logger.debug(s"$this creating new publisher")

  def subscribe(subscriber: Subscriber[_ >: A]): Unit = {
    val subscription = StreamSubscription(subscriber, s, this.toString).unsafeRun()
    logger.debug(s"$this publisher has received subscriber")
    subscriber.onSubscribe(subscription)
  }
}

object StreamUnicastPublisher {

  def apply[A](s: Stream[Task, A])(implicit A: Async[Task]): StreamUnicastPublisher[A] = new StreamUnicastPublisher(s)
}
