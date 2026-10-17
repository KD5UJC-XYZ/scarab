// SPDX-License-Identifier: Apache-2.0
import Dependencies.*
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "xyz.kd5ujc"
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  // Dead code the compiler can see. Left off for now (warning counts on this tree, Scala 3.3.8):
  // -Wunused:implicits 13, -Wvalue-discard 243 and -Wnonunit-statement 308; the last two are almost
  // all fluent CborOut calls in node/ntc.
  "-Wunused:imports",
  "-Wunused:privates",
  "-Wunused:locals",
  "-Wunused:explicits",
  "-Wunused:patvars",
  // The generated models are not ours to tidy.
  "-Wconf:src=.*/src_managed/.*:silent"
)
// scalafix needs the semantic database of the compiler.
ThisBuild / semanticdbEnabled := true
ThisBuild / publish / skip := true
ThisBuild / Test / parallelExecution := false
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

// The tests of every module: munit, ScalaCheck, and the cats law and effect test kits.
val testDependencies = Seq(
  Libraries.catsLawsTest,
  Libraries.catsKernelLawsTest,
  Libraries.catsMtlLawsTest,
  Libraries.disciplineMunitTest,
  Libraries.munitCatsEffectTest,
  Libraries.catsEffectTestkitTest,
  Libraries.munitScalaCheckTest,
  Libraries.munitTest
)

// Every module is the sbt project `<id>`, the directory `<id>` and the artifact `scarab-<id>`. The
// dependency direction below is enforced by sbt (a cycle does not load):
//
//   blstNative <- crypto, plutus
//   codec <- crypto <- protocol <- network
//   codec <- plutus
//   protocol, plutus <- ledger <- consensus <- legacy <- storage
//   models <- storage (the Haskell ledger-state codec reuses the models runtime)
//   storage, network <- node
def scarab(id: String, dir: String = null): Project =
  Project(id, file(Option(dir).getOrElse(id)))
    // sbt-tpolecat would add its own option set; the options above are the whole set here.
    .disablePlugins(TpolecatPlugin)
    .settings(
      name := s"scarab-$id",
      // Initialisation safety of objects (Scala 3.3 spells it -Ysafe-init; later versions -Wsafe-init).
      // Main sources only: it raises 19 warnings in the test suites, all of them the munit idiom of
      // registering the tests in the constructor, and none in main.
      Compile / scalacOptions += "-Ysafe-init",
      Test / scalacOptions -= "-Ysafe-init",
      libraryDependencies ++= testDependencies
    )

// The portable libblst (ADR 0012) as the resource blst-java loads, and the start-up check that the JVM
// is going to use it. Every module that reaches blst depends on it so that it comes before blst-java
// on their class paths.
lazy val blstNative = scarab("blstNative", "blst-native")
  .settings(
    name := "scarab-blst-native",
    libraryDependencies ++= Seq(
      Libraries.blstJava,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsMtl
    )
  )

// CBOR with original-bytes preservation, the byte-string type and exact rationals.
lazy val codec = scarab("codec")
  .settings(
    libraryDependencies += Libraries.catsKernel
  )

// Ed25519, VRF, KES, BLS12-381, Blake2b and the operational certificate.
lazy val crypto = scarab("crypto")
  .dependsOn(codec % "compile->compile;test->test", blstNative)
  .settings(
    libraryDependencies ++= Seq(
      Libraries.bouncyCastle,
      Libraries.curve25519,
      Libraries.blstJava
    )
  )

// Pure Ouroboros mini-protocol codecs and state machines.
lazy val protocol = scarab("protocol")
  .dependsOn(crypto % "compile->compile;test->test")

// Tagless-final network runtime: fs2 bearer, multiplexer and mini-protocol clients and servers.
lazy val network = scarab("network")
  .dependsOn(protocol % "compile->compile;test->test")
  .settings(
    libraryDependencies ++= Seq(
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsMtl,
      Libraries.fs2Core,
      Libraries.fs2Io
    )
  )

