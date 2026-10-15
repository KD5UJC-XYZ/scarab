// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import scala.annotation.unused
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{Block, Header, HeaderVrf}
import scala.jdk.CollectionConverters.*

/** Fixture access shared by the equivalence suites. */
object Fixtures:
  def hexLines(path: String): Vector[Bytes] =
    Files.readAllLines(Path.of(path)).asScala.toVector.filter(_.nonEmpty).map { l =>
      Bytes.fromHex(l.trim).fold(e => throw new IllegalStateException(s"$path: $e"), identity)
    }

  /** The exact header bytes of a fetched block `[tag, [header, ...]]`. */
  def headerBytes(block: Bytes): Bytes =
    val root = Cbor.decode(block, Cbor.Limits(2500000, 2048, 1000000, 2500000)).toOption.get
    val Value.Arr(Vector(_, body)) = root.value: @unchecked
    val Value.Arr(parts) = body.value: @unchecked
    parts.head.original

/** What every era's generated header exposes, read out for comparison with the hand-written
  * decoder.
  */
final case class HeaderView(
    blockNo: BigInt,
    slot: BigInt,
    prevHash: Option[Bytes],
    issuer: Bytes,
    vrfVkey: Bytes,
    vrf: Vector[(Bytes, Bytes)],
    bodySize: BigInt,
    bodyHash: Bytes,
    opCert: (Bytes, BigInt, BigInt, Bytes),
    protocolVersion: (BigInt, BigInt),
    kesSignature: Bytes,
    raw: Bytes,
    bodyRaw: Bytes
)

object HeaderView:
  @unused private def sized(h: Option[conway.Hash32]): Option[Bytes] = h.map(_.value)

  def ofConway(h: conway.Header): HeaderView =
    val b = h.headerBody
    HeaderView(
      b.blockNumber.value,
      b.slot.value,
      b.prevHash.map(_.value),
      b.issuerVkey.value,
      b.vrfVkey.value,
      Vector((b.vrfResult.output, b.vrfResult.proof)),
      BigInt(b.blockBodySize),
      b.blockBodyHash.value,
      (
        b.operationalCert.hotVkey.value,
        b.operationalCert.sequenceNumber.value,
        b.operationalCert.kesPeriod.value,
        b.operationalCert.sigma.value
      ),
      (BigInt(b.protocolVersion.major.value), BigInt(b.protocolVersion.minor)),
      h.bodySignature.value,
      h.raw,
      b.raw
    )

  def ofBabbage(h: babbage.Header): HeaderView =
    val b = h.headerBody
    HeaderView(
      b.blockNumber.value,
      b.slot.value,
      b.prevHash.map(_.value),
      b.issuerVkey.value,
      b.vrfVkey.value,
      Vector((b.vrfResult.output, b.vrfResult.proof)),
      BigInt(b.blockBodySize),
      b.blockBodyHash.value,
      (
        b.operationalCert.hotVkey.value,
        b.operationalCert.sequenceNumber.value,
        b.operationalCert.kesPeriod.value,
        b.operationalCert.sigma.value
      ),
      (BigInt(b.protocolVersion.major.value), BigInt(b.protocolVersion.minor)),
      h.bodySignature.value,
      h.raw,
      b.raw
    )

  def ofAlonzo(h: alonzo.Header): HeaderView =
    val b = h.headerBody
    HeaderView(
      b.blockNumber.value,
      b.slot.value,
      b.prevHash.map(_.value),
      b.issuerVkey.value,
      b.vrfVkey.value,
      Vector((b.nonceVrf.output, b.nonceVrf.proof), (b.leaderVrf.output, b.leaderVrf.proof)),
      BigInt(b.blockBodySize),
      b.blockBodyHash.value,
      (
        b.operationalCert.hotVkey.value,
        b.operationalCert.sequenceNumber.value,
        b.operationalCert.kesPeriod.value,
        b.operationalCert.sigma.value
      ),
      (BigInt(b.protocolVersion.major.value), BigInt(b.protocolVersion.minor)),
      h.bodySignature.value,
      h.raw,
      b.raw
    )

  /** The hand-written decoder's view of the same header. */
  def ofHand(h: Header.Shelley): HeaderView =
    val vrf = h.vrf match
      case HeaderVrf.TPraos(n, l) => Vector((n.output, n.proof), (l.output, l.proof))
      case HeaderVrf.Praos(r)     => Vector((r.output, r.proof))
    HeaderView(
      h.blockNo,
      h.slot,
      h.prevHash,
      h.issuerVkey,
      h.vrfVkey,
      vrf,
      h.bodySize,
      h.bodyHash,
      (h.opCert.hotVkey, h.opCert.sequence, h.opCert.kesPeriod, h.opCert.sigma),
      h.protocolVersion,
      h.kesSignature,
      h.raw,
      h.bodyRaw
    )

/** The generated header codecs decode the real Preview chain exactly as the hand-written decoder
  * does.
  */
