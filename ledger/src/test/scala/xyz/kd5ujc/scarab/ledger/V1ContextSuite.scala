// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ScalusEvaluator

/** The Plutus V1 script context against the reference node, case by case.
  *
  * The JSON files in `fixtures/reference/v1-context` hold transactions that `devnet.py v1-context`
  * built and submitted to the reference node (a Haskell cluster, one file per era and protocol
  * version, see `fixtures/reference/README.md`): each with the outputs it reads and what the
  * reference did with it, accepted into a block or refused with its predicate failure. Every case
  * is replayed here at three levels, and the node must decide as the reference did:
  *
  *   - phase 2 (`Phase2.evaluate`): the context of each script is built, or the translation error
  *     the reference names comes out, then the scripts run;
  *   - the transaction rule of the era (LEDGER) over a ledger holding the outputs the case reads;
  *   - the block rule (BBODY) over a block of the transaction.
  */
class V1ContextSuite extends FunSuite:
  /** What the reference did with a case. */
  private final case class Verdict(accepted: Boolean, text: String):
    /** The context error the reference's refusal names, if it names one. */
    def translation(c: PlutusCase): Option[TranslationError] =
      val inputSource = (c.spent.keys ++ c.references.keys)
        .find { in =>
          text.contains(in.id.hex) && text.contains(s"unTxIx = ${in.index}")
        }
        .map(TxOutSource.TxOutFromInput(_))
      val outputSource = "TxOutFromOutput.*?unTxIx = ([0-9]+)".r
        .findFirstMatchIn(text)
        .map(m => TxOutSource.TxOutFromOutput(m.group(1).toInt))
      val source = inputSource.orElse(outputSource)
      if text.contains("ReferenceInputsNotSupported") then
        Some(TranslationError.ReferenceInputsNotSupported(c.tx.body.referenceInputs.toSet))
      else
        source.flatMap { from =>
          Vector(
            "InlineDatumsNotSupported" -> TranslationError.InlineDatumsNotSupported(from),
            "ByronTxOutInContext" -> TranslationError.ByronTxOutInContext(from),
            "ReferenceScriptsNotSupported" -> TranslationError.ReferenceScriptsNotSupported(from)
          ).collectFirst { case (name, error) if text.contains(name) => error }
        }

    /** The refusal is a disagreement between the validity flag and the outcome of the scripts. */
    def mismatch: Boolean = !accepted && text.contains("ValidationTagMismatch")

  private final case class Recorded(file: String, plutus: PlutusCase, verdict: Verdict)

  // The same transaction/ledger/block harness also checks the executed binary-DST controls.
  private val directories = Vector(
    Path.of("fixtures/reference/v1-context"),
    Path.of("fixtures/reference/context-errors/submissions"),
    Path.of("fixtures/reference/plutus-boundaries/transactions")
  )

  private val recorded: Vector[Recorded] =
    directories.flatMap { directory =>
      if !Files.isDirectory(directory) then Vector.empty
      else
        Files
          .list(directory)
          .iterator
          .asScala
          .filter(_.toString.endsWith(".json"))
          .toVector
          .sortBy(_.getFileName.toString)
          .flatMap { path =>
            val cases =
              parse(Files.readString(path)).toOption.flatMap(_.asArray).getOrElse(Vector.empty)
            cases.map { j =>
              val reference = j.hcursor.downField("reference")
              Recorded(
                path.toString,
                PlutusCase.fromJson(j),
                Verdict(
                  reference.get[Boolean]("accepted").toOption.get,
                  reference.get[String]("error").toOption.get
                )
              )
            }
          }
    }

  test("the fixtures cover every era and both verdicts") {
    assert(recorded.nonEmpty, "no recorded cases")
    val eras = recorded.map(_.plutus.protocolMajor).toSet
    assert(Set(7, 8, 9, 10).subsetOf(eras), s"protocol versions covered: $eras")
    assert(recorded.exists(_.verdict.accepted) && recorded.exists(!_.verdict.accepted))
    assert(recorded.exists(r => !r.plutus.tx.isValid))
  }

  // ---- the scripts of a case ---------------------------------------------------------------
  private def scriptsOf(c: PlutusCase): Map[Bytes, Script] =
    (c.spent.values ++ c.references.values)
      .flatMap(_.referenceScript)
      .map(s => s.hash -> s)
      .toMap ++ c.tx.witnesses.scriptsByHash

  private def utxoOf(c: PlutusCase): Map[TxIn, TxOut] = c.spent ++ c.references ++ c.collateral

  // ---- level 1: phase 2 ------------------------------------------------------------------------
  recorded.foreach { r =>
    val c = r.plutus
    test(s"${r.file}: ${c.name}: phase 2 does what the reference did") {
      val outcome =
        Phase2.evaluate(
          c.tx,
          c.params,
          scriptsOf(c),
          c.spent,
          c.references,
          c.posix,
          ScalusEvaluator.default
        )
      r.verdict.translation(c) match
        case Some(error) =>
          assertEquals(
            outcome.map(_.passed),
            Left(cats.data.NonEmptyVector.one(CollectError.BadTranslation(error)))
          )
        // the reference accepts a transaction whose flag agrees with the scripts' outcome
        case None => assertEquals(outcome.map(_.passed), Right(r.verdict.accepted == c.tx.isValid))
    }
  }

  // ---- level 2: the transaction rule ---------------------------------------------------------------
  /** The refusal the reference gave, as the rule of this node gives it. */
  private def refusalOf(c: PlutusCase, r: Verdict, failure: LedgerFailure): Unit =
    val id = c.tx.body.id
    r.translation(c) match
      case Some(error) =>
        assertEquals(
          failure,
          LedgerFailure.Rejected(
            id,
            PredicateFailure.utxo(
              UtxoFailure.Utxos(
                UtxosFailure.CollectErrors(
                  cats.data.NonEmptyVector.one(CollectError.BadTranslation(error))
                )
              )
            )
          )
        )
      case None =>
        assert(r.mismatch, r.text)
        failure match
          case LedgerFailure.Rejected.Single(
                `id`,
                PredicateFailure.Utxow(
                  UtxowFailure.Utxo(UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(flag, _)))
                )
              ) =>
            assertEquals(flag, c.tx.isValid)
          case other => fail(s"not a validation tag mismatch: $other")

  private def verdictOf[A](
      c: PlutusCase,
      r: Verdict,
      result: Either[LedgerFailure, A]
  ): Unit =
    result match
      case Right(_) => assert(r.accepted, s"the node accepts what the reference refused: ${r.text}")
      case Left(failure) =>
        assert(!r.accepted, s"the node refuses what the reference accepted: ${failure.toString}")
        refusalOf(c, r, failure)

  private val globals = TestLedger.globals

  private def eraTx[E <: EraTag](c: PlutusCase)(using
      era: ShelleyBasedEra[E],
      pre: PreConway[E]
  ): Either[LedgerFailure, Counted[LedgerState[E]]] =
    val nes = Build.funded(Build.nes[E](5, c.params), utxoOf(c))
    JudgedSts(ShelleyLedger.sts[E])(LedgerFailure.Rejected(c.tx.body.id, _))(
      globals,
      ShelleyLedgerEnv(TestLedger.env, nes.es.chainAccount),
      Build.counted(nes),
      c.tx
    )

  private def conwayTx(c: PlutusCase) =
    val nes = Build.funded(Build.nes[Conway](5, c.params), utxoOf(c))
    TestLedger.conwayLedger(nes, c.tx, TestLedger.env)

  recorded.foreach { r =>
    val c = r.plutus
    test(s"${r.file}: ${c.name}: the transaction rule of the era does what the reference did") {
      val result =
        if c.protocolMajor >= 9 then conwayTx(c)
        else if c.protocolMajor >= 7 then eraTx[Babbage](c)
        else eraTx[Alonzo](c)
      verdictOf(c, r.verdict, result)
    }
  }

  // ---- level 3: a block ---------------------------------------------------------------------------
  private def ok[A](either: Either[VocabError, A]): A =
    either.fold(e => fail(s"vocabulary: $e"), identity)

  private val issuer: KeyHash[StakePool] =
    ok(KeyHash.fromBytes[StakePool](Bytes.fromArray(Array.fill(28)(7.toByte))))

  private val commitment = BodyCommitment(100, Bytes.fromArray(Array.fill(32)(1.toByte)))

  private def eraBlock[E <: EraTag](c: PlutusCase)(using
      era: ShelleyBasedEra[E],
      pre: PreConway[E]
  ): Either[BlockFailure, ?] =
    val nes = Build.funded(Build.nes[E](5, c.params), utxoOf(c))
    Sts(ShelleyBbody.sts[E])(
      globals,
      ShelleyBbodyEnv(TestLedger.env, c.params, ChainAccountState(Coin.zero, Coin.zero)),
      ShelleyBbodyState(Build.counted(nes), BlocksMade.empty),
      BbodySignal(Vector(c.tx), issuer, commitment, commitment)
    )

  private def conwayBlock(c: PlutusCase): Either[BlockFailure, ?] =
    val nes = Build.funded(Build.nes[Conway](5, c.params), utxoOf(c))
    Sts(Bbody)(
      globals,
      BbodyEnv(TestLedger.env, c.params, Coin.zero),
      BbodyState(Build.counted(nes), BlocksMade.empty),
      BbodySignal(Vector(c.tx), issuer, commitment, commitment)
    )

  recorded.foreach { r =>
    val c = r.plutus
    test(
      s"${r.file}: ${c.name}: a block of the transaction is accepted or refused as the reference did"
    ) {
      val result =
        if c.protocolMajor >= 9 then conwayBlock(c)
        else if c.protocolMajor >= 7 then eraBlock[Babbage](c)
        else eraBlock[Alonzo](c)
      assertEquals(result.isRight, r.verdict.accepted, result.toString.take(400))
    }
  }
