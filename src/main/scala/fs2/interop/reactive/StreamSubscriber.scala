package fs2
package interop
package reactive

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams._
import org.log4s._

/** Implementation of a org.reactivestreams.Subscriber.
  *
  * @see https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code
  * This is used to obtain a Stream from an upstream reactivestreams system.
  */
final class StreamSubscriber[F[_], A](val sub: StreamSubscriber.Queue[F, A])(implicit A: Async[F]) extends Subscriber[A] {

  /** Called by an upstream reactivestreams system */
  def onSubscribe(s: Subscription): Unit = {
    nonNull(s)
    sub.onSubscribe(s).unsafeRunAsync(_ => ())
  }

  /** Called by an upstream reactivestreams system */
  def onNext(a: A): Unit = {
    nonNull(a)
    sub.onNext(a).unsafeRunAsync(_ => ())
  }

  /** Called by an upstream reactivestreams system */
  def onComplete(): Unit = sub.onComplete.unsafeRunAsync(_ => ())

  /** Called by an upstream reactivestreams system */
  def onError(t: Throwable): Unit = {
    nonNull(t)
    sub.onError(t).unsafeRunAsync(_ => ())
  }

  /** Obtain a Stream */
  def stream: Stream[F, A] = sub.stream

  private def nonNull[A](a: A): Unit = if(a == null) throw new NullPointerException()
}

object StreamSubscriber {

  def apply[F[_], A]()(implicit AA: Async[F]): F[StreamSubscriber[F, A]] = queue[F, A]().map(new StreamSubscriber(_))

  private[this] val logger = getLogger

  /** A single element queue representing the subscriber */
  trait Queue[F[_], A] {

    /** receives a subscription from upstream */
    private[reactive] def onSubscribe(s: Subscription): F[Unit]

    /** receives next record from upstream */
    private[reactive] def onNext(a: A): F[Unit]

    /** receives error from upstream */
    private[reactive] def onError(t: Throwable): F[Unit]
    
    /** called when upstream has finished sending records */
    private[reactive] def onComplete: F[Unit]

    /** called when downstream has finished consuming records */
    private[reactive] def onFinalize: F[Unit]

    /** producer for downstream */
    private[reactive] def dequeue1: F[Attempt[Option[A]]]

    /** downstream stream */
    def stream()(implicit A: Applicative[F]): Stream[F, A] =
      Stream.eval(dequeue1).repeat.through(pipe.rethrow).unNoneTerminate.onFinalize(onFinalize)
  }


  def queue[F[_], A]()(implicit AA: Async[F]): F[Queue[F, A]] = {

    /** Represents the state of the Queue */
    sealed trait State

    /** No requests have been made (the downstream [[fs2.Stream]] has not been pulled on) */
    case object Uninitialized extends State

    /** The first downstream request has been made, but a subscription has not been received from upstream.
      * 
      *  @param req the first downstream request
      */
    case class FirstRequest(req: Async.Ref[F, Attempt[Option[A]]]) extends State

    /** The subscriber has requested an element from upstream, but not yet received it
      * 
      * @param sub the subscription to upstream
      * @param req the request from downstream
      */
    case class PendingElement(sub: Subscription, req: Async.Ref[F, Attempt[Option[A]]]) extends State

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

    AA.refOf[State](Uninitialized).map { qref =>
      new Queue[F, A] {

        def onSubscribe(s: Subscription): F[Unit] = qref.modify {
          case FirstRequest(req) =>
            PendingElement(s, req)
          case Uninitialized =>
            Idle(s)
          case o => o
        }.flatMap { _.previous match {
          case _ : FirstRequest =>
            logger.info(s"$this received subscription after request")
            AA.pure(s.request(1))
          case Uninitialized =>
            logger.info(s"$this received subscription when uninitialized")
            AA.pure(())
          case o =>
            logger.info(s"$this received subscription in invalid state [$o]")
            AA.pure(s.cancel()) >> AA.fail(new Error(s"received subscription in invalid state [$o]"))
        }}

        def onNext(a: A): F[Unit] = qref.modify {
          case PendingElement(s, r) =>
            Idle(s)
          case o =>
            o
        }.flatMap { c => c.previous match {
          case PendingElement(s, r) =>
            logger.trace(s"$this delivering next element [$a]")
            r.setPure(Attempt.success(Some(a)))
          case Cancelled =>
            logger.trace(s"$this received element [$a] after cancellation")
            AA.pure(())
          case o =>
            logger.error(s"$this received record [$a] in invalid state [$o]")
            AA.fail(new Error(s"received record [$a] in invalid state [$o]"))
        }}

        def onComplete(): F[Unit] = qref.modify {
          case _ =>
            Complete
        }.flatMap { _.previous match {
          case PendingElement(sub, r) =>
            logger.info(s"$this completed while waiting for elements")
            r.setPure(Attempt.success(None))
          case o =>
            logger.info(s"$this completed in state [$o]")
            AA.pure(())
        }}

        def onError(t: Throwable): F[Unit] = qref.modify {
          case _ =>
            Errored(t)
        }.flatMap { _.previous match {
          case PendingElement(sub, r) =>
            logger.warn(s"$this received error from upstream [$t]")
            r.setPure(Attempt.failure(t))
          case o =>
            logger.warn(s"$this received error from upstream [$t]")
            AA.pure(())
        }}


        def onFinalize: F[Unit] = qref.modify {
          case PendingElement(_, _) | Idle(_) =>
            Cancelled
          case o =>
            o
        }.flatMap { o =>
          logger.info(s"$this cancelled from downstream in state [${o.previous}]")
          o.previous match {
          case PendingElement(sub, r) =>
            AA.pure {
              sub.cancel()
            } >> r.setPure(Attempt.success(None))
          case Idle(sub) =>
            AA.pure {
              sub.cancel()
            }
          case o =>
            AA.pure(())
        }}


        def dequeue1: F[Attempt[Option[A]]] = AA.ref[Attempt[Option[A]]].flatMap { r =>
          qref.modify {
            case Uninitialized =>
              FirstRequest(r)
            case Idle(sub) =>
              PendingElement(sub, r)
            case o => o
          }.flatMap(c => c.previous match {
            case Uninitialized =>
              logger.info(s"$this received request when uninitialised")
              r.get
            case Idle(sub) =>
              logger.info(s"$this received request after subscription [$sub]")
              AA.pure(sub.request(1)).flatMap( _ => r.get)
            case Errored(err) =>
              logger.warn(s"$this received error from upstream [$err]")
              AA.pure(Attempt.failure(err))
            case Complete =>
              logger.info(s"$this upstream completed")
              AA.pure(Attempt.success(None))
            case FirstRequest(_) | PendingElement(_, _) | Cancelled =>
              logger.error(s"$this received request in invalid state [${c.previous}]")
              AA.pure(Attempt.failure(new Error(s"received request in invalid state [${c.previous}]")))
          })
        }
      }
    }
  }
}
