// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.node.LedgerRun
import xyz.kd5ujc.scarab.node.read.LeaderPools.given

/** The read of the node's flat ledger state `s` as the ledger state of era `E`, at the tip `at`.
  *
  * A member is the mirror's vocabulary for the part of the state asked for: it is converted by the
  * part of [[LedgerMirror]] that makes it, the first time, and kept. The members the header rules
  * and the leader schedule read (the parameters, the pool distribution, the genesis delegations)
  * are the flat state's own, converted not at all: a header is checked against the state the chain
  * has just reached, and a conversion of its accounts would be the cost of the block.
  *
  * Goes with the flat ledger state (J2.9).
  */
final class FlatEraRead[E <: EraTag] private (
    s: flat.LedgerState,
    at: WithOrigin[SlotNo],
    env: MirrorEnv,
    val epoch: EpochNo
)(using val era: ShelleyBasedEra[E])
    extends EraRead[E]:

  private def run[A](program: LedgerMirror[LedgerRun.M] => LedgerRun.M[A]): Read[A] =
    LedgerRun.run(env)(program)

  lazy val scalars: Read[LedgerScalars] = run(_.scalars(s))

  def params: flat.ProtocolParams = s.params

  def utxo: flat.Utxo = s.utxo

  def accountCount: Int = s.accounts.size

  def poolCount: Int = s.pools.size

  def leaderPools(nextEpoch: Boolean): flat.PoolDistribution =
    if nextEpoch then s.snapshots.mark.distribution else s.leaderPools

  def paramsNextEpoch(globals: flat.LedgerGlobals): flat.ProtocolParams =
    flat.PpupRules.newEpoch(s, globals).params

  def genDelegsAt(slot: BigInt): Map[Bytes, flat.GenDelegPair] =
    s.futureGenDelegs
      .filter(_.slot <= slot)
      .sortBy(f => (f.slot, f.genesisKey.hex))
      .foldLeft(s.genDelegs)((m, f) => m.updated(f.genesisKey, f.pair))

  lazy val cert: Read[CertState[E]] = run(_.certState[E](s))

  lazy val gov: Read[GovState[E]] = run(_.govState[E](at, s))

  lazy val instantStake: Read[InstantStake[E]] = run(_.instantStake[E](s.utxo))

  lazy val snapshots: Read[SnapShots] = run(_.snapshots(s))

  lazy val pd: Read[PoolDistr] = run(_.poolDistr(s))

  lazy val rewardsDue: Read[Option[RewardsDue]] =
    s.rewardUpdate.traverse { ru =>
      for paid <- ru.rewards.toList.traverse { (c, amount) =>
          FlatEraRead.stakeCredential(c).map(_ -> amount)
        }
      yield RewardsDue(
        DeltaCoin(ru.deltaTreasury),
        DeltaCoin(ru.deltaReserves),
        DeltaCoin(ru.deltaFees),
        Sorted.map(paid)
      )
    }

  def ppup: Read[(Map[Bytes, flat.ParamUpdate], Map[Bytes, flat.ParamUpdate])] =
    Right((s.ppup.current, s.ppup.future))

  lazy val nes: Read[NewEpochState[E]] = run(_.to[E](at, s)).map(_.value.nes)

object FlatEraRead:
  /** The read of `s` in era `E`; refused when the epoch is outside the range of its Haskell
    * type.
    */
  def of[E <: EraTag](s: flat.LedgerState, at: WithOrigin[SlotNo], env: MirrorEnv)(using
      ShelleyBasedEra[E]
  ): Read[FlatEraRead[E]] =
    EpochNo.fromBigInt(s.epoch).leftMap(MirrorError.Vocab(_)).map(new FlatEraRead[E](s, at, env, _))

  /** A flat credential as the mirror's staking credential. */
  private def stakeCredential(c: flat.Credential): Read[Credential[Staking]] = c match
    case flat.Credential.Key(h) =>
      KeyHash
        .fromBytes[Staking](h)
        .map(Credential.KeyHashObj[Staking](_))
        .leftMap(MirrorError.Vocab(_))
    case flat.Credential.Script(h) =>
      ScriptHash
        .fromBytes(h)
        .map(Credential.ScriptHashObj[Staking](_))
        .leftMap(MirrorError.Vocab(_))
