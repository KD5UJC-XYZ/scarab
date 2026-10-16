// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.GenDelegPair
import xyz.kd5ujc.scarab.ledger.state.*

/** TICK (`Shelley/Rules/Tick.hs`: `bheadTransition` 244, `validatingTickTransition` 160, the same
  * rule in every era): the ledger state is brought to a slot. In order:
  *
  *   1. `solidifyNextEpochPParams` (`Tick.hs:144`): from the point of no return (two stability
  *      windows before the end of the epoch) the parameters that will take effect at the boundary
  *      are certain;
  *   2. NEWEPOCH for the epoch of the slot, which does something only at the boundary
  *      ([[NewEpoch]]);
  *   3. `adoptGenesisDelegs`: the genesis delegations due by the slot take effect;
  *   4. RUPD for the slot ([[Rupd]]), with the blocks made and the epoch state as they were when
  *      the tick began.
  *
  * The environment is `()` and the signal the slot, as in Haskell (`type Environment (TICK era) =
  * ()`, `Signal = SlotNo`). Era changes are not this rule's: the hard-fork combinator translates
  * the state before it ticks into the new era ([[Hfc]]).
  */
object Tick:
  def sts[E <: EraTag](using
      ShelleyBasedEra[E]
  ): Sts[Unit, NewEpochState[E], SlotNo, TickFailure] =
    new Sts[Unit, NewEpochState[E], SlotNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, slot: SlotNo)(using
          ask: Ask[F, Globals],
          nes: Stateful[F, NewEpochState[E]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        for
          before <- nes.get
          globals <- Sts.globals[F]
          now = slot.toBigInt
          epoch = globals.epochOf(now)
          // `getTheSlotOfNoReturn` (`Slot.hs:106`): `*-` is monus, so it does not go below the first slot
          pointOfNoReturn = (globals.firstSlot(epoch + 1) - 2 * globals.stabilityWindow).max(0)
          _ <- nes.modify(n =>
            if now < pointOfNoReturn then n
            else
              n.copy(es =
                n.es.copy(ls =
                  n.es.ls.copy[E](utxo =
                    n.es.ls.utxo.copy(gov =
                      EraLedger.withFuture(
                        n.es.ls.utxo.gov,
                        EraLedger.solidify(EraLedger.futurePParams(n.es.ls.utxo.gov))
                      )
                    )
                  )
                )
              )
          )
          current <- Sts.lift[F, TickFailure, EpochNo](
            EpochNo.fromBigInt(epoch).leftMap(TickFailure.Vocab(_))
          )
          _ <- NewEpoch.sts[E].transition[F]((), current)
          _ <- nes.modify(n => n.copy(es = n.es.copy(ls = adoptGenesisDelegs(n.es.ls, now))))
          _ <- Epoch.within(
            Rupd.sts[E],
            RupdEnv(before.bprev, before.es),
            slot,
            NewEpochState.ru[E]
          )
        yield ()

  /** `adoptGenesisDelegs` (`Tick.hs:111`): the genesis delegations scheduled for a slot up to
    * `slot` replace the current ones; of several for one genesis key the latest slot wins.
    */
  def adoptGenesisDelegs[E <: EraTag](ls: LedgerState[E], slot: BigInt)(using
      ShelleyBasedEra[E]
  ): LedgerState[E] =
    val future = EraLedger.futureGenDelegs(ls)
    val (due, later) = future.partition((k, _) => k.slot.toBigInt <= slot)
    if due.isEmpty then ls
    else
      // `due` ascends by (slot, key): the last entry of a key is its latest
      val latest: Map[KeyHash[Genesis], GenDelegPair] =
        due.iterator.map((k, pair) => k.genKeyHash -> pair).toMap
      EraLedger.withGenDelegs(
        ls,
        EraLedger.genDelegs(ls) ++ latest,
        later
      )
