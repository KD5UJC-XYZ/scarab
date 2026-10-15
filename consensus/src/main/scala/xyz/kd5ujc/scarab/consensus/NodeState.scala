// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.ledger.LeaderCheck
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.byron.ByronLedgerState

/** Verdicts of one epoch's headers in bootstrap mode. */
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

/** Pre-Conway (bootstrap) follow mode: the ledger-lite state, the current era and per-epoch verdict
  * counters. `active` turns on with the first Alonzo-or-earlier block; from then on every block,
  * Conway included, is followed in bootstrap mode until a full ledger can take over.
  */
final case class BootstrapState(
    active: Boolean,
    era: Option[Era],
    lite: LiteLedger,
    stats: Option[EpochStats],
    completed: Option[EpochStats]
)

object BootstrapState:
  def initial(lite: LiteLedger): BootstrapState = BootstrapState(false, None, lite, None, None)

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

  /** The state of a chain that starts in Byron, with the ledger of the genesis. */
  def starting(ledger: ByronLedgerState): ByronState = initial.copy(ledger = Some(ledger))

/** Consensus and ledger state after a block. */
final case class NodeState(
    praos: PraosState,
    ledger: LedgerState,
    boot: BootstrapState,
    byron: ByronState = ByronState.initial
):
  /** The era history as this state knows it: a chain whose Byron span is derived learns the first
    * Shelley epoch when it enters Shelley.
    */
  def geometry(base: EpochGeometry): EpochGeometry =
    byron.firstShelleyEpoch.fold(base)(base.resolved)
