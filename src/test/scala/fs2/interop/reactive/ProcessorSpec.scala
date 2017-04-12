package fs2
package interop
package reactive

import org.scalatest._
import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.tck.IdentityProcessorVerification
import org.reactivestreams.tck.TestEnvironment
import java.util.concurrent.atomic.AtomicInteger
import org.testng.annotations._
import org.testng.Assert._

import org.reactivestreams.tck.SubscriberWhiteboxVerification.{SubscriberPuppet, WhiteboxSubscriberProbe}
import org.{reactivestreams => rs}
import com.typesafe.scalalogging.LazyLogging
import java.util.concurrent.Executors;

class ProcessorSpec extends IdentityProcessorVerification[Int](new TestEnvironment()) with TestNGSuiteLike {

  implicit val S: Strategy = Strategy.fromFixedDaemonPool(4, "identity-processor-spec")
  val e = Executors.newFixedThreadPool(4);
  def createElement(el: Int): Int = el

  def createFailedPublisher(): org.reactivestreams.Publisher[Int] = null
  def publisherExecutorService(): java.util.concurrent.ExecutorService = e

  override def createIdentityProcessor(i: Int): rs.Processor[Int, Int] = new Processor[Int, Int](identity)
}
