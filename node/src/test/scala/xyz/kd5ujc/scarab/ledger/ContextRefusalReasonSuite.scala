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
  * comparison is made here. The one refusal whose description this node does not yet write as the
  * reference does is excluded from that equality and pinned below ([[diverging]], #781).
  *
  * Cases that keep no raw reason (the `v1-context` and `plutus-boundaries` files) are still bounded
  * to containment; register entry `v1context-refusal-membership` stays `bounded` until a fresh
  * recording gives them one too (#434).
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

  /** A refusal this node's encoder does not reproduce yet, and the issue that owns it.
    *
    * `PredicateFailureCbor` writes the failed script's whole message where the reference writes its
    * own description of the failure, so the two encodings differ in that one text field and in its
    * length head. The pin below asserts that the difference is confined to that field and fails
    * once the encodings agree, so fixing the encoder removes the exclusion by failing the test.
    */
  private val diverging = Map("conway-pv10-v1-plain-fail-valid-tag" -> "#781")

  private def utxoOf(c: PlutusCase): Map[TxIn, TxOut] = c.spent ++ c.references ++ c.collateral

  /** The reason the Conway transaction rule gives for `c`, as the node sends it. */
  private def encodedRefusal(c: PlutusCase, name: String): Bytes =
    val nes = Build.funded(Build.nes[Conway](5, c.params), utxoOf(c))
    TestLedger.conwayLedger(nes, c.tx, TestLedger.env) match
      case Right(_) => fail(s"$name: the node accepts what the reference refused")
      case Left(LedgerFailure.Rejected.Single(_, failure)) =>
        PredicateFailureCbor.applyTxError(Vector(failure))
      case Left(other) => fail(s"$name: not a single refusal: $other")

  /** The bytes of `item` from its first two-byte-string head: the reference's payload and
    * everything after the failure's description, which both sides write alike.
    */
  private def payloadFrom(item: Bytes): Option[Bytes] =
    val bytes = item.toArray
    val head = Array(0x59.toByte, 0x04.toByte, 0x40.toByte)
    (0 to bytes.length - head.length)
      .find(start => bytes.slice(start, start + head.length).sameElements(head))
      .map(start => Bytes(bytes.drop(start).toVector))

  test("every refused submission carries the reference's whole raw reason") {
    assertEquals(refused.size, 12, "refusals in the recorded submissions")
    assert(refused.forall(_.reason.nonEmpty), "a refusal with an empty reason")
  }

  refused.filterNot(r => diverging.contains(r.name)).foreach { r =>
    test(s"${r.name}: the ledger rule's encoded refusal is the reference's raw reason") {
      assertEquals(
        encodedRefusal(r.plutus, r.name),
        r.reason,
        s"encoded refusal of ${r.plutus.name}"
      )
    }
  }

  test(
    "the refusals whose encoded reason differs from the reference's are the ones an issue tracks"
  ) {
    val differing = refused
      .filter(r => encodedRefusal(r.plutus, r.name) != r.reason)
      .map(_.name)
      .toSet
    assertEquals(differing, diverging.keySet, "a divergence that is not tracked")
  }

  diverging.foreach { case (name, issue) =>
    test(
      s"$name: only the failure description still differs from the reference's reason ($issue)"
    ) {
      val recorded = refused.find(_.name == name).getOrElse(fail(s"no refusal named $name"))
      val encoded = encodedRefusal(recorded.plutus, name)
      assertNotEquals(
        encoded,
        recorded.reason,
        s"$name no longer differs: remove it from the tracked divergences and its issue"
      )
      // What agrees on both sides is the framing before the description and the payload after it;
      // a difference anywhere else would mean a second, untracked divergence in the same refusal.
      // The framing is 18 bytes: the array and constructor tags that open `ConwayApplyTxError`'s
      // single failure down to `ValidationTagMismatch`, then the outcome selector (failed or
      // passed unexpectedly). The selector must agree too - a difference there is a different
      // outcome, not a different description - so the description starts at byte 18 and the
      // divergence must be exactly there.
      val shared =
        encoded.toArray.zip(recorded.reason.toArray).takeWhile { case (a, b) => a == b }.length
      assertEquals(
        shared,
        18,
        s"$name: the divergence starts at byte $shared, not at the description byte 18 opens"
      )
      val ours = payloadFrom(encoded).getOrElse(fail(s"$name: no payload in the encoded refusal"))
      val theirs = payloadFrom(recorded.reason).getOrElse(fail(s"$name: no payload in the reason"))
      assertEquals(ours, theirs, s"$name: the payload after the description differs")
    }
  }
