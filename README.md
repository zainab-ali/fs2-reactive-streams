# fs2-reactive-streams
A reactive streams implementation for [fs2](https://github.com/functional-streams-for-scala/fs2)

[![Build Status](https://travis-ci.org/zainab-ali/fs2-reactive-streams.svg?branch=master)](http://travis-ci.org/zainab-ali/fs2-reactive-streams)
[![codecov](https://codecov.io/gh/zainab-ali/fs2-reactive-streams/branch/master/graph/badge.svg)](https://codecov.io/gh/zainab-ali/fs2-reactive-streams)

## To use

Add the following to your `build.sbt`:

```scala
libraryDependencies += "com.github.zainab-ali" %% "fs2-reactive-streams" % "0.1.0"
```

## TL;DR


```scala
import fs2._
// import fs2._

import fs2.interop.reactivestreams._
// import fs2.interop.reactivestreams._

implicit val strategy: Strategy = Strategy.fromFixedDaemonPool(4, "worker")
// strategy: fs2.Strategy = Strategy

val upstream = Stream[Task, Int](1, 2, 3)
// upstream: fs2.Stream[fs2.Task,Int] = Segment(Emit(Chunk(1, 2, 3)))

val publisher = upstream.toUnicastPublisher
// publisher: fs2.interop.reactivestreams.StreamUnicastPublisher[fs2.Task,Int] = fs2.interop.reactivestreams.StreamUnicastPublisher@25e04d6f

val downstream = publisher.toStream[Task]
// downstream: fs2.Stream[fs2.Task,Int] = attemptEval(Task).flatMap(<function1>).flatMap(<function1>)

downstream.runLog.unsafeRun()
// res0: Vector[Int] = Vector(1, 2, 3)
```

## Why?

The [reactive streams initiative](http://www.reactive-streams.org/) is complicated, mutable and unsafe - it is not something that is desired for use over fs2.
But there are times when we need use fs2 in conjunction with a different streaming library, and this is where reactive streams shines.

Any reactive streams system can interop with any other reactive streams system by exposing an `org.reactivestreams.Publisher` or an `org.reactivestreams.Subscriber`.

This library provides instances of reactivestreams compliant publishers and subscribers to ease interop with other streaming libraries.

## Usage


To convert a `Stream` into a downstream unicast `org.reactivestreams.Publisher`:

```scala
val stream = Stream[Task, Int](1, 2, 3)
stream.toUnicastPublisher
```

To convert an upstream `org.reactivestreams.Publisher` into a `Stream`:

```scala
val publisher: org.reactivestreams.Publisher[Int] = Stream[Task, Int](1, 2, 3).toUnicastPublisher
publisher.toStream[Task]
```

A unicast publisher must have a single subscriber only.

## Example: Akka streams

Import the Akka streams dsl:

```scala
import akka._
import akka.stream._
import akka.stream.scaladsl._
import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext.Implicits.global

implicit val system = ActorSystem("akka-streams-example")
implicit val materializer = ActorMaterializer()
```

To convert from an `Source` to a `Stream`:

```scala
val source = Source(1 to 5)
// source: akka.stream.scaladsl.Source[Int,akka.NotUsed] = Source(SourceShape(StatefulMapConcat.out(1044284125)))

val publisher = source.runWith(Sink.asPublisher[Int](fanout = false))
// publisher: org.reactivestreams.Publisher[Int] = VirtualProcessor(state = Publisher[StatefulMapConcat.out(1044284125)])

val stream = publisher.toStream[Task]
// stream: fs2.Stream[fs2.Task,Int] = attemptEval(Task).flatMap(<function1>).flatMap(<function1>)

stream.runLog.unsafeRun()
// res4: Vector[Int] = Vector(1, 2, 3, 4, 5)
```

To convert from a `Stream` to a `Source`:

```scala
val stream = Stream.emits[Task, Int]((1 to 5).toSeq)
// stream: fs2.Stream[fs2.Task,Int] = Segment(Emit(Chunk(1, 2, 3, 4, 5)))

val source = Source.fromPublisher(stream.toUnicastPublisher)
// source: akka.stream.scaladsl.Source[Int,akka.NotUsed] = Source(SourceShape(PublisherSource.out(72060556)))

Task.fromFuture(source.runWith(Sink.seq[Int])).unsafeRun()
// res5: scala.collection.immutable.Seq[Int] = Vector(1, 2, 3, 4, 5)
```




## Licence

fs2-reactive-streams is licensed under the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).

## Credits

Many thanks go to [Ross Baker](https://github.com/rossabaker) who took the first step in making a reactive streams implementation in [http4s](https://github.com/http4s/http4s).  Without this, fs2-reactive-streams would have been much harder to write.
