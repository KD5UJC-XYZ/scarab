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

  /** The account a reward account names (`AccountAddress`, `Address.hs:183-187`, cardano-ledger
    * f649f975): its network (`word8ToNetwork`) and its staking credential.
    */
  def accountAddress(r: node.RewardAccount): Either[VocabError, AccountAddress] =
    (
      Network
        .of(BigInt(r.network))
        .toRight(VocabError.OutOfRange(VocabError.Kind.Network, r.network)),
      credential[Staking](r.credential)
    ).mapN((network, c) => AccountAddress(network, AccountId(c)))

  /** The reference's `Withdrawals` (`Address.hs:980-982`, cardano-ledger f649f975) of a body's
    * withdrawals as the node's decoders read them: each account read into `AccountAddress`
    * ([[accountAddress]]). Every account a decoder reads is one: they read it as
    * `decodeAccountAddress` does (`RewardAccount.decode`), whose header leaves the network one bit
    * and whose hash is 28 bytes.
    */
  def withdrawals(m: Iterable[(node.RewardAccount, Coin)]): Either[VocabError, Withdrawals] =
    m.toVector
      .traverse((account, amount) => accountAddress(account).map(_ -> amount))
      .map(read => Withdrawals(read.toMap))

  /** The node's reward account of an account address. */
  def rewardAccountBack(a: AccountAddress): node.RewardAccount =
    node.RewardAccount(a.networkId.wire, back(a.id.credential))

  /** The state's parameters of the node's: the pool's voting key `blsKey`, in the layout of the
    * state they go into, and the metadata read from the parameters' own ([[PoolMetadata.read]] or
    * [[PoolMetadata.held]] of `params.metadata`), which the caller has read with its own refusal.
    */
  def stakePoolParams(
      params: node.PoolParams,
      blsKey: PoolParamsBlsKey,
      metadata: Option[PoolMetadata]
  ): Either[VocabError, StakePoolParams] =
    (
      keyHash[StakePool](params.operator),
      VRFVerKeyHash.fromBytes(params.vrfKeyHash),
      UnitInterval.boundRational(params.margin).toRight(VocabError.NotUnitInterval(params.margin)),
      accountAddress(params.rewardAccount),
      params.owners.toList.traverse(keyHash[Staking]).map(Sorted.set(_))
    ).mapN((id, vrf, margin, account, owners) =>
      StakePoolParams(
        id,
        vrf,
        blsKey,
        Coin(params.pledge),
        Coin(params.cost),
        margin,
        account,
        owners,
        params.relays,
        metadata
      )
    )

  /** The node's parameters of the state's, without the voting key, which the node's do not hold:
    * the owners a sorted set, as the node's decoder reads them.
    */
  def poolParamsBack(p: StakePoolParams): node.PoolParams =
    node.PoolParams(
      p.id.bytes,
      p.vrf.bytes,
      p.pledge.value,
      p.cost.value,
      p.margin.rational,
      rewardAccountBack(p.accountAddress),
      SortedSet.from(p.owners.iterator.map(_.bytes)),
      p.relays,
      p.metadata.map(_.bytes)
    )

  /** `mkStakePoolState` (`State/StakePool.hs`): the registered state of a pool from its (future)
    * parameters, its voting key in the state's layout ([[PoolParamsBlsKey.registered]]), the
    * deposit it was registered with and its delegators.
    */
  def poolState(
      params: node.PoolParams,
      blsKey: PoolBlsKey,
      deposit: CompactCoin,
      delegators: SortedSet[Credential[Staking]]
  ): Either[VocabError, StakePoolState] =
    (
      VRFVerKeyHash.fromBytes(params.vrfKeyHash),
      credential[Staking](params.rewardAccount.credential),
      params.owners.toList.traverse(keyHash[Staking]).map(Sorted.set(_))
    ).mapN((vrf, account, owners) =>
      StakePoolState(
        vrf,
        blsKey,
        Coin(params.pledge),
        Coin(params.cost),
        params.margin,
        account,
        owners,
        params.relays,
        params.metadata,
        deposit,
        delegators
      )
    )

  /** `mkStakePoolState` of the state's parameters: the pool's account is its credential (`aaId`),
    * and nothing is refused, the parameters' fields being of the state's types already.
    */
  def poolState(
      params: StakePoolParams,
      blsKey: PoolBlsKey,
      deposit: CompactCoin,
      delegators: SortedSet[Credential[Staking]]
  ): Either[VocabError, StakePoolState] =
    Right(
      StakePoolState(
        params.vrf,
        blsKey,
        params.pledge,
        params.cost,
        params.margin.rational,
        params.accountAddress.id.credential,
        params.owners,
        params.relays,
        params.metadata.map(_.bytes),
        deposit,
        delegators
      )
    )
