// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

object Stake:
  /** Stake controlled by each credential: lovelace in base addresses staking to it plus its reward
    * balance. Before Conway, pointer addresses count for the credential that registered at the
    * pointed-to certificate (if it is still registered); Conway ignores them.
    */
  def instant(state: LedgerState): Map[Credential, BigInt] =
    val pointers = state.params.protocolMajor < 9
    // Maintained as transactions apply (see `Utxo`); only the pointers need resolving here.
    val fromUtxo =
      if !pointers || state.utxo.ptrStake.isEmpty then state.utxo.stake
      else
        state.utxo.ptrStake.foldLeft(state.utxo.stake) { case (acc, (ptr, coin)) =>
          state.ptrs.get(ptr).fold(acc)(c => acc.updated(c, acc.getOrElse(c, BigInt(0)) + coin))
        }
    state.accounts.iterator.map { case (c, a) =>
      c -> (fromUtxo.getOrElse(c, BigInt(0)) + a.balance)
    }.toMap

  /** The SNAP rule's new mark snapshot: stake of credentials delegated to registered pools. */
  def snapshot(state: LedgerState): Snapshot =
    val delegations = state.accounts.iterator.collect {
      case (c, a) if a.pool.exists(state.pools.contains) => c -> a.pool.get
    }.toMap
    val stake = instant(state).filter { case (c, _) => delegations.contains(c) }
    Snapshot(stake, delegations, state.pools.map { case (id, p) => id -> p.params })
