// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs.{bytes as byteVector, constant, ignore, int32, int64, uint8}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.protocol.Mux
import xyz.kd5ujc.scarab.storage.haskell.*

/** Decoding throughput of the scodec codecs against the `ByteBuffer` parsers they replaced
  * ([[LegacyFormats]]), in one JVM with interleaved rounds. Not part of `check`: it runs when
  * `SCARAB_BENCH` is set, and uses the index files of the Haskell ImmutableDB named by
  * `SCARAB_K1_DB` for the Haskell formats. Prints median nanoseconds per record.
  */
class FormatBenchSuite extends FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(30, "min")

  private val enabled = sys.env.contains("SCARAB_BENCH")
  private val warmups = 5
  private val runs = 10
  private val rounds = 3

  /** Keeps results alive so the JIT cannot discard the work. */
  @volatile private var sink = 0L

  override def afterAll(): Unit = if enabled then println(s"BENCH checksum of results: $sink")

  private def median(xs: Vector[Double]): Double = xs.sorted.apply(xs.size / 2)

  /** Median ns per unit over `runs` timed runs after `warmups` untimed ones. */
  private def measure(units: Long)(work: => Long): Double =
    (0 until warmups).foreach(_ => sink += work)
    median(Vector.fill(runs) {
      val t0 = System.nanoTime()
      sink += work
      (System.nanoTime() - t0).toDouble / units
    })

  private def compare(label: String, units: Long)(legacy: => Long)(scodec: => Long): Unit =
    val rs = (1 to rounds).map(_ => (measure(units)(legacy), measure(units)(scodec)))
    val l = median(rs.map(_._1).toVector)
    val s = median(rs.map(_._2).toVector)
    println(
      f"BENCH $label%-44s hand-rolled $l%8.1f ns   scodec $s%8.1f ns   ratio ${s / l}%.2f  (rounds: ${rs
          .map((a, b) => f"${b / a}%.2f")
          .mkString(" ")})"
    )

  private def k1Chunks(limit: Int): Vector[(Array[Byte], Array[Byte])] =
    val dir = Path.of(sys.env.getOrElse("SCARAB_K1_DB", "/nonexistent")).resolve("immutable")
    val stems = Files
      .list(dir)
      .iterator
      .asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".secondary"))
      .map(_.stripSuffix(".secondary"))
      .toVector
      .sorted
      .take(limit)
    stems.map(s =>
      (
        Files.readAllBytes(dir.resolve(s"$s.primary")),
        Files.readAllBytes(dir.resolve(s"$s.secondary"))
      )
    )

  test("bench: Haskell secondary and primary index files of the K1 Preview run") {
    assume(enabled && sys.env.contains("SCARAB_K1_DB"), "SCARAB_BENCH / SCARAB_K1_DB not set")
    val chunks = k1Chunks(3000)
    val entries = chunks.map(_._2.length / 56L).sum
    val offsets = chunks.map(c => (c._1.length - 1) / 4L).sum
    val slots = ChunkSlots.of(4320).get
    println(s"BENCH data: ${chunks.size} chunks, $entries secondary entries, $offsets offsets")
    compare("haskell secondary entries (ns/entry)", entries)(
      chunks
        .map(c => LegacyFormats.HaskellSecondary.parse(c._2, true).toOption.fold(0L)(_.size.toLong))
        .sum
    )(chunks.map(c => SecondaryIndex.parse(c._2, true).toOption.fold(0L)(_.size.toLong)).sum)
    compare("haskell primary offsets (ns/offset)", offsets)(
      chunks
        .map(c =>
          LegacyFormats.HaskellPrimary
            .parse(c._1, slots, true)
            .toOption
            .fold(0L)(_.offsets.size.toLong)
        )
        .sum
    )(
      chunks
        .map(c => PrimaryIndex.parse(c._1, slots, true).toOption.fold(0L)(_.offsets.size.toLong))
        .sum
    )
  }

  private val hash = Bytes.fromArray(Array.tabulate[Byte](32)(_.toByte))

  test("bench: file store index entries (our immutable store reads)") {
    assume(enabled, "SCARAB_BENCH is not set")
    val count = 200_000
    val entries = (0 until count).map(i =>
      FileImmutableStore.Entry(i * 2000L, 2000 + i % 97, i, BlockRef(i * 20L, hash, i, i % 50 == 0))
    )
    val index = FileImmutableStore.IndexMagic ++ entries.flatMap(FileImmutableStore.encode)
    assertEquals(
      Alternatives.entries(index).toOption,
      Some(FileImmutableStore.entries(index)),
      "the scodec entry codec reads what the store reads"
    )
    assertEquals(
      entries.flatMap(e => Alternatives.entry.encode(e).require.toByteArray.toSeq),
      entries.flatMap(e => FileImmutableStore.encode(e).toSeq)
    )
    compare("immutable index decode (ns/entry)", count)(
      FileImmutableStore.entries(index).size.toLong
    )(Alternatives.entries(index).toOption.fold(0L)(_.size.toLong))
    compare("immutable index encode (ns/entry)", count)(
      entries.map(e => FileImmutableStore.encode(e).length.toLong).sum
    )(entries.map(e => Alternatives.entry.encode(e).require.size / 8).sum)
  }

  test("bench: volatile log records") {
    assume(enabled, "SCARAB_BENCH is not set")
    val count = 2000
    Seq(300, 4096, 65536).foreach { size =>
      val rng = new scala.util.Random(size)
      val records = (0 until count).map { i =>
        val payload = new Array[Byte](size)
        rng.nextBytes(payload)
        VolatileRecord.Block(BlockRef(i * 20L, hash, i), Bytes.wrap(payload))
      }
      val log = records.flatMap(VolatileStore.encode).toArray
      assertEquals(
        records.flatMap(r => Alternatives.record.encode(r).require.toByteArray.toSeq),
        log.toSeq
      )
      assertEquals(Alternatives.records(log), VolatileStore.decodeAll(log).map(_._1))
      compare(s"volatile log decode, $size-byte blocks (ns/record)", count)(
        VolatileStore.decodeAll(log).size.toLong
      )(Alternatives.records(log).size.toLong)
      compare(s"volatile record encode, $size-byte blocks (ns/record)", count)(
        records.map(r => VolatileStore.encode(r).length.toLong).sum
      )(records.map(r => Alternatives.record.encode(r).require.size / 8).sum)
    }
  }

  test("bench: the mux SDU header (protocol/Mux.scala), per SDU on every connection") {
    assume(enabled, "SCARAB_BENCH is not set")
    val rng = new scala.util.Random(7)
    val headers = Vector.fill(1024) {
      val h = Mux.Header(
        rng.nextLong(1L << 32),
        rng.nextInt(0x8000),
        if rng.nextBoolean() then Mux.Direction.Initiator else Mux.Direction.Responder,
        1 + rng.nextInt(65535)
      )
      Mux.encodeHeader(h).toOption.get
    }
    val calls = 4_000_000L
    // The same mapping from the three wire fields to a `Header` on both sides, so that only the
    // reading of the fields differs.
    val wire = scodec.codecs.uint32 :: scodec.codecs.uint16 :: scodec.codecs.uint16
    def viaScodec(bytes: Bytes): Either[scodec.Err, Mux.Header] =
      wire
        .decode(BitVector.view(bytes.toArray))
        .toEither
        .map { r =>
          val (timestamp, mode, length) = r.value
          Mux.Header(
            timestamp,
            mode & 0x7fff,
            if (mode & 0x8000) == 0 then Mux.Direction.Initiator else Mux.Direction.Responder,
            length
          )
        }
    headers.foreach { bytes =>
      assertEquals(
        viaScodec(bytes).fold(e => fail(e.messageWithContext), identity),
        Mux.decodeHeader(bytes).fold(e => fail(e.message), identity)
      )
    }
    def loop[E](decode: Bytes => Either[E, Mux.Header]): Long =
      var i = 0L
      var acc = 0L
      while i < calls do
        acc += decode(headers((i & 1023).toInt)).toOption.fold(0L)(_.payloadLength.toLong)
        i += 1
      acc
    compare("mux header decode (ns/header)", calls)(loop(Mux.decodeHeader(_)))(loop(viaScodec))
  }

  test("bench: the UTxO key (id[32] | u16 index), per lookup and per change") {
    assume(enabled, "SCARAB_BENCH is not set")
    val rng = new scala.util.Random(11)
    val ins = Vector.fill(4096) {
      val id = new Array[Byte](32)
      rng.nextBytes(id)
      TxIn(Bytes.wrap(id), BigInt(rng.nextInt(0x10000)))
    }
    val key = scodec.codecs.bytes(32) :: scodec.codecs.uint16
    def encodeScodec(in: TxIn): Option[Array[Byte]] =
      key.encode((ByteVector.view(in.id.toArray), in.index.toInt)).toOption.map(_.toByteArray)
    def decodeScodec(k: Array[Byte]): TxIn =
      val (id, index) = key.decode(BitVector.view(k)).require.value
      TxIn(Bytes.wrap(id.toArray), BigInt(index))
    val keys = ins.map(i => UtxoStore.Coding.encodeKey(i).get)
    assertEquals(ins.map(i => encodeScodec(i).get.toSeq), keys.map(_.toSeq))
    assertEquals(keys.map(decodeScodec), ins)
    val calls = 4096 * 200L
    def run(f: Int => Long): Long =
      var acc = 0L
      var r = 0
      while r < 200 do
        var i = 0
        while i < 4096 do
          acc += f(i)
          i += 1
        r += 1
      acc
    compare("utxo key encode (ns/key)", calls)(
      run(i => UtxoStore.Coding.encodeKey(ins(i)).fold(0L)(_.length.toLong))
    )(run(i => encodeScodec(ins(i)).fold(0L)(_.length.toLong)))
    compare("utxo key decode (ns/key)", calls)(
      run(i => UtxoStore.Coding.decodeKey(keys(i)).index.toLong)
    )(
      run(i => decodeScodec(keys(i)).index.toLong)
    )
  }

  /** The two persisted formats that stay hand-rolled, written as scodec codecs: what the benchmark
    * measured against, and a record that they can be written that way (same bytes, checked above).
    * They are not used because the file store decodes an index for every block it serves and
    * appends a record for every block it adopts, and these are 5 to 40 times slower (see the
    * numbers in CONTRIBUTING).
    */
  private object Alternatives:
    private val magic: Codec[Unit] = constant(ByteVector.view(FileImmutableStore.IndexMagic))

    private val int64Big: Codec[BigInt] = int64.exmap(
      v => Attempt.successful(BigInt(v)),
      v =>
        if v.isValidLong then Attempt.successful(v.toLong)
        else Attempt.failure(Err(s"$v does not fit 64 bits"))
    )

    private def bytes32: Codec[Bytes] = byteVector(32).xmap(
      bv => Bytes.wrap(bv.toArray),
      b => ByteVector.view(b.toArray)
    )

    val entry: Codec[FileImmutableStore.Entry] =
      (int64 :: int32 :: int32 :: int64Big :: int64Big :: bytes32 :: uint8 <~ ignore(24)).xmap(
        (offset, length, crc, slot, blockNo, hash, flags) =>
          FileImmutableStore
            .Entry(offset, length, crc, BlockRef(slot, hash, blockNo, (flags & 1) != 0)),
        e =>
          (
            e.offset,
            e.length,
            e.crc,
            e.ref.slot,
            e.ref.blockNo,
            e.ref.hash,
            if e.ref.boundary then 1 else 0
          )
      )

    def entries(index: Array[Byte]): Attempt[Vector[FileImmutableStore.Entry]] =
      magic.decode(BitVector.view(index)).flatMap { m =>
        val whole = m.remainder.take(
          m.remainder.size / (8L * FileImmutableStore.EntrySize) * (8L * FileImmutableStore.EntrySize)
        )
        scodec.codecs.vector(entry).decode(whole).map(_.value)
      }

    private def crc32(bits: BitVector): Int =
      val crc = new java.util.zip.CRC32()
      crc.update(bits.toByteArray)
      crc.getValue.toInt

    /** `body` preceded by the CRC-32 of its own encoding. */
    private def crc32Prefixed[A](body: Codec[A]): Codec[A] = new Codec[A]:
      def sizeBound: SizeBound = SizeBound.exact(32) + body.sizeBound
      def encode(a: A): Attempt[BitVector] =
        body.encode(a).map(b => BitVector.fromInt(crc32(b)) ++ b)
      def decode(bits: BitVector): Attempt[DecodeResult[A]] =
        for
          stored <- int32.decode(bits)
          decoded <- body.decode(stored.remainder)
          computed = crc32(stored.remainder.take(stored.remainder.size - decoded.remainder.size))
          _ <-
            if computed == stored.value then Attempt.successful(()) else Attempt.failure(Err("crc"))
        yield decoded

    private val fields: Codec[(Long, Long, Bytes, Bytes)] =
      int32.consume(length =>
        if length < 0 then scodec.codecs.fail(Err("negative length"))
        else
          int64 :: int64 :: bytes32 :: byteVector(length)
            .xmap(bv => Bytes.wrap(bv.toArray), b => ByteVector.view(b.toArray))
      )(_._4.size)

    /** Blocks only; rollbacks differ in `kind` and `flags` alone. */
    val record: Codec[VolatileRecord] =
      crc32Prefixed(uint8 :: uint8 :: fields).exmap(
        (kind, flags, slot, blockNo, hash, payload) =>
          if kind == 1 then
            Attempt.successful(
              VolatileRecord.Block(BlockRef(slot, hash, blockNo, (flags & 1) != 0), payload)
            )
          else Attempt.failure(Err(s"kind $kind")),
        {
          case VolatileRecord.Block(ref, raw) =>
            Attempt.successful(
              (1, if ref.boundary then 1 else 0, ref.slot.toLong, ref.blockNo.toLong, ref.hash, raw)
            )
          case other => Attempt.failure(Err(s"not a block: $other"))
        }
      )

    def records(log: Array[Byte]): Vector[VolatileRecord] =
      val view = ByteVector.view(log)
      @scala.annotation.tailrec
      def go(at: Long, acc: Vector[VolatileRecord]): Vector[VolatileRecord] =
        record.decode(view.drop(at).bits) match
          case Attempt.Successful(DecodeResult(r, rest)) => go(log.length - rest.size / 8, acc :+ r)
          case Attempt.Failure(_)                        => acc
      go(0L, Vector.empty)
