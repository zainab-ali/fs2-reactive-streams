package fs2
package interop
package reactivestreams

import cats._
import cats.effect._
import cats.implicits._
import fs2.async.Ref
import fs2.util._
import org.reactivestreams._

import scala.concurrent.ExecutionContext

/** Implementation of a org.reactivestreams.Subscriber.
  * 
  * This is used to obtain a Stream from an upstream reactivestreams system.
  * 
  * @see https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code
  */
final class StreamSubscriber[F[_], A](val sub: StreamSubscriber.Queue[F, A])(implicit A: Effect[F], ec: ExecutionContext) extends Subscriber[A] {

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

  private def nonNull[A](a: A): Unit = if(a == null) throw new NullPointerException()
}

object StreamSubscriber {

  def apply[F[_], A]()(implicit AA: Effect[F], ec: ExecutionContext): F[StreamSubscriber[F, A]] =
    queue[F, A]().map(new StreamSubscriber(_))

  /** A single element queue representing the subscriber */
  trait Queue[F[_], A] {

    /** receives a subscription from upstream */
    private[reactivestreams] def onSubscribe(s: Subscription): F[Unit]

    /** receives next record from upstream */
    private[reactivestreams] def onNext(a: A): F[Unit]

    /** receives error from upstream */
    private[reactivestreams] def onError(t: Throwable): F[Unit]
    
    /** called when upstream has finished sending records */
    private[reactivestreams] def onComplete: F[Unit]

    /** called when downstream has finished consuming records */
    private[reactivestreams] def onFinalize: F[Unit]

    /** producer for downstream */
    private[reactivestreams] def dequeue1: F[Attempt[Option[A]]]

    /** downstream stream */
    def stream()(implicit A: Applicative[F]): Stream[F, A] =
      Stream.eval(dequeue1).repeat.through(pipe.rethrow).unNoneTerminate.onFinalize(onFinalize)
  }


  def queue[F[_], A]()(implicit AA: Effect[F], ec: ExecutionContext): F[Queue[F, A]] = {

    /** Represents the state of the Queue */
    sealed trait State

    /** No requests have been made (the downstream [[fs2.Stream]] has not been pulled on) */
    case object Uninitialized extends State

    /** The first downstream request has been made, but a subscription has not been received from upstream.
      * 
      *  @param req the first downstream request
      */
    case class FirstRequest(req: Ref[F, Attempt[Option[A]]]) extends State

    /** The subscriber has requested an element from upstream, but not yet received it
      * 
      * @param sub the subscription to upstream
      * @param req the request from downstream
      */
    case class PendingElement(sub: Subscription, req: Ref[F, Attempt[Option[A]]]) extends State

    /** No downstream requests are open and a subscription has been received.
      * 
      * @param sub the subscription to upstream
      */
    case class Idle(sub: Subscription) extends State

    /** The upstream publisher has completed successfully */
    case object Complete extends State

    /** Downstream finished before upstream completed.  The subscription has been cancelled. */
    case object Cancelled extends State

    /** An error was received from upstream */
    case class Errored(err: Throwable) extends State

    async.refOf[F, State](Uninitialized).map { qref =>
      new Queue[F, A] {

        def onSubscribe(s: Subscription): F[Unit] = qref.modify {
          case FirstRequest(req) =>
            PendingElement(s, req)
          case Uninitialized =>
            Idle(s)
          case o => o
        }.flatMap { _.previous match {
          case _ : FirstRequest =>
            AA.pure(s.request(1))
          case Uninitialized =>
            AA.pure(())
          case o =>
            AA.pure(s.cancel()) >> AA.raiseError(new Error(s"received subscription in invalid state [$o]"))
        }}

        def onNext(a: A): F[Unit] = qref.modify {
          case PendingElement(s, r) =>
            Idle(s)
          case o =>
            o
        }.flatMap { c => c.previous match {
          case PendingElement(s, r) =>
            r.setAsyncPure(Attempt.success(Some(a)))
          case Cancelled =>
            AA.pure(())
          case o =>
            AA.raiseError(new Error(s"received record [$a] in invalid state [$o]"))
        }}

        def onComplete(): F[Unit] = qref.modify {
          case _ =>
            Complete
        }.flatMap { _.previous match {
          case PendingElement(sub, r) =>
            r.setAsyncPure(Attempt.success(None))
          case o =>
            AA.pure(())
        }}

        def onError(t: Throwable): F[Unit] = qref.modify {
          case _ =>
            Errored(t)
        }.flatMap { _.previous match {
          case PendingElement(sub, r) =>
            r.setAsyncPure(Attempt.failure(t))
          case o =>
            AA.pure(())
        }}


        def onFinalize: F[Unit] = qref.modify {
          case PendingElement(_, _) | Idle(_) =>
            Cancelled
          case o =>
            o
        }.flatMap { o =>
          o.previous match {
          case PendingElement(sub, r) =>
            AA.pure {
              sub.cancel()
            } >> r.setAsyncPure(Attempt.success(None))
          case Idle(sub) =>
            AA.pure {
              sub.cancel()
            }
          case o =>
            AA.pure(())
        }}


        def dequeue1: F[Attempt[Option[A]]] = async.ref[F, Attempt[Option[A]]].flatMap { r =>
          qref.modify {
            case Uninitialized =>
              FirstRequest(r)
            case Idle(sub) =>
              PendingElement(sub, r)
            case o => o
          }.flatMap(c => c.previous match {
            case Uninitialized =>
              r.get
            case Idle(sub) =>
              AA.pure(sub.request(1)).flatMap( _ => r.get)
            case Errored(err) =>
              AA.pure(Attempt.failure(err))
            case Complete =>
              AA.pure(Attempt.success(None))
            case FirstRequest(_) | PendingElement(_, _) | Cancelled =>
              AA.pure(Attempt.failure(new Error(s"received request in invalid state [${c.previous}]")))
          })
        }
      }
    }
  }
}
