// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.UpdateChecks
import xyz.kd5ujc.scarab.ledger.state.*

/** UPEC with the NEWPP rule it embeds (`Shelley/Rules/Upec.hs:74`, `Newpp.hs:68,88`; Shelley to
  * Babbage): at the epoch boundary the protocol parameters that the proposals voted for take effect
  * and the proposals rotate.
  *
  *   - The parameters adopted are `nextEpochPParams` of the governance state
  *     (`State/Governance.hs:121`): the future parameters when they are `Definite`, else the ones
  *     in force. They are `Definite` only if the tick two stability windows before the end of the
  *     epoch solidified them (`solidifyNextEpochPParams`, `Tick.hs:144`), as the TICK rule does.
  *   - The proposals made for the next epoch become the current ones if every one of them can
  *     follow the parameters adopted (`hasLegalProtVerUpdate`), else they are dropped
  *     (`updatePpup`, `Newpp.hs:97`); the future proposals are empty.
  *   - The future parameters become `Potential` of what the new current proposals vote for
  *     (`votedFuturePParams`, `Rules/Ppup.hs:205`), lazily in Haskell and computed here.
  *
  * The state is the governance state, which holds the parameters in force (`usCurPParams` of
  * Haskell's `UpecState pp ppupState`); EPOCH sets the previous parameters afterwards.
  */
object Upec:
  def sts[E <: EraTag](using
      ShelleyBasedEra[E]
  ): Sts[LedgerState[E], ShelleyGovState, Unit, TickFailure] =
    new Sts[LedgerState[E], ShelleyGovState, Unit, TickFailure]:
      def transition[F[_]: Monad](ls: LedgerState[E], signal: Unit)(using
          globals: Ask[F, Globals],
          gov: Stateful[F, ShelleyGovState],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        Sts.globals[F].flatMap(g => gov.modify(step(g.quorum, _)))

  def step(quorum: Int, g: ShelleyGovState): ShelleyGovState =
    val adopted = g.futurePParams.definite.getOrElse(g.curPParams)
    val proposals =
      if g.futureProposals.valuesIterator.forall(UpdateChecks.canFollow(adopted, _).isRight) then
        g.futureProposals
      else Sorted.empty[KeyHash[Genesis], xyz.kd5ujc.scarab.ledger.ParamUpdate]
    g.copy(
      curProposals = proposals,
      futureProposals = Sorted.empty,
      curPParams = adopted,
      prevPParams = g.curPParams,
      futurePParams = FuturePParams.PotentialPParamsUpdate(
        UpdateChecks.voted(adopted, proposals.iterator.map((k, u) => k.bytes -> u).toMap, quorum)
      )
    )
