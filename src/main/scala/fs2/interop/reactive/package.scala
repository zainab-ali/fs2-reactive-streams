package fs2
package interop

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams.{Subscriber => RSubscriber, Publisher => RPublisher, Subscription => RSubscription}

package object reactive {

  /** produces a [[Subscriber]] */
  def subscriber[A]()(implicit AA: Async[Task]): Task[Subscriber[A]] = SubscriberQueue[A]().map(new Subscriber(_))

  /** produces a stream from an upstream publisher */
  def fromPublisher[A](p: RPublisher[A])(implicit AA: Async[Task]): Stream[Task, A] = Stream.eval(subscriber[A]().map { s =>
    p.subscribe(s)
    s
  }).flatMap(_.sub.stream)

  /** produces a unicast publisher from a stream */
  def toUnicastPublisher[A](s: Stream[Task, A])(implicit A: Async[Task]): UnicastPublisher[A] = new UnicastPublisher(s)
}
