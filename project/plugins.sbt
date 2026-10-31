// SPDX-License-Identifier: Apache-2.0
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
// Compiler options stay in build.sbt (tpolecat's own set is switched off there).
addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.7")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.9")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.2")
// Not on by default: `sbt coverage test coverageReport`.
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
// `sbt dependencyUpdates`.
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.7.0")
// `sbt dependencyTree` (sbt's built-in dependency graph).
addDependencyTreePlugin

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.5.0")

// Tag-derived version data; frozen exports use the pre-verified identity instead of date fallback.
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.0")
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "3.6.3")