class GoldenHeaderSuite extends munit.FunSuite:
  import xyz.kd5ujc.scarab.models.runtime.Codec

  private def viewOf(era: Era, raw: Bytes): HeaderView = era match
    case Era.Conway =>
      HeaderView.ofConway(conway.Header.decodeBytes(raw).fold(e => fail(e.render), identity))
    case Era.Babbage =>
      HeaderView.ofBabbage(babbage.Header.decodeBytes(raw).fold(e => fail(e.render), identity))
    case Era.Alonzo =>
      HeaderView.ofAlonzo(alonzo.Header.decodeBytes(raw).fold(e => fail(e.render), identity))
    case other => fail(s"no generated header for $other")

  private def reencode(era: Era, raw: Bytes): Bytes = era match
    case Era.Conway  => conway.Header.encodeBytes(conway.Header.decodeBytes(raw).toOption.get)
    case Era.Babbage => babbage.Header.encodeBytes(babbage.Header.decodeBytes(raw).toOption.get)
    case Era.Alonzo  => alonzo.Header.encodeBytes(alonzo.Header.decodeBytes(raw).toOption.get)
    case other       => fail(s"no generated header for $other")

  /** Canonical re-encoding of the decoded members, bypassing the preserved bytes. */
  private def canonical(era: Era, raw: Bytes): Bytes = era match
    case Era.Conway =>
      val h = conway.Header.decodeBytes(raw).toOption.get
      conway.Header
        .canonical(
          conway.HeaderBody.canonical(
            h.headerBody.blockNumber,
            h.headerBody.slot,
            h.headerBody.prevHash,
            h.headerBody.issuerVkey,
            h.headerBody.vrfVkey,
            h.headerBody.vrfResult,
            h.headerBody.blockBodySize,
            h.headerBody.blockBodyHash,
            h.headerBody.operationalCert,
            h.headerBody.protocolVersion
          ),
          h.bodySignature
        )
        .raw
    case Era.Babbage =>
      val h = babbage.Header.decodeBytes(raw).toOption.get
      val b = h.headerBody
      babbage.Header
        .canonical(
          babbage.HeaderBody.canonical(
            b.blockNumber,
            b.slot,
            b.prevHash,
            b.issuerVkey,
            b.vrfVkey,
            b.vrfResult,
            b.blockBodySize,
            b.blockBodyHash,
            b.operationalCert,
            b.protocolVersion
          ),
          h.bodySignature
        )
        .raw
    case Era.Alonzo =>
      val h = alonzo.Header.decodeBytes(raw).toOption.get
      val b = h.headerBody
      alonzo.Header
        .canonical(
          alonzo.HeaderBody.canonical(
            b.blockNumber,
            b.slot,
            b.prevHash,
            b.issuerVkey,
            b.vrfVkey,
            b.nonceVrf,
            b.leaderVrf,
            b.blockBodySize,
            b.blockBodyHash,
            b.operationalCert,
            b.protocolVersion
          ),
          h.bodySignature
        )
        .raw
    case other => fail(s"no generated header for $other")

  private val files = Vector("alonzo-first-20", "babbage-transition", "conway")

  for file <- files do
    test(
      s"fixtures/preview/$file.hex: generated headers equal the hand-written decode, field by field"
    ) {
      val blocks = Fixtures.hexLines(s"fixtures/preview/$file.hex")
      assert(blocks.nonEmpty)
      blocks.foreach { block =>
        val hand = Block.decodeShelley(block).fold(e => fail(e.message), identity) match
          case b: Block.Shelley => b
          case other            => fail(s"unexpected $other")
        val raw = Fixtures.headerBytes(block)
        assertEquals(raw, hand.header.raw)
        val generated = viewOf(hand.era, raw)
        assertEquals(generated, HeaderView.ofHand(hand.header), s"block ${hand.header.blockNo}")
        // The header hash is the Blake2b-256 of the preserved bytes, the KES message is the preserved body.
        assertEquals(Blake2b.hash256.hash(generated.raw), hand.header.hash)
        assertEquals(generated.bodyRaw, hand.header.bodyRaw)
      }
    }

    test(
      s"fixtures/preview/$file.hex: re-encoding preserves bytes; canonical encoding reproduces them"
    ) {
      Fixtures.hexLines(s"fixtures/preview/$file.hex").foreach { block =>
        val era = Block.decodeShelley(block).toOption.get.era
        val raw = Fixtures.headerBytes(block)
        assertEquals(reencode(era, raw), raw)
        // Real headers are canonical, so building from the members gives the same bytes.
        assertEquals(canonical(era, raw), raw)
      }
    }

  test("a header decoded from a wrong era layout is a typed error with a path and offset") {
    val raw = Fixtures.headerBytes(Fixtures.hexLines("fixtures/preview/conway.hex").head)
    conway.Header.decodeBytes(raw).toOption.get // sanity
    val err = alonzo.Header.decodeBytes(raw).left.toOption.getOrElse(fail("expected an error"))
    assertEquals(err.path.headOption, Some("header_body"))
    assert(err.render.nonEmpty)
    // Truncation is a syntax error, trailing bytes too; neither throws.
    assert(conway.Header.decodeBytes(raw.take(raw.size - 1)).isLeft)
    assert(conway.Header.decodeBytes(raw ++ Bytes(Vector(0.toByte))).isLeft)
  }

  test("range constraints are enforced by the types: protocol major above 12 is rejected") {
    val raw = Fixtures.headerBytes(Fixtures.hexLines("fixtures/preview/conway.hex").head)
    val h = conway.Header.decodeBytes(raw).toOption.get
    val bad = conway.MajorProtocolVersion.from(13)
    assert(bad.isLeft)
    assertEquals(bad.left.toOption.get.typeName, "MajorProtocolVersion")
    assert(conway.MajorProtocolVersion.from(h.headerBody.protocolVersion.major.value).isRight)
    val _ = Codec
  }
