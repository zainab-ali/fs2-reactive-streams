# fs2-reactive-streams
A reactive streams implementation for [fs2](https://github.com/functional-streams-for-scala/fs2)

[![Join the chat at https://gitter.im/fs2-reactive-streams/Lobby](https://badges.gitter.im/fs2-reactive-streams/Lobby.svg)](https://gitter.im/fs2-reactive-streams/Lobby)


[![Build Status](https://travis-ci.org/zainab-ali/fs2-reactive-streams.svg?branch=master)](http://travis-ci.org/zainab-ali/fs2-reactive-streams)
[![codecov](https://codecov.io/gh/zainab-ali/fs2-reactive-streams/branch/master/graph/badge.svg)](https://codecov.io/gh/zainab-ali/fs2-reactive-streams)
[![Nexus](https://img.shields.io/nexus/r/https/oss.sonatype.org/com.github.zainab-ali/fs2-reactive-streams_2.12.svg)](https://oss.sonatype.org/content/groups/public/com/github/zainab-ali/fs2-reactive-streams_2.12/)

## To use

Add the following to your `build.sbt`:

```scala
libraryDependencies += "com.github.zainab-ali" %% "fs2-reactive-streams" % "0.8.0"
```
This is dependent on version `1.0.0-M5` of fs2.

## TL;DR


```scala
import cats._, cats.effect._, fs2._
// import cats._
// import cats.effect._
// import fs2._

import fs2.interop.reactivestreams._
// import fs2.interop.reactivestreams._

import scala.concurrent.ExecutionContext
// import scala.concurrent.ExecutionContext

implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
// contextShift: cats.effect.ContextShift[cats.effect.IO] = cats.effect.internals.IOContextShift@5921d05c

val upstream = Stream(1, 2, 3).covary[IO]
// upstream: fs2.Stream[cats.effect.IO,Int] = Stream(..)

val publisher = upstream.toUnicastPublisher
// publisher: fs2.interop.reactivestreams.StreamUnicastPublisher[cats.effect.IO,Int] = fs2.interop.reactivestreams.StreamUnicastPublisher@53cd992d

val downstream = publisher.toStream[IO]
// downstream: fs2.Stream[cats.effect.IO,Int] = Stream(..)

downstream.compile.toVector.unsafeRunSync()
// res1: Vector[Int] = Vector(1, 2, 3)
```

## Why?

The [reactive streams initiative](http://www.reactive-streams.org/) is complicated, mutable and unsafe - it is not something that is desired for use over fs2.
But there are times when we need use fs2 in conjunction with a different streaming library, and this is where reactive streams shines.

Any reactive streams system can interop with any other reactive streams system by exposing an `org.reactivestreams.Publisher` or an `org.reactivestreams.Subscriber`.

This library provides instances of reactivestreams compliant publishers and subscribers to ease interop with other streaming libraries.

## Usage

You may require the following imports

```scala
import cats._, cats.effect._, fs2._
import fs2.interop.reactivestreams._
import scala.concurrent.ExecutionContext
```

A `ContextShift` instance is necessary when working with `IO`

```scala
implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
```

To convert a `Stream` into a downstream unicast `org.reactivestreams.Publisher`:

```scala
val stream = Stream(1, 2, 3).covary[IO]
stream.toUnicastPublisher
```

To convert an upstream `org.reactivestreams.Publisher` into a `Stream`:

```scala
val publisher: org.reactivestreams.Publisher[Int] = Stream(1, 2, 3).covary[IO].toUnicastPublisher
publisher.toStream[IO]
```

A unicast publisher must have a single subscriber only.

## Example: Akka streams to Stream[IO]

Import the Akka streams dsl:

```scala
import akka._
import akka.stream._
import akka.stream.scaladsl._
import akka.actor.ActorSystem
import cats.effect._
import fs2.Stream
import fs2.interop.reactivestreams._
import scala.concurrent.ExecutionContext

implicit val system: ActorSystem = ActorSystem("akka-streams-example")
implicit val materializer: ActorMaterializer = ActorMaterializer()

implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
```

To convert from a `Source` to a `Stream`:

```scala
val source = Source(1 to 5)
// source: akka.stream.scaladsl.Source[Int,akka.NotUsed] = Source(SourceShape(StatefulMapConcat.out(569807275)))

val publisher = source.runWith(Sink.asPublisher[Int](fanout = false))
// publisher: org.reactivestreams.Publisher[Int] = VirtualProcessor(state = Publisher[StatefulMapConcat.out(569807275)])

val stream = publisher.toStream[IO]
// stream: fs2.Stream[cats.effect.IO,Int] = Stream(..)

stream.compile.toVector.unsafeRunSync()
// res0: Vector[Int] = Vector(1, 2, 3, 4, 5)
```

To convert from a `Stream` to a `Source`:

```scala
import scala.concurrent.ExecutionContext.global
// import scala.concurrent.ExecutionContext.global

val stream = Stream.emits((1 to 5).toSeq).covary[IO]
// stream: fs2.Stream[cats.effect.IO,Int] = Stream(..)

val source = Source.fromPublisher(stream.toUnicastPublisher)
// source: akka.stream.scaladsl.Source[Int,akka.NotUsed] = Source(SourceShape(PublisherSource.out(1201700068)))

IO.fromFuture(IO(source.runWith(Sink.seq[Int]))).unsafeRunSync()
// res1: scala.collection.immutable.Seq[Int] = Vector(1, 2, 3, 4, 5)
```



## Version Compatability

Patch releases (e.g `0.2.7` to `0.2.8`) are binary compatible.  If you're concerned about a broken release, please check the [CHANGELOG](CHANGELOG.md) for more details.


| fs2            | fs2-reactive-streams | status     |
|:--------------:|:--------------------:|:----------:|
| 1.0.0-M5       | 0.8.0                | current    |
| 1.0.0-M4       | 0.7.0                | current    |
| 1.0.0-M1       | 0.6.0                | current    |
| 0.10.1         | 0.5.1                | current    |
| 0.10.0         | 0.5.0                | current    |
| ~~0.10.0~~     | ~~0.4.0~~            | ~~broken~~ |
| ~~0.10.0-RC2~~ | ~~0.3.0~~            | ~~broken~~ |
| ~~0.10.0-M11~~ | ~~0.2.8~~            | ~~broken~~ |
| ~~0.10.0-M10~~ | ~~0.2.7~~            | ~~broken~~ |
| ~~0.10.0-M9~~  | ~~0.2.6~~            | ~~broken~~ |
| ~~0.10.0-M8~~  | ~~0.2.5~~            | ~~broken~~ |
| ~~0.10.0-M7~~  | ~~0.2.4~~            | ~~broken~~ |
| ~~0.10.0-M6~~  | ~~0.2.3~~            | ~~broken~~ |
| ~~0.10.0-M5~~  | ~~0.2.2~~            | ~~broken~~ |
| ~~0.10.0-M5~~  | ~~0.2.1~~            | ~~broken~~ |
| ~~0.10.0-M2~~  | ~~0.2.0~~            | ~~broken~~ |
| 0.9.4          | 0.1.1                | current    |
| ~~0.9.4~~      | ~~0.1.0~~            | ~~broken~~ |

## Contributors

The following people have taken their time and effort to improve fs2-reactive-streams.

* Ross A Baker [@rossabaker](https://github.com/rossabaker)
* Bjørn Madsen [@aeons](https://github.com/aeons)
* Fabio Labella [@SystemFw](https://github.com/SystemFw)

Thank you for your help!

## Licence

fs2-reactive-streams is licensed under the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).

## Credits

Many thanks go to [Ross Baker](https://github.com/rossabaker) who took the first step in making a reactive streams implementation in [http4s](https://github.com/http4s/http4s).  Without this, fs2-reactive-streams would have been much harder to write.
