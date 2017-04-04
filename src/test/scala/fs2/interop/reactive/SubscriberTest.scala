package fs2
package interop
package reactive

import org.scalatest._
import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.tck.SubscriberWhiteboxVerification
import org.reactivestreams.tck.TestEnvironment
import java.util.concurrent.atomic.AtomicInteger
import org.testng.annotations._
import org.testng.Assert._

class SubscriberSpec extends SubscriberWhiteboxVerification[Int](new TestEnvironment()) with TestNGSuiteLike {
  implicit val S: Strategy = Strategy.fromFixedDaemonPool(1, "subscriber-spec")
  private val counter = new AtomicInteger()
  def createSubscriber(probe: SubscriberWhiteboxVerification.WhiteboxSubscriberProbe[Int]): Subscriber[Int] =
    reactive.subscriber[Int].unsafeRun()
  def createElement(i: Int): Int = counter.getAndIncrement
}
