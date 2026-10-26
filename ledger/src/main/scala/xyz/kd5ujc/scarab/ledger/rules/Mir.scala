// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.state.*

/** MIR (`Shelley/Rules/Mir.hs:93`, `mirTransition`): the instantaneous rewards the certificates of
  * the epoch collected are paid at the epoch boundary, to the accounts that are still registered,
  * and only if both pots can pay what was asked of them (the pot-to-pot transfers count in what a
  * pot has). In either case the collected rewards are cleared. Conway has no MIR rule
  * (`Conway/Rules/ NewEpoch.hs`).
  */
object Mir:
  def sts[E <: EraTag](using ShelleyBasedEra[E]): Sts[Unit, EpochState[E], Unit, TickFailure] =
    new Sts[Unit, EpochState[E], Unit, TickFailure]:
      def transition[F[_]: Monad](env: Unit, signal: Unit)(using
          ask: Ask[F, Globals],
          es: Stateful[F, EpochState[E]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        Sts.modifyE[F, EpochState[E], TickFailure](step(_).leftMap(TickFailure.Vocab(_)))

  private val none: InstantaneousRewards =
    InstantaneousRewards(Sorted.empty, Sorted.empty, DeltaCoin(BigInt(0)), DeltaCoin(BigInt(0)))

  def step[E <: EraTag](es: EpochState[E])(using
      ShelleyBasedEra[E]
  ): Either[VocabError, EpochState[E]] =
    val ir = EraLedger.iRewards(es.ls)
    val toReserves = ir.reserves.filter((c, _) => EraLedger.isRegistered(es.ls, c))
    val toTreasury = ir.treasury.filter((c, _) => EraLedger.isRegistered(es.ls, c))
    val totalR = toReserves.valuesIterator.foldLeft(BigInt(0))(_ + _.value)
    val totalT = toTreasury.valuesIterator.foldLeft(BigInt(0))(_ + _.value)
    val availableR = es.chainAccount.reserves.value + ir.deltaReserves.value
    val availableT = es.chainAccount.treasury.value + ir.deltaTreasury.value
    val cleared = es.copy(ls = EraLedger.withIRewards(es.ls, none))
    if totalR <= availableR && totalT <= availableT then
      EraLedger
        .credit(cleared.ls, (toReserves.iterator ++ toTreasury.iterator).toVector)
        .map(credited =>
          es.copy(
            ls = credited,
            chainAccount = ChainAccountState(Coin(availableT - totalT), Coin(availableR - totalR))
          )
        )
    else Right(cleared)
