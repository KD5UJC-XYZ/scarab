// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import io.circe.{HCursor, parser}
import java.nio.file.{Files, Path}
import org.scalacheck.{Gen, Prop}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni, PlutusData, Text}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  BuiltinFailure,
  DataShape,
  ErrorClass,
  EvaluationFailure,
  Outcome,
  UnliftingBound,
  UnliftingCause,
  UnliftingFailure
}

import CoreTesting.*
import DefaultFun.*

/** The core denotations against the meanings of `toBuiltinMeaning`
  * (`PlutusCore/Default/Builtins.hs`, plutus 1.70.0.0 `507cab8c`, cited by line), applied as the
  * machine applies them (unlifting, then the denotation).
  */
final class CoreDenotationsSuite extends munit.ScalaCheckSuite:
  import CoreVariant.{A, B, C, D, E}
  private val variants = CoreVariant.values.toVector

  /** The application, with the invariant every failure keeps: its logs are the reference's message
    * for it.
    */
  private def run(v: CoreVariant, f: DefaultFun, args: Probe*): Outcome[Probe] =
    val outcome = CoreTesting(v, f, args*)
    outcome match
      case Outcome.Failure(BuiltinFailure.Evaluation(failure), logs) =>
        assertEquals(logs, failure.message.toVector, s"$f: the logs of $failure")
      case Outcome.Failure(BuiltinFailure.Unlifting(failure), logs) =>
        assertEquals(logs, CoreUnlifting.log(failure).toVector, s"$f: the logs of $failure")
      case Outcome.Success(_, _) => ()
    outcome

  private def value(v: CoreVariant, f: DefaultFun, args: Probe*): Either[BuiltinFailure, Constant] =
    result(run(v, f, args*))

  private def in(f: DefaultFun, args: Probe*): Either[BuiltinFailure, Constant] = value(E, f, args*)

  private def structural(cause: UnliftingCause) =
    Left(BuiltinFailure.Unlifting(UnliftingFailure(ErrorClass.Structural, cause)))

  private def integer(n: BigInt) = Right(Constant.Integer(n))
  private def truth(b: Boolean) = Right(Constant.Bool(b))
  private def byteString(hex: String) = Right(Constant.ByteString(hexBytes(hex)))

  test("every core builtin has a denotation in every variant, and none of the others does") {
    val core = Set(
      AddInteger,
      SubtractInteger,
      MultiplyInteger,
      DivideInteger,
      QuotientInteger,
      RemainderInteger,
      ModInteger,
      EqualsInteger,
      LessThanInteger,
      LessThanEqualsInteger,
      AppendByteString,
      ConsByteString,
      SliceByteString,
      LengthOfByteString,
      IndexByteString,
      EqualsByteString,
      LessThanByteString,
      LessThanEqualsByteString,
      AppendString,
      EqualsString,
      EncodeUtf8,
      DecodeUtf8,
      IfThenElse,
      ChooseUnit,
      Trace,
      FstPair,
      SndPair,
      ChooseList,
      MkCons,
      HeadList,
      TailList,
      NullList,
      ChooseData,
      ConstrData,
      MapData,
      ListData,
      IData,
      BData,
      UnConstrData,
      UnMapData,
      UnListData,
      UnIData,
      UnBData,
      EqualsData,
      SerialiseData,
      MkPairData,
      MkNilData,
      MkNilPairData,
      DropList
    )
    assertEquals(CoreDenotations.functions, core)
    for v <- variants do assertEquals(CoreDenotations(v).keySet, core, s"$v")
  }

  // `Builtins.hs:1099-1143`
  test("addInteger, subtractInteger and multiplyInteger are exact") {
    val big = BigInt(2).pow(200) + 3
    assertEquals(in(AddInteger, int(big), int(-big)), integer(0))
    assertEquals(in(SubtractInteger, int(5), int(big)), integer(5 - big))
    assertEquals(in(MultiplyInteger, int(big), int(-big)), integer(-(big * big)))
  }

  // `div` and `mod` round towards negative infinity, `quot` and `rem` towards zero
  // (`Builtins.hs:1144-1203`, Haskell's `Integral Integer`).
  test("the four divisions on every combination of signs") {
    val cases = Vector(
      (7, 2, 3, 1, 3, 1),
      (-7, 2, -4, 1, -3, -1),
      (7, -2, -4, -1, -3, 1),
      (-7, -2, 3, -1, 3, -1),
      (6, 3, 2, 0, 2, 0),
      (-6, 3, -2, 0, -2, 0)
    )
    for (x, y, d, m, q, r) <- cases do
      assertEquals(in(DivideInteger, int(x), int(y)), integer(d), s"div $x $y")
      assertEquals(in(ModInteger, int(x), int(y)), integer(m), s"mod $x $y")
      assertEquals(in(QuotientInteger, int(x), int(y)), integer(q), s"quot $x $y")
      assertEquals(in(RemainderInteger, int(x), int(y)), integer(r), s"rem $x $y")
  }

  private val integers: Gen[BigInt] =
    Gen.oneOf(
      Gen.choose(-1000L, 1000L).map(BigInt(_)),
      Gen.long.map(BigInt(_)),
      Gen.long.map(BigInt(_) * Long.MaxValue)
    )

  property("div and mod, quot and rem are the two Euclidean pairs of Haskell") {
    Prop.forAll(integers, integers.suchThat(_ != 0)) { (x, y) =>
      val results = for
        d <- in(DivideInteger, int(x), int(y))
        m <- in(ModInteger, int(x), int(y))
        q <- in(QuotientInteger, int(x), int(y))
        r <- in(RemainderInteger, int(x), int(y))
      yield (d, m, q, r)
      results match
        case Right(
              (Constant.Integer(d), Constant.Integer(m), Constant.Integer(q), Constant.Integer(r))
            ) =>
          assertEquals(d * y + m, x, "div and mod")
          assert(m.signum == 0 || m.signum == y.signum, s"mod $x $y = $m has the sign of $y")
          assert(m.abs < y.abs, "mod is smaller than the divisor")
          assertEquals(q * y + r, x, "quot and rem")
          assert(r.signum == 0 || r.signum == x.signum, s"rem $x $y = $r has the sign of $x")
        case other => fail(s"$other")
    }
  }

  // `nonZeroSecondArg` (`Builtins.hs:248-266`)
  test("a division by zero fails with the reference's log line, in every variant") {
    for
      v <- variants
      f <- Vector(DivideInteger, QuotientInteger, RemainderInteger, ModInteger)
    do
      run(v, f, int(1), int(0)) match
        case Outcome.Failure(BuiltinFailure.Evaluation(EvaluationFailure.DivisionByZero), logs) =>
          assertEquals(logs, Vector("Cannot divide by zero"), s"$f in $v")
        case other => fail(s"$f in $v: $other")
  }

  // `Builtins.hs:1204-1240`
  test("the integer comparisons") {
    assertEquals(in(EqualsInteger, int(-3), int(-3)), truth(true))
    assertEquals(in(LessThanInteger, int(-3), int(-3)), truth(false))
    assertEquals(in(LessThanEqualsInteger, int(-3), int(-3)), truth(true))
    assertEquals(in(LessThanInteger, int(-4), int(-3)), truth(true))
    // `equalsInteger` reads plain `Integer`s in every variant, beyond the `CInteger` bound
    assert(
      in(EqualsInteger, int(BigInt(2).pow(262144)), int(0)).isRight,
      "equalsInteger is unbounded"
    )
  }

  // `Builtins.hs:1242-1308`; the cases of `builtin/semantics/consByteString` in the corpus.
  test("consByteString keeps the low byte in A, B and D and reads a Word8 in C and E") {
    val tail = "686543616b654973414c6965"
    for v <- variants do
      assertEquals(value(v, ConsByteString, int(84), bytes(tail)), byteString("54" + tail), s"$v")
    for v <- Vector(A, B, D) do
      assertEquals(value(v, ConsByteString, int(256), bytes("")), byteString("00"), s"$v 256")
      assertEquals(
        value(v, ConsByteString, int(-88), bytes(tail)),
        byteString("a8" + tail),
        s"$v -88"
      )
    for v <- Vector(C, E); n <- Vector(BigInt(256), BigInt(-88)) do
      assertEquals(
        value(v, ConsByteString, int(n), bytes("")).left.map {
          case BuiltinFailure.Unlifting(f) => f
          case other                       => fail(s"$other")
        },
        Left(
          UnliftingFailure(
            ErrorClass.Operational,
            UnliftingCause.NotWithinBounds(0, UnliftingBound.Word8, n)
          )
        ),
        s"$v $n"
      )
  }

  test("appendByteString, lengthOfByteString and the comparisons of unsigned bytes") {
    assertEquals(in(AppendByteString, bytes("0102"), bytes("ff")), byteString("0102ff"))
    assertEquals(in(LengthOfByteString, bytes("0102ff")), integer(3))
    assertEquals(in(EqualsByteString, bytes("01"), bytes("01")), truth(true))
    assertEquals(in(LessThanByteString, bytes(""), bytes("00")), truth(true))
    assertEquals(in(LessThanByteString, bytes("7f"), bytes("80")), truth(true))
    assertEquals(in(LessThanByteString, bytes("ff"), bytes("0100")), truth(false))
    assertEquals(in(LessThanByteString, bytes("01"), bytes("01")), truth(false))
    assertEquals(in(LessThanEqualsByteString, bytes("01"), bytes("01")), truth(true))
    assertEquals(in(LessThanEqualsByteString, bytes("0100"), bytes("01")), truth(false))
  }

  // `BS.take n (BS.drop start xs)` (`Builtins.hs:1309-1323`)
  test("sliceByteString clamps the start and the length to the byte string") {
    val xs = bytes("0102030405")
    assertEquals(in(SliceByteString, int(1), int(2), xs), byteString("0203"))
    assertEquals(in(SliceByteString, int(-3), int(2), xs), byteString("0102"))
    assertEquals(in(SliceByteString, int(3), int(10), xs), byteString("0405"))
    assertEquals(in(SliceByteString, int(5), int(1), xs), byteString(""))
    assertEquals(in(SliceByteString, int(1), int(-1), xs), byteString(""))
    assertEquals(in(SliceByteString, int(Long.MaxValue), int(Long.MaxValue), xs), byteString(""))
    assertEquals(
      in(SliceByteString, int(Long.MinValue), int(Long.MaxValue), xs),
      byteString("0102030405")
    )
  }

  // `BS.indexMaybe` and `fail "Index out of bounds"` (`Builtins.hs:1331-1350`)
  test(
    "indexByteString reads an unsigned byte and fails past either end with the reference's log"
  ) {
    assertEquals(in(IndexByteString, bytes("00ff"), int(1)), integer(255))
    for n <- Vector(BigInt(-1), BigInt(2)) do
      run(E, IndexByteString, bytes("00ff"), int(n)) match
        case Outcome.Failure(BuiltinFailure.Evaluation(f), logs) =>
          assertEquals(f, EvaluationFailure.ByteStringIndexOutOfBounds(n, 2))
          assertEquals(logs, Vector("Index out of bounds"))
        case other => fail(s"$n: $other")
  }

  // `Builtins.hs:1507-1610`
  test("the string builtins, and decodeUtf8 refusing what is not well-formed UTF-8") {
    assertEquals(
      in(AppendString, string("λ"), string("x")),
      Right(Constant.String(Text.fromString("λx")))
    )
    assertEquals(in(EqualsString, string("λ"), string("λ")), truth(true))
    assertEquals(in(EqualsString, string("a"), string("b")), truth(false))
    assertEquals(in(EncodeUtf8, string("λ")), byteString("cebb"))
    assertEquals(in(DecodeUtf8, bytes("cebb")), Right(Constant.String(Text.fromString("λ"))))
    for (hex, what) <- Vector(
        "ff" -> "a stray byte",
        "c080" -> "an overlong NUL",
        "eda080" -> "a surrogate",
        "f4908080" -> "beyond U+10FFFF",
        "ce" -> "a truncated sequence"
      )
    do
      run(A, DecodeUtf8, bytes(hex)) match
        case Outcome.Failure(BuiltinFailure.Evaluation(EvaluationFailure.InvalidUtf8), logs) =>
          assertEquals(logs, Vector("decodeUtf8: invalid input"), what)
        case other => fail(s"$what: $other")
  }

  // `Builtins.hs:1612-1637`: the opaque arguments are passed as they are.
  test("ifThenElse, chooseUnit and trace pass their opaque argument through; trace logs its text") {
    val other = Probe.Lambda("y")
    assertEquals(
      run(A, IfThenElse, bool(true), lambda, other),
      Outcome.Success(lambda, Vector.empty)
    )
    assertEquals(
      run(A, IfThenElse, bool(false), lambda, other),
      Outcome.Success(other, Vector.empty)
    )
    assertEquals(run(A, ChooseUnit, unit, lambda), Outcome.Success(lambda, Vector.empty))
    assertEquals(run(A, Trace, string("hello"), lambda), Outcome.Success(lambda, Vector("hello")))
  }

  // `Builtins.hs:1638-1661`
  test("fstPair and sndPair; any other constant is a structural error with no log") {
    val p = con(Constant.Pair(Constant.Bool(true), Constant.ByteString(hexBytes("012345"))))
    assertEquals(in(FstPair, p), truth(true))
    assertEquals(in(SndPair, p), byteString("012345"))
    assertEquals(
      in(FstPair, int(1)),
      structural(UnliftingCause.ExpectedPair(0, DefaultUni.Integer))
    )
    assertEquals(run(E, SndPair, int(1)).logs, Vector.empty)
  }

  extension (o: Outcome[Probe])
    def logs: Vector[String] = o match
      case Outcome.Success(_, l) => l
      case Outcome.Failure(_, l) => l

  private val ints = con(list(DefaultUni.Integer, Constant.Integer(1), Constant.Integer(2)))
  private val noInts = con(list(DefaultUni.Integer))

  // `Builtins.hs:1663-1730` and `builtin/semantics/mkCons` of the corpus.
  test("the list builtins") {
    val other = Probe.Lambda("y")
    assertEquals(run(A, ChooseList, noInts, lambda, other), Outcome.Success(lambda, Vector.empty))
    assertEquals(run(A, ChooseList, ints, lambda, other), Outcome.Success(other, Vector.empty))
    assertEquals(
      in(MkCons, int(0), ints),
      Right(list(DefaultUni.Integer, Constant.Integer(0), Constant.Integer(1), Constant.Integer(2)))
    )
    assertEquals(in(MkCons, int(0), noInts), Right(list(DefaultUni.Integer, Constant.Integer(0))))
    assertEquals(in(HeadList, ints), integer(1))
    assertEquals(in(TailList, ints), Right(list(DefaultUni.Integer, Constant.Integer(2))))
    assertEquals(in(NullList, ints), truth(false))
    assertEquals(in(NullList, noInts), truth(true))
  }

  test("the list builtins' structural errors, after the cost and with no log") {
    assertEquals(
      in(ChooseList, int(1), lambda, lambda),
      structural(UnliftingCause.ExpectedList(0, DefaultUni.Integer))
    )
    assertEquals(
      in(HeadList, bool(true)),
      structural(UnliftingCause.ExpectedList(0, DefaultUni.Bool))
    )
    assertEquals(
      in(TailList, bool(true)),
      structural(UnliftingCause.ExpectedList(0, DefaultUni.Bool))
    )
    assertEquals(
      in(NullList, bool(true)),
      structural(UnliftingCause.ExpectedList(0, DefaultUni.Bool))
    )
    assertEquals(
      in(MkCons, int(1), int(2)),
      structural(UnliftingCause.ExpectedList(1, DefaultUni.Integer))
    )
    // `mkCons-fail` of the corpus: an integer onto a list of data
    assertEquals(
      in(MkCons, int(3), con(dataList())),
      structural(UnliftingCause.ElementMismatch(DefaultUni.Data, DefaultUni.Integer))
    )
    assertEquals(run(E, MkCons, int(3), con(dataList())).logs, Vector.empty)
  }

  test("headList and tailList of an empty list fail with the reference's log line") {
    for f <- Vector(HeadList, TailList) do
      run(E, f, noInts) match
        case Outcome.Failure(
              BuiltinFailure.Evaluation(EvaluationFailure.EmptyList),
              logs
            ) =>
          assertEquals(logs, Vector("Expected a non-empty list but got an empty one"), s"$f")
        case other => fail(s"$f: $other")
  }

  // `drop` of an `IntegerCostedLiterally` (`Builtins.hs:2328-2376`)
  test("dropList saturates both ways and keeps the element type") {
    val three =
      con(list(DefaultUni.Integer, Constant.Integer(1), Constant.Integer(2), Constant.Integer(3)))
    val empty = Right(list(DefaultUni.Integer))
    assertEquals(in(DropList, int(2), three), Right(list(DefaultUni.Integer, Constant.Integer(3))))
    assertEquals(in(DropList, int(0), three), result(Outcome.Success(three, Vector.empty)))
    assertEquals(in(DropList, int(-5), three), result(Outcome.Success(three, Vector.empty)))
    assertEquals(
      in(DropList, int(-BigInt(2).pow(70)), three),
      result(Outcome.Success(three, Vector.empty))
    )
    assertEquals(in(DropList, int(3), three), empty)
    assertEquals(in(DropList, int(Long.MaxValue), three), empty)
    assertEquals(in(DropList, int(BigInt(2).pow(70)), three), empty)
    assertEquals(
      in(DropList, int(1), int(1)),
      structural(UnliftingCause.ExpectedList(1, DefaultUni.Integer))
    )
  }

  private val d = PlutusData.Constr(3, Vector(PlutusData.I(1), PlutusData.B(hexBytes("ab"))))

  // `Builtins.hs:1732-1884`
  test("the Data builtins") {
    val branches = (1 to 5).map(i => Probe.Lambda(s"$i"))
    val shapes = Vector(
      d,
      PlutusData.Map(Vector.empty),
      PlutusData.List(Vector.empty),
      PlutusData.I(0),
      PlutusData.B(Bytes.empty)
    )
    for (shape, branch) <- shapes.zip(branches) do
      assertEquals(
        run(A, ChooseData, (data(shape) +: branches)*),
        Outcome.Success(branch, Vector.empty),
        s"$shape"
      )
    assertEquals(
      in(ConstrData, int(3), con(dataList(PlutusData.I(1), PlutusData.B(hexBytes("ab"))))),
      Right(Constant.Data(d))
    )
    val entries = Vector(PlutusData.I(1) -> PlutusData.I(2), PlutusData.I(1) -> PlutusData.I(3))
    val pairs = list(
      DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data),
      entries.map((k, v) => Constant.Pair(Constant.Data(k), Constant.Data(v)))*
    )
    assertEquals(in(MapData, con(pairs)), Right(Constant.Data(PlutusData.Map(entries))))
    assertEquals(in(UnMapData, data(PlutusData.Map(entries))), Right(pairs))
    assertEquals(in(ListData, con(dataList(d))), Right(Constant.Data(PlutusData.List(Vector(d)))))
    assertEquals(in(UnListData, data(PlutusData.List(Vector(d)))), Right(dataList(d)))
    assertEquals(in(IData, int(-7)), Right(Constant.Data(PlutusData.I(-7))))
    assertEquals(in(UnIData, data(PlutusData.I(-7))), integer(-7))
    assertEquals(in(BData, bytes("ab")), Right(Constant.Data(PlutusData.B(hexBytes("ab")))))
    assertEquals(in(UnBData, data(PlutusData.B(hexBytes("ab")))), byteString("ab"))
    assertEquals(
      in(UnConstrData, data(d)),
      Right(
        Constant.Pair(Constant.Integer(3), dataList(PlutusData.I(1), PlutusData.B(hexBytes("ab"))))
      )
    )
    assertEquals(
      in(MkPairData, data(d), data(PlutusData.I(0))),
      Right(Constant.Pair(Constant.Data(d), Constant.Data(PlutusData.I(0))))
    )
    assertEquals(in(MkNilData, unit), Right(list(DefaultUni.Data)))
    assertEquals(
      in(MkNilPairData, unit),
      Right(list(DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data)))
    )
  }

  test(
    "a deconstructing Data builtin given another constructor fails with the reference's log line"
  ) {
    val cases = Vector(
      (UnConstrData, DataShape.Constr, "Constr"),
      (UnMapData, DataShape.Map, "Map"),
      (UnListData, DataShape.List, "List"),
      (UnIData, DataShape.I, "I"),
      (UnBData, DataShape.B, "B")
    )
    for (f, shape, name) <- cases do
      val other = if shape == DataShape.I then PlutusData.B(Bytes.empty) else PlutusData.I(0)
      run(E, f, data(other)) match
        case Outcome.Failure(
              BuiltinFailure.Evaluation(EvaluationFailure.UnexpectedData(s)),
              logs
            ) =>
          assertEquals(s, shape, s"$f")
          assertEquals(
            logs,
            Vector(s"Expected the $name constructor but got a different one"),
            s"$f"
          )
        case other => fail(s"$f: $other")
  }

  // Derived `Eq` (`PlutusCore/Data.hs:41-48`): entries in order, repeats kept.
  test("equalsData compares map entries in order") {
    val ab =
      PlutusData.Map(Vector(PlutusData.I(1) -> PlutusData.I(1), PlutusData.I(2) -> PlutusData.I(2)))
    val ba =
      PlutusData.Map(Vector(PlutusData.I(2) -> PlutusData.I(2), PlutusData.I(1) -> PlutusData.I(1)))
    assertEquals(in(EqualsData, data(ab), data(ab)), truth(true))
    assertEquals(in(EqualsData, data(ab), data(ba)), truth(false))
    assertEquals(
      in(EqualsData, data(PlutusData.Map(Vector.empty)), data(PlutusData.List(Vector.empty))),
      truth(false)
    )
    assertEquals(
      in(
        EqualsData,
        data(PlutusData.Constr(0, Vector.empty)),
        data(PlutusData.Constr(1, Vector.empty))
      ),
      truth(false)
    )
  }

  test("a denotation given arguments the unlifting did not check reports what is wrong with them") {
    val denote = CoreDenotations(E)
    def call(f: DefaultFun, args: Argument[Probe]*) =
      denote.get(f).map(_(args.toVector))
    assertEquals(
      call(AddInteger, Argument.Con(Constant.Integer(1)), Argument.Opaque(lambda)),
      Some(
        Outcome.Failure(
          BuiltinFailure.Unlifting(
            UnliftingFailure(ErrorClass.Structural, UnliftingCause.NotAConstant(1))
          ),
          Vector.empty
        )
      )
    )
    assertEquals(
      call(AddInteger, Argument.Con(Constant.Integer(1))),
      Some(
        Outcome.Failure(
          BuiltinFailure.Unlifting(
            UnliftingFailure(ErrorClass.Structural, UnliftingCause.Arity(2, 1))
          ),
          Vector.empty
        )
      )
    )
    // the variant's bound is read again: E's `consByteString` takes a `Word8`
    assertEquals(
      call(
        ConsByteString,
        Argument.Con(Constant.Integer(256)),
        Argument.Con(Constant.ByteString(Bytes.empty))
      ),
      Some(
        Outcome.Failure(
          BuiltinFailure.Unlifting(
            UnliftingFailure(
              ErrorClass.Operational,
              UnliftingCause.NotWithinBounds(0, UnliftingBound.Word8, 256)
            )
          ),
          Vector("256 is not within the bounds of Word8")
        )
      )
    )
    // a constant the machine passed as an opaque value is read as the constant it is
    assertEquals(
      call(AddInteger, Argument.Opaque(int(1)), Argument.Opaque(int(2))),
      Some(Outcome.Success(int(3), Vector.empty))
    )
  }

  // `fixtures/reference/plutus-text/observations.jsonl`: `Data.Text`'s `pack` and `encodeUtf8`,
  // and `decodeUtf8'`, observed in the pinned native libraries.
  test("encodeUtf8 and decodeUtf8 agree with the reference's text observations") {
    val rows = Files
      .readAllLines(Path.of("fixtures/reference/plutus-text/observations.jsonl"))
      .asScala
      .toVector
      .map(line => parser.parse(line).fold(e => fail(s"$e"), _.hcursor))
    def ints(c: HCursor, field: String) =
      c.get[Option[Vector[Int]]](field).fold(e => fail(s"$e"), identity)
    def utf8(c: HCursor) =
      Bytes.wrap(ints(c, "utf8").getOrElse(fail("utf8")).map(_.toByte).toArray)
    val packs = rows.filter(_.get[String]("kind").contains("pack"))
    val decodes = rows.filter(_.get[String]("kind").contains("decode"))
    assertEquals((packs.size, decodes.size), (12, 5), "rows")
    for row <- packs do
      val id = row.get[String]("id").getOrElse("?")
      val text = Text
        .fromCodePoints(ints(row, "inputCodePoints").getOrElse(fail(id)))
        .fold(e => fail(s"$e"), identity)
      assertEquals(
        in(EncodeUtf8, con(Constant.String(text))),
        Right(Constant.ByteString(utf8(row))),
        id
      )
      val decoded = in(DecodeUtf8, bytes(utf8(row)))
      val expected = Text
        .fromCodePoints(ints(row, "codePoints").getOrElse(fail(id)))
        .fold(e => fail(s"$e"), identity)
      assertEquals(decoded, Right(Constant.String(expected)), s"$id decoded")
    for row <- decodes do
      val id = row.get[String]("id").getOrElse("?")
      val decoded = in(DecodeUtf8, bytes(utf8(row)))
      ints(row, "codePoints") match
        case Some(points) =>
          val expected = Text.fromCodePoints(points).fold(e => fail(s"$e"), identity)
          assertEquals(decoded, Right(Constant.String(expected)), id)
        case None =>
          assertEquals(decoded, Left(BuiltinFailure.Evaluation(EvaluationFailure.InvalidUtf8)), id)
  }
