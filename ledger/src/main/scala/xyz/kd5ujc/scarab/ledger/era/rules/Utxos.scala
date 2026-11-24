// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Applicative, Monad}
import cats.data.{Chain, NonEmptyList}
import cats.mtl.{Raise, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.TxEnv
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTx,
  AlonzoEraUTxO,
  CollectError,
  IsPhase2Valid,
  Phase2Deferral,
  PlutusEvaluation,
  PlutusWithContext,
  ScriptFailure,
  ScriptResult,
  ScriptVerdictUnavailable,
  StAnnTx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.rules.{Judge, Sts}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Dijkstra, EraTag}
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ScriptEvaluator}

// UTXOS as the reference writes it (cardano-ledger f649f975, the `Rules/Utxos.hs` of Alonzo, Babbage
// and Conway): the evaluation of a transaction's Plutus scripts, whose verdict must be the one its
// `isValid` flag announces. Each object is the module of the era that defines its functions, each
// function has the reference's name and asks for the reference's classes, and the failures and events
// are the reference's, each era's own type with its injections. Conway's UTXOS has no environment and
// no state, so its transition is a function of the annotated transaction, the validation policy and
// the capabilities it uses: the judgment it records into, the events it tells and the evaluator.

/** `data FailureDescription` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxos.hs:259-261`,
  * cardano-ledger f649f975). The reference's `PlutusFailure` holds the text `ScriptFailure`
  * explains the failure with and the script with its context serialised in Base64; here it holds
  * the evaluator's failure and the script with its context as they are, which those two are written
  * from.
  */
enum FailureDescription:
  case PlutusFailure(cause: EvalFailure, plutus: PlutusWithContext)

object FailureDescription:
  /** `scriptFailureToFailureDescription` (`Alonzo/Rules/Utxos.hs:288-290`, cardano-ledger
    * f649f975).
    */
  def scriptFailureToFailureDescription(sf: ScriptFailure): FailureDescription =
    PlutusFailure(sf.scriptFailureCause, sf.scriptFailurePlutus)

/** `data TagMismatchDescription` (`Alonzo/Rules/Utxos.hs:292-295`, cardano-ledger f649f975): the
  * scripts passed though the flag says they fail, or they failed, with each failure, though it says
  * they pass.
  */
enum TagMismatchDescription:
  case PassedUnexpectedly
  case FailedUnexpectedly(failures: NonEmptyList[FailureDescription])

/** `EraRuleFailure "PPUP" era` (`Core/Era.hs`, cardano-ledger f649f975), of the eras whose UTXOS is
  * typed: Conway has no PPUP, its failure is `VoidEraRule` (`Conway/Era.hs`), no value, and neither
  * has Dijkstra (`Dijkstra/Era.hs:86-90`, cardano-ledger 9df9f98c). The eras before Conway, whose
  * UTXOS runs PPUP, add their case with their PPUP.
  */
type PpupPredFailure[E <: EraTag] = E match
  case Conway   => Nothing
  case Dijkstra => Nothing

/** `EraRuleEvent "PPUP" era`, as [[PpupPredFailure]]: Conway's and Dijkstra's are `VoidEraRule`. */
type PpupEvent[E <: EraTag] = E match
  case Conway   => Nothing
  case Dijkstra => Nothing

/** `data AlonzoUtxosPredFailure era` (`Alonzo/Rules/Utxos.hs:323-336`, cardano-ledger f649f975):
  * the failures Alonzo's and Babbage's UTXOS functions report, which an era injects into its own
  * ([[InjectAlonzoUtxos]]).
  */
enum AlonzoUtxosPredFailure[E <: EraTag]:
  /** The flag `isValid` (the transaction's), and how the scripts disagreed with it. */
  case ValidationTagMismatch(isValid: IsPhase2Valid, description: TagMismatchDescription)

  /** The scripts could not be collected with their contexts. */
  case CollectErrors(errors: NonEmptyList[CollectError[E]])

  /** PPUP's failure, under UTXOS from Alonzo to Babbage. */
  case UpdateFailure(failure: PpupPredFailure[E])