// The Plutus (UPLC) machine and builtins through Scalus; the script context and the rules are ours.
lazy val plutus = scarab("plutus")
  .dependsOn(codec % "compile->compile;test->test", blstNative)
  .settings(
    libraryDependencies ++= Seq(
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.scalus,
      Libraries.circeParserTest
    )
  )

// The pure ledger rules and types: blocks, headers, genesis, transactions, scripts, governance,
// rewards, the pre-Conway and Byron rules.
lazy val ledger = scarab("ledger")
  .dependsOn(
    protocol % "compile->compile;test->test",
    plutus % "compile->compile;test->test"
  )
  .settings(
    // Snapshot codecs are derived by inline `Mirror` recursion; ProtocolParams has 33 fields.
    scalacOptions += "-Xmax-inlines:512",
    libraryDependencies ++= Seq(
      Libraries.cats,
      Libraries.catsMtl,
      Libraries.circeParser,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.kittens,
      Libraries.monocleLawTest
    )
  )

// Praos, TPraos and PBFT, chain selection, the hard-fork combinator, header rules, the chain state.
lazy val consensus = scarab("consensus")
  .dependsOn(ledger % "compile->compile;test->test")
  .settings(
    scalacOptions += "-Xmax-inlines:512",
    libraryDependencies ++= Seq(
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsMtl,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.kittens,
      Libraries.monocleLawTest
    )
  )

// The frozen readers of the snapshot formats before the current one (V1 to V3) with the node state
// of their time, and the upgrade of that state to the current one: what `storage` migrates from. Data
// and readers only; deleted at its sunset (ADR 0006, design ledger-mirror-step2.md D3).
lazy val legacy = scarab("legacy")
  .dependsOn(consensus % "compile->compile;test->test")
  .settings(
    // The snapshot codecs are derived by inline `Mirror` recursion.
    scalacOptions += "-Xmax-inlines:512",
    libraryDependencies ++= Seq(
      Libraries.cats
    )
  )

// Immutable and volatile stores, ledger snapshots, the UTxO store, the Haskell ImmutableDB reader.
lazy val storage = scarab("storage")
  .dependsOn(
    consensus % "compile->compile;test->test",
    legacy % "compile->compile;test->test",
    models
  )
  .settings(
    scalacOptions += "-Xmax-inlines:512",
    libraryDependencies ++= Seq(
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsMtl,
      Libraries.fs2Core,
      Libraries.fs2Io,
      Libraries.scodecCore,
      Libraries.rocksdb
    ),
    // MemoryBoundSuite checks what a decode holds in the heap, so it runs alone in a JVM with a
    // 256 MiB heap (issue #94); every other suite runs in the build's JVM as before.
    Test / testGrouping := {
      val (bounded, others) =
        (Test / definedTests).value.partition(_.name.endsWith(".MemoryBoundSuite"))
      val fork = (Test / forkOptions).value.withRunJVMOptions(Vector("-Xmx256m"))
      Seq(
        Tests.Group("storage", others, Tests.InProcess),
        Tests.Group("heap-bound", bounded, Tests.SubProcess(fork))
      )
    }
  )

// The short hash of HEAD, read from the repository's files (project/GitHead.scala) so that the build
// container, which has no `git`, knows it too; "unknown" outside a checkout.
def gitCommit(root: File): String =
  GitHead.commit(root.toPath).map(_.take(8)).getOrElse("unknown")

lazy val runtimeClasspathFile = taskKey[File]("Write the resolved application runtime classpath")

