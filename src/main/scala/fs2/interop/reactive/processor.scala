package fs2
package interop
package reactive


import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.{reactivestreams => rs }
import com.typesafe.scalalogging.LazyLogging

final class Processor[A, B](p: Pipe[Task, A, B])(implicit AA: Async[Task]) extends rs.Processor[A, B] with LazyLogging {

  val sub = subscriber[A]().unsafeRun
  val pub = toUnicastPublisher[B](sub.stream through p)

  def subscribe(subscriber: rs.Subscriber[_ >: B]): Unit = {
    logger.info(s"$this is has received subscriber $subscriber with $pub")
    pub.subscribe(subscriber)
  }
  def onSubscribe(s: rs.Subscription): Unit = {
    logger.info(s"$this has received subscription $s forwarding to $sub")
    sub.onSubscribe(s)
  }
  def onNext(a: A): Unit = {
    logger.info(s"$this has received element $a forwarding to $sub")
    sub.onNext(a)
  }
  def onComplete(): Unit = {
    logger.info(s"$this has completed")
    sub.onComplete()
  }
  def onError(t: Throwable): Unit = {
    logger.info(s"$this has received error $t")
    sub.onError(t)
  }
}
