// SPDX-License-Identifier: Apache-2.0
import Dependencies.*
ThisBuild / scalaVersion := "3.3.8"
lazy val scarabBuildIdentity =
  settingKey[BuildIdentity.Identity]("Verified export or unreleased workspace identity")
ThisBuild / dynverVTagPrefix := true
ThisBuild / dynverCurrentDate := new java.util.Date(0L)
ThisBuild / scarabBuildIdentity := BuildIdentity.load(
  (ThisBuild / baseDirectory).value.toPath,
  (ThisBuild / dynverGitDescribeOutput).value
)
ThisBuild / version := (ThisBuild / scarabBuildIdentity).value.version
ThisBuild / dynver := (ThisBuild / version).value
ThisBuild / isVersionStable := (ThisBuild / scarabBuildIdentity).value.mode == "release"
ThisBuild / isSnapshot := !(ThisBuild / isVersionStable).value
ThisBuild / organization := "xyz.kd5ujc"
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
// Enable the unused-code warnings supported by the maintained source. At Scala 3.3.8, the excluded
// -Wunused:implicits, -Wvalue-discard and -Wnonunit-statement produced 13, 243 and 308 warnings;
// the latter two were mostly fluent CborOut calls in node/ntc. Generated models are warning-silent.
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-Wunused:imports",
  "-Wunused:privates",
  "-Wunused:locals",
  "-Wunused:explicits",
  "-Wunused:patvars",
  "-Wconf:src=.*/src_managed/.*:silent"
)
// scalafix needs the semantic database of the compiler.
ThisBuild / semanticdbEnabled := true
// Record source paths relative to the checkout in TASTy/debug metadata.
ThisBuild / scalacOptions += "-sourceroot:" + (ThisBuild / baseDirectory).value.getAbsolutePath
ThisBuild / packageTimestamp := Some(315532800000L)
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

// Modules use `<id>` for the project and directory, with an explicit override for models-gen
// below. Artifact names start with `scarab-`; sbt enforces an acyclic dependency graph:
//
//   codec <- crypto <- protocol <- network
//   codec <- plutus
//   protocol, plutus <- ledger <- consensus <- storage
//   models <- storage (the Haskell ledger-state codec reuses the models runtime)
//   storage, network <- node
// Initialisation safety applies to main sources (-Ysafe-init on Scala 3.3, -Wsafe-init later).
// Test sources exclude it because munit registers tests in constructors; the initial sweep found
// 19 warnings for that idiom and none in main sources.
def scarab(id: String, dir: String = null): Project =
  Project(id, file(Option(dir).getOrElse(id)))
    // sbt-tpolecat would add its own option set; the options above are the whole set here.
    .disablePlugins(TpolecatPlugin)
    .settings(
      name := s"scarab-$id",
      Compile / scalacOptions += "-Ysafe-init",
      Test / scalacOptions -= "-Ysafe-init",
      libraryDependencies ++= testDependencies
    )

// CBOR with original-bytes preservation, the byte-string type and exact rationals.
lazy val codec = scarab("codec")
  .settings(
    libraryDependencies += Libraries.catsKernel
  )

// Ed25519, VRF, KES, BLS12-381 (jblst, with the start-up check of its native library), Blake2b and
// the operational certificate.
lazy val crypto = scarab("crypto")
  .dependsOn(codec % "compile->compile;test->test")
  .settings(
    libraryDependencies ++= Seq(
      Libraries.bouncyCastle,
      Libraries.curve25519,
      Libraries.circeParserTest,
      Libraries.jblst,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsMtl
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
      Libraries.fs2Io,
      Libraries.circeParserTest
    )
  )

val scalusPrepared = ScalusPatch.requirePrepared(Dependencies.V.scalus)

