// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import ReferenceJson.{Json as J, field, string, uint}

/** Registered only when an independently pinned fresh-capture manifest is explicitly supplied. */
trait NativeLedgerV2AuditedFixture extends munit.FunSuite:
  protected def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  protected def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  protected def read(path: Path, limit: Int): Bytes =
    val stream = Files.newInputStream(path)
    val raw =
      try stream.readNBytes(limit + 1)
      finally stream.close()
    assert(raw.length <= limit)
    Bytes.fromArray(raw)
  protected def obj(j: J) = j match
    case J.Obj(m) => m
    case _        => fail("fresh input manifest object required")
  protected def hash(j: J): Bytes =
    val text = string(j)
    assert(text.matches("[0-9a-f]{64}"))
    get(Bytes.fromHex(text))

  protected def loadFresh(directory: String): NativeLedgerV2.Checked =
    val root = Path.of(directory)
    val manifest = read(root.resolve("adapter-inputs.json"), 16384)
    assertEquals(
      sha(manifest).hex,
      sys.env.getOrElse(
        "NATIVE_LEDGER_V2_MANIFEST_SHA256",
        fail("external fresh manifest pin required")
      )
    )
    val descriptor = ReferenceJson.parse(manifest)
    assertEquals(obj(descriptor).keySet, Set("schema", "point", "inputs"))
    assertEquals(string(field(descriptor, "schema")), "native-ledger-v2-reviewed-inputs-v1")
    val point = field(descriptor, "point")
    assertEquals(obj(point).keySet, Set("hash", "slot", "blockNo"))
    val anchor =
      Point(hash(field(point, "hash")), uint(field(point, "slot")), uint(field(point, "blockNo")))
    assert(anchor.slot < 300)
    val inputs = obj(field(descriptor, "inputs"))
    assertEquals(inputs.keySet, NativeLedgerV2.InputNames)
    val originals = inputs.map { (name, description) =>
      assertEquals(obj(description).keySet, Set("sha256", "bytes"))
      val raw = read(root.resolve(name), 524288)
      assertEquals(BigInt(raw.size), uint(field(description, "bytes")), name)
      assertEquals(sha(raw), hash(field(description, "sha256")), name)
      name -> raw
    }
    val pins = inputs.map((name, description) => name -> hash(field(description, "sha256")))
    val joined = get(NativeLedgerV2.decode(originals, pins, anchor))
    assertEquals(joined.point, anchor)
    println(s"NATIVE_V2_JOIN_ID=${joined.id.hex}")
    joined

class NativeLedgerV2AuditedSuite extends NativeLedgerV2AuditedFixture:
  sys.env.get("NATIVE_LEDGER_V2_BUNDLE").foreach { directory =>
    test("independently pinned fresh v2 originals join without granting runtime admission") {
      val joined = loadFresh(directory)
      assertEquals(joined.ledger.globals.epoch, BigInt(0))
      assertEquals(joined.ledger.globals.geometry.epochLength, BigInt(1000))
      assertEquals(joined.ledger.globals.randomnessStabilisationWindow, BigInt(400))
      assertEquals(joined.ledger.epochComponents.reward.original.hex, "80")
      assertEquals(
        joined.acquisition.originals,
        joined.protocol.originals
      )
      assertEquals(joined.protocol.originals, joined.acquisition.originals)
      assertEquals(joined.crossingBlockers, Set.empty[NativeLedgerSeed.Blocker])
      assert(NativeLedgerV2.checkCrossingGeometry(joined).isRight)
      assert(
        !joined.runtimeReady && !joined.runtimeImport && !joined.rewardSeedAdmission &&
          !joined.authenticatedSnapshot && !joined.actualAcquisitionVerified &&
          !joined.fullLedgerValidated && !joined.nativeConformance
      )
    }
  }
