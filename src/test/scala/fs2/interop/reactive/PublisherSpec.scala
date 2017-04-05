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
import org.{reactivestreams => rs}

class PublisherSpec extends PublisherVerification[Int](new TestEnvironment()) with TestNGSuiteLike {

  implicit val S: Strategy = Strategy.fromFixedDaemonPool(1, "publisher-spec")
  def createPublisher(n: Long): Publisher[Int] = if(n == java.lang.Long.MAX_VALUE)
    reactive.toPublisher(Stream.repeatEval(Task.now(1)), 10)
  else reactive.toPublisher(Stream[Task, Int]((0 until n.toInt):_*), 10)

  def createFailedPublisher(): Publisher[Int] =
    reactive.toPublisher(Stream.eval(Task.fail(new Error("BOOM"))), 10)
}
