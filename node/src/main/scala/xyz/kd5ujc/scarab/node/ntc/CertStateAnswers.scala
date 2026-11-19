// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import scala.collection.immutable.TreeMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Credential, PoolParams, ProtocolParams, RewardAccount}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state as mirror
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.node.read.{EraRead, EraViews, Read}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Record, Versions, Wire}
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given

/** The answers of the queries that read the certificate state of a Shelley-based era, its pools and
  * its stake accounts, and of the pools' stake snapshots: `GetFilteredDelegationsAndRewardAccounts`
  * (10), `GetStakePools` (16), `GetStakePoolParams` (17), `GetPoolState` (19), `GetStakeSnapshots`
  * (20) and `GetStakeDelegDeposits` (22). The reference answers each alike in every Shelley-based
  * era (`answerPureBlockQuery`, `Shelley/Ledger/Query.hs:430-431, 440-453`, ouroboros-consensus
  * 82ecba32), the ledger reading the era's certificate state through `EraCertState`, Shelley's
  * accounts before Conway and Conway's in it (`Api/State/Query.hs:152-164, 485-525, 644-700`,
  * `Api/State/Query/Account.hs:24-33`, `Shelley/API/Wallet.hs:154-160`, cardano-ledger f649f975);
  * it writes them at the era's lowest version (`LC.toEraCBOR @era`, `Query.hs:1018, 1025-1030`), so
  * that a pool's owners are a set under tag 258 from Conway and a plain list before it, and
  * `GetStakePools` with `toCBOR` (`Query.hs:1024`).
  *
  * A Dijkstra state answers none of them, as before: their answers there are not compared with the
  * reference's yet.
  */
