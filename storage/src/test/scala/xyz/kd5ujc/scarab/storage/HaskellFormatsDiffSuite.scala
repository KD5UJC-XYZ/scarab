// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scodec.Attempt
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.storage.haskell.*

/** The scodec codecs of the Haskell ImmutableDB indices against the `ByteBuffer` parsers they
  * replaced: the same value or the same typed fault for every input, valid or damaged, and the same
  * bytes out for every value the old encoder wrote faithfully.
  */
class HaskellFormatsDiffSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(1000)

  private val chunkSlots = ChunkSlots.of(20).get

  private val entryGen: Gen[SecondaryEntry] =
    for
      offset <- Gen.oneOf(Gen.choose(0L, 1L << 33), Gen.choose(0L, Long.MaxValue))
      headerOffset <- Gen.choose(0, 0xffff)
      headerSize <- Gen.choose(0, 0xffff)
      crc <- Arbitrary.arbitrary[Int]
      hash <- Gen.listOfN(32, Arbitrary.arbitrary[Byte])
      slot <- Gen.oneOf(Gen.choose(0L, 1L << 40), Gen.choose(0L, Long.MaxValue))
    yield SecondaryEntry(
      ByteOffset.of(offset).get,
      headerOffset,
      headerSize,
      crc,
      Bytes.fromArray(hash.toArray),
      slot
    )

  /** Valid files, files cut or extended at any byte, and files with random bytes overwritten. */
  private def damaged(valid: Gen[Array[Byte]]): Gen[Array[Byte]] =
    Gen.oneOf(
      valid,
      valid.flatMap(a => Gen.choose(0, a.length).map(a.take)),
      valid.flatMap(a => Gen.listOf(Arbitrary.arbitrary[Byte]).map(t => a ++ t.take(70))),
      valid.flatMap { a =>
        if a.isEmpty then Gen.const(a)
        else
          Gen.listOfN(3, Gen.zip(Gen.choose(0, a.length - 1), Arbitrary.arbitrary[Byte])).map {
            edits =>
              val c = a.clone()
              edits.foreach((i, b) => c(i) = b)
              c
          }
      },
      Gen.listOf(Arbitrary.arbitrary[Byte]).map(_.toArray)
    )

  private val secondaryFiles: Gen[Array[Byte]] =
    damaged(Gen.listOf(entryGen).map(es => LegacyFormats.HaskellSecondary.encode(es)))

  private val primaryFiles: Gen[Array[Byte]] =
    val filled = Gen.listOf(Gen.choose(0, 20)).map(_.distinct.sorted)
    damaged(filled.map(f => LegacyFormats.HaskellPrimary.encode(PrimaryIndex.build(f, 21))))

  property("secondary parse is the legacy parse, with and without torn tail") {
    forAll(secondaryFiles, Arbitrary.arbitrary[Boolean]) { (bytes, tolerate) =>
      assertEquals(
        SecondaryIndex.parse(bytes, tolerate),
        LegacyFormats.HaskellSecondary.parse(bytes, tolerate)
      )
    }
  }

  property("primary parse is the legacy parse, with and without torn tail") {
    forAll(primaryFiles, Arbitrary.arbitrary[Boolean]) { (bytes, tolerate) =>
      assertEquals(
        PrimaryIndex.parse(bytes, chunkSlots, tolerate),
        LegacyFormats.HaskellPrimary.parse(bytes, chunkSlots, tolerate)
      )
    }
  }

  property("secondary encode is the legacy encode") {
    forAll(Gen.listOf(entryGen)) { entries =>
      assertEquals(
        SecondaryIndex.encode(entries).map(_.toSeq),
        Attempt.successful(LegacyFormats.HaskellSecondary.encode(entries).toSeq)
      )
    }
  }

  property("primary encode is the legacy encode") {
    forAll(Gen.listOf(Gen.choose(0, 20)).map(_.distinct.sorted)) { filled =>
      val index = PrimaryIndex.build(filled, 21)
      assertEquals(
        PrimaryIndex.encode(index).map(_.toSeq),
        Attempt.successful(LegacyFormats.HaskellPrimary.encode(index).toSeq)
      )
    }
  }

  property("a negative offset or slot is the typed fault of its entry") {
    forAll(Gen.listOf(entryGen).suchThat(_.nonEmpty), Gen.choose(Long.MinValue, -1L)) {
      (entries, negative) =>
        val bytes = LegacyFormats.HaskellSecondary.encode(entries)
        val at = entries.size - 1
        val offset = bytes.clone()
        java.nio.ByteBuffer.wrap(offset).putLong(at * 56, negative)
        assertEquals(
          SecondaryIndex.parse(offset, tolerateTornTail = false),
          Left(ChunkFault.BlockOffsetRange(at))
        )
        val slot = bytes.clone()
        java.nio.ByteBuffer.wrap(slot).putLong(at * 56 + 48, negative)
        assertEquals(
          SecondaryIndex.parse(slot, tolerateTornTail = false),
          Left(ChunkFault.SlotRange(at))
        )
    }
  }

  test("a value that has no encoding fails instead of being truncated") {
    val entry = SecondaryEntry(ByteOffset.zero, 0x10000, 1, 0, Bytes.fromArray(new Array(32)), 0L)
    assert(SecondaryIndex.encode(List(entry)).isFailure)
    assert(SecondaryIndex.encode(List(entry.copy(headerOffset = -1))).isFailure)
    assert(
      SecondaryIndex.encode(List(entry.copy(headerOffset = 7, headerHash = Bytes.empty))).isFailure
    )
    assert(PrimaryIndex.encode(PrimaryIndex(Vector(0L, 1L << 32))).isFailure)
    assert(PrimaryIndex.encode(PrimaryIndex(Vector(-1L))).isFailure)
  }

  test("a version other than 1 is reported with the byte found") {
    assertEquals(
      PrimaryIndex.parse(Array(7.toByte, 0, 0, 0, 0), chunkSlots, tolerateTornTail = false),
      Left(ChunkFault.PrimaryVersion(7))
    )
    assertEquals(
      PrimaryIndex.parse(Array(0xff.toByte), chunkSlots, tolerateTornTail = true),
      Left(ChunkFault.PrimaryVersion(255))
    )
  }
