// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.ledger.{DRepVotingThresholds, PoolVotingThresholds, ProtocolParams}

/** What differs between the states the previous build recorded and the states the rules give now.
  *
  * The previous parameters of the first epoch of Conway used to lack the fields Conway introduces
  * (the reference translates them with the current ones, `Conway/Translation.hs:165`; found by the
  * era-indexed tick, J2.3, `docs/findings.md`). The snapshots and the files recorded by the earlier
  * build hold the old value, and a migration or a replay compared with them leaves those fields
  * out.
  */
object PreviousBuild:
  private def clear(p: ProtocolParams): ProtocolParams =
    p.copy(
      drepDeposit = 0,
      drepActivity = 0,
      govActionDeposit = 0,
      govActionLifetime = 0,
      minFeeRefScriptCostPerByte = Rational.zero,
      poolVotingThresholds = PoolVotingThresholds.zero,
      drepVotingThresholds = DRepVotingThresholds.zero,
      committeeMinSize = 0,
      committeeMaxTermLength = 0,
      costModels = p.costModels - 2
    )

  /** The state without the Conway fields in its previous parameters, and in the pulser's. */
  def withoutConwayFields(s: NodeState): NodeState =
    val ledger = s.ledger
    s.copy(ledger =
      ledger.copy(
        prevParams = ledger.prevParams.map(clear),
        gov = ledger.gov.copy(pulsed =
          ledger.gov.pulsed.map((snapshot, ratify) =>
            (
              snapshot,
              ratify.copy(enact = ratify.enact.copy(prevParams = clear(ratify.enact.prevParams)))
            )
          )
        )
      )
    )
