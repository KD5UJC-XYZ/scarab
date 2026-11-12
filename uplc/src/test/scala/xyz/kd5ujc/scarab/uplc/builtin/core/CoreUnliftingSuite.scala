// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultFun,
  DefaultUni,
  PlutusData,
  SemanticsVariant,
  Signature
}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  ErrorClass,
  UnliftingBound,
  UnliftingCause,
  UnliftingFailure
}

import CoreTesting.*

/** The unlifting of every builtin under variants A to E against plutus 1.70.0.0 (`507cab8c`):
  * `toMonoF` (`PlutusCore/Builtin/Meaning.hs:304-330`) and the `ReadKnownIn` instances
  * (`Builtin/KnownType.hs:310-323,488-498`, `Default/Universe.hs:585-926`).
  */
final class CoreUnliftingSuite extends munit.FunSuite:
  private val variants = CoreVariant.values.toVector

  /** Arguments every builtin accepts: a constant of each monomorphic parameter's type within its
    * bounds, a constant of the scheme's shape for a polymorphic one, a lambda for an opaque one.
    */
  private def valid(function: DefaultFun): Vector[Probe] =
    function.signature.parameters.map {
      case Signature.Parameter.Opaque(_)   => lambda
      case Signature.Parameter.Constant(t) => con(sample(instantiate(t)))
    }

  /** The scheme's type with every variable taken as `integer`. */
  private def instantiate(t: Signature.Type): DefaultUni = t match
    case Signature.Type.Variable(_) => DefaultUni.Integer
    case Signature.Type.List(e)     => DefaultUni.List(instantiate(e))
    case Signature.Type.Pair(a, b)  => DefaultUni.Pair(instantiate(a), instantiate(b))
    case Signature.Type.Array(e)    => DefaultUni.Array(instantiate(e))
    case ground                     => ground.ground.getOrElse(sys.error(s"$ground"))

  test("every builtin unlifts its arguments in every variant of 1.70.0.0") {
    for
      v <- variants
      f <- DefaultFun.values.toVector
    do
      val args = valid(f)
      CoreUnlifting(v)(f, args) match
        case Right(unlifted) =>
          val kinds = unlifted.map {
            case Argument.Con(_)    => "constant"
            case Argument.Opaque(_) => "opaque"
          }
          val expected = f.signature.parameters.map {
            case Signature.Parameter.Opaque(_)   => "opaque"
            case Signature.Parameter.Constant(_) => "constant"
          }
          assertEquals(kinds, expected, s"$f in $v")
        case Left(failure) => fail(s"$f in $v refused valid arguments: $failure")
  }

  test("variants F and G are 1.71.0.0's and are refused, not taken as D and E") {
    assertEquals(
      CoreUnlifting.of(SemanticsVariant.F).map(_ => ()),
      Left(UnsupportedVariant(SemanticsVariant.F))
    )
    assertEquals(
      CoreUnlifting.of(SemanticsVariant.G).map(_ => ()),
      Left(UnsupportedVariant(SemanticsVariant.G))
    )
    assertEquals(
      CoreDenotations.of(SemanticsVariant.G).map(_ => ()),
      Left(UnsupportedVariant(SemanticsVariant.G))
    )
    for v <- Vector(
        SemanticsVariant.A,
        SemanticsVariant.B,
        SemanticsVariant.C,
        SemanticsVariant.D,
        SemanticsVariant.E
      )
    do assert(CoreUnlifting.of(v).isRight, s"$v")
  }

  test("each bound sits on a monomorphic parameter of the type it bounds") {
    for
      v <- variants
      f <- DefaultFun.values.toVector
      (position, bound) <- CoreUnlifting.bounds(v, f)
    do
      val parameter = f.signature.parameters.lift(position)
      val expected = bound match
        case UnliftingBound.CByteString => Signature.Type.ByteString
        case _                          => Signature.Type.Integer
      assertEquals(
        parameter,
        Some(Signature.Parameter.Constant(expected)),
        s"$f argument $position in $v"
      )
  }

  // The `ensurable` branches (`Builtins.hs:2788-2793`) that read a `CInteger` or `CByteString`:
  // the 35 builtins of `fixtures/reference/plutus-unlifting-bounds/README.md`.
  test("variants D and E bound 35 builtins with CInteger or CByteString, A to C none") {
    def onChain(v: CoreVariant) = DefaultFun.values.toVector.filter(f =>
      CoreUnlifting
        .bounds(v, f)
        .values
        .exists(b => b == UnliftingBound.CInteger || b == UnliftingBound.CByteString)
    )
    assertEquals(onChain(CoreVariant.D).size, 35, "D")
    assertEquals(onChain(CoreVariant.E), onChain(CoreVariant.D), "E")
    for v <- Vector(CoreVariant.A, CoreVariant.B, CoreVariant.C) do
      assertEquals(onChain(v), Vector.empty, s"$v")
  }

  // The `Int`, `Word8` and `Word64` arguments, by variant (`Builtins.hs:1258-1350,1745-1759,
  // 2184-2266,2395-2409`).
  test("the fixed-width bounds of every variant") {
    import DefaultFun.*
    import UnliftingBound.{Int as I, Word64 as W64, Word8 as W8}
    def fixed(v: CoreVariant): Map[DefaultFun, Map[Int, UnliftingBound]] =
      DefaultFun.values.toVector
        .map(f =>
          f -> CoreUnlifting
            .bounds(v, f)
            .filter((_, b) => b != UnliftingBound.CInteger && b != UnliftingBound.CByteString)
        )
        .filter(_._2.nonEmpty)
        .toMap
    val always = Map(
      SliceByteString -> Map(0 -> I, 1 -> I),
      IndexByteString -> Map(1 -> I),
      ReadBit -> Map(1 -> I),
      ReplicateByte -> Map(1 -> W8),
      IndexArray -> Map(1 -> I)
    )
    assertEquals(fixed(CoreVariant.A), always, "A")
    assertEquals(fixed(CoreVariant.B), always, "B")
    assertEquals(fixed(CoreVariant.C), always + (ConsByteString -> Map(0 -> W8)), "C")
    val onChain = always + (ConstrData -> Map(0 -> W64)) + (ShiftByteString -> Map(1 -> I)) +
      (RotateByteString -> Map(1 -> I))
    assertEquals(fixed(CoreVariant.D), onChain, "D")
    assertEquals(fixed(CoreVariant.E), onChain + (ConsByteString -> Map(0 -> W8)), "E")
  }

  test(
    "a value that is not a constant fails structurally, at a monomorphic or polymorphic parameter"
  ) {
    assertEquals(
      CoreUnlifting(CoreVariant.E)(DefaultFun.AddInteger, Vector(int(1), lambda)),
      Left(UnliftingFailure(ErrorClass.Structural, UnliftingCause.NotAConstant(1)))
    )
    assertEquals(
      CoreUnlifting(CoreVariant.A)(DefaultFun.HeadList, Vector(lambda)),
      Left(UnliftingFailure(ErrorClass.Structural, UnliftingCause.NotAConstant(0)))
    )
  }

  test("a constant of another type fails structurally, the whole type compared") {
    assertEquals(
      CoreUnlifting(CoreVariant.B)(DefaultFun.AddInteger, Vector(bytes("00"), int(1))),
      Left(
        UnliftingFailure(
          ErrorClass.Structural,
          UnliftingCause.WrongType(0, Signature.Type.Integer, DefaultUni.ByteString)
        )
      )
    )
    val integers = con(list(DefaultUni.Integer, Constant.Integer(1)))
    assertEquals(
      CoreUnlifting(CoreVariant.E)(DefaultFun.ListData, Vector(integers)),
      Left(
        UnliftingFailure(
          ErrorClass.Structural,
          UnliftingCause.WrongType(
            0,
            Signature.Type.List(Signature.Type.Data),
            DefaultUni.List(DefaultUni.Integer)
          )
        )
      )
    )
  }

  // `SomeConstant` reads any constant (`KnownType.hs:488-490`); the shape is the denotation's.
  test("a polymorphic parameter takes a constant of any shape; an opaque one any value") {
    assert(CoreUnlifting(CoreVariant.E)(DefaultFun.FstPair, Vector(int(1))).isRight, "fstPair")
    assert(
      CoreUnlifting(CoreVariant.E)(DefaultFun.MkCons, Vector(int(1), bytes("00"))).isRight,
      "mkCons"
    )
    assert(
      CoreUnlifting(CoreVariant.E)(
        DefaultFun.DropList,
        Vector(int(BigInt(2).pow(70)), bool(true))
      ).isRight,
      "dropList"
    )
    assert(
      CoreUnlifting(CoreVariant.A)(
        DefaultFun.IfThenElse,
        Vector(bool(true), lambda, int(1))
      ).isRight,
      "ifThenElse"
    )
  }

  test("the first failing argument wins, whatever the later ones are") {
    // a shape that would fail the denotation does not come before a later argument's bound
    assertEquals(
      CoreUnlifting(CoreVariant.E)(DefaultFun.IndexArray, Vector(int(1), int(BigInt(2).pow(70)))),
      Left(
        UnliftingFailure(
          ErrorClass.Operational,
          UnliftingCause.NotWithinBounds(1, UnliftingBound.Int, BigInt(2).pow(70))
        )
      )
    )
    assertEquals(
      CoreUnlifting(CoreVariant.E)(
        DefaultFun.AddInteger,
        Vector(int(BigInt(2).pow(262143)), lambda)
      ),
      Left(
        UnliftingFailure(
          ErrorClass.Operational,
          UnliftingCause.OutOfBounds(0, UnliftingBound.CInteger)
        )
      )
    )
    assertEquals(
      CoreUnlifting(CoreVariant.E)(DefaultFun.AddInteger, Vector(lambda, bytes("00"))),
      Left(UnliftingFailure(ErrorClass.Structural, UnliftingCause.NotAConstant(0)))
    )
    // within one argument the type comes before the bound
    assertEquals(
      CoreUnlifting(CoreVariant.E)(DefaultFun.SliceByteString, Vector(int(0), int(0), int(1))),
      Left(
        UnliftingFailure(
          ErrorClass.Structural,
          UnliftingCause.WrongType(2, Signature.Type.ByteString, DefaultUni.Integer)
        )
      )
    )
  }

  test("the wrong number of arguments is refused as a defect of the caller") {
    assertEquals(
      CoreUnlifting(CoreVariant.A)(DefaultFun.AddInteger, Vector(int(1))),
      Left(UnliftingFailure(ErrorClass.Structural, UnliftingCause.Arity(2, 1)))
    )
  }

  // `Default/Universe/Cardano.hs:9-19`: [-2^262143, 2^262143 - 1] and 65,536 bytes.
  test("the CInteger and CByteString bounds are inclusive") {
    val max = BigInt(2).pow(262143) - 1
    val min = -BigInt(2).pow(262143)
    def add(x: BigInt) = CoreUnlifting(CoreVariant.D)(DefaultFun.AddInteger, Vector(int(x), int(0)))
    assert(add(max).isRight, "max")
    assert(add(min).isRight, "min")
    assertEquals(
      add(max + 1).left.map(_.cause),
      Left(UnliftingCause.OutOfBounds(0, UnliftingBound.CInteger))
    )
    assertEquals(
      add(min - 1).left.map(_.cause),
      Left(UnliftingCause.OutOfBounds(0, UnliftingBound.CInteger))
    )
    def length(n: Int) = CoreUnlifting(CoreVariant.D)(
      DefaultFun.Sha2_256,
      Vector(bytes(Bytes.wrap(new Array[Byte](n))))
    )
    assert(length(65536).isRight, "65536 bytes")
    assertEquals(
      length(65537).left.map(_.cause),
      Left(UnliftingCause.OutOfBounds(0, UnliftingBound.CByteString))
    )
  }

  test("the fixed-width bounds are the ranges of Int64, Word8 and Word64") {
    def slice(from: BigInt) =
      CoreUnlifting(CoreVariant.A)(DefaultFun.SliceByteString, Vector(int(from), int(0), bytes("")))
    assert(slice(Long.MaxValue).isRight && slice(Long.MinValue).isRight, "Int64 ends")
    assertEquals(
      slice(BigInt(Long.MaxValue) + 1).left.map(_.errorClass),
      Left(ErrorClass.Operational)
    )
    assertEquals(
      slice(BigInt(Long.MinValue) - 1).left.map(_.errorClass),
      Left(ErrorClass.Operational)
    )
    def replicate(byte: BigInt) =
      CoreUnlifting(CoreVariant.A)(DefaultFun.ReplicateByte, Vector(int(1), int(byte)))
    assert(replicate(0).isRight && replicate(255).isRight, "Word8 ends")
    assert(replicate(-1).isLeft && replicate(256).isLeft, "past Word8")
    def constr(i: BigInt) =
      CoreUnlifting(CoreVariant.E)(DefaultFun.ConstrData, Vector(int(i), con(dataList())))
    assert(constr(0).isRight && constr(BigInt(2).pow(64) - 1).isRight, "Word64 ends")
    assert(constr(-1).isLeft && constr(BigInt(2).pow(64)).isLeft, "past Word64")
    assert(
      CoreUnlifting(CoreVariant.C)(DefaultFun.ConstrData, Vector(int(-1), con(dataList()))).isRight,
      "C reads an Integer"
    )
  }

  // `readKnownAsInteger` (`Universe.hs:585-607`) names the integer and the type (`Int` reads as
  // an `Int64`, line 615-617); the `CInteger` and `CByteString` instances name neither
  // (`Universe.hs:889-926`). The `MonadError BuiltinResult` instance logs an operational message
  // only (`Builtin/Result.hs:198-220`).
  test("an operational unlifting failure logs the reference's message, a structural one nothing") {
    def logOf(v: CoreVariant, f: DefaultFun, args: Probe*) =
      CoreUnlifting(v)(f, args.toVector).left.toOption.flatMap(CoreUnlifting.log)
    assertEquals(
      logOf(CoreVariant.A, DefaultFun.IndexByteString, bytes("00"), int(BigInt(2).pow(63))),
      Some("9223372036854775808 is not within the bounds of Int64")
    )
    assertEquals(
      logOf(CoreVariant.E, DefaultFun.ConsByteString, int(-88), bytes("")),
      Some("-88 is not within the bounds of Word8")
    )
    assertEquals(
      logOf(CoreVariant.E, DefaultFun.ConstrData, int(-1), con(dataList())),
      Some("-1 is not within the bounds of Word64")
    )
    assertEquals(
      logOf(CoreVariant.D, DefaultFun.AddInteger, int(BigInt(2).pow(262143)), int(0)),
      Some("Integer out of bounds")
    )
    assertEquals(
      logOf(
        CoreVariant.D,
        DefaultFun.AppendByteString,
        bytes(Bytes.wrap(new Array[Byte](65537))),
        bytes("")
      ),
      Some("ByteString overflow")
    )
    assertEquals(logOf(CoreVariant.D, DefaultFun.AddInteger, lambda, int(0)), None)
    assertEquals(logOf(CoreVariant.D, DefaultFun.AddInteger, bool(true), int(0)), None)
  }

  test("a data list and a map list are told apart by their whole type") {
    val pairs = con(list(DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data)))
    assert(CoreUnlifting(CoreVariant.A)(DefaultFun.MapData, Vector(pairs)).isRight, "mapData")
    assert(CoreUnlifting(CoreVariant.A)(DefaultFun.ListData, Vector(pairs)).isLeft, "listData")
    assert(
      CoreUnlifting(CoreVariant.A)(
        DefaultFun.MapData,
        Vector(con(dataList(PlutusData.I(1))))
      ).isLeft,
      "mapData of data"
    )
  }
