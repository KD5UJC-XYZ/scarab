// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.codec.DomainError
import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{BlockRef, ConsensusExtras, FollowState, HardForkConfig}
import xyz.kd5ujc.scarab.ledger.era.{ConwayGovState as EraConwayGovState, GovStateRecord}
import xyz.kd5ujc.scarab.ledger.state.{
  Diff,
  Dijkstra,
  Gap,
  MirrorEnv,
  MirrorError,
  NodeExtras,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

// The consensus side of the mirror: the Haskell `ExtLedgerState` of the Cardano block
// (`Ledger/Extended.hs:70-73`, ouroboros-consensus 82ecba32) with the node's bookkeeping beside it
// ([[MirrorState]]), the environment the conversions read and their error. The state a chain starts
// from is built from its genesis ([[ByronMirror]], [[GenesisMirror]]).

/** What the consensus side of the conversion reads from the node's configuration: the ledger side's
  * ([[MirrorEnv]]) and what the era history is made from.
  *
  * @param ledger
  *   the environment of the ledger side (network, epoch geometry, update quorum)
  * @param forks
  *   how each Shelley-based era is entered (`HardForkConfig`)
  * @param byron
  *   the Byron parameters of the era history (epoch size, slot length, security parameter)
  * @param layout
  *   the state layout of the build the node mirrors: what a state the mirror builds with no
  *   Shelley-based ledger state to take it from, a Byron one ([[ByronMirror]]), is laid out in
  * @param observed
  *   the epoch in which the node saw each era begin, where the configuration does not say
  *   (`EraHistory.summary`)
  */
final case class ConsensusMirrorEnv(
    ledger: MirrorEnv,
    forks: HardForkConfig,
    byron: EraHistory.ByronParams,
    layout: StateLayout,
    observed: Map[Era, BigInt] = Map.empty
)

/** Why a conversion refused. Closed, and over the ledger side's [[MirrorError]] rather than an
  * extension of it: the consensus cases name types (`TelescopeError`) that the ledger module, where
  * [[MirrorError]] is, cannot see.
  */
enum StateMirrorError extends DomainError derives Show:
  /** The ledger side refused. */
  case Ledger(error: MirrorError)

  /** A telescope cannot be built. */
  case Telescope(error: TelescopeError)

  /** The era history has no summary of the era. */
  case MissingEra(era: Era)

  /** The era history has no end for the past era at `index`. */
  case OpenPastEra(index: Int)

  /** The ledger and the header telescopes are in different eras (`ExtLedgerState` does not forbid
    * it; Haskell's single type-level list does).
    */
  case EraMismatch(ledger: Era, header: Era)

  /** The extras are those of another kind of state than the era's (Byron's or a Shelley-based
    * era's).
    */
  case ExtrasMismatch(era: Era)

  /** The chain-dependent state is of another protocol than the era runs, or the extras are. */
  case ChainDepFamily(era: Era)

  /** The anchor is a Byron epoch boundary block, which no Shelley-based era has. */
  case BoundaryOutsideByron(era: Era)

  /** The imported consensus counter cannot take another Word32 successor. Native `Enum.succ` throws
    * here; the pure mirror reports a typed invalid-state refusal.
    */
  case TransitionCounterOverflow

  /** The mirror's Shelley-based ledger state is of the layout `mirror`, and the new-epoch state a
    * block leaves of `ledger`: the reference's state has one layout, its build's.
    */
  case LayoutMismatch(mirror: StateLayout, ledger: StateLayout)

object StateMirrorError:
  private given Show[Era] = Show.fromToString
  private given Show[StateLayout] = Show.fromToString

/** What the node holds that the Haskell state has no place for, on the consensus side. The ledger
  * side's ([[NodeExtras]]) is inside the Shelley-based case.
  *
  * Haskell's `Past` eras keep bounds only, so the Byron ledger and its chain-follow state go here
  * once the chain has left Byron (in [[ConsensusExtras]]); and a chain still in Byron has no
  * `NewEpochState`, so the state that Shelley will start from (initial, until Shelley) goes here
  * instead.
  */
enum MirrorExtras:
  case InByron(
      pending: ChainDepState,
      follow: FollowState,
      protocol: ProtocolExtras
  )
  case InShelley(ledger: NodeExtras, consensus: ConsensusExtras)

object MirrorExtras:
  // The node's own records, compared structurally and rendered by their `toString`.
  given Eq[MirrorExtras] = Eq.fromUniversalEquals
  given Show[MirrorExtras] = Show.fromToString

/** Completeness and era observations belonging to one chain state, including restored imports. No
  * transition can infer missing historical likelihoods from a newly computed reward update. This
  * record is live bookkeeping; V5 stores its fields in the existing snapshot sidecar.
  */
final case class MirrorProvenance private (
    gaps: Set[Gap],
    observed: Map[Era, BigInt],
    private val rotations: MirrorProvenance.Rotations
):
  /** Retain source observations when a configured era start and its observed start disagree. */
  def entered(era: Era, epoch: BigInt): MirrorProvenance =
    copy(observed = observed.updatedWith(era)(_.orElse(Some(epoch))))

  /** The provenance with `gap` closed, its missing value having been recovered exactly. */
  def recovered(gap: Gap): MirrorProvenance = copy(gaps = gaps - gap)

  /** NEWEPOCH discards the old reward update and resets the voting count. Count one SNAP witness
    * per successful block crossing an epoch, even when its tick traverses several: this
    * conservatively understates recovery. Three witnesses replace every imported mark/set/go and
    * the distribution derived from mark (`Rules/Snap.hs:75`, `Rules/NewEpoch.hs:148`,
    * cardano-ledger f649f975). Reopening restarts the proof: V5 records gaps, not this transient
    * witness.
    */
  def epochBoundary: MirrorProvenance =
    val expired = Set(Gap.RewardAttribution, Gap.PendingPulsingUpdate, Gap.ShelleyTransition)
    val advanced =
      if gaps.contains(Gap.NumDelegatorsLowerBound) then
        rotations match
          case MirrorProvenance.Rotations.None => (MirrorProvenance.Rotations.One, false)
          case MirrorProvenance.Rotations.One  => (MirrorProvenance.Rotations.Two, false)
          case MirrorProvenance.Rotations.Two  => (MirrorProvenance.Rotations.None, true)
      else (MirrorProvenance.Rotations.None, false)
    copy(
      gaps = gaps -- expired -- Option.when(advanced._2)(Gap.NumDelegatorsLowerBound),
      rotations = advanced._1
    )

object MirrorProvenance:
  private enum Rotations:
    case None, One, Two

  /** Source metadata, with no invented evidence about how many native rotations preceded it. */
  def recorded(gaps: Set[Gap], observed: Map[Era, BigInt]): MirrorProvenance =
    MirrorProvenance(gaps, observed, Rotations.None)

  val complete: MirrorProvenance = recorded(Set.empty, Map.empty)

  // Reopening deliberately resets the transient proof; equality still includes it because
  // its progress affects when a subsequent boundary can clear the diagnostic.
  given Eq[MirrorProvenance] = Eq.fromUniversalEquals
  given Show[MirrorProvenance] = Show.fromToString
  given Diff[MirrorProvenance] = Diff.leaf(summon[Eq[MirrorProvenance]], _.toString)

/** The flat parameter records, current and previous, a restored Dijkstra state's snapshot sidecar
  * holds: the fields of the eras before Dijkstra its writer gave them (an import writes the
  * genesis's, `Params.completeIn`), which Dijkstra's governance state, the era's record, has no
  * place for. The sidecar is written again with the governance state's parameters laid over them,
  * so a restored state keeps its sidecar's bytes; the typed record is authoritative for Dijkstra's
  * own fields. Held in memory only, never written apart from the sidecar's records (#826).
  */
final case class RestoredParams(
    current: xyz.kd5ujc.scarab.ledger.ProtocolParams,
    previous: Option[xyz.kd5ujc.scarab.ledger.ProtocolParams]
)

object RestoredParams:
  /** The sidecar's records `current` and `previous` of a restored Dijkstra state whose governance
    * state is `gov`: none when they are what this node writes of it, its parameters over zeros, so
    * a state restored from a file it wrote is the state written.
    */
  def of(
      gov: EraConwayGovState[Dijkstra],
      current: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      previous: Option[xyz.kd5ujc.scarab.ledger.ProtocolParams]
  ): Option[RestoredParams] =
    Option.unless(
      current == GovStateRecord.view(gov.cgsCurPParams) &&
        previous.contains(GovStateRecord.view(gov.cgsPrevPParams))
    )(RestoredParams(current, previous))

  /** The sidecar's records of a Dijkstra state whose governance state is `gov`: its parameters laid
    * over `restored` 's records, those of the snapshot it was restored from, or over zeros.
    */
  def written(
      gov: EraConwayGovState[Dijkstra],
      restored: Option[RestoredParams]
  ): (xyz.kd5ujc.scarab.ledger.ProtocolParams, Option[xyz.kd5ujc.scarab.ledger.ProtocolParams]) =
    restored match
      case None =>
        (GovStateRecord.view(gov.cgsCurPParams), Some(GovStateRecord.view(gov.cgsPrevPParams)))
      case Some(r) =>
        (
          GovStateRecord.viewOver(gov.cgsCurPParams, r.current),
          r.previous.map(GovStateRecord.viewOver(gov.cgsPrevPParams, _))
        )

  given Eq[RestoredParams] = Eq.fromUniversalEquals
  given Show[RestoredParams] = Show.fromToString
  given Diff[RestoredParams] = Diff.leaf(summon[Eq[RestoredParams]], _.toString)

/** The mirror of the node's state: the Haskell `ExtLedgerState` and node-only bookkeeping.
  * Provenance is mandatory: a constructor cannot silently turn an incomplete import into a complete
  * state. `restored` is the sidecar's parameter records of a restored Dijkstra state
  * ([[RestoredParams]]), none otherwise.
  */
final case class MirrorState(
    ext: ExtLedgerState,
    extras: MirrorExtras,
    provenance: MirrorProvenance,
    restored: Option[RestoredParams]
)

object MirrorState:
  given Eq[MirrorState] =
    Eq.instance((a, b) =>
      a.ext === b.ext && a.extras === b.extras && a.provenance === b.provenance &&
        a.restored === b.restored
    )

  given Diff[MirrorState] = Diff.record(
    Diff.at("ext")(_.ext),
    Diff.at("extras")(_.extras),
    Diff.at("provenance")(_.provenance),
    Diff.at("restored")(_.restored)
  )

  given Show[MirrorState] =
    Show.show(m =>
      s"MirrorState(ext = ${m.ext.show}, extras = ${m.extras.show}, provenance = ${m.provenance.show}, restored = ${m.restored.show})"
    )

/** The tip of the header state as the node's block reference. */
object StateMirror:
  /** The block of an annotated tip, none at the origin. */
  def anchorOf(tip: WithOrigin[AnnTip]): Option[BlockRef] = tip match
    case WithOrigin.Origin => None
    case WithOrigin.At(t) =>
      val (hash, boundary) = t.info match
        case TipInfo.ByronTip(h, ebb)      => (h, ebb == IsEBB.EBB)
        case TipInfo.ShelleyBasedTip(_, h) => (h, false)
      Some(BlockRef(t.slot.toBigInt, hash.bytes, t.blockNo.toBigInt, boundary))
