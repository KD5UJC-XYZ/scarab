// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, TranslationError, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.{
  Block,
  EraBlockHeader,
  PraosEraBlockHeader,
  ProposalsReadError,
  ProposalsWriteError,
  ScriptVerdictUnavailable
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judgment, RuleT, contramap}
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

/** What ends a run of Conway's rules on a block without a verdict: the defects the rules raise,
  * each typed as its rule raises it, GOV's proposals the node's record cannot give or hold among
  * them ([[ProposalsRecord]]).
  */
enum ConwayRuleDefect:
  case Unavailable(cause: ScriptVerdictUnavailable)
  case UtxoState(cause: UtxoStateDefect)
  case Ledger(cause: LedgerDefect)
  case Gov(cause: GovDefect)
  case ProposalsRead(cause: ProposalsReadError)
  case ProposalsWrite(cause: ProposalsWriteError)

/** Conway's rules on a block, run as the node applies the block (`applyBlockEither` with
  * `ValidateAll`, cardano-ledger f649f975 `Shelley/API/Validation.hs`): BBODY in one judgment
  * ([[ConwayBbody.conwayBbody]]), every failure accumulated (`RuleT.Policy.Accumulate`); the events
  * are discarded. The node does not run it yet: its blocks run the flat BBODY until the Conway
  * switch (#85).
  */
object ConwayBlockRun:
  private type F[A] =
    RuleT[BbodyPredFailure[Conway], ConwayRuleDefect, ShelleyBbodyState[Conway], A]
  import RuleT.given

  // the events of a block applied by the node are not kept (`ComputeLedgerEvents` off)
  private given Tell[F, Chain[BbodyEvent[Conway]]] = new Tell[F, Chain[BbodyEvent[Conway]]]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Chain[BbodyEvent[Conway]]): F[Unit] = Monad[F].unit

  private given Raise[F, ScriptVerdictUnavailable] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Unavailable(_))
  private given Raise[F, UtxoStateDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.UtxoState(_))
  private given Raise[F, LedgerDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Ledger(_))
  private given Raise[F, GovDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Gov(_))
  private given Raise[F, ProposalsReadError] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.ProposalsRead(_))
  private given Raise[F, ProposalsWriteError] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.ProposalsWrite(_))

  private def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
    request => Monad[F].unit.map(_ => evaluator.evaluate(request))

  /** BBODY on `block` from `state`: a defect, the refusal's failures, or the BBODY state after. */
  def bbody[H](
      globals: Globals,
      env: BbodyEnv[Conway],
      block: Block[H, Conway],
      context: UtxoContext[Conway],
      state: ShelleyBbodyState[Conway],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  )(using
      EraBlockHeader[H, Conway],
      PraosEraBlockHeader[H, Conway]
  ): Either[ConwayRuleDefect, Either[
    NonEmptyChain[BbodyPredFailure[Conway]],
    ShelleyBbodyState[Conway]
  ]] =
    given ScriptEvaluator[F] = lifted(evaluator)
    Judgment
      .judge(
        ConwayBbody.conwayBbody[F, H](env, block, context, verdicts, decoder, slotToPOSIXTime),
        RuleT.Policy.Accumulate
      )(globals, state)
      .map(_.map(_._1))
