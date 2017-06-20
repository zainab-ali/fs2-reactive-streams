package fs2
package interop
package reactivestreams

import java.util.concurrent.Executors

import cats.effect._
import org.scalatest._
import org.scalatest.prop._

import scala.concurrent.ExecutionContext

class PublisherToSubscriberSpec extends FlatSpec with Matchers with PropertyChecks {

  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  it should "have the same output as input" in { forAll { (ints: Seq[Int]) =>
    val subscriberStream = Stream.emits(ints).covary[IO].toUnicastPublisher.toStream[IO]

    subscriberStream.runLog.unsafeRunSync() should === (ints.toVector)
  }}

  object TestError extends Exception("BOOM")

  it should "propagate errors downstream" in {
    val input: Stream[IO, Int] = Stream(1, 2, 3) ++ Stream.fail(TestError)
    val output: Stream[IO, Int] = input.toUnicastPublisher.toStream[IO]

    output.run.attempt.unsafeRunSync() should === (Left(TestError))
  }

  it should "cancel upstream if downstream completes" in { forAll { (as: Seq[Int], bs: Seq[Int]) =>
    val subscriberStream = Stream.emits(as ++ bs).covary[IO].toUnicastPublisher.toStream[IO].take(as.size)

    subscriberStream.runLog.unsafeRunSync() should === (as.toVector)
  }}
}
