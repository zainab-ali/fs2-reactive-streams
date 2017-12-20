# fs2-reactive-streams
A reactive streams implementation for [fs2](https://github.com/functional-streams-for-scala/fs2)

[![Build Status](https://travis-ci.org/zainab-ali/fs2-reactive-streams.svg?branch=master)](http://travis-ci.org/zainab-ali/fs2-reactive-streams)
[![codecov](https://codecov.io/gh/zainab-ali/fs2-reactive-streams/branch/master/graph/badge.svg)](https://codecov.io/gh/zainab-ali/fs2-reactive-streams)

## To use

Add the following to your `build.sbt`:

```tut:silent:fail
libraryDependencies += "com.github.zainab-ali" %% "fs2-reactive-streams" % "0.2.7"
```
This is dependent on version `0.10.0-M10` of fs2.

## TL;DR


```tut:book
import cats._, cats.effect._, fs2._
import fs2.interop.reactivestreams._
import scala.concurrent.ExecutionContext.Implicits.global

val upstream = Stream(1, 2, 3).covary[IO]
val publisher = upstream.toUnicastPublisher
val downstream = publisher.toStream[IO]
downstream.runLog.unsafeRunSync()
```

## Why?

The [reactive streams initiative](http://www.reactive-streams.org/) is complicated, mutable and unsafe - it is not something that is desired for use over fs2.
But there are times when we need use fs2 in conjunction with a different streaming library, and this is where reactive streams shines.

Any reactive streams system can interop with any other reactive streams system by exposing an `org.reactivestreams.Publisher` or an `org.reactivestreams.Subscriber`.

This library provides instances of reactivestreams compliant publishers and subscribers to ease interop with other streaming libraries.

## Usage


To convert a `Stream` into a downstream unicast `org.reactivestreams.Publisher`:

```tut:silent
val stream = Stream(1, 2, 3).covary[IO]
stream.toUnicastPublisher
```

To convert an upstream `org.reactivestreams.Publisher` into a `Stream`:

```tut:silent
val publisher: org.reactivestreams.Publisher[Int] = Stream(1, 2, 3).covary[IO].toUnicastPublisher
publisher.toStream[IO]
```

A unicast publisher must have a single subscriber only.

## Example: Akka streams

Import the Akka streams dsl:

```tut:silent
import akka._
import akka.stream._
import akka.stream.scaladsl._
import akka.actor.ActorSystem

implicit val system = ActorSystem("akka-streams-example")
implicit val materializer = ActorMaterializer()
```

To convert from an `Source` to a `Stream`:

```tut:book
val source = Source(1 to 5)
val publisher = source.runWith(Sink.asPublisher[Int](fanout = false))
val stream = publisher.toStream[IO]
stream.runLog.unsafeRunSync()
```

To convert from a `Stream` to a `Source`:

```tut:book
val stream = Stream.emits((1 to 5).toSeq).covary[IO]
val source = Source.fromPublisher(stream.toUnicastPublisher)
IO.fromFuture(IO(source.runWith(Sink.seq[Int]))).unsafeRunSync()
```
```tut:invisible
system.terminate()
```

## Version Compatability

| fs2            | fs2-reactive-streams | status     |
|:--------------:|:--------------------:|:----------:|
| 0.10.0-M10     | 0.2.7                | current    |
| 0.10.0-M9      | 0.2.6                | current    |
| 0.10.0-M8      | 0.2.5                | current    |
| 0.10.0-M7      | 0.2.4                | current    |
| 0.10.0-M6      | 0.2.3                | current    |
| 0.10.0-M5      | 0.2.2                | current    |
| ~~0.10.0-M5~~  | ~~0.2.1~~            | ~~broken~~ |
| ~~0.10.0-M2~~  | ~~0.2.0~~            | ~~broken~~ |
| 0.9.4          | 0.1.1                | current    |
| ~~0.9.4~~      | ~~0.1.0~~            | ~~broken~~ |

## Contributors

The following people have taken their time and effort to improve fs2-reactive-streams.

* Ross A Baker [@rossabaker](https://github.com/rossabaker)
* Bjørn Madsen [@aeons](https://github.com/aeons)

Thank you for your help!

## Licence

fs2-reactive-streams is licensed under the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).

## Credits

Many thanks go to [Ross Baker](https://github.com/rossabaker) who took the first step in making a reactive streams implementation in [http4s](https://github.com/http4s/http4s).  Without this, fs2-reactive-streams would have been much harder to write.
