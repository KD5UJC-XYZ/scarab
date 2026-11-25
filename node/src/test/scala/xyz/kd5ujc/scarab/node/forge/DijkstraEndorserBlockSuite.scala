// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.consensus.leios.ProtVerParity
import cats.data.NonEmptyVector
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{EbAnnouncement, ForgeDefect, LeiosBlock, TestLedger}
import xyz.kd5ujc.scarab.ledger.era.{Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{Capacity, EbCapacityScale, EbMeasure}
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosFetch}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.LeiosEb

/** The endorser block the forger makes of the transactions a ranking block leaves
  * ([[BlockBuilder.partition]], `snapshotPartition` 's second part), its capacity
  * ([[Capacity.endorserBlockCapacity]], [[EbCapacityScale]]), the block itself
  * ([[ForgedLeiosEb.forge]], `forgeLeiosEb`) and the announcement of the header that carries it, on
  * the testnet's block 64,853 (`fixtures/leios/testnet/blocks-rc-2026w44.tsv`) and the endorser
  * block it announced, its body and closure as the reference stored them
  * (`fixtures/leios/testnet/eb-1455869.tsv.gz`), and on measures and parameters made for each case.
  */
class DijkstraEndorserBlockSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private val testnet = Path.of("fixtures", "leios", "testnet")

  private lazy val announcingRaw: Bytes = Files
    .readAllLines(testnet.resolve("blocks-rc-2026w44.tsv"))
    .asScala
    .map(_.split('\t'))
    .collectFirst { case r if r(0) == "announcing-with-txs" => hex(r(5)) }
    .getOrElse(fail("no block 64,853"))

  /** Block 64,853, which announces the endorser block of slot 1,455,869. */
  private lazy val announcing: LeiosBlock =
    LeiosBlock.decode(announcingRaw).fold(e => fail(e.message), identity)

  /** Block 64,853's 40 transactions, read by the era's block decoder. */
  private lazy val rankingTxs: Vector[Tx[TxLevel.Top, Dijkstra]] =
    DijkstraEraTxs.ofBlock(announcingRaw)

  /** The recorded endorser block: its point and announced size, its body's bytes, and its closure,
    * each transaction's bytes in the body's order.
    */
  private final case class Recorded(point: EbPoint, size: Long, body: Bytes, closure: Vector[Bytes])

  private lazy val recorded: Recorded =
    val text = new String(
      new GZIPInputStream(
        new ByteArrayInputStream(Files.readAllBytes(testnet.resolve("eb-1455869.tsv.gz")))
      ).readAllBytes(),
      StandardCharsets.US_ASCII
    )
    val rows = text.linesIterator.map(_.split('\t').toVector).toVector
    val (slot, hash, size) = rows.collectFirst { case Vector("point", s, h, n) =>
      (BigInt(s), hex(h), n.toLong)
    }.get
    val body = rows.collectFirst { case Vector("body", b) => hex(b) }.get
    val closure = rows.collect { case Vector("tx", offset, t) => offset.toInt -> hex(t) }
    assertEquals(closure.map(_._1), closure.indices.toVector)
    Recorded(
      EbPoint(Word64.clamped(slot), EbHash.fromBytes(hash)),
      size,
      body,
      closure.map(_._2)
    )

  /** The closure's transactions, read as a block's are, by the era's decoder. */
  private lazy val endorsed: NonEmptyVector[Tx[TxLevel.Top, Dijkstra]] =
    NonEmptyVector
      .fromVector(recorded.closure.map(DijkstraEraTxs.inBlock))
      .getOrElse(fail("an empty closure"))

  /** Dijkstra's endorser-block parameters as the testnet's state at slot 2,168,639 holds them
    * (`DijkstraForgeSuite` reads them from it).
    */
  private lazy val params = TestLedger
    .params(12)
    .copy(
      maxEndorserBlockTxsSize = BigInt(2000000),
      maxEndorserBlockExUnits = ExUnits(BigInt(2160000000L), BigInt(600000000000L)),
      maxRefScriptSizePerEndorserBlock = BigInt(12000000),
      maxEndorserBlockReferencesSize = BigInt(308000)
    )

  private val unbounded =
    TxMeasure(Long.MaxValue, BigInt(Long.MaxValue), BigInt(Long.MaxValue), Long.MaxValue)

  private def held(txs: Vector[Tx[TxLevel.Top, Dijkstra]]): Vector[DijkstraHeld] =
    txs.map(tx => DijkstraHeld(tx, Capacity.dijkstraMeasure(tx, 0L)))

  /** A snapshot of the ranking block's 40 transactions, then the endorser block's 323. */
  private lazy val snapshot: DijkstraSnapshot =
    DijkstraSnapshot(held(rankingTxs) ++ held(endorsed.toVector))

  /** The measure of the ranking block's 40 transactions together. */
  private lazy val rankingUsed: TxMeasure =
    held(rankingTxs).map(_.measure).reduce(_ + _)

  test("the recorded endorser block is the one block 64,853 announces, as the store holds it") {
    val announced = announcing.header.leios.announcement.getOrElse(fail("no announcement"))
    assertEquals(announced, EbAnnouncement(recorded.point.hash, recorded.size))
    assertEquals(recorded.point.slot.toBigInt, announcing.header.slot)
    assertEquals(BigInt(recorded.body.size), BigInt(recorded.size))
    val body = LeiosEb.decode(recorded.body).fold(e => fail(e.toString), identity)
    assertEquals(LeiosEb.hashOf(body.transactions.toVector), recorded.point.hash)
    assertEquals(body.size, 323)
    // each closure transaction is the bytes its hash and length name, and reads as a block's
    assertEquals(
      body.transactions.toVector,
      recorded.closure.map(t =>
        LeiosEb.hashTx(t) -> LeiosEb.of(NonEmptyVector.one(t)).transactions.head._2
      )
    )
    assertEquals(endorsed.toVector.map(DijkstraTx.forBlockInclusion), recorded.closure)
  }

  test("the endorser block forged of the recorded closure is the reference's, byte for byte") {
    val forged = ForgedLeiosEb.forge(announcing.header.slot, ProtVerParity.Even, endorsed)
    assertEquals(forged.body.encoded, recorded.body)
    assertEquals(forged.point, recorded.point)
    assertEquals(
      forged.closure.toVector,
      forged.body.transactions.toVector.map(_._1).zip(recorded.closure)
    )
    // `encodeLeiosEbSize` is the length of the encoding
    assertEquals(forged.body.encodedSize, recorded.body.size.toLong)
    assertEquals(forged.announcement, EbAnnouncement(recorded.point.hash, recorded.size))
    assertEquals(Some(forged.announcement), announcing.header.leios.announcement)
  }

  test("the header body that announces the forged block is block 64,853's signed bytes") {
    val forged = ForgedLeiosEb.forge(announcing.header.slot, ProtVerParity.Even, endorsed)
    val h = announcing.header.body
    val assembly = BlockBuilder.Assembly.dijkstra(
      rankingTxs,
      None,
      Some(BlockBuilder.Endorsing(announcing.header.slot, ProtVerParity.Even, endorsed))
    )
    val rbBody = assembly match
      case BlockBuilder.Assembly.InDijkstra(b, Some(e)) =>
        assertEquals(e.forged, forged)
        b
      case other => fail(s"not a Dijkstra body with an endorser block: $other")
    val built = BlockBuilder.leiosHeaderBody(
      h.blockNo,
      h.slot,
      h.prevHash,
      h.issuerVkey,
      h.vrfVkey,
      h.vrf,
      rbBody,
      Some(forged.announcement),
      h.opCert,
      (h.leios.versionInfo.highestSupportedMajor, h.leios.versionInfo.softwareTag)
    )
    assertEquals(built.encode, announcing.header.signable)
    assertEquals(assembly.endorsedIds, endorsed.toVector.map(_.id))
    assertEquals(assembly.endorserBlockOverWire, None)
  }

  test("the partition gives the ranking block its 40 and the endorser block the 323 after them") {
    val capacity = Capacity.endorserBlockCapacity(params).fold(e => fail(e.toString), identity)
    val split = BlockBuilder.partition(snapshot, rankingUsed, capacity)
    assertEquals(split.ranking.transactions, rankingTxs)
    assertEquals(split.endorsedTransactions, endorsed.toVector)
    assertEquals(
      split.endorsedUsed,
      held(endorsed.toVector).map(h => EbMeasure.of(h.measure)).reduce(_ + _)
    )
    val endorsing = split
      .endorsing(announcing.header.slot, ProtVerParity.Even)
      .getOrElse(fail("no endorser block"))
    assertEquals(endorsing.forged.body.encoded, recorded.body)
    assertEquals(endorsing.transactions, endorsed)
  }

  test("at the testnet's ranking-block capacity the partition is the one block 64,853 records") {
    // `blockCapacityTxMeasure` of the state at slot 2,168,639 (`DijkstraForgeSuite`); the block is
    // of slot 1,455,869, whose parameters no recording here holds
    val block = TxMeasure(89088L, BigInt(72000000), BigInt(20000000000L), 1048576L)
    val capacity = Capacity.endorserBlockCapacity(params).fold(e => fail(e.toString), identity)
    val split = BlockBuilder.partition(snapshot, block, capacity)
    assertEquals(split.ranking.chosen.size, 40)
    assertEquals(split.endorsedTransactions.size, 323)
  }

  test(
    "a transaction's reference is charged on its measure's size, not the length the body lists"
  ) {
    // the measure's size is `sizeTxF` plus 4, the listed length `sizeTxF` plus the flag's byte
    endorsed.toVector.foreach { tx =>
      val m = Capacity.dijkstraMeasure(tx, 0L)
      assertEquals(
        EbMeasure.of(m).references,
        LeiosEb.itemSize(tx.sizeTxF + Capacity.PerTxOverhead)
      )
      assertEquals(m.bytes, DijkstraTx.forBlockInclusion(tx).size.toLong + 3)
    }
    // the item is a 32-byte hash under a 2-byte head and the size's head: 1, 2, 3 or 5 bytes
    assertEquals(
      Vector(0L, 23L, 24L, 255L, 256L, 65535L, 65536L, 0xffffffffL).map(LeiosEb.itemSize),
      Vector(35L, 35L, 36L, 36L, 37L, 37L, 39L, 39L)
    )
    // where the four bytes cross a head's width the charge is wider than the item listed
    assertEquals(EbMeasure.of(TxMeasure(65536L, 0, 0, 0L)).references, 39L)
    assertEquals(LeiosEb.itemSize(65533L), 37L)
  }

  test("the endorser block's capacity: the closure's parameters, the references less 5 bytes") {
    assertEquals(
      Capacity.endorserBlockCapacity(params).map(_.references),
      Right(params.maxEndorserBlockReferencesSize.toLong - 5)
    )
    assertEquals(
      Capacity.endorserBlockCapacity(params).map(_.closure),
      Capacity.endorserBlockReservation(params)
    )
    assertEquals(
      Vector(0L, 4L, 5L, 6L, 600000L).map(LeiosEb.referencesCapacity),
      Vector(0L, 0L, 0L, 1L, 599995L)
    )
    // `guardLeiosWireLimit`: at the LeiosFetch limit and one byte past it
    val at = params.copy(maxEndorserBlockReferencesSize = BigInt(LeiosFetch.MaxEbBytes))
    assertEquals(Capacity.endorserBlockCapacity(at).map(_.references), Right(599995L))
    assertEquals(
      Capacity.endorserBlockCapacity(at.copy(maxEndorserBlockReferencesSize = BigInt(600001))),
      Left(Capacity.EndorserBlockReferencesOverWire(BigInt(600001), LeiosFetch.MaxEbBytes))
    )
  }

  test("the run ends at the exact endorser-block budget, in each component") {
    val measures = held(endorsed.toVector).map(h => EbMeasure.of(h.measure))
    Vector(1, 2, 100, 322, 323).foreach { k =>
      val total = measures.take(k).reduce(_ + _)
      def run(limit: EbMeasure) =
        BlockBuilder.partition(snapshot, rankingUsed, limit).endorsedTransactions.size
      assertEquals(run(total), k, s"$k")
      assertEquals(run(total.copy(references = total.references - 1)), k - 1, s"$k references")
      // a component one less than the total ends the run before the k-th transaction where that
      // transaction counts in it (the recorded ones carry no units or script bytes here)
      val last = measures(k - 1).closure
      Vector(
        (last.bytes > 0) -> total.closure.copy(bytes = total.closure.bytes - 1),
        (last.exUnitsMemory > 0) -> total.closure.copy(exUnitsMemory =
          total.closure.exUnitsMemory - 1
        ),
        (last.exUnitsSteps > 0) -> total.closure.copy(exUnitsSteps =
          total.closure.exUnitsSteps - 1
        ),
        (last.refScriptsBytes > 0) ->
          total.closure.copy(refScriptsBytes = total.closure.refScriptsBytes - 1)
      ).collect { case (true, c) => c }
        .foreach(c => assertEquals(run(total.copy(closure = c)), k - 1, s"$k closure $c"))
    }
    // every component bounds the run: one unit of each per transaction, three fit
    val one = TxMeasure(10L, BigInt(10), BigInt(10), 10L)
    val units = DijkstraSnapshot(Vector.fill(5)(DijkstraHeld(endorsed.head, one)))
    val three = EbMeasure.of(one) + EbMeasure.of(one) + EbMeasure.of(one)
    def runOf(limit: EbMeasure) =
      BlockBuilder.partition(units, TxMeasure.zero, limit).endorsedTransactions.size
    assertEquals(runOf(three), 3)
    Vector(
      three.copy(references = three.references - 1),
      three.copy(closure = three.closure.copy(bytes = 29L)),
      three.copy(closure = three.closure.copy(exUnitsMemory = BigInt(29))),
      three.copy(closure = three.closure.copy(exUnitsSteps = BigInt(29))),
      three.copy(closure = three.closure.copy(refScriptsBytes = 29L))
    ).foreach(limit => assertEquals(runOf(limit), 2, limit.toString))
  }

  test(
    "no endorser block when nothing overflows the ranking block, or the first left does not fit"
  ) {
    val capacity = Capacity.endorserBlockCapacity(params).fold(e => fail(e.toString), identity)
    // everything fits the ranking block
    val all = BlockBuilder.partition(snapshot, unbounded, capacity)
    assertEquals(all.ranking.chosen.size, 363)
    assertEquals(all.endorsedTransactions, Vector.empty)
    assertEquals(all.endorsing(announcing.header.slot, ProtVerParity.Even), None)
    // transactions overflow, but the first of them does not fit the endorser block: none is taken,
    // however small those after it
    val first = EbMeasure.of(Capacity.dijkstraMeasure(endorsed.head, 0L))
    val tight = BlockBuilder.partition(
      snapshot,
      rankingUsed,
      first.copy(references = first.references - 1)
    )
    assertEquals(tight.ranking.transactions, rankingTxs)
    assertEquals(tight.endorsing(announcing.header.slot, ProtVerParity.Even), None)
    // an empty snapshot, and a zero limit
    assertEquals(
      BlockBuilder
        .partition(DijkstraSnapshot.empty, unbounded, capacity)
        .endorsing(0, ProtVerParity.Even),
      None
    )
    assertEquals(
      BlockBuilder
        .partition(snapshot, rankingUsed, EbMeasure.zero)
        .endorsing(0, ProtVerParity.Even),
      None
    )
    // the body of a block with no endorser block announces none
    val body = BlockBuilder.Assembly.dijkstra(all.ranking.transactions, None, None)
    assertEquals(body.endorsedIds, Vector.empty)
  }

  test(
    "a block certifying takes no transactions: a zero block limit leaves the endorser block all"
  ) {
    val capacity = Capacity.endorserBlockCapacity(params).fold(e => fail(e.toString), identity)
    val split = BlockBuilder.partition(snapshot, TxMeasure.zero, capacity)
    assertEquals(split.ranking.chosen, Vector.empty)
    assertEquals(split.endorsedTransactions.size, 363)
  }

  test("the scale floors each component, and is a factor in [0, 1]") {
    val m = EbMeasure(TxMeasure(5L, BigInt(7), BigInt(9), 11L), 13L)
    val half = EbCapacityScale.of(Rational(1, 2)).getOrElse(fail("1/2 is a scale"))
    assertEquals(half(m), EbMeasure(TxMeasure(2L, BigInt(3), BigInt(4), 5L), 6L))
    assertEquals(EbCapacityScale.full(m), m)
    assertEquals(EbCapacityScale.of(Rational.zero).map(_(m)), Some(EbMeasure.zero))
    assertEquals(EbCapacityScale.of(Rational(3, 2)), None)
    assertEquals(EbCapacityScale.of(Rational(-1, 3)), None)
    // a scaled capacity bounds the run as the unscaled one does
    val capacity = Capacity.endorserBlockCapacity(params).fold(e => fail(e.toString), identity)
    val tenth = EbCapacityScale.of(Rational(1, 10)).getOrElse(fail("1/10 is a scale"))
    val scaled = tenth(capacity)
    assertEquals(scaled.references, (params.maxEndorserBlockReferencesSize.toLong - 5) / 10)
    val run = BlockBuilder.partition(snapshot, rankingUsed, scaled).endorsedUsed
    assert(run.within(scaled), run)
  }

  test("an endorser block filled to the wire's references capacity encodes within the wire") {
    // the same transaction, its measure the recorded one's, as often as the references allow
    val one = held(Vector(endorsed.head)).head
    val limit = EbMeasure(unbounded, LeiosEb.referencesCapacity(LeiosFetch.MaxEbBytes.toLong))
    val many = DijkstraSnapshot(Vector.fill(20000)(one))
    val split = BlockBuilder.partition(many, TxMeasure.zero, limit)
    val endorsing = split.endorsing(1, ProtVerParity.Even).getOrElse(fail("no endorser block"))
    assert(
      endorsing.forged.body.encodedSize <= LeiosFetch.MaxEbBytes,
      endorsing.forged.body.encodedSize
    )
    assertEquals(endorsing.forged.body.encodedSize, endorsing.forged.body.encoded.size.toLong)
    assert(split.endorsed.size <= LeiosFetch.MaxTxsPerEb, split.endorsed.size)
    assertEquals(
      BlockBuilder.Assembly.dijkstra(Vector.empty, None, Some(endorsing)).endorserBlockOverWire,
      None
    )
  }

  test("an endorser block encoded past the wire's limit is a defect of the block just built") {
    val over = NonEmptyVector(endorsed.head, Vector.fill(17200)(endorsed.head))
    val endorsing = BlockBuilder.Endorsing(1, ProtVerParity.Even, over)
    val size = endorsing.forged.body.encodedSize
    assert(size > LeiosFetch.MaxEbBytes, size)
    assertEquals(
      BlockBuilder.Assembly.dijkstra(Vector.empty, None, Some(endorsing)).endorserBlockOverWire,
      Some(ForgeDefect.EndorserBlockSize(size, LeiosFetch.MaxEbBytes))
    )
  }
