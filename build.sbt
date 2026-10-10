// Private corpus tests are explicit and never counted in the public check.
lazy val PrivateCorpus = config("privateCorpus") extend Test
lazy val requirePrivateCorpus = taskKey[Unit]("Fail unless all separately obtained private corpus directories exist")
lazy val privateCorpusSettings = inConfig(PrivateCorpus)(Defaults.testSettings) ++ Seq(
  PrivateCorpus / dependencyClasspath := (Test / fullClasspath).value,
  PrivateCorpus / unmanagedResourceDirectories := (Test / unmanagedResourceDirectories).value,
  PrivateCorpus / parallelExecution := false,
  requirePrivateCorpus := {
    val rootDir = (LocalRootProject / baseDirectory).value
    val required = Seq(
      "fixtures/chain-fetch",
      "fixtures/post-byron",
      "fixtures/body-commitment",
      "fixtures/block-evidence",
      "core/src/test/resources/historical-index",
      "core/src/test/resources/post-byron",
      "core/src/test/resources/body-commitment",
      "core/src/test/resources/block-evidence"
    )
    val missing = required.filterNot(p => (rootDir / p).isDirectory && ((rootDir / p) ** "*").get.exists(_.isFile))
    if (missing.nonEmpty) sys.error("Private corpus unavailable; no private tests ran. Missing: " + missing.mkString(", "))
  },
  PrivateCorpus / test := ((PrivateCorpus / test) dependsOn requirePrivateCorpus).value
)

// Align the Cats family with the version already selected by pinned Scalus in app.
// Law integrations stay Test-only; evaluator, crypto and Cats Effect pins are unchanged.
val catsVersion = "2.13.0"
val catsEffectVersion = "3.6.3"
val munitVersion = "1.0.2"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.23.0"
ThisBuild / organization := "dev.cardano.research"
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror")
ThisBuild / publish / skip := true
// Bound test overlap under the documented APC4 / 2 GiB research runner.
ThisBuild / Test / parallelExecution := false
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)
lazy val core = project.in(file("core")).configs(PrivateCorpus).settings(privateCorpusSettings).settings(
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
lazy val vm = project.in(file("vm")).dependsOn(core).configs(PrivateCorpus).settings(privateCorpusSettings).settings(
  libraryDependencies ++= Seq(
    // Direct use by the bounded BLAKE2b provider; same version already supplied by Scalus.
    "org.bouncycastle" % "bcprov-jdk18on" % "1.85.2",
    ("org.scalus" %% "scalus" % "1.3.0")
      .exclude("foundation.icon", "blst-java")
      .exclude("org.scalus", "scalus-secp256k1-jni"),
    "org.scalameta" %% "munit" % munitVersion % Test
  )
)
lazy val network = project.in(file("network")).dependsOn(core).configs(PrivateCorpus).settings(privateCorpusSettings).settings(
  libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test
)
lazy val networkRuntime = project.in(file("network-runtime")).dependsOn(network).configs(PrivateCorpus).settings(privateCorpusSettings).settings(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion % Test,
    "org.scalameta" %% "munit" % munitVersion % Test
  )
)
lazy val ledger = project.in(file("ledger")).dependsOn(core).configs(PrivateCorpus).settings(privateCorpusSettings).settings(
  libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test
)
lazy val ledgerRuntime = project.in(file("ledger-runtime")).dependsOn(ledger).configs(PrivateCorpus).settings(privateCorpusSettings).settings(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.scalameta" %% "munit" % munitVersion % Test
  ),
  Test / fork := true,
  Test / javaOptions ++= Seq("-XX:ActiveProcessorCount=2", "-Xmx512m", "-Dcardano.replay.test.forked=true")
)
lazy val fetcher = project.in(file("fetcher")).dependsOn(core, networkRuntime).configs(PrivateCorpus).settings(privateCorpusSettings).settings(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion % Test,
    "org.scalameta" %% "munit" % munitVersion % Test
  )
)
lazy val runtimeClasspathFile = taskKey[File]("Write the resolved application runtime classpath")
lazy val app = project.in(file("app")).dependsOn(core, vm, network, networkRuntime, ledger, ledgerRuntime, fetcher).configs(PrivateCorpus).settings(privateCorpusSettings).settings(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion % Test,
    "org.scalameta" %% "munit" % munitVersion % Test
  ),
  runtimeClasspathFile := {
    val output = target.value / "runtime-classpath.txt"
    IO.write(output, (Runtime / fullClasspath).value.files.mkString(java.io.File.pathSeparator))
    output
  },
  Compile / run / fork := true,
  Compile / run / baseDirectory := (LocalRootProject / baseDirectory).value
)
lazy val root = project.in(file(".")).aggregate(core, vm, network, networkRuntime, ledger, ledgerRuntime, fetcher, app)
// Compile every test module first, then complete module test commands one at a time.
addCommandAlias(
  "check",
  ";scalafmtCheckAll;core/Test/compile;vm/Test/compile;network/Test/compile;networkRuntime/Test/compile;ledger/Test/compile;ledgerRuntime/Test/compile;fetcher/Test/compile;app/Test/compile;core/Test/test;vm/Test/test;network/Test/test;networkRuntime/Test/test;ledger/Test/test;ledgerRuntime/Test/test;fetcher/Test/test;app/Test/test"
)

addCommandAlias("checkPrivateCorpus", ";core/requirePrivateCorpus;core/PrivateCorpus/test;fetcher/PrivateCorpus/test;app/PrivateCorpus/test")

// Offline differential translator: all implementation lives in Test, never app/runtime.
lazy val translator = project.in(file("translator")).dependsOn(core, vm).settings(
  libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test,
  Test / parallelExecution := false
)
