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
import scala.concurrent.duration._

import org.reactivestreams.tck.SubscriberWhiteboxVerification.{SubscriberPuppet, WhiteboxSubscriberProbe}
import org.{reactivestreams => rs}
import com.typesafe.scalalogging.LazyLogging

class FailedSubscription(sub: rs.Subscriber[_]) extends rs.Subscription {
  def cancel(): Unit = {}
  def request(n: Long): Unit = {}
}
class FailedPublisher extends rs.Publisher[Int] {

  def subscribe(subscriber: rs.Subscriber[_ >: Int]): Unit = {
    subscriber.onSubscribe(new FailedSubscription(subscriber))
    subscriber.onError(new Error(""))
  }
}

class UnicastPublisherSpec extends PublisherVerification[Int](new TestEnvironment()) with TestNGSuiteLike with LazyLogging {

  implicit val S: Strategy = Strategy.fromFixedDaemonPool(1, "publisher-spec")

  def createPublisher(n: Long): UnicastPublisher[Int] = {
    val timestamp = System.nanoTime()
    val s: Stream[Task, Int] = if(n == java.lang.Long.MAX_VALUE) {
      Stream[Task, Int]((1 until 20): _*).repeat
    } else Stream[Task, Int](1).repeat.scan(1)(_ + _).map {
      i => if(i > n) None else {
        logger.trace(s"outputting $i of $n at $timestamp")
        Some(i)
      }
    }.unNoneTerminate
    val pub = reactive.toUnicastPublisher(s)
    logger.debug(s"$pub creating publisher for [$n] elements")
    pub
  }

  //impossible to fail lazy unicast publisher
  def createFailedPublisher(): FailedPublisher = new FailedPublisher()
}

class MulticastPublisherSpec extends PublisherVerification[Int](new TestEnvironment(1000)) with TestNGSuiteLike with LazyLogging {

  implicit val S: Strategy = Strategy.fromFixedDaemonPool(1, "publisher-spec")
  implicit val SS: Scheduler = Scheduler.fromFixedDaemonPool(1, "publisher-spec-scheduler")

  def createPublisher(n: Long): MulticastPublisher[Int] = {
    val timestamp = System.nanoTime()
    val els: Stream[Task, Int] = Stream.eval(Task.delay(()).schedule(100 milliseconds)) >> Stream[Task, Int](1).repeat.scan(1)(_ + _)
    val s: Stream[Task, Int] = if(n == java.lang.Long.MAX_VALUE) els else els.map { i => if(i > n) None else Some(i) }.unNoneTerminate

    val pub = reactive.toMulticastPublisher(s)
    logger.debug(s"$pub creating multicast publisher for [$n] elements")
    pub.run.unsafeRunAsync {
      case Left(err) => logger.error(s"$pub finished with error [$err]")
      case _ => logger.debug(s"$pub finished successfully")
    }
    pub
  }

  def createFailedPublisher(): MulticastPublisher[Int] = {
    val pub = reactive.toMulticastPublisher(Stream.eval(Task.delay[Int](throw new Error("BOOM!"))))
    pub.run.flatMap(_ => Task.delay(()).schedule(100 milliseconds)).unsafeRun()
    pub
  }
}
