// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.state.*

/** NEWEPOCH (`Shelley/Rules/NewEpoch.hs:148`, `Conway/Rules/NewEpoch.hs:154`): the new-epoch state
  * moves to epoch `e` when `e` is the epoch after the one it is in.
  *
  * At the boundary: the reward update is applied (`updateRewards`), MIR pays the instantaneous
  * rewards (before Conway), EPOCH runs, and the epoch's blocks become the previous epoch's, the
  * reward update is cleared and the pool distribution is the one of the `mark` snapshot the
  * boundary has just made `set` (`ssStakeMarkPoolDistr` of the snapshots before EPOCH). Inside the
  * epoch Conway predicts the future parameters again (`Conway/Rules/NewEpoch.hs:160-165`) and the
  * earlier eras change nothing.
  *
  * Haskell does nothing for an epoch that is not the next one (`pure src`), and consensus never
  * ticks across a whole epoch with no blocks; the node's chains can (a test cluster), so [[Hfc]]
  * runs this rule once for each epoch crossed.
  */
object NewEpoch:
  def sts[E <: EraTag](using
      era: ShelleyBasedEra[E]
  ): Sts[Unit, NewEpochState[E], EpochNo, TickFailure] =
    new Sts[Unit, NewEpochState[E], EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, epoch: EpochNo)(using
          ask: Ask[F, Globals],
          nes: Stateful[F, NewEpochState[E]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        nes.get.flatMap { current =>
          withinEpoch(current.epoch, epoch, current.es).fold(boundary(current, epoch))(es =>
            nes.set(current.copy(es = es))
          )
        }

      private def boundary[F[_]: Monad](before: NewEpochState[E], epoch: EpochNo)(using
          ask: Ask[F, Globals],
          nes: Stateful[F, NewEpochState[E]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        val es = nes.focus(NewEpochState.es[E])
        for
          _ <- before.ru match
            case None => Monad[F].unit
            case Some(pending) =>
              Sts.modifyE[F, EpochState[E], TickFailure](state =>
                Rewards
                  .complete(pending)
                  .flatMap(applyRewardUpdate(state, _))
                  .leftMap(TickFailure.Vocab(_))
              )(using Monad[F], es, failures)
          _ <- era match
            case ShelleyBasedEra.SConway => Monad[F].unit
            case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
                ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
              Epoch.within(Mir.sts[E], (), (), NewEpochState.es[E])
          _ <- Epoch.within(Epoch.sts[E], (), epoch, NewEpochState.es[E])
          _ <- nes.modify(n =>
            n.copy(
              epoch = epoch,
              bprev = before.bcur,
              bcur = BlocksMade.empty,
              ru = None,
              pd = before.es.snapshots.markPoolDistr
            )
          )
        yield ()

  /** The existing future-parameter prediction branch, corresponding to
    * `Conway/Rules/NewEpoch.hs:160-166` at cardano-ledger
    * `f649f9751074d2ab3de033fc3912f29c9862c1f5`. Internal DRep pulse scheduling is separate from
    * this projection. Returning `None` delegates the next-epoch signal to the boundary rule;
    * untouched outer fields, including pool distribution, are not required by this branch.
    */
  def withinEpoch[E <: EraTag](current: EpochNo, signal: EpochNo, es: EpochState[E])(using
      ShelleyBasedEra[E]
  ): Option[EpochState[E]] =
    Option.unless(current.next.contains(signal))(
      es.copy(ls =
        es.ls.copy[E](utxo = es.ls.utxo.copy(gov = EraLedger.predictFuture(es.ls.utxo.gov)))
      )
    )

  /** The rewards of the accounts that are registered, and of those that are not, each in the order
    * of the credential. The rewards and the accounts are both in that order, so they are walked
    * together once, where a lookup of each reward in the accounts, or a `partition` of the map (two
    * trees built by inserting each entry), compares about seventeen keys for each.
    */
  private def partitionRegistered(
      rewards: SortedMap[Credential[Staking], RewardSet],
      registered: Credential[Staking] => Boolean
  ): (Vector[(Credential[Staking], RewardSet)], Vector[(Credential[Staking], RewardSet)]) =
    rewards.iterator.toVector.partition(entry => registered(entry._1))

  /** `updateRewards` with `applyRUpdFiltered` (`NewEpoch.hs:227`, `LedgerState/
    * IncrementalStake.hs:95`): the rewards of the registered accounts are added to their balances
    * and those of the accounts that are no longer registered go to the treasury; the treasury, the
    * reserves and the fees change by the update's deltas.
    */
  def applyRewardUpdate[E <: EraTag](es: EpochState[E], update: RewardUpdate)(using
      ShelleyBasedEra[E]
  ): Either[VocabError, EpochState[E]] =
    val major = EraLedger.prevPParams(es.ls.utxo.gov).protocolMajor
    val (registered, unregistered) =
      partitionRegistered(update.rs, EraLedger.registeredAscending(es.ls))
    val treasury = Coin(
      es.chainAccount.treasury.value + update.deltaT.value + Rewards.total(major, unregistered)
    )
    val reserves = Coin(es.chainAccount.reserves.value + update.deltaR.value)
    val fees = Coin(es.ls.utxo.fees.value + update.deltaF.value)
    Rewards.aggregateCompact(major, registered).map { delivered =>
      val credited = EraLedger.creditAscending(es.ls, delivered)
      es.copy(
        chainAccount = ChainAccountState(treasury, reserves),
        ls = credited.copy[E](utxo = credited.utxo.copy(fees = fees)),
        nonMyopic = update.nonMyopic
      )
    }
