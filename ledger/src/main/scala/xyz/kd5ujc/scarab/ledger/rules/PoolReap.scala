// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import monocle.Lens
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.state.*

/** `ShelleyPoolreapState` (`Shelley/Rules/PoolReap.hs:61`, cardano-ledger f649f975): the three
  * parts of the epoch state that retiring a pool changes.
  */
final case class PoolreapState[E <: EraTag](
    utxo: UTxOState[E],
    chainAccount: ChainAccountState,
    cert: CertState[E]
)

object PoolreapState:
  /** The part of an epoch state POOLREAP sees (the `Embed` of the EPOCH rule). */
  def lens[E <: EraTag]: Lens[EpochState[E], PoolreapState[E]] =
    Lens[EpochState[E], PoolreapState[E]](es =>
      PoolreapState(es.ls.utxo, es.chainAccount, es.ls.cert)
    )(p => es => es.copy(chainAccount = p.chainAccount, ls = LedgerState[E](p.cert, p.utxo)))

/** POOLREAP (`Shelley/Rules/PoolReap.hs:131` (cardano-ledger f649f975), the same rule in Conway):
  * at the epoch boundary the pools' future parameters take effect, and the pools that retire at the
  * epoch are removed with their delegators' delegations; their deposits go back to the reward
  * accounts, or, where the account is no longer registered, to the treasury.
  *
  * Haskell also keeps the VRF key hashes of the pools (`psVRFKeyHashes`); the rule removes the hash
  * of a re-registered pool's old key and one occurrence of each retired pool's. Before protocol
  * version 11 the map is not maintained ([[HardFork]] populates it entering 11,
  * `HardFork.hs:75-78`), so on a state that has none these are no-ops, as Haskell's `Map.update`
  * is.
  */
