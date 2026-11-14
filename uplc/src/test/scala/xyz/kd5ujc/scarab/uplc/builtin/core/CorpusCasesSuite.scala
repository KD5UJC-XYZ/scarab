// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni, PlutusData}
import xyz.kd5ujc.scarab.uplc.builtin.{BuiltinFailure, ErrorClass, EvaluationFailure, Outcome}

import CoreTesting.*
import DefaultFun.*

/** Cases of the upstream conformance corpus (`plutus-reference/conformance/builtin/semantics`,
  * plutus 1.70.0.0) that are a single saturated application of a core builtin to constants,
  * transcribed by hand with the reference's expected result, under variant E. This is not a run of
  * the corpus, which needs the parser and the machine: it checks that each of these cases fails or
  * succeeds in the builtin, and for the reason the reference's source gives.
  */
final class CorpusCasesSuite extends munit.FunSuite:
  private def failure(f: DefaultFun, args: Probe*): BuiltinFailure =
    CoreTesting(CoreVariant.E, f, args*) match
      case Outcome.Failure(failure, _) => failure
      case other                       => fail(s"$f: expected a failure, got $other")

  private def evaluation(f: EvaluationFailure) = BuiltinFailure.Evaluation(f)

  test("the cases whose expected result is an evaluation failure, for the reason of the builtin") {
    // consByteString-01, -02: a `Word8` in E
    assertEquals(
      failure(ConsByteString, int(256), bytes("")).errorClass,
      Some(ErrorClass.Operational)
    )
    assertEquals(
      failure(ConsByteString, int(-88), bytes("686543616B654973414C6965")).errorClass,
      Some(ErrorClass.Operational)
    )
    // decodeUtf8-invalid
    assertEquals(failure(DecodeUtf8, bytes("A3")), evaluation(EvaluationFailure.InvalidUtf8))
    // divideInteger-zero (and mkCons/divideInteger), modInteger-zero, quotientInteger-zero,
    // remainderInteger-zero
    for f <- Vector(DivideInteger, ModInteger, QuotientInteger, RemainderInteger) do
      assertEquals(failure(f, int(1), int(0)), evaluation(EvaluationFailure.DivisionByZero), s"$f")
    // headList-02, headPartial, tailList-01, tailList-partial
    val noData = con(list(DefaultUni.Data))
    for f <- Vector(HeadList, TailList); xs <- Vector(con(list(DefaultUni.Integer)), noData) do
      assertEquals(failure(f, xs), evaluation(EvaluationFailure.EmptyList), s"$f")
    // ifThenElse-bad-cond-01 (a constant of another type), -02 (a lambda)
    assertEquals(
      failure(IfThenElse, int(1), int(11), int(-22)).errorClass,
      Some(ErrorClass.Structural)
    )
    assertEquals(
      failure(IfThenElse, lambda, int(11), int(-22)).errorClass,
      Some(ErrorClass.Structural)
    )
    // indexByteStringOOB, indexByteStringOverflow (an `Int`)
    assertEquals(
      failure(IndexByteString, bytes("00"), int(1)),
      evaluation(EvaluationFailure.ByteStringIndexOutOfBounds(1, 1))
    )
    assertEquals(
      failure(IndexByteString, bytes("00"), int(BigInt("9223372036854775808"))).errorClass,
      Some(ErrorClass.Operational)
    )
    // mkCons-fail
    assertEquals(failure(MkCons, int(3), noData).errorClass, Some(ErrorClass.Structural))
    // unBData-fail, unConstrData-fail, unIData-fail, unListData-fail, unMapData-fail
    val af00 = data(PlutusData.B(hexBytes("af00")))
    assertEquals(failure(UnBData, data(PlutusData.I(0))).errorClass, None)
    for f <- Vector(UnConstrData, UnIData, UnListData, UnMapData) do
      assertEquals(failure(f, af00).errorClass, None, s"$f")
  }

  extension (f: BuiltinFailure)
    /** The class of an unlifting error; none for an evaluation failure. */
    def errorClass: Option[ErrorClass] = f match
      case BuiltinFailure.Unlifting(u)  => Some(u.errorClass)
      case BuiltinFailure.Evaluation(_) => None

  // `builtin/semantics/dropList`, all 16 cases.
  test("every dropList case") {
    val nine = (1 to 9).map(i => Constant.Integer(11 * i))
    val integers = con(list(DefaultUni.Integer, nine*))
    val hexes = Vector(
      "123456",
      "234567",
      "345678",
      "456789",
      "56789a",
      "6789ab",
      "789abc",
      "89abcd93232479173417824fff",
      "ffeeffeeffee"
    ).map(h => Constant.ByteString(hexBytes(h)))
    val strings = con(list(DefaultUni.ByteString, hexes*))
    val long = con(list(DefaultUni.Integer, (20 to 111).map(Constant.Integer(_))*))
    def drop(n: BigInt, xs: Probe) = result(CoreTesting(CoreVariant.E, DropList, int(n), xs))
    def ints(items: Seq[Constant]) = Right(list(DefaultUni.Integer, items*))
    def byteStrings(items: Seq[Constant]) = Right(list(DefaultUni.ByteString, items*))
    assertEquals(drop(0, integers), ints(nine), "01")
    assertEquals(drop(0, strings), byteStrings(hexes), "02")
    assertEquals(drop(7, integers), ints(nine.drop(7)), "03")
    assertEquals(drop(7, strings), byteStrings(hexes.drop(7)), "04")
    assertEquals(drop(17, integers), ints(Nil), "05")
    assertEquals(drop(17, strings), byteStrings(Nil), "06")
    assertEquals(drop(-7, integers), ints(nine), "07")
    assertEquals(drop(-7, strings), byteStrings(hexes), "08")
    assertEquals(drop(BigInt("10000000000000000000"), integers), ints(Nil), "09")
    assertEquals(drop(BigInt("-12345678901234567890"), integers), ints(nine), "10")
    val boolString = DefaultUni.Pair(DefaultUni.Bool, DefaultUni.String)
    assertEquals(drop(-1234, con(list(boolString))), Right(list(boolString)), "11")
    val unitInteger = DefaultUni.Pair(DefaultUni.Unit, DefaultUni.Integer)
    assertEquals(drop(0, con(list(unitInteger))), Right(list(unitInteger)), "12")
    val listUnit = DefaultUni.Pair(DefaultUni.List(DefaultUni.Integer), DefaultUni.Unit)
    assertEquals(drop(1234, con(list(listUnit))), Right(list(listUnit)), "13")
    assertEquals(drop(BigInt("9223372036854775806"), long), ints(Nil), "14")
    assertEquals(drop(BigInt("9223372036854775807"), long), ints(Nil), "15")
    assertEquals(drop(BigInt("9223372036854775808"), long), ints(Nil), "16")
  }

  // The results of D and E are plain `Integer` and `ByteString`, not bounded
  // (`Builtins.hs:1099-1257`).
  test("the results of the on-chain variants are not bounded, only their arguments") {
    val max = BigInt(2).pow(262143) - 1
    for v <- Vector(CoreVariant.D, CoreVariant.E) do
      assertEquals(
        result(CoreTesting(v, MultiplyInteger, int(max), int(max))),
        Right(Constant.Integer(max * max)),
        s"$v"
      )
      val big = bytes(xyz.kd5ujc.scarab.codec.Bytes.wrap(new Array[Byte](65536)))
      result(CoreTesting(v, AppendByteString, big, big)) match
        case Right(Constant.ByteString(b)) => assertEquals(b.size, 131072, s"$v")
        case other                         => fail(s"$v: $other")
  }
