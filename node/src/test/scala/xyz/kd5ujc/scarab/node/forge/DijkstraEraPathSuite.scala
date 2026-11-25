// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.consensus.leios.ProtVerParity
import cats.data.NonEmptyVector
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.bls.Certificate
import xyz.kd5ujc.scarab.ledger.{
  EbAnnouncement,
  HeaderVersionInfo,
  LeiosBlock,
  LeiosHeader,
  LeiosHeaderBody,
  LeiosHeaderFields,
  LedgerDecodeError,
  TestLedger
}
import xyz.kd5ujc.scarab.ledger.era.{
  BothLevels,
  EraTx,
  EraTxBody,
  EraTxError,
  IsPhase2Valid,
  Tx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraBlock, DijkstraCorpus, DijkstraTx}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{Capacity, DijkstraRead, EbCapacityScale, EbMeasure}
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.{EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.LeiosEb

/** The forger's Dijkstra path in the era's types (`Tx TopTx DijkstraEra`, #85) against what the
  * reference writes: the blocks, the endorser blocks, the transactions' bytes in them, the measures
  * and the partitions, byte for byte and value for value.
  *
  * The expected values are the recorded bytes and what the reference's definitions derive from them
  * ([[Expected]]), never the forger's own functions: a transaction's block form
  * (`toCBORForBlockInclusion`, cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:527-533`) and its size
  * (`toCBORForSizeComputation` and `sizeDijkstraTxF`, `Tx.hs:297-320`) from the parts the recorded
  * bytes hold, its execution units with its sub-transactions' (`getTotalExUnits`, `Tx.hs:417-422`,
  * which `DijkstraTxSuite` and `DijkstraUTxOSuite` check), a partition as the longest prefix whose
  * summed measure fits (`splitAfterTxSize`, ouroboros-consensus 2416493b
  * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/TxSeq.hs:211-231`), and
  * an endorser block as `forgeLeiosEb` makes it (`LeiosDemoTypes.hs:1257-1279` at
  * ouroboros-consensus d91b3557). The recorded block 64,853 and the endorser block it announces are
  * forged again, byte for byte, and `LeiosEncodingSuite` checks the block body's encoding on every
  * recorded Dijkstra block.
  *
  * The transactions are every Dijkstra transaction the repository holds that the build accepts: the
  * public testnet's blocks and the 323 of the endorser block of slot 1,455,869
  * (`fixtures/leios/testnet`), the corpus of the Leios build's verdicts
  * (`fixtures/reference/leios-testnet-blocks`), the reference cluster's chain and its submitted
  * transaction (`fixtures/dijkstra-devnet`), the script-context fixtures, recorded on the testnet's
  * chain with their sub-transactions, the UTXOW cases and the mempool forms of the crossing corpus.
  * Each is read as the node reads it: one of a block by the era's block decoder, one alone by the
  * era's transaction decoder, one submitted by the pool's read ([[DijkstraRead.submitted]]).
  */
class DijkstraEraPathSuite extends munit.FunSuite:
  import DijkstraCorpus.{hex, str}

  /** The parts of a transaction as recorded bytes hold them: the body, the witnesses and the
    * auxiliary data's bytes, and the flag of a block form (true in a submitted form).
    */
  private final case class Parts(body: Bytes, wits: Bytes, aux: Option[Bytes], valid: Boolean)

  /** The values the reference derives from recorded bytes, by its definitions. */
  private object Expected:
    private def head(major: Int, n: Long): Array[Byte] =
      val m = major << 5
      if n < 24 then Array((m | n.toInt).toByte)
      else if n < 256 then Array((m | 24).toByte, n.toByte)
      else if n < 65536 then Array((m | 25).toByte, (n >> 8).toByte, n.toByte)
      else Array((m | 26).toByte, (n >> 24).toByte, (n >> 16).toByte, (n >> 8).toByte, n.toByte)

    private val nullByte = Bytes.fromArray(Array(0xf6.toByte))

    private def flag(valid: Boolean): Bytes =
      Bytes.fromArray(Array((if valid then 0xf5 else 0xf4).toByte))

    /** The parts of a block form `[body, witnesses, auxiliary data / null, is_valid]`, a mempool
      * form `[body, witnesses, auxiliary data / null]` or a legacy submission `[body, witnesses,
      * true, auxiliary data / null]` (`decodeDijkstraTopTx`, `Tx.hs:147-169`, cardano-ledger
      * 9df9f98c).
      */
    def parts(raw: Bytes, inBlock: Boolean): Parts =
      def aux(n: Node) = Option.when(n.original != nullByte)(n.original)
      def bool(n: Node) = n.value match
        case Value.Bool(b) => b
        case other         => fail(s"not a flag: $other")
      Cbor.decode(raw).fold(e => fail(e.toString), identity).value match
        case Value.Arr(Vector(b, w, a, v)) if inBlock =>
          Parts(b.original, w.original, aux(a), bool(v))
        case Value.Arr(Vector(b, w, a)) if !inBlock => Parts(b.original, w.original, aux(a), true)
        case Value.Arr(Vector(b, w, v, a)) if !inBlock && bool(v) =>
          Parts(b.original, w.original, aux(a), true)
        case other => fail(s"not a transaction's form: $other")

    /** `toCBORForBlockInclusion` (`Tx.hs:527-533`, cardano-ledger 9df9f98c) with the flag `valid`.
      */
    def blockForm(p: Parts, valid: Boolean): Bytes =
      Bytes.fromArray(Array(0x84.toByte)) ++ p.body ++ p.wits ++ p.aux.getOrElse(nullByte) ++
        flag(valid)

    /** `toCBORForMempoolSubmission` and `toCBORForSizeComputation` (`Tx.hs:297-305, 500-515`,
      * cardano-ledger 9df9f98c): `[body, witnesses, auxiliary data / null]`, the flag omitted.
      */
    def mempoolForm(p: Parts): Bytes =
      Bytes.fromArray(Array(0x83.toByte)) ++ p.body ++ p.wits ++ p.aux.getOrElse(nullByte)

    /** The legacy submission `[body, witnesses, flag, auxiliary data / null]` (`Tx.hs:147-169`,
      * cardano-ledger 9df9f98c).
      */
    def legacy(p: Parts, valid: Boolean): Bytes =
      Bytes.fromArray(Array(0x84.toByte)) ++ p.body ++ p.wits ++ flag(valid) ++
        p.aux.getOrElse(nullByte)

    /** The pool's measure of a transaction: `sizeDijkstraTxF` (`Tx.hs:310-319`, cardano-ledger
      * 9df9f98c, the length of [[mempoolForm]]) plus `Capacity.PerTxOverhead`, `getTotalExUnits`
      * and the reference scripts' bytes.
      */
    def measure(p: Parts, units: ExUnits, refScriptBytes: Long): TxMeasure =
      TxMeasure(
        mempoolForm(p).size.toLong + Capacity.PerTxOverhead,
        units.mem,
        units.steps,
        refScriptBytes
      )

    /** A block body, `[transactions, Leios certificate / null, Peras certificate / null]`, the
      * transactions' list definite up to 23 items and indefinite past them, as the recorded block
      * 64,853 holds its 40.
      */
    def body(txs: Vector[Bytes], cert: Option[Certificate]): Bytes =
      val items = txs.map(_.toArray)
      val seq =
        if items.sizeIs <= 23 then head(4, items.size.toLong) +: items
        else (Array(0x9f.toByte) +: items) :+ Array(0xff.toByte)
      Bytes.wrap(
        (Vector(head(4, 3)) ++ seq ++ Vector(
          cert.fold(Array(0xf6.toByte))(_.encode.toArray),
          Array(0xf6.toByte)
        )).flatten.toArray
      )

    /** A ranking block's body: no transactions when it certifies. */
    def rankingBody(txs: Vector[Bytes], cert: Option[Certificate]): Bytes =
      body(cert.fold(txs)(_ => Vector.empty), cert)

    /** `forgeLeiosEb` (`LeiosDemoTypes.hs:1257-1279` at ouroboros-consensus d91b3557): the point,
      * the body of each transaction's hash and size, and the closure of the block forms.
      */
    def forge(
        slot: BigInt,
        txs: NonEmptyVector[Bytes]
    ): (EbPoint, LeiosEb, NonEmptyVector[(LeiosTxHash, Bytes)]) =
      val body = LeiosEb.of(txs)
      (
        EbPoint(Word64.clamped(slot), LeiosEb.hashOf(body.transactions.toVector)),
        body,
        txs.map(tx => LeiosEb.hashTx(tx) -> tx)
      )

    private def fits(m: TxMeasure, limit: TxMeasure): Boolean =
      m.bytes <= limit.bytes && m.exUnitsMemory <= limit.exUnitsMemory &&
        m.exUnitsSteps <= limit.exUnitsSteps && m.refScriptsBytes <= limit.refScriptsBytes

    /** `splitAfterTxSize` (`TxSeq.hs:211-231` at ouroboros-consensus 2416493b): how many of the
      * first measures fit `limit` together, every component of their sum at most the limit's.
      */
    def cut(measures: Vector[TxMeasure], limit: TxMeasure): Int =
      measures.scanLeft(TxMeasure.zero)(_ + _).drop(1).takeWhile(fits(_, limit)).size

    /** The same for an endorser block, `DijkstraEbMeasure`'s order pointwise. */
    def ebCut(measures: Vector[EbMeasure], limit: EbMeasure): Int =
      measures
        .scanLeft(EbMeasure.zero)(_ + _)
        .drop(1)
        .takeWhile(m => fits(m.closure, limit.closure) && m.references <= limit.references)
        .size

  /** A transaction the node read, with the parts of the recorded bytes it was read from. */
  private final case class Item(name: String, parts: Parts, era: Tx[TxLevel.Top, Dijkstra]):
    def blockForm: Bytes = Expected.blockForm(parts, parts.valid)
    def units: ExUnits = EraTx.dijkstra.getTotalExUnits[TxLevel.Top](era)(using BothLevels.Top)

  private def inBlock(name: String, raw: Bytes): Option[Item] =
    Cbor
      .decode(raw)
      .toOption
      .flatMap(n => DijkstraTx.inBlock(n).toOption)
      .map(era => Item(name, Expected.parts(raw, inBlock = true), era))

  /** A submitted transaction, read as the pool reads it: the pool relays its mempool form. */
  private def submitted(name: String, raw: Bytes): Option[Item] =
    DijkstraRead.submitted(raw).toOption.map { r =>
      val parts = Expected.parts(raw, inBlock = false)
      assertEquals(r.raw, Expected.mempoolForm(parts), s"$name: the mempool form")
      assertEquals(r.size, Expected.mempoolForm(parts).size.toLong, name)
      Item(name, parts, r.tx)
    }

  /** The transactions of a block `[8, [header, [transactions, certificate, Peras]]]`, read by the
    * era's block decoder, with the bytes the block holds for each.
    */
  private def ofBlock(name: String, raw: Bytes): Vector[Item] =
    DijkstraBlock
      .decode(raw)
      .fold(
        _ => Vector.empty,
        b =>
          def items(n: Node): Vector[Node] = n.value match
            case Value.Arr(xs) => xs
            case other         => fail(s"$name: not an array: $other")
          val root = Cbor.decode(raw).fold(e => fail(e.toString), identity)
          val recorded = items(items(items(items(root)(1))(1))(0)).map(_.original)
          val txs = b.blockBody.txs
          assertEquals(txs.size, recorded.size, name)
          txs.zip(recorded).zipWithIndex.map { case ((era, bytes), i) =>
            Item(s"$name tx $i", Expected.parts(bytes, inBlock = true), era)
          }
      )

  private val testnet = Path.of("fixtures", "leios", "testnet")

  private lazy val announcingRaw: Bytes =
    DijkstraCorpus.testnetBlocks
      .collectFirst { case ("announcing-with-txs", raw) => raw }
      .getOrElse(fail("no block 64,853"))

  private lazy val announcing: LeiosBlock =
    LeiosBlock.decode(announcingRaw).fold(e => fail(e.message), identity)

  /** The closure of the endorser block block 64,853 announces, in its order, as recorded. */
  private lazy val closureBytes: Vector[Bytes] =
    val text = new String(
      new GZIPInputStream(
        new ByteArrayInputStream(Files.readAllBytes(testnet.resolve("eb-1455869.tsv.gz")))
      ).readAllBytes(),
      StandardCharsets.US_ASCII
    )
    text.linesIterator
      .map(_.split('\t').toVector)
      .collect { case Vector("tx", _, t) => hex(t) }
      .toVector

  private lazy val closure: Vector[Item] =
    closureBytes.zipWithIndex.map((raw, i) =>
      inBlock(s"closure $i", raw).getOrElse(fail(s"closure $i: refused"))
    )

  /** Block 64,853's 40 transactions, then the 323 of the endorser block it announces. */
  private lazy val recorded: Vector[Item] = ofBlock("block 64,853", announcingRaw) ++ closure

  /** The submitted transactions: the devnet's and the crossing corpus's mempool forms. */
  private lazy val forms: Vector[(String, Bytes)] =
    (DijkstraCorpus.devnetSubmitted +: DijkstraCorpus.crossingForms).zipWithIndex
      .map((raw, i) => (s"submitted $i", raw))

  /** Every Dijkstra transaction the repository holds that the build accepts. */
  private lazy val corpus: Vector[Item] =
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
    blocks ++ closure ++ alone ++ utxow ++ forms.flatMap(submitted)

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
    // every accepted transaction of the fixtures: the blocks' 104 (the testnet's 40, the devnet's
    // one and the Leios corpus's 63), the closure's 323, the 484 alone (the Leios corpus's 273, the
    // contexts' 11 and the chain's 200), the 12 distinct UTXOW transactions and the 150 submitted
    assertEquals(corpus.size, 104 + 323 + 484 + 12 + 150)
    assert(corpus.exists(_.parts.aux.isDefined))
    assert(corpus.exists(_.parts.aux.isEmpty))
    assert(
      corpus.exists(i =>
        EraTxBody.dijkstra
          .subTransactions(EraTx.dijkstra.body[TxLevel.Top](i.era)(using BothLevels.Top))
          .toVector
          .nonEmpty
      )
    )
    assert(corpus.exists(_.units != ExUnits.zero))
    // more than 23 transactions in a block, written indefinite
    assert(recorded.size > 23)
  }

  test("each transaction's block form, flag, id, measure and Leios key are the reference's") {
    corpus.foreach { case item @ Item(name, parts, era) =>
      assertEquals(era.id, Blake2b.hash256.hash(parts.body), name)
      // the bytes a block and an endorser block hold, `Entry.leiosTx` 's bytes and key
      val bytes = DijkstraTx.forBlockInclusion(era)
      assertEquals(bytes, item.blockForm, name)
      assertEquals(
        LeiosEb.hashTx(bytes),
        LeiosTxHash.fromBytes(Blake2b.hash256.hash(item.blockForm))
      )
      // either flag, the refused script's one of which no recorded transaction carries
      IsPhase2Valid.values.foreach(v =>
        assertEquals(
          DijkstraTx.forBlockInclusion(DijkstraTx.withIsValid(era, v)),
          Expected.blockForm(parts, v == IsPhase2Valid.Phase2Valid),
          s"$name $v"
        )
      )
      Vector(0L, 7L, 1048576L).foreach(r =>
        assertEquals(
          Capacity.dijkstraMeasure(era, r),
          Expected.measure(parts, item.units, r),
          s"$name $r"
        )
      )
      // the mempool form, as the pool relays and lists a block's transaction once submitted
      if parts.valid then
        val form = Expected.mempoolForm(parts)
        DijkstraRead.submitted(form) match
          case Right(read) =>
            assertEquals(read.raw, form, name)
            assertEquals(read.id, era.id, name)
          case Left(e) => fail(s"$name: $e")
    }
  }

  test(
    "a legacy submission with the flag true is relayed in the mempool form; with false refused"
  ) {
    // `decodeDijkstraTopTx` (`Tx.hs:147-169`, cardano-ledger 9df9f98c): `[body, witnesses, true,
    // auxiliary data / null]` is read as the transaction with its flag valid, which the pool relays
    // as `[body, witnesses, auxiliary data / null]`; `false` is refused
    val accepted =
      forms.flatMap((name, raw) => DijkstraRead.submitted(raw).toOption.map((name, raw, _)))
    val checked = accepted.map { (name, raw, read) =>
      val parts = Expected.parts(raw, inBlock = false)
      val form = Expected.mempoolForm(parts)
      DijkstraRead.submitted(Expected.legacy(parts, valid = true)) match
        case Right(legacy) =>
          assertEquals(legacy.raw, form, name)
          assertEquals(legacy.id, read.id, name)
          assertEquals(legacy.isValid, IsPhase2Valid.Phase2Valid, name)
          assertEquals(DijkstraTx.forBlockInclusion(legacy.tx), Expected.blockForm(parts, true))
        case Left(e) => fail(s"$name: the legacy form refused: $e")
      DijkstraRead.submitted(Expected.legacy(parts, valid = false)) match
        case Left(EraTxError.Decode(LedgerDecodeError.InvalidFlagSubmitted)) => ()
        case other => fail(s"$name: the flag false not refused as the reference refuses it: $other")
      parts.aux.isDefined
    }
    // every submitted form the repository holds, four of them with auxiliary data
    assertEquals(accepted.size, forms.size)
    assertEquals((checked.size, checked.count(identity)), (150, 4))
  }

  /** The snapshot of `items` as the era's forger holds it, each measured with `refScriptBytes`. */
  private def snapshot(items: Vector[Item], refScriptBytes: Long): DijkstraSnapshot =
    DijkstraSnapshot(
      items.map(i => DijkstraHeld(i.era, Capacity.dijkstraMeasure(i.era, refScriptBytes)))
    )

  /** The ranking block of a partition, its header body and the block, and the endorser block it
    * announces, under the header of block 64,853, against the expected: the transactions of the
    * longest prefix that fits each limit, and the bytes the reference writes of their block forms.
    * The result is how many the ranking block and the endorser block take.
    */
  private def sameBlocks(
      items: Vector[Item],
      blockLimit: TxMeasure,
      ebLimit: EbMeasure,
      cert: Option[Certificate],
      what: String,
      refScriptBytes: Long = 0L
  ): (Int, Int) =
    val measures = items.map(i => Expected.measure(i.parts, i.units, refScriptBytes))
    val k = Expected.cut(measures, blockLimit)
    val e = Expected.ebCut(measures.drop(k).map(EbMeasure.of), ebLimit)
    val inBlock = items.take(k)
    val inEb = items.slice(k, k + e)
    val split = BlockBuilder.partition(snapshot(items, refScriptBytes), blockLimit, ebLimit)
    // the partition: the same transactions, in order, counting for the same
    assertEquals(split.ranking.transactions.map(_.id), inBlock.map(_.era.id), what)
    assertEquals(split.ranking.used, measures.take(k).foldLeft(TxMeasure.zero)(_ + _), what)
    assertEquals(split.endorsedTransactions.map(_.id), inEb.map(_.era.id), what)
    assertEquals(
      split.endorsedUsed,
      measures.slice(k, k + e).map(EbMeasure.of).foldLeft(EbMeasure.zero)(_ + _),
      what
    )
    val slot = announcing.header.slot
    val assembly =
      BlockBuilder.Assembly.dijkstra(
        split.ranking.transactions,
        cert,
        split.endorsing(slot, ProtVerParity.Even)
      )
    // the endorser block: its point, body, closure and announcement
    val expectedEb = NonEmptyVector.fromVector(inEb.map(_.blockForm)).map(Expected.forge(slot, _))
    val announcement = assembly match
      case BlockBuilder.Assembly.InDijkstra(_, eb) =>
        assertEquals(
          eb.map(x => (x.forged.point, x.forged.body, x.forged.closure)),
          expectedEb,
          what
        )
        eb.map(_.forged.announcement)
      case other => fail(s"$what: not a Dijkstra body: $other")
    assertEquals(
      announcement,
      expectedEb.map((point, body, _) => EbAnnouncement(point.hash, body.encodedSize)),
      what
    )
    // the body, the header body and the block
    val expectedBody = Expected.rankingBody(inBlock.map(_.blockForm), cert)
    assertEquals(assembly.size, expectedBody.size.toLong, what)
    assertEquals(assembly.ids, cert.fold(inBlock.map(_.era.id))(_ => Vector.empty), what)
    assertEquals(assembly.endorsedIds, inEb.map(_.era.id), what)
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
    val expectedHeaderBody = LeiosHeaderBody(
      h.blockNo,
      h.slot,
      h.prevHash,
      h.issuerVkey,
      h.vrfVkey,
      h.vrf,
      BigInt(expectedBody.size),
      Blake2b.hash256.hash(expectedBody),
      h.opCert,
      LeiosHeaderFields(HeaderVersionInfo(pv._1, pv._2), cert.isDefined, announcement)
    ).encode
    assertEquals(headerBody, expectedHeaderBody, what)
    val signature = announcing.header.kesSignature
    assertEquals(
      assembly.block(headerBody, signature),
      LeiosBlock.encode(LeiosHeader.signed(expectedHeaderBody, signature), expectedBody),
      what
    )
    (k, e)

  test("block 64,853 and its endorser block are forged again, byte for byte the recorded") {
    val block = TxMeasure(89088L, BigInt(72000000), BigInt(20000000000L), 1048576L)
    // the block takes its 40 transactions and the endorser block the 323 of its closure
    assertEquals(
      sameBlocks(recorded, block, ebCapacity, None, "the testnet's capacities"),
      (40, 323)
    )
    // the recorded bytes: the block's transactions and the closure as the block and the endorser
    // block hold them
    assertEquals(recorded.drop(40).map(_.blockForm), closureBytes)
    // the body the derived encoding writes of them is the one the header commits to
    val body = Expected.body(recorded.take(40).map(_.blockForm), None)
    assertEquals(BigInt(body.size), announcing.header.body.bodySize)
    assertEquals(Blake2b.hash256.hash(body), announcing.header.body.bodyHash)
    val split = BlockBuilder.partition(snapshot(recorded, 0L), block, ebCapacity)
    val assembly =
      BlockBuilder.Assembly.dijkstra(
        split.ranking.transactions,
        None,
        split.endorsing(announcing.header.slot, ProtVerParity.Even)
      )
    // the endorser block is the one the header announces
    val eb = assembly match
      case BlockBuilder.Assembly.InDijkstra(_, Some(e)) => e.forged
      case other                                        => fail(s"no endorser block: $other")
    assertEquals(Some(eb.announcement), announcing.header.body.leios.announcement)
    assertEquals(eb.closure.map(_._2).toVector, closureBytes)
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

  test("the partitions and blocks are the reference's at every boundary, certifying or not") {

    /** The limits at each boundary of a prefix of `k`, with the counts the ranking block and the
      * endorser block take at it where the boundary decides them.
      */
    def boundaries(items: Vector[Item]): Vector[(TxMeasure, EbMeasure, Option[(Int, Int)])] =
      val measures = items.map(i => Expected.measure(i.parts, i.units, 0L))
      val ks =
        Vector(0, 1, 2, 23, 24, 40, items.size / 2, items.size).distinct.filter(_ <= items.size)
      ks.flatMap { k =>
        val total = measures.take(k).foldLeft(TxMeasure.zero)(_ + _)
        val rest = measures.drop(k).map(EbMeasure.of)
        val n = k.max(1).min(rest.size)
        val ebTotal = rest.take(k.max(1)).foldLeft(EbMeasure.zero)(_ + _)
        // an exact fit takes the k, and the endorser block's the n after them; a byte or a
        // reference's byte less takes one fewer, as every transaction has bytes
        Vector(
          (total, ebCapacity, None),
          (total.copy(bytes = (total.bytes - 1).max(0)), ebCapacity, None),
          (total, ebTotal, Some((k, n))),
          (
            total,
            ebTotal.copy(references = (ebTotal.references - 1).max(0)),
            Some((k, (n - 1).max(0)))
          ),
          (total, EbMeasure.zero, Some((k, 0))),
          (
            total,
            EbCapacityScale
              .of(xyz.kd5ujc.scarab.codec.Rational(1, 10))
              .fold(ebCapacity)(_(ebCapacity)),
            None
          )
        ) ++ Option
          .when(k > 0)((total.copy(bytes = total.bytes - 1), EbMeasure.zero, Some((k - 1, 0))))
          .toVector
      } ++ Vector(
        (unbounded, ebCapacity, Some((items.size, 0))),
        (TxMeasure.zero, ebCapacity, None),
        (TxMeasure.zero, EbMeasure(unbounded, Long.MaxValue), Some((0, items.size)))
      )
    Vector("the recorded" -> recorded, "the corpus" -> corpus).foreach { (label, items) =>
      boundaries(items).zipWithIndex.foreach { case ((block, eb, counts), i) =>
        val taken = sameBlocks(items, block, eb, None, s"$label $i")
        counts.foreach(c => assertEquals(taken, c, s"$label $i"))
        // a certifying block partitions with a zero block limit (`partitionMempool`)
        val certifying = sameBlocks(items, TxMeasure.zero, eb, Some(certificate), s"$label $i cert")
        assertEquals(certifying._1, 0, s"$label $i certifying")
      }
    }
  }

  test("the reference scripts' bytes bind a partition as the other components do") {
    // every transaction counted with 1,000 bytes of reference scripts: a block limit of k times
    // that, every other component unbounded, takes k, and the endorser block's closure the same
    val r = 1000L
    val items = recorded.take(40)
    Vector(0, 1, 7, 23, 40).foreach { k =>
      val limit = unbounded.copy(refScriptsBytes = k * r)
      assertEquals(sameBlocks(items, limit, EbMeasure.zero, None, s"block $k", r), (k, 0))
      val ebLimit = EbMeasure(unbounded.copy(refScriptsBytes = k * r), Long.MaxValue)
      assertEquals(
        sameBlocks(items, TxMeasure.zero, ebLimit, None, s"endorser block $k", r),
        (0, k)
      )
      if k > 0 then
        val tight = unbounded.copy(refScriptsBytes = k * r - 1)
        assertEquals(sameBlocks(items, tight, EbMeasure.zero, None, s"block $k - 1", r), (k - 1, 0))
    }
  }

  test("the minimal block is the reference's") {
    assertEquals(sameBlocks(Vector.empty, unbounded, ebCapacity, None, "empty"), (0, 0))
    assertEquals(
      sameBlocks(Vector.empty, TxMeasure.zero, ebCapacity, Some(certificate), "empty certifying"),
      (0, 0)
    )
    // the 4 bytes of the first block's body (`LeiosEncodingSuite`)
    assertEquals(BlockBuilder.Assembly.minimalDijkstra.size, 4L)
    assertEquals(Expected.rankingBody(Vector.empty, None).hex, "8380f6f6")
  }
