package fs2
package interop

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._
import org.reactivestreams._

package object reactive {

  def fromPublisher[F[_], A](p: Publisher[A])(implicit A: Async[F]): Stream[F, A] = Stream.eval(StreamSubscriber[F, A]().map { s =>
    p.subscribe(s)
    s
  }).flatMap(_.sub.stream)

  implicit final class PublisherOps[A](val pub: Publisher[A]) extends AnyVal {
    def toStream[F[_]]()(implicit A: Async[F]): Stream[F, A] = fromPublisher(pub)
  }

  implicit final class StreamOps[F[_], A](val stream: Stream[F, A]) extends AnyVal {
    def toUnicastPublisher()(implicit A: Async[F]): StreamUnicastPublisher[F, A] = StreamUnicastPublisher(stream)
  }
}
