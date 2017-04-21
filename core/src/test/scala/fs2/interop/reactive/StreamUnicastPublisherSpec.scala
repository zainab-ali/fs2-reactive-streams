package fs2
package interop
package reactive

import org.scalatest._
import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.tck.PublisherVerification
import org.reactivestreams.tck.TestEnvironment
import java.util.concurrent.atomic.AtomicInteger
import org.testng.annotations._
import org.testng.Assert._

import org.reactivestreams.tck.SubscriberWhiteboxVerification.{SubscriberPuppet, WhiteboxSubscriberProbe}
import org.reactivestreams._

class FailedSubscription(sub: Subscriber[_]) extends Subscription {
  def cancel(): Unit = {}
  def request(n: Long): Unit = {}
}

class FailedPublisher extends Publisher[Int] {

  def subscribe(subscriber: Subscriber[_ >: Int]): Unit = {
    subscriber.onSubscribe(new FailedSubscription(subscriber))
    subscriber.onError(new Error("BOOM"))
  }
}

class StreamUnicastPublisherSpec extends PublisherVerification[Int](new TestEnvironment(1000L)) with TestNGSuiteLike {

  implicit val S: Strategy = Strategy.fromFixedDaemonPool(1, "publisher-spec")

  def createPublisher(n: Long): StreamUnicastPublisher[Task, Int] = {
    val timestamp = System.nanoTime()
    val s: Stream[Task, Int] = if(n == java.lang.Long.MAX_VALUE) {
      Stream[Task, Int]((1 until 20): _*).repeat
    } else Stream[Task, Int](1).repeat.scan(1)(_ + _).map {
      i => if(i > n) None else Some(i)
    }.unNoneTerminate
    s.toUnicastPublisher()
  }

  def createFailedPublisher(): FailedPublisher = new FailedPublisher()
}
