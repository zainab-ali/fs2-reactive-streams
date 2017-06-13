package fs2
package interop

import cats.effect._
import cats.implicits._
import org.reactivestreams._

import scala.concurrent.ExecutionContext

package object reactivestreams {

  /** Creates a lazy stream from an org.reactivestreams.Publisher.
    *
    * The publisher only receives a subscriber when the stream is run.
    */
  def fromPublisher[F[_], A](p: Publisher[A])(implicit A: Effect[F], ec: ExecutionContext): Stream[F, A] = Stream.eval(StreamSubscriber[F, A]().map { s =>
    p.subscribe(s)
    s
  }).flatMap(_.sub.stream)


  implicit final class PublisherOps[A](val pub: Publisher[A]) extends AnyVal {

    /** Creates a lazy stream from an org.reactivestreams.Publisher */
    def toStream[F[_]]()(implicit A: Effect[F], ec: ExecutionContext): Stream[F, A] = fromPublisher(pub)
  }

  implicit final class StreamOps[F[_], A](val stream: Stream[F, A]) {

    /** Creates a [[fs2.interop.reactive.StreamUnicastPublisher]] from a stream.
      *
      * This publisher can only have a single subscription.
      * The stream is only ran when elements are requested.
      */
    def toUnicastPublisher()(implicit A: Effect[F], ec: ExecutionContext): StreamUnicastPublisher[F, A] = StreamUnicastPublisher(stream)
  }
}
