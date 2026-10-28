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
  * next era begins the state is translated first and the boundary (NEWEPOCH) runs in the new era;
  * the old rules did the boundary first and translated after, with a special case for Conway. Which
  * era the chain is in at an epoch is the hard-fork configuration's (`eraAt`: by protocol version
  * or by configured epoch); the version asked of is the one the boundary is about to adopt, the
  * parameters of the next epoch (`nextEpochPParams`), as the ledger's `shelleyTransition` does.
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

  /** The era the chain is in at `epoch` when the parameters adopted there have protocol major
    * version `major`.
    */
  type EraAt = (BigInt, BigInt) => Era

  /** The state at `slot`, from the state `start` of the epoch the chain is in. */
  def tick(globals: Globals, eraAt: EraAt)(
      start: EraNes,
      slot: SlotNo
  ): Either[TickFailure, EraNes] =
    val target = globals.epochOf(slot.toBigInt)
    type Result[A] = Either[TickFailure, A]
    // one step: translate into the next era (the slot is past the end of the state's era: the era
    // of the slot's epoch is a later one, whatever the state's own epoch), or tick
    Monad[Result].tailRecM(start) { current =>
      val epoch = current.epoch.toBigInt
      if epoch < target && current.toEra.ordinal < eraAt(target, adoptedMajor(current)).ordinal
      then
        Translate.next(globals, current).flatMap {
          case Some(translated) => Right(Left(translated))
          case None             => ticked(globals, current, slot).map(Right(_))
        }
      else ticked(globals, current, slot).map(Right(_))
    }

  /** The major version of the parameters the next boundary adopts (`nextEpochPParams`). */
  private def adoptedMajor(e: EraNes): BigInt = e match
    case i: EraNes.InEra[e] =>
      given ShelleyBasedEra[e] = i.era
      val gov = i.nes.es.ls.utxo.gov
      EraLedger.futurePParams(gov).definite.getOrElse(EraLedger.curPParams(gov)).protocolMajor

  private def ticked(globals: Globals, e: EraNes, slot: SlotNo): Either[TickFailure, EraNes] =
    e match
      case i: EraNes.InEra[e] =>
        given ShelleyBasedEra[e] = i.era
        Sts(Tick.sts[e])(globals, (), i.nes, slot).map(EraNes.InEra(i.era, _))
