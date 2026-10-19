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
  * Each tick in the interval starts or advances the same pure pulser. Past the force point,
  * remaining work completes synchronously, including an update imported from a Haskell state.
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
          next <- Sts.lift[F, TickFailure, Option[PulsingRewUpdate]](
            (if s <= start then Right(None)
             else
               val force = start + globals.randomnessStabilisationWindow
               val advanced = current match
                 case None                 => Rewards.start(env.es, env.blocks, globals, epoch)
                 case Some(p) if s > force => Right(p)
                 case Some(p)              => Rewards.pulse(p)
               advanced.flatMap { p =>
                 if s > force then Rewards.complete(p).map(u => Some(PulsingRewUpdate.Complete(u)))
                 else Right(Some(p))
               }
            ) .leftMap(TickFailure.Vocab(_))
          )
          _ <- ru.set(next)
        yield ()
