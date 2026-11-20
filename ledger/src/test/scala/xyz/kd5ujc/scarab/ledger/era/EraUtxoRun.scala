// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{
  CollectError as FlatCollectError,
  PredicateFailure,
  TestLedger,
  UtxoFailure,
  UtxosFailure,
  UtxowFailure
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  AlonzoUtxoEvent,
  ConwayUtxo,
  ConwayUtxoPredFailure,
  ConwayUtxosEvent,
  UtxoContext,
  UtxoEnv,
  UtxoPredFailure,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{Judge, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Conway, UTxOState}
import xyz.kd5ujc.scarab.plutus.{
  EvalFailure,
  EvalRequest,
  ScriptEvaluator,
  ScriptFailure as UplcScriptFailure
}
import xyz.kd5ujc.scarab.uplc.cek.CekError
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError

/** Conway's UTXO on the era types (`ConwayUtxo.conwayUtxoTransition`) run in one judgment, and the
  * form its failures and the flat UTXO's are compared in, for the suites that compare the two
  * (`EraUtxoSuite`, and `ConwayUtxoChecksSuite` in the node).
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
    * in their order as one level down does: `asSubRule` runs it one level down.
    */
  def run(
      env: UtxoEnv[Conway],
      stAnnTx: AlonzoStAnnTx[Conway],
      context: UtxoContext[Conway],
      state: UTxOState[Conway],
      evaluator: ScriptEvaluator[Id],
      asSubRule: Boolean = false
  ): Either[Defect, Either[NonEmptyChain[Rec], S]] =
    given ScriptEvaluator[F] = lifted(evaluator)
    val transition = ConwayUtxo.conwayUtxoTransition[F, Conway](env, stAnnTx, context)
    Judgment
      .judge(
        if asSubRule then summon[Judge[F, Rec]].nest(transition) else transition,
        RuleT.Policy.Accumulate
      )(TestLedger.globals, (state, Chain.empty))
      .map(_.map(_._1))

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

  /** A collection's failure by its kind, and the language a missing cost model is of: their
    * contents are `EraPlutusContextSuite` 's.
    */
  enum Collect:
    case NoRedeemer, NoWitness, BadTranslation
    case NoCostModel(language: xyz.kd5ujc.scarab.plutus.PlutusLanguage)

  /** A failure as the comparison sees it: UTXO's own in the node's flat form, UTXOS's by kind, and
    * a script's failure by its cause and request.
    */
  enum Failure:
    case Utxo(failure: PredicateFailure)
    case CollectErrors(kinds: List[Collect])
    case PassedUnexpectedly(flag: Boolean)
    case FailedUnexpectedly(flag: Boolean, scripts: List[(Cause, EvalRequest)])

  /** A flat failure in the comparison's form; none for a script's failure without its request,
    * which the flat rule always gives.
    */
  def form(f: PredicateFailure): Option[Failure] = f match
    case PredicateFailure.Utxow(UtxowFailure.Utxo(UtxoFailure.Utxos(failure))) =>
      failure match
        case UtxosFailure.CollectErrors(es) =>
          Some(Failure.CollectErrors(es.toVector.toList.map {
            case FlatCollectError.NoRedeemer(_)     => Collect.NoRedeemer
            case FlatCollectError.NoWitness(_)      => Collect.NoWitness
            case FlatCollectError.NoCostModel(l)    => Collect.NoCostModel(l)
            case FlatCollectError.BadTranslation(_) => Collect.BadTranslation
          }))
        case UtxosFailure.ValidationTagMismatch(flag, None) =>
          Some(Failure.PassedUnexpectedly(flag))
        case UtxosFailure.ValidationTagMismatch(flag, Some(fs)) =>
          fs.toList
            .traverse(f => f.request.map(cause(f.cause) -> _))
            .map(Failure.FailedUnexpectedly(flag, _))
    case other => Some(Failure.Utxo(other))

  /** An era failure in the comparison's form, through the node's flat form where it has one. */
  def eraForm(f: ConwayUtxoPredFailure[Conway]): Option[Failure] =
    ConwayUtxoPredFailure
      .toStored(f)
      .fold(
        es =>
          Some(Failure.CollectErrors(es.toList.map {
            case CollectError.NoRedeemer(_)     => Collect.NoRedeemer
            case CollectError.NoWitness(_)      => Collect.NoWitness
            case CollectError.NoCostModel(l)    => Collect.NoCostModel(l)
            case CollectError.BadTranslation(_) => Collect.BadTranslation
          })),
        form
      )

  /** The counters the flat UTXOS keeps (`PlutusStats`), as UTXO's events give them: one passing
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
