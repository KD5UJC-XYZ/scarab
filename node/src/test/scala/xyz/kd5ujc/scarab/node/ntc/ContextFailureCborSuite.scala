// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyVector
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*

class ContextFailureCborSuite extends FunSuite:
  import ContextReferenceCases.{all, field, hex}

  all.filterNot(_.collected).foreach { recorded =>
    test(s"${recorded.name}: byte-equal unchanged reference ConwayUtxosPredFailure") {
      val errors =
        recorded.collect.swap.fold(_ => fail("collection unexpectedly succeeded"), identity)
      assertEquals(
        ContextFailureCbor.utxos(errors),
        Some(hex(field[String](recorded.oracle, "utxosFailureCbor")))
      )
      val failure = PredicateFailure.utxo(UtxoFailure.Utxos(UtxosFailure.CollectErrors(errors)))
      assert(PredicateFailureCbor.inConway(failure))
    }
  }

  all.foreach { recorded =>
    val certificates = field[Vector[Json]](recorded.oracle, "certificateEncodings")
    certificates.zipWithIndex.foreach { case (oracle, index) =>
      test(s"${recorded.name}: direct unchanged certificate-purpose serialization $index") {
        assertEquals(certificates.size, recorded.tx.body.certificates.size)
        val certificate = recorded.tx.body.certificates(index)
        val errors =
          NonEmptyVector.one(CollectError.NoRedeemer(Purpose.Certifying(index, certificate)))
        assertEquals(
          ContextFailureCbor.utxos(errors),
          Some(hex(field[String](oracle, "noRedeemerUtxosCbor")))
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
        val errors =
          recorded.collect.swap.fold(_ => fail("collection unexpectedly succeeded"), identity)
        val failure = PredicateFailure.utxo(UtxoFailure.Utxos(UtxosFailure.CollectErrors(errors)))
        assertEquals(
          PredicateFailureCbor.applyTxError(Vector(failure)),
          hex(field[String](reference, "reasonCbor"))
        )
        assertEquals(
          recorded.original,
          hex(field[String](field[Json](json, "contextCase"), "transaction"))
        )
      }
  }

  test("implementation-specific setup errors retain their explicit text fallback") {
    val failure = PredicateFailure.utxo(
      UtxoFailure.Utxos(
        UtxosFailure.CollectErrors(
          NonEmptyVector.one(CollectError.BadTranslation(TranslationError.NoEraHistory))
        )
      )
    )
    assert(!PredicateFailureCbor.inConway(failure))
    assertEquals(
      ContextFailureCbor.utxos(
        NonEmptyVector.one(CollectError.BadTranslation(TranslationError.NoEraHistory))
      ),
      None
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
    assert(
      ContextFailureCbor
        .utxos(NonEmptyVector.one(CollectError.NoRedeemer(Purpose.Certifying(0, certificate))))
        .isDefined
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
    assertEquals(update.fields(0).hex, "1801")
    assertEquals(update.fields(9).hex, "d81e820204")
    val body = proposal.tx.body.raw
    val id = proposal.tx.body.id
    val errors = proposal.collect.swap.fold(_ => fail("no context refusal"), identity)
    assert(ContextFailureCbor.utxos(errors).isDefined)
    assertEquals(proposal.tx.body.raw, body)
    assertEquals(proposal.tx.body.id, id)
    assertEquals(update.fields(0).hex, "1801")
    assertEquals(update.fields(9).hex, "d81e820204")
  }

  test("twenty thousand collection errors encode on a small stack with reference list framing") {
    val error = CollectError.NoWitness(Bytes(Vector.fill(28)(1.toByte)))
    val errors = NonEmptyVector(error, Vector.fill(19999)(error))
    val encoded = PreprodDeepTx
      .onSmallStack(ContextFailureCbor.utxos(errors))
      .getOrElse(fail("no reference encoding"))
    // encodeList uses indefinite framing beyond23 items, including NonEmpty CollectError lists.
    assert(encoded.hex.startsWith("82019f"))
    assert(encoded.hex.endsWith("ff"))
  }
