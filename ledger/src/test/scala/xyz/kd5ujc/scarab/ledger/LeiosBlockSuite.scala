// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.era.IsPhase2Valid
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
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
    assert(LeiosHeader.decode(ten) match {
      case Left(
            ChainError
              .Decode(DecodeField.Header, LedgerDecodeError.ArrayLength(DecodeField.Header, _, _))
          ) =>
        true
      case _ => false
    })
    val good = headers("first")._2
    (1 until good.size by 11).foreach(n =>
      assert(
        LeiosHeader.decode(good.take(n)) match {
          case Left(ChainError.Decode(_, _)) => true
          case _                             => false
        },
        s"$n"
      )
    )
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
    assert(Block.decode(conwayTag, BigInt(21600)) match {
      case Left(
            ChainError
              .Decode(DecodeField.Block, LedgerDecodeError.ArrayLength(DecodeField.Block, _, _))
          ) =>
        true
      case _ => false
    })
  }

  test("a Dijkstra block hashes its whole body, transactions are whole, and the header matches") {
    val txs = block("announcing-with-txs")
    assert(txs.transactions.nonEmpty)
    assertEquals(txs.faults, Vector.empty)
    assertEquals(txs.bodyHash, txs.header.bodyHash)
    assert(txs.transactions.forall(t => DijkstraTx.isValid(t) == IsPhase2Valid.Phase2Valid))
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
    (1 until raw.size by 13).foreach(n =>
      assert(
        LeiosBlock.decode(raw.take(n)) match {
          case Left(ChainError.Decode(_, _)) => true
          case _                             => false
        },
        s"$n"
      )
    )
  }

  test(
    "a transaction its decoder's bounds rule out is the node's defect: the block passes the fetch checks and is withheld"
  ) {
    import xyz.kd5ujc.scarab.codec.Node
    import xyz.kd5ujc.scarab.ledger.era.{EraTxError, TxLevel}
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraBlock, DijkstraBlockError, DijkstraCorpus}
    import xyz.kd5ujc.scarab.ledger.state.VocabError
    val raw = blocks("announcing-with-txs")._2
    val recorded = block("announcing-with-txs")
    val items = DijkstraCorpus.blockItems(raw)
    assertEquals(items.size, recorded.transactions.size)
    // the decoder's bounds make the real path unreachable: a reader that meets such a value at the
    // block's second transaction, and reads the others as the decoder does
    val vocab = VocabError.OutOfRange(VocabError.Kind.Word32, BigInt(-1))
    val cause = EraTxError.Vocab(vocab)
    def failing(at: Int, e: EraTxError)(n: Node): Either[EraTxError, DijkstraTx[TxLevel.Top]] =
      if n.original == items(at) then Left(e) else DijkstraTx.inBlock(n)
    val defective =
      LeiosBlock.decodeWith(raw, failing(1, cause)).fold(e => fail(e.message), identity)
    assertEquals(defective.unread, Vector(LeiosBlock.Unread(1, LeiosBlock.Defect.Vocab(vocab))))
    assertEquals(defective.outsideEra, Vector.empty)
    assertEquals(defective.transactions, recorded.transactions.patch(1, Nil, 1))
    assertEquals(defective.transactionCount, recorded.transactions.size)
    assertEquals(defective.faults, recorded.faults)
    // the fetch checks pass it, so no peer is charged (`FetchedBlockIntegrity`)
    val announced = recorded.header.dijkstraView
    assertEquals(
      BlockTransport.matches(announced, Block.Dijkstra(defective)),
      Right(Block.Dijkstra(defective))
    )
    // and its application in the era's types meets the defect, which the follower withholds the
    // block for (`MirrorDijkstra`: `DijkstraBlockDefect.Projection`)
    assertEquals(
      DijkstraBlock.decodeWith(raw, failing(1, cause)).map(_ => ()),
      Left(DijkstraBlockError.Projection(cause))
    )
    // a refusal of the decoder at the same transaction stays the block's, the peer's fault
    val refusal = LedgerDecodeError.Empty(DecodeField.Certificates)
    assertEquals(
      LeiosBlock.decodeWith(raw, failing(1, EraTxError.Decode(refusal))).map(_ => ()),
      Left(ChainError.Decode(DecodeField.Header, refusal))
    )
  }

  test(
    "a transaction with a field Dijkstra does not have is the bytes' fault: the block passes the fetch checks and its application refuses it"
  ) {
    import xyz.kd5ujc.scarab.codec.Node
    import xyz.kd5ujc.scarab.ledger.era.{EraField, EraTxError, TxLevel}
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraBlock, DijkstraBlockError, DijkstraCorpus}
    val raw = blocks("announcing-with-txs")._2
    val recorded = block("announcing-with-txs")
    val items = DijkstraCorpus.blockItems(raw)
    // as the projection refused it before the one pass: the block decoder accepts the bytes, and
    // the era's types refuse them when the block is applied (`DijkstraBlockError.Era`, the path of
    // an invalid block), not the node's defect (`Projection`)
    val cause = EraTxError.NotInEra(EraField.Update)
    def failing(n: Node): Either[EraTxError, DijkstraTx[TxLevel.Top]] =
      if n.original == items(1) then Left(cause) else DijkstraTx.inBlock(n)
    val refused = LeiosBlock.decodeWith(raw, failing).fold(e => fail(e.message), identity)
    assertEquals(refused.outsideEra, Vector(LeiosBlock.OutsideEra(1, EraField.Update)))
    assertEquals(refused.unread, Vector.empty)
    assertEquals(refused.transactionCount, recorded.transactions.size)
    assertEquals(
      BlockTransport.matches(recorded.header.dijkstraView, Block.Dijkstra(refused)),
      Right(Block.Dijkstra(refused))
    )
    assertEquals(
      DijkstraBlock.decodeWith(raw, failing).map(_ => ()),
      Left(DijkstraBlockError.Era(cause))
    )
  }

  test("the block decoder reads no transaction after the first its decoder refuses") {
    import xyz.kd5ujc.scarab.codec.Node
    import xyz.kd5ujc.scarab.ledger.era.{EraTxError, TxLevel}
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus
    val raw = blocks("announcing-with-txs")._2
    val items = DijkstraCorpus.blockItems(raw)
    assert(items.size > 3)
    val refusal = LedgerDecodeError.Empty(DecodeField.Certificates)
    // the refusal at the second item ends the reading: a later item given to the reader fails
    val later = items.drop(2).toSet
    assert(!later.contains(items(0)) && !later.contains(items(1)))
    def reader(n: Node): Either[EraTxError, DijkstraTx[TxLevel.Top]] =
      if later.contains(n.original) then fail("an item after the refusal was read")
      else if n.original == items(1) then Left(EraTxError.Decode(refusal))
      else DijkstraTx.inBlock(n)
    assertEquals(
      LeiosBlock.decodeWith(raw, reader).map(_ => ()),
      Left(ChainError.Decode(DecodeField.Header, refusal))
    )
  }
end LeiosBlockSuite
