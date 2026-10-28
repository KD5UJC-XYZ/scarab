// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.TestGeometry
import xyz.kd5ujc.scarab.ledger.{Block, EpochGeometry, Header, HeaderVrf}
import java.nio.file.{Files, Path}
import java.time.Instant
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NtNHeader}
import xyz.kd5ujc.scarab.ledger.PoolDistribution
import xyz.kd5ujc.scarab.consensus.{Praos, PraosConfig}
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold.Fraction
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** Decodes real Preview (magic 2) and mainnet Byron blocks committed under `fixtures/`. */
class EraDecodingSuite extends FunSuite:
  /** Byron epoch length of mainnet: 10k slots, k = 2160. */
  private val MainnetEpochSlots: BigInt = BigInt(2160) * 10

  private def hexLines(path: String): Vector[Bytes] =
    Files.readAllLines(Path.of(path)).asScala.toVector.filter(_.nonEmpty).map { l =>
      Bytes.fromHex(l).fold(e => fail(e.toString), identity)
    }

  private def blocks(path: String): Vector[Block.Shelley] =
    hexLines(path).map(Block.decodeShelley(_).fold(e => fail(e.message), identity)).map {
      case b: Block.Shelley => b
      case other            => fail(s"unexpected ${other.era} block")
    }

  /** Header hash, block body commitment and (when contiguous) the prev-hash chain. */
  private def checkBlocks(bs: Vector[Block.Shelley], contiguous: Boolean): Unit =
    bs.foreach { b =>
      assertEquals(b.header.era, b.era)
      assertEquals(b.bodyHash, b.header.bodyHash, s"body hash at block ${b.header.blockNo}")
      assertEquals(BigInt(b.bodySize), b.header.bodySize)
      assertEquals(b.header.kesSignature.size, 448)
    }
    if contiguous then
      bs.zip(bs.drop(1)).foreach { (a, b) =>
        assertEquals(b.header.prevHash, Some(a.header.hash))
        assertEquals(b.header.blockNo, a.header.blockNo + 1)
        assert(b.header.slot > a.header.slot)
      }

  test("first 20 Preview blocks are Alonzo with TPraos headers") {
    val bs = blocks("fixtures/preview/alonzo-first-20.hex")
    assertEquals(bs.size, 20)
    assert(bs.forall(_.era == Era.Alonzo))
    checkBlocks(bs, contiguous = true)
    assertEquals(bs.head.header.prevHash, None)
    assertEquals(bs.head.header.blockNo, BigInt(0))
    assertEquals(bs.map(_.header.protocolVersion).distinct, Vector((BigInt(7), BigInt(2))))
    assert(bs.forall(_.header.vrf.isInstanceOf[HeaderVrf.TPraos]))
    assertEquals(bs.map(_.transactions.size).sum, 2)
    assertEquals(bs(3).transactions.size, 1)
    assert(bs.flatMap(_.transactions).forall(_.isValid))
  }

  test("Alonzo blocks with transactions carry matching body hashes") {
    val bs = blocks("fixtures/preview/alonzo-txs.hex")
    assertEquals(bs.map(_.transactions.size), Vector(1, 1))
    checkBlocks(bs, contiguous = false)
    assertEquals(bs.map(_.header.blockNo), Vector(BigInt(4330), BigInt(8645)))
  }

  test("TPraos to Praos transition: last Alonzo block then Babbage") {
    val bs = blocks("fixtures/preview/babbage-transition.hex")
    checkBlocks(bs, contiguous = true)
    assertEquals(bs.head.era, Era.Alonzo)
    assertEquals(bs.head.header.blockNo, BigInt(13011))
    assert(bs.head.header.vrf.isInstanceOf[HeaderVrf.TPraos])
    assert(bs.tail.forall(_.era == Era.Babbage))
    assert(bs.tail.forall(_.header.vrf.isInstanceOf[HeaderVrf.Praos]))
    assert(bs.tail.forall(_.header.praosVrf.exists(c => c.output.size == 64 && c.proof.size == 80)))
    assertEquals(bs(1).header.protocolVersion._1, BigInt(8))
  }

  test("Conway blocks decode with Praos headers and their transactions") {
    val bs = blocks("fixtures/preview/conway.hex")
    assertEquals(bs.size, 30)
    assert(bs.forall(_.era == Era.Conway))
    checkBlocks(bs, contiguous = true)
    assert(bs.forall(_.header.vrf.isInstanceOf[HeaderVrf.Praos]))
    assert(bs.map(_.transactions.size).sum > 0)
  }

  test("announced headers decode from their NtN envelope identically to the block header") {
    for
      file <- Vector("alonzo-first-20", "babbage-transition", "conway")
      b <- blocks(s"fixtures/preview/$file.hex")
    do
      val wire = NtNHeader.codec
        .encode(NtNHeader(b.era, b.header.raw, None))
        .fold(e => fail(e.toString), identity)
      assertEquals(Header.fromEnvelope(wire, MainnetEpochSlots).toOption, Some(b.header))
  }

  test("an envelope declaring the wrong era layout is a decode error") {
    val b = blocks("fixtures/preview/conway.hex").head
    val wrong = NtNHeader.codec.encode(NtNHeader(Era.Alonzo, b.header.raw, None)).toOption.get
    assert(Header.fromEnvelope(wrong, MainnetEpochSlots).isLeft)
  }

  test("Praos validation rejects TPraos headers with a typed error") {
    val tpraos = blocks("fixtures/preview/alonzo-first-20.hex").head.header
    val geometry =
      EpochGeometry(Instant.EPOCH, 1000, 86400, TestGeometry.k(432), TestGeometry.f("0.05"))
    val config = PraosConfig(geometry, 129600, 62, Fraction.checked(1, 20).toOption.get)
    val state = ProtocolState.initial(Era.Conway, Bytes.empty)
    val result = Praos.update(state, tpraos, PoolDistribution(Map.empty, Map.empty), config)
    assertEquals(result, Left(xyz.kd5ujc.scarab.ledger.PraosError.TPraosHeader(tpraos.era)))
  }

  test("Byron: header hash is Blake2b-256 of [subtag, header]; chain links by it") {
    val ebbRaw = hexLines("fixtures/mainnet/byron-ebb-header.hex").head
    val ebb = Header
      .byronFromRaw(ebbRaw, boundary = true, MainnetEpochSlots)
      .fold(e => fail(e.message), identity)
    assertEquals(
      ebb.hash.hex,
      "89d9b5a5b8ddc8d7e5a6795e9774d97faf1efea59b2caf7eaf9f8c5b32059df4"
    )
    assertEquals((ebb.epoch, ebb.slot, ebb.blockNo), (BigInt(0), BigInt(0), BigInt(0)))
    val mains = hexLines("fixtures/mainnet/byron-main-blocks.hex")
      .map(Block.decode(_, MainnetEpochSlots))
      .map {
        case Right(b: Block.Byron) => b
        case other                 => fail(s"unexpected $other")
      }
    assertEquals(mains.size, 3)
    assert(mains.forall(!_.header.boundary))
    assertEquals(mains.head.header.prevHash, Some(ebb.hash))
    mains.zip(mains.drop(1)).foreach((a, b) => assertEquals(b.header.prevHash, Some(a.header.hash)))
    assertEquals(mains.map(_.header.slot), Vector(BigInt(0), BigInt(1), BigInt(2)))
    assertEquals(mains.map(_.header.blockNo), Vector(BigInt(1), BigInt(2), BigInt(3)))
  }
