// SPDX-License-Identifier: Apache-2.0
import sbt._

/** Every version and every library of the build. `build.sbt` only picks from here. */
object Dependencies {

  object V {
    val cats = "2.13.0"
    val catsEffect = "3.7.1"
    val catsMtl = "1.7.0"
    val fs2 = "3.14.0"
    val circe = "0.14.16" // brings jawn-parser 1.7.0 (fixes jawn advisories)
    val http4s = "0.23.37"
    val catsParse = "1.1.0"
    val monocle = "3.3.0"
    val kittens = "3.5.0"
    val scodec = "2.3.3" // needs scodec-bits 1.2.4; fs2 already brings 1.2.5
    // The API the node's own SLF4J backend implements (node/log/LibraryLogs.scala); http4s brings
    // 1.7.36 through log4cats, whose calls 2.0 keeps.
    val slf4j = "2.0.17"
    val dnsjava = "3.6.5"

    val scalus = "1.4.0"
    // Scalus 1.4.0 is built against 1.86 and would evict an older pin; declared equals resolved.
    val bouncyCastle = "1.86"
    val curve25519 = "0.1.3"
    val blstJava = "0.3.2"
    val rocksdb = "10.2.1"

    val munit = "1.0.2"
    val munitScalaCheck = "1.0.0"
    val munitCatsEffect = "2.0.0"
    val disciplineMunit = "2.0.0"
  }

  object Libraries {
    val cats = "org.typelevel" %% "cats-core" % V.cats
    val catsKernel = "org.typelevel" %% "cats-kernel" % V.cats
    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
    val catsMtl = "org.typelevel" %% "cats-mtl" % V.catsMtl
    val fs2Core = "co.fs2" %% "fs2-core" % V.fs2
    val fs2Io = "co.fs2" %% "fs2-io" % V.fs2
    val circeParser = "io.circe" %% "circe-parser" % V.circe
    val http4sEmberServer = "org.http4s" %% "http4s-ember-server" % V.http4s
    val http4sDsl = "org.http4s" %% "http4s-dsl" % V.http4s
    val http4sCirce = "org.http4s" %% "http4s-circe" % V.http4s
    val catsParse = "org.typelevel" %% "cats-parse" % V.catsParse
    val slf4jApi = "org.slf4j" % "slf4j-api" % V.slf4j
    // DNS SRV and address records with answer TTLs for the node's relay-root resolver.
    val dnsjava = "dnsjava" % "dnsjava" % V.dnsjava
    // Optics and derived Eq/Show for the ledger-state mirror (ledger/state).
    val monocleCore = "dev.optics" %% "monocle-core" % V.monocle
    val monocleMacro = "dev.optics" %% "monocle-macro" % V.monocle
    val kittens = "org.typelevel" %% "kittens" % V.kittens
    // Read-once fixed layouts such as ImmutableDB indices; hot-path framing and CBOR use their own codecs.
    val scodecCore = "org.scodec" %% "scodec-core" % V.scodec

    // The UPLC machine and builtins; all Scalus artifacts move together.
    val scalus = "org.scalus" %% "scalus" % V.scalus
    val bouncyCastle = "org.bouncycastle" % "bcprov-jdk18on" % V.bouncyCastle
    val curve25519 = "com.weavechain" % "curve25519-elisabeth" % V.curve25519
    // BLS12-381 (MinSig voting keys of Leios); Apache-2.0, bundles libblst. Declared directly, not via Scalus.
    val blstJava = "foundation.icon" % "blst-java" % V.blstJava
    // The on-disk UTxO set (an embedded LSM tree; native library bundled in the jar).
    val rocksdb = "org.rocksdb" % "rocksdbjni" % V.rocksdb
    // Declared by http4s-core but referenced by no class on the node's runtime classpath (#214);
    // http4s logs through log4cats, which stays (docs/licenses.md).
    val unusedLog4s = ExclusionRule("org.log4s", "log4s_3")

    // The tests of every module: munit, ScalaCheck, and the cats law and effect test kits.
    val catsLawsTest = "org.typelevel" %% "cats-laws" % V.cats % Test
    val catsKernelLawsTest = "org.typelevel" %% "cats-kernel-laws" % V.cats % Test
    val catsMtlLawsTest = "org.typelevel" %% "cats-mtl-laws" % V.catsMtl % Test
    val disciplineMunitTest = "org.typelevel" %% "discipline-munit" % V.disciplineMunit % Test
    val munitCatsEffectTest = "org.typelevel" %% "munit-cats-effect" % V.munitCatsEffect % Test
    val catsEffectTestkitTest = "org.typelevel" %% "cats-effect-testkit" % V.catsEffect % Test
    val munitScalaCheckTest = "org.scalameta" %% "munit-scalacheck" % V.munitScalaCheck % Test
    val munitTest = "org.scalameta" %% "munit" % V.munit % Test
    val monocleLawTest = "dev.optics" %% "monocle-law" % V.monocle % Test
    val circeParserTest = "io.circe" %% "circe-parser" % V.circe % Test
  }

  // Scala 3 has no compiler plugins in use here: better-monadic-for and kind-projector (the two of
  // the Scala 2 builds of this owner) do not apply, since for-comprehension patterns, `*` type
  // lambdas and `?` placeholders are built into the language.
  object CompilerPlugin {}
}