/** `data AlonzoUtxosEvent era` (`Alonzo/Rules/Utxos.hs:125-129`, cardano-ledger f649f975). */
enum AlonzoUtxosEvent[E <: EraTag]:
  case AlonzoPpupToUtxosEvent(event: PpupEvent[E])
  case SuccessfulPlutusScriptsEvent(scripts: NonEmptyList[PlutusWithContext])
  case FailedPlutusScriptsEvent(scripts: NonEmptyList[PlutusWithContext])

/** `data ConwayUtxosPredFailure era` (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxos.hs:
  * 53-65`, cardano-ledger f649f975): Alonzo's without PPUP's.
  */
enum ConwayUtxosPredFailure[E <: EraTag]:
  case ValidationTagMismatch(isValid: IsPhase2Valid, description: TagMismatchDescription)
  case CollectErrors(errors: NonEmptyList[CollectError[E]])

/** `data ConwayUtxosEvent era` (`Conway/Rules/Utxos.hs:67-70`, cardano-ledger f649f975). */
enum ConwayUtxosEvent[E <: EraTag]:
  case SuccessfulPlutusScriptsEvent(scripts: NonEmptyList[PlutusWithContext])
  case FailedPlutusScriptsEvent(scripts: NonEmptyList[PlutusWithContext])

/** `EraRuleFailure "UTXOS" era`: Conway's is its own (`Conway/Rules/Utxos.hs:76`, cardano-ledger
  * f649f975), and Dijkstra's is Conway's, as its UTXOS is (`Dijkstra/Rules/Utxos.hs:18`,
  * cardano-ledger 9df9f98c). Each era adds its case with its rule.
  */
type UtxosPredFailure[E <: EraTag] = E match
  case Conway   => ConwayUtxosPredFailure[Conway]
  case Dijkstra => ConwayUtxosPredFailure[Dijkstra]

/** `EraRuleEvent "UTXOS" era`: Conway's is its own (`Conway/Rules/Utxos.hs:78`, cardano-ledger
  * f649f975), and Dijkstra's is Conway's (`Dijkstra/Rules/Utxos.hs:20`, cardano-ledger 9df9f98c).
  */
type UtxosEvent[E <: EraTag] = E match
  case Conway   => ConwayUtxosEvent[Conway]
  case Dijkstra => ConwayUtxosEvent[Dijkstra]

/** `InjectRuleFailure "UTXOS" AlonzoUtxosPredFailure era` and `InjectRuleEvent "UTXOS"
  * AlonzoUtxosEvent era` (`Core/Era.hs`, cardano-ledger f649f975): how era `E` reports the failures
  * and events of the UTXOS functions it shares with Alonzo and Babbage. One instance per era, in
  * the companion.
  */
trait InjectAlonzoUtxos[E <: EraTag]:
  def injectFailure(failure: AlonzoUtxosPredFailure[E]): UtxosPredFailure[E]
  def injectEvent(event: AlonzoUtxosEvent[E]): UtxosEvent[E]

object InjectAlonzoUtxos:
  /** Conway's (`Conway/Rules/Utxos.hs:84-88`, cardano-ledger f649f975). */
  given conway: InjectAlonzoUtxos[Conway] with
    def injectFailure(failure: AlonzoUtxosPredFailure[Conway]): UtxosPredFailure[Conway] =
      ConwayUtxos.alonzoToConwayUtxosPredFailure[Conway](failure)
    def injectEvent(event: AlonzoUtxosEvent[Conway]): UtxosEvent[Conway] =
      ConwayUtxos.alonzoToConwayUtxosEvent[Conway](event)

  /** Dijkstra's (`Dijkstra/Rules/Utxos.hs:26-30`, cardano-ledger 9df9f98c): Conway's functions. */
  given dijkstra: InjectAlonzoUtxos[Dijkstra] with
    def injectFailure(failure: AlonzoUtxosPredFailure[Dijkstra]): UtxosPredFailure[Dijkstra] =
      ConwayUtxos.alonzoToConwayUtxosPredFailure[Dijkstra](failure)
    def injectEvent(event: AlonzoUtxosEvent[Dijkstra]): UtxosEvent[Dijkstra] =
      ConwayUtxos.alonzoToConwayUtxosEvent[Dijkstra](event)

