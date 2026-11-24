// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.{Chain, EitherT, NonEmptyChain, NonEmptyList, Writer}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Horizon,
  LedgerDefect,
  Phase2,
  PlutusStats,
  SystemStart,
  TranslationError,
  TxEnv,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.{
  Block,
  BothLevels,
  EraBlockBody,
  EraBlockHeader,
  EraTx,
  IsPhase2Valid,
  LeiosEraBlockHeader,
  Phase2Deferral,
  PlutusEvaluation,
  PlutusWithContext,
  Proposals,
  ScriptResult,
  ScriptVerdictUnavailable,
  StAnnTx,
  Tx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judgment, RuleT, TxDependencies, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{BlocksMade, Dijkstra, LedgerState}
import xyz.kd5ujc.scarab.plutus.{EvalRequest, EvalResult, ScriptDecoder, ScriptEvaluator}

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
  *
  * The run is serial ([[bbody]]), the executable reference, or a body program ([[program]]) whose
  * scripts the node can evaluate in bounded parallel workers after an ordered preparation (ADR
  * 0018), modelled on Conway's ([[ConwayBlockRun.program]]): the same rules run once, in order,
  * deferring each batch's scripts, its top level's and its sub-transactions', where its top level's
  * UTXOS collects them ([[Phase2Deferral]]), then the plan lands their verdicts in order
  * ([[Plan]]), and its result is the serial run's. The node applies the Leios build's Dijkstra
  * blocks with it (`MirrorDijkstra`, #85).
  */
object DijkstraBlockRun:
  /** The annotated transaction whose scripts its UTXOS evaluates now, as the run is told of it
    * ([[Phase2Deferral.evalScripts]]), and how many of its scripts ran so far: UTXOS runs them in
    * the order it collected them (`evalPlutusScripts`, `Alonzo/Plutus/Evaluate.hs:184-203`,
    * cardano-ledger 9df9f98c), so the `ran` th is the one running, which two scripts with the same
    * context tell apart.
    */
  private final case class Running(stAnnTx: StAnnTx[TxLevel.Top, Dijkstra], ran: Int)

  /** What of a run's events its result reads, in their order: those the counters read
    * ([[ScriptsTold]]), and, in a block the node prepares, the place of each batch whose scripts it
    * deferred, where the events of its scripts land once the plan has their verdict.
    */
  private enum Told:
    case Event(told: ScriptsTold)
    case At(job: Deferred)

  /** The state of a run: BBODY's, the proposals, what of its events its result reads ([[Told]]), in
    * their order, the transaction whose scripts run, between its UTXOS's evaluation's start and end
    * ([[Running]]), and the results it is answered with (`answers`, [[answered]]).
    */
  private final case class S(
      bbody: ShelleyBbodyState[Dijkstra],
      proposals: Proposals[Dijkstra],
      told: Chain[Told],
      running: Option[Running],
      answers: List[(EvalRequest, EvalResult)]
  )

  /** How a run evaluates its scripts: where UTXOS collects them, each answered in turn by `answers`
    * where its request is the one answered (a prepared block's results, which the evaluator, a
    * function of the request, gives again) and by the evaluator otherwise; or, a block prepared,
    * deferred into the plan's recorder ([[Deferred]]).
    */
  private enum Mode:
    case Evaluating(answers: List[(EvalRequest, EvalResult)])
    case Preparing(recorder: Phase2.Recorder[Deferred])

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
        s.copy(told =
          s.told ++ events.flatMap(e => Chain.fromOption(DijkstraPhase2.told(e)).map(Told.Event(_)))
        )
      )

  private given Raise[F, UtxoStateDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.UtxoState(_))
  private given Raise[F, LedgerDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Ledger(_))
  private given Raise[F, PoolDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Pool(_))
  private given Raise[F, GovDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Gov(_))

  /** The result of the script whose request is `request`: the next of the run's answers if it
    * answers that request, the evaluator's otherwise ([[Mode.Evaluating]]). A result evaluated
    * ahead is so used only for the very request it was evaluated for, the script's context and all
    * it read with it; any other is evaluated here, in order.
    */
  private def answered(evaluator: ScriptEvaluator[Id])(request: EvalRequest): F[EvalResult] =
    val state = summon[Stateful[F, S]]
    state.get.flatMap { s =>
      s.answers match
        case (asked, result) :: rest if asked == request =>
          state.set(s.copy(answers = rest)).as(result)
        case _ => Monad[F].unit.map(_ => evaluator.evaluate(request))
    }

  /** `evaluator` in the run, each script it runs counted for the transaction whose scripts run
    * ([[Running]]) and answered as [[answered]] answers it.
    */
  private def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
    request =>
      summon[Stateful[F, S]].modify(s =>
        s.copy(running = s.running.map(r => r.copy(ran = r.ran + 1)))
      ) *> answered(evaluator)(request)

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

  /** Where a block's preparation defers the scripts of a batch of the block, by its place among the
    * block's batches `places` ([[DijkstraPhase2.places]]): recorded into `recorder` and at their
    * place among the run's events, none run, so that the rules go on as its top level's flag
    * announces, which UTXOS checks the batch's scripts against. A batch the block does not hold has
    * them run there, as [[running]] runs them.
    */
  private def deferring(
      recorder: Phase2.Recorder[Deferred],
      places: Map[Bytes, Int]
  ): Phase2Deferral[F, Dijkstra] = new Phase2Deferral[F, Dijkstra]:
    def evalScripts(
        stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
        scripts: NonEmptyList[PlutusWithContext]
    )(now: F[Unit]): F[Unit] =
      places.get(DijkstraPhase2.txId(stAnnTx)).fold(running.evalScripts(stAnnTx, scripts)(now)) {
        index =>
          val t = EraTx.dijkstra
          val job = Deferred(
            index,
            t.isValid(t.txStAnnTx[TxLevel.Top](stAnnTx)(using BothLevels.Top)),
            scripts
          )
          Monad[F].unit.map(_ => recorder.append(job)) *>
            summon[Stateful[F, S]].modify(s => s.copy(told = s.told :+ Told.At(job)))
      }
    def uncollected(tx: Tx[TxLevel.Top, Dijkstra]): F[Unit] = Monad[F].unit

  /** What a block the rules admitted comes to: the BBODY state and the proposals after it, and the
    * node's phase-2 counters of its scripts, from zero ([[DijkstraPhase2.counted]]).
    */
  final case class Applied(
      state: ShelleyBbodyState[Dijkstra],
      proposals: Proposals[Dijkstra],
      plutus: PlutusStats
  )

  /** What a run on a block comes to: a defect, the refusal's failures, or the block admitted. */
  type Result =
    Either[DijkstraRuleDefect, Either[NonEmptyChain[BbodyPredFailure[Dijkstra]], Applied]]

  /** A run before its counters: the BBODY state and the proposals after an admitted block, with its
    * events.
    */
  private type Ran = Either[DijkstraRuleDefect, Either[
    NonEmptyChain[BbodyPredFailure[Dijkstra]],
    (ShelleyBbodyState[Dijkstra], Proposals[Dijkstra], Chain[Told])
  ]]

  /** The scripts of a batch a prepared block deferred ([[Mode.Preparing]]): its place among the
    * block's batches, its top level's flag, and its scripts in the order its top level's UTXOS runs
    * them, the top level's first, then each sub-transaction's (`plutusScriptsWithContextStAnnTx`,
    * `Dijkstra/UTxO.hs:258-276`, cardano-ledger 9df9f98c), each with the context the rules built
    * from the set before the batch. Identity is intentional, as Conway's
    * ([[ConwayBlockRun.Deferred]]): completed values cannot be reused for another block.
    */
  final class Deferred private[DijkstraBlockRun] (
      val index: Int,
      private[DijkstraBlockRun] val flag: IsPhase2Valid,
      private[DijkstraBlockRun] val scripts: NonEmptyList[PlutusWithContext]
  ):
    private[DijkstraBlockRun] def requests: List[EvalRequest] =
      scripts.toList.map(PlutusWithContext.evalRequest)

  /** A deferred batch's scripts run, as `evalPlutusScripts` runs them: each one's result, in order,
    * to the first the evaluator gave no verdict for, and what they come to.
    */
  private[DijkstraBlockRun] final case class Evaluated(
      results: Vector[EvalResult],
      verdict: Either[ScriptVerdictUnavailable, ScriptResult]
  )

  /** The scripts of `job` run by `evaluator` with the reference's `evalPlutusScripts`, which stops
    * at the first with no verdict, every result kept.
    */
  private def evaluated(
      job: Deferred,
      evaluator: ScriptEvaluator[Id]
  ): Either[Phase2.Defect, Evaluated] =
    type W[A] = Writer[Vector[EvalResult], A]
    type G[A] = EitherT[W, ScriptVerdictUnavailable, A]
    given ScriptEvaluator[G] = request =>
      val result = evaluator.evaluate(request)
      EitherT.liftF[W, ScriptVerdictUnavailable, EvalResult](Writer(Vector(result), result))
    given Raise[G, ScriptVerdictUnavailable] = new Raise[G, ScriptVerdictUnavailable]:
      val functor: Functor[G] = summon[Monad[G]]
      def raise[E2 <: ScriptVerdictUnavailable, A](cause: E2): G[A] =
        EitherT.leftT[W, A](cause)
    Either
      .catchNonFatal {
        val (results, verdict) =
          PlutusEvaluation.evalPlutusScripts[G](job.scripts.toList).value.run
        Evaluated(results, verdict)
      }
      .leftMap(Phase2.Defect(_))

  /** The verdict of `job` 's scripts if it is the one its top level's flag announces: they pass,
    * flagged valid, or fail, flagged invalid (`expectScriptsToPass` and
    * `babbageEvalScriptsTxInvalid`, `Babbage/Rules/Utxos.hs`, which Dijkstra's UTXOS runs).
    */
  private def agreed(job: Deferred, evaluated: Evaluated): Option[ScriptResult] =
    (job.flag, evaluated.verdict) match
      case (IsPhase2Valid.Phase2Valid, Right(passes @ ScriptResult.Passes(_)))    => Some(passes)
      case (IsPhase2Valid.Phase2Invalid, Right(fails @ ScriptResult.Fails(_, _))) => Some(fails)
      case (_, Right(ScriptResult.Passes(_)) | Right(ScriptResult.Fails(_, _)) | Left(_)) => None

  /** What UTXOS tells of scripts whose verdict is the one their flag announces, as the counters
    * read it ([[DijkstraPhase2.told]]): those that passed, if any did, then, failing, those that
    * failed (`expectScriptsToPass` and `babbageEvalScriptsTxInvalid`).
    */
  private def toldOf(verdict: ScriptResult): Chain[ScriptsTold] =
    def passed(ps: List[PlutusWithContext]): Chain[ScriptsTold] =
      Chain.fromOption(Option.when(ps.nonEmpty)(ScriptsTold.Passed(ps.size)))
    verdict match
      case ScriptResult.Passes(ps)    => passed(ps)
      case ScriptResult.Fails(ps, fs) => passed(ps) :+ ScriptsTold.Failed(fs.size)

  /** A run's result, its counters from its events, each deferred batch's scripts told where they
    * were deferred, by the verdict `landed` gives them.
    */
  private def settled(
      txs: Vector[Tx[TxLevel.Top, Dijkstra]],
      ran: Ran,
      landed: Map[Deferred, ScriptResult]
  ): Result =
    ran.map(_.map { (state, proposals, told) =>
      Applied(
        state,
        proposals,
        DijkstraPhase2.counted(
          txs,
          told.flatMap {
            case Told.Event(event) => Chain.one(event)
            case Told.At(job)      => landed.get(job).fold(Chain.empty[ScriptsTold])(toldOf)
          }
        )
      )
    })

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
  ): Result =
    program(
      globals,
      env,
      block,
      systemStart,
      epochInfo,
      state,
      proposals,
      evaluator,
      verdicts,
      decoder,
      slotToPOSIXTime
    ).serial

  /** [[bbody]] as a body program ([[Body]]): run serially, or prepared for the node's bounded
    * parallel evaluation of its scripts (ADR 0018), each to the same result.
    */
  def program[H](
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
  ): Body =
    val txs = EraBlockBody.dijkstra.txSeqBlockBody(block.blockBody)
    val places = DijkstraPhase2.places(txs)
    def run(mode: Mode): Ran =
      given ScriptEvaluator[F] = lifted(evaluator)
      given Phase2Deferral[F, Dijkstra] = mode match
        case Mode.Evaluating(_)       => running
        case Mode.Preparing(recorder) => deferring(recorder, places)
      // a script with no verdict, named in the batch whose scripts run, at its place among them:
      // it ran, and was counted, so `ran` less one ran before it
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
      val context =
        DijkstraUtxoContext(globals.networkId, systemStart, epochInfo, TxEnv.Validation.All)
      val answers = mode match
        case Mode.Evaluating(answers) => answers
        case Mode.Preparing(_)        => Nil
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
        )(globals, S(state, proposals, Chain.empty, None, answers))
        .map(_.map { case (after, _) => (after.bbody, after.proposals, after.told) })
    Body(txs, evaluator, run)

  /** Dijkstra's run on a block as a body program (`Phase2.Program`): [[serial]] is the run with
    * every script evaluated where UTXOS collects it; [[prepare]] runs the same rules once, in
    * order, with the scripts of each batch deferred, and its [[Plan]] lands their verdicts. A batch
    * whose sub-transaction alone holds a redeemer has scripts as much as one whose top level does
    * ([[DijkstraPhase2.redeeming]]).
    */
  final class Body private[DijkstraBlockRun] (
      txs: Vector[Tx[TxLevel.Top, Dijkstra]],
      evaluator: ScriptEvaluator[Id],
      run: Mode => Ran
  ) extends Phase2.Program[Result]:
    def serial: Result = settled(txs, run(Mode.Evaluating(Nil)), Map.empty)
    def hasScripts: Boolean = txs.exists(DijkstraPhase2.redeeming)
    def worthwhile: Boolean = txs.count(DijkstraPhase2.redeeming) > 1
    def prepare: Plan =
      val (tentative, recorded) =
        Phase2.captured[Deferred, Ran](recorder => run(Mode.Preparing(recorder)))
      Plan(
        tentative,
        recorded,
        txs,
        evaluator,
        answers => settled(txs, run(Mode.Evaluating(answers)), Map.empty)
      )

  /** Where the landing of a plan's deferred scripts stops: at a raw defect, or at the first batch
    * whose scripts' verdict is not the one its flag announces, or gives none.
    */
  private enum Stop:
    case Defect(defect: Phase2.Defect)
    case Disagreed(landed: Vector[(Deferred, Evaluated)])

  /** A prepared block: its deferred batches' scripts ([[jobs]]) in dependency batches
    * ([[DijkstraPhase2.footprint]]), and the only route to its result, which lands each job's
    * verdict in order, as Conway's plan does ([[ConwayBlockRun.Plan]]):
    *   - while every verdict is the one its flag announces, the preparation went as the serial run
    *     goes, every batch's scripts given the contexts the serial run gives them, so its result is
    *     the serial run's: its counters from each job's verdict told where it was deferred, its
    *     refusal, or the defect it ended in;
    *   - a raw defect a job's scripts ended in is the serial run's, which reaches them: raised as
    *     it is, never evaluated again;
    *   - at the first verdict that is not the one its flag announces, or a script with no verdict,
    *     the serial run's result differs from the preparation's (the failure recorded, every later
    *     script left to `whenFailureFree`, the script named in the batch of the raise): the serial
    *     run itself is the result, each script answered by the jobs' results to that one, in order,
    *     where its request is the very one answered, and evaluated otherwise.
    *
    * The dependency batches order the evaluation only: a verdict reaches the result only through
    * the ordered landing above, whatever batch evaluated it. Missing or foreign completed values
    * are evaluated directly, never treated as a success.
    */
  final class Plan private[DijkstraBlockRun] (
      tentative: Either[Phase2.Defect, Ran],
      recorded: Vector[Deferred],
      txs: Vector[Tx[TxLevel.Top, Dijkstra]],
      evaluator: ScriptEvaluator[Id],
      replay: List[(EvalRequest, EvalResult)] => Result
  ) extends Phase2.Plan[Result]:
    type Job = Deferred

    /** The deferred batches' scripts, in the order the preparation deferred them. */
    val jobs: Vector[Deferred] = recorded

    /** A worker's value: a deferred batch's scripts run by this plan's evaluator, or the raw defect
      * that ended them. It is this plan's own type: a value another plan executed, with its own
      * evaluator, does not type-check here.
      */
    final class Completed private[Plan] (
        private[Plan] val job: Deferred,
        private[Plan] val outcome: Either[Phase2.Defect, Evaluated]
    )

    def batches: Vector[Vector[Deferred]] =
      TxDependencies.scheduled(txs.map(DijkstraPhase2.footprint), jobs)(_.index)
    def execute(job: Deferred): Completed = Completed(job, evaluated(job, evaluator))

    def resolve(completed: Vector[Completed]): Either[Phase2.Defect, Result] =
      val values = completed.iterator.map(c => c.job -> c.outcome).toMap
      type Landing[A] = Either[Stop, A]
      jobs
        .foldLeftM[Landing, Vector[(Deferred, Evaluated, ScriptResult)]](Vector.empty) {
          (landed, job) =>
            values.getOrElse(job, evaluated(job, evaluator)) match
              case Left(defect) => Left(Stop.Defect(defect))
              case Right(e) =>
                agreed(job, e)
                  .toRight(Stop.Disagreed(landed.map((j, e, _) => j -> e) :+ (job -> e)))
                  .map(v => landed :+ (job, e, v))
        } match
        case Left(Stop.Defect(defect)) => Left(defect)
        case Left(Stop.Disagreed(landed)) =>
          Either
            .catchNonFatal(replay(landed.toList.flatMap((job, e) => job.requests.zip(e.results))))
            .leftMap(Phase2.Defect(_))
        case Right(landed) =>
          tentative.map(settled(txs, _, landed.iterator.map((j, _, v) => j -> v).toMap))

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
        S(ShelleyBbodyState[Dijkstra](state, BlocksMade.empty), proposals, Chain.empty, None, Nil)
      )
      .map(_.map { case (after, _) => (after.bbody.ls, after.proposals) })
