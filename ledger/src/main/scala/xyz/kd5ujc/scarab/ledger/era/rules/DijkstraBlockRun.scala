// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain, NonEmptyList}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{
  Horizon,
  LedgerDefect,
  PlutusStats,
  SystemStart,
  TranslationError,
  TxEnv,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.{
  Block,
  EraBlockBody,
  EraBlockHeader,
  LeiosEraBlockHeader,
  Phase2Deferral,
  PlutusWithContext,
  Proposals,
  ScriptVerdictUnavailable,
  StAnnTx,
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
  /** A script the evaluator gave no verdict for, not named: the mempool's, and a block's the run
    * could not name, which the script the rules ran never is ([[DijkstraBlockRun.bbody]] names one
    * as a [[Ledger]] defect).
    */
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
  * proposals are their own state beside the ledger's, as LEDGER keeps them ([[ProposalsRecord]]).
  * Of the events, only what the node's phase-2 counters read is kept ([[ScriptsTold]],
  * [[DijkstraPhase2.counted]]), and a script the evaluator gives no verdict for is named by its
  * batch, its level and its redeemer ([[DijkstraPhase2.named]]), as Conway's run names one
  * ([[ConwayBlockRun]]): both Scarab's own, which change no rule's result.
  */
object DijkstraBlockRun:
  /** The annotated transaction whose scripts its UTXOS evaluates now, as the run is told of it
    * ([[Phase2Deferral.evalScripts]]), and how many of its scripts ran so far: UTXOS runs them in
    * the order it collected them (`evalPlutusScripts`, `Alonzo/Plutus/Evaluate.hs:184-203`,
    * cardano-ledger 9df9f98c), so the `ran` th is the one running, which two scripts with the same
    * context tell apart.
    */
  private final case class Running(stAnnTx: StAnnTx[TxLevel.Top, Dijkstra], ran: Int)

  /** The state of a run: BBODY's, the proposals, what of its events the counters read
    * ([[ScriptsTold]]), in their order, and the transaction whose scripts run, between its UTXOS's
    * evaluation's start and end ([[Running]]).
    */
  private final case class S(
      bbody: ShelleyBbodyState[Dijkstra],
      proposals: Proposals[Dijkstra],
      told: Chain[ScriptsTold],
      running: Option[Running]
  )

  private type F[A] = RuleT[BbodyPredFailure[Dijkstra], DijkstraRuleDefect, S, A]
  import RuleT.given

  private given Stateful[F, ShelleyBbodyState[Dijkstra]] =
    summon[Stateful[F, S]].focus(
      Lens[S, ShelleyBbodyState[Dijkstra]](_.bbody)(b => _.copy(bbody = b))
    )

  private given Stateful[F, Proposals[Dijkstra]] =
    summon[Stateful[F, S]].focus(
      Lens[S, Proposals[Dijkstra]](_.proposals)(p => _.copy(proposals = p))
    )

  private given Stateful[F, LedgerState[Dijkstra]] =
    summon[Stateful[F, ShelleyBbodyState[Dijkstra]]].focus(ShelleyBbodyState.ls[Dijkstra])

  // the events of a block applied by the node are not kept (`ComputeLedgerEvents` off) but for
  // what the node's phase-2 counters read of them
  private given Tell[F, Chain[BbodyEvent[Dijkstra]]] = new Tell[F, Chain[BbodyEvent[Dijkstra]]]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Chain[BbodyEvent[Dijkstra]]): F[Unit] =
      summon[Stateful[F, S]].modify(s =>
        s.copy(told = s.told ++ events.flatMap(e => Chain.fromOption(DijkstraPhase2.told(e))))
      )

  private given Raise[F, UtxoStateDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.UtxoState(_))
  private given Raise[F, LedgerDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Ledger(_))
  private given Raise[F, PoolDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Pool(_))
  private given Raise[F, GovDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Gov(_))

  /** `evaluator` in the run, each script it runs counted for the transaction whose scripts run
    * ([[Running]]).
    */
  private def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
    request =>
      summon[Stateful[F, S]].modify(s =>
        s.copy(running = s.running.map(r => r.copy(ran = r.ran + 1)))
      ) *> Monad[F].unit.map(_ => evaluator.evaluate(request))

  /** The scripts of a batch evaluated where UTXOS collects them, as the reference evaluates them,
    * the run told of the annotated transaction they run for ([[Running]]) until they ran.
    */
  private val running: Phase2Deferral[F, Dijkstra] = new Phase2Deferral[F, Dijkstra]:
    private def set(r: Option[Running]): F[Unit] =
      summon[Stateful[F, S]].modify(_.copy(running = r))
    def evalScripts(
        stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
        scripts: NonEmptyList[PlutusWithContext]
    )(now: F[Unit]): F[Unit] = set(Some(Running(stAnnTx, 0))) *> now <* set(None)
    def uncollected(tx: Tx[TxLevel.Top, Dijkstra]): F[Unit] = Monad[F].unit

  /** What a block the rules admitted comes to: the BBODY state and the proposals after it, and the
    * node's phase-2 counters of its scripts, from zero ([[DijkstraPhase2.counted]]).
    */
  final case class Applied(
      state: ShelleyBbodyState[Dijkstra],
      proposals: Proposals[Dijkstra],
      plutus: PlutusStats
  )

  /** BBODY on `block` from `state` and `proposals`: a defect, the refusal's failures, or the BBODY
    * state and the proposals after. The context of its transactions' rules is made here from
    * `globals`, as the reference's rules read it from theirs: the network (`liftSTS $ asks
    * networkId`, `Dijkstra/Rules/Utxo.hs:400`, cardano-ledger 9df9f98c, the Shelley genesis's),
    * with the system start and the epoch information the caller gives (`systemStart`, `epochInfo`,
    * the latter depending on the tip), and every check validated as the reference's application
    * does (`ValidateAll`; its reapplication validates none), so that no caller runs the block's
    * rules under another policy or on another network. Dijkstra's injection of Shelley's failures
    * is the era's given, not the build's ([[DijkstraUtxoContext]]). A script the evaluator gives no
    * verdict for is named by its batch, its level and its redeemer in the batch UTXOS ran
    * ([[DijkstraPhase2.named]]), raised as the ledger's defect ([[DijkstraRuleDefect.Ledger]] of
    * `LedgerFailure.Unavailable`), so that the node withholds the block as it withholds the other
    * eras' (issue 567); one the run could not name, which the script the rules ran never is, is
    * raised as it was ([[DijkstraRuleDefect.Unavailable]]).
    */
  def bbody[H](
      globals: Globals,
      env: BbodyEnv[Dijkstra],
      block: Block[H, Dijkstra],
      systemStart: SystemStart,
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
  ): Either[DijkstraRuleDefect, Either[NonEmptyChain[BbodyPredFailure[Dijkstra]], Applied]] =
    given ScriptEvaluator[F] = lifted(evaluator)
    given Phase2Deferral[F, Dijkstra] = running
    // a script with no verdict, named in the batch whose scripts run, at its place among them: it
    // ran, and was counted, so `ran` less one ran before it
    given Raise[F, ScriptVerdictUnavailable] = new Raise[F, ScriptVerdictUnavailable]:
      val functor: Functor[F] = summon[Monad[F]]
      def raise[E2 <: ScriptVerdictUnavailable, A](cause: E2): F[A] =
        summon[Stateful[F, S]].get.flatMap(s =>
          summon[Raise[F, DijkstraRuleDefect]].raise[DijkstraRuleDefect, A](
            s.running
              .flatMap(r => DijkstraPhase2.named(r.stAnnTx, env.bbodyPp, cause, r.ran - 1))
              .fold(DijkstraRuleDefect.Unavailable(cause))(DijkstraRuleDefect.Ledger(_))
          )
        )
    val txs = EraBlockBody.dijkstra.txSeqBlockBody(block.blockBody)
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
      )(globals, S(state, proposals, Chain.empty, None))
      .map(_.map { case (after, _) =>
        Applied(after.bbody, after.proposals, DijkstraPhase2.counted(txs, after.told))
      })

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
    // no script is evaluated (`ValidateNone`): one raised is not named
    given Raise[F, ScriptVerdictUnavailable] =
      summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Unavailable(_))
    Judgment
      .judge(
        DijkstraClosure.applyLeiosClosure[F](env, txs, context, verdicts, decoder, slotToPOSIXTime),
        RuleT.Policy.Accumulate
      )(
        globals,
        S(ShelleyBbodyState[Dijkstra](state, BlocksMade.empty), proposals, Chain.empty, None)
      )
      .map(_.map { case (after, _) => (after.bbody.ls, after.proposals) })
