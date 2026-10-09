// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosCertificateState.Point
import lab.submission.AdmissionProfile
import ReferenceJson.Json as J

/** In-memory synthetic mutations of independently pinned retained originals. The changed
  * declarations are test scaffolding, never native acquisition or verifier evidence.
  */
class NativeFundedBootstrapSuite extends NativeLedgerV2AuditedFixture:
  private final case class Packet(originals: Map[String, Bytes], point: Point):
    def pins: Map[String, Bytes] = originals.map((name, raw) => name -> sha(raw))
    def decode(profile: AdmissionProfile) =
      NativeLedgerV2.decode(originals, pins, point, profile)

  // Only changed ancestors receive new array framing. Untouched native child spans,
  // including indefinite historical parameter arrays, remain byte-for-byte originals.
  private def encoded(node: Node): Bytes =
    if node.original.size > 0 then node.original
    else
      node.value match
        case V.Arr(items) =>
          require(items.size < 24, "bounded test mutation ancestor width")
          Bytes(Vector((0x80 + items.size).toByte) ++ items.flatMap(child => encoded(child).value))
        case _ => get(Cbor.encode(node.value))
  private def arr(node: Node): Vector[Node] = node.value match
    case V.Arr(items) => items
    case _            => fail("test array required")
  private def at(node: Node, path: List[Int]): Node = path match
    case Nil          => node
    case head :: tail => at(arr(node)(head), tail)
  private def replace(node: Node, path: List[Int], value: Node): Node = path match
    case Nil => value
    case head :: tail =>
      Node(V.Arr(arr(node).updated(head, replace(arr(node)(head), tail, value))), Bytes.empty)
  private def number(value: BigInt): Node = Node(V.UInt(value), Bytes.empty)
  private def json(fields: Map[String, J]): Bytes = SyntheticRewardProjection.encode(J.Obj(fields))
  private def changed(base: NativeLedgerV2.Checked, fee: BigInt, conserved: Boolean): Packet =
    val old = base.ledger.epochComponents.originals ++ base.acquisition.originals
    val decode = (raw: Bytes) => get(Cbor.decode(raw, Cbor.Limits(524288, 48, 100000, 524288)))
    val seed0 = decode(old("derived-full-epoch-seed.cbor"))
    val debug0 = decode(old("original-debug-epoch.cbor"))
    val reservePath = List(3, 0, 1)
    val feePath = List(3, 1, 1, 2)
    val reserves = at(seed0, reservePath).value match
      case V.UInt(value) => value
      case _             => fail("test reserves")
    def mutate(node: Node): Node =
      val fees = replace(node, feePath, number(fee))
      if conserved then replace(fees, reservePath, number(reserves - fee)) else fees
    val seedNode = mutate(seed0)
    val seed = encoded(seedNode)
    val debug = encoded(mutate(debug0))
    // Fee/reserve mutations must not alter original governance or MemPack subtrees.
    for path <- Vector(List(3, 1, 1, 3), List(3, 1, 1, 0)) do
      assertEquals(at(decode(seed), path).original, at(seed0, path).original)
      assertEquals(at(decode(debug), path).original, at(debug0, path).original)
    val capture = json(
      obj(ReferenceJson.parse(old("capture.json"))).updated("epochHex", J.Str(debug.hex))
    )
    val verifier = json(
      obj(ReferenceJson.parse(old("native-verification.json"))) ++ Map(
        "epochInputSHA256" -> J.Str(sha(debug).hex),
        "derivedSeedHex" -> J.Str(seed.hex)
      )
    )
    val receipt = json(
      obj(ReferenceJson.parse(old("receipt.json"))) ++ Map(
        "captureSHA256" -> J.Str(sha(capture).hex),
        "epochSHA256" -> J.Str(sha(debug).hex),
        "derivedSeedSHA256" -> J.Str(sha(seed).hex)
      )
    )
    val projection0 = obj(ReferenceJson.parse(old("native-projection.json")))
    val components = obj(projection0("components"))
    def component(name: String, path: List[Int]): (String, J) =
      val raw = encoded(at(seedNode, path))
      name -> J.Obj(
        obj(components(name)) ++ Map(
          "cborHex" -> J.Str(raw.hex),
          "sha256" -> J.Str(sha(raw).hex)
        )
      )
    val projection = json(
      projection0 ++ Map(
        "seedSHA256" -> J.Str(sha(seed).hex),
        "components" -> J.Obj(
          components ++ Map(component("fees", feePath), component("chainAccountState", List(3, 0)))
        )
      )
    )
    Packet(
      old ++ Map(
        "derived-full-epoch-seed.cbor" -> seed,
        "original-debug-epoch.cbor" -> debug,
        "capture.json" -> capture,
        "native-verification.json" -> verifier,
        "receipt.json" -> receipt,
        "native-projection.json" -> projection
      ),
      base.point
    )

  test("null profile cannot enable funded bootstrap") {
    assert(NativeLedgerV2.decode(null, null, null, null).isLeft)
  }

  private def baseline(directory: String): NativeLedgerV2.Checked =
    NativeLiveBoundaryMain.initial(
      Path.of(directory),
      sys.env.getOrElse(
        "NATIVE_FUNDED_BOOTSTRAP_MANIFEST_SHA256",
        fail("independent baseline manifest pin required")
      )
    )

  sys.env.get("NATIVE_FUNDED_BOOTSTRAP_BUNDLE").foreach { directory =>
    test("actual v2 decoder accepts conserved funded fees only with explicit native profile") {
      val base = baseline(directory)
      val packet = changed(base, 200000, conserved = true)
      val joined = get(packet.decode(AdmissionProfile.NativeScript))
      val epoch = joined.ledger.epochComponents
      assertEquals(epoch.pots.fees, BigInt(200000))
      assertEquals(epoch.stake.fees, BigInt(200000))
      assertEquals(
        epoch.utxoCoin + epoch.pots.reserves + epoch.pots.fees,
        joined.ledger.globals.maxLovelaceSupply
      )
      assertEquals(
        joined.acquisition.originals("derived-full-epoch-seed.cbor"),
        packet.originals("derived-full-epoch-seed.cbor")
      )
      assert(!joined.fullLedgerValidated && !joined.runtimeImport && !joined.nativeConformance)
      val defaultResult = NativeLedgerV2.decode(packet.originals, packet.pins, packet.point)
      assert(defaultResult.left.exists(_.contains("unsupported nonzero")))
      assert(packet.decode(AdmissionProfile.AdaVkey).left.exists(_.contains("unsupported nonzero")))
    }
    test("actual native v2 decoder rejects fees without conserved complete supply") {
      val packet = changed(baseline(directory), 200000, conserved = false)
      assert(
        packet
          .decode(AdmissionProfile.NativeScript)
          .left
          .exists(_.contains("independent coin supply mismatch"))
      )
    }
    test("zero-fee default identities remain stable and native profile identities are separated") {
      val base = baseline(directory)
      val originals = base.ledger.epochComponents.originals ++ base.acquisition.originals
      val packet = Packet(originals, base.point)
      assertEquals(get(packet.decode(AdmissionProfile.AdaVkey)).id, base.id)
      val native = get(packet.decode(AdmissionProfile.NativeScript))
      assertNotEquals(native.ledger.epochComponents.id, base.ledger.epochComponents.id)
      assertNotEquals(native.id, base.id)
      assertEquals(native.acquisition.originals, base.acquisition.originals)
    }
    test("actual initial entry point preserves default rejection and explicit native fee state") {
      val packet = changed(baseline(directory), 200000, conserved = true)
      val manifest = json(
        Map(
          "schema" -> J.Str("native-ledger-v2-reviewed-inputs-v1"),
          "point" -> NativeLiveBoundaryMain.point(packet.point),
          "inputs" -> J.Obj(packet.originals.map { (name, raw) =>
            name -> J.Obj(Map("sha256" -> J.Str(sha(raw).hex), "bytes" -> J.Num(raw.size.toString)))
          })
        )
      )
      IO.blocking(Files.createTempDirectory("synthetic-funded-bootstrap-"))
        .bracket { root =>
          IO.blocking {
            packet.originals.foreach((name, raw) => Files.write(root.resolve(name), raw.toArray))
            Files.write(root.resolve("adapter-inputs.json"), manifest.toArray)
            val pin = sha(manifest).hex
            intercept[IllegalArgumentException](NativeLiveBoundaryMain.initial(root, pin))
            val joined = NativeLiveBoundaryMain.initial(root, pin, AdmissionProfile.NativeScript)
            assertEquals(joined.ledger.epochComponents.pots.fees, BigInt(200000))
          }
        } { root =>
          IO.blocking {
            (packet.originals.keySet + "adapter-inputs.json")
              .foreach(name => Files.deleteIfExists(root.resolve(name)))
            Files.delete(root)
          }
        }
        .unsafeToFuture()
    }
  }
