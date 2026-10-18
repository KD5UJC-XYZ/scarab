// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.io.{BufferedInputStream, DataInputStream, FileInputStream}
import java.nio.file.Path
import java.util.Arrays
import munit.FunSuite
import org.rocksdb.{Options, RocksDB}
import scala.collection.mutable.ArrayBuffer
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TxOut
import xyz.kd5ujc.scarab.ledger.mempack.{MemPack, TxOutMemPack, TxOutParts}
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.storage.haskell.state.UtxoCodecs

/** The MemPack `TxOut` codec against a real `tables` file and the node's own UTxO values.
  *
  * The `tables` file of a Haskell snapshot (`SCARAB_MEMPACK_TABLES`, a Conway one) is read in one
  * pass, entry by entry, with the codec's decoder and encoder (every value must decode, and pack
  * back to its bytes or to the same output). With a RocksDB UTxO checkpoint of this node
  * (`SCARAB_MEMPACK_CHECKPOINT`, keys of 34 bytes) the two sorted streams are joined by key: for
  * every output both have, the stored CBOR value and the MemPack value must be the same output, and
  * the codec must pack the one into the bytes of the other. `SCARAB_MEMPACK_BENCH` adds the
  * measurements of `docs/design/haskell-snapshots.md` section 8: bytes and nanoseconds per output
  * of the stored CBOR value against the MemPack one over the joined set.
  *
  * Not part of `check`: it runs when the variables are set. Needs a heap of 6 GB for the joined set
  * of the benchmark (`-J-Xmx6g` of sbt, or a forked JVM).
  */
class MemPackJoinSuite extends FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(60, "min")

  private val tables = sys.env.get("SCARAB_MEMPACK_TABLES")
  private val checkpoint = sys.env.get("SCARAB_MEMPACK_CHECKPOINT")

  test("the whole tables file decodes, and packs back") {
    assume(tables.nonEmpty, "SCARAB_MEMPACK_TABLES names a Conway tables file")
    tables.foreach { t =>
      val report = MemPackJoin.scanTables(Path.of(t))
      println(report)
      assertEquals(report.undecodable, 0L)
      assertEquals(report.differentOutput, 0L)
    }
  }

  test("the stored values and the table's are the same outputs") {
    assume(
      tables.nonEmpty && checkpoint.nonEmpty,
      "SCARAB_MEMPACK_TABLES and SCARAB_MEMPACK_CHECKPOINT"
    )
    for t <- tables; c <- checkpoint do
      val report =
        MemPackJoin.join(Path.of(t), Path.of(c), sys.env.contains("SCARAB_MEMPACK_BENCH"))
      println(report)
      assertEquals(report.differentOutput, 0L)
  }

