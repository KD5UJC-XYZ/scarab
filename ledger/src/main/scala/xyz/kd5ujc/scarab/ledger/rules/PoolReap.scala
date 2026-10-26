// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import monocle.Lens
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.state.*

/** `ShelleyPoolreapState` (`Shelley/Rules/PoolReap.hs:61`): the three parts of the epoch state that
  * retiring a pool changes.
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

/** POOLREAP (`Shelley/Rules/PoolReap.hs:131`, the same rule in Conway): at the epoch boundary the
  * pools' future parameters take effect, and the pools that retire at the epoch are removed with
  * their delegators' delegations; their deposits go back to the reward accounts, or, where the
  * account is no longer registered, to the treasury.
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
        Sts.modifyE[F, PoolreapState[E], TickFailure](step(epoch, _).leftMap(TickFailure.Vocab(_)))

  /** One decrement of a count; a count that reaches zero is removed (`Map.update` of `mapNonZero
    * (subtract 1)`).
    */
  private def decrement(n: NonZero[Word64]): Option[NonZero[Word64]] =
    NonZero.from(Word64.fromUnsigned(n.value.bits - 1)).toOption

  def step[E <: EraTag](epoch: EpochNo, st: PoolreapState[E])(using
      ShelleyBasedEra[E]
  ): Either[VocabError, PoolreapState[E]] =
    val ls0 = LedgerState[E](st.cert, st.utxo)
    val ps0 = EraLedger.pState(ls0)
    // the VRF key hashes no longer relevant: a pool re-registered with another key
    val dangling: Set[VRFVerKeyHash] =
      ps0.stakePools.iterator.flatMap { (id, sps) =>
        ps0.future.get(id).flatMap { f =>
          VRFVerKeyHash.fromBytes(f.vrfKeyHash).toOption.filter(_ != sps.vrf).as(sps.vrf)
        }
      }.toSet
    // the future parameters take effect
    val activated: Either[VocabError, SortedMap[KeyHash[StakePool], StakePoolState]] =
      ps0.future.toList
        .foldM(ps0.stakePools) { case (pools, (id, params)) =>
          pools.get(id) match
            case None => Right(pools)
            case Some(current) =>
              Convert
                .poolState(params, current.deposit, current.delegators)
                .map(pools.updated(id, _))
        }
    activated.flatMap { pools =>
      val retired: Set[KeyHash[StakePool]] =
        ps0.retiring.iterator.collect { case (id, e) if e == epoch => id }.toSet
      val retiring = pools.filter((id, _) => retired(id))
      val refundsByAccount: Map[Credential[Staking], BigInt] =
        retiring.valuesIterator.toVector
          .groupMapReduce(_.accountId)(_.deposit.toBigInt)(_ + _)
      val (refunds, unclaimedDeposits) =
        refundsByAccount.partition((c, _) => EraLedger.isRegistered(ls0, c))
      val refunded = refunds.values.sum
      val unclaimed = unclaimedDeposits.values.sum
      val clear = retiring.valuesIterator.flatMap(_.delegators).toVector
      val vrfs: SortedMap[VRFVerKeyHash, NonZero[Word64]] =
        retiring.valuesIterator
          .map(_.vrf)
          .foldLeft(ps0.vrfKeyHashes.filter((k, _) => !dangling(k)))((m, vrf) =>
            m.get(vrf).fold(m)(n => decrement(n).fold(m - vrf)(m.updated(vrf, _)))
          )
      val ps = ps0.copy(
        vrfKeyHashes = vrfs,
        stakePools = pools.filter((id, _) => !retired(id)),
        future = Sorted.empty,
        retiring = ps0.retiring.filter((id, _) => !retired(id))
      )
      val amounts = refunds.toList.map((c, n) => c -> Coin(n))
      for
        credited <- EraLedger.credit(ls0, amounts)
        cleared = EraLedger.clearPoolDelegations(credited, clear)
        deposited = Coin(st.utxo.deposited.value - (unclaimed + refunded))
        treasury = Coin(st.chainAccount.treasury.value + unclaimed)
      yield PoolreapState[E](
        st.utxo.copy(deposited = deposited),
        st.chainAccount.copy(treasury = treasury),
        EraLedger.withPState(cleared, ps).cert
      )
    }
