// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.DomainError

/** The build of `cardano-node` that Scarab mirrors (#826): the build the network runs. Its value
  * selects the layout of the reference's database that Scarab reads and writes and, from #826 step
  * c, the ledger and consensus rules it follows. That is the reference's own model: one binary is
  * one layout and one rule set. A value other than the build the network runs is like running
  * another binary: the node would follow other rules than its peers. Operators are trusted to name
  * the right one; a warning is given where a mistake is visible, nothing more.
  *
  * The names are what `--reference-build` takes. A stable build is named by its release line,
  * `<major>.<minor>`: the reference upgrades a patch release of a line in place, without a replay,
  * so the patches of a line share its value. A patch-level value is added only when the reference's
  * release notes require a replay or a database conversion at that patch. An unstable Leios build
  * is named by its node commit, `leios-<8 hex digits>`; it is meant for
  * `ExperimentalHardForksEnabled` and can change or go away at any time. Several values may share
  * one layout: a value is not a codec.
  *
  * Each value's pins are registered in `docs/data/reference-pins.json`, which lists this file as a
  * dependent of each; `docs/playbooks/add-a-reference-build.md` lists every place a new value or a
  * promotion from unstable to stable touches.
  */
enum ReferenceBuild(
    val name: String,
    val stability: ReferenceBuild.Stability,
    val pins: ReferenceBuild.Pins
) derives CanEqual:
  /** The cardano-node 11.1 release line, behaving as its pinned release 11.1.3 behaves:
    * cardano-ledger f649f9751074d2ab3de033fc3912f29c9862c1f5 (cardano-ledger-binary at
    * 048b78d2807ce9644dbe483c605625824f6be88f) and ouroboros-consensus
    * 82ecba329d7d054340bf707d44fe6e9ac27cec40, as the 11.1.3 release notes list them. The known
    * differences inside the line are in the playbook.
    */
  case Release11_1
      extends ReferenceBuild(
        "11.1",
        ReferenceBuild.Stability.Stable,
        ReferenceBuild.Pins(
          "11.1.3",
          "f649f9751074d2ab3de033fc3912f29c9862c1f5",
          "048b78d2807ce9644dbe483c605625824f6be88f",
          "82ecba329d7d054340bf707d44fe6e9ac27cec40"
        )
      )

  /** The build of the public Leios testnet (release rc-2026w44, #711): cardano-node
    * b004fe0c50913dcdaa827a98e68a43d0ccce152a (branch leios-prototype), with cardano-ledger
    * 9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c (its binary package included) and ouroboros-consensus
    * 2416493bfdbe93b761b3a4bb1ccf2b4a913e5e4b. Unstable.
    */
  case LeiosB004fe0c
      extends ReferenceBuild(
        "leios-b004fe0c",
        ReferenceBuild.Stability.Unstable,
        ReferenceBuild.Pins(
          "b004fe0c50913dcdaa827a98e68a43d0ccce152a",
          "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c",
          "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c",
          "2416493bfdbe93b761b3a4bb1ccf2b4a913e5e4b"
        )
      )

  /** An unstable build is meant for use with `ExperimentalHardForksEnabled`: without it, the node
    * is warned (#826).
    */
  def wantsExperimentalHardForks: Boolean = stability == ReferenceBuild.Stability.Unstable

  /** The name, the stability and the pinned node: `11.1 (stable, cardano-node 11.1.3)`. */
  def describe: String =
    s"$name (${stability.label}, cardano-node ${pins.nodeShort})"

object ReferenceBuild:
  /** What the node runs without `--reference-build`. */
  val Default: ReferenceBuild = Release11_1

  /** Whether the reference publishes the build as a release. */
  enum Stability derives CanEqual:
    /** A release line of `cardano-node`. */
    case Stable

    /** A prototype build that can change or go away at any time. */
    case Unstable

    def label: String = this match
      case Stable   => "stable"
      case Unstable => "unstable"

  /** The pinned revisions of a build. `node` is a release tag for a stable build and the full
    * commit for an unstable one; `ledger`, `binary` (cardano-ledger-binary, which can come from
    * another cardano-ledger revision than the rest) and `consensus` are full commits.
    */
  final case class Pins(node: String, ledger: String, binary: String, consensus: String):
    /** The node's tag, or the first eight digits of its commit. */
    def nodeShort: String = if node.contains('.') then node else node.take(8)

  /** The build named `text`, as `--reference-build` takes it. Total: any other text is
    * [[UnknownReferenceBuild]].
    */
  def parse(text: String): Either[UnknownReferenceBuild, ReferenceBuild] =
    values.find(_.name == text).toRight(UnknownReferenceBuild(text))

/** A `--reference-build` value, or a recorded one, that names no build this build knows. */
final case class UnknownReferenceBuild(text: String) extends DomainError:
  def message: String =
    s"unknown reference build '$text' (known: ${ReferenceBuild.values.map(_.name).mkString(", ")})"

/** A reference build that this build names but does not implement yet, and so refuses at startup
  * (#826), as [[DijkstraNotYet]] refuses the parts of Dijkstra. Each part is removed by the step of
  * #826 that implements it.
  */
enum ReferenceBuildNotYet extends DomainError:
  /** The ledger and consensus rules of `build`, which `follow` would run: #826 step c. Its `state`
    * layout is read and written (`import-ledger-snapshot`, `export-ledger-snapshot`).
    */
  case Rules(build: ReferenceBuild)

  def message: String = this match
    case Rules(build) =>
      s"the reference build ${build.describe} is not implemented yet: this build reads and writes its ledger state layout, but does not follow its rules"
