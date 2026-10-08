// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.unsafe.implicits.global
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.chain.CardanoBlockEvidence as E

class BlockEvidenceCommandSuite extends munit.FunSuite:
  private val packet = Path.of("fixtures/block-evidence")
  private val file = "original-08.cbor"
  private def raw = Bytes.fromArray(Files.readAllBytes(packet.resolve(s"blocks/$file")))
  private def supplied = Bytes.fromArray(Files.readAllBytes(packet.resolve(s"contexts/$file.tsv")))
  private def bytes(s: String) = Bytes.fromArray(s.getBytes(StandardCharsets.US_ASCII))

  test("bounded context reader and exact parser") {
    BlockEvidenceCommand
      .readContext(packet.resolve(s"contexts/$file.tsv"))
      .map { data =>
        assertEquals(data, supplied)
        val context = BlockEvidenceCommand.parseContext(data).toOption.get
        assertEquals(context.expectedSlot, BigInt(43610414))
        assert(context.nonce.isEmpty)
      }
      .unsafeToFuture()
  }

  test("context parser rejects defaults, duplicate rows, malformed pairs and excess bytes") {
    val text = new String(supplied.toArray, StandardCharsets.US_ASCII)
    val f = text.trim.split("\t", -1).toVector
    Vector(
      "",
      text + text,
      text.trim,
      text.replace("129600", "0129600"),
      f.updated(9, "00" * 32).mkString("\t") + "\n",
      text.replace("129600", "0"),
      f.updated(0, "other-schema").mkString("\t") + "\n",
      "x" * 4097
    ).foreach { s =>
      assert(BlockEvidenceCommand.parseContext(bytes(s)).isLeft, s.take(80))
    }
    assert(BlockEvidenceCommand.parseContext(Bytes(Vector(0xff.toByte))).isLeft)
  }

  test("read-only receipt reports conditional coverage and missing nonce") {
    val context = BlockEvidenceCommand.parseContext(supplied).toOption.get
    val receipt = E.inspect(raw, context).toOption.get
    val rendered = BlockEvidenceCommand.render(receipt)
    Vector(
      "\"coverage\":\"body_opcert_supplied_timing_sum6\"",
      "\"vrf\":\"not_checked_missing_nonce\"",
      "\"messageEvidence\":\"source_profile_shortest_definite_candidate\"",
      "\"referenceSerializerParity\":false",
      "\"authorizedIssuer\":false",
      "\"nonceDerivedFromState\":false",
      "\"ledgerApplied\":false",
      "\"selectedChain\":false",
      receipt.originalBlockSha256.hex,
      receipt.contextDigest.hex,
      receipt.profileHash.hex,
      receipt.receiptId.hex
    ).foreach(s => assert(rendered.contains(s), s))
    assert(rendered.length < 8192)
  }

  test("CLI success means completed partial evidence; conditional KES failure remains exit 1") {
    (for
      positive <- BlockEvidenceCommand.run(
        List(
          packet.resolve(s"blocks/$file").toString,
          packet.resolve(s"contexts/$file.tsv").toString
        )
      )
      negative <- BlockEvidenceCommand.run(
        List(
          packet.resolve("blocks/original-13.cbor").toString,
          packet.resolve("contexts/original-13.cbor.tsv").toString
        )
      )
    yield
      assertEquals(positive.code, 0)
      assertEquals(negative.code, 1)
    ).unsafeToFuture()
  }

  test("usage errors and help") {
    (for
      empty <- BlockEvidenceCommand.run(Nil)
      bad <- BlockEvidenceCommand.run(List("--bad"))
      help <- BlockEvidenceCommand.run(List("--help"))
    yield
      assertEquals(empty.code, 2)
      assertEquals(bad.code, 2)
      assertEquals(help.code, 0)
    ).unsafeToFuture()
  }

  test("typed CLI exit mapping preserves unsupported versus predicate versus implementation") {
    assertEquals(
      BlockEvidenceCommand.errorCode(E.Failure.Unsupported(E.Stage.Index, "test")).code,
      3
    )
    assertEquals(BlockEvidenceCommand.errorCode(E.Failure.Rejected(E.Stage.Timing, "test")).code, 1)
    assertEquals(BlockEvidenceCommand.errorCode(E.Failure.Malformed(E.Stage.Input, "test")).code, 2)
    assertEquals(
      BlockEvidenceCommand.errorCode(E.Failure.ResourceLimit(E.Stage.Input, "test")).code,
      2
    )
    assertEquals(
      BlockEvidenceCommand.errorCode(E.Failure.InternalFailure(E.Stage.Input, "test")).code,
      4
    )
  }
