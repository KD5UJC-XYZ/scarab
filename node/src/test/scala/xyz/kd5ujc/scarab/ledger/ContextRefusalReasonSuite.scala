// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor

/** The whole raw reason of each refused context submission (#434).
  *
  * `fixtures/reference/context-errors/submissions/conway-pv10.json` records, for every transaction
  * the reference refused, the identical `ApplyTxErr` the reference node answered over
  * LocalTxSubmission (`reference.reasonCbor`). Those are the only refused cases in the tree that
  * carry the reference's raw reason rather than the truncated `cardano-cli` text.
  *
  * `V1ContextSuite` reads the one cause that text names and checks that the Scala rule gives that
  * failure; a constructor name appearing in the text does not show that the reference's failure
  * list held only that failure, nor that the encodings agree. Here each refusal is compared as a
  * whole: the Conway LEDGER rule replays the transaction at the recorded protocol parameters, and
  * the encoded Scala refusal must be the reference's reason, byte for byte. The encoding lives in
  * the node module (`PredicateFailureCbor`), which the ledger module cannot reach, so the
  * comparison is made here.
  *
  * Cases that keep no raw reason (the `v1-context` and `plutus-boundaries` files) are still
  * bounded to containment; register entry `v1context-refusal-membership` stays `bounded` until a
  * fresh recording gives them one too (#434).
  */
class ContextRefusalReasonSuite extends FunSuite:
  private final case class Refused(name: String, plutus: PlutusCase, reason: Bytes)

  private val submissions =
    parse(
      Files.readString(Path.of("fixtures/reference/context-errors/submissions/conway-pv10.json"))
    ).fold(error => fail(error.message), identity)
      .asArray
      .getOrElse(fail("not submissions"))

  /** Every refused submission, with the raw reason the reference answered. */
  private val refused: Vector[Refused] =
    submissions.flatMap { json =>
      val reference = json.hcursor.downField("reference")
      val name = json.hcursor.get[String]("name").fold(e => fail(e.message), identity)
      val accepted = reference.get[Boolean]("accepted").fold(e => fail(e.message), identity)
      if accepted then None
      else
        val raw = reference.get[String]("reasonCbor").fold(e => fail(e.message), identity)
        val reason = Bytes.fromHex(raw).fold(e => fail(e.toString), identity)
        Some(Refused(name, PlutusCase.fromJson(json), reason))
    }

  private def utxoOf(c: PlutusCase): Map[TxIn, TxOut] = c.spent ++ c.references ++ c.collateral

  /** A refusal whose reason this node's encoder does not reproduce yet, and the issue that owns it.
    *
    * `PredicateFailureCbor` writes the failed script's whole message where the reference writes its
    * own description of the failure, so the two encodings differ in that one text field and in its
    * length head. Every other refusal here is compared as a whole; this one is pinned as a
    * divergence so that fixing the encoder fails this test and removes the exclusion (#434, #781).
    */
  private val diverging = Map(
    "conway-pv10-v1-plain-fail-valid-tag" -> "the failed script's description (#781)"
  )

  test("every refused submission carries the reference's whole raw reason") {
    assertEquals(refused.size, 12, "refusals in the recorded submissions")
    assert(refused.forall(_.reason.nonEmpty), "a refusal with an empty reason")
  }

  test("the refusals whose reason the encoder does not reproduce are the ones tracked by an issue") {
    assertEquals(
      refused.map(_.name).filter(diverging.contains).toSet,
      diverging.keySet,
      "a tracked divergence that no longer occurs, or a new one that is not tracked"
    )
  }

  refused.filterNot(r => diverging.contains(r.name)).foreach { r =>
    test(s"${r.name}: the ledger rule's encoded refusal is the reference's raw reason") {
      val c = r.plutus
      val nes = Build.funded(Build.nes[Conway](5, c.params), utxoOf(c))
      TestLedger.conwayLedger(nes, c.tx, TestLedger.env) match
        case Right(_) => fail("the node accepts what the reference refused")
        case Left(LedgerFailure.Rejected.Single(_, failure)) =>
          assertEquals(
            PredicateFailureCbor.applyTxError(Vector(failure)),
            r.reason,
            s"encoded refusal of ${c.name}"
          )
        case Left(other) => fail(s"not a single refusal: $other")
    }
  }

  diverging.foreach { case (name, why) =>
    test(s"$name: the encoder's refusal still differs from the reference's reason, $why") {
      val c = refused.find(_.name == name).getOrElse(fail(s"no refusal named $name")).plutus
      val nes = Build.funded(Build.nes[Conway](5, c.params), utxoOf(c))
      val encoded = TestLedger.conwayLedger(nes, c.tx, TestLedger.env) match
        case Left(LedgerFailure.Rejected.Single(_, failure)) =>
          PredicateFailureCbor.applyTxError(Vector(failure))
        case other => fail(s"not a single refusal: $other")
      val reason = refused.find(_.name == name).get.reason
      assertNotEquals(encoded, reason)
    }
  }
