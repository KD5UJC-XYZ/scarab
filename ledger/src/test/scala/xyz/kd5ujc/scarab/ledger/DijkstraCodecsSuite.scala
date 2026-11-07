// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraLevel, DijkstraTx}
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, Era}

/** The block, header and transaction readers of the node on the Conway to Dijkstra chain of the
  * reference cluster (`fixtures/dijkstra-devnet`, provenance in its README), the build the Leios
  * testnet runs (cardano-node b004fe0c, cardano-ledger 9df9f98c, ouroboros-consensus 2416493b), and
  * on the blocks of the public Leios testnet (`fixtures/leios/testnet`).
  */
class DijkstraCodecsSuite extends munit.FunSuite:
  private val devnet = Path.of("fixtures", "dijkstra-devnet")

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def rows(path: Path): Vector[Array[String]] =
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .drop(1)
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map(_.split('\t'))

  /** `tag, blockNo, slot, headerHash, bytes` */
  private final case class Row(tag: Int, blockNo: BigInt, slot: BigInt, hash: String, raw: Bytes)

  private def devnetRows(file: String): Vector[Row] =
    rows(devnet.resolve(file)).map(p =>
      Row(p(0).toInt, BigInt(p(1)), BigInt(p(2)), p(3), hex(p(4)))
    )

  private lazy val blocks = devnetRows("blocks.tsv")
  private lazy val headers = devnetRows("headers.tsv")

  private def era(tag: Int): Era = Era.fromBlockTag(tag).getOrElse(fail(s"tag $tag"))

  private def block(row: Row): Block =
    Block.decodeShelley(row.raw).fold(e => fail(s"block ${row.blockNo}: ${e.message}"), identity)

  private def dijkstra(row: Row): Block.Dijkstra = block(row) match
    case b: Block.Dijkstra => b
    case other             => fail(s"block ${row.blockNo} is of ${other.era}")

  /** The manifest's transaction id, block number and slot. */
  private lazy val manifest: String = Files.readString(devnet.resolve("manifest.json"))
  private lazy val txId: String =
    """"transaction"\s*:\s*\{\s*"id"\s*:\s*"([0-9a-f]{64})"""".r
      .findFirstMatchIn(manifest)
      .map(_.group(1))
      .getOrElse(fail("no transaction id in the manifest"))

  /** The mempool form `cardano-cli dijkstra transaction sign` wrote. */
  private lazy val submitted: Bytes =
    """"cborHex"\s*:\s*"([0-9a-f]+)"""".r
      .findFirstMatchIn(Files.readString(devnet.resolve("transaction.json")))
      .map(m => hex(m.group(1)))
      .getOrElse(fail("no cborHex"))

  private def node(raw: Bytes): Node = Cbor.decode(raw).fold(e => fail(e.toString), identity)
  private def item(v: Value): Node = Node(v, Bytes.empty)
  private def encode(v: Value): Bytes = Cbor.encode(v).fold(e => fail(e.toString), identity)

  test("every block of the devnet chain decodes, Conway's as Shelley blocks, Dijkstra's whole") {
    val (conway, later) = blocks.partition(_.tag == 7)
    assertEquals((conway.size, later.size), (33, 8))
    blocks.foreach { row =>
      val b = block(row)
      val h = b.header
      assertEquals(b.era, era(row.tag), s"block ${row.blockNo}")
      assertEquals(h.era, b.era, s"block ${row.blockNo}")
      assertEquals(h.hash.hex, row.hash, s"block ${row.blockNo}")
      assertEquals((h.blockNo, h.slot), (row.blockNo, row.slot))
    }
    later.foreach { row =>
      val b = dijkstra(row)
      assertEquals(b.bodyHash, b.header.bodyHash, s"block ${row.blockNo}")
      assertEquals(BigInt(b.bodySize), b.header.bodySize, s"block ${row.blockNo}")
      assertEquals(b.block.faults, Vector.empty, s"block ${row.blockNo}")
      assertEquals(b.header.protocolVersion, (BigInt(12), BigInt(0)), s"block ${row.blockNo}")
    }
  }

  test("a header read by its era hashes its original bytes; Dijkstra's signs its body's") {
    assertEquals(headers.map(_.tag).distinct, Vector(7, 8))
    headers.foreach { row =>
      val h = Header.fromRaw(era(row.tag), row.raw).fold(e => fail(e.message), identity)
      assertEquals(h.era, era(row.tag))
      assertEquals(h.raw, row.raw)
      assertEquals(h.hash, Blake2b.hash256.hash(row.raw))
      assertEquals(h.hash.hex, row.hash)
      if row.tag == 8 then
        val body = node(row.raw).value match
          case Value.Arr(xs) => xs(0).original
          case other         => fail(other.toString)
        assertEquals(h.signable, body, s"header ${row.blockNo}")
    }
    // the header of each block is the one ChainSync carries
    val byHash = blocks.map(r => r.hash -> r).toMap
    headers.foreach { row =>
      assertEquals(block(byHash(row.hash)).header.raw, row.raw, s"header ${row.blockNo}")
    }
  }

  test("a Dijkstra header is not read as a Praos one, nor a Conway header as Dijkstra's") {
    val conway = headers.find(_.tag == 7).getOrElse(fail("no Conway header"))
    val later = headers.find(_.tag == 8).getOrElse(fail("no Dijkstra header"))
    assert(Header.fromRaw(Era.Dijkstra, conway.raw).isLeft)
    assert(Header.fromRaw(Era.Conway, later.raw).isLeft)
    assertEquals(
      Header.fromRaw(Era.Byron, later.raw),
      Left(ChainError.Decode(DecodeField.Header, LedgerDecodeError.ByronLayout))
    )
  }

  test("a fetched Dijkstra block matches its announced header, and a changed body does not") {
    val byHash = blocks.map(r => r.hash -> r).toMap
    headers.filter(_.tag == 8).foreach { row =>
      val announced = Header.fromRaw(Era.Dijkstra, row.raw).fold(e => fail(e.message), identity)
      val raw = byHash(row.hash).raw
      assertEquals(BlockTransport.check(announced, raw).map(_.header.hash), Right(announced.hash))
    }
    val row = headers.filter(_.tag == 8).last
    val announced = Header.fromRaw(Era.Dijkstra, row.raw).fold(e => fail(e.message), identity)
    val other = blocks.filter(_.tag == 8).head
    assert(
      BlockTransport.check(announced, other.raw).left.exists {
        case BlockTransportError.WrongHeader(_, _) => true
        case _                                     => false
      }
    )
  }

  test("the submitted transaction is the block's: same id, body and witnesses, valid") {
    val tx = DijkstraTx.fromSubmission(submitted).fold(e => fail(e.message), identity)
    assertEquals(tx.id.hex, txId)
    assertEquals(tx.isValid, true)
    val inBlock = blocks
      .filter(_.tag == 8)
      .flatMap(dijkstra(_).block.transactions)
      .filter(_.id.hex == txId)
    assertEquals(inBlock.size, 1)
    val t = inBlock(0)
    assertEquals(t.body.raw, tx.body.raw)
    assertEquals(t.witnesses.raw, tx.witnesses.raw)
    assertEquals(t.auxiliary, tx.auxiliary)
    assertEquals(t.isValid, true)
    // the mempool form's parts, as the generic envelope reader splits them
    assertEquals(
      Tx.envelopeParts(node(submitted), Era.Dijkstra),
      Right(Block.Transaction(t.body.raw, t.witnesses.raw, None, true))
    )
  }

  /** The submitted transaction's three items. */
  private lazy val parts: Vector[Node] = node(submitted).value match
    case Value.Arr(xs) => xs
    case other         => fail(other.toString)

  private def array(xs: Node*): Bytes = encode(Value.Arr(xs.toVector))

  test("the mempool form takes a flag only as a boolean third item, and only `true`") {
    val Vector(body, witnesses, aux) = parts: @unchecked
    val t = item(Value.Bool(true))
    val f = item(Value.Bool(false))
    assertEquals(
      DijkstraTx.fromSubmission(array(body, witnesses, t, aux)).map(_.id.hex),
      Right(txId)
    )
    assertEquals(
      DijkstraTx.fromSubmission(array(body, witnesses, f, aux)),
      Left(LedgerDecodeError.InvalidFlagSubmitted)
    )
    // the block form, flag last, is not the mempool form
    assertEquals(
      DijkstraTx.fromSubmission(array(body, witnesses, aux, t)),
      Left(LedgerDecodeError.ArrayLength(DecodeField.Transaction, 3, 4))
    )
    assertEquals(
      DijkstraTx.fromSubmission(array(body, witnesses, t)),
      Left(LedgerDecodeError.ArrayLength(DecodeField.Transaction, 4, 3))
    )
    assertEquals(
      DijkstraTx.fromSubmission(array(body, witnesses)),
      Left(LedgerDecodeError.ArrayLength(DecodeField.Transaction, 3, 2))
    )
    // the block form requires the flag last, and may say `false`
    val inBlock = node(array(body, witnesses, aux, f))
    assertEquals(DijkstraTx.inBlock(inBlock).map(_.isValid), Right(false))
    assert(DijkstraTx.inBlock(node(submitted)).isLeft)
  }

  /** The submitted body's entries with `changes` applied, `None` removing a key. */
  private def body(changes: (Int, Option[Value])*): Node =
    val entries = parts(0).value match
      case Value.Map(kvs) => kvs
      case other          => fail(other.toString)
    val keyed = entries.map((k, v) =>
      k.value match
        case Value.UInt(i) => i.toInt -> v
        case other         => fail(other.toString)
    )
    val changed = changes.foldLeft(keyed) { case (acc, (key, value)) =>
      acc.filterNot(_._1 == key) ++ value.map(v => key -> item(v)).toVector
    }
    node(encode(Value.Map(changed.map((k, v) => item(Value.UInt(k)) -> v))))

  private def top(n: Node) = DijkstraTx.body(n, DijkstraLevel.Top)
  private def sub(n: Node) = DijkstraTx.body(n, DijkstraLevel.Sub)
  private val empty = Value.Arr(Vector.empty)

  test("a body refuses a missing required field, a key of the other level and an unknown key") {
    val site = DecodeField.TransactionBody
    assert(top(body()).isRight)
    assertEquals(top(body(2 -> None)), Left(LedgerDecodeError.MissingKeys(site, Set(2))))
    // a sub-transaction has no fee, collateral, sub-transactions or starting intervals
    assertEquals(sub(body()), Left(LedgerDecodeError.UnknownKey(site, 2)))
    assert(sub(body(2 -> None)).isRight)
    assertEquals(sub(body(2 -> None, 0 -> None)), Left(LedgerDecodeError.MissingKeys(site, Set(0))))
    Vector(6, 10, 12, 28).foreach { key =>
      assertEquals(
        top(body(key -> Some(Value.UInt(0)))),
        Left(LedgerDecodeError.UnknownKey(site, key))
      )
    }
    Vector(13, 16, 17, 23, 27).foreach { key =>
      assertEquals(
        sub(body(2 -> None, key -> Some(Value.UInt(0)))),
        Left(LedgerDecodeError.UnknownKey(site, key))
      )
    }
  }

  test(
    "a body refuses a key twice, an empty collection where the reference does, a zero donation"
  ) {
    val raw = parts(0).original
    // `a3 00 .. 01 .. 02 1a 0002990d`, with the fee entry again: four entries, the key 2 twice
    val fee = Bytes(raw.value.drop(raw.size - 6))
    assertEquals(fee.hex.take(4), "021a")
    val twice = node(Bytes(Vector(0xa4.toByte) ++ raw.value.drop(1) ++ fee.value))
    assertEquals(
      top(twice),
      Left(
        LedgerDecodeError.DuplicateElement(DecodeField.TransactionBody / DecodeSite.Qualifier.Key)
      )
    )
    Vector(
      4 -> DecodeField.Certificates,
      13 -> DecodeField.Collateral,
      18 -> DecodeField.ReferenceInputs,
      20 -> DecodeField.ProposalProcedures
    ).foreach { (key, site) =>
      assertEquals(top(body(key -> Some(empty))), Left(LedgerDecodeError.Empty(site)), s"key $key")
    }
    Vector(5, 9, 19, 24, 25, 26).foreach { key =>
      assert(top(body(key -> Some(Value.Map(Vector.empty)))).isLeft, s"key $key")
    }
    assert(top(body(23 -> Some(empty))).isLeft)
    // the guards may be empty
    assert(top(body(14 -> Some(empty))).isRight)
    assertEquals(
      top(body(22 -> Some(Value.UInt(0)))),
      Left(LedgerDecodeError.Zero(DecodeField.Donation))
    )
    assertEquals(top(body(22 -> Some(Value.UInt(1)))).map(_.donation), Right(BigInt(1)))
  }

  test("a body refuses an input twice in its set") {
    val inputs = parts(0).value match
      case Value.Map(kvs) => kvs.collectFirst { case (k, v) if k.value == Value.UInt(0) => v }
      case _              => None
    val input = inputs.map(_.value) match
      case Some(Value.Tag(_, set)) =>
        set.value match
          case Value.Arr(xs) => xs(0)
          case other         => fail(other.toString)
      case other => fail(other.toString)
    assertEquals(
      top(body(0 -> Some(Value.Arr(Vector(input, input))))),
      Left(LedgerDecodeError.DuplicateElement(DecodeField.Inputs))
    )
  }

  test("the Conway-shaped readers the rules consume refuse a Dijkstra transaction") {
    val refused = Left(LedgerDecodeError.NotYet(DijkstraNotYet.Rules))
    assertEquals(TxBody.decode(parts(0).original, Era.Dijkstra), refused)
    assertEquals(Witnesses.decode(parts(1).original, Era.Dijkstra), refused)
    assertEquals(Tx.fromEnvelope(submitted, Era.Dijkstra), refused)
  }

  test("a Dijkstra block holds no detached record, its era's shapes are single items") {
    import DetachedRecords.Shape
    assertEquals(DetachedRecords.Shapes.header(Era.Dijkstra), Shape.Item)
    assertEquals(DetachedRecords.Shapes.body(Era.Dijkstra), Shape.Item)
    assertEquals(DetachedRecords.Shapes.witnesses(Era.Dijkstra), Shape.Item)
    assertEquals(DetachedRecords.Shapes.transaction(Era.Dijkstra), Shape.Item)
    assertEquals(DetachedRecords.Shapes.auxiliaryData(Era.Dijkstra), Shape.Item)
    blocks.filter(_.tag == 8).foreach { row =>
      assert(DetachedRecords.detached(row.raw, DetachedRecords.Shapes.block).exists(_.isEmpty))
    }
  }

  test("the blocks of the public Leios testnet decode as Dijkstra blocks") {
    val testnet = rows(Path.of("fixtures", "leios", "testnet", "blocks-rc-2026w44.tsv"))
    assert(testnet.nonEmpty)
    testnet.foreach { p =>
      val raw = hex(p(5))
      Block.decodeShelley(raw) match
        case Right(b: Block.Dijkstra) =>
          assertEquals(b.header.hash.hex, p(4), p(0))
          assertEquals(b.block, LeiosBlock.decode(raw).fold(e => fail(e.message), identity))
        case other => fail(s"${p(0)}: $other")
    }
  }

  test("a Dijkstra block whose transaction nests deeper than the stack decodes on a small stack") {
    val header = headers.find(_.tag == 8).getOrElse(fail("no Dijkstra header")).raw
    val depth = 50000
    // one transaction whose metadata nests `depth` lists (ADR 0011); the body hash is then not the
    // header's, which the block's faults report and its decoding does not
    val metadata = "a101" + "81" * depth + "01"
    val input = "81825820" + "11" * 32 + "00"
    val tx = hex(s"84a300${input}01800201a0${metadata}f5")
    val body = hex("8381") ++ tx ++ hex("f6f6")
    val raw = hex("820882") ++ header ++ body
    val decoded = PreprodDeepTx.onSmallStack(Block.decodeShelley(raw))
    decoded match
      case Right(b: Block.Dijkstra) =>
        assertEquals(b.block.transactions.size, 1)
        assertEquals(b.block.faults.contains(LeiosBlock.Fault.BodyHashDiffers), true)
      case other => fail(other.toString)
  }
