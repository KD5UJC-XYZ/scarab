// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.{Eq, Show}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.consensus.state.{PBftState, ProtocolExtras}
import xyz.kd5ujc.scarab.ledger.LeaderCheck
import xyz.kd5ujc.scarab.ledger.byron.ByronLedgerState

/** The leader-check verdicts of one epoch's Shelley-based headers. */
final case class EpochStats(
    epoch: BigInt,
    era: Era,
    threshold: Long,
    overlay: Long,
    unchecked: Long
):
  def total: Long = threshold + overlay + unchecked
  def record(leader: LeaderCheck): EpochStats = leader match
    case LeaderCheck.Threshold    => copy(threshold = threshold + 1)
    case LeaderCheck.Overlay(_)   => copy(overlay = overlay + 1)
    case LeaderCheck.Unchecked(_) => copy(unchecked = unchecked + 1)

/** What the node reports of the chain it follows beside the ledger: the era of the last block
  * applied, whose change is [[FollowerEvent.EraEntered]], and the leader-check verdicts of the
  * current epoch's headers and of the last epoch completed, which is
  * [[FollowerEvent.EpochValidated]] when it changes.
  */
final case class FollowState(
    era: Option[Era],
    stats: Option[EpochStats],
    completed: Option[EpochStats]
)

object FollowState:
  /** Before the first block. */
  val initial: FollowState = FollowState(None, None, None)

/** Verdicts of one Byron epoch's blocks. */
final case class ByronEpochStats(
    epoch: BigInt,
    boundary: Long,
    verified: Long,
    offSchedule: Long,
    txs: Long
):
  def record(check: ByronCheck): ByronEpochStats = check match
    case ByronCheck.Boundary => copy(boundary = boundary + 1)
    case ByronCheck.Verified(onSchedule, n) =>
      copy(
        verified = verified + 1,
        offSchedule = offSchedule + (if onSchedule then 0 else 1),
        txs = txs + n
      )

/** Byron chain-follow state: the last header, the verdict counters of the current epoch, the Byron
  * ledger and PBFT window while the chain is in Byron, and the epoch in which the chain entered
  * Shelley once it has.
  */
final case class ByronState(
    tip: Option[ByronTip],
    stats: Option[ByronEpochStats],
    completed: Option[ByronEpochStats],
    ledger: Option[ByronLedgerState] = None,
    pbft: PBftState = PBftState.empty,
    firstShelleyEpoch: Option[BigInt] = None
):
  /** The Byron era is over (or the epoch changed): the current epoch's counters are final. */
  def finish: ByronState = copy(stats = None, completed = stats.orElse(completed))

object ByronState:
  val initial: ByronState = ByronState(None, None, None)

  // Structural equality and the default rendering: the mirror's `ByronCurrent` carries this value.
  given Eq[ByronState] = Eq.fromUniversalEquals
  given Show[ByronState] = Show.fromToString

  /** The state of a chain that starts in Byron, with the ledger of the genesis. */
  def starting(ledger: ByronLedgerState): ByronState = initial.copy(ledger = Some(ledger))

/** What the node keeps of the consensus side beside the Haskell header state: the follow state, the
  * Byron chain-follow state and what the chain-dependent state of the protocol leaves out
  * ([[ProtocolExtras]]).
  */
final case class ConsensusExtras(
    follow: FollowState,
    byron: ByronState,
    protocol: ProtocolExtras
)
