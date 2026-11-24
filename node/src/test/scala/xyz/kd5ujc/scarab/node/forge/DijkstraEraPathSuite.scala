// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.data.NonEmptyVector
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.bls.Certificate
import xyz.kd5ujc.scarab.ledger.{
  EbAnnouncement,
  HeaderVersionInfo,
  LeiosBlock,
  LeiosHeader,
  LeiosHeaderBody,
  LeiosHeaderFields,
  TestLedger
}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTx as FlatTx
import xyz.kd5ujc.scarab.ledger.era.{Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraTx}
import xyz.kd5ujc.scarab.ledger.rules.DijkstraTxMeasures
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{Capacity, DijkstraRead, EbCapacityScale, EbMeasure}
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.{EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.LeiosEb

/** The forger's Dijkstra path in the era's types (`Tx TopTx DijkstraEra`, #85) against the flat
  * path it replaced, which forged on the public Leios testnet until then: the blocks, the endorser
  * blocks, the transactions' bytes in them, the measures and the partitions must be the same, byte
  * for byte and value for value. The flat path is frozen here as it was at 0ea3528b8 ([[FlatPath]]:
  * `LeiosBlockBody.encode`, `ForgedLeiosEb.forge`, `Capacity.dijkstraMeasure`,
  * `BlockBuilder.partition` and `DijkstraRead.raw` of that commit, over the flat transaction).
  *
  * The transactions are every Dijkstra transaction the repository holds that the build accepts: the
  * public testnet's blocks and the 323 of the endorser block of slot 1,455,869
  * (`fixtures/leios/testnet`), the corpus of the Leios build's verdicts
  * (`fixtures/reference/leios-testnet-blocks`), the reference cluster's chain and its submitted
  * transaction (`fixtures/dijkstra-devnet`), the script-context fixtures, recorded on the testnet's
  * chain with their sub-transactions, the UTXOW cases and the mempool forms of the crossing corpus.
  * Each is read as the node reads it: one of a block by the block decoders, one submitted by the
  * pool's read ([[DijkstraRead.submitted]]).
  */
class DijkstraEraPathSuite extends munit.FunSuite:
  import DijkstraCorpus.{hex, str}

  /** The flat path of 0ea3528b8, over the flat transaction. */
  private object FlatPath:
    private def head(major: Int, n: Long): Array[Byte] =
      val m = major << 5
      if n < 24 then Array((m | n.toInt).toByte)
      else if n < 256 then Array((m | 24).toByte, n.toByte)
      else if n < 65536 then Array((m | 25).toByte, (n >> 8).toByte, n.toByte)
      else Array((m | 26).toByte, (n >> 24).toByte, (n >> 16).toByte, (n >> 8).toByte, n.toByte)

    /** `LeiosBlockBody(transactions, leiosCertificate, perasCertificate).encode`. */
    def body(txs: Vector[FlatTx], cert: Option[Certificate], peras: Option[Bytes]): Bytes =
      val items = txs.map(_.forBlockInclusion.toArray)
      val seq =
        if items.sizeIs <= 23 then head(4, items.size.toLong) +: items
        else (Array(0x9f.toByte) +: items) :+ Array(0xff.toByte)
      Bytes.wrap(
        (Vector(head(4, 3)) ++ seq ++ Vector(
          cert.fold(Array(0xf6.toByte))(_.encode.toArray),
          peras.fold(Array(0xf6.toByte))(p => head(2, p.size.toLong) ++ p.toArray)
        )).flatten.toArray
      )

    /** `Assembly.dijkstra` 's body: no transactions when it certifies. */
    def rankingBody(txs: Vector[FlatTx], cert: Option[Certificate]): Bytes =
      body(cert.fold(txs)(_ => Vector.empty), cert, None)

    /** `ForgedLeiosEb.forge`: the point, the body and the closure. */
    def forge(
        slot: BigInt,
        txs: NonEmptyVector[FlatTx]
    ): (EbPoint, LeiosEb, NonEmptyVector[(LeiosTxHash, Bytes)]) =
      val bytes = txs.map(_.forBlockInclusion)
      val body = LeiosEb.of(bytes)
      (
        EbPoint(Word64.clamped(slot), LeiosEb.hashOf(body.transactions.toVector)),
        body,
        body.transactions.map(_._1).zipWith(bytes)((hash, tx) => hash -> tx)
      )

    /** `Capacity.dijkstraMeasure`. */
    def measure(tx: FlatTx, refScriptBytes: Long): TxMeasure =
      val units = DijkstraTxMeasures.totalExUnits(tx)
      TxMeasure(tx.size + Capacity.PerTxOverhead, units.mem, units.steps, refScriptBytes)

    /** `BlockBuilder.partition` over `(transaction, measure)`: the ranking block's, its total, the
      * endorser block's and its total.
      */
    def partition(
        held: Vector[(FlatTx, TxMeasure)],
        blockLimit: TxMeasure,
        ebLimit: EbMeasure
    ): (Vector[FlatTx], TxMeasure, Vector[FlatTx], EbMeasure) =
      val (inBlock, afterBlock, used) = BlockBuilder.splitAfterSize(held, blockLimit)(_._2)
      val (inEb, _, ebUsed) =
        BlockBuilder.splitAfterSize(afterBlock, ebLimit)(h => EbMeasure.of(h._2))
      (inBlock.map(_._1), used, inEb.map(_._1), ebUsed)

    /** `DijkstraRead.raw`: the mempool form, `[body, witnesses, auxiliary data / null]`. */
    def raw(tx: FlatTx): Bytes =
      Bytes.fromArray(Array(0x83.toByte)) ++ tx.body.raw ++ tx.witnesses.raw ++
        tx.auxiliary.fold(Bytes.fromArray(Array(0xf6.toByte)))(_.raw)

  /** A transaction both ways: the flat one and the era's, read from the same bytes. */
  private final case class Pair(name: String, flat: FlatTx, era: Tx[TxLevel.Top, Dijkstra])

  private def inBlock(name: String, raw: Bytes): Option[Pair] =
    Cbor.decode(raw).toOption.flatMap { n =>
      (FlatTx.inBlock(n), DijkstraTx.inBlock(n)) match
        case (Right(f), Right(e)) => Some(Pair(name, f, e))
        case (Left(_), Left(_))   => None
        case other                => fail(s"$name: the two decoders disagree: $other")
    }

  /** A submitted transaction, read as the pool reads it. */
  private def submitted(name: String, raw: Bytes): Option[Pair] =
    (FlatTx.fromSubmission(raw), DijkstraRead.submitted(raw)) match
      case (Right(f), Right(r)) =>
        assertEquals(r.raw, FlatPath.raw(f), s"$name: the mempool form")
        assertEquals(r.raw, raw, s"$name: the mempool form is the bytes submitted")
        assertEquals(r.size, f.size, name)
        Some(Pair(name, f, r.tx))
      case (Left(_), Left(_)) => None
      case other              => fail(s"$name: the two reads disagree: $other")

  private def ofBlock(name: String, raw: Bytes): Vector[Pair] =
    LeiosBlock
      .decode(raw)
      .fold(
        _ => Vector.empty,
        b =>
          // each read again by the era's decoder from the bytes the block holds
          b.transactions.zipWithIndex.map((f, i) => Pair(s"$name tx $i", f, DijkstraEraTxs.of(f)))
      )

  private val testnet = Path.of("fixtures", "leios", "testnet")

  private lazy val announcingRaw: Bytes =
    DijkstraCorpus.testnetBlocks
      .collectFirst { case ("announcing-with-txs", raw) => raw }
      .getOrElse(fail("no block 64,853"))

  private lazy val announcing: LeiosBlock =
    LeiosBlock.decode(announcingRaw).fold(e => fail(e.message), identity)

  /** The closure of the endorser block block 64,853 announces, in its order. */
  private lazy val closure: Vector[Pair] =
    val text = new String(
      new GZIPInputStream(
        new ByteArrayInputStream(Files.readAllBytes(testnet.resolve("eb-1455869.tsv.gz")))
      ).readAllBytes(),
      StandardCharsets.US_ASCII
    )
    text.linesIterator
      .map(_.split('\t').toVector)
      .collect { case Vector("tx", offset, t) => inBlock(s"closure $offset", hex(t)) }
      .flatten
      .toVector

  /** Block 64,853's 40 transactions, then the 323 of the endorser block it announces. */
  private lazy val recorded: Vector[Pair] = ofBlock("block 64,853", announcingRaw) ++ closure

  /** Every Dijkstra transaction the repository holds that the build accepts. */
  private lazy val corpus: Vector[Pair] =
    val blocks = (DijkstraCorpus.testnetBlocks ++ DijkstraCorpus.devnetBlocks ++
      DijkstraCorpus.leiosCorpus
        .filter(j => str(j, "kind") == "block")
        .map(j => (str(j, "name"), hex(str(j, "bytes")))))
      .flatMap(ofBlock)
    val alone = (DijkstraCorpus.leiosCorpus.filter(j => str(j, "kind") == "tx") ++
      DijkstraCorpus.contextCases ++ DijkstraCorpus.chainContextCases)
      .flatMap(j => inBlock(str(j, "name"), hex(str(j, "bytes"))))
    val utxow = DijkstraCorpus.utxowCases
      .map(j => str(j, "tx"))
      .distinct
      .zipWithIndex
      .flatMap((t, i) => inBlock(s"utxow $i", hex(t)))
    val forms = (DijkstraCorpus.devnetSubmitted +: DijkstraCorpus.crossingForms).zipWithIndex
      .flatMap((raw, i) => submitted(s"submitted $i", raw))
    blocks ++ closure ++ alone ++ utxow ++ forms

  private val unbounded =
    TxMeasure(Long.MaxValue, BigInt(Long.MaxValue), BigInt(Long.MaxValue), Long.MaxValue)

  /** The endorser-block parameters of the testnet's state at slot 2,168,639. */
  private lazy val ebCapacity: EbMeasure =
    Capacity
      .endorserBlockCapacity(
        TestLedger
          .params(12)
          .copy(
            maxEndorserBlockTxsSize = BigInt(2000000),
            maxEndorserBlockExUnits = ExUnits(BigInt(2160000000L), BigInt(600000000000L)),
            maxRefScriptSizePerEndorserBlock = BigInt(12000000),
            maxEndorserBlockReferencesSize = BigInt(308000)
          )
      )
      .fold(e => fail(e.toString), identity)

  private lazy val certificate: Certificate =
    DijkstraCorpus.testnetBlocks
      .collectFirst { case ("certifying", raw) => raw }
      .flatMap(raw => LeiosBlock.decode(raw).toOption)
      .flatMap(_.leiosCertificate)
      .map(_.certificate)
      .getOrElse(fail("no certifying block"))

  test("the corpus holds the transactions the comparison is claimed for") {
    assertEquals(recorded.size, 363)
    // the block's 40, the closure's 323, and every other accepted transaction of the fixtures
    assert(corpus.size > 1000, corpus.size)
    assert(corpus.exists(_.flat.auxiliary.isDefined))
    assert(corpus.exists(_.flat.auxiliary.isEmpty))
    assert(corpus.exists(_.flat.body.subTransactions.nonEmpty))
    assert(corpus.exists(p => DijkstraTxMeasures.totalExUnits(p.flat) != ExUnits.zero))
    // more than 23 transactions in a block, written indefinite
    assert(recorded.size > 23)
  }

  test("each transaction's block form, flag, id, measure and Leios key are the flat path's") {
    corpus.foreach { case Pair(name, flat, era) =>
      assertEquals(era.id, flat.id, name)
      // the bytes a block and an endorser block hold, `Entry.leiosTx` 's bytes and key
      val bytes = DijkstraTx.forBlockInclusion(era)
      assertEquals(bytes, flat.forBlockInclusion, name)
      assertEquals(LeiosEb.hashTx(bytes), LeiosEb.hashTx(flat.forBlockInclusion), name)
      // the flag of a refused script, which no recorded transaction carries
      val invalid =
        DijkstraTx.withIsValid(era, xyz.kd5ujc.scarab.ledger.era.IsPhase2Valid.Phase2Invalid)
      assertEquals(
        DijkstraTx.forBlockInclusion(invalid),
        flat.copy(isValid = false).forBlockInclusion,
        name
      )
      Vector(0L, 7L, 1048576L).foreach(r =>
        assertEquals(Capacity.dijkstraMeasure(era, r), FlatPath.measure(flat, r), s"$name $r")
      )
      // the mempool form, as the pool relays and lists a block's transaction once submitted
      if flat.isValid then
        val form = FlatPath.raw(flat)
        DijkstraRead.submitted(form) match
          case Right(read) =>
            assertEquals(read.raw, form, name)
            assertEquals(read.id, flat.id, name)
          case Left(e) => fail(s"$name: $e")
    }
  }

  /** The snapshot of `pairs` as the era's forger holds it, and the flat one's. */
  private def snapshots(
      pairs: Vector[Pair]
  ): (DijkstraSnapshot, Vector[(FlatTx, TxMeasure)]) =
    (
      DijkstraSnapshot(pairs.map(p => DijkstraHeld(p.era, Capacity.dijkstraMeasure(p.era, 0L)))),
      pairs.map(p => p.flat -> FlatPath.measure(p.flat, 0L))
    )

  /** The ranking block of a partition, its header body and the block, and the endorser block it
    * announces, both ways, under the header of block 64,853.
    */
  private def sameBlocks(
      pairs: Vector[Pair],
      blockLimit: TxMeasure,
      ebLimit: EbMeasure,
      cert: Option[Certificate],
      what: String
  ): Unit =
    val (era, flat) = snapshots(pairs)
    val split = BlockBuilder.partition(era, blockLimit, ebLimit)
    val (flatBlock, flatUsed, flatEb, flatEbUsed) = FlatPath.partition(flat, blockLimit, ebLimit)
    // the partition: the same transactions, in order, counting for the same
    assertEquals(split.ranking.transactions.map(_.id), flatBlock.map(_.id), what)
    assertEquals(split.ranking.used, flatUsed, what)
    assertEquals(split.endorsedTransactions.map(_.id), flatEb.map(_.id), what)
    assertEquals(split.endorsedUsed, flatEbUsed, what)
    val slot = announcing.header.slot
    val assembly =
      BlockBuilder.Assembly.dijkstra(split.ranking.transactions, cert, split.endorsing(slot))
    // the endorser block: its point, body, closure and announcement
    val flatForged = NonEmptyVector.fromVector(flatEb).map(FlatPath.forge(slot, _))
    val announcement = assembly match
      case BlockBuilder.Assembly.InDijkstra(_, e) =>
        assertEquals(
          e.map(x => (x.forged.point, x.forged.body, x.forged.closure)),
          flatForged,
          what
        )
        e.map(_.forged.announcement)
      case other => fail(s"$what: not a Dijkstra body: $other")
    assertEquals(
      announcement,
      flatForged.map((point, body, _) => EbAnnouncement(point.hash, body.encodedSize)),
      what
    )
    // the body, the header body and the block
    val flatBody = FlatPath.rankingBody(flatBlock, cert)
    assertEquals(assembly.size, flatBody.size.toLong, what)
    assertEquals(assembly.ids, cert.fold(flatBlock.map(_.id))(_ => Vector.empty), what)
    assertEquals(assembly.endorsedIds, flatEb.map(_.id), what)
    val h = announcing.header.body
    val pv = (h.leios.versionInfo.highestSupportedMajor, h.leios.versionInfo.softwareTag)
    val headerBody =
      assembly.headerBody(
        h.blockNo,
        h.slot,
        h.prevHash,
        h.issuerVkey,
        h.vrfVkey,
        h.vrf,
        h.opCert,
        pv
      )
    val flatHeaderBody = LeiosHeaderBody(
      h.blockNo,
      h.slot,
      h.prevHash,
      h.issuerVkey,
      h.vrfVkey,
      h.vrf,
      BigInt(flatBody.size),
      Blake2b.hash256.hash(flatBody),
      h.opCert,
      LeiosHeaderFields(HeaderVersionInfo(pv._1, pv._2), cert.isDefined, announcement)
    ).encode
    assertEquals(headerBody, flatHeaderBody, what)
    val signature = announcing.header.kesSignature
    assertEquals(
      assembly.block(headerBody, signature),
      LeiosBlock.encode(LeiosHeader.signed(flatHeaderBody, signature), flatBody),
      what
    )

  test("block 64,853 and its endorser block are forged the same both ways, and are the recorded") {
    val block = TxMeasure(89088L, BigInt(72000000), BigInt(20000000000L), 1048576L)
    sameBlocks(recorded, block, ebCapacity, None, "the testnet's capacities")
    // the era path's are the reference's: the ranking block and the endorser block recorded
    val (era, _) = snapshots(recorded)
    val split = BlockBuilder.partition(era, block, ebCapacity)
    assertEquals(split.ranking.chosen.size, 40)
    val assembly =
      BlockBuilder.Assembly.dijkstra(
        split.ranking.transactions,
        None,
        split.endorsing(announcing.header.slot)
      )
    val h = announcing.header.body
    val headerBody = assembly.headerBody(
      h.blockNo,
      h.slot,
      h.prevHash,
      h.issuerVkey,
      h.vrfVkey,
      h.vrf,
      h.opCert,
      (h.leios.versionInfo.highestSupportedMajor, h.leios.versionInfo.softwareTag)
    )
    assertEquals(assembly.block(headerBody, announcing.header.kesSignature), announcingRaw)
  }

  test("the partitions and blocks are the same both ways at every boundary, certifying or not") {
    def boundaries(pairs: Vector[Pair]): Vector[(TxMeasure, EbMeasure)] =
      val measures = pairs.map(p => Capacity.dijkstraMeasure(p.era, 0L))
      val ks =
        Vector(0, 1, 2, 23, 24, 40, pairs.size / 2, pairs.size).distinct.filter(_ <= pairs.size)
      ks.flatMap { k =>
        val total = measures.take(k).foldLeft(TxMeasure.zero)(_ + _)
        val rest = measures.drop(k).map(EbMeasure.of)
        val ebTotal = rest.take(k.max(1)).foldLeft(EbMeasure.zero)(_ + _)
        Vector(
          (total, ebCapacity),
          (total.copy(bytes = (total.bytes - 1).max(0)), ebCapacity),
          (total, ebTotal),
          (total, ebTotal.copy(references = (ebTotal.references - 1).max(0))),
          (total, EbMeasure.zero),
          (
            total,
            EbCapacityScale
              .of(xyz.kd5ujc.scarab.codec.Rational(1, 10))
              .fold(ebCapacity)(_(ebCapacity))
          )
        )
      } ++ Vector(
        (unbounded, ebCapacity),
        (TxMeasure.zero, ebCapacity),
        (TxMeasure.zero, EbMeasure(unbounded, Long.MaxValue))
      )
    Vector("the recorded" -> recorded, "the corpus" -> corpus).foreach { (label, pairs) =>
      boundaries(pairs).zipWithIndex.foreach { case ((block, eb), i) =>
        sameBlocks(pairs, block, eb, None, s"$label $i")
        // a certifying block partitions with a zero block limit (`partitionMempool`)
        sameBlocks(pairs, TxMeasure.zero, eb, Some(certificate), s"$label $i certifying")
      }
    }
  }

  test("the minimal block is the same both ways") {
    sameBlocks(Vector.empty, unbounded, ebCapacity, None, "empty")
    sameBlocks(Vector.empty, TxMeasure.zero, ebCapacity, Some(certificate), "empty certifying")
    assertEquals(
      BlockBuilder.Assembly.minimalDijkstra.size,
      FlatPath.rankingBody(Vector.empty, None).size.toLong
    )
  }
