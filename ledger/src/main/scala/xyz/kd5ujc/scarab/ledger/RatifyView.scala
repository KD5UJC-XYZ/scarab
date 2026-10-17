// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes

// The records of the ledger state that the ratification rules ([[Ratify]]) read their accounts, pools
// and DReps through. The era-indexed rules put the mirror's records into these for the pulser
// ([[xyz.kd5ujc.scarab.ledger.rules.ConwayPulser]]).

/** A registered stake credential. `balance` is its reward balance. */
final case class Account(balance: BigInt, deposit: BigInt, pool: Option[Bytes], drep: Option[DRep])

final case class PoolState(params: PoolParams, deposit: BigInt)

/** A registered DRep. `delegs` is the reverse index of stake credentials delegating to it (kept as
  * the ledger keeps it, including its bootstrap-phase inconsistencies).
  */
final case class DRepState(
    deposit: BigInt,
    expiry: BigInt,
    anchor: Option[Anchor] = None,
    delegs: Set[Credential] = Set.empty
)
