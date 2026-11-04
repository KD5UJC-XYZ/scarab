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

  /** What the hard-fork configuration answers of a state: whether, and at which epoch, the chain
    * leaves its era.
    */
  enum Decision:
    /** No transition is decided: the chain stays in its era. */
    case Stays

    /** The chain leaves its era at `epoch`. */
    case Leaves(epoch: BigInt)

    /** The chain leaves its era at `epoch` if the blocks after the voting deadline reached `k`,
      * which the state cannot tell: its counter is not known (`Gap.ShelleyTransition` of a snapshot
      * of the retired flat formats that the immutable store could not recount). The reference never
      * has an unknown counter (it serialises it), so this answer is Scarab's own and is never taken
      * as either way: the owner approved refusing to cross on it (2026-11-04, PR #772).
      */
    case Undecided(epoch: BigInt)

  object Decision:
    /** The decision of a rule that always knows its answer. */
    def of(leaves: Option[BigInt]): Decision = leaves.fold(Stays)(Leaves(_))

  /** A transition out of `era` at `epoch` that the state's unknown counter leaves undecided
    * ([[Decision.Undecided]]), met by a block of `epoch` or later.
    */
  final case class UndecidedTransition(era: Era, epoch: BigInt)

  /** Whether, and at which epoch, the chain leaves the era of the state. */
  type Transition = Pending => Decision

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
      val decision =
        if epoch < target then transition(pending(current, afterVoting)) else Decision.Stays
      decision match
        case Decision.Leaves(at) if at <= target =>
          Translate.next(globals, current).flatMap {
            case Some(translated) => Right(Left(translated))
            case None             => ticked(globals, current, slot).map(Right(_))
          }
        // refused, not guessed (owner-approved 2026-11-04, PR #772): see `Decision.Undecided`
        case Decision.Undecided(at) if at <= target =>
          Left(TickFailure.TransitionUndecided(current.toEra, at))
        case _ => ticked(globals, current, slot).map(Right(_))
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
