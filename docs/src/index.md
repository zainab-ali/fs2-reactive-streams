## Fs2 Reactive

fs2 reactive is a reactive streams implementation for fs2.

## TL;DR


```tut:book
import fs2._
import fs2.interop.reactive._

implicit strategy: Strategy = Strategy.fromFixedDaemonPool(4, "worker")
val stream = Stream[Task, Int](1, 2, 3)

val publisher = stream.toUnicastPublisher
val subscriberStream = publisher.toStream[Task]
subscriberStream.runLog.unsafeRun()
```

## Why?

Reactive Streams spec is complicated, mutable and unsafe - it is not functional, and not something that is preferred.
But there are times when we need use fs2 in conjunction with a different streaming library, and this is where the reactive streams spec has its uses.

Any reactive streams system can interop with any other system by exposing an `org.reactivestreams.Publisher` or an `org.reactivestreams.Subscriber`.

This library provides instances of a reactivestreams compliant publisher and subscriber to ease interop with other streaming libraries.

 - To convert an upstream reactive streams system into an fs2 stream, expose an `fs2.interop.reactive.StreamSubscriber`.
 - To convert an fs2 stream into a downstream reactive streams system, expose an `fs2.interop.reactive.UnicastPublisher`.


## Examples

### Akka streams

```tut:book

import akka._
import akka.stream._
import akka.stream.scaladsl._
import akka.actor.ActorSystem

implicit val system = ActorSystem("akka-streams-example")
implicit val materializer = ActorMaterializer()

val source: Source[Int, NotUsed] = Source(1 to 100)

val sink: Sink[Int, Publisher[Int]] = Sink.asPublisher[Int](fanout = false)

val publisher: Publisher[Int] = source.runWith(sink)
val stream: Stream[Task, Int] = publisher.toStream[Task].runLog.unsafeRun
```

### Monix

### RxScala

