# fs2-reactive
A reactive streams implementation for fs2

[![Build Status](https://travis-ci.org/to-ithaca/fs2-reactive.svg?branch=master)](http://travis-ci.org/to-ithaca/fs2-reactive)
[![codecov](https://codecov.io/gh/to-ithaca/fs2-reactive/branch/master/graph/badge.svg)](https://codecov.io/gh/to-ithaca/fs2-reactive)

## To use

Add the following to your `build.sbt`:

## TL;DR


```tut:book
import fs2._
import fs2.interop.reactive._

implicit val strategy: Strategy = Strategy.fromFixedDaemonPool(4, "worker")

val upstream = Stream[Task, Int](1, 2, 3)
val publisher = upstream.toUnicastPublisher
val downstream = publisher.toStream[Task]
downstream.runLog.unsafeRun()
```

## Why?

The reactive streams protocol is complicated, mutable and unsafe - it is not something that is desired for use over fs2.
But there are times when we need use fs2 in conjunction with a different streaming library, and this is where reactive streams shines.

Any reactive streams system can interop with any other reactive streams system by exposing an `org.reactivestreams.Publisher` or an `org.reactivestreams.Subscriber`.

This library provides instances of reactivestreams compliant publishers and subscribers to ease interop with other streaming libraries.

## Usage


To convert a `Stream` into a downstream unicast `org.reactivestreams.Publisher`:

```tut:silent
val stream = Stream[Task, Int](1, 2, 3)
stream.toUnicastPublisher
```

To convert an upstream `org.reactivestreams.Publisher` into a `Stream`:

```tut:silent
val publisher: org.reactivestreams.Publisher[Int] = Stream[Task, Int](1, 2, 3).toUnicastPublisher
publisher.toStream[Task]
```

A unicast publisher must have a single subscriber only.

## Example: Akka streams

Import the Akka streams dsl:

```tut:silent
import akka._
import akka.stream._
import akka.stream.scaladsl._
import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext.Implicits.global

implicit val system = ActorSystem("akka-streams-example")
implicit val materializer = ActorMaterializer()
```

To convert from an `Source` to a `Stream`:

```tut:book
val source = Source(1 to 5)
val publisher = source.runWith(Sink.asPublisher[Int](fanout = false))
val stream = publisher.toStream[Task]
stream.runLog.unsafeRun()
```

To convert from a `Stream` to a `Source`:

```tut:book
val stream = Stream.emits[Task, Int]((1 to 5).toSeq)
val source = Source.fromPublisher(stream.toUnicastPublisher)
Task.fromFuture(source.runWith(Sink.seq[Int])).unsafeRun()
```

```tut:silent
system.terminate()
```