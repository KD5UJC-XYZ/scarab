// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{EnactState, GovAction, GovActionId, LedgerFailure, Ratify}

/** The translated inputs supplied to the Conway ENACT judgment. */
final case class EnactRuleEnv(epoch: BigInt, treasury: BigInt)

/** `[governance_action_id, governance_action]`, the Conway ENACT signal. */
final case class EnactSignal(id: GovActionId, action: GovAction)

/** Conway ENACT (`Conway/Rules/Enact.hs`): apply one already-ratified action to EnactState. */
object Enact extends Sts[EnactRuleEnv, EnactState, EnactSignal, LedgerFailure]:
  def transition[F[_]: Monad](env: EnactRuleEnv, signal: EnactSignal)(using
      ask: Ask[F, Globals],
      st: Stateful[F, EnactState],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    st.get.flatMap(state => st.set(Ratify.enact(state, signal.id, signal.action)))
