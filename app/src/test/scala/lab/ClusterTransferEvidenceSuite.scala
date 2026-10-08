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
        "signed-transaction-cbor.md",
        "pre-tips.md",
        "post-tips.md",
        "pre-ledger-state.md",
        "post-ledger-state.md"
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
        "binding" -> "atomic",
        "networkMagic" -> "1082027",
        "feePerByte" -> "0",
        "feeFixed" -> "1",
        "maxTxSize" -> "100000",
        "preSlot" -> (load.context.preSlot + 1).toString,
        "preEpoch" -> (load.context.epoch + 1).toString,
        "preHash" -> ("11" * 32),
        "postTipsSha256" -> ("00" * 32),
        "preLedgerSha256" -> ("00" * 32)
      )
    do
      test("reject context mutation: " + field) {
        changed(field, value)(p =>
          intercept[IllegalArgumentException](ClusterTransferCommand.load(p))
        )
      }
    test("reject actual fee-pot observation mutation") {
      changed("feesAfter", "200001") { p =>
        intercept[IllegalArgumentException](ClusterTransferCommand.load(p))
      }
    }
    for (name, digestKey, oldValue, replacement) <- Vector(
        (
          "pre-parameters.md",
          "parametersSha256",
          "\"txFeePerByte\": 1",
          "\"txFeePerByte\": 1e0"
        ),
        (
          "pre-parameters.md",
          "parametersSha256",
          "\"txFeePerByte\": 1",
          "\"txFeePerByte\": 1, \"txFeePerByte\": 1"
        ),
        (
          "pre-parameters.md",
          "parametersSha256",
          "\"txFeePerByte\": 1",
          "\"txFeePerByte\": 18446744073709551616"
        ),
        ("pre-ledger-state.md", "preLedgerSha256", "\"fees\": 0", "\"fees\": -0"),
        (
          "pre-tips.md",
          "preTipsSha256",
          s"\"slot\": ${load.context.preSlot}",
          s"\"slot\": ${load.context.preSlot + 1}"
        )
      )
    do
      test("reject rehashed JSON source mutation: " + name + " " + replacement) {
        changed("format", ClusterTransferCommand.ContextFormat) { p =>
          val original = Files.readString(p.resolve(name))
          assert(original.contains(oldValue))
          val mutated = original.replaceFirst(
            java.util.regex.Pattern.quote(oldValue),
            java.util.regex.Matcher.quoteReplacement(replacement)
          )
          Files.writeString(p.resolve(name), mutated)
          if name == "pre-parameters.md" then
            Files.writeString(p.resolve("post-parameters.md"), mutated)
          val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(mutated.getBytes("UTF-8"))
            .map(b => f"${b & 255}%02x")
            .mkString
          val manifest = Files.readString(p.resolve("transfer-context.md"))
          Files.writeString(
            p.resolve("transfer-context.md"),
            manifest.linesIterator
              .map { line =>
                if line.startsWith(digestKey + "\t") then digestKey + "\t" + digest else line
              }
              .mkString("\n") + "\n"
          )
          intercept[IllegalArgumentException](ClusterTransferCommand.load(p))
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
