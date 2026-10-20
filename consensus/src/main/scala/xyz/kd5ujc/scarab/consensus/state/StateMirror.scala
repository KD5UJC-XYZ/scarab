// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.codec.DomainError
import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{BlockRef, BootstrapState, ConsensusExtras, HardForkConfig}
import xyz.kd5ujc.scarab.ledger.state.{Diff, Gap, MirrorEnv, MirrorError, NodeExtras, WithOrigin}
import xyz.kd5ujc.scarab.protocol.Era

// The consensus side of the mirror: the Haskell `ExtLedgerState` of the Cardano block
// (`Ledger/Extended.hs:70-73`) with the node's bookkeeping beside it ([[MirrorState]]), the environment the
// conversions read and their error. The conversion of the flat state of the old rules, which
// the older snapshot files hold, is `legacy.StateMirror` (J2.9).

/** What the consensus side of the conversion reads from the node's configuration: the ledger side's
  * ([[MirrorEnv]]) and what the era history is made from.
  *
  * @param ledger
  *   the environment of the ledger side (network, epoch geometry, update quorum)
  * @param forks
  *   how each Shelley-based era is entered (`HardForkConfig`)
  * @param byron
  *   the Byron parameters of the era history (epoch size, slot length, security parameter)
  * @param observed
  *   the epoch in which the node saw each era begin, where the configuration does not say
  *   (`EraHistory.summary`)
  */
final case class ConsensusMirrorEnv(
    ledger: MirrorEnv,
    forks: HardForkConfig,
    byron: EraHistory.ByronParams,
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

object StateMirrorError:
  private given Show[Era] = Show.fromToString

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
      boot: BootstrapState,
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

  /** NEWEPOCH discards the old reward update and resets the voting count. Count one SNAP witness
    * per successful block crossing an epoch, even when its tick traverses several: this
    * conservatively understates recovery. Three witnesses replace every imported mark/set/go and
    * the distribution derived from mark (`Rules/Snap.hs:75`, `Rules/NewEpoch.hs:148`). Reopening
    * restarts the proof: V5 records gaps, not this transient witness.
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

/** The mirror of the node's state: the Haskell `ExtLedgerState` and node-only bookkeeping.
  * Provenance is mandatory: a constructor cannot silently turn an incomplete import into a complete
  * state.
  */
final case class MirrorState(
    ext: ExtLedgerState,
    extras: MirrorExtras,
    provenance: MirrorProvenance
)

object MirrorState:
  given Eq[MirrorState] =
    Eq.instance((a, b) => a.ext === b.ext && a.extras === b.extras && a.provenance === b.provenance)

  given Diff[MirrorState] = Diff.record(
    Diff.at("ext")(_.ext),
    Diff.at("extras")(_.extras),
    Diff.at("provenance")(_.provenance)
  )

  given Show[MirrorState] =
    Show.show(m =>
      s"MirrorState(ext = ${m.ext.show}, extras = ${m.extras.show}, provenance = ${m.provenance.show})"
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
