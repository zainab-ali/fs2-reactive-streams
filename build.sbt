lazy val buildSettings = Seq(
  organization := "com.ithaca",
  scalaOrganization := "org.typelevel",
  scalaVersion := "2.12.1",
  name := "fs2-reactive",
  version := "0.1.0-SNAPSHOT"
)

lazy val commonScalacOptions = Seq(
  "-encoding",
  "UTF-8",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:experimental.macros",
  "-language:postfixOps",
  "-Ypartial-unification",
  "-Yliteral-types"
)

lazy val commonResolvers = Seq(
  Resolver.sonatypeRepo("releases")
)

lazy val coverageSettings = Seq(
  coverageMinimum := 60,
  coverageFailOnMinimum := false
)

lazy val commonSettings = Seq(
    resolvers := commonResolvers,
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % "0.9.4",
      "org.reactivestreams" % "reactive-streams" % "1.0.0",
      "org.log4s" %% "log4s" % "1.3.4",
      "org.scalatest" %% "scalatest" % "3.0.1" % "test",
      "org.reactivestreams" % "reactive-streams-tck" % "1.0.0" % "test",
      "org.slf4j" % "slf4j-simple" % "1.7.25" % "test"
    ),
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")
) ++ coverageSettings ++ buildSettings

lazy val root = (project in file("."))
  .settings(commonSettings)
