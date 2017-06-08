package fs2
package interop
package reactivestreams


import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import cats.effect._
import org.reactivestreams._
import org.reactivestreams.tck.SubscriberWhiteboxVerification.{SubscriberPuppet, WhiteboxSubscriberProbe}
import org.reactivestreams.tck.{SubscriberBlackboxVerification, SubscriberWhiteboxVerification, TestEnvironment}
import org.scalatest.testng.TestNGSuiteLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class SubscriberWhiteboxSpec extends SubscriberWhiteboxVerification[Int](new TestEnvironment(1000L)) with TestNGSuiteLike {
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1))
  private val counter = new AtomicInteger()
  def createSubscriber(p: SubscriberWhiteboxVerification.WhiteboxSubscriberProbe[Int]): Subscriber[Int] =
    StreamSubscriber[IO, Int]().map { s =>
      new WhiteboxSubscriber(s, p)
    }.unsafeRunSync()

  def createElement(i: Int): Int = counter.getAndIncrement
}


final class WhiteboxSubscriber[A](sub: StreamSubscriber[IO, A],
  probe: WhiteboxSubscriberProbe[A]) extends Subscriber[A] {

  def onError(t: Throwable): Unit = {
    sub.onError(t)
    probe.registerOnError(t)
  }

  def onSubscribe(s: Subscription): Unit = {
    sub.onSubscribe(s)
    probe.registerOnSubscribe(new SubscriberPuppet {
      override def triggerRequest(elements: Long): Unit = {
        (0 to elements.toInt).foldLeft(IO.unit)((t, _) => t.flatMap( _ => sub.sub.dequeue1.map(_ => ()))).unsafeRunAsync(_ => ())
      }

      override def signalCancel(): Unit = {
        s.cancel()
      }
    })
  }

  def onComplete(): Unit = {
    sub.onComplete()
    probe.registerOnComplete()
  }

  def onNext(a: A): Unit = {
    sub.onNext(a)
    probe.registerOnNext(a)
  }
}

class SubscriberBlackboxSpec extends SubscriberBlackboxVerification[Int](new TestEnvironment(1000L)) with TestNGSuiteLike {

  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1))
  val scheduler: Scheduler = Scheduler.fromFixedDaemonPool(2, "subscriber-blackbox-spec-scheduler")
  private val counter = new AtomicInteger()
  def createSubscriber(): StreamSubscriber[IO, Int] = StreamSubscriber[IO, Int]().unsafeRunSync()

  override def triggerRequest(s: Subscriber[_ >: Int]): Unit = {
    val req = s.asInstanceOf[StreamSubscriber[IO, Int]].sub.dequeue1
    scheduler.scheduleOnce(100 milliseconds)(req.unsafeRunAsync(_ => ()))
  }

  def createElement(i: Int): Int = counter.incrementAndGet()
}
