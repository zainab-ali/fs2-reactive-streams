package fs2
package interop
package reactive

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams.{Subscriber => RSubscriber, Publisher => RPublisher, Subscription => RSubscription}
import com.typesafe.scalalogging.LazyLogging

/** An incomplete implementation of an org.reactivestreams.Publisher */
class Publisher[A](val s: Stream[Task, A], queueSize: Int)(implicit AA: Async[Task]) extends RPublisher[A] with LazyLogging {
  logger.debug("creating new publisher")

  def subscribe(subscriber: RSubscriber[_ >: A]): Unit = {
    val subscription = new Subscription(s, queueSize, subscriber)
    logger.debug("publisher has received subscriber")
    subscriber.onSubscribe(subscription)
  }

  def run: Unit = s.run.unsafeRunAsync(_ => ())
}

/** An incomplete implementation of an org.reactivestreams.Subscription */
class Subscription[A, AA >: A](s: Stream[Task, A], queueSize: Int, sub: RSubscriber[AA])(implicit AA: Async[Task]) extends RSubscription with LazyLogging {
  logger.debug(s"creating new subscription for subscriber [$sub]")

  private val requests: Queue[Task, Boolean] = async.boundedQueue[Task, Boolean](queueSize).unsafeRun()
  private val halt: Signal[Task, Boolean] = async.signalOf[Task, Boolean](false).unsafeRun()

  s.zip(requests.dequeueAvailable).interruptWhen(halt).flatMap {
    case (a, _) =>
      logger.trace(s"subscription providing an element")
      sub.onNext(a)
      Stream.emit(())
  }.run.unsafeRunAsync({
    case Right(_) =>
      logger.debug("subscription stream has finished.  Subscriber is complete")
      sub.onComplete()
    case Left(t) =>
      logger.error(s"received error from publisher [$t]")
      sub.onError(t)
  })
  
  def cancel(): Unit = {
    logger.debug("subscriber has cancelled subscription")
    AA.unsafeRunAsync(halt.set(true))(_ => ())
  }

  def request(i: Long): Unit = {
    logger.trace(s"subscription received request for [$i] elements")
    (0 until i.toInt).foreach { _ => requests.enqueue1(true).unsafeRunAsync(_ => ()) }
    logger.trace("subscription queued requests")
  }
}

