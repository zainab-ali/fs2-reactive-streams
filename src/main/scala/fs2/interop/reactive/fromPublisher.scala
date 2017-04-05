package fs2
package interop
package reactive

import fs2.util._
import fs2.util.syntax._
import fs2.async.mutable._

import org.reactivestreams.{Subscriber => RSubscriber, Publisher => RPublisher, Subscription => RSubscription}
import com.typesafe.scalalogging.LazyLogging

/** An implementation of a org.reactivestreams.Subscriber */
final class Subscriber[A](val sub: SubscriberQueue[Task, A]) extends RSubscriber[A] {
  def onSubscribe(s: RSubscription): Unit = {
    nonNull(s)
    sub.onSubscribe(s).unsafeRunAsync(_ => ())
  }
  def onNext(a: A): Unit = {
    nonNull(a)
    sub.onNext(a).unsafeRun()
  }
  def onComplete(): Unit = sub.onComplete.unsafeRun()
  def onError(t: Throwable): Unit = {
    nonNull(t)
    sub.onError(t).unsafeRunAsync(_ => ())
  }

  def stream: Stream[Task, A] = sub.stream

  private def nonNull[A](a: A): Unit = if(a == null) throw new NullPointerException()
}

/** Dequeues from an upstream publisher */
trait SubscriberQueue[F[_], A] {

  /** receives a subscription from upstream */
  def onSubscribe(s: RSubscription): F[Unit]

  /** receives next record from upstream */
  def onNext(a: A): F[Unit]

  /** receives error from upstream */
  def onError(t: Throwable): F[Unit] 
  
  /** called when upstream has finished sending records */
  def onComplete: F[Unit] 

  /** called when downstream has finished consuming records */
  def onFinalize: F[Unit] 

  /** producer for downstream */
  def dequeue1: F[Attempt[Option[A]]]

  /** downstream stream */
  def stream()(implicit A: Applicative[F]): Stream[F, A] = 
    Stream.eval(dequeue1).repeat.through(pipe.rethrow).unNoneTerminate.onFinalize(onFinalize)
}

object SubscriberQueue extends LazyLogging {


  //TODO: This needs to be tested against <a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.0/README.md#specification">reactive specification</a>

  def apply[A]()(implicit AA: Async[Task]): Task[SubscriberQueue[Task, A]] = {

    /** Represents the state of the SubscriberQueue */
    sealed trait State

    /** No downstream requests have been made (the downstream stream has not been pulled on) */
    case object Uninitialized extends State

    /** The first downstream request has been made, but there is no subscription yet
      * 
      *  @req the first downstream request
      */
    case class FirstRequest(req: Async.Ref[Task, Attempt[Option[A]]]) extends State

    /** The subscriber has requested an element from upstream, but not yet received it
      * 
      * @sub the subscription to upstream
      * @req the request from downstream
      */
    case class PendingElement(sub: RSubscription, req: Async.Ref[Task, Attempt[Option[A]]]) extends State

    /** No downstream requests are open
      * 
      * @sub the subscription to upstream
      */
    case class Idle(sub: RSubscription) extends State

    /** The upstream publisher has completed successfully */
    case object Complete extends State

    /** Downstream finished before upstream completed.  The subscriber cancelled the subscription. */
    case object Cancelled extends State

    /** An error was received from upstream */
    case class Errored(err: Throwable) extends State

    AA.refOf[State](Uninitialized).map { qref =>
      new SubscriberQueue[Task, A] {

        def onSubscribe(s: RSubscription): Task[Unit] = qref.modify {
          case FirstRequest(req) =>
              logger.info(s"$this received subscription after request")
            PendingElement(s, req)
          case Uninitialized =>
              logger.info(s"$this received subscription when uninitialized")
            Idle(s)
          case o =>
              logger.info(s"$this received subscription in state $o")
            o
        }.flatMap { _.previous match {
          case _ : FirstRequest => 
            AA.delay(s.request(1))
          case Uninitialized =>
            AA.pure(())
          case o => 
            AA.delay(s.cancel()) >> AA.fail(new Error(s"received subscription in invalid state [$o]"))
        }}

        def onNext(a: A): Task[Unit] = qref.modify {
          case PendingElement(s, r) =>
            Idle(s)
          case o =>
            o
        }.flatMap { c => c.previous match {
          case PendingElement(s, r) => r.setPure(Attempt.success(Some(a)))
          case Cancelled => AA.pure(())
          case o => 
            //AA.fail(new Error(s"received record [$a] in invalid state [$o]"))
            AA.pure(())
        }}

        def onComplete(): Task[Unit] = qref.modify {
          case _ => 
            Complete
        }.flatMap { _.previous match {
          case PendingElement(sub, r) =>
            r.setPure(Attempt.success(None))
          case o => AA.pure(())
        }}

        def onError(t: Throwable): Task[Unit] = qref.modify {
          case _ => 
            Errored(t)
        }.flatMap { _.previous match {
          case PendingElement(sub, r) => r.setPure(Attempt.failure(t))
          case o => AA.pure(())
        }}


        def onFinalize: Task[Unit] = qref.modify {
          case PendingElement(_, _) | Idle(_) => 
            Cancelled
          case o => 
            o
        }.flatMap { _.previous match {
          case PendingElement(sub, r) => AA.delay {
            sub.cancel()
          } >> r.setPure(Attempt.success(None))
          case Idle(sub) => AA.delay {
            sub.cancel()
          }
          case o => AA.pure(())
        }}


        def dequeue1: Task[Attempt[Option[A]]] = AA.ref[Attempt[Option[A]]].flatMap { r =>
          qref.modify {
            case Uninitialized =>
              logger.info(s"$this received request when uninitialised")
              FirstRequest(r)
            case Idle(sub) =>
              logger.info(s"$this received request when idle")
              PendingElement(sub, r)
            case o => o
          }.flatMap(c => c.previous match {
            case Uninitialized => r.get
            case Idle(sub) =>
              AA.delay(sub.request(1)).flatMap( _ => r.get)
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
