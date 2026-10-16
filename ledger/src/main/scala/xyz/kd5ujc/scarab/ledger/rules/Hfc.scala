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
  * Haskell ticks to a slot only, and consensus never skips a whole epoch; the node's chains can (a
  * test cluster), so each epoch crossed has its boundary. The tick of the slot itself ([[Tick]])
  * comes last, in the epoch of the slot, and it is the one that does the slot-dependent work.
  *
  * The chain over which this is run is unbounded (epochs crossed), so the loop is `tailRecM`.
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
    // one step: translate into the next era, run the boundary of an epoch no block is in, or tick
    Monad[Result].tailRecM(start) { current =>
      val epoch = current.epoch.toBigInt
      def boundary(next: BigInt): Result[Either[EraNes, EraNes]] =
        if next < target then
          EpochNo.fromBigInt(next).left.map(TickFailure.Vocab(_)).flatMap { e =>
            newEpoch(globals, current, e).map(Left(_))
          }
        else ticked(globals, current, slot).map(Right(_))
      if epoch >= target then ticked(globals, current, slot).map(Right(_))
      else if current.toEra.ordinal < eraAt(epoch + 1, adoptedMajor(current)).ordinal then
        Translate.next(globals, current).flatMap {
          case Some(translated) => Right(Left(translated))
          case None             => boundary(epoch + 1)
        }
      else boundary(epoch + 1)
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

  private def newEpoch(globals: Globals, e: EraNes, epoch: EpochNo): Either[TickFailure, EraNes] =
    e match
      case i: EraNes.InEra[e] =>
        given ShelleyBasedEra[e] = i.era
        Sts(NewEpoch.sts[e])(globals, (), i.nes, epoch).map(EraNes.InEra(i.era, _))