// The Plutus (UPLC) machine and builtins through Scalus; the script context and the rules are ours.
lazy val plutus = scarab("plutus")
  .dependsOn(codec % "compile->compile;test->test")
  .settings(
    libraryDependencies ++= Seq(
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.scalus,
      Libraries.circeParserTest
    ),
    Test / testGrouping := {
      // NativeCryptoAbsentSuite needs a JVM in which libscalus_crypto cannot load (ADR 0020): its
      // java.io.tmpdir does not exist, so native-lib-loader cannot extract the library.
      val (absent, others) =
        (Test / definedTests).value.partition(_.name.endsWith(".NativeCryptoAbsentSuite"))
      val fork = (Test / forkOptions).value
        .withRunJVMOptions(Vector("-Djava.io.tmpdir=/nonexistent/scarab-no-tmpdir"))
      Seq(
        Tests.Group("plutus", others, Tests.InProcess),
        Tests.Group("native-crypto-absent", absent, Tests.SubProcess(fork))
      )
    }
  )

// The pure ledger rules and types: blocks, headers, genesis, transactions, scripts, governance,
// rewards, the pre-Conway and Byron rules.
// The larger inline limit supports Mirror-derived snapshot codecs, including ProtocolParams.
lazy val ledger = scarab("ledger")
  .dependsOn(
    protocol % "compile->compile;test->test",
    plutus % "compile->compile;test->test"
  )
  .settings(
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

// Immutable and volatile stores, ledger snapshots, the UTxO store, the Haskell ImmutableDB reader.
lazy val storage = scarab("storage")
  .dependsOn(
    consensus % "compile->compile;test->test",
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
    Test / testGrouping := {
      // MemoryBoundSuite checks retained decode heap in a separate 256 MiB JVM (#94).
      // Other suites run in the build JVM.
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
lazy val releaseInventory =
  taskKey[File]("Export the resolved runtime graph and exact assembly inputs")
lazy val reproducibleCheck = taskKey[Unit]("Compare two clean builds of a frozen source export")

// Tagless-final node: wiring, mempool, forger, relay, node-to-client, the query server, the CLI.
// BuildInfo records the build identity reported by the `started` log event.
lazy val node = scarab("node")
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(
    storage % "compile->compile;test->test",
    network % "compile->compile;test->test"
  )
  .settings(
    buildInfoPackage := "xyz.kd5ujc.scarab.node",
    buildInfoKeys := {
      val root = (ThisBuild / baseDirectory).value
      val identity = (ThisBuild / scarabBuildIdentity).value
      Seq[BuildInfoKey](
        version,
        scalaVersion,
        sbtVersion,
        BuildInfoKey.action("gitCommit")(gitCommit(root)),
        BuildInfoKey.action("buildMode")(identity.mode),
        BuildInfoKey.action("releaseChannel")(identity.channel),
        BuildInfoKey.action("gitTag")(identity.tag)
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
      Libraries.slf4jApi,
      Libraries.dnsjava
    ),
    // http4s-core 0.23.37 declares log4s, but no class on the runtime classpath refers to it (#214):
    // http4s logs through log4cats, which `EmberServerBuilder.default` builds over SLF4J (LibraryLogs).
    excludeDependencies += Libraries.unusedLog4s,
    runtimeClasspathFile := {
      val output = target.value / "runtime-classpath.txt"
      IO.write(output, (Runtime / fullClasspath).value.files.mkString(java.io.File.pathSeparator))
      output
    },
    releaseInventory := ReleaseInventory.write(
      updateFull.value,
      (Runtime / fullClasspath).value.files,
      target.value / "release-inventory.json"
    ),
    Compile / mainClass := Some("xyz.kd5ujc.scarab.node.Main"),
    assembly / mainClass := (Compile / mainClass).value,
    assembly / assemblyJarName := s"scarab-node-${version.value}-assembly.jar",
    assemblyRepeatableBuild := true,
    // The node calls native code (the local socket's unlink through the FFM linker, the Plutus
    // crypto's JNI library); `java -jar` reads this attribute in place of
    // `--enable-native-access=ALL-UNNAMED`, so the JDK does not warn on the first such call.
    assembly / packageOptions += Package.ManifestAttributes(
      "Multi-Release" -> "true",
      "Enable-Native-Access" -> "ALL-UNNAMED"
    ),
    assemblyMergeStrategy := {
      case path if path == "module-info.class" || path.endsWith("/module-info.class") =>
        MergeStrategy.discard
      case PathList("META-INF", name)
          if Vector(".SF", ".DSA", ".RSA", ".EC").exists(name.toUpperCase.endsWith) ||
            name.toUpperCase.startsWith("SIG-") =>
        MergeStrategy.discard
      case path if path.startsWith("META-INF/scarab/") => MergeStrategy.deduplicate
      case "META-INF/DEPENDENCIES"                     => MergeStrategy.rename
      case path =>
        val fallback = (ThisBuild / assemblyMergeStrategy).value
        fallback(path)
    },
    Compile / resourceGenerators += Def.task {
      val root = (ThisBuild / baseDirectory).value
      val output = (Compile / resourceManaged).value / "META-INF" / "scarab"
      val identity = (ThisBuild / scarabBuildIdentity).value.metadata ++ Seq(
        "scalaVersion" -> scalaVersion.value,
        "sbtVersion" -> sbtVersion.value,
        "javaVendor" -> sys.props("java.vendor"),
        "javaRuntimeVersion" -> sys.props("java.runtime.version")
      )
      val metadata = output / "build-info.properties"
      IO.write(metadata, identity.sortBy(_._1).map { case (k, v) => s"$k=$v\n" }.mkString)
      val notices = Seq("LICENSE", "NOTICE").map { name =>
        val target = output / name
        IO.copyFile(root / name, target, preserveLastModified = false)
        target
      }
      // The third-party licence texts a binary must carry (licenses/index.json; checked against the
      // assembly by scripts/licence_texts.py): the licenses/ tree, and the texts kept beside the module
      // that uses their component, under the module's name.
      val kept = Seq(
        root / "plutus" / "licenses" -> "plutus",
        root / "crypto" / "licenses" -> "crypto"
      ).flatMap { case (dir, name) =>
        (dir * "LICENSE-*").get.map(file => file -> s"$name/${file.getName}")
      }
      val tree = (root / "licenses").allPaths.filter(_.isFile).get.map { file =>
        file -> IO.relativize(root / "licenses", file).getOrElse(file.getName)
      }
      val texts = (tree ++ kept).sortBy(_._2).map { case (source, name) =>
        val target = output / "licenses" / name
        IO.copyFile(source, target, preserveLastModified = false)
        target
      }
      (metadata +: notices) ++ texts
    }.taskValue,
    Compile / run / fork := true,
    // Scala 3.3's lazy vals use sun.misc.Unsafe, on which Java 25 warns (docker/candidate.Dockerfile);
    // from a classpath the JAR's Enable-Native-Access attribute does not apply
    Compile / run / javaOptions ++= Seq(
      "--sun-misc-unsafe-memory-access=allow",
      "--enable-native-access=ALL-UNNAMED"
    )
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
      cached(inputs).toSeq.sortBy(_.getAbsolutePath)
    }.taskValue
  )

// Structured fuzzing of every decoder entry point (roadmap K3; docs/running/fuzzing.md). Test-only. The
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
    codec,
    crypto,
    protocol,
    network,
    plutus,
    ledger,
    consensus,
    storage,
    node,
    modelsGen,
    models,
    fuzz
  )
  .settings(
    reproducibleCheck := {
      val root = baseDirectory.value
      val status = scala.sys.process
        .Process(
          Seq(
            "python3",
            (root / "scripts" / "reproducible_build.py").getAbsolutePath,
            "--inside-jdk"
          ),
          root
        )
        .!
      if (status != 0) sys.error("clean artifact reproducibility check failed")
    },
    commands += Command.args("fuzz", "[cases] [first-seed] [seeds] [target-text]") {
      (state, args) =>
        // Run a larger fuzz sample than `check`; these settings apply only to this invocation.
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
  "codec",
  "crypto",
  "protocol",
  "network",
  "plutus",
  "ledger",
  "consensus",
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
