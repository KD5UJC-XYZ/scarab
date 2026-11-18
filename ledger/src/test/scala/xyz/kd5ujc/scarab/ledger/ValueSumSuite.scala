// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.{Bytes, CollidingBytes}

/** [[Value.sum]] is the value a fold of `+` gives, which it replaces where the rules sum what a
  * transaction spends and produces, and its maps are sorted whatever the hash codes of the names.
  */
class ValueSumSuite extends ScalaCheckSuite:
  // few policies and names, quantities of either sign and zero, so that sums cancel and prune
  private val key: Gen[Bytes] = Gen.choose(0, 3).map(i => Bytes(Vector.fill(i)(i.toByte)))
  private val assets: Gen[Map[Bytes, Map[Bytes, BigInt]]] =
    Gen.mapOf(Gen.zip(key, Gen.mapOf(Gen.zip(key, Gen.choose(-3, 3).map(BigInt(_))))))
  private val value: Gen[Value] = Gen.zip(Gen.choose(-5, 5).map(BigInt(_)), assets).map(Value(_, _))

  property("the sum of values is their fold under +") {
    Prop.forAll(Gen.listOf(value)) { values =>
      assertEquals(Value.sum(values), values.foldLeft(Value.zero)(_ + _))
    }
  }

  test("the sum of names of one hash code holds each of them, in sorted maps") {
    val policy = Bytes(Vector.fill(28)(9.toByte))
    val names = CollidingBytes.of(20000, 7)
    val values = names.grouped(500).map(g => Value(1, Map(policy -> g.map(_ -> BigInt(2)).toMap)))
    val total = Value.sum(values)
    assertEquals(total.coin, BigInt(40))
    assertEquals(total.assets.get(policy).map(_.size), Some(names.size))
    assert(
      total.assets.get(policy).exists(_.isInstanceOf[scala.collection.immutable.SortedMap[?, ?]])
    )
  }