object CertStateAnswers:
  /** `GetFilteredDelegationsAndRewardAccounts`: `queryStakePoolDelegsAndRewards`, the accounts of
    * the credentials asked for (none for the empty set), the pool each delegates to and its reward
    * balance (`Api/State/Query.hs:152-164`, cardano-ledger f649f975).
    */
  def delegationsAndRewards[E <: EraTag](
      l: EraRead[E],
      credentials: Set[Credential]
  ): Either[Unanswered, Bytes] =
    inEra(l) {
      delegationsAndRewardsCodec.answerRead(accounts(l).map { all =>
        val asked = all.filter((c, _) => credentials(c)).toMap
        (
          asked.flatMap((c, a) => a.pool.map(pool => c -> pool.bytes)),
          asked.map((c, a) => c -> a.balance.toBigInt)
        )
      })
    }

  /** `GetStakePools`: `getPools`, the ids of the registered pools (`Shelley/API/Wallet.hs:154-160`,
    * cardano-ledger f649f975), written with `toCBOR` (`Query.hs:1024`, ouroboros-consensus
    * 82ecba32): a set under tag 258 in every era, as the reference's answer in Babbage on the
    * devnet that walks the eras was (`d90102`, recorded 2026-11-15).
    */
  def stakePools[E <: EraTag](l: EraRead[E]): Either[Unanswered, Bytes] =
    inEra(l) {
      pools(l).leftMap(Unanswered.Refused(_)).map { all =>
        CborOut.build { w =>
          w.set(all.size)
          all.keysIterator.foreach(id => w.bytes(id.bytes))
        }
      }
    }

  /** `GetStakePoolParams`: `queryPoolParameters`, the parameters of the registered pools asked for
    * (none for the empty set), the reward account on the node's network
    * (`Api/State/Query.hs:516-525`, cardano-ledger f649f975).
    */
  def stakePoolParams[E <: EraTag](
      l: EraRead[E],
      network: Int,
      ids: Set[Bytes]
  ): Either[Unanswered, Bytes] =
    inEra(l) {
      Wire
        .sortedMapOf(poolId, HaskellStateCodecs.poolParams)
        .answerRead(pools(l).map { all =>
          all.iterator.collect {
            case (id, p) if ids(id.bytes) => id.bytes -> registered(network, id.bytes, p)
          }.toMap
        })
    }

  /** `GetPoolState`: `queryPoolState`, the registered pools' parameters, their future parameters,
    * their retirements and their deposits, of the pools asked for or of every pool
    * (`Api/State/Query.hs:485-514`, cardano-ledger f649f975).
    */
  def poolState[E <: EraTag](
      l: EraRead[E],
      network: Int,
      ids: Option[Set[Bytes]]
  ): Either[Unanswered, Bytes] =
    inEra(l) {
      def pick[V](m: Map[Bytes, V]): Map[Bytes, V] =
        ids.fold(m)(keep => m.filter((id, _) => keep(id)))
      pState(l).leftMap(Unanswered.Refused(_)).flatMap { state =>
        val registeredPools = pick(state.stakePools.iterator.map((id, p) => id.bytes -> p).toMap)
        pick(state.future.iterator.map((id, p) => id.bytes -> p).toMap).toVector
          .traverse((id, p) => futureParams(p).map(id -> _))
          .flatMap(future =>
            poolStateCodec.answerRead(
              Right(
                (
                  registeredPools.map((id, p) => id -> registered(network, id, p)),
                  future.toMap,
                  pick(state.retiring.iterator.map((id, e) => id.bytes -> e.toBigInt).toMap),
                  registeredPools.map((id, p) => id -> p.deposit.toBigInt)
                )
              )
            )
          )
      }
    }

  /** `GetStakeSnapshots`: `queryStakeSnapshots`, each pool's stake in the mark, set and go
    * snapshots, and the snapshots' total active stakes (`Api/State/Query.hs:644-700`,
    * cardano-ledger f649f975).
    */
  def stakeSnapshots[E <: EraTag](
      l: EraRead[E],
      ids: Option[Set[Bytes]]
  ): Either[Unanswered, Bytes] =
    inEra(l)(stakeSnapshotsCodec.answerRead(l.snapshots.map(stakeSnapshotsOf(l.params, _, ids))))

  /** `GetStakeDelegDeposits`: `queryAccountsDeposits`, the deposit of each registered credential
    * asked for, of every one for the empty set (`Api/State/Query/Account.hs:24-33`, cardano-ledger
    * f649f975).
    */
  def stakeDelegDeposits[E <: EraTag](
      l: EraRead[E],
      credentials: Set[Credential]
  ): Either[Unanswered, Bytes] =
    inEra(l) {
      Wire
        .sortedMapOf(HaskellStateCodecs.nodeCredential, coin)
        .answerRead(accounts(l).map { all =>
          all
            .filter((c, _) => credentials.isEmpty || credentials(c))
            .map((c, a) => c -> a.deposit.toBigInt)
            .toMap
        })
    }

  /** An answer of a query of the era of `l`, written at the era's lowest version. */
  private def inEra[E <: EraTag](l: EraRead[E])(
      answer: ProtVer ?=> Either[Unanswered, Bytes]
  ): Either[Unanswered, Bytes] =
    l.era match
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
        answer(using Versions.eraLow(l.era))
      case ShelleyBasedEra.SDijkstra => Left(Unanswered.Unsupported)

  /** The bytes of an answer, or why there are none: its value is outside what the Haskell type
    * holds, or the state cannot be read.
    */
  extension [A](codec: Codec[A])
    private def answerRead(a: Read[A]): Either[Unanswered, Bytes] =
      a.leftMap(Unanswered.Refused(_))
        .flatMap(codec.write(_).leftMap(_ => Unanswered.Unwritable))

  /** The pools of the era's certificate state. */
  private def pState[E <: EraTag](l: EraRead[E]): Read[mirror.PState] =
    l.cert.map(EraViews.pState(_)(using l.era))

  private def pools[E <: EraTag](
      l: EraRead[E]
  ): Read[Map[mirror.KeyHash[mirror.StakePool], mirror.StakePoolState]] =
    pState(l).map(_.stakePools)

  /** The stake accounts of the era's certificate state by credential, as every era's accounts see
    * them.
    */
  private def accounts[E <: EraTag](
      l: EraRead[E]
  ): Read[Iterator[(Credential, EraViews.Account)]] =
    l.cert.map(c => EraViews.accounts(c)(using l.era).map((k, a) => EraRead.credential(k) -> a))

  /** A pool's future parameters as the node's parameters write them (`StakePoolParams`'s nine
    * items): parameters holding a voting key, which nine items have no place for, are not written.
    */
  private[ntc] def futureParams(p: mirror.StakePoolParams): Either[Unanswered, PoolParams] =
    p.blsKey match
      case mirror.PoolParamsBlsKey.Leios(Some(_)) => Left(Unanswered.Unwritable)
      case mirror.PoolParamsBlsKey.Leios(None) | mirror.PoolParamsBlsKey.Node11_1 =>
        Right(Convert.poolParamsBack(p))

  /** The parameters of a registered pool as the reference reads them back
    * (`stakePoolStateToStakePoolParams`, `State/StakePool.hs:275-291`, cardano-ledger f649f975):
    * the reward account on the node's network.
    */
  private def registered(network: Int, id: Bytes, p: mirror.StakePoolState): PoolParams =
    PoolParams(
      id,
      p.vrf.bytes,
      p.pledge.value,
      p.cost.value,
      p.margin,
      RewardAccount(network, EraRead.credential(p.accountId)),
      p.owners.iterator.map(_.bytes).toSet,
      p.relays,
      p.metadata
    )

  private val coin = Wire.word(64)
  private val poolId = Wire.hash(28)

  private def delegationsAndRewardsCodec(using
      ProtVer
  ): Codec[(Map[Credential, Bytes], Map[Credential, BigInt])] =
    Record.of[(Map[Credential, Bytes], Map[Credential, BigInt])](
      (
        Wire.sortedMapOf(HaskellStateCodecs.nodeCredential, poolId),
        Wire.sortedMapOf(HaskellStateCodecs.nodeCredential, coin)
      )
    )

  /** `QueryPoolStateResult`: the pools' parameters, their future parameters, their retirements and
    * their deposits.
    */
  private type PoolStateAnswer =
    (Map[Bytes, PoolParams], Map[Bytes, PoolParams], Map[Bytes, BigInt], Map[Bytes, BigInt])

  private def poolStateCodec(using ProtVer): Codec[PoolStateAnswer] =
    val params = Wire.sortedMapOf(poolId, HaskellStateCodecs.poolParams)
    Record.of[PoolStateAnswer](
      (params, params, Wire.sortedMapOf(poolId, coin), Wire.sortedMapOf(poolId, coin))
    )

  /** `StakeSnapshots`: each pool's stake in the mark, set and go snapshots, and their totals. */
  private type StakeSnapshotsAnswer =
    (Map[Bytes, (BigInt, BigInt, BigInt)], BigInt, BigInt, BigInt)

  private def stakeSnapshotsCodec(using ProtVer): Codec[StakeSnapshotsAnswer] =
    Record.of[StakeSnapshotsAnswer](
      (
        Wire.sortedMapOf(poolId, Record.of[(BigInt, BigInt, BigInt)]((coin, coin, coin))),
        coin,
        coin,
        coin
      )
    )

  /** `queryStakeSnapshots` (`Api/State/Query.hs:644-700`, cardano-ledger f649f975). Without a list
    * of pools: before protocol version 11 the pools that have a delegator in any snapshot, each
    * with its stake in every snapshot (zero where it has none); from 11 those with stake, and of
    * the pools asked for only those with stake. The totals are each snapshot's active stake, 1 when
    * it has none (`ssTotalActiveStake`, `sumAllActiveStake`, `State/SnapShots.hs:272-281, 397-403`,
    * `State/Stake.hs:157-160`).
    */
  private[ntc] def stakeSnapshotsOf(
      params: ProtocolParams,
      snapshots: mirror.SnapShots,
      ids: Option[Set[Bytes]]
  ): StakeSnapshotsAnswer =
    val snaps = Vector(snapshots.mark, snapshots.set, snapshots.go)
    val before11 = params.protocolMajor < 11
    // each pool's stake in each snapshot, by pool id
    val stakes: Vector[Map[Bytes, BigInt]] =
      snaps.map(_.pools.iterator.map((id, s) => id.bytes -> s.stake.toBigInt).toMap)
    def stakeOf(i: Int, p: Bytes): BigInt = stakes(i).getOrElse(p, BigInt(0))
    val chosen: Set[Bytes] = ids.getOrElse {
      snaps
        .flatMap(_.pools.iterator.collect {
          case (p, s) if (if before11 then !s.numDelegators.isZero else s.stake.toBigInt > 0) =>
            p.bytes
        })
        .toSet
    }
    val rows = chosen.toVector
      .map(p => p -> snaps.indices.map(stakeOf(_, p)))
      .filter((_, st) => before11 || st.exists(_ > 0))
      .map((p, st) => p -> (st(0), st(1), st(2)))
    (TreeMap.from(rows), totalActive(snaps(0)), totalActive(snaps(1)), totalActive(snaps(2)))

  /** The active stake of a snapshot, summed; 1 for none (a `NonZero Coin`). */
  private def totalActive(s: mirror.SnapShot): BigInt =
    s.activeStake.entries.valuesIterator
      .foldLeft(BigInt(0))(_ + _.stake.value.toBigInt)
      .max(1)
