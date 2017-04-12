package fs2
package interop

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._
import org.reactivestreams._

package object reactive {

  def fromPublisher[A](p: Publisher[A])(implicit A: Async[Task]): Stream[Task, A] = Stream.eval(StreamSubscriber[A]().map { s =>
    p.subscribe(s)
    s
  }).flatMap(_.sub.stream)

  implicit final class PublisherOps[A](val pub: Publisher[A]) extends AnyVal {
    def toStream()(implicit A: Async[Task]): Stream[Task, A] = fromPublisher(pub)
  }

  implicit final class StreamOps[A](val stream: Stream[Task, A]) extends AnyVal {
    def toUnicastPublisher()(implicit A: Async[Task]): StreamUnicastPublisher[A] = StreamUnicastPublisher(stream)
  }
}
