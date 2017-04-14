package fs2
package interop
package reactive

import org.scalatest._
import org.scalatest.prop._

class PublisherToSubscriberSpec extends FlatSpec with Matchers with PropertyChecks {

  implicit val S: Strategy = Strategy.fromFixedDaemonPool(1, "publisher-spec")

  it should "have the same output as input" in { forAll { (ints: Seq[Int]) =>

    val subscriberStream = Stream.emits[Task, Int](ints).toUnicastPublisher.toStream[Task]

    subscriberStream.runLog.unsafeRun() should === (ints.toVector)
  }}

  object TestError extends Exception("BOOM")

  it should "propagate errors downstream" in {
    val input: Stream[Task, Int] = Stream(1, 2, 3) ++ Stream.fail(TestError)
    val output: Stream[Task, Int] = input.toUnicastPublisher.toStream[Task]

    output.run.unsafeAttemptRun() should === (Left(TestError))
  }

  it should "cancel upstream if downstream completes" in { forAll { (as: Seq[Int], bs: Seq[Int]) =>

    val subscriberStream = Stream.emits[Task, Int](as ++ bs).toUnicastPublisher.toStream[Task].take(as.size)
    subscriberStream.runLog.unsafeRun() should === (as.toVector)
  }}
}
