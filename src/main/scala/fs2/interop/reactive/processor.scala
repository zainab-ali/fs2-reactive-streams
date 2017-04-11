package fs2
package interop
package reactive


import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.{reactivestreams => rs }
import com.typesafe.scalalogging.LazyLogging

final class Processor[A, B](p: Pipe[Task, A, B])(implicit AA: Async[Task]) extends rs.Processor[A, B] {

  val sub = subscriber[A]().unsafeRun
  val pub = toUnicastPublisher[B](sub.stream through p)

  def subscribe(subscriber: rs.Subscriber[_ >: B]): Unit = pub.subscribe(subscriber)
  def onSubscribe(s: rs.Subscription): Unit = sub.onSubscribe(s)
  def onNext(a: A): Unit = sub.onNext(a)
  def onComplete(): Unit = sub.onComplete()
  def onError(t: Throwable): Unit = sub.onError(t)
}
