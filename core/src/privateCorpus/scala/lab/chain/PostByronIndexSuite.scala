// SPDX-License-Identifier: Apache-2.0
package lab.chain

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}

/** Original independent expectations plus explicitly synthetic structural negatives. */
class PostByronIndexSuite extends munit.FunSuite:
  private def resource(name: String): Bytes =
    val in = getClass.getResourceAsStream(s"/post-byron/$name")
    require(in != null, name)
    try Bytes.fromArray(in.readAllBytes())
    finally in.close()
  private def right[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def inspect(b: Bytes) = CardanoBlockIndex.inspect(b)
  private def node(v: Value) = Node(v, Bytes.empty)
  private def arr(xs: Vector[Node]) = node(Value.Arr(xs))
  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case _             => fail("test expected array")
  private def changed(raw: Bytes, path: List[Int], f: Node => Node): Bytes =
    def visit(n: Node, p: List[Int]): Node = p match
      case Nil       => f(n)
      case i :: tail => val xs = items(n); arr(xs.updated(i, visit(xs(i), tail)))
    right(Cbor.encode(visit(right(Cbor.decode(raw)), path).value))
  private val singles = Vector("shelley", "allegra", "mary", "alonzo", "babbage", "conway")
  private val cases = new String(
    resource("expectations.tsv").toArray,
    java.nio.charset.StandardCharsets.UTF_8
  ).linesIterator.map(_.split("\t").toVector).toVector

  for row <- cases do
    test(
      s"independent original ${row.head}: exact era, header span/hash, parent and unsigned point"
    ) {
      val raw = resource(row(0)); val b = right(inspect(raw))
      assertEquals(b.era, CardanoBlockIndex.Era.values.find(_.diskTag == row(1).toInt).get.label)
      assertEquals(b.blockNo, BigInt(row(2)))
      assertEquals(b.slot, BigInt(row(3)))
      assertEquals(b.headerHash.hex, row(4))
      assertEquals(b.parentHash.hex, row(5))
      assertEquals(b.rawSha256.hex, row(6))
      assertEquals(b.headerOffset, row(7).toInt)
      assertEquals(b.headerLength, row(8).toInt)
      assertEquals(b.rawBytes, raw)
      assertEquals(
        b.headerBytes,
        Bytes(raw.value.slice(b.headerOffset, b.headerOffset + b.headerLength))
      )
      assertEquals(b.headerHash, Blake2b.hash256.hash(b.headerBytes))
      assertNotEquals(b.headerHash, Blake2b.hash256.hash(raw))
    }

  test("all five preprod slices link, preserving first anchor and final selected point") {
    val bs = (0 until 5).map(i => right(inspect(resource(f"02019-$i%03d.cbor"))))
    bs.sliding(2).foreach { pair =>
      assertEquals(pair(1).parentHash, pair(0).headerHash)
      assert(pair(1).slot > pair(0).slot)
      assertEquals(pair(1).blockNo, pair(0).blockNo + 1)
    }
  }

  for era <- singles do
    test(s"$era synthetic shape, null parent, numeric and byte-preserving boundaries") {
      val raw = resource(s"${era}1.block")
      val original = right(inspect(raw))
      val hb = List(1, 0, 0)
      val praos = era == "babbage" || era == "conway"
      val five = praos || era == "alonzo"
      val badPaths = Vector(List(1), List(1, 0), hb)
      badPaths.foreach(p =>
        assert(inspect(changed(raw, p, n => arr(items(n).dropRight(1)))).isLeft)
      )
      val nullParent = inspect(changed(raw, hb :+ 2, _ => node(Value.Null)))
      assert(nullParent.left.toOption.get.contains("null parent is unsupported"))
      Vector(0, 1, if praos then 6 else 7).foreach { i =>
        assert(inspect(changed(raw, hb :+ i, _ => node(Value.NInt(-1)))).isLeft)
        for n <- Vector(BigInt(0), BigInt(1) << 63, (BigInt(1) << 64) - 1) do
          val b = right(inspect(changed(raw, hb :+ i, _ => node(Value.UInt(n)))))
          if i == 0 then assertEquals(b.blockNo, n)
          if i == 1 then assertEquals(b.slot, n)
        assert(
          inspect(
            changed(
              raw,
              hb :+ i,
              _ => node(Value.Tag(2, node(Value.ByteString(Bytes(Vector(1.toByte))))))
            )
          ).isLeft
        )
      }
      Vector(2, 3, 4, if praos then 7 else 8).foreach { i =>
        assert(inspect(changed(raw, hb :+ i, _ => node(Value.ByteString(Bytes.empty)))).isLeft)
      }
      val vrfs = if praos then Vector(5) else Vector(5, 6)
      vrfs.foreach { i =>
        assert(inspect(changed(raw, hb :+ i, n => arr(items(n).dropRight(1)))).isLeft)
        assert(
          inspect(changed(raw, hb ++ List(i, 1), _ => node(Value.ByteString(Bytes.empty)))).isLeft
        )
      }
      if praos then
        Vector(8, 9).foreach { i =>
          assert(inspect(changed(raw, hb :+ i, n => arr(items(n).dropRight(1)))).isLeft)
        }
        Vector(List(8, 1), List(8, 2), List(9, 0), List(9, 1)).foreach { p =>
          assert(inspect(changed(raw, hb ++ p, _ => node(Value.NInt(-1)))).isLeft)
        }
      else
        Vector(10, 11, 13, 14).foreach { i =>
          assert(inspect(changed(raw, hb :+ i, _ => node(Value.NInt(-1)))).isLeft)
        }
      if five then
        assert(inspect(changed(raw, List(1, 4), _ => node(Value.Map(Vector.empty)))).isLeft)
        assert(inspect(changed(raw, List(1, 4), _ => arr(Vector(node(Value.NInt(-1)))))).isLeft)
      Vector(0, 1, 8).foreach { tag =>
        assert(inspect(changed(raw, List(0), _ => node(Value.UInt(tag)))).isLeft)
      }
      assert(inspect(Bytes(raw.value.dropRight(1))).isLeft)
      assert(inspect(Bytes(raw.value :+ 0.toByte)).isLeft)
      assert(inspect(right(Cbor.encode(Value.Tag(24, node(Value.ByteString(raw)))))).isLeft)
      assert(CardanoBlockIndex.inspect(raw, raw.size - 1).isLeft)
      assert(CardanoBlockIndex.inspect(raw, CardanoBlockIndex.Limits(maxDepth = 1)).isLeft)
      assert(CardanoBlockIndex.inspect(raw, CardanoBlockIndex.Limits(maxItems = 4)).isLeft)
      assert(CardanoBlockIndex.inspect(raw, CardanoBlockIndex.Limits(maxStringBytes = 31)).isLeft)
      // Body-only mutation explicitly demonstrates lack of commitment validation.
      val bodyChanged = Bytes(
        raw.value.take(original.headerOffset + original.headerLength) ++
          Vector(0x80.toByte, 0x80.toByte, 0xa0.toByte) ++ (if five then Vector(0x80.toByte)
                                                            else Vector.empty)
      )
      val b = right(inspect(bodyChanged))
      assertEquals(b.headerHash, original.headerHash)
      assertNotEquals(b.rawSha256, original.rawSha256)
    }

  test("era determines shape, never protocol major; original indefinite Praos header is hashed") {
    val raw = resource("babbage1.block"); val original = right(inspect(raw))
    val wide = Bytes(Vector(0x98.toByte, 2.toByte) ++ raw.value.drop(1))
    assertEquals(right(inspect(wide)).headerOffset, 4)
    assertEquals(right(inspect(wide)).headerHash, original.headerHash)
    val indefinite = Bytes(
      raw.value.take(3) ++ Vector(0x9f.toByte) ++ original.headerBytes.value.drop(1) ++
        Vector(0xff.toByte) ++ raw.value.drop(3 + original.headerLength)
    )
    val b = right(inspect(indefinite))
    assertNotEquals(b.headerHash, original.headerHash)
    assertEquals(b.headerHash, Blake2b.hash256.hash(b.headerBytes))
    assert(inspect(changed(raw, List(0), _ => node(Value.UInt(5)))).isLeft)
    assertEquals(
      right(inspect(changed(raw, List(1, 0, 0, 9, 0), _ => node(Value.UInt(99))))).era,
      "babbage"
    )
    assertEquals(
      right(inspect(changed(resource("mary1.block"), List(0), _ => node(Value.UInt(2))))).era,
      "shelley"
    )
  }
