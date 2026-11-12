// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni, PlutusData, SemanticsVariant, Text}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  BuiltinFailure,
  DataShape,
  Denotation,
  EvaluationFailure,
  MachineValue,
  Outcome,
  UnliftingCause
}

import Reading.{failed, list, listOf, pair, structural}

/** The denotations of the core builtins of plutus 1.70.0.0 (`507cab8c`), the meanings of
  * `toBuiltinMeaning` in `PlutusCore/Default/Builtins.hs` (cited by line): integers, byte strings,
  * strings, `Bool`, unit, `trace`, pairs, lists and `Data`. The cryptographic, bitwise, conversion,
  * `Value` and array builtins are not here.
  *
  * Where the variants of a builtin differ, they differ only in the Haskell type an argument is read
  * as (`CInteger`, `Word8`, `Word64`, `CByteString`), so the variants share one meaning and differ
  * in the bounds their arguments are read within ([[CoreUnlifting.bounds]]), which a denotation
  * checks again ([[Reading]]). Each failure carries the reference's log line
  * ([[EvaluationFailure.message]]); the shape checks of the polymorphic builtins (a list, a pair,
  * the element type of `mkCons`) are structural errors raised after the cost is spent, with no log
  * line, as the reference raises them in the denotation (`Builtins.hs:1069-1074`).
  */
