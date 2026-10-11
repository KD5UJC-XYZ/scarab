// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

object Stake:
  /** Stake controlled by each credential: lovelace in base addresses staking to it plus its reward
    * balance. Before Conway, pointer addresses count for the credential that registered at the
    * pointed-to certificate (if it is still registered); Conway ignores them.
    */
  def instant(state: LedgerState): Map[Credential, BigInt] =
    val pointers = state.params.protocolMajor < 9
    val fromUtxo = state.utxo.valuesIterator
      .flatMap(out =>
        out.address.kind match
          case Address.Kind.Shelley(_, _, Some(stake), _) => Iterator(stake -> out.value.coin)
          case Address.Kind.Shelley(_, _, None, Some(ptr)) if pointers =>
            state.ptrs.get(ptr).iterator.map(_ -> out.value.coin)
          case _ => Iterator.empty
      )
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + _)
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
