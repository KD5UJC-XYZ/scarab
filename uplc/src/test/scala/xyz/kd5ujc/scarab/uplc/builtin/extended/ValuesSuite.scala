// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultUni, PlutusData, Value}
import xyz.kd5ujc.scarab.uplc.DefaultFun.*
import xyz.kd5ujc.scarab.uplc.builtin.{
  BuiltinFailure,
  Denotation,
  ErrorClass,
  EvaluationFailure,
  Outcome,
  UnliftingCause,
  UnliftingFailure
}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreVariant, Probe}

import ExtendedTesting.*

/** The `Value` builtins (`PlutusCore/Value.hs`), `expModInteger` (`PlutusCore/Crypto/ExpMod.hs`)
  * and the arrays (`Default/Builtins.hs:2378-2409`) of plutus 1.70.0.0, cited by line.
  */
final class ValuesSuite extends munit.FunSuite:
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
  private def valueOf(outcome: Outcome[Probe]): Value = ExtendedTesting.value(outcome) match
    case Constant.Value(x) => x
    case other             => sys.error(s"expected a value, got $other")
  private def failed(f: ValueFailure) = EvaluationFailure.ValueOperation(f)
  private val Max: BigInt = (BigInt(1) << 127) - 1
  private val long = "aa" * 33

  test("insertCoin: an amount of 0 deletes without checking the keys (Value.hs:345-411)") {
    val one = v("" -> Seq("" -> 1))
    assertEquals(valueOf(e(InsertCoin, bytes(long), bytes(""), int(0), value(one))), one)
    assertEquals(valueOf(e(InsertCoin, bytes(""), bytes(""), int(0), value(one))), Value.empty)
    val two = v("aa" -> Seq("01" -> 1, "02" -> 2))
    assertEquals(
      valueOf(e(InsertCoin, bytes("aa"), bytes("01"), int(0), value(two))),
      v("aa" -> Seq("02" -> 2))
    )
  }

  test("insertCoin: the currency, the token, then the amount are checked (Value.hs:347-351)") {
    assertEquals(
      failureOf(e(InsertCoin, bytes(long), bytes(long), int(Max + 1), value(Value.empty))),
      failed(ValueFailure.InvalidCurrency(hexBytes(long))) ->
        Vector(s"insertCoin: invalid currency: [${Vector.fill(33)("170").mkString(",")}]")
    )
    assertEquals(
      failureOf(e(InsertCoin, bytes("01ff"), bytes(long), int(1), value(Value.empty)))._1,
      failed(ValueFailure.InvalidToken(hexBytes(long)))
    )
    assertEquals(
      failureOf(e(InsertCoin, bytes(""), bytes(""), int(Max + 1), value(Value.empty))),
      failed(ValueFailure.QuantityOutOfBounds(Max + 1)) ->
        Vector(s"insertCoin: quantity out of bounds: ${Max + 1}")
    )
    assertEquals(showWord8s(hexBytes("01ff")), "[1,255]")
    assertEquals(
      valueOf(
        e(InsertCoin, bytes("aa"), bytes("bb"), int(-Max - 1), value(v("aa" -> Seq("bb" -> 1))))
      ),
      v("aa" -> Seq("bb" -> (-Max - 1)))
    )
  }

  test("lookupCoin: 0 for what the value does not have, a key too long included (413-418)") {
    val x = v("aa" -> Seq("bb" -> 7))
    assertEquals(integerOf(e(LookupCoin, bytes("aa"), bytes("bb"), value(x))), BigInt(7))
    assertEquals(integerOf(e(LookupCoin, bytes("aa"), bytes("cc"), value(x))), BigInt(0))
    assertEquals(integerOf(e(LookupCoin, bytes(long), bytes("bb"), value(x))), BigInt(0))
  }

  test("valueContains: negative amounts fail, first value first (487-500)") {
    val neg = v("aa" -> Seq("bb" -> -1))
    val pos = v("aa" -> Seq("bb" -> 2, "cc" -> 1))
    assertEquals(
      failureOf(e(ValueContains, value(neg), value(neg))),
      failed(ValueFailure.FirstValueNegative) ->
        Vector("valueContains: first value contains negative amounts")
    )
    assertEquals(
      failureOf(e(ValueContains, value(pos), value(neg))),
      failed(ValueFailure.SecondValueNegative) ->
        Vector("valueContains: second value contains negative amounts")
    )
    assert(boolOf(e(ValueContains, value(pos), value(v("aa" -> Seq("bb" -> 2))))))
    assert(!boolOf(e(ValueContains, value(pos), value(v("aa" -> Seq("bb" -> 3))))))
    assert(!boolOf(e(ValueContains, value(pos), value(v("ab" -> Seq("bb" -> 1))))))
    assert(boolOf(e(ValueContains, value(pos), value(Value.empty))))
  }

  test("unionValue: sums, a sum of 0 dropped, the 128-bit bounds checked (508-538)") {
    val x = v("aa" -> Seq("bb" -> 2), "cc" -> Seq("dd" -> 1))
    val y = v("aa" -> Seq("bb" -> -2, "ee" -> 5))
    assertEquals(
      valueOf(e(UnionValue, value(x), value(y))),
      v("aa" -> Seq("ee" -> 5), "cc" -> Seq("dd" -> 1))
    )
    assertEquals(
      failureOf(e(UnionValue, value(v("" -> Seq("" -> Max))), value(v("" -> Seq("" -> 1))))),
      failed(ValueFailure.UnionOutOfBounds) ->
        Vector("unionValue: quantity is out of the signed 128-bit integer bounds")
    )
  }

  test("valueData: the nested maps, at most 40000 entries (540-555)") {
    val x = v("aa" -> Seq("bb" -> 2))
    assertEquals(
      ExtendedTesting.value(e(ValueData, value(x))),
      Constant.Data(
        PlutusData.Map(
          Vector(
            PlutusData.B(hexBytes("aa")) -> PlutusData.Map(
              Vector(PlutusData.B(hexBytes("bb")) -> PlutusData.I(2))
            )
          )
        )
      )
    )
    def wide(n: Int): Value =
      Value
        .build(
          (0 until n).map(i =>
            key(f"${i}%08x") -> Seq(key("") -> Value.Quantity(BigInt(1)).getOrElse(sys.error("q")))
          )
        )
        .fold(e => sys.error(e.toString), identity)
    assert(ExtendedTesting.result(e(ValueData, value(wide(40000)))).isRight)
    assertEquals(
      failureOf(e(ValueData, value(wide(40001)))),
      failed(ValueFailure.ValueDataTooLarge) -> Vector(
        "valueData: maximum input size (40000) exceeded"
      )
    )
  }

  test("unValueData: each refusal with its line, in the order buildValueWith checks (557-694)") {
    import PlutusData.{B, I, List as L, Map as M}
    def cause(d: PlutusData): String = failureOf(e(UnValueData, data(d)))._2.mkString
    val b = B(hexBytes("aa"))
    val c = B(hexBytes("cc"))
    assertEquals(cause(L(Vector.empty)), "unValueData: non-Map constructor")
    assertEquals(cause(M(Vector(I(1) -> M(Vector.empty)))), "unValueData: non-B constructor")
    assertEquals(
      cause(M(Vector(B(hexBytes(long)) -> I(1)))),
      s"unValueData: invalid key: [${Vector.fill(33)("170").mkString(",")}]"
    )
    assertEquals(cause(M(Vector(b -> I(1)))), "unValueData: non-Map constructor")
    assertEquals(
      cause(M(Vector(b -> M(Vector(b -> B(Bytes.empty)))))),
      "unValueData: non-I constructor"
    )
    assertEquals(cause(M(Vector(b -> M(Vector(b -> I(Max + 1)))))), "unValueData: invalid quantity")
    assertEquals(cause(M(Vector(b -> M(Vector(b -> I(0)))))), "unValueData: zero quantity")
    assertEquals(cause(M(Vector(b -> M(Vector.empty)))), "unValueData: empty inner map")
    assertEquals(
      cause(M(Vector(c -> M(Vector(b -> I(1))), b -> M(Vector(b -> I(1)))))),
      "unValueData: currency symbols not strictly ascending"
    )
    assertEquals(
      cause(M(Vector(b -> M(Vector(c -> I(1), b -> I(1)))))),
      "unValueData: token names not strictly ascending"
    )
    // the current entry is converted before it is compared with the one before
    assertEquals(
      cause(M(Vector(c -> M(Vector(b -> I(1))), b -> I(1)))),
      "unValueData: non-Map constructor"
    )
    assertEquals(
      valueOf(e(UnValueData, data(M(Vector(b -> M(Vector(c -> I(3)))))))),
      v("aa" -> Seq("cc" -> 3))
    )
  }

  test("scaleValue: 0 gives the empty value; the first product out of bounds fails (604-628)") {
    val x = v("aa" -> Seq("bb" -> 2, "cc" -> -3))
    assertEquals(valueOf(e(ScaleValue, int(-2), value(x))), v("aa" -> Seq("bb" -> -4, "cc" -> 6)))
    assertEquals(valueOf(e(ScaleValue, int(0), value(x))), Value.empty)
    val big = v("aa" -> Seq("bb" -> Max), "bb" -> Seq("aa" -> -Max))
    assertEquals(
      failureOf(e(ScaleValue, int(2), value(big))),
      failed(ValueFailure.ScaleOutOfBounds(2, Max)) ->
        Vector(s"scaleValue: quantity out of bounds: 2 * $Max")
    )
  }

  private def expMod(b: BigInt, x: BigInt, m: BigInt) = e(ExpModInteger, int(b), int(x), int(m))

  test("expModInteger: values (ExpMod.hs:17-35)") {
    assertEquals(integerOf(expMod(2, 10, 1000)), BigInt(24))
    assertEquals(integerOf(expMod(3, -1, 7)), BigInt(5))
    assertEquals(integerOf(expMod(-3, 1, 7)), BigInt(4))
    assertEquals(integerOf(expMod(0, 0, 5)), BigInt(1))
    assertEquals(integerOf(expMod(5, -3, 1)), BigInt(0))
    // m = 1 is answered before the bounds of the base and exponent
    assertEquals(integerOf(expMod(BigInt(1) << 9000, 1, 1)), BigInt(0))
    assertEquals(integerOf(expMod(2, 1, (BigInt(1) << 8191) - 1)), BigInt(2))
  }

  test("expModInteger: each failure with its line (Builtins.hs:2314-2326, ExpMod.hs:17-50)") {
    def line(b: BigInt, x: BigInt, m: BigInt) = failureOf(expMod(b, x, m))._2
    assertEquals(line(1, 1, -1), Vector("expModInteger: negative modulus"))
    assertEquals(line(1, 1, 0), Vector("expMod: invalid modulus"))
    assertEquals(line(1, 1, BigInt(1) << 8191), Vector("expMod: invalid modulus"))
    assertEquals(line(0, -1, 5), Vector("expMod: 0 is not invertible modulo 5"))
    assertEquals(line(-2, -1, 4), Vector("expMod: -2 is not invertible modulo 4"))
    assertEquals(line(BigInt(1) << 8191, 1, 5), Vector("expMod: out of bounds"))
    assertEquals(line(1, -(BigInt(1) << 8191) - 1, 5), Vector("expMod: out of bounds"))
    assertEquals(integerOf(expMod(-(BigInt(1) << 8191), 1, 5)), (-(BigInt(1) << 8191)).mod(5))
  }

  private val ints = list(DefaultUni.Integer, Constant.Integer(10), Constant.Integer(20))
  private def array(items: Constant*) =
    Constant.Array.of(DefaultUni.Integer, items.toVector).fold(e => sys.error(e.toString), identity)

  test("the arrays: length, from a list, by index (Builtins.hs:2378-2409)") {
    val a = array(Constant.Integer(10), Constant.Integer(20))
    assertEquals(integerOf(e(LengthOfArray, con(a))), BigInt(2))
    assertEquals(ExtendedTesting.value(e(ListToArray, con(ints))), a)
    assertEquals(ExtendedTesting.value(e(IndexArray, con(a), int(1))), Constant.Integer(20))
    assertEquals(
      failureOf(e(IndexArray, con(a), int(2))),
      EvaluationFailure.ArrayIndexOutOfBounds(2, 2) -> Vector("Array index out of bounds")
    )
    assertEquals(
      failureOf(e(IndexArray, con(a), int(-1)))._1,
      EvaluationFailure.ArrayIndexOutOfBounds(-1, 2)
    )
  }

  test("the arrays: a constant of another shape is a structural error without a log line") {
    def structural(d: Denotation, args: Constant*) =
      d(args.toVector.map(xyz.kd5ujc.scarab.uplc.builtin.Argument.Con(_)))(using
        Probe.given_MachineValue_Probe
      )
    val denotations = ExtendedDenotations(CoreVariant.E)
    def get(f: xyz.kd5ujc.scarab.uplc.DefaultFun): Denotation =
      denotations.getOrElse(f, sys.error(s"$f"))
    assertEquals(
      structural(get(LengthOfArray), ints),
      Outcome.Failure(
        BuiltinFailure.Unlifting(
          UnliftingFailure(ErrorClass.Structural, UnliftingCause.ExpectedArray(0, ints.typeOf))
        ),
        Vector.empty
      )
    )
    assertEquals(
      structural(get(ListToArray), array()),
      Outcome.Failure(
        BuiltinFailure.Unlifting(
          UnliftingFailure(ErrorClass.Structural, UnliftingCause.ExpectedList(0, array().typeOf))
        ),
        Vector.empty
      )
    )
  }
