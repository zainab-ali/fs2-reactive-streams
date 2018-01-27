package fs2
package interop
package reactivestreams

import cats._
import cats.effect._
import cats.implicits._
import fs2.async.{Promise, Ref}
import org.reactivestreams._

import scala.concurrent.ExecutionContext

/** Implementation of a org.reactivestreams.Subscriber.
  *
  * This is used to obtain a Stream from an upstream reactivestreams system.
  *
  * @see https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code
  */
final class StreamSubscriber[F[_], A](val sub: StreamSubscriber.FSM[F, A])(implicit A: Effect[F],
                                                                           ec: ExecutionContext)
    extends Subscriber[A] {

  /** Called by an upstream reactivestreams system */
  def onSubscribe(s: Subscription): Unit = {
    nonNull(s)
    async.unsafeRunAsync(sub.onSubscribe(s))(_ => IO.unit)
  }

  /** Called by an upstream reactivestreams system */
  def onNext(a: A): Unit = {
    nonNull(a)
    async.unsafeRunAsync(sub.onNext(a))(_ => IO.unit)
  }

  /** Called by an upstream reactivestreams system */
  def onComplete(): Unit = async.unsafeRunAsync(sub.onComplete)(_ => IO.unit)

  /** Called by an upstream reactivestreams system */
  def onError(t: Throwable): Unit = {
    nonNull(t)
    async.unsafeRunAsync(sub.onError(t))(_ => IO.unit)
  }

  /** Obtain a Stream */
  def stream: Stream[F, A] = sub.stream

  private def nonNull[A](a: A): Unit = if (a == null) throw new NullPointerException()
}

object StreamSubscriber {
  def apply[F[_], A]()(implicit AA: Effect[F], ec: ExecutionContext): F[StreamSubscriber[F, A]] =
    fsm[F, A].map(new StreamSubscriber(_))

  /** A finite state machine descriving the subscriber */
  private[reactivestreams] trait FSM[F[_], A] {

    /** receives a subscription from upstream */
    def onSubscribe(s: Subscription): F[Unit]

    /** receives next record from upstream */
    def onNext(a: A): F[Unit]

    /** receives error from upstream */
    def onError(t: Throwable): F[Unit]

    /** called when upstream has finished sending records */
    def onComplete: F[Unit]

    /** called when downstream has finished consuming records */
    def onFinalize: F[Unit]

    /** producer for downstream */
    def dequeue1: F[Either[Throwable, Option[A]]]

    /** downstream stream */
    def stream()(implicit A: Applicative[F]): Stream[F, A] =
      Stream.eval(dequeue1).repeat.rethrow.unNoneTerminate.onFinalize(onFinalize)
  }

  private[reactivestreams] def fsm[F[_], A](implicit F: Effect[F],
                                            ec: ExecutionContext): F[FSM[F, A]] = {

    type Out = Either[Throwable, Option[A]]

    sealed trait Input
    case class OnSubscribe(s: Subscription) extends Input
    case class OnNext(a: A) extends Input
    case class OnError(e: Throwable) extends Input
    case object OnComplete extends Input
    case object OnFinalize extends Input
    case class OnDequeue(response: Promise[F, Out]) extends Input

    sealed trait State

    /** No requests have been made (the downstream [[fs2.Stream]] has not been pulled on) */
    case object Uninitialized extends State

    /** No downstream requests are open and a subscription has been received. */
    case class Idle(sub: Subscription) extends State

    /** The first downstream request has been made, but a subscription has not been received from upstream */
    case class FirstRequest(req: Promise[F, Out]) extends State

    /** The subscriber has requested an element from upstream, but not yet received it */
    case class PendingElement(sub: Subscription, req: Promise[F, Out]) extends State

    /** The upstream publisher has completed successfully */
    case object Complete extends State

    /** Downstream finished before upstream completed.  The subscription has been cancelled. */
    case object Cancelled extends State

    /** An error was received from upstream */
    case class Errored(err: Throwable) extends State

    def step(in: Input): State => (State, F[Unit]) = in match {
      case OnSubscribe(s) => {
        case FirstRequest(req) => PendingElement(s, req) -> F.delay(s.request(1))
        case Uninitialized => Idle(s) -> F.unit
        case o =>
          val err = new Error(s"received subscription in invalid state [$o]")
          o -> F.delay(s.cancel) *> F.raiseError(err)
      }
      case OnNext(a) => {
        case PendingElement(s, r) => Idle(s) -> r.complete(a.some.asRight)
        case Cancelled => Cancelled -> F.unit
        case o => o -> F.raiseError(new Error(s"received record [$a] in invalid state [$o]"))
      }
      case OnComplete => {
        case PendingElement(sub, r) => Complete -> r.complete(None.asRight)
        case o => Complete -> F.unit
      }
      case OnError(e) => {
        case PendingElement(sub, r) => Errored(e) -> r.complete(e.asLeft)
        case o => Errored(e) -> F.unit
      }
      case OnFinalize => {
        case PendingElement(sub, r) =>
          Cancelled -> (F.delay(sub.cancel) *> r.complete(None.asRight))
        case Idle(sub) => Cancelled -> F.delay(sub.cancel)
        case o => o -> F.unit
      }
      case OnDequeue(r) => {
        case Uninitialized => FirstRequest(r) -> F.unit
        case Idle(sub) => PendingElement(sub, r) -> F.delay(sub.request(1))
        case Errored(e) => Errored(e) -> r.complete(e.asLeft)
        case Complete => Complete -> r.complete(None.asRight)
        case o => o -> r.complete((new Error(s"received request in invalid state [$o]")).asLeft)
      }
    }

    async.refOf[F, State](Uninitialized).map { ref =>
      new FSM[F, A] {
        def nextState(in: Input): F[Unit] = ref.modify2(step(in)).flatMap(_._2)
        def onSubscribe(s: Subscription): F[Unit] = nextState(OnSubscribe(s))
        def onNext(a: A): F[Unit] = nextState(OnNext(a))
        def onError(t: Throwable): F[Unit] = nextState(OnError(t))
        def onComplete: F[Unit] = nextState(OnComplete)
        def onFinalize: F[Unit] = nextState(OnFinalize)
        def dequeue1: F[Either[Throwable, Option[A]]] =
          async.promise[F, Out].flatMap { p =>
            ref.modify2(step(OnDequeue(p))).flatMap(_._2) *> p.get
          }
      }
    }
  }

}
