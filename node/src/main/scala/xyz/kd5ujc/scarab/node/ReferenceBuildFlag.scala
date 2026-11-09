// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
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

  /** The codec of `build`'s layout, or the refusal of a build not implemented yet. */
  def layout(build: ReferenceBuild): Either[ConfigError, ReferenceLayout] =
    ReferenceLayout.of(build).leftMap(e => ConfigError(ConfigProblem.ReferenceBuildNotYet(e)))

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
