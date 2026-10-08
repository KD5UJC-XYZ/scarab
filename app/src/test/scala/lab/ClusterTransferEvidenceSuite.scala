// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.ledger.ClusterTransfer

/** Opt-in public-byte regressions; no private cluster evidence is checked into Git. */
class ClusterTransferEvidenceSuite extends munit.FunSuite:
  sys.env.get("CLUSTER_TRANSFER_EVIDENCE").foreach { directory =>
    val dir = Path.of(directory)
    def load = ClusterTransferCommand.load(dir)
    def changed(field: String, value: String)(check: Path => Unit): Unit =
      val tmp = Files.createTempDirectory("transfer-context-test")
      val names = Vector(
        "transfer-context.md",
        "transfer-genesis.md",
        "pre-parameters.md",
        "post-parameters.md",
        "pre-utxo-cbor.md",
        "post-utxo-cbor.md",
        "signed-transaction-cbor.md"
      )
      try
        names.foreach(n => Files.copy(dir.resolve(n), tmp.resolve(n)))
        val manifest = Files.readString(tmp.resolve("transfer-context.md"))
        Files.writeString(
          tmp.resolve("transfer-context.md"),
          manifest.linesIterator
            .map { line =>
              if line.startsWith(field + "\t") then field + "\t" + value else line
            }
            .mkString("\n") + "\n"
        )
        check(tmp)
      finally
        names.foreach(n => Files.deleteIfExists(tmp.resolve(n)))
        Files.deleteIfExists(tmp)
    test("live original state transition is accepted") {
      val in = load
      val r = ClusterTransfer.compare(in.context, in.pre, in.post, in.tx).toOption.get
      assertEquals(r.fee, BigInt(200000))
      assertEquals(r.observedFeePotDelta, BigInt(200000))
      assert(!r.fullLedgerValidated && !r.referenceSnapshotAtomic)
    }
    for (field, value) <- Vector(
        "major" -> "11",
        "postEpoch" -> "999",
        "postSlot" -> "0",
        "genesisSha256" -> ("00" * 32),
        "parametersSha256" -> ("00" * 32),
        "binding" -> "atomic"
      )
    do
      test("reject context mutation: " + field) {
        changed(field, value)(p =>
          intercept[IllegalArgumentException](ClusterTransferCommand.load(p))
        )
      }
    test("reject actual fee-pot observation mutation") {
      changed("feesAfter", "200001") { p =>
        val in = ClusterTransferCommand.load(p)
        assert(ClusterTransfer.compare(in.context, in.pre, in.post, in.tx).isLeft)
      }
    }
    test("reject missing UTxO transition and truncated signed transaction") {
      val in = load
      assert(ClusterTransfer.compare(in.context, in.pre, in.pre, in.tx).isLeft)
      assert(
        ClusterTransfer.compare(in.context, in.pre, in.post, Bytes(in.tx.value.dropRight(1))).isLeft
      )
    }
    test("reject changed observed UTxO bytes") {
      val in = load
      val raw = in.post.value.updated(in.post.size - 1, (in.post.value.last ^ 1).toByte)
      assert(ClusterTransfer.compare(in.context, in.pre, Bytes(raw), in.tx).isLeft)
    }
    test("reject mutated original witness signature") {
      val in = load
      val raw = in.tx.value.updated(in.tx.size - 4, (in.tx.value(in.tx.size - 4) ^ 1).toByte)
      assert(ClusterTransfer.compare(in.context, in.pre, in.post, Bytes(raw)).isLeft)
    }
    test("original inclusion bytes accepted; missing or duplicated inclusion rejected") {
      val in = load
      val pattern = "\"rawBlockHex\":\"([0-9a-f]+)\"".r
      val blocks = pattern
        .findAllMatchIn(Files.readString(dir.resolve("scala-transfer.md")))
        .map(m => Bytes.fromHex(m.group(1)).toOption.get)
        .toVector
      assert(blocks.nonEmpty)
      ClusterTransferCommand.checkInclusion(in.tx, blocks)
      intercept[IllegalArgumentException](
        ClusterTransferCommand.checkInclusion(in.tx, Vector.empty)
      )
      intercept[IllegalArgumentException](
        ClusterTransferCommand.checkInclusion(in.tx, blocks ++ blocks)
      )
    }
  }
