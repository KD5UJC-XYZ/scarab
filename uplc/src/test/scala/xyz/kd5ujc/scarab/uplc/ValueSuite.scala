// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.Value.{Invalid, Key, Quantity}

/** `Value` keeps the invariants of `PlutusCore/Value.hs` and refuses as `buildValueWith` does. */
final class ValueSuite extends munit.FunSuite:
  private def b(s: String): Bytes = Bytes.wrap(s.getBytes("US-ASCII"))
  private def k(s: String): Key = Key(b(s)).getOrElse(fail(s"key $s"))
  private def q(n: BigInt): Quantity = Quantity(n).getOrElse(fail(s"quantity $n"))

  test("keys are at most 32 bytes and quantities signed 128-bit") {
    assert(Key(Bytes.wrap(Array.fill[Byte](32)(1))).isDefined)
    assert(Key(Bytes.wrap(Array.fill[Byte](33)(1))).isEmpty)
    assert(Quantity((BigInt(1) << 127) - 1).isDefined)
    assert(Quantity(BigInt(1) << 127).isEmpty)
    assert(Quantity(-(BigInt(1) << 127)).isDefined)
    assert(Quantity(-(BigInt(1) << 127) - 1).isEmpty)
  }

  test("build accepts strictly ascending entries and caches the sizes") {
    val v =
      Value.build(Seq(k("") -> Seq(k("") -> q(5)), k("a") -> Seq(k("x") -> q(-1), k("y") -> q(2))))
    assertEquals(v.map(x => (x.totalSize, x.maxInnerSize, x.negativeAmounts)), Right((3, 2, 1)))
    assertEquals(Value.empty.totalSize, 0)
  }

  test("build refuses as buildValueWith does, in its order") {
    assertEquals(
      Value.build(Seq(k("b") -> Seq(k("x") -> q(1)), k("a") -> Seq(k("x") -> q(1)))),
      Left(Invalid.CurrencySymbolsNotAscending(k("b"), k("a")))
    )
    assertEquals(
      Value.build(Seq(k("a") -> Seq(k("x") -> q(1)), k("a") -> Seq(k("y") -> q(1)))),
      Left(Invalid.CurrencySymbolsNotAscending(k("a"), k("a")))
    )
    assertEquals(
      Value.build(Seq(k("a") -> Seq(k("y") -> q(1), k("x") -> q(1)))),
      Left(Invalid.TokenNamesNotAscending(k("a"), k("y"), k("x")))
    )
    assertEquals(Value.build(Seq(k("a") -> Seq())), Left(Invalid.EmptyInnerMap(k("a"))))
    assertEquals(
      Value.build(Seq(k("a") -> Seq(k("x") -> q(0)))),
      Left(Invalid.ZeroQuantity(k("a"), k("x")))
    )
    // The token check comes before the zero check of the same entry (Value.hs:681-691).
    assertEquals(
      Value.build(Seq(k("a") -> Seq(k("y") -> q(1), k("x") -> q(0)))),
      Left(Invalid.TokenNamesNotAscending(k("a"), k("y"), k("x")))
    )
  }

  test("the decoders type every key and amount before any order check") {
    val long = Bytes.wrap(Array.fill[Byte](33)(0))
    assertEquals(
      Value.decoded(Seq(b("b") -> Seq(b("x") -> BigInt(1)), b("a") -> Seq(long -> BigInt(1)))),
      Left(Invalid.KeyTooLong(long))
    )
    assertEquals(
      Value.decoded(
        Seq(b("b") -> Seq(b("x") -> BigInt(0)), b("a") -> Seq(b("x") -> (BigInt(1) << 127)))
      ),
      Left(Invalid.QuantityOutOfBounds(BigInt(1) << 127))
    )
  }

  test("pack drops zero quantities and then empty inner maps") {
    val packed = Value.pack(
      SortedMap(
        k("a") -> SortedMap(k("x") -> q(0)),
        k("b") -> SortedMap(k("x") -> q(3), k("y") -> q(0))
      )
    )
    assertEquals(packed.toList, Vector(k("b") -> Vector(k("x") -> q(3))))
  }
