import xerial.sbt.Sonatype.autoImport.sonatypeProfileName
import ReleaseTransformations._

lazy val buildSettings = Seq(
  organization := "com.github.zainab-ali",
  crossScalaVersions := List("2.12.2", "2.11.11"),
  scalaVersion := crossScalaVersions.value.head,
  name := "fs2-reactive-streams"
)

lazy val commonScalacOptions = Seq(
  "-encoding",
  "UTF-8",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:experimental.macros",
  "-language:postfixOps"
)

lazy val commonResolvers = Seq(
 Resolver.sonatypeRepo("releases")
)

lazy val coverageSettings = Seq(
  coverageMinimum := 60,
  coverageFailOnMinimum := false
)

lazy val noPublishSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false
)


lazy val commonSettings = Seq(
  resolvers := commonResolvers,
  scalacOptions ++= commonScalacOptions,
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % "0.10.0-M1",
    "org.reactivestreams" % "reactive-streams" % "1.0.0",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test",
    "org.scalacheck" %% "scalacheck" % "1.13.4" % "test",
    "org.reactivestreams" % "reactive-streams-tck" % "1.0.0" % "test"
  )
) ++ coverageSettings ++ buildSettings

lazy val docSettings = tutSettings ++ Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-stream" % "2.5.0"
  ),
  tutTargetDirectory := (baseDirectory in ThisBuild).value
)


val publishSettings = Seq(
  releaseCrossBuild := true,
  releaseIgnoreUntrackedFiles := true,
  sonatypeProfileName := "com.github.zainab-ali",
  developers += Developer("zainab-ali", "Zainab Ali", "", url("http://github.com/zainab-ali")),
  licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("https://github.com/zainab-ali/fs2-reactive-streams")),
  scmInfo := Some(ScmInfo(url("https://github.com/zainab-ali/fs2-reactive-streams"),
    "git@github.com:zainab-ali/fs2-reactive-streams")),
  credentials ++= (for {
    username <- Option(System.getenv().get("SONATYPE_USERNAME"))
    password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
  } yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)).toSeq,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    ReleaseStep(action = Command.process("publishSigned", _), enableCrossBuild = true),
    setNextVersion,
    commitNextVersion,
    ReleaseStep(action = Command.process("sonatypeReleaseAll", _), enableCrossBuild = true),
    pushChanges)
)

lazy val core = (project in file("core"))
  .settings(moduleName := "fs2-reactive-streams")
  .settings(commonSettings)
  .settings(publishSettings)

lazy val docs = (project in file("docs"))
  .settings(moduleName := "docs")
  .dependsOn(core)
  .settings(commonSettings)
  .settings(docSettings)
  .settings(noPublishSettings)

lazy val root = (project in file("."))
  .aggregate(core, docs)
  .settings(noPublishSettings)
