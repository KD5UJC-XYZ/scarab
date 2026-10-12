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

  /** The SNAP rule's new mark snapshot: stake of credentials delegated to registered pools. A
    * credential without stake (no UTxO behind it and no rewards) is left out of both maps, as the
    * ledger's `resolveActiveInstantStakeCredentials` does (it keeps the non-zero active stake only;
    * found by the db-analyser comparison, roadmap K1), but its pool is remembered in `idlePools`
    * because the ledger's pool distribution still lists a pool with such a delegator. Rewards and
    * the leader schedule do not see the difference.
    */
  def snapshot(state: LedgerState, pointers: Boolean = true): Snapshot =
    val registered = state.accounts.iterator.flatMap { (c, a) =>
      a.pool.filter(state.pools.contains).map(c -> _)
    }.toMap
    val stake = instantWith(state, pointers && state.params.protocolMajor < 9).filter {
      case (c, coin) => coin > 0 && registered.contains(c)
    }
    val delegations = registered.filter((c, _) => stake.contains(c))
    val idle = registered.collect { case (c, p) if !stake.contains(c) => p }.toSet
    Snapshot(stake, delegations, state.pools.map { case (id, p) => id -> p.params }, idle)
