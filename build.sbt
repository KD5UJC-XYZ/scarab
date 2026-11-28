// SPDX-License-Identifier: Apache-2.0
import Dependencies.*
ThisBuild / scalaVersion := "3.9.0"
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
// sbt-header (`headerCheckAll`, part of `check`): every Scala, Java and sbt source starts with the SPDX line,
// with no copyright line. The header is that line alone: the plugin's own `//` style takes every leading comment
// line for the header, and would replace the notes below it. A source adapted from code under another licence
// names that licence on the same line (`Apache-2.0 AND MIT`), and an existing line of that form is kept. A source
// with a second SPDX comment line (indented or in a block comment too) fails the check, so that `headerCreateAll`
// cannot settle a conflicting declaration by adding a line above it.
ThisBuild / headerLicense := Some(HeaderLicense.Custom("SPDX-License-Identifier: Apache-2.0"))
ThisBuild / headerEmptyLine := false
ThisBuild / headerMappings := {
  val header = raw"""// SPDX-License-Identifier: Apache-2\.0(?: AND [\w.+-]+)*(?:\r\n|\r|\n|\z)"""
  val spdxLine = HeaderCommentStyle.cppStyleLineComment.copy(
    commentCreator = new sbtheader.CommentCreator {
      def apply(text: String, existingText: Option[String]): String =
        existingText.fold(s"// $text")(_.stripLineEnd)
    },
    pattern = raw"""(?sm)($header)(?!.*^[\t ]*(?://|/\*+|\*)[\t ]*SPDX-License-Identifier:)(.*)""".r
  )
  Map(HeaderFileType.scala -> spdxLine, HeaderFileType.java -> spdxLine, HeaderFileType("sbt") -> spdxLine)
}
// The warnings on, each an error under -Werror (docs/design-rules.md, "What enforces the rules today").
// -Winfer-union refuses a type argument inferred as a union, such as the error type of an Either built
// from two error types: a union of errors is declared where it is meant (ERR-1).
// -Wvalue-discard and -Wnonunit-statement stay off: at Scala 3.9.0 they found 239 and 257 sites in main
// sources (2026-11-14), mostly fluent CborOut calls in node/ntc (#201). Generated models are warning-silent.
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-Wunused:imports",
  "-Wunused:privates",
  "-Wunused:locals",
  "-Wunused:explicits",
  "-Wunused:implicits",
  "-Wunused:patvars",
  "-Wunused:nowarn",
  "-Wimplausible-patterns",
  "-Wrecurse-with-default",
  "-Wwrong-arrow",
  "-Wshadow:all",
  "-Wenum-comment-discard",
  "-Winfer-union",
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
// JDK 25 warns where a restricted method is called and where `sun.misc.Unsafe`'s memory methods are used
// (issue 589). blst loads its JNI library through `java.lang.System::load`, and Scala 3's lazy vals and the
// build's hashing call `Unsafe`. Allowing both keeps the test logs free of the warning today, and stops a
// later JDK from blocking the call the first time a test makes it. The build JVM gets the same two flags from
// `scripts/sbtw`, because the default test group runs there; a forked group that sets its own
// `runJVMOptions` replaces the options `Test / javaOptions` supplies, so it names them again.
// jitWorkaround (#1521): on JDK 25, C2 miscompiled code reached from `TxChecks.collateral` (a null `Collateral.fee`);
// turning off its reduction of allocation merges stopped it. Every JVM that runs Scarab's code takes these, so they
// ride on jdk25Flags to every forked JVM here, and `scripts/sbtw` gives them to the build JVM. The one list is
// `scripts/jit_workaround.py`; `scripts/check_jit_workaround.py` holds this copy to it. jdk25FlagsJitDefaults is for
// the miscompilation's own regression test alone, which must run on the JIT's defaults to test anything; the check
// names the groups that may use it.
val jitWorkaround = Seq("-XX:+UnlockDiagnosticVMOptions", "-XX:-ReduceAllocationMerges")
val jdk25FlagsJitDefaults = Seq("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
val jdk25Flags = jdk25FlagsJitDefaults ++ jitWorkaround
ThisBuild / Test / javaOptions ++= jdk25Flags

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
//   crypto <- uplc <- plutus
//   protocol, plutus <- ledger <- consensus <- storage
//   models <- storage (the Haskell ledger-state codec reuses the models runtime)
//   storage, network <- node
// Initialisation safety applies to main sources (-Wsafe-init).
// Test sources exclude it because munit registers tests in constructors; the initial sweep found
// 19 warnings for that idiom and none in main sources.
// WartRemover (issue 650): the imperative and partial patterns the code rules disallow (docs/design-rules.md,
// FUN-1 to FUN-3, ERR-4 and ERR-5: functional code by default; mutation only on measured performance paths,
// kept local; no throw for domain errors; no partial accessor on a value not provably present), and from
// ArrayEquals on, mistakes the compiler lets through: reference equality on arrays, scala.Enumeration, a
// descendant of a sealed type that is neither final nor sealed, a lock on a value-based class, a String built
// with + from a value that is not one, an inferred Product, a conversion to the type a value already has and
// a size compared where sizeIs would do. Main sources only; generated code is excluded. try/catch/finally has
// no wart and is checked by scripts/check_imperative.py. DisallowedWartsSuite (tools tests) shows that each
// wart but ThreadSleep fires; a ThreadSleep probe would be a sleep to scripts/check_test_sleeps.py.
lazy val disallowedWarts: Seq[wartremover.Wart] = Seq(
  Wart.Var,
  Wart.While,
  Wart.Throw,
  Wart.Return,
  Wart.Null,
  Wart.AsInstanceOf,
  Wart.IsInstanceOf,
  Wart.MutableDataStructures,
  Wart.OptionPartial,
  Wart.TryPartial,
  Wart.EitherProjectionPartial,
  Wart.IterableOps,
  Wart.TripleQuestionMark,
  Wart.ThreadSleep,
  Wart.GlobalExecutionContext,
  Wart.ArrayEquals,
  Wart.Enumeration,
  Wart.LeakingSealed,
  Wart.SynchronizedValueBasedClasses,
  Wart.StringPlusAny,
  Wart.Product,
  Wart.RedundantConversions,
  Wart.SizeIs
)

// The design rules' own warts, in the `warts` project below, which every module loads through
// `wartremoverClasspaths` (WartRemover.dependsOnLocalProjectWarts). In main sources, as the warts above:
// SystemTimeAndUnsafeRun (EFF-5, EFF-6, ERR-4) and FloatingPointInLedger (ARI-1) are errors, whose kept site carries
// @SuppressWarnings with the wart's full name on its smallest definition. StringControlFlow (STR-3) is a warning,
// which -Werror makes an error, so that -Wconf can silence it in the parsers of `project/StringParsers.scala` and
// nowhere else.
def ruleWart(name: String): wartremover.Wart = Wart.custom(s"xyz.kd5ujc.scarab.warts.$name")
lazy val ruleWartErrors: Seq[wartremover.Wart] = Seq("SystemTimeAndUnsafeRun", "FloatingPointInLedger").map(ruleWart)
lazy val ruleWartWarnings: Seq[wartremover.Wart] = Seq("StringControlFlow").map(ruleWart)
lazy val ruleWarts: Seq[wartremover.Wart] = ruleWartErrors ++ ruleWartWarnings

def scarab(id: String, dir: String = null): Project =
  Project(id, file(Option(dir).getOrElse(id)))
    // sbt-tpolecat would add its own option set; the options above are the whole set here.
    .disablePlugins(TpolecatPlugin)
    .settings(
      name := s"scarab-$id",
      Compile / scalacOptions += "-Wsafe-init",
      Test / scalacOptions -= "-Wsafe-init",
      Compile / compile / wartremoverErrors ++= disallowedWarts,
      wartremoverExcluded += sourceManaged.value,
      libraryDependencies ++= testDependencies
    )
    .settings(wartremover.WartRemover.dependsOnLocalProjectWarts(LocalProject("warts")))
    .settings(
      Compile / compile / wartremoverErrors ++= ruleWartErrors,
      Compile / compile / wartremoverWarnings ++= ruleWartWarnings,
      Compile / scalacOptions ++= StringParsers.options((ThisBuild / baseDirectory).value)
    )

// The design rules' own warts (above), compiled against the WartRemover artifact of this Scala version, as
// WartRemover's own example of a project of custom warts does for Scala 3. It is build tooling: its code runs in
// the compiler, so the warts it defines do not run on it, and it ships nothing.
lazy val warts = project
  .in(file("warts"))
  .disablePlugins(TpolecatPlugin)
  .settings(
    name := "scarab-warts",
    libraryDependencies ++= Seq(
      Libraries.wartremoverApi,
      Libraries.catsEffect % Test,
      Libraries.munitTest
    )
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

// Scarab's own UPLC evaluator (ADR 0027), the node's only Plutus evaluator: the term model, Flat
// decoder, CEK machine, builtins, costing and budget.
// Libraries.catsParse: The textual syntax of UPLC (`uplc.text.UplcParser`), which the node itself
// never reads.
// Test / unmanagedResourceDirectories: The vendored plutus reference corpus (`plutus-reference`)
// and the Wycheproof vectors, read as test resources without a dependency on `plutus`
// (`LocalProject`, as `plutus` itself depends on this project).
lazy val uplc = scarab("uplc")
  .dependsOn(
    codec % "compile->compile;test->test",
    crypto % "compile->compile;test->test"
  )
  .settings(
    libraryDependencies ++= Seq(
      Libraries.cats,
      Libraries.catsMtl,
      Libraries.catsParse
    ),
    Test / unmanagedResourceDirectories += (LocalProject("plutus") / baseDirectory).value / "src/test/resources"
  )

// The evaluation algebra the ledger rules use (`ScriptEvaluator`, `ScriptDecoder`) and its interpreter
// over the own evaluator (`ScarabEvaluator`).
lazy val plutus = scarab("plutus")
  .dependsOn(
    codec % "compile->compile;test->test",
    uplc % "compile->compile;test->test"
  )
  .settings(
    libraryDependencies ++= Seq(
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeParserTest
    ),
    Test / testGrouping := {
      // ProgramCacheMemorySuite checks what the program cache holds in a separate JVM with the
      // shipped launchers' 768 MiB heap, reading the fixtures from the build's root as the build
      // JVM does. Other suites run in the build JVM.
      val (bounded, others) =
        (Test / definedTests).value.partition(_.name.endsWith(".ProgramCacheMemorySuite"))
      val options = Vector("-Xmx768m") ++ jdk25Flags
      val fork = (Test / forkOptions).value
        .withRunJVMOptions(options)
        .withWorkingDirectory((ThisBuild / baseDirectory).value)
      Seq(
        Tests.Group("plutus", others, Tests.InProcess),
        Tests.Group("heap-bound", bounded, Tests.SubProcess(fork))
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
    ),
    Test / testGrouping := {
      // MainnetCollateralJitSuite runs TxChecks.collateral under C2 in a JVM of its own (#1521):
      // `-Xbatch` compiles each method before it runs again, and two processors give the profile
      // under which C2 miscompiled it. The options name the JIT's defaults on purpose: this group
      // checks the code on the JIT as it ships, so it takes no flag that turns an optimisation off.
      // It reads the fixtures from the build's root, as the build JVM does.
      val (jit, others) =
        (Test / definedTests).value.partition(_.name.endsWith(".MainnetCollateralJitSuite"))
      val options = Vector("-Xbatch", "-XX:ActiveProcessorCount=2") ++ jdk25FlagsJitDefaults
      val fork = (Test / forkOptions).value
        .withRunJVMOptions(options)
        .withWorkingDirectory((ThisBuild / baseDirectory).value)
      Seq(
        Tests.Group("ledger", others, Tests.InProcess),
        Tests.Group("c2-collateral", jit, Tests.SubProcess(fork))
      )
    }
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
  // Storage concurrency tests use the shared simulated scheduler and failure-reporting runtime.
  .dependsOn(
    consensus % "compile->compile;test->test",
    models,
    network % "test->test"
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
      Libraries.rocksdb,
      Libraries.sqliteJdbc
    ),
    Test / testGrouping := {
      // MemoryBoundSuite checks retained decode heap in a separate 256 MiB JVM (#94).
      // Other suites run in the build JVM.
      val (bounded, others) =
        (Test / definedTests).value.partition(_.name.endsWith(".MemoryBoundSuite"))
      val options = Vector("-Xmx256m") ++ jdk25Flags
      val fork = (Test / forkOptions).value.withRunJVMOptions(options)
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
// excludeDependencies: http4s-core 0.23.37 declares log4s, but no class on the runtime classpath
// refers to it (#214): http4s logs through log4cats, which `EmberServerBuilder.default` builds over
// SLF4J (LibraryLogs).
// assembly / packageOptions: The node calls native code (the local socket's unlink through the FFM
// linker, the Plutus crypto's JNI library); `java -jar` reads this attribute in place of
// `--enable-native-access=ALL-UNNAMED`, so the JDK does not warn on the first such call.
// Compile / run / javaOptions: From a classpath the JAR's Enable-Native-Access attribute does not
// apply, so the node run this way needs the flags itself (docker/candidate.Dockerfile; issue 589)
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
      // Maven's metadata of a jar, licence files among it (sqlite-jdbc's): the licence texts the
      // assembly carries are under META-INF/scarab/licenses (licenses/index.json)
      case path if path.startsWith("META-INF/maven/") => MergeStrategy.discard
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
        root / "crypto" / "licenses" -> "crypto",
        root / "ledger" / "licenses" -> "ledger"
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
    Compile / run / javaOptions ++= jdk25Flags
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

// Benchmarks, probes and the programs that write fixtures: run by hand (`tools/runMain <class>`), never as
// tests and never shipped. They use the modules' test kits, so they follow the rules of test code
// (docs/design-rules.md, TOOL-2): no `-Wsafe-init`, no WartRemover errors or warnings. Its tests hold
// DisallowedWartsSuite, which shows that each wart of `disallowedWarts` and `ruleWarts` but ThreadSleep fires: it
// reads their names from a source the build writes, and types its probes with WartRemover's own test traverser.
lazy val tools = scarab("tools")
  .dependsOn(
    codec % "compile->compile;compile->test",
    crypto % "compile->compile;compile->test",
    protocol % "compile->compile;compile->test",
    network % "compile->compile;compile->test",
    uplc % "compile->compile;compile->test",
    plutus % "compile->compile;compile->test",
    ledger % "compile->compile;compile->test",
    consensus % "compile->compile;compile->test",
    storage % "compile->compile;compile->test",
    node % "compile->compile;compile->test",
    models % "compile->compile;compile->test",
    warts % "test->compile"
  )
  .settings(
    Compile / scalacOptions -= "-Wsafe-init",
    Compile / compile / wartremoverErrors := Nil,
    Compile / compile / wartremoverWarnings := Nil,
    publish / skip := true,
    libraryDependencies += Libraries.wartremoverTest,
    Test / sourceGenerators += Def.task {
      val source = (Test / sourceManaged).value / "DisallowedWarts.scala"
      def quoted(names: Seq[String]): String = names.map(name => "\"" + name + "\"").mkString("List(", ", ", ")")
      val names = quoted((disallowedWarts ++ ruleWarts).map(_.clazz))
      val installed = ruleWartsInstalled.value.mkString(",\n    ")
      IO.write(
        source,
        s"""package xyz.kd5ujc.scarab
           |
           |object DisallowedWarts:
           |  val names: List[String] = $names
           |  val ruleErrors: List[String] = ${quoted(ruleWartErrors.map(_.clazz))}
           |  val ruleWarnings: List[String] = ${quoted(ruleWartWarnings.map(_.clazz))}
           |  val parsers: Int = ${StringParsers.files.size}
           |  val installed: List[(String, List[String], List[String], Int)] = List(
           |    $installed
           |  )
           |""".stripMargin
      )
      Seq(source)
    }.taskValue
  )

// The modules whose main sources the design rules' own warts check, and for each the warts its compiler refuses
// and the count of its StringControlFlow allowlist options, which DisallowedWartsSuite compares with the lists.
lazy val ruleWartModules: ScopeFilter = ScopeFilter(
  inProjects(codec, crypto, protocol, network, uplc, plutus, ledger, consensus, storage, node, modelsGen, models)
)
lazy val ruleWartsInstalled: Def.Initialize[Task[Seq[String]]] = Def.task {
  def quoted(names: Seq[String]): String = names.map(name => "\"" + name + "\"").mkString("List(", ", ", ")")
  val errors = quoted((Compile / compile / wartremoverErrors).value.map(_.clazz))
  val warnings = quoted((Compile / compile / wartremoverWarnings).value.map(_.clazz))
  val allowlisted = (Compile / scalacOptions).value.count(_.endsWith("&msg=wartremover.StringControlFlow:s"))
  s"""("${thisProject.value.id}", $errors, $warnings, $allowlisted)"""
}.all(ruleWartModules)

// The sources that belong to no module, whose headers the root project checks: the build definition (without
// the git-ignored metals.sbt and local.sbt) and the conversion programs kept beside the scripts.
lazy val sourcesOutsideModules = Def.task {
  val root = baseDirectory.value
  val build = (root * "build.sbt") +++ (root / "project" * (("*.scala" || "*.sbt") -- "metals.sbt" -- "local.sbt"))
  (build +++ (root / "scripts" / "fixtures" ** "*.scala")).get.filterNot(_.getPath.contains("/target/"))
}

// Every module's test suites as sbt detects them, for CI's test shards (scripts/ci_test_shards.py): a line
// `<module>\t<directory>\t<suite>` for each, in the order of `modules`, and `<module>\t<directory>` for a module
// without suites.
lazy val testSuiteInventory = taskKey[File]("Write every module's test suites for CI's test shards")
def testSuitesOfModules: Def.Initialize[Task[Seq[(String, File, Seq[String])]]] =
  modules.map { m =>
    Def.task((m, (LocalProject(m) / baseDirectory).value, (LocalProject(m) / Test / definedTestNames).value))
  }.join.apply(_.join)

lazy val root = project
  .in(file("."))
  .disablePlugins(TpolecatPlugin)
  .aggregate(
    warts,
    codec,
    crypto,
    protocol,
    network,
    uplc,
    plutus,
    ledger,
    consensus,
    storage,
    node,
    modelsGen,
    models,
    fuzz,
    tools
  )
  .settings(
    Compile / headerSources ++= sourcesOutsideModules.value,
    testSuiteInventory := {
      val root = baseDirectory.value
      val lines = testSuitesOfModules.value.flatMap { case (module, directory, suites) =>
        val relative = IO
          .relativize(root, directory)
          .getOrElse(sys.error(s"module $module is outside the build: $directory"))
        if (suites.isEmpty) Seq(s"$module\t$relative") else suites.map(suite => s"$module\t$relative\t$suite")
      }
      val output = target.value / "ci" / "test-suites.tsv"
      IO.writeLines(output, lines)
      output
    },
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
  "warts",
  "codec",
  "crypto",
  "protocol",
  "network",
  "uplc",
  "plutus",
  "ledger",
  "consensus",
  "storage",
  "node",
  "modelsGen",
  "models",
  "fuzz",
  "tools"
)

// `check` runs these in order: formatting, the SPDX headers, every module's test sources
// compiled, every module's tests, then scalafix. CI runs the same commands in two parts (.github/workflows/ci.yml):
// its build job runs `checkBuild`, which is `check` without the tests, and its test shards run every suite of every
// module (scripts/ci_test_shards.py, over the suites `testSuiteInventory` lists).
val checkBeforeTests =
  Seq("scalafmtCheckAll", "headerCheckAll") ++ modules.map(m => s"$m/Test/compile")
val checkTests = modules.map(m => s"$m/Test/test")
val checkAfterTests = Seq("scalafixAll --check")
addCommandAlias("check", (checkBeforeTests ++ checkTests ++ checkAfterTests).mkString(";", ";", ""))
addCommandAlias("checkBuild", (checkBeforeTests ++ checkAfterTests).mkString(";", ";", ""))
