// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.syntax.all.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.*

/** The conversions between the node's plain records (credentials and hashes as bytes) and the
  * mirror's checked ones, in the direction the rules need: the pool parameters and the governance
  * records the rules share with the node arrive as the node's types, and the state holds the
  * mirror's. Each refusal is the vocabulary's.
  */
object Convert:
  def keyHash[R <: KeyRole](b: Bytes): Either[VocabError, KeyHash[R]] = KeyHash.fromBytes[R](b)

  def credential[R <: KeyRole](c: node.Credential): Either[VocabError, Credential[R]] = c match
    case node.Credential.Key(h) => KeyHash.fromBytes[R](h).map(Credential.KeyHashObj[R](_))
    case node.Credential.Script(h) =>
      ScriptHash.fromBytes(h).map(Credential.ScriptHashObj[R](_))

  /** The node's credential of a mirror one. */
  def back[R <: KeyRole](c: Credential[R]): node.Credential = c match
    case Credential.ScriptHashObj(h) => node.Credential.Script(h.bytes)
    case Credential.KeyHashObj(h)    => node.Credential.Key(h.bytes)

  def drep(d: node.DRep): Either[VocabError, DRep] = d match
    case node.DRep.Registered(node.Credential.Key(h)) =>
      KeyHash.fromBytes[DRepRole](h).map(DRep.DRepKeyHash(_))
    case node.DRep.Registered(node.Credential.Script(h)) =>
      ScriptHash.fromBytes(h).map(DRep.DRepScriptHash(_))
    case node.DRep.AlwaysAbstain      => Right(DRep.DRepAlwaysAbstain)
    case node.DRep.AlwaysNoConfidence => Right(DRep.DRepAlwaysNoConfidence)

  def drepBack(d: DRep): node.DRep = d match
    case DRep.DRepKeyHash(h)         => node.DRep.Registered(node.Credential.Key(h.bytes))
    case DRep.DRepScriptHash(h)      => node.DRep.Registered(node.Credential.Script(h.bytes))
    case DRep.DRepAlwaysAbstain      => node.DRep.AlwaysAbstain
    case DRep.DRepAlwaysNoConfidence => node.DRep.AlwaysNoConfidence

  /** `mkStakePoolState` (`State/StakePool.hs`): the registered state of a pool from its (future)
    * parameters, the deposit it was registered with and its delegators.
    */
  def poolState(
      params: node.PoolParams,
      deposit: CompactCoin,
      delegators: SortedSet[Credential[Staking]]
  ): Either[VocabError, StakePoolState] =
    (
      VRFVerKeyHash.fromBytes(params.vrfKeyHash),
      Coin.fromBigInt(params.pledge),
      Coin.fromBigInt(params.cost),
      credential[Staking](params.rewardAccount.credential),
      params.owners.toList.traverse(keyHash[Staking]).map(Sorted.set(_))
    ).mapN((vrf, pledge, cost, account, owners) =>
      StakePoolState(
        vrf,
        pledge,
        cost,
        params.margin,
        account,
        owners,
        params.relays,
        params.metadata,
        deposit,
        delegators
      )
    )
