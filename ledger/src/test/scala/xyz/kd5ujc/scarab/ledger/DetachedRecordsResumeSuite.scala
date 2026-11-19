// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import scala.util.Random
import xyz.kd5ujc.scarab.codec.Bytes

/** [[DetachedRecords.prefix]] walked on from where the walk of a shorter prefix stopped answers as
  * the walk of each prefix from its start, and [[DetachedRecords.mayHold]] searched on answers as
  * the search from the start: on the corpus of records written as `9f ff`, the blocks they are in,
  * and copies of them with bytes flipped.
  */
class DetachedRecordsResumeSuite extends FunSuite:
  import DetachedRecordsReferenceSuite.*

  private def shape(row: Row): Option[(Bytes, DetachedRecords.Shape)] = row.kind match
    case Kind.Transaction => Some((row.raw, DetachedRecords.Shapes.transaction(row.era)))
    case Kind.Body        => Some((row.raw, DetachedRecords.Shapes.body(row.era)))
    case Kind.BlockBody   => Some((block(row.era, row.raw), DetachedRecords.Shapes.block))
    case Kind.Witnesses | Kind.Auxiliary | Kind.GovActionIds => None

  private val inputs: Vector[(Bytes, DetachedRecords.Shape)] = load.flatMap(shape)

  private def flipped(raw: Bytes, random: Random): Bytes =
    val bytes = raw.toArray
    val at = random.nextInt(bytes.length)
    bytes(at) = (bytes(at) ^ (1 << random.nextInt(8))).toByte
    Bytes.wrap(bytes)

  /** The prefixes of `raw` that end at `cuts`, each walked on from the walk of the last. */
  private def resumed(raw: Bytes, shape: DetachedRecords.Shape, cuts: Vector[Int]) =
    cuts
      .foldLeft((Option.empty[DetachedRecords.Walking], Vector.empty[DetachedRecords.Prefix])) {
        case ((walking, seen), at) =>
          val (next, found) = DetachedRecords.prefix(raw.take(at), shape, walking)
          (next, seen :+ found)
      }
      ._2

  test("the corpus holds inputs of every shape walked") {
    assert(inputs.sizeIs > 10, inputs.size.toString)
  }

  test("a walk resumed on longer prefixes answers as the walk of each from its start") {
    val random = new Random(11L)
    val differences = inputs.flatMap { (raw, shape) =>
      (0 until 6).flatMap { round =>
        val input = if round == 0 then raw else flipped(raw, random)
        val cuts =
          (Vector.fill(1 + random.nextInt(32))(
            random.nextInt(input.size + 1)
          ) :+ input.size).distinct.sorted
        val found = resumed(input, shape, cuts)
        val expected = cuts.map(at => DetachedRecords.prefix(input.take(at), shape))
        // a resumed walk is only asked again while the prefix was short
        val asked = expected.indexWhere(_ != DetachedRecords.Prefix.Short) match
          case -1 => expected.size
          case at => at + 1
        Option.when(found.take(asked) != expected.take(asked))(
          s"${input.size} B cuts ${cuts.mkString(",")}: $found against $expected"
        )
      }
    }
    assertEquals(differences, Vector.empty)
  }

  test("the search for 9f ff resumed answers as the search from the start") {
    val random = new Random(12L)
    val differences = inputs.flatMap { (raw, _) =>
      val cuts = (Vector.fill(16)(random.nextInt(raw.size + 1)) :+ raw.size).distinct.sorted
      val found = cuts
        .foldLeft((DetachedRecords.Scan.Unseen(0): DetachedRecords.Scan, Vector.empty[Boolean])) {
          case ((scan, seen), at) =>
            val next = DetachedRecords.mayHold(raw.take(at), scan)
            (next, seen :+ (next == DetachedRecords.Scan.Seen))
        }
        ._2
      val expected = cuts.map(at => DetachedRecords.mayHold(raw.take(at)))
      Option.when(found != expected)(s"${raw.size} B: $found against $expected")
    }
    assertEquals(differences, Vector.empty)
  }

  test("a walk resumed in an item nested 100,000 deep runs on a small stack (STK-3)") {
    // GetProposals' shape walks its ids as records; an id nested deep is an item the walk skips
    val nested = Bytes.wrap(Array.fill[Byte](100000)(0x81.toByte) :+ 0x00.toByte)
    val shape = DetachedRecords.Shape.ListOf(DetachedRecords.Shape.Item)
    val raw = Bytes.wrap(Array[Byte](0x9f.toByte)) ++ nested ++ Bytes.wrap(Array[Byte](0xff.toByte))
    val cuts = (1 to raw.size by 997).toVector :+ raw.size
    val found = PreprodDeepTx.onSmallStack(
      cuts
        .foldLeft((Option.empty[DetachedRecords.Walking], Vector.empty[DetachedRecords.Prefix])) {
          case ((walking, seen), at) =>
            val (next, result) = DetachedRecords.prefix(raw.take(at), shape, walking)
            (next, seen :+ result)
        }
        ._2
    )
    assertEquals(found.lastOption, Some(DetachedRecords.prefix(raw, shape)))
    assert(found.init.forall(_ == DetachedRecords.Prefix.Short), found.init.distinct.toString)
  }
