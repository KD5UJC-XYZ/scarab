// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import java.nio.file.{Files, Path}
import munit.FunSuite

class NativeIndexComparisonSuite extends FunSuite:
  import NativeIndexComparison.*
  private val slots = ChunkSlots.of(4320).getOrElse(fail("invalid constant geometry"))
  private val base = Path.of("fixtures/haskell-immutable/preview/immutable")
  private val primary = Files.readAllBytes(base.resolve("00000.primary"))
  private val secondary = Files.readAllBytes(base.resolve("00000.secondary"))

  test("the original native chunk decodes successfully on both index implementations") {
    val compared = compare("00000", primary, secondary, slots)
    assert(compared.exists(s => s.chunks == 1 && s.entries > 0 && s.offsets > 0), compared.toString)
    assertEquals(compared.flatMap(complete), compared)
  }

  test("empty inventories and missing chunk or index companions are refused") {
    assertEquals(inventory(Vector.empty), Left(Error.EmptyInventory))
    val files = Vector("00000.chunk", "00000.primary", "00000.secondary")
    assertEquals(inventory(files), Right(Vector("00000")))
    files.foreach { missing =>
      assert(
        inventory(files.filterNot(_ == missing)).left.exists(_.isInstanceOf[Error.Inventory]),
        missing
      )
    }
    assert(inventory(files :+ "00001.primary").left.exists(_.isInstanceOf[Error.Inventory]))
    assert(inventory(files :+ "00001.chunk").left.exists(_.isInstanceOf[Error.Inventory]))
  }

  test("matching decoder failures are not successful native comparisons") {
    assertEquals(
      agreement[PrimaryIndex](
        "bad",
        Format.Primary,
        Left(ChunkFault.PrimaryEmpty),
        Left(ChunkFault.PrimaryEmpty)
      ),
      Left(Error.Decode("bad", Format.Primary, Parser.Current, ChunkFault.PrimaryEmpty))
    )
    assert(compare("bad", Array.emptyByteArray, Array.emptyByteArray, slots).isLeft)
  }

  test("either parser failure and different successful values fail with typed causes") {
    val value = PrimaryIndex(Vector(0L))
    assertEquals(
      agreement("bad", Format.Primary, Right(value), Left(ChunkFault.PrimaryEmpty)),
      Left(Error.Decode("bad", Format.Primary, Parser.Legacy, ChunkFault.PrimaryEmpty))
    )
    assertEquals(
      agreement("bad", Format.Primary, Right(value), Right(PrimaryIndex(Vector(0L, 56L)))),
      Left(Error.Mismatch("bad", Format.Primary))
    )
  }

  test("torn primary and secondary tails fail in closed chunks") {
    assert(compare("torn-primary", primary.dropRight(1), secondary, slots).left.exists {
      case Error.Decode(_, Format.Primary, _, _) => true
      case _                                     => false
    })
    assert(compare("torn-secondary", primary, secondary.dropRight(1), slots).left.exists {
      case Error.Decode(_, Format.Secondary, _, _) => true
      case _                                       => false
    })
  }

  test("nonempty inventories with zero successfully decoded data cannot satisfy acceptance") {
    Vector(Summary(0L, 0L, 0L), Summary(1L, 0L, 1L), Summary(1L, 1L, 0L)).foreach { summary =>
      assertEquals(complete(summary), Left(Error.EmptyDecoded(summary)))
    }
  }

  test("only the newest chunk tolerates bounded partial tails with successful complete entries") {
    val completeInput =
      compare("00000", primary, secondary, slots).fold(e => fail(e.toString), identity)
    val newest = compare(
      "00000",
      primary ++ Array[Byte](0),
      secondary ++ Array[Byte](0, 0, 0),
      slots,
      mostRecent = true
    )
    assertEquals(newest, Right(completeInput.copy(primaryTailBytes = 1L, secondaryTailBytes = 3L)))
    assert(newest.flatMap(complete).isRight)
    val corruptVersion = primary.updated(0, 0.toByte)
    assert(compare("00000", corruptVersion, secondary, slots, mostRecent = true).isLeft)
  }

  test("chunk numbering is canonical and greatest chunk selection uses numeric order") {
    val files = Vector("99999", "100000").flatMap(stem =>
      Vector(s"$stem.chunk", s"$stem.primary", s"$stem.secondary")
    )
    assertEquals(inventory(files), Right(Vector("99999", "100000")))
    assertEquals(
      inventory(Vector("x.chunk", "x.primary", "x.secondary")),
      Left(Error.InvalidStem("x"))
    )
  }
