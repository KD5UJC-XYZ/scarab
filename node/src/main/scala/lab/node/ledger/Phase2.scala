// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.Id
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.node.plutus.*

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

    def loop(
        rest: List[Redeemer],
        done: Int
    ): Either[TranslationError, Outcome] = rest match
      case Nil => Right(Outcome.Passed(done))
      case r :: tail =>
        for
          purpose <- Purpose
            .resolve(tx.body, spent, r.tag, r.index)
            .toRight(TranslationError(s"redeemer (${r.tag}, ${r.index}) names no purpose"))
          hash <- purpose.scriptHash.toRight(
            TranslationError(s"redeemer (${r.tag}, ${r.index}) has no script")
          )
          script <- scripts.get(hash).toRight(TranslationError(s"script ${hash.hex} is missing"))
          language <- languageOf(script)
          code <- script.plutus
            .orElse(referenceScripts.get(hash))
            .toRight(TranslationError(s"script ${hash.hex} has no bytes"))
          model <- costModel(pp, language)
          txInfo <- info(language)
          args <- ScriptContexts.arguments(language, txInfo, tx, r, purpose, pp.protocolMajor.toInt)
          result = evaluator.evaluate(
            EvalRequest(language, pp.protocolMajor.toInt, code, args, model, r.exUnits)
          )
          outcome <- result match
            case EvalResult.Success(_, _) => loop(tail, done + 1)
            case EvalResult.Failure(reason, _, _) =>
              Right(Outcome.Failed(done, r, reason))
        yield outcome

    loop(redeemers.toList, 0)

  private def languageOf(script: Script): Either[TranslationError, PlutusLanguage] =
    script.kind match
      case ScriptKind.PlutusV1 => Right(PlutusLanguage.V1)
      case ScriptKind.PlutusV2 => Right(PlutusLanguage.V2)
      case ScriptKind.PlutusV3 => Right(PlutusLanguage.V3)
      case ScriptKind.Native   => Left(TranslationError("a redeemer names a native script"))

  private def costModel(
      pp: ProtocolParams,
      language: PlutusLanguage
  ): Either[TranslationError, Vector[Long]] =
    pp.costModels
      .get(language.id)
      .toRight(TranslationError(s"no cost model for $language"))
      .flatMap(m =>
        Either.cond(
          m.forall(_.isValidLong),
          m.map(_.toLong),
          TranslationError("cost model parameter exceeds 64 bits")
        )
      )
