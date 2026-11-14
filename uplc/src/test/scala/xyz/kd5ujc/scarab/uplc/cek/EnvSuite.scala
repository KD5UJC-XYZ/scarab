// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.uplc.SmallStack

/** The skew-binary list against a plain list: `indexOne` counts from 1 at the element consed last
  * (`contIndexOne`, `SkewBinary.hs:128-130`, plutus 1.70.0.0 `507cab8c`).
  */
final class EnvSuite extends munit.ScalaCheckSuite:
  private def build(items: List[Int]): Env[Int] = items.reverse.foldLeft(Env.empty[Int])(_.cons(_))

  private def lookup(env: Env[Int], index: Long): Option[Int] = env.indexOne(index) match
    case found: Env.Tree[Int] => Some(found.value)
    case Env.Absent           => None

  property("indexOne agrees with the list it was consed from") {
    Prop.forAll(Gen.choose(0, 300).flatMap(n => Gen.listOfN(n, Gen.choose(-1000, 1000)))) { items =>
      val env = build(items)
      val agree = (0L to items.size.toLong + 2).forall { i =>
        lookup(env, i) == Option.when(i >= 1 && i <= items.size)(items((i - 1).toInt))
      }
      Prop(agree) :| "every index" && Prop(env.length == items.size.toLong) :| "length"
    }
  }

  test("index 0 and an index read as an unsigned word of 2^63 or more are absent") {
    val env = build(List(1, 2, 3))
    assertEquals(lookup(env, 0L), None)
    assertEquals(lookup(env, -1L), None, "2^64 - 1")
    assertEquals(lookup(env, Long.MinValue), None, "2^63")
    assertEquals(lookup(env, 1L), Some(1))
    assertEquals(lookup(env, 3L), Some(3))
    assertEquals(lookup(env, 4L), None)
  }

  test("a large environment: every index in logarithmic depth, on a small stack") {
    val n = 1 << 20
    val env = SmallStack.run((0 until n).foldLeft(Env.empty[Int])(_.cons(_)))
    // The element consed last is index 1.
    assertEquals(lookup(env, 1L), Some(n - 1))
    assertEquals(lookup(env, n.toLong), Some(0))
    assertEquals(lookup(env, n.toLong / 2), Some(n - n / 2))
    assertEquals(env.length, n.toLong)
  }
