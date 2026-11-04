// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.{Era, HfcHeader}

/** Headers and blocks captured from the public Leios testnet (`fixtures/leios/testnet`, provenance
  * in `fixtures/leios/README.md`): the 12-element header, the Dijkstra block and its certificate.
  * The blocks of `blocks.tsv` are of the network before its restart of 2026-10-18; its blocks of
  * the Conway tag are not read by the current reference.
  */
class LeiosBlockSuite extends munit.FunSuite:
  private val dir = Path.of("fixtures", "leios", "testnet")

  private def lines(file: String): Vector[Array[String]] =
    Files
      .readAllLines(dir.resolve(file))
      .asScala
      .toVector
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map(_.split('\t'))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  /** Headers by label: the envelope is unwrapped to the raw header. */
  private val headers: Map[String, (String, Bytes)] =
    lines("headers-notable.tsv").map { p =>
      val raw = HfcHeader.decode(hex(p(5))) match
        case Right(HfcHeader.Dijkstra(r)) => r
        case other                        => fail(other.toString)
      p(0) -> (p(4) -> raw)
    }.toMap

  /** Blocks by label: `label, kind, block no, slot, hash, block (hex)`. */
  private val blocks: Map[String, (String, Bytes)] =
    lines("blocks-rc-2026w44.tsv").map(p => p(0) -> (p(4) -> hex(p(5)))).toMap

  private def block(label: String): LeiosBlock =
    LeiosBlock.decode(blocks(label)._2).fold(e => fail(s"$label: ${e.message}"), identity)

  private def header(label: String): LeiosHeader =
    LeiosHeader.decode(headers(label)._2).fold(e => fail(e.message), identity)

  test("a header decodes to the fields of its CDDL and hashes to the block hash") {
    headers.foreach { case (label, (hash, raw)) =>
      val h = header(label)
      assertEquals(h.hash.hex, hash, label)
      assertEquals(h.raw, raw, label)
      assertEquals(Blake2b.hash256.hash(raw), h.hash, label)
    }
    val first = header("first")
    assertEquals(first.blockNo, BigInt(0))
    assertEquals(first.slot, BigInt(16))
    assertEquals(first.prevHash, None)
    assertEquals(first.leios.versionInfo, HeaderVersionInfo(12, 0))
    assertEquals(first.leios.blockBodyContainsLeiosCert, false)
    assertEquals(first.leios.announcement, None)
    assertEquals(first.bodySize, BigInt(4))
  }

  test("the announcing headers name an endorser block and its size; the certifying ones say so") {
    val announcing = header("announcing")
    assertEquals(announcing.blockNo, BigInt(64849))
    assert(announcing.leios.announcement.isDefined)
    assertEquals(announcing.leios.blockBodyContainsLeiosCert, false)
    Vector("certifying", "certifying-2").foreach { label =>
      val certifying = header(label)
      assertEquals(certifying.leios.blockBodyContainsLeiosCert, true, label)
      assert(certifying.leios.announcement.isDefined, label)
    }
  }

  test("the Praos view of a header is what the Praos rules read, tagged Conway") {
    val h = header("first")
    val v = h.praosView
    assertEquals(v.era, Era.Conway)
    assertEquals(v.hash, h.hash)
    assertEquals(v.signable, h.signable)
    assertEquals(v.protocolVersion, (BigInt(12), BigInt(0)))
    assertEquals(v.vrf, HeaderVrf.Praos(h.vrf))
    assertEquals(h.point.toString.contains("16"), true)
  }

  test("a header of the Conway layout is not a Leios header, and a truncated one is refused") {
    val conway = Bytes
      .fromHex(Files.readAllLines(Path.of("fixtures", "preview", "conway.hex")).get(0).trim)
      .fold(e => fail(e.toString), identity)
    val ten = conwayHeader(conway)
    assert(Header.fromRaw(Era.Conway, ten).isRight)
    assert(LeiosHeader.decode(ten).isLeft)
    val good = headers("first")._2
    (1 until good.size by 11).foreach(n => assert(LeiosHeader.decode(good.take(n)).isLeft, s"$n"))
  }

  private def conwayHeader(block: Bytes): Bytes =
    import xyz.kd5ujc.scarab.codec.{Cbor, Value}
    val root = CborRead
      .decode(block, Cbor.Limits(2500000, 2048, 1000000, 2500000))
      .toOption
      .get
    val Value.Arr(Vector(_, body)) = root.value: @unchecked
    val Value.Arr(parts) = body.value: @unchecked
    parts.head.original

  test("a block of the Conway tag is not a Leios block, nor a Conway one with this header") {
    // tag 7 is `ShelleyBlock (Praos c) ConwayEra` in the testnet's build (ouroboros-consensus
    // 2416493b, `Cardano/Block.hs:269-278`): a Praos header of 10 fields, not the Leios header
    val raw = blocks("block-0")._2
    val conwayTag = raw.take(1) ++ Bytes(Vector(0x07.toByte)) ++ raw.drop(2)
    assert(LeiosBlock.decode(conwayTag).left.exists {
      case ChainError.Decode(_, LedgerDecodeError.UnknownTag(DecodeField.LeiosBlockTag, t)) =>
        t == 7
      case _ => false
    })
    assert(Block.decode(conwayTag, BigInt(21600)).isLeft)
  }

  test("a Dijkstra block hashes its whole body, transactions are whole, and the header matches") {
    val txs = block("announcing-with-txs")
    assert(txs.transactions.nonEmpty)
    assertEquals(txs.faults, Vector.empty)
    assertEquals(txs.bodyHash, txs.header.bodyHash)
    assert(txs.transactions.forall(_.isValid))
    val empty = block("block-0")
    assertEquals(empty.bodySize, 4L) // [[], null, null] = 0x83 0x80 0xf6 0xf6
    assertEquals(empty.faults, Vector.empty)
  }

  test("a block that announces an endorser block carries transactions and no certificate") {
    val b = block("announcing-with-txs")
    assert(b.header.leios.announcement.isDefined)
    assert(b.transactions.nonEmpty)
    assertEquals(b.leiosCertificate, None)
    assertEquals(b.faults, Vector.empty)
  }

  test("a certifying block carries a strictly decoded certificate and no transactions") {
    val b = block("certifying")
    val cert = b.leiosCertificate.getOrElse(fail("no certificate"))
    assertEquals(b.header.leios.blockBodyContainsLeiosCert, true)
    assertEquals(b.transactions, Vector.empty)
    assertEquals(b.faults, Vector.empty)
    assertEquals(cert.certificate.encode, cert.raw)
    assertEquals(b.perasCertificate, None)
  }

  test("every block fault is reported on its own case") {
    val cert = block("certifying")
    val txs = block("announcing-with-txs")
    // a certificate and transactions together
    assertEquals(
      cert.copy(transactions = txs.transactions).faults,
      Vector(LeiosBlock.Fault.CertificateWithTransactions(txs.transactions.size))
    )
    // the flag does not agree with the body
    assertEquals(
      cert.copy(leiosCertificate = None).faults,
      Vector(LeiosBlock.Fault.CertificateFlagDiffers(declared = true, present = false))
    )
    // another body: the digest differs (and the size, as the bodies differ in length)
    val other = txs.copy(header = txs.header.copy(bodyHash = cert.header.bodyHash))
    assert(other.faults.contains(LeiosBlock.Fault.BodyHashDiffers))
    val sized = txs.copy(bodySize = txs.bodySize + 1)
    assertEquals(
      sized.faults,
      Vector(LeiosBlock.Fault.BodySizeDiffers(txs.bodySize + 1, txs.header.bodySize))
    )
  }

  test("a changed byte in the body is caught by the body hash, one in the header by its hash") {
    val raw = blocks("announcing-with-txs")._2
    val decoded = block("announcing-with-txs")
    val bodyStart = raw.size - decoded.bodySize.toInt
    // inside the body, in the middle of a transaction
    val i = bodyStart + decoded.bodySize.toInt / 2
    val flipped = raw.take(i) ++ Bytes(Vector((raw.toArray(i) ^ 1).toByte)) ++ raw.drop(i + 1)
    LeiosBlock.decode(flipped) match
      case Left(_) => ()
      case Right(b) =>
        assert(b.faults.contains(LeiosBlock.Fault.BodyHashDiffers), b.faults.toString)
    // inside the header: another header hash, the same body
    val j = 40
    val other = raw.take(j) ++ Bytes(Vector((raw.toArray(j) ^ 1).toByte)) ++ raw.drop(j + 1)
    LeiosBlock.decode(other).foreach(b => assertNotEquals(b.header.hash, decoded.header.hash))
  }

  test("a block of another tag or with a malformed certificate is refused with a reason") {
    val raw = blocks("certifying")._2
    // tag 8 -> 9
    val otherTag = raw.take(1) ++ Bytes(Vector(0x09.toByte)) ++ raw.drop(2)
    assert(LeiosBlock.decode(otherTag).left.exists {
      case ChainError.Decode(_, LedgerDecodeError.UnknownTag(DecodeField.LeiosBlockTag, _)) => true
      case _                                                                                => false
    })
    (1 until raw.size by 13).foreach(n => assert(LeiosBlock.decode(raw.take(n)).isLeft, s"$n"))
  }
end LeiosBlockSuite
