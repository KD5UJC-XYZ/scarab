// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, ReferenceBuildNotYet}
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

/** `--reference-build <build>` of `follow`, `import-haskell-db`, `import-ledger-snapshot` and
  * `export-ledger-snapshot` (#826): the build of `cardano-node` the network runs
  * ([[ReferenceBuild]]), `11.1` without the flag.
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
    * or strip Scarab's envelope with; or the refusal of a build whose layout this build does not
    * read or write yet ([[ReferenceBuildNotYet.Layout]]).
    */
  def layout(build: ReferenceBuild): Either[ConfigError, ReferenceLayout] =
    ReferenceLayout.of(build).leftMap(e => ConfigError(ConfigProblem.ReferenceBuildNotYet(e)))

  /** The codec of `build` 's layout for `follow`, or the refusal of a build whose layout or rules
    * this build does not run yet ([[ReferenceBuildNotYet.Layout]]; [[ReferenceBuildNotYet.Rules]],
    * #826 step c). The Leios build's Dijkstra runs on the era rules (#54).
    */
  def follows(build: ReferenceBuild): Either[ConfigError, ReferenceLayout] = build match
    case ReferenceBuild.Release11_1 | ReferenceBuild.Leios => layout(build)
    case ReferenceBuild.Release11_2 =>
      layout(build).flatMap(_ =>
        Left(ConfigError(ConfigProblem.ReferenceBuildNotYet(ReferenceBuildNotYet.Rules(build))))
      )

  /** `--header-first` under `build`: a release build may run without header-first validation, as
    * Scarab's own block-only mode; the Leios build's ChainSync client counts and fetches endorser
    * blocks from the headers it validates (`leiosMsgRollForwardCallback`, ouroboros-consensus
    * 2416493b `Network/NodeToNode.hs` 484-526), and the reference has no block-only mode, so `off`
    * is refused there ([[ConfigProblem.HeaderFirstRequired]]), in either consensus mode.
    */
  def headerFirst(build: ReferenceBuild, on: Boolean): Either[ConfigError, Unit] = build match
    case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 => Right(())
    case ReferenceBuild.Leios =>
      Either.cond(on, (), ConfigError(ConfigProblem.HeaderFirstRequired(build)))

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