/** Alonzo's functions of UTXOS (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxos.hs`,
  * cardano-ledger f649f975).
  */
object AlonzoUtxos:
  /** `when2Phase` (`Alonzo/Rules/Utxos.hs:420-421`, cardano-ledger f649f975): `fa` labelled static
    * and 2-phase, so it runs only under the policy that validates every check
    * ([[xyz.kd5ujc.scarab.ledger.TxEnv.Validation]]); a transaction applied again (`reapplyTx`)
    * skips it.
    */
  def when2Phase[F[_]: Applicative](validation: TxEnv.Validation)(fa: => F[Unit]): F[Unit] =
    validation match
      case TxEnv.Validation.All       => fa
      case TxEnv.Validation.NonStatic => Applicative[F].unit

/** Babbage's functions of UTXOS (`eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxos.hs`,
  * cardano-ledger f649f975), which Conway's UTXOS runs.
  */
object BabbageUtxos:
  /** The scripts of the annotated transaction with their contexts, their collection's failures
    * recorded (`?!:`, `CollectErrors`), and the scripts evaluated with `run` only under the policy
    * that validates every check (`when2Phase`) and only if nothing failed so far in the judgment
    * (`whenFailureFree`): the steps `expectScriptsToPass` and `babbageEvalScriptsTxInvalid` share.
    * Where they are evaluated is the run's ([[Phase2Deferral]]): a block the node prepares defers
    * them, so that neither their verdict nor `run` is reached before its plan checks them. A
    * transaction whose scripts are not collected is told to the run before the failures are
    * recorded ([[Phase2Deferral.uncollected]]), which the reference has no counterpart for.
    */
  private def evalScripts[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(run: ScriptResult => F[Unit])(using
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    val scriptsWithContextEither =
      u.plutusScriptsWithContextStAnnTx[TxLevel.Top](stAnnTx)(using u.txOf.bodyOf.top)
    val tx = u.txOf.txStAnnTx[TxLevel.Top](stAnnTx)(using u.txOf.bodyOf.top)
    for
      _ <- scriptsWithContextEither.left.toOption.traverse_(errors =>
        deferral.uncollected(tx) *>
          judge.fail(inject.injectFailure(AlonzoUtxosPredFailure.CollectErrors(errors)))
      )
      _ <- AlonzoUtxos.when2Phase[F](validation)(
        Sts.whenFailureFree[F, UtxosPredFailure[E]](
          scriptsWithContextEither.toOption.traverse_ { scriptsWithContext =>
            val now = PlutusEvaluation.evalPlutusScripts[F](scriptsWithContext).flatMap(run)
            NonEmptyList
              .fromList(scriptsWithContext)
              .fold(now)(deferral.evalScripts(stAnnTx, _)(now))
          }
        )
      )
    yield ()

  /** The event of the scripts that passed, if any did (`mapM_ (tellEvent ...) (nonEmpty ps)`). */
  private def tellPassed[F[_]: Applicative, E <: EraTag](passed: List[PlutusWithContext])(using
      inject: InjectAlonzoUtxos[E],
      events: Tell[F, Chain[UtxosEvent[E]]]
  ): F[Unit] =
    NonEmptyList
      .fromList(passed)
      .traverse_(ps =>
        events.tell(
          Chain.one(inject.injectEvent(AlonzoUtxosEvent.SuccessfulPlutusScriptsEvent(ps)))
        )
      )

  /** `expectScriptsToPass` (`Babbage/Rules/Utxos.hs:130-155`, cardano-ledger f649f975): the scripts
    * of a transaction flagged valid must pass; a failure is `ValidationTagMismatch` with each
    * script's failure, and the scripts that passed are told.
    */
  def expectScriptsToPass[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)
    evalScripts[F, E](stAnnTx, validation) {
      case ScriptResult.Fails(_, fs) =>
        judge.fail(
          inject.injectFailure(
            AlonzoUtxosPredFailure.ValidationTagMismatch(
              t.isValid(tx),
              TagMismatchDescription.FailedUnexpectedly(
                fs.map(FailureDescription.scriptFailureToFailureDescription)
              )
            )
          )
        )
      case ScriptResult.Passes(ps) => tellPassed[F, E](ps)
    }

  /** `babbageEvalScriptsTxInvalid` (`Babbage/Rules/Utxos.hs:192-222`, cardano-ledger f649f975): the
    * scripts of a transaction flagged invalid must not all pass; if they do,
    * `ValidationTagMismatch` with `PassedUnexpectedly`, and otherwise the scripts that passed and
    * those that failed are told.
    */
  def babbageEvalScriptsTxInvalid[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)
    evalScripts[F, E](stAnnTx, validation) {
      case ScriptResult.Passes(_) =>
        judge.fail(
          inject.injectFailure(
            AlonzoUtxosPredFailure.ValidationTagMismatch(
              t.isValid(tx),
              TagMismatchDescription.PassedUnexpectedly
            )
          )
        )
      case ScriptResult.Fails(ps, fs) =>
        tellPassed[F, E](ps) *> events.tell(
          Chain.one(
            inject.injectEvent(
              AlonzoUtxosEvent.FailedPlutusScriptsEvent(fs.map(_.scriptFailurePlutus))
            )
          )
        )
    }

/** Conway's UTXOS (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxos.hs`, cardano-ledger
  * f649f975; the same at 29a7d656, the 11.2 build, but for the instances it derives).
  */
object ConwayUtxos:
  /** `alonzoToConwayUtxosPredFailure` (`Conway/Rules/Utxos.hs:90-98`, cardano-ledger f649f975):
    * each failure as Conway's own; PPUP's has no value where the era has no PPUP (`absurdEraRule`),
    * which `void` witnesses.
    */
  def alonzoToConwayUtxosPredFailure[E <: EraTag](failure: AlonzoUtxosPredFailure[E])(using
      void: PpupPredFailure[E] =:= Nothing
  ): ConwayUtxosPredFailure[E] = failure match
    case AlonzoUtxosPredFailure.ValidationTagMismatch(t, x) =>
      ConwayUtxosPredFailure.ValidationTagMismatch(t, x)
    case AlonzoUtxosPredFailure.CollectErrors(x) => ConwayUtxosPredFailure.CollectErrors(x)
    case AlonzoUtxosPredFailure.UpdateFailure(x) => void(x)

  /** `alonzoToConwayUtxosEvent` (`Conway/Rules/Utxos.hs:100-108`, cardano-ledger f649f975). */
  def alonzoToConwayUtxosEvent[E <: EraTag](event: AlonzoUtxosEvent[E])(using
      void: PpupEvent[E] =:= Nothing
  ): ConwayUtxosEvent[E] = event match
    case AlonzoUtxosEvent.AlonzoPpupToUtxosEvent(x) => void(x)
    case AlonzoUtxosEvent.SuccessfulPlutusScriptsEvent(l) =>
      ConwayUtxosEvent.SuccessfulPlutusScriptsEvent(l)
    case AlonzoUtxosEvent.FailedPlutusScriptsEvent(l) =>
      ConwayUtxosEvent.FailedPlutusScriptsEvent(l)

  /** `utxosTransition` (`Conway/Rules/Utxos.hs:207-224`, cardano-ledger f649f975): by the
    * transaction's flag, the scripts expected to pass (`conwayEvalScriptsTxValid`) or not
    * (`babbageEvalScriptsTxInvalid`). The environment and the state are `()`.
    */
  def utxosTransition[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    t.isValid(t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)) match
      case IsPhase2Valid.Phase2Valid => conwayEvalScriptsTxValid[F, E](stAnnTx, validation)
      case IsPhase2Valid.Phase2Invalid =>
        BabbageUtxos.babbageEvalScriptsTxInvalid[F, E](stAnnTx, validation)

  /** `conwayEvalScriptsTxValid` (`Conway/Rules/Utxos.hs:226-242`, cardano-ledger f649f975):
    * Babbage's `expectScriptsToPass`, without Babbage's PPUP before it.
    */
  def conwayEvalScriptsTxValid[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    BabbageUtxos.expectScriptsToPass[F, E](stAnnTx, validation)