object PoolReap:
  def sts[E <: EraTag](using
      ShelleyBasedEra[E]
  ): Sts[Unit, PoolreapState[E], EpochNo, TickFailure] =
    new Sts[Unit, PoolreapState[E], EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, epoch: EpochNo)(using
          ask: Ask[F, Globals],
          st: Stateful[F, PoolreapState[E]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        Sts.modifyE[F, PoolreapState[E], TickFailure](
          step(epoch, _, shelleyRelease, shelleyAdopt)
        )

  /** The VRF key hashes a reap stops using, and how the count of each is released: `superseded`,
    * the active hash of each pool whose future parameters switch to another (once per pool), and
    * `retired`, the hash of each pool retiring (once per pool).
    */
  type Release = (
      SortedMap[VRFVerKeyHash, NonZero[Word64]],
      Vector[VRFVerKeyHash],
      Vector[VRFVerKeyHash]
  ) => SortedMap[VRFVerKeyHash, NonZero[Word64]]

  /** Shelley's (`Shelley/Rules/PoolReap.hs:211-223`, cardano-ledger 9df9f98c, the rule up to
    * Conway): a superseded hash is removed whatever its count (`Map.withoutKeys`), then each
    * retired pool's hash releases one occurrence.
    */
  val shelleyRelease: Release = (counts, superseded, retired) =>
    removeOccurrences(counts -- superseded.toSet, retired)

  /** The voting key a pool holds once its future parameters take effect at the epoch:
    * `mkStakePoolState e` of the parameters (9df9f98c `State/StakePool.hs:288-320`), from the
    * pool's state and its future parameters, or the failure of a state the rule does not hold.
    */
  type Adopt = (StakePoolState, StakePoolParams, EpochNo) => Either[TickFailure, PoolBlsKey]

  /** Shelley's (`mkStakePoolState e SNothing`, 9df9f98c `Shelley/Rules/PoolReap.hs:137-155`, the
    * rule up to Conway), in the layout of the pool's state: in the Leios layout a re-registered
    * pool holds no key, the parameters a certificate before Dijkstra registers carrying none
    * ([[PoolParamsBlsKey.certified]]).
    */
  val shelleyAdopt: Adopt = (current, _, _) =>
    Right(PoolBlsKey.certified(current.blsKey.stateLayout))

  /** `removeVRFKeyHashOccurrence` over `vrfs` (`State/CertState.hs:515-519`, cardano-ledger
    * 9df9f98c): one decrement each, a count that reaches zero removed, a hash not counted
    * unchanged.
    */
  def removeOccurrences(
      counts: SortedMap[VRFVerKeyHash, NonZero[Word64]],
      vrfs: Vector[VRFVerKeyHash]
  ): SortedMap[VRFVerKeyHash, NonZero[Word64]] =
    vrfs.foldLeft(counts)((m, vrf) =>
      m.get(vrf).fold(m)(n => decrement(n).fold(m - vrf)(m.updated(vrf, _)))
    )

  /** One decrement of a count; a count that reaches zero is removed (`Map.update` of `mapNonZero
    * (subtract 1)`).
    */
  private def decrement(n: NonZero[Word64]): Option[NonZero[Word64]] =
    NonZero.from(Word64.fromUnsigned(n.value.bits - 1)).toOption

  def step[E <: EraTag](
      epoch: EpochNo,
      st: PoolreapState[E],
      release: Release,
      adopt: Adopt
  )(using ShelleyBasedEra[E]): Either[TickFailure, PoolreapState[E]] =
    val ls0 = LedgerState[E](st.cert, st.utxo)
    val ps0 = EraLedger.pState(ls0)
    // the active VRF key hash of each pool re-registered with another key, in pool order
    val superseded: Vector[VRFVerKeyHash] =
      ps0.stakePools.iterator.flatMap { (id, sps) =>
        ps0.future.get(id).filter(_.vrf != sps.vrf).as(sps.vrf)
      }.toVector
    // the future parameters take effect
    val activated: Either[TickFailure, SortedMap[KeyHash[StakePool], StakePoolState]] =
      ps0.future.toList
        .foldM(ps0.stakePools) { case (pools, (id, params)) =>
          pools.get(id) match
            case None          => Right(pools)
            case Some(current) =>
              // `mkStakePoolState e _ (spsDeposit) (spsDelegators) futureParams`, its voting key
              // the era's (`adopt`)
              adopt(current, params, epoch).flatMap(blsKey =>
                Convert
                  .poolState(params, blsKey, current.deposit, current.delegators)
                  .bimap(TickFailure.Vocab(_), pools.updated(id, _))
              )
        }
    activated.map { pools =>
      val retired: Set[KeyHash[StakePool]] =
        ps0.retiring.iterator.collect { case (id, e) if e == epoch => id }.toSet
      val retiring = pools.filter((id, _) => retired(id))
      val refundsByAccount: Map[Credential[Staking], CompactCoin] =
        retiring.valuesIterator.toVector
          .groupMapReduce(_.accountId)(_.deposit)((a, b) => a.add(b))
      val (refunds, unclaimedDeposits) =
        refundsByAccount.partition((c, _) => EraLedger.isRegistered(ls0, c))
      val refunded = CompactCoin.sum(refunds.values)
      val unclaimed = CompactCoin.sum(unclaimedDeposits.values)
      val clear = retiring.valuesIterator.flatMap(_.delegators).toVector
      val vrfs = release(ps0.vrfKeyHashes, superseded, retiring.valuesIterator.map(_.vrf).toVector)
      val ps = ps0.copy(
        vrfKeyHashes = vrfs,
        stakePools = pools.filter((id, _) => !retired(id)),
        future = Sorted.empty,
        retiring = ps0.retiring.filter((id, _) => !retired(id))
      )
      val credited = EraLedger.credit(ls0, refunds)
      val cleared = EraLedger.clearPoolDelegations(credited, clear)
      val deposited = Coin(st.utxo.deposited.value - unclaimed.add(refunded).toBigInt)
      val treasury = Coin(st.chainAccount.treasury.value + unclaimed.toBigInt)
      PoolreapState[E](
        st.utxo.copy(deposited = deposited),
        st.chainAccount.copy(treasury = treasury),
        EraLedger.withPState(cleared, ps).cert
      )
    }