object MemPackJoin:

  /** What a pass over the table found. */
  final case class ScanReport(
      entries: Long,
      undecodable: Long,
      differentOutput: Long,
      differentBytes: Long,
      byTag: Map[Int, Long],
      mempackBytes: Long,
      canonicalCborBytes: Long,
      decodePartsNanos: Long,
      decodeOutNanos: Long,
      encodeNanos: Long
  ):
    override def toString: String =
      val per = (n: Long) => f"${n.toDouble / math.max(entries, 1)}%.0f"
      s"""SCAN entries=$entries undecodable=$undecodable differentOutput=$differentOutput differentBytes=$differentBytes
         |SCAN by tag: ${byTag.toSeq.sorted.map((t, n) => s"$t:$n").mkString(" ")}
         |SCAN bytes: mempack=$mempackBytes (${f"${mempackBytes.toDouble / math.max(entries, 1)}%.1f"}/output)  canonical cbor value=$canonicalCborBytes (${f"${canonicalCborBytes.toDouble / math.max(entries, 1)}%.1f"}/output)
         |SCAN ns/output: decode to parts ${per(
          decodePartsNanos
        )}  decode to TxOut (with raw) ${per(decodeOutNanos)}  encode ${per(
          encodeNanos
        )}""".stripMargin

  /** The entries of a `tables` file in file order: the 34-byte key and the MemPack value. */
  def entries(path: Path): Iterator[(Array[Byte], Array[Byte])] =
    val in = new DataInputStream(new BufferedInputStream(new FileInputStream(path.toFile), 1 << 20))
    require(in.readUnsignedByte() == 0x81, "tables: an array of one map")
    val head = in.readUnsignedByte()
    require(head >> 5 == 5, "tables: a map")
    val indefinite = head == 0xbf
    val total: Long =
      if indefinite then -1L
      else
        (head & 0x1f) match
          case n if n < 24 => n.toLong
          case 24          => in.readUnsignedByte().toLong
          case 25          => in.readUnsignedShort().toLong
          case 26          => in.readInt() & 0xffffffffL
          case _           => in.readLong()
    def byteString(): Array[Byte] =
      val h = in.readUnsignedByte()
      require(h >> 5 == 2, "tables: a byte string")
      val n = (h & 0x1f) match
        case n if n < 24 => n
        case 24          => in.readUnsignedByte()
        case 25          => in.readUnsignedShort()
        case _           => in.readInt()
      val a = new Array[Byte](n)
      in.readFully(a)
      a
    new Iterator[(Array[Byte], Array[Byte])]:
      private var left = total
      // the head byte of the next key, read ahead by `hasNext` of an indefinite map
      private var ahead = -1
      private var finished = false
      private def end(): Boolean =
        finished = true
        in.close()
        false
      def hasNext: Boolean =
        if finished then false
        else if indefinite then
          if ahead >= 0 then true
          else
            val b = in.read()
            if b == 0xff || b < 0 then end()
            else
              ahead = b
              true
        else if left > 0 then true
        else end()
      def next(): (Array[Byte], Array[Byte]) =
        val first = if ahead >= 0 then ahead else in.readUnsignedByte()
        ahead = -1
        require(first == 0x58, s"tables: a key of 34 bytes (head $first)")
        require(in.readUnsignedByte() == 34)
        val key = new Array[Byte](34)
        in.readFully(key)
        val value = byteString()
        left -= 1
        (key, value)

  private def unsignedCompare(a: Array[Byte], b: Array[Byte]): Int =
    Arrays.compareUnsigned(a, b)

  def scanTables(path: Path): ScanReport =
    var n, bad, different, differentBytes = 0L
    var mempackBytes, cborBytes, tParts, tOut, tEnc = 0L
    val byTag = scala.collection.mutable.Map.empty[Int, Long].withDefaultValue(0L)
    entries(path).foreach { (_, value) =>
      n += 1
      byTag(value(0) & 0xff) += 1
      mempackBytes += value.length
      val bytes = Bytes.wrap(value)
      val t0 = System.nanoTime()
      val parts = TxOutMemPack.decodeParts[Conway](bytes)
      val t1 = System.nanoTime()
      val out = TxOutMemPack.decode[Conway](bytes)
      val t2 = System.nanoTime()
      tParts += t1 - t0
      tOut += t2 - t1
      out match
        case Left(_) => bad += 1
        case Right(o) =>
          cborBytes += o.raw.size + 1
          val t3 = System.nanoTime()
          val back = TxOutMemPack.encode[Conway](o)
          tEnc += System.nanoTime() - t3
          back match
            case Right(b) if b == bytes => ()
            case Right(b) =>
              differentBytes += 1
              if TxOutMemPack.decode[Conway](b) != Right(o) then different += 1
            case Left(_) => different += 1
      if parts.isLeft && out.isRight then different += 1
    }
    ScanReport(
      n,
      bad,
      different,
      differentBytes,
      byTag.toMap,
      mempackBytes,
      cborBytes,
      tParts,
      tOut,
      tEnc
    )

  /** Where the canonical `raw` of a decoded output differs from the original encoding the node
    * stored, in what the node's rules and answers read from it (counts over the joined outputs).
    */
  final case class Rebuilt(
      raw: Long,
      ledgerSize: Long,
      referenceScriptSize: Long,
      referenceScript: Long,
      valueSize: Long,
      nodeToClient: Long
  )

  /** What the join found. */
  final case class JoinReport(
      stored: Long,
      table: Long,
      common: Long,
      onlyStored: Long,
      onlyTable: Long,
      undecodable: Long,
      differentOutput: Long,
      differentBytes: Long,
      rebuilt: Rebuilt,
      samples: List[String],
      bench: Option[String]
  ):
    override def toString: String =
      s"""JOIN stored=$stored table=$table common=$common onlyStored=$onlyStored onlyTable=$onlyTable
         |JOIN undecodable=$undecodable differentOutput=$differentOutput differentBytes=$differentBytes
         |JOIN canonical raw against the stored original: $rebuilt
         |${samples.map("JOIN sample " + _).mkString("\n")}
         |${bench.getOrElse("")}""".stripMargin

  private def partsOf(o: TxOut): Either[MemPack.Failure, TxOutParts] = TxOutMemPack.parts(o)

  def join(tablesPath: Path, checkpointPath: Path, bench: Boolean): JoinReport =
    RocksDB.loadLibrary()
    val options = new Options()
    val db = RocksDB.openReadOnly(options, checkpointPath.toString)
    val it = db.newIterator()
    it.seekToFirst()
    // The outputs have keys of 34 bytes (txid and a big-endian u16), or 36 in the checkpoints of the
    // builds before K6 (the index a u32, see `RocksUtxoStore.rekeyInto`); the stamp's key is one byte.
    def nextStored(): Option[(Array[Byte], Array[Byte])] =
      while it.isValid && it.key().length != 34 && it.key().length != 36 do it.next()
      if it.isValid then
        val k = it.key()
        val key =
          if k.length == 34 then k
          else
            val c = new Array[Byte](34)
            System.arraycopy(k, 0, c, 0, 32)
            c(32) = k(34)
            c(33) = k(35)
            c
        val r = (key, it.value())
        it.next()
        Some(r)
      else None
    val table = entries(tablesPath)
    def nextTable(): Option[(Array[Byte], Array[Byte])] =
      if table.hasNext then Some(table.next()) else None

    var stored, tableN, common, onlyStored, onlyTable, bad, different, differentBytes = 0L
    var rawD, sizeD, scriptSizeD, scriptD, valueSizeD, n2cD = 0L
    val kinds = scala.collection.mutable.Map.empty[String, Long].withDefaultValue(0L)
    val sizeSamples = ArrayBuffer.empty[String]
    val samples = ArrayBuffer.empty[String]
    val cborSide = ArrayBuffer.empty[Array[Byte]]
    val packSide = ArrayBuffer.empty[Array[Byte]]
    val keep = 400000

    var s = nextStored()
    var t = nextTable()
    s.foreach(_ => stored += 1)
    t.foreach(_ => tableN += 1)
    while s.isDefined || t.isDefined do
      val c =
        if s.isEmpty then 1
        else if t.isEmpty then -1
        else unsignedCompare(s.get._1, t.get._1)
      if c < 0 then
        onlyStored += 1
        s = nextStored()
        s.foreach(_ => stored += 1)
      else if c > 0 then
        onlyTable += 1
        t = nextTable()
        t.foreach(_ => tableN += 1)
      else
        common += 1
        val (key, cbor) = s.get
        val (_, pack) = t.get
        val stored1 = UtxoStore.Coding.decodeValue(cbor)
        val decoded = TxOutMemPack.decode[Conway](Bytes.wrap(pack))
        (stored1, decoded) match
          case (Right(a), Right(b)) =>
            if partsOf(a) != partsOf(b) then
              different += 1
              if samples.size < 6 then
                samples += s"${Bytes.wrap(key).hex} cbor=${a.raw.hex.take(160)} pack=${Bytes.wrap(pack).hex.take(160)}"
            else
              // what kinds of difference: the first byte of the original and of the canonical encoding
              if a.raw != b.raw then
                rawD += 1
                kinds(f"${a.raw(0) & 0xff}%02x->${b.raw(0) & 0xff}%02x") += 1
              if a.ledgerSize != b.ledgerSize then
                sizeD += 1
                if sizeSamples.size < 4 then
                  sizeSamples += s"ledgerSize ${a.ledgerSize} against ${b.ledgerSize}: ${a.raw.hex.take(240)} | ${b.raw.hex.take(240)}"
              if a.referenceScriptSize != b.referenceScriptSize then scriptSizeD += 1
              if a.referenceScript.map(x => (x.kind, x.hash, x.plutus)) !=
                  b.referenceScript.map(x => (x.kind, x.hash, x.plutus))
              then scriptD += 1
              if a.valueSize != b.valueSize then valueSizeD += 1
              if UtxoCodecs.txOut.encodeUnchecked(a) != UtxoCodecs.txOut.encodeUnchecked(b) then
                n2cD += 1
                sizeSamples += s"n2c differs: ${a.raw.hex.take(240)} | ${b.raw.hex.take(240)}"
              TxOutMemPack.encode[Conway](a) match
                case Right(bytes) if bytes == Bytes.wrap(pack) => ()
                case _                                         => differentBytes += 1
          case _ =>
            bad += 1
            if samples.size < 6 then
              samples += s"${Bytes.wrap(key).hex} undecodable: stored=${stored1.left.toOption} table=${decoded.left.toOption}"
        if bench && cborSide.size < keep then
          cborSide += cbor
          packSide += pack
        s = nextStored()
        s.foreach(_ => stored += 1)
        t = nextTable()
        t.foreach(_ => tableN += 1)
    it.close()
    db.close()
    options.close()
    JoinReport(
      stored,
      tableN,
      common,
      onlyStored,
      onlyTable,
      bad,
      different,
      differentBytes,
      Rebuilt(rawD, sizeD, scriptSizeD, scriptD, valueSizeD, n2cD),
      samples.toList ++ sizeSamples.toList ++ List(
        s"raw first bytes (original->canonical): ${kinds.toSeq.sorted.mkString(" ")}"
      ),
      if bench then Some(measure(cborSide.toVector, packSide.toVector)) else None
    )

  @volatile private var sink = 0L

  private def median(xs: Vector[Double]): Double = xs.sorted.apply(xs.size / 2)

  private def measureNs(units: Int)(work: => Long): Double =
    (0 until 3).foreach(_ => sink += work)
    median(Vector.fill(7) {
      val t0 = System.nanoTime()
      sink += work
      (System.nanoTime() - t0).toDouble / units
    })

  /** Bytes and median ns per output of the stored CBOR value and the MemPack one over the same
    * outputs.
    */
  def measure(cbor: Vector[Array[Byte]], pack: Vector[Array[Byte]]): String =
    val n = cbor.size
    val cborBytes = cbor.map(_.length.toLong).sum
    val packBytes = pack.map(_.length.toLong).sum
    val outs = cbor.map(c => UtxoStore.Coding.decodeValue(c).toOption.get)
    val packed = pack.map(Bytes.wrap)
    val partsV = packed.map(p => TxOutMemPack.decodeParts[Conway](p).toOption.get)
    val rounds = 3
    def round(): (Double, Double, Double, Double, Double, Double) =
      (
        measureNs(n)(
          cbor.foldLeft(0L)((a, c) =>
            a + UtxoStore.Coding.decodeValue(c).toOption.get.value.coin.toLong
          )
        ),
        measureNs(n)(
          packed.foldLeft(0L)((a, p) =>
            a + TxOutMemPack.decodeParts[Conway](p).toOption.get.value.coin.toLong
          )
        ),
        measureNs(n)(
          packed.foldLeft(0L)((a, p) =>
            a + TxOutMemPack.decode[Conway](p).toOption.get.value.coin.toLong
          )
        ),
        measureNs(n)(outs.foldLeft(0L)((a, o) => a + UtxoStore.Coding.encodeValue(o).length)),
        measureNs(n)(
          outs.foldLeft(0L)((a, o) => a + TxOutMemPack.encode[Conway](o).toOption.get.size)
        ),
        measureNs(n)(
          partsV.foldLeft(0L)((a, p) => a + TxOutMemPack.encodeParts[Conway](p).toOption.get.size)
        )
      )
    val rs = (1 to rounds).map(_ => round())
    def col(f: ((Double, Double, Double, Double, Double, Double)) => Double) = median(
      rs.map(f).toVector
    )
    f"""BENCH outputs=$n
       |BENCH bytes/output: stored cbor value ${cborBytes.toDouble / n}%.1f  mempack ${packBytes.toDouble / n}%.1f  (mempack / cbor ${packBytes.toDouble / cborBytes}%.3f)
       |BENCH ns/output decode: cbor value to TxOut ${col(_._1)}%.0f   mempack to parts ${col(
        _._2
      )}%.0f   mempack to TxOut with raw ${col(_._3)}%.0f
       |BENCH ns/output encode: cbor value from TxOut ${col(_._4)}%.0f   mempack from TxOut ${col(
        _._5
      )}%.0f   mempack from parts ${col(_._6)}%.0f
       |BENCH checksum of results: $sink""".stripMargin

  def main(args: Array[String]): Unit =
    args.toList match
      case "scan" :: tables :: Nil => println(scanTables(Path.of(tables)))
      case "join" :: tables :: checkpoint :: rest =>
        println(join(Path.of(tables), Path.of(checkpoint), rest.contains("bench")))
      case _ =>
        println("usage: MemPackJoin scan <tables> | join <tables> <checkpoint> [bench]")
