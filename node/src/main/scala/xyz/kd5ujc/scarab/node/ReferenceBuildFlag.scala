// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, ReferenceBuildNotYet}
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

/** `--reference-build <build>` of `follow`, `import-ledger-snapshot` and `export-ledger-snapshot`
  * (#826): the build of `cardano-node` the network runs ([[ReferenceBuild]]), `11.1` without the
  * flag.
  */
object ReferenceBuildFlag:
  /** What the flag means, after a command's usage line: the value must be the build the network
    * runs.
    */
  val Help: String =
    "--reference-build: the cardano-node build the network runs, whose database layout (and, later, rules) this node mirrors; a value other than the network's is like running another binary: the node would follow other rules than its peers. " +
      ReferenceBuild.values
        .map(b =>
          s"${b.name}: ${b.stability.label}, cardano-node ${b.pins.nodeShort}" +
            (if b == ReferenceBuild.Default then ", the default" else "") +
            (if b.wantsExperimentalHardForks then ", for ExperimentalHardForksEnabled" else "")
        )
        .mkString("; ")

  /** The build a flag value names (none: the default), or the typed refusal of an unknown one. */
  def parse(value: Option[String], usage: String): Either[ConfigError, ReferenceBuild] =
    value.fold(Right(ReferenceBuild.Default))(text =>
      ReferenceBuild
        .parse(text)
        .leftMap(e =>
          ConfigError(ConfigProblem.WithUsage(ConfigProblem.ReferenceBuildUnknown(e), usage))
        )
    )

  /** The codec of `build` 's layout: what `import-ledger-snapshot` and `export-ledger-snapshot` add
    * or strip Scarab's envelope with. Every build's layout has one.
    */
  def layout(build: ReferenceBuild): ReferenceLayout = ReferenceLayout.of(build)

  /** The codec of `build` 's layout for `follow`, or the refusal of a build whose rules this build
    * does not run yet ([[ReferenceBuildNotYet.Rules]], #826 step c).
    */
  def follows(build: ReferenceBuild): Either[ConfigError, ReferenceLayout] = build match
    case ReferenceBuild.Release11_1 => Right(layout(build))
    case ReferenceBuild.LeiosB004fe0c =>
      Left(ConfigError(ConfigProblem.ReferenceBuildNotYet(ReferenceBuildNotYet.Rules(build))))

  /** The fields of the line that names the build a command runs with: its name, stability and pins,
    * the exact pinned release among them.
    */
  def fields(build: ReferenceBuild): List[(String, io.circe.Json)] =
    List(
      "build" -> io.circe.Json.fromString(build.name),
      "stability" -> io.circe.Json.fromString(build.stability.label),
      "node" -> io.circe.Json.fromString(build.pins.node),
      "ledger" -> io.circe.Json.fromString(build.pins.ledger),
      "ledgerBinary" -> io.circe.Json.fromString(build.pins.binary),
      "consensus" -> io.circe.Json.fromString(build.pins.consensus)
    )

  /** Whether to warn that `build` runs without `ExperimentalHardForksEnabled`: an unstable build is
    * meant for it.
    */
  def warns(build: ReferenceBuild, experimentalHardForksEnabled: Boolean): Boolean =
    build.wantsExperimentalHardForks && !experimentalHardForksEnabled
