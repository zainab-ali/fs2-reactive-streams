package fs2
package interop
package reactivestreams

import cats._
import cats.effect._
import cats.implicits._
import fs2.async.Ref
import org.reactivestreams._

import scala.concurrent.ExecutionContext

/** Implementation of a org.reactivestreams.Subscriber.
  *
  * This is used to obtain a Stream from an upstream reactivestreams system.
  *
  * @see https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code
  */
final class StreamSubscriber[F[_], A](val sub: StreamSubscriber.Queue[F, A])(implicit A: Effect[F],
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
    private[reactivestreams] def dequeue1: F[Either[Throwable, Option[A]]]

    /** downstream stream */
    def stream()(implicit A: Applicative[F]): Stream[F, A] =
      Stream.eval(dequeue1).repeat.rethrow.unNoneTerminate.onFinalize(onFinalize)
  }

  def queue[F[_], A]()(implicit F: Effect[F], ec: ExecutionContext): F[Queue[F, A]] = {

    /** Represents the state of the Queue */
    sealed trait State

    /** No requests have been made (the downstream [[fs2.Stream]] has not been pulled on) */
    case object Uninitialized extends State

    /** The first downstream request has been made, but a subscription has not been received from upstream.
      *
      *  @param req the first downstream request
      */
    case class FirstRequest(req: Ref[F, Either[Throwable, Option[A]]]) extends State

    /** The subscriber has requested an element from upstream, but not yet received it
      *
      * @param sub the subscription to upstream
      * @param req the request from downstream
      */
    case class PendingElement(sub: Subscription, req: Ref[F, Either[Throwable, Option[A]]])
        extends State

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

        def onSubscribe(s: Subscription): F[Unit] =
          qref
            .modify {
              case FirstRequest(req) =>
                PendingElement(s, req)
              case Uninitialized =>
                Idle(s)
              case o => o
            }
            .flatMap {
              _.previous match {
                case _: FirstRequest =>
                  F.pure(s.request(1))
                case Uninitialized =>
                  F.pure(())
                case o =>
                  F.pure(s.cancel()) *> F
                    .raiseError(new Error(s"received subscription in invalid state [$o]"))
              }
            }

        def onNext(a: A): F[Unit] =
          qref
            .modify {
              case PendingElement(s, r) =>
                Idle(s)
              case o =>
                o
            }
            .flatMap { c =>
              c.previous match {
                case PendingElement(s, r) =>
                  r.setAsyncPure(Right(Some(a)))
                case Cancelled =>
                  F.pure(())
                case o =>
                  F.raiseError(new Error(s"received record [$a] in invalid state [$o]"))
              }
            }

        def onComplete(): F[Unit] =
          qref
            .modify { _ =>
              Complete
            }
            .flatMap {
              _.previous match {
                case PendingElement(sub, r) =>
                  r.setAsyncPure(Right(None))
                case o =>
                  F.pure(())
              }
            }

        def onError(t: Throwable): F[Unit] =
          qref
            .modify { _ =>
              Errored(t)
            }
            .flatMap {
              _.previous match {
                case PendingElement(sub, r) =>
                  r.setAsyncPure(Left(t))
                case o =>
                  F.pure(())
              }
            }

        def onFinalize: F[Unit] =
          qref
            .modify {
              case PendingElement(_, _) | Idle(_) =>
                Cancelled
              case o =>
                o
            }
            .flatMap { o =>
              o.previous match {
                case PendingElement(sub, r) =>
                  F.pure(sub.cancel()) *> r.setAsyncPure(Right(None))
                case Idle(sub) =>
                  F.pure(sub.cancel())
                case o =>
                  F.pure(())
              }
            }

        def dequeue1: F[Either[Throwable, Option[A]]] =
          async.ref[F, Either[Throwable, Option[A]]].flatMap { r =>
            qref
              .modify {
                case Uninitialized =>
                  FirstRequest(r)
                case Idle(sub) =>
                  PendingElement(sub, r)
                case o => o
              }
              .flatMap(
                c =>
                  c.previous match {
                    case Uninitialized =>
                      r.get
                    case Idle(sub) =>
                      F.pure(sub.request(1)).flatMap(_ => r.get)
                    case Errored(err) =>
                      F.pure(Left(err))
                    case Complete =>
                      F.pure(Right(None))
                    case FirstRequest(_) | PendingElement(_, _) | Cancelled =>
                      F.pure(Left(new Error(s"received request in invalid state [${c.previous}]")))
                }
              )
          }
      }
    }
  }
}