object CoreDenotations:

  /** The core denotations of `variant`, refused for the variants plutus 1.70.0.0 does not have. */
  def of(variant: SemanticsVariant): Either[UnsupportedVariant, Map[DefaultFun, Denotation]] =
    CoreVariant.of(variant).map(apply)

  def apply(variant: CoreVariant): Map[DefaultFun, Denotation] = tables(variant.ordinal)

  /** The builtins whose denotations are here. */
  def functions: Set[DefaultFun] = bodies.keySet

  /** A denotation over the arguments as [[Reading]] reads them. */
  private type Body = [V] => Reading[V] => Either[BuiltinFailure, Done[V]]

  private def denotation(variant: CoreVariant, function: DefaultFun, body: Body): Denotation =
    val bounds = CoreUnlifting.bounds(variant, function)
    new Denotation:
      def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): Outcome[V] =
        Reading.outcome(body[V](Reading(function.arity, bounds, arguments)))

  private def constant1[A: Reader](f: A => Either[BuiltinFailure, Constant]): Body =
    [V] => (r: Reading[V]) => r[A](0).flatMap(f).map(r.constant)

  private def constant2[A: Reader, B: Reader](f: (A, B) => Either[BuiltinFailure, Constant]): Body =
    [V] =>
      (r: Reading[V]) =>
        for
          a <- r[A](0)
          b <- r[B](1)
          c <- f(a, b)
        yield r.constant(c)

  private def constant3[A: Reader, B: Reader, C: Reader](
      f: (A, B, C) => Either[BuiltinFailure, Constant]
  ): Body =
    [V] =>
      (r: Reading[V]) =>
        for
          a <- r[A](0)
          b <- r[B](1)
          c <- r[C](2)
          d <- f(a, b, c)
        yield r.constant(d)

  private def integer(n: BigInt): Either[BuiltinFailure, Constant] = Right(Constant.Integer(n))
  private def bool(b: Boolean): Either[BuiltinFailure, Constant] = Right(Constant.Bool(b))
  private def bytes(b: Bytes): Either[BuiltinFailure, Constant] = Right(Constant.ByteString(b))
  private def data(d: PlutusData): Either[BuiltinFailure, Constant] = Right(Constant.Data(d))

  private def dataList(ds: Vector[PlutusData]): Either[BuiltinFailure, Constant] =
    listOf(DefaultUni.Data, ds.map(Constant.Data(_)))

  private val DataPair: DefaultUni = DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data)

  /** `nonZeroSecondArg` (`Builtins.hs:248-266`): "Cannot divide by zero" when `y` is 0. */
  private def division(f: (BigInt, BigInt) => BigInt): Body =
    constant2[BigInt, BigInt]((x, y) =>
      if y.signum == 0 then Left(failed(EvaluationFailure.DivisionByZero)) else integer(f(x, y))
    )

  /** Haskell's `div`, rounding towards negative infinity. */
  private def floorDivision(x: BigInt, y: BigInt): BigInt =
    val (q, r) = x /% y
    if r.signum != 0 && r.signum != y.signum then q - 1 else q

  /** Haskell's `mod`, of the sign of `y`. */
  private def floorModulus(x: BigInt, y: BigInt): BigInt =
    val r = x % y
    if r.signum != 0 && r.signum != y.signum then r + y else r

  /** `BS.take n (BS.drop start xs)`, both clamped to the byte string (`Builtins.hs:1309-1323`). */
  private def slice(from: BigInt, length: BigInt, xs: Bytes): Bytes =
    val size = BigInt(xs.size)
    val start = from.max(0).min(size)
    val end = start + length.max(0).min(size - start)
    xs.slice(start.toInt, end.toInt)

  /** `drop` of an `Integer` (`Builtins.hs:2328-2376`): the whole list for `n <= 0`, nothing once
    * `n` reaches its length (a list here is shorter than `maxBound :: Int`, so the reference's
    * unreachable clause stays unreachable).
    */
  private def dropList(n: BigInt, xs: Constant.List): Constant.List =
    val size = xs.items.size
    if n.signum <= 0 then xs else if n >= size then xs.drop(size) else xs.drop(n.toInt)

  private val bodies: Map[DefaultFun, Body] =
    import DefaultFun.*
    Map(
      // `Builtins.hs:1099-1240`
      AddInteger -> constant2[BigInt, BigInt]((x, y) => integer(x + y)),
      SubtractInteger -> constant2[BigInt, BigInt]((x, y) => integer(x - y)),
      MultiplyInteger -> constant2[BigInt, BigInt]((x, y) => integer(x * y)),
      DivideInteger -> division(floorDivision),
      QuotientInteger -> division(_ / _),
      RemainderInteger -> division(_ % _),
      ModInteger -> division(floorModulus),
      EqualsInteger -> constant2[BigInt, BigInt]((x, y) => bool(x == y)),
      LessThanInteger -> constant2[BigInt, BigInt]((x, y) => bool(x < y)),
      LessThanEqualsInteger -> constant2[BigInt, BigInt]((x, y) => bool(x <= y)),
      // `Builtins.hs:1242-1396`
      AppendByteString -> constant2[Bytes, Bytes]((x, y) => bytes(x ++ y)),
      // `fromIntegral` to a `Word8` keeps the low 8 bits (variants A, B and D); C and E read a
      // `Word8`, so their argument is that byte already.
      ConsByteString -> constant2[BigInt, Bytes]((n, xs) =>
        bytes(Bytes.wrap(Array((n & BigInt(0xff)).toByte)) ++ xs)
      ),
      SliceByteString -> constant3[BigInt, BigInt, Bytes]((from, n, xs) =>
        bytes(slice(from, n, xs))
      ),
      LengthOfByteString -> constant1[Bytes](xs => integer(xs.size)),
      IndexByteString -> constant2[Bytes, BigInt]((xs, n) =>
        Option
          .when(n.signum >= 0 && n < xs.size)(n.toInt)
          .flatMap(xs.lift)
          .toRight(failed(EvaluationFailure.ByteStringIndexOutOfBounds(n, xs.size)))
          .map(b => Constant.Integer(b & 0xff))
      ),
      EqualsByteString -> constant2[Bytes, Bytes]((x, y) => bool(x == y)),
      LessThanByteString -> constant2[Bytes, Bytes]((x, y) => bool(x.compareTo(y) < 0)),
      LessThanEqualsByteString -> constant2[Bytes, Bytes]((x, y) => bool(x.compareTo(y) <= 0)),
      // `Builtins.hs:1507-1611`
      AppendString -> constant2[Text, Text]((x, y) =>
        Right(Constant.String(Text.fromString(x.string + y.string)))
      ),
      EqualsString -> constant2[Text, Text]((x, y) => bool(x == y)),
      EncodeUtf8 -> constant1[Text](t => bytes(t.utf8)),
      DecodeUtf8 -> constant1[Bytes](b =>
        Text
          .fromUtf8(b)
          .left
          .map(_ => failed(EvaluationFailure.InvalidUtf8))
          .map(Constant.String(_))
      ),
      // `Builtins.hs:1612-1637`
      IfThenElse -> ([V] =>
        (r: Reading[V]) =>
          for
            c <- r[Boolean](0)
            x <- r.opaque(1)
            y <- r.opaque(2)
          yield r.value(if c then x else y)),
      ChooseUnit -> ([V] =>
        (r: Reading[V]) =>
          for
            _ <- r[Unit](0)
            x <- r.opaque(1)
          yield r.value(x)),
      Trace -> ([V] =>
        (r: Reading[V]) =>
          for
            t <- r[Text](0)
            x <- r.opaque(1)
          yield Done(x, Vector(t.string))),
      // `Builtins.hs:1638-1661`
      FstPair -> ([V] =>
        (r: Reading[V]) => r.someConstant(0).flatMap(pair(_, 0)).map(p => r.constant(p.first))),
      SndPair -> ([V] =>
        (r: Reading[V]) => r.someConstant(0).flatMap(pair(_, 0)).map(p => r.constant(p.second))),
      // `Builtins.hs:1663-1730`
      ChooseList -> ([V] =>
        (r: Reading[V]) =>
          for
            c <- r.someConstant(0)
            ifEmpty <- r.opaque(1)
            otherwise <- r.opaque(2)
            xs <- list(c, 0)
          yield r.value(if xs.items.isEmpty then ifEmpty else otherwise)),
      MkCons -> ([V] =>
        (r: Reading[V]) =>
          for
            x <- r.someConstant(0)
            c <- r.someConstant(1)
            xs <- list(c, 1)
            out <- xs
              .cons(x)
              .left
              .map(m => structural(UnliftingCause.ElementMismatch(m.expected, m.found)))
          yield r.constant(out)),
      HeadList -> ([V] =>
        (r: Reading[V]) =>
          for
            c <- r.someConstant(0)
            xs <- list(c, 0)
            x <- xs.items.headOption.toRight(failed(EvaluationFailure.EmptyList))
          yield r.constant(x)),
      TailList -> ([V] =>
        (r: Reading[V]) =>
          for
            c <- r.someConstant(0)
            xs <- list(c, 0)
            rest <- xs.tail.toRight(failed(EvaluationFailure.EmptyList))
          yield r.constant(rest)),
      NullList -> ([V] =>
        (r: Reading[V]) =>
          r.someConstant(0)
            .flatMap(list(_, 0))
            .map(xs => r.constant(Constant.Bool(xs.items.isEmpty)))),
      // `Builtins.hs:1732-1744`
      ChooseData -> ([V] =>
        (r: Reading[V]) =>
          r[PlutusData](0).flatMap { d =>
            val branch = d match
              case PlutusData.Constr(_, _) => 1
              case PlutusData.Map(_)       => 2
              case PlutusData.List(_)      => 3
              case PlutusData.I(_)         => 4
              case PlutusData.B(_)         => 5
            r.opaque(branch).map(r.value)
        }),
      // `Builtins.hs:1745-1787`; `constrData` reads a `Word64` in D and E.
      ConstrData -> constant2[BigInt, Vector[PlutusData]]((i, ds) =>
        data(PlutusData.Constr(i, ds))
      ),
      MapData -> constant1[Vector[(PlutusData, PlutusData)]](es => data(PlutusData.Map(es))),
      ListData -> constant1[Vector[PlutusData]](ds => data(PlutusData.List(ds))),
      IData -> constant1[BigInt](n => data(PlutusData.I(n))),
      BData -> constant1[Bytes](b => data(PlutusData.B(b))),
      // `Builtins.hs:1788-1841`
      UnConstrData -> constant1[PlutusData] {
        case PlutusData.Constr(i, fields) =>
          dataList(fields).map(Constant.Pair(Constant.Integer(i), _))
        case _ => Left(failed(EvaluationFailure.UnexpectedData(DataShape.Constr)))
      },
      UnMapData -> constant1[PlutusData] {
        case PlutusData.Map(entries) =>
          listOf(DataPair, entries.map((k, v) => Constant.Pair(Constant.Data(k), Constant.Data(v))))
        case _ => Left(failed(EvaluationFailure.UnexpectedData(DataShape.Map)))
      },
      UnListData -> constant1[PlutusData] {
        case PlutusData.List(items) => dataList(items)
        case _ => Left(failed(EvaluationFailure.UnexpectedData(DataShape.List)))
      },
      UnIData -> constant1[PlutusData] {
        case PlutusData.I(n) => integer(n)
        case _               => Left(failed(EvaluationFailure.UnexpectedData(DataShape.I)))
      },
      UnBData -> constant1[PlutusData] {
        case PlutusData.B(b) => bytes(b)
        case _               => Left(failed(EvaluationFailure.UnexpectedData(DataShape.B)))
      },
      // Haskell's derived `Eq`: constructors, entries and items compared in order
      // (`Builtins.hs:1843-1849`, `PlutusCore/Data.hs:42-49`); `PlutusData`'s equality is that
      // structural comparison, walked on the heap.
      EqualsData -> constant2[PlutusData, PlutusData]((x, y) => bool(x == y)),
      SerialiseData -> constant1[PlutusData](d => bytes(DataSerialisation.serialise(d))),
      // `Builtins.hs:1858-1884`
      MkPairData -> constant2[PlutusData, PlutusData]((x, y) =>
        Right(Constant.Pair(Constant.Data(x), Constant.Data(y)))
      ),
      MkNilData -> constant1[Unit](_ => Right(Constant.List.empty(DefaultUni.Data))),
      MkNilPairData -> constant1[Unit](_ => Right(Constant.List.empty(DataPair))),
      // `Builtins.hs:2328-2376`
      DropList -> ([V] =>
        (r: Reading[V]) =>
          for
            n <- r[BigInt](0)
            c <- r.someConstant(1)
            xs <- list(c, 1)
          yield r.constant(dropList(n, xs)))
    )

  private val tables: Vector[Map[DefaultFun, Denotation]] =
    CoreVariant.values.toVector.map(v => bodies.map((f, body) => f -> denotation(v, f, body)))
