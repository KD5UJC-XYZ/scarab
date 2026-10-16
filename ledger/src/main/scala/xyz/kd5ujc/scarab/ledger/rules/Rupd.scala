// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.state.*

/** `RupdEnv` (`Shelley/Rules/Rupd.hs:65`): the blocks of the previous epoch and the epoch state the
  * reward calculation starts from.
  */
final case class RupdEnv[E <: EraTag](blocks: BlocksMade, es: EpochState[E])

/** RUPD (`Shelley/Rules/Rupd.hs:107`, `rupdTransition`; `determineRewardTiming` 101): the reward
  * update of the epoch, as a state of the new epoch state (`nesRu`). Its timing is the "Goldilocks"
  * one (`determineRewardTiming`):
  *
  *   - up to and including the slot `randomnessStabilisationWindow` (4k/f) slots into the epoch
  *     there is none yet (`RewardsTooEarly`, the state becomes `SNothing`);
  *   - from the first slot after it the calculation starts, with the epoch state as it is
  *     (`startStep`), and
  *   - past twice the window it is forced to completion (`RewardsTooLate`), so that tools can see
  *     the update before the epoch ends.
  *
  * Haskell pulses the calculation over the blocks in between; the node computes it when it starts
  * (design D8), so the state is `Complete` from the start, and `RewardsTooLate` only matters for a
  * state with nothing started. An update the node found `Pulsing` (read from a Haskell state, never
  * produced here) is left as it is.
  */
object Rupd:
  def sts[E <: EraTag](using
      ShelleyBasedEra[E]
  ): Sts[RupdEnv[E], Option[PulsingRewUpdate], SlotNo, TickFailure] =
    new Sts[RupdEnv[E], Option[PulsingRewUpdate], SlotNo, TickFailure]:
      def transition[F[_]: Monad](env: RupdEnv[E], slot: SlotNo)(using
          ask: Ask[F, Globals],
          ru: Stateful[F, Option[PulsingRewUpdate]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        for
          globals <- Sts.globals[F]
          s = slot.toBigInt
          epoch = globals.epochOf(s)
          start = globals.firstSlot(epoch) + globals.randomnessStabilisationWindow
          current <- ru.get
          // `RewardsTooEarly`: nothing yet; otherwise the calculation starts if it has not
          next <-
            if s <= start then Monad[F].pure(None)
            else
              current match
                case None =>
                  Sts.lift[F, TickFailure, Option[PulsingRewUpdate]](
                    Rewards
                      .update(env.es, env.blocks, globals, epoch)
                      .map(u => Some(PulsingRewUpdate.Complete(u)))
                      .leftMap(TickFailure.Vocab(_))
                  )
                case other => Monad[F].pure(other)
          _ <- ru.set(next)
        yield ()
