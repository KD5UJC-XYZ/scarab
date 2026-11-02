// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import scala.annotation.tailrec

import Cbor.{Error, Limits}
import CborDecodeFunctional.Decoded

/** [[CborDecodeFunctional]] decodes every input as [[Cbor]]'s decoder does: the same index (where
  * each item starts and ends) or the same error. The index of the imperative decode is read through
  * its nodes in pre-order, which is the order the items are numbered in.
  */
class CborDecodeEquivalenceSuite extends munit.FunSuite:
  private def imperative(bytes: Bytes, limits: Limits, prefix: Boolean): Either[Error, Decoded] =
    val decoded = if prefix then Cbor.decodePrefix(bytes, limits) else Cbor.decode(bytes, limits)
    decoded.map { root =>
      @tailrec def walk(pending: List[Node], starts: Vector[Int], ends: Vector[Int]): Decoded =
        pending match
          case Nil => Decoded(starts, ends, root.offset + root.original.size)
          case n :: rest =>
            val children = n.value match
              case Value.Arr(xs)   => xs.toList
              case Value.Map(ps)   => ps.toList.flatMap((k, v) => List(k, v))
              case Value.Tag(_, x) => List(x)
              case _               => Nil
            walk(children ::: rest, starts :+ n.offset, ends :+ (n.offset + n.original.size))
      walk(List(root), Vector.empty, Vector.empty)
    }

  private def same(bytes: Bytes, limits: Limits = CborCorpus.Blocks, prefix: Boolean = false)(using
      munit.Location
  ): Unit =
    assertEquals(
      CborDecodeFunctional.decode(bytes, limits, prefix),
      imperative(bytes, limits, prefix),
      s"${bytes.size} bytes ${bytes.take(16).hex}..., $limits, prefix $prefix"
    )

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  test("every block of the corpus has the same index") {
    assertEquals(CborCorpus.all.size, 730)
    CborCorpus.all.foreach(b => same(b))
  }

  test("a block inside a larger buffer has the same index, at the same offsets") {
    CborCorpus.preprod.take(10).foreach { b =>
      val framed = Bytes.wrap(Array.fill[Byte](7)(1) ++ b.toArray ++ Array.fill[Byte](5)(2))
      val window = framed.slice(7, 7 + b.size)
      same(window)
      same(framed.drop(7), prefix = true)
    }
  }

  test("prefixes of blocks fail or stop as they do in the imperative decoder") {
    CborCorpus.all.zipWithIndex.filter(_._2 % 10 == 0).map(_._1).foreach { b =>
      val short = (0 to math.min(256, b.size)).iterator
      val spaced = (0 until 64).iterator.map(k => k * b.size / 64)
      (short ++ spaced).foreach { n =>
        same(b.take(n))
        same(b.take(n), prefix = true)
      }
    }
  }

  test("blocks with a byte changed fail or decode as they do in the imperative decoder") {
    val random = new scala.util.Random(650)
    CborCorpus.all.zipWithIndex.filter(_._2 % 5 == 0).map(_._1).foreach { b =>
      (0 until 16).foreach { _ =>
        val bytes = b.toArray
        val at = random.nextInt(bytes.length)
        bytes(at) = (bytes(at) ^ (1 << random.nextInt(8))).toByte
        same(Bytes.wrap(bytes))
      }
    }
  }

  /** The smallest value of a limit the imperative decoder accepts `b` with. */
  private def smallest(b: Bytes)(limits: Int => Limits): Int =
    @tailrec def search(lo: Int, hi: Int): Int =
      if lo >= hi then lo
      else
        val mid = lo + (hi - lo) / 2
        if Cbor.decode(b, limits(mid)).isRight then search(lo, mid) else search(mid + 1, hi)
    search(0, b.size + 1)

  test("each block at its smallest item, depth and string limits, and one below") {
    CborCorpus.all.zipWithIndex.filter(_._2 % 20 == 0).map(_._1).foreach { b =>
      Vector[Int => Limits](
        n => CborCorpus.Blocks.copy(maxItems = n),
        n => CborCorpus.Blocks.copy(maxDepth = n),
        n => CborCorpus.Blocks.copy(maxStringBytes = n),
        n => CborCorpus.Blocks.copy(maxInputBytes = n)
      ).foreach { limits =>
        val n = smallest(b)(limits)
        same(b, limits(n))
        if n > 0 then same(b, limits(n - 1))
      }
    }
  }

  test("strings, chunks, simple values, floats, tags, breaks and reserved heads") {
    val cases = Vector(
      "00",
      "17",
      "1818",
      "19ffff",
      "1affffffff",
      "1bffffffffffffffff",
      "1c",
      "1f",
      "3b7fffffffffffffff",
      "40",
      "4401020304",
      "5f42010243030405ff",
      "5f4201024203ff",
      "5f61ff",
      "5fff",
      "5f",
      "5f42",
      "60",
      "6461626364",
      "62c328",
      "7f61616162ff",
      "7f6161ff",
      "7f4161ff",
      "7f6261",
      "63e282ac",
      "80",
      "83010203",
      "9f01029f0304ffff",
      "9fff",
      "9f01",
      "98",
      "9a00015f90",
      "a0",
      "a201020304",
      "bf0102ff",
      "bf01ff",
      "bf0102",
      "a1",
      "ba7fffffff",
      "c0",
      "c1",
      "d8181a",
      "c240",
      "c2",
      "d9d8184100",
      "f4",
      "f5",
      "f6",
      "f7",
      "f0",
      "f81f",
      "f820",
      "f8",
      "f93c00",
      "fa3f800000",
      "fb3ff0000000000000",
      "fc",
      "fd",
      "fe",
      "ff",
      "0001",
      "8101ff",
      "c0c0c0c000"
    )
    val variants = Vector(
      Limits(),
      Limits(allowFloats = true),
      Limits(allowSimpleValues = true),
      Limits(allowFloats = true, allowSimpleValues = true),
      Limits(maxItems = 2),
      Limits(maxDepth = 1),
      Limits(maxStringBytes = 2),
      Limits(maxInputBytes = 3),
      Limits(maxItems = -1)
    )
    cases
      .map(hex)
      .foreach(b =>
        variants.foreach { limits =>
          same(b, limits)
          same(b, limits, prefix = true)
        }
      )
  }

  test("deep nesting is decoded without the thread's stack, as the imperative decoder does it") {
    val depth = 100000
    val nested = Bytes.wrap(Array.fill[Byte](depth)(0x81.toByte) :+ 0.toByte)
    same(nested, CborCorpus.Blocks.copy(maxItems = depth + 1))
    same(nested, CborCorpus.Blocks.copy(maxDepth = depth - 1, maxItems = depth + 1))
  }
