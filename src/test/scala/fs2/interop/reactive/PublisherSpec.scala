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
import com.typesafe.scalalogging.LazyLogging

class PublisherSpec extends PublisherVerification[Int](new TestEnvironment()) with TestNGSuiteLike with LazyLogging {

  implicit val S: Strategy = Strategy.fromFixedDaemonPool(1, "publisher-spec")

  def createPublisher(n: Long): Publisher[Int] = {
    val timestamp = System.nanoTime()
    val s: Stream[Task, Int] = if(n == java.lang.Long.MAX_VALUE) {
      Stream[Task, Int]((1 until 20): _*).repeat
    } else Stream[Task, Int](1).repeat.scan(1)(_ + _).map {
      i => if(i > n) {
        logger.info(s"finished outputting [$n] elements")
        None
      } else {
        logger.info(s"outputting [$i] of [$n] at [$timestamp]")
        Some(i)
      }
    }.unNoneTerminate
    reactive.toPublisher(s, 100)
  }

  def createFailedPublisher(): Publisher[Int] =
    reactive.toPublisher(Stream.eval(Task.fail(new Error("BOOM"))), 100)
}

class UnicastPublisherSpec extends PublisherVerification[Int](new TestEnvironment()) with TestNGSuiteLike with LazyLogging {

  implicit val S: Strategy = Strategy.fromFixedDaemonPool(1, "publisher-spec")

  def createPublisher(n: Long): UnicastPublisher[Int] = {
    val timestamp = System.nanoTime()
    val s: Stream[Task, Int] = if(n == java.lang.Long.MAX_VALUE) {
      Stream[Task, Int]((1 until 20): _*).repeat
    } else Stream[Task, Int](1).repeat.scan(1)(_ + _).map {
      i => if(i > n) None else {
        logger.info(s"outputting $i of $n at $timestamp")
        Some(i)
      }
    }.unNoneTerminate
    val pub = reactive.toUnicastPublisher(s)
    logger.debug(s"$pub creating publisher for [$n] elements")
    pub
  }

  def createFailedPublisher(): UnicastPublisher[Int] =
    reactive.toUnicastPublisher(Stream.eval(Task.fail(new Error("BOOM"))))
}
