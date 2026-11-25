// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyList
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoContextError,
  AsItem,
  BabbageContextError,
  CollectError as EraCollect,
  EraTxBody,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayContextError, ConwayPlutusPurpose}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayLedgerPredFailure,
  ConwayUtxoPredFailure,
  ConwayUtxosPredFailure,
  ConwayUtxowPredFailure
}
import xyz.kd5ujc.scarab.ledger.state.Conway

/** The reference's collection API results (`fixtures/reference/context-errors`) against Conway's
  * era collection (`collectPlutusScriptsWithContext`) and its era failure encoder
  * ([[ConwayFailureCbor]]): each recorded UTXOS failure, each certificate as a missing redeemer's
  * purpose, and each submission's whole LocalTxSubmission refusal, byte for byte.
  */
class ContextFailureCborSuite extends FunSuite:
  import ContextReferenceCases.{all, field, hex}

  /** UTXOS's `CollectErrors` of `errors`. */
  private def collectErrors(
      errors: NonEmptyList[EraCollect[Conway]]
  ): ConwayUtxosPredFailure[Conway] =
    ConwayUtxosPredFailure.CollectErrors[Conway](errors)

  /** UTXOS's failure as LEDGER embeds it, through UTXO's and UTXOW's embeddings. */
  private def ledger(f: ConwayUtxosPredFailure[Conway]): ConwayLedgerPredFailure[Conway] =
    ConwayLedgerPredFailure.ConwayUtxowFailure[Conway](
      ConwayUtxowPredFailure.UtxoFailure[Conway](ConwayUtxoPredFailure.UtxosFailure[Conway](f))
    )

  private def utxos(f: ConwayUtxosPredFailure[Conway]): Bytes =
    ConwayFailureCbor.utxos(f).fold(e => fail(s"no Conway encoding: $e"), identity)

  private def refused(recorded: ContextReferenceCases.Recorded): NonEmptyList[EraCollect[Conway]] =
    recorded.eraCollect.swap.fold(_ => fail("collection unexpectedly succeeded"), identity)

  all.filterNot(_.collected).foreach { recorded =>
    test(s"${recorded.name}: byte-equal unchanged reference ConwayUtxosPredFailure") {
      assertEquals(
        utxos(collectErrors(refused(recorded))),
        hex(field[String](recorded.oracle, "utxosFailureCbor"))
      )
    }
  }

  all.foreach { recorded =>
    val certificates = field[Vector[Json]](recorded.oracle, "certificateEncodings")
    certificates.zipWithIndex.foreach { case (oracle, index) =>
      test(s"${recorded.name}: direct unchanged certificate-purpose serialization $index") {
        val b = EraTxBody.conway
        val body = xyz.kd5ujc.scarab.ledger.era.EraTx.conway
          .body[TxLevel.Top](recorded.eraTx)(using
            xyz.kd5ujc.scarab.ledger.era.EraTx.conway.bodyOf.top
          )
        val certs = b.certs[TxLevel.Top](body)(using b.top)
        assertEquals(certificates.size, certs.size)
        val purpose = ConwayPlutusPurpose.ConwayCertifying[AsItem](AsItem(certs(index)))
        assertEquals(
          utxos(collectErrors(NonEmptyList.one(EraCollect.NoRedeemer[Conway](purpose)))),
          hex(field[String](oracle, "noRedeemerUtxosCbor"))
        )
      }
    }
  }

  private val submitted = parse(
    Files.readString(Path.of("fixtures/reference/context-errors/submissions/conway-pv10.json"))
  )
    .fold(error => fail(error.message), identity)
    .asArray
    .getOrElse(fail("not submissions"))

  submitted.foreach { json =>
    val name = field[String](json, "name")
    val recorded = all.find(_.name == name).getOrElse(fail(s"no API result for $name"))
    if !recorded.collected then
      test(s"$name: exact actual reference LocalTxSubmission refusal bytes") {
        val reference = field[Json](json, "reference")
        assert(!field[Boolean](reference, "accepted"))
        assertEquals(
          ConwayFailureCborCheck.direct(Vector(ledger(collectErrors(refused(recorded))))),
          hex(field[String](reference, "reasonCbor"))
        )
        assertEquals(
          recorded.original,
          hex(field[String](field[Json](json, "contextCase"), "transaction"))
        )
      }
  }

  test("implementation-specific setup errors retain their explicit text fallback") {
    // the clock's own failure has no constructor of the reference's: no bytes, the cause kept
    val cause = AlonzoContextError.TimeTranslationPastHorizon[Conway](TranslationError.NoEraHistory)
    val failure = collectErrors(
      NonEmptyList.one(
        EraCollect.BadTranslation[Conway](
          ConwayContextError.BabbageContextError[Conway](
            BabbageContextError.AlonzoContextError[Conway](cause)
          )
        )
      )
    )
    assertEquals(
      ConwayFailureCbor.applyTxError(NonEmptyList.one(ledger(failure))),
      Left(ConwayFailureCbor.NoReferenceEncoding.ContextError(cause))
    )
  }

  test("diagnostic normalization preserves the noncanonical original pool and proposal bytes") {
    val pool = all.find(_.name == "api-pool-noncanonical-payload").getOrElse(fail("no pool oracle"))
    val certificate = pool.tx.body.certificates.headOption.getOrElse(fail("no pool certificate"))
    val originalPool = certificate match
      case Certificate.RegisterPool(p) =>
        assert(p.relays.hex.startsWith("9801"))
        assert(p.metadata.exists(_.hex.startsWith("9802")))
        (p.relays, p.metadata)
      case _ => fail("not pool parameters")
    val poolBody = pool.tx.body.raw
    val poolId = pool.tx.body.id
    val b = EraTxBody.conway
    val t = xyz.kd5ujc.scarab.ledger.era.EraTx.conway
    val eraCertificate = b
      .certs[TxLevel.Top](t.body[TxLevel.Top](pool.eraTx)(using t.bodyOf.top))(using b.top)
      .headOption
      .getOrElse(fail("no era pool certificate"))
    assert(
      ConwayFailureCbor
        .utxos(
          collectErrors(
            NonEmptyList.one(
              EraCollect.NoRedeemer[Conway](
                ConwayPlutusPurpose.ConwayCertifying[AsItem](AsItem(eraCertificate))
              )
            )
          )
        )
        .isRight
    )
    assertEquals(pool.tx.body.raw, poolBody)
    assertEquals(pool.tx.body.id, poolId)
    certificate match
      case Certificate.RegisterPool(p) => assertEquals((p.relays, p.metadata), originalPool)
      case _                           => fail("not pool parameters")

    val proposal = all
      .find(_.name == "api-proposal-noncanonical-params")
      .getOrElse(fail("no parameter oracle"))
    val update = proposal.tx.body.proposals.headOption.map(_.action) match
      case Some(GovAction.ParameterChange(_, update, _)) => update
      case _                                             => fail("not a parameter change")
    assertEquals(update.encodings.toMap.apply(0).hex, "1801")
    assertEquals(update.encodings.toMap.apply(9).hex, "d81e820204")
    val body = proposal.tx.body.raw
    val id = proposal.tx.body.id
    assert(ConwayFailureCbor.utxos(collectErrors(refused(proposal))).isRight)
    assertEquals(proposal.tx.body.raw, body)
    assertEquals(proposal.tx.body.id, id)
    assertEquals(update.encodings.toMap.apply(0).hex, "1801")
    assertEquals(update.encodings.toMap.apply(9).hex, "d81e820204")
  }

  test("twenty thousand collection errors encode on a small stack with reference list framing") {
    val error = EraCollect.NoWitness[Conway](Bytes(Vector.fill(28)(1.toByte)))
    val errors = NonEmptyList(error, List.fill(19999)(error))
    val encoded = PreprodDeepTx
      .onSmallStack(ConwayFailureCbor.utxos(collectErrors(errors)))
      .fold(e => fail(s"no reference encoding: $e"), identity)
    // encodeList uses indefinite framing beyond23 items, including NonEmpty CollectError lists.
    assert(encoded.hex.startsWith("82019f"))
    assert(encoded.hex.endsWith("ff"))
  }
