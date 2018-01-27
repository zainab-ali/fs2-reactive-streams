package fs2
package interop
package reactivestreams

import java.util.concurrent.atomic.AtomicInteger

import cats.effect._
import org.reactivestreams._
import org.reactivestreams.tck.SubscriberWhiteboxVerification.{
  SubscriberPuppet,
  WhiteboxSubscriberProbe
}
import org.reactivestreams.tck.{
  SubscriberBlackboxVerification,
  SubscriberWhiteboxVerification,
  TestEnvironment
}
import org.scalatest.testng.TestNGSuiteLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class SubscriberWhiteboxSpec
    extends SubscriberWhiteboxVerification[Int](new TestEnvironment(1000L))
    with TestNGSuiteLike {
  implicit val ec: ExecutionContext = ExecutionContext.global
  private val counter = new AtomicInteger()

  def createSubscriber(
    p: SubscriberWhiteboxVerification.WhiteboxSubscriberProbe[Int]
  ): Subscriber[Int] =
    StreamSubscriber[IO, Int]
      .map { s =>
        new WhiteboxSubscriber(s, p)
      }
      .unsafeRunSync()

  def createElement(i: Int): Int = counter.getAndIncrement
}

final class WhiteboxSubscriber[A](sub: StreamSubscriber[IO, A], probe: WhiteboxSubscriberProbe[A])
    extends Subscriber[A] {

  def onError(t: Throwable): Unit = {
    sub.onError(t)
    probe.registerOnError(t)
  }

  def onSubscribe(s: Subscription): Unit = {
    sub.onSubscribe(s)
    probe.registerOnSubscribe(new SubscriberPuppet {
      override def triggerRequest(elements: Long): Unit = {
        (0 to elements.toInt)
          .foldLeft(IO.unit)((t, _) => t.flatMap(_ => sub.sub.dequeue1.map(_ => ())))
          .unsafeRunAsync(_ => ())
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

class SubscriberBlackboxSpec
    extends SubscriberBlackboxVerification[Int](new TestEnvironment(1000L))
    with TestNGSuiteLike {
  implicit val ec: ExecutionContext = ExecutionContext.global

  val (scheduler: Scheduler, _) =
    Scheduler
      .allocate[IO](corePoolSize = 2, threadPrefix = "subscriber-blackbox-spec-scheduler")
      .unsafeRunSync()

  private val counter = new AtomicInteger()

  def createSubscriber(): StreamSubscriber[IO, Int] = StreamSubscriber[IO, Int].unsafeRunSync()

  override def triggerRequest(s: Subscriber[_ >: Int]): Unit = {
    val req = s.asInstanceOf[StreamSubscriber[IO, Int]].sub.dequeue1
    (scheduler.sleep_[IO](100 milliseconds) ++ Stream.eval(req)).compile.drain.unsafeRunAsync(_ => ())
  }

  def createElement(i: Int): Int = counter.incrementAndGet()
}
