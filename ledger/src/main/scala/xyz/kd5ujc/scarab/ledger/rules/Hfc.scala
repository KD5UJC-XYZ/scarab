// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.Era

/** The ledger side of the hard-fork combinator's tick (`applyChainTickLedgerResult`,
  * `HardFork/Combinator/Ledger.hs:160`): bring the new-epoch state of whichever era the chain is in
  * to a slot, translating it into each era the chain has entered on the way, and ticking it in the
  * era it ends in.
  *
  * The order is the combinator's (`extendToSlot` before `tickOne`): at an epoch boundary where the
  * next era begins the state is translated first and the boundary (NEWEPOCH) runs in the new era.
  * Whether the chain leaves its era is the hard-fork configuration's decision ([[Transition]]),
  * made as the combinator makes it (`extendToSlot`): from the state before the tick.
  *
  * Like the reference, this ticks to a slot only: [[Tick]] runs once, in the epoch of the slot, and
  * NEWEPOCH does nothing when that epoch is not the one after the state's (`Shelley/Rules/
  * NewEpoch.hs:148`, `pure src`). The boundaries of an epoch with no block are therefore not
  * crossed here either.
  *
  * The only loop is over the eras translated on the way (the combinator's `extendToSlot`), so it is
  * `tailRecM`.
  */
object Hfc:

  /** What `singleEraTransition` reads of the state of the era the chain is in
    * (`ShelleyHFC.hs:194-227`, ouroboros-consensus 82ecba32).
    *
    * @param epoch
    *   the epoch of the state
    * @param update
    *   the protocol major version of the definite parameter update the next boundary adopts (`SJust
    *   pp` of `pparamsUpdate`); `None` when there is none, even if the parameters in force already
    *   have the version
    * @param afterVoting
    *   the blocks of `epoch` at or after its voting deadline (`shelleyAfterVoting`)
    */
  final case class Pending(era: Era, epoch: BigInt, update: Option[BigInt], afterVoting: Long)

  /** The epoch at which the chain leaves the era of the state, when that is decided. */
  type Transition = Pending => Option[BigInt]

  /** The state at `slot`, from the state `start` of the epoch the chain is in, whose blocks after
    * the voting deadline are `afterVoting`. A translation carries the update and the counter over
    * (`Cardano/CanHardFork.hs:704`), so every era on the way is asked with the same counter.
    */
  def tick(globals: Globals, transition: Transition)(
      start: EraNes,
      afterVoting: Long,
      slot: SlotNo
  ): Either[TickFailure, EraNes] =
    val target = globals.epochOf(slot.toBigInt)
    type Result[A] = Either[TickFailure, A]
    // one step: translate into the next era (the slot is past the end of the state's era, as
    // decided from the state before it, whatever the state's own epoch), or tick
    Monad[Result].tailRecM(start) { current =>
      val epoch = current.epoch.toBigInt
      if epoch < target && transition(pending(current, afterVoting)).exists(_ <= target) then
        Translate.next(globals, current).flatMap {
          case Some(translated) => Right(Left(translated))
          case None             => ticked(globals, current, slot).map(Right(_))
        }
      else ticked(globals, current, slot).map(Right(_))
    }

  /** What the combinator reads of `e`: the definite update only (`ShelleyHFC.hs:222`). */
  def pending(e: EraNes, afterVoting: Long): Pending = e match
    case i: EraNes.InEra[e] =>
      given ShelleyBasedEra[e] = i.era
      Pending(
        e.toEra,
        i.nes.epoch.toBigInt,
        EraLedger.futurePParams(i.nes.es.ls.utxo.gov).definite.map(_.protocolMajor),
        afterVoting
      )

  private def ticked(globals: Globals, e: EraNes, slot: SlotNo): Either[TickFailure, EraNes] =
    e match
      case i: EraNes.InEra[e] =>
        given ShelleyBasedEra[e] = i.era
        Sts(Tick.sts[e])(globals, (), i.nes, slot).map(EraNes.InEra(i.era, _))
