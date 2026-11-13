// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import xyz.kd5ujc.scarab.uplc.{Constant, DefaultUni, Value}
import xyz.kd5ujc.scarab.uplc.DefaultFun.*
import xyz.kd5ujc.scarab.uplc.builtin.EvaluationFailure
import xyz.kd5ujc.scarab.uplc.builtin.core.Probe

import ExtendedTesting.*

/** Batch 7 (`multiIndexArray`, `policies`, `assetCount`, `keepPolicies`, `dropPolicies`,
  * `Default/Builtins.hs:2482-2527`, `PlutusCore/Arrays.hs`, `PlutusCore/Value.hs:419-475`), which
  * no protocol from 5 to 11 makes available and the corpus has no case of.
  */
final class Batch7Suite extends munit.FunSuite:
  private def key(hex: String): Value.Key =
    Value.Key(hexBytes(hex)).getOrElse(sys.error(s"key $hex"))
  private def v(entries: (String, Seq[(String, BigInt)])*): Value =
    Value
      .build(
        entries.map((c, ts) =>
          key(c) -> ts.map((t, q) => key(t) -> Value.Quantity(q).getOrElse(sys.error(s"$q")))
        )
      )
      .fold(e => sys.error(e.toString), identity)
  private def value(x: Value): Probe = con(Constant.Value(x))
  private def valueOf(o: xyz.kd5ujc.scarab.uplc.builtin.Outcome[Probe]): Value =
    ExtendedTesting.value(o) match
      case Constant.Value(x) => x
      case other             => sys.error(s"expected a value, got $other")
  private def byteStrings(hexes: String*): Probe =
    con(list(DefaultUni.ByteString, hexes.map(h => Constant.ByteString(hexBytes(h)))*))
  private val array = Constant.Array
    .of(DefaultUni.Integer, Vector(10, 20, 30).map(i => Constant.Integer(i)))
    .fold(e => sys.error(e.toString), identity)

  test("multiIndexArray: the elements in the order of the indices (Arrays.hs:48-69)") {
    assertEquals(
      ExtendedTesting.value(e(MultiIndexArray, con(array), integers(2, 0, 2))),
      list(DefaultUni.Integer, Constant.Integer(30), Constant.Integer(10), Constant.Integer(30))
    )
    assertEquals(
      ExtendedTesting.value(e(MultiIndexArray, con(array), integers())),
      list(DefaultUni.Integer)
    )
    assertEquals(
      failureOf(e(MultiIndexArray, con(array), integers(0, 3, -1))),
      EvaluationFailure.ArrayIndices(ArrayFailure.IndexOutOfBounds(3)) ->
        Vector("multiIndexArray: array index out of bounds", "Index: 3")
    )
  }

  test("multiIndexArray: at most 1024 indices, counted as they are read (Arrays.hs:34-61)") {
    val most = integers(Vector.fill(1024)(BigInt(1))*)
    assertEquals(
      ExtendedTesting.value(e(MultiIndexArray, con(array), most)) match
        case l: Constant.List => l.items.size
        case _                => -1
      ,
      1024
    )
    // the 1025th index fails on the count even when it is out of bounds
    val tooMany = integers((Vector.fill(1024)(BigInt(1)) :+ BigInt(99))*)
    assertEquals(
      failureOf(e(MultiIndexArray, con(array), tooMany)),
      EvaluationFailure.ArrayIndices(ArrayFailure.TooManyIndices) ->
        Vector("multiIndexArray: too many indices (maximum is 1024)")
    )
    // an index out of bounds before the 1025th fails on the bounds
    val early = integers((BigInt(7) +: Vector.fill(1100)(BigInt(1)))*)
    assertEquals(
      failureOf(e(MultiIndexArray, con(array), early))._1,
      EvaluationFailure.ArrayIndices(ArrayFailure.IndexOutOfBounds(7))
    )
  }

  private val x = v("" -> Seq("" -> 5), "aa" -> Seq("01" -> 1, "02" -> -2), "bb" -> Seq("01" -> 3))

  test("policies and assetCount (Value.hs:419-424, totalSize)") {
    assertEquals(
      ExtendedTesting.value(e(Policies, value(x))),
      list(
        DefaultUni.ByteString,
        Vector("", "aa", "bb").map(h => Constant.ByteString(hexBytes(h)))*
      )
    )
    assertEquals(integerOf(e(AssetCount, value(x))), BigInt(4))
    assertEquals(integerOf(e(AssetCount, value(Value.empty))), BigInt(0))
  }

  test("keepPolicies and dropPolicies: ids the value lacks, or too long, are ignored (425-477)") {
    val long = "cc" * 33
    assertEquals(
      valueOf(e(KeepPolicies, byteStrings("bb", "", long, "dd", "bb"), value(x))),
      v("" -> Seq("" -> 5), "bb" -> Seq("01" -> 3))
    )
    assertEquals(
      valueOf(e(DropPolicies, byteStrings("bb", "", long, "dd", "bb"), value(x))),
      v("aa" -> Seq("01" -> 1, "02" -> -2))
    )
    assertEquals(valueOf(e(KeepPolicies, byteStrings(), value(x))), Value.empty)
    assertEquals(valueOf(e(DropPolicies, byteStrings(), value(x))), x)
  }
