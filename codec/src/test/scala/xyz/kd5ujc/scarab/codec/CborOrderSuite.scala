// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

/** [[CborOrder]] is a total order that agrees with the equality of decoded items. */
class CborOrderSuite extends ScalaCheckSuite:
  private def node(v: Value): Node = Node(v, Bytes.empty)

  // two NaNs of different payloads are one value, as equality reads the canonical bits
  private val float: Gen[Value] = Gen
    .oneOf(0.0, -0.0, 1.5, Double.NaN, java.lang.Double.longBitsToDouble(0x7ff8000000000001L))
    .flatMap(d => Gen.oneOf(FloatWidth.values.toSeq).map(Value.Float(d, _)))

  private val leaf: Gen[Value] = Gen.oneOf(
    Gen.choose(0, 2).map(i => Value.UInt(BigInt(i))),
    Gen.choose(1, 2).map(i => Value.NInt(BigInt(-i))),
    Gen.choose(0, 2).map(i => Value.ByteString(Bytes(Vector.fill(i)(1.toByte)))),
    Gen.oneOf("", "a").map(Value.Text(_)),
    Gen.oneOf(true, false).map(Value.Bool(_)),
    Gen.const(Value.Null),
    Gen.choose(0, 1).map(Value.Simple(_)),
    float
  )

  private def value(depth: Int): Gen[Value] =
    if depth == 0 then leaf
    else
      Gen.frequency(
        3 -> leaf,
        1 -> Gen
          .choose(0, 2)
          .flatMap(Gen.listOfN(_, value(depth - 1)))
          .map(xs => Value.Arr(xs.toVector.map(node))),
        1 -> Gen
          .choose(0, 2)
          .flatMap(Gen.listOfN(_, Gen.zip(value(depth - 1), value(depth - 1))))
          .map(kvs => Value.Map(kvs.toVector.map((k, v) => node(k) -> node(v)))),
        1 -> Gen
          .zip(Gen.choose(0, 1), value(depth - 1))
          .map((t, v) => Value.Tag(BigInt(t), node(v)))
      )

  private val order = CborOrder.values

  property("two items compare equal exactly when they are equal") {
    Prop.forAll(value(3), value(3)) { (a, b) =>
      assertEquals(order.compare(a, b) == 0, a == b)
      assertEquals(order.compare(a, a), 0)
    }
  }

  property("the order is antisymmetric and transitive") {
    Prop.forAll(value(2), value(2), value(2)) { (a, b, c) =>
      assertEquals(Integer.signum(order.compare(a, b)), -Integer.signum(order.compare(b, a)))
      val sorted = Vector(a, b, c).sorted(using order)
      assert(order.lteq(sorted(0), sorted(1)) && order.lteq(sorted(1), sorted(2)))
      assert(order.lteq(sorted(0), sorted(2)))
    }
  }

  test("NaNs of two payloads are one value at every width, and other floats are not") {
    val other = java.lang.Double.longBitsToDouble(0x7ff8000000000001L)
    FloatWidth.values.foreach { w =>
      val (a, b) = (Value.Float(Double.NaN, w), Value.Float(other, w))
      assertEquals(a, b)
      assertEquals(order.compare(a, b), 0)
      assert(order.compare(Value.Float(0.0, w), Value.Float(-0.0, w)) != 0)
      assertNotEquals(Value.Float(0.0, w), Value.Float(-0.0, w))
    }
    assert(
      order.compare(Value.Float(1.5, FloatWidth.Half), Value.Float(1.5, FloatWidth.Single)) != 0
    )
  }

  test("the original bytes do not take part, as in equality") {
    val a = Value.Arr(Vector(Node(Value.UInt(1), Bytes(Vector(0x01.toByte)))))
    val b = Value.Arr(Vector(Node(Value.UInt(1), Bytes(Vector(0x18.toByte, 0x01.toByte)))))
    assertEquals(a, b)
    assertEquals(order.compare(a, b), 0)
  }

  test("items nested 100,000 deep compare on a 256 KiB thread") {
    def deep(leafValue: Value): Value =
      (1 to 100000).foldLeft(leafValue)((v, _) => Value.Arr(Vector(node(v))))
    val (x, y) = (deep(Value.UInt(1)), deep(Value.UInt(2)))
    val result = new java.util.concurrent.atomic.AtomicInteger(42)
    val thread = new Thread(null, () => result.set(order.compare(x, y)), "small", 256 * 1024)
    thread.start()
    thread.join()
    assertEquals(result.get, -1)
  }
