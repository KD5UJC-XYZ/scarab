// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.state.*

/** EPOCH of the era `E` (`Shelley/Rules/Epoch.hs`, `Conway/Rules/Epoch.hs`): the Shelley rule
  * before Conway, the Conway rule from it, chosen by the witness.
  */
object Epoch:
  def sts[E <: EraTag](using
      era: ShelleyBasedEra[E]
  ): Sts[Unit, EpochState[E], EpochNo, TickFailure] =
    new Sts[Unit, EpochState[E], EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, epoch: EpochNo)(using
          ask: Ask[F, Globals],
          es: Stateful[F, EpochState[E]],
          failures: Raise[F, TickFailure]
      ): F[Unit] = era match
        case ShelleyBasedEra.SConway => ConwayEpoch.sts.transition[F](env, epoch)
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
            ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
          shelley[E].transition[F](env, epoch)

  /** Runs a sub-rule on the part of the state that `lens` names (`trans`, `Extended.hs:475`; all
    * the rules of the tick fail with the same [[TickFailure]], so the failures need no wrapping).
    */
  private[rules] def within[F[_]: Monad, Env, S, S0, Sig](
      sub: Sts[Env, S, Sig, TickFailure],
      env: Env,
      signal: Sig,
      lens: Lens[S0, S]
  )(using Ask[F, Globals], Stateful[F, S0], Raise[F, TickFailure]): F[Unit] =
    given Embed[S, S0, TickFailure, TickFailure] = Embed(lens, identity[TickFailure])
    Sts.trans[F, Env, S, S0, Sig, TickFailure, TickFailure](sub, env, signal)

  /** The Shelley governance state seen in the governance state of any era (the lens of
    * [[EraLedger.shelleyGov]]) in an epoch state.
    */
  private def shelleyGov[E <: EraTag](using
      ShelleyBasedEra[E]
  ): Lens[EpochState[E], ShelleyGovState] =
    EpochState
      .ls[E]
      .andThen(LedgerState.utxo[E])
      .andThen(UTxOState.gov[E])
      .andThen(EraLedger.shelleyGov[E])

  /** `totalObligation` (`Shelley/LedgerState/Types.hs`): what the certificates and the governance
    * state hold in deposits. Before Conway: the accounts' and the pools'.
    */
  private def obligation[E <: EraTag](ls: LedgerState[E])(using ShelleyBasedEra[E]): BigInt =
    EraLedger.keyDeposits(ls) +
      EraLedger.pState(ls).stakePools.valuesIterator.foldLeft(BigInt(0))(_ + _.deposit.toBigInt)

  /** EPOCH of Shelley to Babbage (`Shelley/Rules/Epoch.hs:142`): SNAP, POOLREAP and UPEC, then the
    * deposits are counted again and the parameters of the next epoch are in force.
    */
  private def shelley[E <: EraTag](using
      ShelleyBasedEra[E]
  ): Sts[Unit, EpochState[E], EpochNo, TickFailure] =
    new Sts[Unit, EpochState[E], EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, epoch: EpochNo)(using
          ask: Ask[F, Globals],
          es: Stateful[F, EpochState[E]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        for
          es0 <- es.get
          _ <- within(Snap.sts[E], es0.ls, (), EpochState.snapshots[E])
          _ <- within(PoolReap.sts[E], (), epoch, PoolreapState.lens[E])
          es1 <- es.get
          _ <- within(Upec.sts[E], es1.ls, (), shelleyGov[E])
          es2 <- es.get
          deposited <- Sts.lift[F, TickFailure, Coin](
            // the governance state of these eras holds no deposits
            Coin.fromBigInt(obligation(es2.ls)).leftMap(TickFailure.Vocab(_))
          )
          _ <- es.set(
            es2.copy(ls = es2.ls.copy[E](utxo = es2.ls.utxo.copy(deposited = deposited)))
          )
        yield ()
