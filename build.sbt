// SPDX-License-Identifier: Apache-2.0
val catsVersion = "2.13.0"
val catsEffectVersion = "3.7.1"
val catsMtlVersion = "1.7.0"
val fs2Version = "3.14.0"
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
lazy val core = project
  .in(file("core"))
  .settings(
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
lazy val network = project
  .in(file("network"))
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

// Tagless-final network runtime: fs2 bearer, multiplexer and mini-protocol clients.
lazy val networkRuntime = project
  .in(file("network-runtime"))
  .dependsOn(network % "compile->compile;test->test")
  .settings(
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
lazy val node = project
  .in(file("node"))
  .dependsOn(core, network, networkRuntime)
  .settings(
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
      // circe 0.14.16 brings jawn-parser 1.7.0 (fixes jawn advisories)
      "io.circe" %% "circe-parser" % "0.14.16",
      "org.http4s" %% "http4s-ember-server" % "0.23.37",
      "org.http4s" %% "http4s-dsl" % "0.23.37",
      "org.http4s" %% "http4s-circe" % "0.23.37"
    ) ++ lawTestDependencies,
    runtimeClasspathFile := {
      val output = target.value / "runtime-classpath.txt"
      IO.write(output, (Runtime / fullClasspath).value.files.mkString(java.io.File.pathSeparator))
      output
    },
    Compile / run / fork := true
  )

// Schema-first models, step 1: CDDL parser and Scala generator (build-time tool; see docs/models.md).
lazy val modelsGen = project
  .in(file("models-gen"))
  .settings(
    name := "scarab-models-gen",
    organization := "xyz.kd5ujc",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-parse" % "1.1.0",
      "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

// Schema-first models, step 2: code generated from the pinned upstream CDDL at build time, its hand-written
// runtime, and the equivalence tests against the hand-written decoders of `network` and `node`.
lazy val modelsSpike = project
  .in(file("models-spike"))
  .dependsOn(core, modelsGen % "test->compile", network % "test->compile", node % "test->compile")
  .settings(
    name := "scarab-models-spike",
    organization := "xyz.kd5ujc",
    Compile / sourceGenerators += Def.task {
      val log = streams.value.log
      val cddl = (Compile / sourceDirectory).value / "cddl"
      val out = (Compile / sourceManaged).value / "scarab-models"
      (modelsGen / Compile / compile).value
      val cp = (modelsGen / Compile / fullClasspath).value.files
      val genRunner = (modelsGen / Compile / runner).value
      val generatorClasses = ((modelsGen / Compile / classDirectory).value ** "*.class").get
      val inputs = ((cddl ** "*").filter(_.isFile).get ++ generatorClasses).toSet
      val cached =
        FileFunction.cached(streams.value.cacheDirectory / "models-gen", FilesInfo.hash) { _ =>
          IO.delete(out)
          val code = genRunner
            .run(
              "xyz.kd5ujc.scarab.models.gen.Main",
              cp,
              Seq(cddl.getAbsolutePath, out.getAbsolutePath),
              log
            )
          code.failed.foreach(e => sys.error(s"models generator failed: ${e.getMessage}"))
          (out ** "*.scala").get.toSet
        }
      cached(inputs).toSeq
    }.taskValue,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

// Structured fuzzing of every decoder entry point (roadmap K3; docs/running.md "Fuzzing"). Test-only. The
// tests run in a forked JVM (a hang or an out-of-memory kills the fork, not the build server) from the
// repository root; the defaults below are the budget of `check` (about a minute), the `fuzz` command
// below turns them up.
val fuzzCases = settingKey[Int]("Fuzz cases per target and seed")
val fuzzSeed = settingKey[Long]("First fuzz seed")
val fuzzSeeds = settingKey[Int]("Number of consecutive fuzz seeds")

lazy val fuzz = project
  .in(file("fuzz"))
  .dependsOn(core, network, node % "compile->compile;test->test", modelsGen)
  .settings(
    name := "scarab-fuzz",
    fuzzCases := 20000,
    fuzzSeed := 1L,
    fuzzSeeds := 1,
    Test / fork := true,
    Test / baseDirectory := (ThisBuild / baseDirectory).value,
    Test / javaOptions ++= Seq(
      "-Xmx1536m",
      s"-Dfuzz.cases=${fuzzCases.value}",
      s"-Dfuzz.seed=${fuzzSeed.value}",
      s"-Dfuzz.seeds=${fuzzSeeds.value}"
    ),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(core, network, networkRuntime, node, modelsGen, modelsSpike, fuzz)
  .settings(
    // fuzz [cases] [first-seed] [seeds] [target-glob]: the fuzz suite with more cases or other seeds than
    // `check` uses; the settings change for this run only.
    commands += Command.args("fuzz", "[cases] [first-seed] [seeds] [target-glob]") { (state, args) =>
      def number[A](i: Int, default: A)(parse: String => A): A = args.lift(i).fold(default)(parse)
      val extracted = Project.extract(state)
      val tuned = extracted.appendWithoutSession(
        Seq(
          fuzz / fuzzCases := number(0, 2000)(_.toInt),
          fuzz / fuzzSeed := number(1, 1L)(_.toLong),
          fuzz / fuzzSeeds := number(2, 1)(_.toInt)
        ),
        state
      )
      val glob = args.lift(3).fold("")(g => s" --tests=*$g*")
      val run = s" lab.fuzz.FuzzSuite --$glob"
      try {
        Project.extract(tuned).runInputTask(fuzz / Test / testOnly, run, tuned)
        state
      } catch {
        case _: Throwable => state.fail
      }
    }
  )

addCommandAlias(
  "check",
  ";scalafmtCheckAll;core/Test/compile;network/Test/compile;networkRuntime/Test/compile;node/Test/compile;modelsGen/Test/compile;modelsSpike/Test/compile;fuzz/Test/compile;core/Test/test;network/Test/test;networkRuntime/Test/test;node/Test/test;modelsGen/Test/test;modelsSpike/Test/test;fuzz/Test/test"
)
