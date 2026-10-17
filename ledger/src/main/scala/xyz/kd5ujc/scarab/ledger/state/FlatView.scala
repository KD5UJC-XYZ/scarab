// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node

/** The mirror's records in the node's plain form (credentials as `node.Credential`, accounts and
  * pools as the ratification rules read them), for the pulser of Conway
  * ([[xyz.kd5ujc.scarab.ledger.rules.ConwayPulser]]).
  */
object FlatView:
  def credBack[R <: KeyRole](c: Credential[R]): node.Credential = c match
    case Credential.ScriptHashObj(h) => node.Credential.Script(h.bytes)
    case Credential.KeyHashObj(h)    => node.Credential.Key(h.bytes)

  def drepBack(d: DRep): node.DRep = d match
    case DRep.DRepKeyHash(h)         => node.DRep.Registered(node.Credential.Key(h.bytes))
    case DRep.DRepScriptHash(h)      => node.DRep.Registered(node.Credential.Script(h.bytes))
    case DRep.DRepAlwaysAbstain      => node.DRep.AlwaysAbstain
    case DRep.DRepAlwaysNoConfidence => node.DRep.AlwaysNoConfidence

  def stakeMap[R <: KeyRole, A, B](m: SortedMap[Credential[R], A])(
      f: A => B
  ): Map[node.Credential, B] =
    m.iterator.map((c, a) => credBack(c) -> f(a)).toMap

  def coinMap(
      m: SortedMap[Credential[Staking], Coin]
  ): Map[node.Credential, BigInt] =
    stakeMap(m)(_.value)

  def conwayAccount(a: ConwayAccountState): node.Account =
    node.Account(
      a.balance.toBigInt,
      a.deposit.toBigInt,
      a.pool.map(_.bytes),
      a.drep.map(drepBack)
    )

  def shelleyAccount(a: ShelleyAccountState): node.Account =
    node.Account(a.balance.toBigInt, a.deposit.toBigInt, a.pool.map(_.bytes), None)

  def pools(
      network: Int,
      ps: SortedMap[KeyHash[StakePool], StakePoolState]
  ): Map[Bytes, node.PoolState] =
    ps.iterator.map { (id, p) =>
      id.bytes -> node.PoolState(
        node.PoolParams(
          id.bytes,
          p.vrf.bytes,
          p.pledge.value,
          p.cost.value,
          p.margin,
          node.RewardAccount(network, credBack(p.accountId)),
          p.owners.iterator.map(_.bytes).toSet,
          p.relays,
          p.metadata
        ),
        p.deposit.toBigInt
      )
    }.toMap
