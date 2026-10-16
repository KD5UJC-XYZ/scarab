// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.state.Diff
import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import monocle.{Focus, Lens}
import xyz.kd5ujc.scarab.ledger.state.{BlockNo, Hash32, ShelleyBasedEra, SlotNo, WithOrigin}

/** `IsEBB` (Byron epoch boundary blocks): a closed two-case type where Haskell has a two-case
  * `data`.
  */
enum IsEBB derives Eq, Show, Diff:
  case EBB, NotEBB

/** The annotation of the tip of the Cardano chain: `TipInfo` of each era under `OneEraTipInfo`
  * (`Cardano/Block.hs:873-907` lists the eras). Byron's is `TipInfoIsEBB`, the hash and whether the
  * block is a boundary block (`HeaderValidation.hs:569`, `Byron/Ledger/ HeaderValidation.hs:30`); a
  * Shelley-based era's is the header hash alone.
  */
enum TipInfo:
  case ByronTip(hash: Hash32, isEBB: IsEBB)
  case ShelleyBasedTip(era: ShelleyBasedEra[?], hash: Hash32)

object TipInfo:
  given Eq[TipInfo] = Eq.fromUniversalEquals // enum cases and an opaque hash: structural equality
  given Show[TipInfo] = Show.show {
    case ByronTip(hash, isEBB)      => s"ByronTip(${hash.show}, ${isEBB.show})"
    case ShelleyBasedTip(era, hash) => s"ShelleyBasedTip(${era.show}, ${hash.show})"
  }

/** `AnnTip` (`HeaderValidation.hs:115-119`): the tip's slot and block number with the era's
  * annotation.
  */
final case class AnnTip(slot: SlotNo, blockNo: BlockNo, info: TipInfo) derives Eq, Show, Diff

object AnnTip:
  val slot: Lens[AnnTip, SlotNo] = Focus[AnnTip](_.slot)
  val blockNo: Lens[AnnTip, BlockNo] = Focus[AnnTip](_.blockNo)
  val info: Lens[AnnTip, TipInfo] = Focus[AnnTip](_.info)

/** `HeaderState` (`HeaderValidation.hs:184-187`): the tip of the headers and the chain-dependent
  * state of each era the chain has been through.
  */
final case class HeaderState(tip: WithOrigin[AnnTip], chainDep: Telescope[ChainDepState])

object HeaderState:
  given Eq[HeaderState] =
    Eq.instance((a, b) => StateEq.withOrigin(a.tip, b.tip) && a.chainDep === b.chainDep)

  given Diff[HeaderState] = Diff.record(Diff.at("tip")(_.tip), Diff.at("chainDep")(_.chainDep))

  given Show[HeaderState] =
    Show.show(h => s"HeaderState(tip = ${h.tip.show}, chainDep = ${h.chainDep.show})")

  /** The header state as the node holds it: the origin as tip and `chainDep` in a telescope of the
    * first era of its protocol that is not placed on the era history ([[Telescope.unplaced]]).
    */
  def live(chainDep: ChainDepState): HeaderState =
    HeaderState(WithOrigin.Origin, Telescope.unplaced(ChainDepState.firstEra(chainDep), chainDep))

  val tip: Lens[HeaderState, WithOrigin[AnnTip]] = Focus[HeaderState](_.tip)
  val chainDep: Lens[HeaderState, Telescope[ChainDepState]] = Focus[HeaderState](_.chainDep)
