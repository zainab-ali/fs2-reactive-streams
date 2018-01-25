import org.reactivestreams._
import fs2.interop.reactivestreams._
import scala.concurrent.ExecutionContext.Implicits.global
import fs2._
import cats.effect._

object t {
  case class T() extends Subscriber[Int] {
    def onNext(t: Int) = println(s"just got $t")
    def onComplete() = println("completed")
    def onError(e: Throwable) = println(s"failed with $e")
    def onSubscribe(s: Subscription) = ()
  }

  val st = Stream(1, 2, 3, 4).covary[IO]

  val d = T()
  def sub = StreamSubscription(d, st).unsafeRunSync
}
