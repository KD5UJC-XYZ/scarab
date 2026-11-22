// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{Horizon, LedgerDefect, TranslationError, TxEnv, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.{
  Block,
  EraBlockHeader,
  LeiosEraBlockHeader,
  Proposals,
  ScriptVerdictUnavailable,
  Tx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{BlocksMade, Dijkstra, LedgerState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

/** What ends a run of Dijkstra's rules on a block without a verdict: the defects the rules raise,
  * each typed as its rule raises it.
  */
enum DijkstraRuleDefect:
  case Unavailable(cause: ScriptVerdictUnavailable)
  case UtxoState(cause: UtxoStateDefect)
  case Ledger(cause: LedgerDefect)
  case Pool(cause: PoolDefect)
  case Gov(cause: GovDefect)

/** Dijkstra's rules on a block, run as the node applies the block (`applyBlockEither` with
  * `ValidateAll`, cardano-ledger 9df9f98c `Shelley/API/Validation.hs`): BBODY in one judgment,
  * every failure accumulated (`RuleT.Policy.Accumulate`), every check of its transactions validated
  * (`TxEnv.Validation.All`, the `ValidateAll` of `applyBlockEither`), and the application of an
  * endorser block's closure, which records none ([[DijkstraClosure.applyLeiosClosure]]). The
  * proposals are their own state beside the ledger's, as LEDGER keeps them ([[ProposalsRecord]]);
  * the events are discarded.
  */
object DijkstraBlockRun:
  private type After = (ShelleyBbodyState[Dijkstra], Proposals[Dijkstra])

  private type F[A] = RuleT[BbodyPredFailure[Dijkstra], DijkstraRuleDefect, After, A]
  import RuleT.given

  private given Stateful[F, ShelleyBbodyState[Dijkstra]] =
    summon[Stateful[F, After]].focus(
      Lens[After, ShelleyBbodyState[Dijkstra]](_._1)(b => s => (b, s._2))
    )

  private given Stateful[F, Proposals[Dijkstra]] =
    summon[Stateful[F, After]].focus(Lens[After, Proposals[Dijkstra]](_._2)(p => s => (s._1, p)))

  private given Stateful[F, LedgerState[Dijkstra]] =
    summon[Stateful[F, ShelleyBbodyState[Dijkstra]]].focus(ShelleyBbodyState.ls[Dijkstra])

  // the events of a block applied by the node are not kept (`ComputeLedgerEvents` off)
  private given Tell[F, Chain[BbodyEvent[Dijkstra]]] = new Tell[F, Chain[BbodyEvent[Dijkstra]]]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Chain[BbodyEvent[Dijkstra]]): F[Unit] = Monad[F].unit

  private given Raise[F, ScriptVerdictUnavailable] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Unavailable(_))
  private given Raise[F, UtxoStateDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.UtxoState(_))
  private given Raise[F, LedgerDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Ledger(_))
  private given Raise[F, PoolDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Pool(_))
  private given Raise[F, GovDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Gov(_))

  private def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
    request => Monad[F].unit.map(_ => evaluator.evaluate(request))

  /** BBODY on `block` from `state` and `proposals`: a defect, the refusal's failures, or the BBODY
    * state and the proposals after. The context of its transactions' rules is made here from
    * `globals`, as the reference's rules read it from theirs: the network (`liftSTS $ asks
    * networkId`, `Dijkstra/Rules/Utxo.hs:400`, cardano-ledger 9df9f98c, the Shelley genesis's),
    * with the system start and the epoch information the caller gives (`systemStart`, `epochInfo`,
    * the latter depending on the tip), and every check validated as the reference's application
    * does (`ValidateAll`; its reapplication validates none), so that no caller runs the block's
    * rules under another policy or on another network. Dijkstra's injection of Shelley's failures
    * is the era's given, not the build's ([[DijkstraUtxoContext]]).
    */
  def bbody[H](
      globals: Globals,
      env: BbodyEnv[Dijkstra],
      block: Block[H, Dijkstra],
      systemStart: BigInt,
      epochInfo: Horizon,
      state: ShelleyBbodyState[Dijkstra],
      proposals: Proposals[Dijkstra],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  )(using
      EraBlockHeader[H, Dijkstra],
      LeiosEraBlockHeader[H, Dijkstra]
  ): Either[DijkstraRuleDefect, Either[NonEmptyChain[BbodyPredFailure[Dijkstra]], After]] =
    given ScriptEvaluator[F] = lifted(evaluator)
    val netId = globals.networkId
    val context =
      DijkstraUtxoContext(netId, systemStart, epochInfo, TxEnv.Validation.All)
    Judgment
      .judge(
        DijkstraBbody.dijkstraBbodyTransition[F, H](
          env,
          block,
          context,
          verdicts,
          decoder,
          slotToPOSIXTime
        ),
        RuleT.Policy.Accumulate
      )(globals, (state, proposals))
      .map(_.map(_._1))

  /** The closure `txs` applied with `env` to `state` and `proposals`
    * ([[DijkstraClosure.applyLeiosClosure]]): a defect, the refusal's failures, or the ledger state
    * and the proposals after. Its judgment records nothing (`ValidateNone`), so a refusal does not
    * come of it; the reference's would be the block's (`SL.BlockTransitionError . fmap
    * injectFailure`, ouroboros-consensus 2416493b `Shelley/Ledger/Leios.hs:214-215`), which is the
    * type of the failures here.
    */
  def closure(
      globals: Globals,
      env: LedgerEnv[Dijkstra],
      txs: Vector[Tx[TxLevel.Top, Dijkstra]],
      context: DijkstraUtxoContext,
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  ): Either[DijkstraRuleDefect, Either[
    NonEmptyChain[BbodyPredFailure[Dijkstra]],
    (LedgerState[Dijkstra], Proposals[Dijkstra])
  ]] =
    given ScriptEvaluator[F] = lifted(evaluator)
    Judgment
      .judge(
        DijkstraClosure.applyLeiosClosure[F](env, txs, context, verdicts, decoder, slotToPOSIXTime),
        RuleT.Policy.Accumulate
      )(globals, (ShelleyBbodyState[Dijkstra](state, BlocksMade.empty), proposals))
      .map(_.map { case ((after, ps), _) => (after.ls, ps) })
