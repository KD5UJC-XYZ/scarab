// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import xyz.kd5ujc.scarab.codec.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalacheck.Prop.forAll

import HaskellDbKit.*

/** Pure properties of the Haskell ImmutableDB index formats: round trips, and a typed fault for
  * every corruption. The encodings are the ones of ouroboros-consensus 4.2.1.0 (`Primary.hs`,
  * `Secondary.hs`).
  */
class HaskellIndexSuite extends ScalaCheckSuite:
  private val chunkSlots = ChunkSlots.of(20).get
  private val chunk = ChunkNo.of(3).get

  private val entryGen: Gen[SecondaryEntry] =
    for
      offset <- Gen.choose(0L, Long.MaxValue)
      headerOffset <- Gen.choose(0, 0xffff)
      headerSize <- Gen.choose(0, 0xffff)
      crc <- Arbitrary.arbitrary[Int]
      hash <- Gen.listOfN(32, Arbitrary.arbitrary[Byte])
      slot <- Gen.choose(0L, Long.MaxValue)
    yield SecondaryEntry(
      ByteOffset.of(offset).get,
      headerOffset,
      headerSize,
      crc,
      Bytes.fromArray(hash.toArray),
      slot
    )

  property("secondary entries round trip through 56-byte records") {
    forAll(Gen.listOf(entryGen)) { entries =>
      val bytes = SecondaryIndex.encode(entries).require
      assertEquals(bytes.length, 56 * entries.size)
      assertEquals(SecondaryIndex.parse(bytes, tolerateTornTail = false), Right(entries.toVector))
    }
  }

  property("a primary index round trips and lists the filled relative slots") {
    forAll(Gen.listOf(Gen.choose(0, 20)).map(_.distinct.sorted)) { filled =>
      val index = PrimaryIndex.build(filled, 21)
      assertEquals(index.offsets.size, 22)
      assertEquals(index.filled, filled.toVector)
      assertEquals(index.offsets.last, 56L * filled.size)
      assertEquals(
        PrimaryIndex
          .parse(PrimaryIndex.encode(index).require, chunkSlots, tolerateTornTail = false),
        Right(index)
      )
    }
  }

  test("the layout of the first Preview entry matches the bytes observed on a real database") {
    // 00000.secondary of the Preview ImmutableDB, first entry (hash and CRC as stored).
    val hex =
      "0000000000000000" + "0003" + "03c1" + "65fb2a4d" +
        "268ae601af8f9214804735910a3301881fbe0eec9936db7d1fb9fc39e93d1e37" + "0000000000000000"
    val bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), _.toArray)
    val entry = SecondaryIndex.parse(bytes, false).fold(f => fail(f.message), _.head)
    assertEquals(entry.blockOffset.value, 0L)
    assertEquals(entry.headerOffset, 3)
    assertEquals(entry.headerSize, 961)
    assertEquals(entry.crc, 0x65fb2a4d)
    assertEquals(entry.slotOrEpoch, 0L)
    assertEquals(SecondaryIndex.encode(List(entry)).require.toSeq, bytes.toSeq)
  }

  // ---- CBOR item lengths ----

  private sealed trait Tree
  private final case class Num(n: Long) extends Tree
  private final case class Str(b: Array[Byte]) extends Tree
  private final case class Arr(xs: List[Tree], indefinite: Boolean) extends Tree
  private final case class Mp(xs: List[(Tree, Tree)]) extends Tree
  private final case class Tg(t: Tree) extends Tree

  private def head(major: Int, n: Long): Array[Byte] =
    if n < 24 then Array(((major << 5) | n.toInt).toByte)
    else if n < 256 then Array(((major << 5) | 24).toByte, n.toByte)
    else if n < 65536 then Array(((major << 5) | 25).toByte, (n >> 8).toByte, n.toByte)
    else Array(((major << 5) | 26).toByte) ++ (3 to 0 by -1).map(i => (n >> (8 * i)).toByte)

  private def enc(t: Tree): Array[Byte] = t match
    case Num(n)        => head(0, n)
    case Str(b)        => head(2, b.length) ++ b
    case Arr(xs, true) => Array(0x9f.toByte) ++ xs.flatMap(enc) ++ Array(0xff.toByte)
    case Arr(xs, _)    => head(4, xs.size) ++ xs.flatMap(enc)
    case Mp(xs)        => head(5, xs.size) ++ xs.flatMap((k, v) => enc(k) ++ enc(v))
    case Tg(x)         => head(6, 24) ++ enc(x)

  private def tree(depth: Int): Gen[Tree] =
    val leaf = Gen.oneOf(
      Gen.choose(0L, 100000L).map(Num(_)),
      Gen.listOf(Arbitrary.arbitrary[Byte]).map(l => Str(l.toArray))
    )
    if depth == 0 then leaf
    else
      val sub = Gen.lzy(tree(depth - 1))
      Gen.frequency(
        3 -> leaf,
        2 -> Gen
          .zip(Gen.listOfN(3, sub).map(_.take(2)), Arbitrary.arbitrary[Boolean])
          .map(Arr(_, _)),
        1 -> Gen.listOfN(2, Gen.zip(sub, sub)).map(Mp(_)),
        1 -> sub.map(Tg(_))
      )

  property("CborItem.length finds the end of any item, definite or indefinite") {
    forAll(tree(4), Gen.listOf(Arbitrary.arbitrary[Byte])) { (t, tail) =>
      val item = enc(t)
      assertEquals(CborItem.length(item ++ tail, 0), Some(item.length))
    }
  }

  property("CborItem.length refuses an item cut short") {
    forAll(tree(4)) { t =>
      val item = enc(t)
      // Every proper prefix of an item is an incomplete item.
      (0 until item.length).forall(n => CborItem.length(item.take(n), 0).isEmpty)
    }
  }

  // ---- chunks: a valid chunk and its corruptions ----

  private final case class Fixture(
      items: Vector[Item],
      files: ChunkFiles,
      secondary: Vector[SecondaryEntry],
      primary: PrimaryIndex
  )

  private val fixtureGen: Gen[Fixture] =
    for
      relSlots <- Gen.choose(1, 8).flatMap(n => Gen.pick(n, 0 to 20)).map(_.toList.sorted)
      payloads <- Gen.listOfN(
        relSlots.size,
        Gen.choose(1, 300).flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[Byte]))
      )
    yield
      val items = relSlots
        .zip(payloads)
        .map { (r, p) =>
          val pos =
            if r == 0 then BlockPosition.Ebb(chunk.value.toLong)
            else BlockPosition.Regular(chunkSlots.firstSlot(chunk) + r - 1)
          shelleyLike(pos, p.toArray)
        }
        .toVector
      val files = chunkFiles(chunkSlots, items)
      Fixture(
        items,
        files,
        SecondaryIndex.parse(files.secondary, false).toOption.get,
        PrimaryIndex.parse(files.primary, chunkSlots, false).toOption.get
      )

  private def validate(
      f: Fixture,
      data: Array[Byte],
      mostRecent: Boolean,
      secondary: Vector[SecondaryEntry] = Vector.empty,
      useSecondary: Boolean = false
  ) =
    ChunkValidator.validate(
      chunk,
      chunkSlots,
      f.primary,
      if useSecondary then secondary else f.secondary,
      data,
      mostRecent
    )

  property("an untouched chunk validates, finished or most recent") {
    forAll(fixtureGen) { f =>
      val finished = validate(f, f.files.chunk, mostRecent = false)
      assertEquals(finished.map(_.blocks.size), Right(f.items.size))
      assertEquals(finished.map(_.dropped), Right(None))
      assertEquals(validate(f, f.files.chunk, mostRecent = true), finished)
      val blocks = finished.toOption.get.blocks
      blocks.zip(f.items).foreach { (b, i) =>
        assertEquals(
          java.util.Arrays.copyOfRange(f.files.chunk, b.start, b.start + b.length).toSeq,
          i.bytes.toSeq
        )
        assertEquals(b.position, i.position)
      }
    }
  }

  private def isLeft(r: Either[ChunkFault, Validated])(check: ChunkFault => Boolean) =
    r match
      case Left(fault) => check(fault)
      case Right(_)    => false

  property("a flipped bit in a block is a typed fault") {
    forAll(fixtureGen, Arbitrary.arbitrary[Int], Gen.choose(0, 7)) { (f, where, bit) =>
      val i = Math.floorMod(where, f.files.chunk.length)
      val data = f.files.chunk.clone()
      data(i) = (data(i) ^ (1 << bit)).toByte
      val r = validate(f, data, mostRecent = false)
      assert(r.isLeft, clues(r))
      // The most recent chunk is cut back to the blocks before the damage instead.
      val recent = validate(f, data, mostRecent = true).toOption.get
      assert(recent.blocks.size < f.items.size)
      assert(recent.dropped.exists(_.entries == f.items.size - recent.blocks.size))
    }
  }

  property("a flipped CRC in the secondary index is a CrcMismatch") {
    forAll(fixtureGen, Arbitrary.arbitrary[Int]) { (f, which) =>
      val i = Math.floorMod(which, f.secondary.size)
      val bad = f.secondary.updated(i, f.secondary(i).copy(crc = f.secondary(i).crc ^ 1))
      val r = validate(f, f.files.chunk, mostRecent = false, bad, useSecondary = true)
      assert(
        isLeft(r) { case ChunkFault.CrcMismatch(`i`, _, _) => true; case _ => false },
        clues(r)
      )
    }
  }

  property(
    "a changed block offset is an offset fault (or the block no longer ends where it should)"
  ) {
    forAll(fixtureGen, Arbitrary.arbitrary[Int], Gen.choose(1, 40)) { (f, which, delta) =>
      val i = Math.floorMod(which, f.secondary.size)
      val old = f.secondary(i).blockOffset.value
      val moved = ByteOffset.of(old + delta).get
      val bad = f.secondary.updated(i, f.secondary(i).copy(blockOffset = moved))
      val r = validate(f, f.files.chunk, mostRecent = false, bad, useSecondary = true)
      assert(r.isLeft, clues(r))
    }
  }

  property("a changed header hash or slot is a typed fault") {
    forAll(fixtureGen, Arbitrary.arbitrary[Int]) { (f, which) =>
      val i = Math.floorMod(which, f.secondary.size)
      val e = f.secondary(i)
      val wrongHash = e.copy(headerHash = Bytes.fromArray(Array.fill(32)(7.toByte)))
      val rHash = validate(
        f,
        f.files.chunk,
        mostRecent = false,
        f.secondary.updated(i, wrongHash),
        useSecondary = true
      )
      assert(isLeft(rHash) { case ChunkFault.HeaderHashMismatch(`i`) => true; case _ => false })
      val rSlot = validate(
        f,
        f.files.chunk,
        mostRecent = false,
        f.secondary.updated(i, e.copy(slotOrEpoch = e.slotOrEpoch + 1)),
        useSecondary = true
      )
      assert(isLeft(rSlot) { case ChunkFault.SlotMismatch(`i`, _, _) => true; case _ => false })
    }
  }

  property(
    "a truncated chunk file is a typed fault; the most recent chunk keeps its whole blocks"
  ) {
    forAll(fixtureGen, Arbitrary.arbitrary[Int]) { (f, cut) =>
      val keep = Math.floorMod(cut, f.files.chunk.length) // strictly shorter than the file
      val data = f.files.chunk.take(keep)
      val r = validate(f, data, mostRecent = false)
      assert(r.isLeft, clues(r))
      val recent = validate(f, data, mostRecent = true).toOption.get
      // Whatever it kept lies wholly within the bytes that remain, and is a prefix of the blocks.
      assert(recent.blocks.forall(b => b.start + b.length <= keep))
      assert(recent.blocks.map(_.entry) == f.secondary.take(recent.blocks.size))
      assert(recent.blocks.size < f.items.size)
    }
  }

  property("a truncated secondary index is a typed fault, tolerated on the most recent chunk") {
    forAll(fixtureGen, Arbitrary.arbitrary[Int]) { (f, cut) =>
      val keep = Math.floorMod(cut, f.files.secondary.length)
      val bytes = f.files.secondary.take(keep)
      val parsed = SecondaryIndex.parse(bytes, tolerateTornTail = false)
      if keep % 56 != 0 then assertEquals(parsed, Left(ChunkFault.SecondaryLength(keep)))
      val tolerated = SecondaryIndex.parse(bytes, tolerateTornTail = true).toOption.get
      assertEquals(tolerated.size, keep / 56)
      // A finished chunk whose secondary index lost entries does not match its primary index.
      val r = validate(f, f.files.chunk, mostRecent = false, tolerated, useSecondary = true)
      assertEquals(r, Left(ChunkFault.IndexCountMismatch(f.items.size, tolerated.size)))
      // The most recent chunk keeps the blocks it still has an entry for.
      val recent = validate(f, f.files.chunk, mostRecent = true, tolerated, useSecondary = true)
      assertEquals(recent.map(_.blocks.size), Right(tolerated.size))
    }
  }

  property("a damaged primary index is a typed fault") {
    forAll(fixtureGen, Arbitrary.arbitrary[Int]) { (f, which) =>
      val bytes = f.files.primary
      val parse = (b: Array[Byte]) => PrimaryIndex.parse(b, chunkSlots, tolerateTornTail = false)
      assertEquals(parse(Array.empty), Left(ChunkFault.PrimaryEmpty))
      assertEquals(parse(bytes.updated(0, 2.toByte)), Left(ChunkFault.PrimaryVersion(2)))
      assertEquals(
        parse(bytes.dropRight(1)),
        Left(ChunkFault.PrimaryLength(bytes.length - 1))
      )
      assertEquals(
        parse(bytes.updated(4, 1.toByte)),
        Left(ChunkFault.PrimaryFirstOffset(1L))
      )
      // Change one non-zero step so that it is neither 0 nor 56.
      val r = Math.floorMod(which, f.primary.offsets.size - 1)
      val tampered = f.primary.offsets.zipWithIndex.map((o, i) => if i > r then o + 3 else o)
      assert(
        parse(PrimaryIndex.encode(PrimaryIndex(tampered)).require).isLeft,
        "an offset step of 3 must be refused"
      )
      // More offsets than a chunk can have.
      val tooLong = PrimaryIndex(Vector.fill(chunkSlots.value.toInt + 3)(0L))
      assert(parse(PrimaryIndex.encode(tooLong).require).isLeft)
    }
  }

  property("bytes after the last block are a fault in a finished chunk, ignored in the newest") {
    forAll(fixtureGen, Gen.choose(1, 30)) { (f, extra) =>
      val data = f.files.chunk ++ Array.fill(extra)(0x01.toByte)
      assertEquals(
        validate(f, data, mostRecent = false).left.map(_.getClass),
        Left(classOf[ChunkFault.TrailingBytes])
      )
      val recent = validate(f, data, mostRecent = true).toOption.get
      assertEquals(recent.blocks.size, f.items.size)
      assertEquals(recent.dropped.map(_.bytes), Some(extra.toLong))
    }
  }