// Tagless-final node: wiring, mempool, forger, relay, node-to-client, the query server, the CLI.
lazy val node = scarab("node")
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(
    storage % "compile->compile;test->test",
    network % "compile->compile;test->test"
  )
  .settings(
    // `xyz.kd5ujc.scarab.node.BuildInfo`: what the `started` log line says about this build.
    buildInfoPackage := "xyz.kd5ujc.scarab.node",
    buildInfoKeys := {
      val root = (ThisBuild / baseDirectory).value
      Seq[BuildInfoKey](
        version,
        scalaVersion,
        sbtVersion,
        BuildInfoKey.action("gitCommit")(gitCommit(root))
      )
    },
    scalacOptions += "-Xmax-inlines:512",
    libraryDependencies ++= Seq(
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsMtl,
      Libraries.circeParser,
      Libraries.http4sEmberServer,
      Libraries.http4sDsl,
      Libraries.http4sCirce,
      Libraries.slf4jApi
    ),
    runtimeClasspathFile := {
      val output = target.value / "runtime-classpath.txt"
      IO.write(output, (Runtime / fullClasspath).value.files.mkString(java.io.File.pathSeparator))
      output
    },
    Compile / run / fork := true
  )

// Schema-first models, step 1: CDDL parser and Scala generator (build-time tool; see docs/models.md).
lazy val modelsGen = scarab("modelsGen", "models-gen")
  .settings(
    name := "scarab-models-gen",
    libraryDependencies ++= Seq(
      Libraries.catsParse
    )
  )

// Schema-first models, step 2: code generated from the pinned upstream CDDL at build time, its hand-written
// runtime, and the equivalence tests against the hand-written decoders of `protocol` and `ledger`.
lazy val models = scarab("models")
  .dependsOn(
    codec,
    crypto,
    modelsGen % "test->compile",
    protocol % "test->compile",
    ledger % "test->compile"
  )
  .settings(
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
    }.taskValue
  )

// Structured fuzzing of every decoder entry point (roadmap K3; docs/running.md "Fuzzing"). Test-only. The
// tests run in a forked JVM (a hang or an out-of-memory kills the fork, not the build server) from the
// repository root; the defaults below are the budget of `check` (about a minute), the `fuzz`
// command below turns them up.
val fuzzCases = settingKey[Int]("Fuzz cases per target and seed")
val fuzzSeed = settingKey[Long]("First fuzz seed")
val fuzzSeeds = settingKey[Int]("Number of consecutive fuzz seeds")

lazy val fuzz = scarab("fuzz")
  .dependsOn(codec, protocol, node % "compile->compile;test->test", modelsGen)
  .settings(
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
    )
  )

lazy val root = project
  .in(file("."))
  .disablePlugins(TpolecatPlugin)
  .aggregate(
    blstNative,
    codec,
    crypto,
    protocol,
    network,
    plutus,
    ledger,
    consensus,
    legacy,
    storage,
    node,
    modelsGen,
    models,
    fuzz
  )
  .settings(
    // fuzz [cases] [first-seed] [seeds] [target-text]: the fuzz suite with more cases or other seeds than
    // `check` uses; the settings change for this run only.
    commands += Command.args("fuzz", "[cases] [first-seed] [seeds] [target-text]") {
      (state, args) =>
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
        val glob = args.lift(3).fold("")(g => s" --tests=.*$g.*")
        val run = s" xyz.kd5ujc.scarab.fuzz.FuzzSuite --$glob"
        try {
          Project.extract(tuned).runInputTask(fuzz / Test / testOnly, run, tuned)
          state
        } catch {
          case _: Throwable => state.fail
        }
    }
  )

val modules = Seq(
  "blstNative",
  "codec",
  "crypto",
  "protocol",
  "network",
  "plutus",
  "ledger",
  "consensus",
  "legacy",
  "storage",
  "node",
  "modelsGen",
  "models",
  "fuzz"
)

addCommandAlias(
  "check",
  (";scalafmtCheckAll" +: (modules.map(m => s"$m/Test/compile") ++ modules
    .map(m => s"$m/Test/test")) :+ "scalafixAll --check")
    .mkString(";")
)
