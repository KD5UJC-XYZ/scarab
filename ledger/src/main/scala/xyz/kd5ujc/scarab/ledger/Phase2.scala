// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.{Id, Monad}
import cats.syntax.traverse.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.*

/** Phase-2 validation: every redeemer is evaluated against its script with the script context the
  * ledger builds. A transaction passes phase 2 when all of its scripts succeed within the budgets
  * the redeemers declare; the block's `is_valid` flag must equal that verdict.
  *
  * The evaluator is a pure function of the request (see [[ScriptEvaluator]]), so this stays a pure
  * function of the ledger state, the transaction and the era-history clock.
  */
object Phase2:
  /** Everything one evaluation depended on, handed to an optional observer (tooling that records
    * evaluation cases); observing never changes the result.
    */
  final case class Observation(
      tx: Tx,
      params: ProtocolParams,
      slot: BigInt,
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      clock: Option[BigInt => BigInt],
      outcome: Either[TranslationError, Outcome]
  )

  /** The first script that failed, or the number of scripts that all succeeded. */
  enum Outcome:
    case Passed(scripts: Int)
    case Failed(scripts: Int, redeemer: Redeemer, reason: EvalFailure)

    def passed: Boolean = this match
      case Passed(_) => true
      case _         => false

  /** `posix` converts a slot to POSIX milliseconds; a failure is a translation error. */
  def evaluate(
      tx: Tx,
      pp: ProtocolParams,
      scripts: Map[Bytes, Script],
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      posix: BigInt => Either[TranslationError, BigInt],
      evaluator: ScriptEvaluator[Id]
  ): Either[TranslationError, Outcome] =
    val env = ScriptContexts.Environment(pp.protocolMajor.toInt, spent, references, posix)
    lazy val referenceScripts: Map[Bytes, Bytes] =
      (spent.valuesIterator ++ references.valuesIterator)
        .flatMap(_.referenceScript)
        .flatMap(s => s.plutus.map(s.hash -> _))
        .toMap
    val redeemers = tx.witnesses.redeemers.sortBy(r => (r.tag, r.index))
    // The transaction information is shared by every redeemer of a language.
    val infos = collection.mutable.Map.empty[PlutusLanguage, Either[TranslationError, PlutusData]]
    def info(language: PlutusLanguage) =
      infos.getOrElseUpdate(language, ScriptContexts.txInfo(language, tx, env))

    /** One redeemer against its script: the request to evaluate, or why it cannot be set up. */
    def prepare(r: Redeemer): Either[TranslationError, (Redeemer, EvalRequest)] =
      for
        purpose <- Purpose
          .resolve(tx.body, spent, r.tag, r.index)
          .toRight(TranslationError.NoRedeemer(r.tag, r.index))
        hash <- purpose.scriptHash.toRight(TranslationError.NoRedeemer(r.tag, r.index))
        script <- scripts.get(hash).toRight(TranslationError.NoWitness(hash))
        language <- languageOf(script)
        code <- script.plutus
          .orElse(referenceScripts.get(hash))
          .toRight(TranslationError.NoScriptBytes(hash))
        model <- costModel(pp, language)
        txInfo <- info(language)
        args <- ScriptContexts.arguments(language, txInfo, tx, r, purpose, pp.protocolMajor.toInt)
      yield r -> EvalRequest(language, pp.protocolMajor.toInt, code, args, model, r.exUnits)

    // The reference builds the context of every script before it runs any of them
    // (`collectPlutusScriptsWithContext`, `Alonzo/Plutus/Evaluate.hs:128-185`) and refuses the
    // transaction on any failure to do so (`Babbage/Rules/Utxos.hs:142-143`, `:205-206`), whatever
    // the validity flag says. So a script that fails first does not spare a later one whose context
    // cannot be built.
    redeemers.traverse(prepare).flatMap { requests =>
      // A transaction can carry well over a hundred redeemers. The loop is `tailRecM` over a typed
      // state, so the stack does not grow with their number.
      Monad[Translated].tailRecM(Progress(requests.toList, 0)) {
        case Progress(Nil, done) => Right(Right(Outcome.Passed(done)))
        case Progress((r, request) :: tail, done) =>
          Right(evaluator.evaluate(request) match
            case EvalResult.Success(_, _)         => Left(Progress(tail, done + 1))
            case EvalResult.Failure(reason, _, _) => Right(Outcome.Failed(done, r, reason)))
      }
    }

  private type Translated[A] = Either[TranslationError, A]

  /** The loop state of [[evaluate]]: the redeemers still to run and how many have passed. */
  private final case class Progress(rest: List[(Redeemer, EvalRequest)], done: Int)

  private[ledger] def languageOf(script: Script): Either[TranslationError, PlutusLanguage] =
    script.kind match
      case ScriptKind.PlutusV1 => Right(PlutusLanguage.V1)
      case ScriptKind.PlutusV2 => Right(PlutusLanguage.V2)
      case ScriptKind.PlutusV3 => Right(PlutusLanguage.V3)
      case ScriptKind.Native   => Left(TranslationError.NativeScriptRedeemer)

  private def costModel(
      pp: ProtocolParams,
      language: PlutusLanguage
  ): Either[TranslationError, Vector[Long]] =
    pp.costModels
      .get(language.id)
      .toRight(TranslationError.NoCostModel(language))
      .flatMap(m =>
        Either.cond(
          m.forall(_.isValidLong),
          m.map(_.toLong),
          TranslationError.CostModelParameterTooLarge
        )
      )
