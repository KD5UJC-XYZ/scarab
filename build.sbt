// SPDX-License-Identifier: Apache-2.0
val catsVersion = "2.13.0"
val catsEffectVersion = "3.6.3"
val catsMtlVersion = "1.6.0"
val fs2Version = "3.11.0"
val munitVersion = "1.0.2"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "io.github.ottobot-ai"
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror")
ThisBuild / publish / skip := true
ThisBuild / Test / parallelExecution := false
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

val lawTestDependencies = Seq(
  "org.typelevel" %% "cats-laws" % catsVersion % Test,
  "org.typelevel" %% "cats-mtl-laws" % catsMtlVersion % Test,
  "org.typelevel" %% "discipline-munit" % "2.0.0" % Test,
  "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test,
  "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion % Test,
  "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test,
  "org.scalameta" %% "munit" % munitVersion % Test
)

// Pure codecs and cryptography: CBOR, Blake2b, Ed25519, VRF, KES, operational certificates.
lazy val core = project.in(file("core")).settings(
  libraryDependencies ++= Seq(
    "org.bouncycastle" % "bcprov-jdk18on" % "1.85.2",
    "com.weavechain" % "curve25519-elisabeth" % "0.1.3",
    "org.typelevel" %% "cats-kernel" % catsVersion,
    "org.typelevel" %% "cats-kernel-laws" % catsVersion % Test,
    "org.typelevel" %% "discipline-munit" % "2.0.0" % Test,
    "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test,
    "org.scalameta" %% "munit" % munitVersion % Test
  )
)

// Pure Ouroboros mini-protocol codecs and state machines.
lazy val network = project.in(file("network")).dependsOn(core).settings(
  libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test
)

// Tagless-final network runtime: fs2 bearer, multiplexer and mini-protocol clients.
lazy val networkRuntime = project.in(file("network-runtime")).dependsOn(network % "compile->compile;test->test").settings(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.typelevel" %% "cats-mtl" % catsMtlVersion,
    "co.fs2" %% "fs2-core" % fs2Version,
    "co.fs2" %% "fs2-io" % fs2Version
  ) ++ lawTestDependencies
)

lazy val runtimeClasspathFile = taskKey[File]("Write the resolved application runtime classpath")

// Tagless-final node: algebras over F[_] with cats-mtl typed control flow.
lazy val node = project.in(file("node")).dependsOn(core, network, networkRuntime).settings(
  // Snapshot codecs are derived by inline `Mirror` recursion; ProtocolParams has 33 fields.
  scalacOptions += "-Xmax-inlines:512",
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.typelevel" %% "cats-mtl" % catsMtlVersion,
    // Plutus (UPLC) machine and builtins only; the script context, ledger rules and plumbing are ours.
    "org.scalus" %% "scalus" % "1.2.0",
    // The on-disk UTxO set (an embedded LSM tree; native library bundled in the jar).
    "org.rocksdb" % "rocksdbjni" % "10.2.1",
    "io.circe" %% "circe-parser" % "0.14.10",
    "org.http4s" %% "http4s-ember-server" % "0.23.30",
    "org.http4s" %% "http4s-dsl" % "0.23.30",
    "org.http4s" %% "http4s-circe" % "0.23.30"
  ) ++ lawTestDependencies,
  runtimeClasspathFile := {
    val output = target.value / "runtime-classpath.txt"
    IO.write(output, (Runtime / fullClasspath).value.files.mkString(java.io.File.pathSeparator))
    output
  },
  Compile / run / fork := true
)

lazy val root = project.in(file(".")).aggregate(core, network, networkRuntime, node)

addCommandAlias(
  "check",
  ";scalafmtCheckAll;core/Test/compile;network/Test/compile;networkRuntime/Test/compile;node/Test/compile;core/Test/test;network/Test/test;networkRuntime/Test/test;node/Test/test"
)
