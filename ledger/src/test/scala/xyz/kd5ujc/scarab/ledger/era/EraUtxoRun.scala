// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{ProtocolParams, TestLedger, TxEnv}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTestKit, ConwayTx}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  AlonzoUtxoEvent,
  BabbageUtxo,
  ConwayUtxo,
  ConwayUtxoPredFailure,
  ConwayUtxosEvent,
  UtxoContext,
  UtxoEnv,
  UtxoPredFailure,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{Judge, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Conway, ConwayCertState, UTxOState}
import xyz.kd5ujc.scarab.plutus.{
  EvalFailure,
  EvalResult,
  ScriptEvaluator,
  ScriptFailure as UplcScriptFailure
}
import xyz.kd5ujc.scarab.uplc.cek.CekError
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError

/** Conway's UTXO and UTXOW on the era types (`ConwayUtxo.conwayUtxoTransition`,
  * `BabbageUtxow.babbageUtxowTransition`) run in one judgment, with what their judgments are
  * recorded and checked by, for the suites that hold them to the flat rules' recorded judgments
  * (`EraUtxoSuite`, `ConwayUtxoChecksSuite` and `ConwayUtxowChecksSuite` in the node).
  */
object EraUtxoRun:
  type Rec = UtxoPredFailure[Conway]
  type Events = Chain[AlonzoUtxoEvent[Conway]]
  type Defect = Either[ScriptVerdictUnavailable, UtxoStateDefect]
  type S = (UTxOState[Conway], Events)
  type F[A] = RuleT[Rec, Defect, S, A]
  import RuleT.given

  private given Stateful[F, UTxOState[Conway]] =
    summon[Stateful[F, S]].focus(Lens[S, UTxOState[Conway]](_._1)(u => s => (u, s._2)))

  private given Tell[F, Events] = new Tell[F, Events]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Events): F[Unit] = summon[Stateful[F, S]].modify((u, e) => (u, e ++ events))

  private given Raise[F, ScriptVerdictUnavailable] =
    summon[Raise[F, Defect]].contramap(Left(_))

  private given Raise[F, UtxoStateDefect] = summon[Raise[F, Defect]].contramap(Right(_))

  private def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
    request => Monad[F].unit.map(_ => evaluator.evaluate(request))

  /** The transition from `state`, every failure accumulated: a defect, the failures in the
    * reference's order, or the state after with UTXO's events.
    *
    * The reference reports the failures of the rule it applies (the root) in the reverse of their
    * order, those one level below in their order, and so on by the parity of the depth
    * (`Judgment.flatten`). As the root, as the flat UTXO is run here, UTXO's failures are reversed.
    * A submitted transaction's refusal is MEMPOOL's, which runs LEDGER, UTXOW and UTXO each as a
    * sub-rule (`ruleApplyTxValidation`, `Shelley/API/Mempool.hs:245-254`; `Conway/Rules/Mempool.hs:
    * 138`, cardano-ledger f649f975), so UTXO is three levels down, an odd depth, which reports them
    * in their order as one level down does: `asSubRule` runs it one level down. The rule reads
    * `globals`.
    */
  def run(
      env: UtxoEnv[Conway],
      stAnnTx: AlonzoStAnnTx[Conway],
      context: UtxoContext[Conway],
      state: UTxOState[Conway],
      evaluator: ScriptEvaluator[Id],
      asSubRule: Boolean = false,
      globals: xyz.kd5ujc.scarab.ledger.rules.Globals = TestLedger.globals
  ): Either[Defect, Either[NonEmptyChain[Rec], S]] =
    given ScriptEvaluator[F] = lifted(evaluator)
    given Phase2Deferral[F, Conway] = Phase2Deferral.now
    val transition = ConwayUtxo.conwayUtxoTransition[F, Conway](env, stAnnTx, context)
    Judgment
      .judge(
        if asSubRule then summon[Judge[F, Rec]].nest(transition) else transition,
        RuleT.Policy.Accumulate
      )(globals, (state, Chain.empty))
      .map(_.map(_._1))

  /** Conway's UTXO whole on `tx`, one level down (its failures in their order), from `state` and
    * the UTxO set it holds, with the parameters `pp` (as stored) and the certificate state `cert`,
    * under `ctx`: its slot, clock, network, policy, build and evaluator.
    */
  def judge(
      tx: ConwayTx,
      pp: ProtocolParams,
      cert: ConwayCertState,
      state: UTxOState[Conway],
      ctx: TxEnv = TestLedger.env
  ): Either[Defect, Either[NonEmptyChain[Rec], S]] =
    val params = PParams[Conway](
      ConwayPParams.fromStored(pp).fold(e => munit.Assertions.fail(s"parameters: $e"), identity)
    )
    val clock = ctx.clock.getOrElse(munit.Assertions.fail("the environment has no clock"))
    val utxo = conway.ConwayUTxO
      .fromStored(state.utxo.utxo.toMap)
      .fold(e => munit.Assertions.fail(s"outputs: $e"), identity)
    run(
      UtxoEnv(ctx.slot, params, cert),
      AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock.posix, params, utxo, Map.empty, tx),
      ConwayTestKit.contextOf(ctx),
      state,
      ctx.evaluator,
      asSubRule = true
    )

  private type H[A] = RuleT[Nothing, UtxoStateDefect, Events, A]

  private given Tell[H, Events] = new Tell[H, Events]:
    val functor: Functor[H] = summon[Monad[H]]
    def tell(events: Events): H[Unit] = summon[Stateful[H, Events]].modify(_ ++ events)

  /** Conway's change of the UTxO state on `tx` from `state` (Babbage's `updateUTxOState` after
    * Conway's `updateTreasuryDonation`, as `conwayUtxoTransition` runs them after UTXOS), with
    * UTXO's events, the parameters `pp` as stored.
    */
  def update(
      tx: ConwayTx,
      pp: ProtocolParams,
      cert: ConwayCertState,
      state: UTxOState[Conway]
  ): (UTxOState[Conway], Events) =
    val params = PParams[Conway](
      ConwayPParams.fromStored(pp).fold(e => munit.Assertions.fail(s"parameters: $e"), identity)
    )
    Judgment.judge(
      BabbageUtxo.updateUTxOState[H, Conway](
        params,
        cert,
        tx,
        ConwayUtxo.updateTreasuryDonation[Conway](tx, state)
      ),
      RuleT.Policy.Accumulate
    )(TestLedger.globals, Chain.empty) match
      case Right(Right((events, next))) => (next, events)
      case other                        => munit.Assertions.fail(s"the change of the state: $other")

  /** Conway's UTXOW (`BabbageUtxow.babbageUtxowTransition`) run in one judgment, as [[run]] runs
    * UTXO: its defects are UTXO's or the node's (`LedgerDefect`), its events UTXOW's.
    */
  object Utxow:
    type WRec = rules.UtxowPredFailure[Conway]
    type WEvents = Chain[rules.AlonzoUtxowEvent[Conway]]
    type WDefect = Either[Defect, xyz.kd5ujc.scarab.ledger.LedgerDefect]
    type WS = (UTxOState[Conway], WEvents)
    type W[A] = RuleT[WRec, WDefect, WS, A]

    private given Stateful[W, UTxOState[Conway]] =
      summon[Stateful[W, WS]].focus(Lens[WS, UTxOState[Conway]](_._1)(u => s => (u, s._2)))

    private given Tell[W, WEvents] = new Tell[W, WEvents]:
      val functor: Functor[W] = summon[Monad[W]]
      def tell(events: WEvents): W[Unit] =
        summon[Stateful[W, WS]].modify((u, e) => (u, e ++ events))

    private given Raise[W, ScriptVerdictUnavailable] =
      summon[Raise[W, WDefect]].contramap(e => Left(Left(e)))

    private given Raise[W, UtxoStateDefect] =
      summon[Raise[W, WDefect]].contramap(e => Left(Right(e)))

    private given Raise[W, xyz.kd5ujc.scarab.ledger.LedgerDefect] =
      summon[Raise[W, WDefect]].contramap(Right(_))

    /** The transition from `state`, every failure accumulated, `depth` levels below the root: the
      * reference reports a submitted transaction's UTXOW failures two levels below MEMPOOL's
      * (LEDGER's sub-rule), the parity at which they are reversed ([[run]]). The rule reads
      * `globals`.
      */
    def run(
        env: UtxoEnv[Conway],
        stAnnTx: AlonzoStAnnTx[Conway],
        context: UtxoContext[Conway],
        state: UTxOState[Conway],
        evaluator: ScriptEvaluator[Id],
        verdicts: xyz.kd5ujc.scarab.ledger.Verdicts,
        decoder: xyz.kd5ujc.scarab.plutus.ScriptDecoder[Id],
        depth: Int = 0,
        globals: xyz.kd5ujc.scarab.ledger.rules.Globals = TestLedger.globals
    ): Either[WDefect, Either[NonEmptyChain[WRec], WS]] =
      given ScriptEvaluator[W] = request => Monad[W].unit.map(_ => evaluator.evaluate(request))
      given Phase2Deferral[W, Conway] = Phase2Deferral.now
      val judge = summon[Judge[W, WRec]]
      val transition = rules.BabbageUtxow
        .babbageUtxowTransition[W, Conway](env, stAnnTx, context, verdicts, decoder)
      Judgment
        .judge(
          (0 until depth).foldLeft(transition)((fa, _) => judge.nest(fa)),
          RuleT.Policy.Accumulate
        )(globals, (state, Chain.empty))
        .map(_.map(_._1))

    /** The counters UTXOS's events give, through UTXOW's and UTXO's embeddings ([[counters]]). */
    def counters(valid: Boolean, events: WEvents): Option[(Long, Long, Long)] =
      EraUtxoRun.counters(
        valid,
        events.map {
          case rules.AlonzoUtxowEvent
                .WrappedShelleyEraEvent(rules.ShelleyUtxowEvent.UtxoEvent(e)) =>
            e
        }
      )

  /** A script's failure, the machine's by its error and the script's trace: the cause the machine
    * keeps beside it holds terms, which are equal only as the same object.
    */
  enum Cause:
    case Machine(error: CekError, logs: Vector[String])
    case Other(failure: EvalFailure)

  def cause(failure: EvalFailure): Cause = failure match
    case EvalFailure.Script(UplcScriptFailure.Uplc(EvaluationError.Cek(cek), logs)) =>
      Cause.Machine(cek.error, logs)
    case other => Cause.Other(other)

  /** The failures of the scripts a UTXO failure reports, each with its cause and its script with
    * its context: those of a tag mismatch whose scripts failed unexpectedly.
    */
  def scriptFailures(f: ConwayUtxoPredFailure[Conway]): List[rules.FailureDescription] = f match
    case ConwayUtxoPredFailure.UtxosFailure(
          rules.ConwayUtxosPredFailure
            .ValidationTagMismatch(_, rules.TagMismatchDescription.FailedUnexpectedly(fs))
        ) =>
      fs.toList
    case _ => Nil

  /** Whether the cause of each script failure of `failures` is the one `evaluator` gives its
    * request. A refusal's bytes hold the request whole, but not always the cause: the reference's
    * text of a script that ended at an explicit `error` leaves its trace out
    * (`PlutusFailureCbor.description`), so the cause is held by its request this way.
    */
  def causesHeld(
      failures: Iterable[ConwayUtxoPredFailure[Conway]],
      evaluator: ScriptEvaluator[Id]
  ): Boolean =
    failures.flatMap(scriptFailures).forall {
      case rules.FailureDescription.PlutusFailure(failure, pwc) =>
        evaluator.evaluate(PlutusWithContext.evalRequest(pwc)) match
          case EvalResult.Failure(reason, _, _) => cause(reason) == cause(failure)
          case EvalResult.Success(_, _)         => false
    }

  /** [[causesHeld]] of UTXOW's failures: those UTXO reports through it. */
  def utxowCausesHeld(
      failures: Iterable[rules.ConwayUtxowPredFailure[Conway]],
      evaluator: ScriptEvaluator[Id]
  ): Boolean =
    causesHeld(
      failures.collect { case rules.ConwayUtxowPredFailure.UtxoFailure(f) => f },
      evaluator
    )

  /** The counters the flat UTXOS kept (`PlutusStats`), as UTXO's events give them: one passing
    * transaction if a transaction flagged valid told its scripts passed, one failing if any told
    * they failed, and every script told; none where an event of a kind is told twice, which UTXOS
    * never does.
    */
  def counters(valid: Boolean, events: Events): Option[(Long, Long, Long)] =
    val utxos = events.toList.collect { case AlonzoUtxoEvent.UtxosEvent(e) => e }
    val passed = utxos.collect { case ConwayUtxosEvent.SuccessfulPlutusScriptsEvent(ps) =>
      ps.size
    }
    val failed = utxos.collect { case ConwayUtxosEvent.FailedPlutusScriptsEvent(fs) => fs.size }
    Option.when(passed.size <= 1 && failed.size <= 1)(
      (
        if valid && passed.nonEmpty then 1L else 0L,
        if failed.nonEmpty then 1L else 0L,
        (passed ++ failed).sum.toLong
      )
    )

  /** Conway's injections of the UTXOW failures of Shelley, Alonzo and Babbage. */
  val shelleyW: rules.ShelleyUtxowPredFailure[Conway] => rules.ConwayUtxowPredFailure[Conway] =
    rules.InjectUtxowFailure.conwayShelley.injectFailure
  val alonzoW: rules.AlonzoUtxowPredFailure[Conway] => rules.ConwayUtxowPredFailure[Conway] =
    rules.InjectUtxowFailure.conwayAlonzo.injectFailure
  val babbageW: rules.BabbageUtxowPredFailure[Conway] => rules.ConwayUtxowPredFailure[Conway] =
    rules.InjectUtxowFailure.conwayBabbage.injectFailure
