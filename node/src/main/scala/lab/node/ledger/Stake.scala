// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

object Stake:
  /** Stake controlled by each credential: lovelace in base addresses staking to it plus its reward
    * balance. Before Conway, pointer addresses count for the credential that registered at the
    * pointed-to certificate (if it is still registered); Conway ignores them.
    */
  def instant(state: LedgerState): Map[Credential, BigInt] =
    instantWith(state, state.params.protocolMajor < 9)

  /** [[instant]] with pointer addresses counted or not as told. */
  def instantWith(state: LedgerState, pointers: Boolean): Map[Credential, BigInt] =
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
  def snapshot(state: LedgerState, pointers: Boolean = true): Snapshot =
    val registered = state.pools.toMap
    val delegations = state.accounts.iterator.flatMap { (c, a) =>
      a.pool.filter(registered.contains).map(c -> _)
    }.toMap
    val stake = instantWith(state, pointers && state.params.protocolMajor < 9).filter {
      case (c, _) => delegations.contains(c)
    }
    // Each snapshot is a new generation of the stored rows (a rotation moves the values, not rows).
    Snapshot.of(
      state.snapshots.mark.gen + 1,
      stake,
      delegations,
      registered.map { case (id, p) => id -> p.params },
      state.accounts.stamp // the rows are changes of the block being applied
    )
