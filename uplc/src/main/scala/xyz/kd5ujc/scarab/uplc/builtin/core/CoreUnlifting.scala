// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni, SemanticsVariant, Signature}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  ErrorClass,
  HeldUnlifting,
  MachineValue,
  UnliftingBound,
  UnliftingCause,
  UnliftingFailure,
  Unlifting
}

/** The unlifting of every builtin of plutus 1.71.0.0 (`261766ad`) under variants A to G: each
  * argument in order, the first failure winning, as `toMonoF` reads them at full saturation
  * (`PlutusCore/Builtin/Meaning.hs:304-330`). Under A to E it is 1.70.0.0's (`507cab8c`), whose
  * lines the bounds below cite; F and G read the arguments as 1.71.0.0's `ensurableInteger` and
  * `ensurableByteString` choose ([[CoreVariant]]).
  *
  *   - An opaque parameter (`Opaque val rep`, a bare type variable) takes any value
  *     (`Builtin/KnownType.hs:496-498`).
  *   - A parameter whose type has variables (`SomeConstant uni rep`) takes any constant; only a
  *     value that is not a constant fails, and the shape of the constant is the denotation's to
  *     check, after the cost is spent (`KnownType.hs:488-490`).
  *   - A monomorphic parameter takes a constant of its exact type (`readKnownConstant`,
  *     `KnownType.hs:310-323`), and then the variant's bound on it, if any: `Int`, `Word8` and
  *     `Word64` through `readKnownAsInteger` (`Default/Universe.hs:585-607,615-617,689-735`),
  *     `CInteger` and `CByteString` with their on-chain limits (`Universe.hs:889-926`,
  *     `Default/Universe/Cardano.hs:9-19`).
  *
  * A value that is not a constant (`notAConstant`, `Builtin/Result.hs:141-143`; the machine's
  * `asConstant`, `Cek/Internal.hs:711-713`) and a constant of another type (`typeMismatchError`,
  * `KnownType.hs:284-297`) are structural errors; a value outside a bound is an operational one,
  * whose message the reference emits as a log line ([[log]]).
  */
object CoreUnlifting:

  /** The unlifting of `variant`. */
  def of(variant: SemanticsVariant): Unlifting = apply(CoreVariant.of(variant))

  def apply(variant: CoreVariant): Unlifting = tables(variant.ordinal)

  /** The bounds on `function` 's arguments under `variant`, by position: where the Haskell type of
    * its denotation in `toBuiltinMeaning` (`PlutusCore/Default/Builtins.hs`, cited by line at
    * 1.70.0.0, where "D and E" is `ensurable`) refines the argument's universe type. Every other
    * argument is unbounded. At 1.71.0.0 the `CInteger` bounds are those of `ensurableInteger` (D
    * and E) and every other bound of D and E is that of `ensurableByteString` (D to G,
    * `Builtins.hs:2806-2820` at `261766ad`).
    */
  def bounds(variant: CoreVariant, function: DefaultFun): Map[Int, UnliftingBound] =
    import DefaultFun.*
    import UnliftingBound.{CByteString, CInteger, Word64 as W64, Word8 as W8}
    val int = UnliftingBound.Int
    def onChainIntegers(entries: (Int, UnliftingBound)*): Map[Int, UnliftingBound] =
      if variant.ensurableInteger then entries.toMap else Map.empty
    def onChain(entries: (Int, UnliftingBound)*): Map[Int, UnliftingBound] =
      if variant.ensurableByteString then entries.toMap else Map.empty
    val none = Map.empty[Int, UnliftingBound]
    function match
      // `CInteger -> CInteger` in D and E (`Builtins.hs:1099-1203,1211-1240`, plutus 1.70.0.0;
      // `ensurableInteger`, `Builtins.hs:1102-1243` at 1.71.0.0).
      case AddInteger | SubtractInteger | MultiplyInteger | DivideInteger | QuotientInteger |
          RemainderInteger | ModInteger | LessThanInteger | LessThanEqualsInteger =>
        onChainIntegers(0 -> CInteger, 1 -> CInteger)
      // `Integer -> Integer` in every variant (`Builtins.hs:1204-1210`, plutus 1.70.0.0).
      case EqualsInteger => none
      // `CByteString -> CByteString` in D and E (`Builtins.hs:1242-1257,1351-1396`,
      // plutus 1.70.0.0).
      case AppendByteString | EqualsByteString | LessThanByteString | LessThanEqualsByteString =>
        onChain(0 -> CByteString, 1 -> CByteString)
      // `Integer` (A, B), `Word8` (C), `CInteger -> CByteString` (D), `Word8 -> CByteString` (E)
      // (`Builtins.hs:1258-1308`, plutus 1.70.0.0); `Integer -> CByteString` (F) and
      // `Word8 -> CByteString` (G) (`Builtins.hs:1261-1320`, plutus 1.71.0.0).
      case ConsByteString =>
        variant match
          case CoreVariant.A | CoreVariant.B => none
          case CoreVariant.C                 => Map(0 -> W8)
          case CoreVariant.D                 => Map(0 -> CInteger, 1 -> CByteString)
          case CoreVariant.E | CoreVariant.G => Map(0 -> W8, 1 -> CByteString)
          case CoreVariant.F                 => Map(1 -> CByteString)
      // `Int -> Int`, and `CByteString` in D and E (`Builtins.hs:1309-1323`, plutus 1.70.0.0).
      case SliceByteString => Map(0 -> int, 1 -> int) ++ onChain(2 -> CByteString)
      // `BS.ByteString` in every variant (`Builtins.hs:1324-1330`, plutus 1.70.0.0).
      case LengthOfByteString => none
      // `CByteString` in D and E, then `Int` (`Builtins.hs:1331-1350`, plutus 1.70.0.0); `readBit`
      // likewise (`Builtins.hs:2184-2198`).
      case IndexByteString | ReadBit => Map(1 -> int) ++ onChain(0 -> CByteString)
      // `CByteString` in D and E (`Builtins.hs:1397-1441,1588-1611,2059-2092,2167-2183,
      // 2267-2313`, plutus 1.70.0.0).
      case Sha2_256 | Sha3_256 | Blake2b_256 | Keccak_256 | Blake2b_224 | Ripemd_160 | DecodeUtf8 |
          ComplementByteString | CountSetBits | FindFirstSetBit =>
        onChain(0 -> CByteString)
      // The message only, in D and E (`Builtins.hs:1442-1478,1487-1504`, plutus 1.70.0.0).
      case VerifyEd25519Signature | VerifySchnorrSecp256k1Signature => onChain(1 -> CByteString)
      // `BS.ByteString` in every variant (`Builtins.hs:1479-1486`, plutus 1.70.0.0).
      case VerifyEcdsaSecp256k1Signature => none
      // `Text` (A to C) or `TextCostedByByteLength` (D, E), a costing measure, not a bound
      // (`Builtins.hs:1507-1587`, plutus 1.70.0.0).
      case AppendString | EqualsString | EncodeUtf8 => none
      // Polymorphic, `()`, `Bool`, `Text`, `Data` and lists of them (`Builtins.hs:1612-1743,
      // 1760-1884`, plutus 1.70.0.0).
      case IfThenElse | ChooseUnit | Trace | FstPair | SndPair | ChooseList | MkCons | HeadList |
          TailList | NullList | ChooseData | MapData | ListData | IData | BData | UnConstrData |
          UnMapData | UnListData | UnIData | UnBData | EqualsData | SerialiseData | MkPairData |
          MkNilData | MkNilPairData =>
        none
      // `Word64` in D and E (`Builtins.hs:1745-1759`, plutus 1.70.0.0).
      case ConstrData => onChain(0 -> W64)
      // The message (the first argument) in D and E (`Builtins.hs:1933-1950,2007-2024`,
      // plutus 1.70.0.0).
      case Bls12_381_G1_hashToGroup | Bls12_381_G2_hashToGroup => onChain(0 -> CByteString)
      // Group elements, `Integer` scalars and `BS.ByteString` in every variant
      // (`Builtins.hs:1886-1932,1951-2006,2025-2058,2410-2425`, plutus 1.70.0.0).
      case Bls12_381_G1_add | Bls12_381_G1_neg | Bls12_381_G1_scalarMul | Bls12_381_G1_equal |
          Bls12_381_G1_compress | Bls12_381_G1_uncompress | Bls12_381_G2_add | Bls12_381_G2_neg |
          Bls12_381_G2_scalarMul | Bls12_381_G2_equal | Bls12_381_G2_compress |
          Bls12_381_G2_uncompress | Bls12_381_millerLoop | Bls12_381_mulMlResult |
          Bls12_381_finalVerify | Bls12_381_G1_multiScalarMul | Bls12_381_G2_multiScalarMul =>
        none
      // `NumBytesCostedAsNumWords` is a costing measure (`Builtins.hs:2093-2102`, plutus 1.70.0.0).
      case IntegerToByteString => none
      // `CByteString` in D and E (`Builtins.hs:2103-2118`, plutus 1.70.0.0).
      case ByteStringToInteger => onChain(1 -> CByteString)
      // `Bool -> CByteString -> CByteString` in D and E (`Builtins.hs:2119-2166`, plutus 1.70.0.0).
      case AndByteString | OrByteString | XorByteString =>
        onChain(1 -> CByteString, 2 -> CByteString)
      // `BS.ByteString -> [Integer] -> Bool` in every variant (`Builtins.hs:2199-2229`,
      // plutus 1.70.0.0).
      case WriteBits => none
      // `NumBytesCostedAsNumWords -> Word8` in every variant (`Builtins.hs:2230-2236`,
      // plutus 1.70.0.0).
      case ReplicateByte => Map(1 -> W8)
      // `Int` in D and E, `IntegerCostedLiterally` before (`Builtins.hs:2237-2266`,
      // plutus 1.70.0.0).
      case ShiftByteString | RotateByteString => onChain(1 -> int)
      // `Integer`s and `IntegerCostedLiterally` (`Builtins.hs:2314-2377`, plutus 1.70.0.0).
      case ExpModInteger | DropList => none
      // `SomeConstant` arrays; `indexArray` takes an `Int` (`Builtins.hs:2378-2409`,
      // plutus 1.70.0.0).
      case LengthOfArray | ListToArray => none
      case IndexArray                  => Map(1 -> int)
      // `ByteString`, `Integer`, `[Integer]`, `[ByteString]` and the `Value` measures, which
      // are costing measures (`Builtins.hs:2426-2527`, plutus 1.70.0.0).
      case InsertCoin | LookupCoin | UnionValue | ValueContains | ValueData | UnValueData |
          ScaleValue | MultiIndexArray | Policies | AssetCount | KeepPolicies | DropPolicies =>
        none

  /** What the unlifting checks of one parameter. */
  private enum Check:
    /** `Opaque`: any value, passed as it is. */
    case Anything

    /** `SomeConstant`: any constant. */
    case AnyConstant

    /** A constant of `uni`, the ground form of `expected`, within `bound` if there is one. */
    case Typed(expected: Signature.Type, uni: DefaultUni, bound: Option[UnliftingBound])

  private def checks(variant: CoreVariant, function: DefaultFun): Vector[Check] =
    val bounded = bounds(variant, function)
    function.signature.parameters.zipWithIndex.map {
      case (Signature.Parameter.Opaque(_), _) => Check.Anything
      case (Signature.Parameter.Constant(t), i) =>
        t.ground.fold(Check.AnyConstant)(uni => Check.Typed(t, uni, bounded.get(i)))
    }

  private val tables: Vector[Unlifting] =
    CoreVariant.values.toVector.map(v => Table(DefaultFun.values.toVector.map(checks(v, _))))

  private def structural(cause: UnliftingCause) = UnliftingFailure(ErrorClass.Structural, cause)
  private def operational(cause: UnliftingCause) = UnliftingFailure(ErrorClass.Operational, cause)

  /** The cause when constant `c` at `position` lies outside `bound`. */
  private[core] def outside(
      bound: UnliftingBound,
      position: Int,
      c: Constant
  ): Option[UnliftingCause] =
    (bound, c) match
      case (UnliftingBound.CInteger, Constant.Integer(n)) =>
        // within [-2^262143, 2^262143 - 1] exactly when the two's complement needs at most 262143
        // bits besides the sign
        Option.when(n.bitLength > 262143)(UnliftingCause.OutOfBounds(position, bound))
      case (UnliftingBound.CByteString, Constant.ByteString(b)) =>
        Option.when(b.size > 65536)(UnliftingCause.OutOfBounds(position, bound))
      case (UnliftingBound.Int | UnliftingBound.Int64, Constant.Integer(n)) =>
        Option.unless(n.isValidLong)(UnliftingCause.NotWithinBounds(position, bound, n))
      case (UnliftingBound.Word64, Constant.Integer(n)) =>
        Option.unless(n.signum >= 0 && n.bitLength <= 64)(
          UnliftingCause.NotWithinBounds(position, bound, n)
        )
      case (UnliftingBound.Word8, Constant.Integer(n)) =>
        Option.unless(n.signum >= 0 && n <= 255)(UnliftingCause.NotWithinBounds(position, bound, n))
      // `bounds` puts each bound on a parameter of its type only (`CoreUnliftingSuite`)
      case _ => None

  /** The unlifting of one variant: the checks of every builtin, by ordinal. */
  private final class Table(byFunction: Vector[Vector[Check]]) extends Unlifting, HeldUnlifting:
    /** The checks of every builtin the last one first, as the machine holds the arguments. */
    private val lastFirst: Vector[List[Check]] = byFunction.map(_.reverseIterator.toList)

    /** [[apply]]'s verdict without its arguments: every check is total and pure, so walking from
      * the last argument and keeping the failure nearest the first is the failure [[apply]] stops
      * at.
      */
    override def held: Option[HeldUnlifting] = Some(this)

    def refusal[V](function: DefaultFun, arguments: List[V])(using
        MachineValue[V]
    ): Option[UnliftingFailure] =
      val expected = byFunction(function.ordinal)
      @tailrec def go(
          values: List[V],
          checks: List[Check],
          position: Int,
          found: Option[UnliftingFailure]
      ): Option[UnliftingFailure] = values match
        case Nil => found
        case value :: earlier =>
          checks match
            case Nil => found
            case check :: before =>
              val failure = verdict(check, position, value)
              go(earlier, before, position - 1, if failure.isEmpty then found else failure)
      if arguments.sizeIs != expected.size then
        Some(structural(UnliftingCause.Arity(expected.size, arguments.size)))
      else go(arguments, lastFirst(function.ordinal), expected.size - 1, None)

    /** [[unlift]]'s failure, made only when there is one. */
    private def verdict[V](check: Check, position: Int, value: V)(using
        mv: MachineValue[V]
    ): Option[UnliftingFailure] = check match
      case Check.Anything => None
      case Check.AnyConstant =>
        mv.asConstant(value) match
          case _: Constant             => None
          case MachineValue.NoConstant => Some(structural(UnliftingCause.NotAConstant(position)))
      case Check.Typed(expected, uni, bound) =>
        mv.asConstant(value) match
          case MachineValue.NoConstant => Some(structural(UnliftingCause.NotAConstant(position)))
          case c: Constant if (c.typeOf ne uni) && c.typeOf != uni =>
            Some(structural(UnliftingCause.WrongType(position, expected, c.typeOf)))
          case c: Constant =>
            bound match
              case None => None
              case Some(b) =>
                outside(b, position, c) match
                  case None        => None
                  case Some(cause) => Some(operational(cause))

    def apply[V](function: DefaultFun, arguments: Vector[V])(using
        mv: MachineValue[V]
    ): Either[UnliftingFailure, Vector[Argument[V]]] =
      val expected = byFunction(function.ordinal)
      @tailrec def go(
          i: Int,
          done: Vector[Argument[V]]
      ): Either[UnliftingFailure, Vector[Argument[V]]] =
        if i == expected.size then Right(done)
        else
          unlift(expected(i), i, arguments(i)) match
            case Right(argument) => go(i + 1, done :+ argument)
            case Left(failure)   => Left(failure)
      if arguments.sizeIs != expected.size then
        Left(structural(UnliftingCause.Arity(expected.size, arguments.size)))
      else go(0, Vector.empty)

    private def unlift[V](check: Check, position: Int, value: V)(using
        mv: MachineValue[V]
    ): Either[UnliftingFailure, Argument[V]] = check match
      case Check.Anything => Right(Argument.Opaque(value))
      case Check.AnyConstant =>
        mv.constantOf(value)
          .toRight(structural(UnliftingCause.NotAConstant(position)))
          .map(Argument.Con(_))
      case Check.Typed(expected, uni, bound) =>
        mv.constantOf(value) match
          case None => Left(structural(UnliftingCause.NotAConstant(position)))
          // the ground types of the scheme without arguments are the enum's singletons, so most
          // checks end at the reference comparison
          case Some(c) if (c.typeOf ne uni) && c.typeOf != uni =>
            Left(structural(UnliftingCause.WrongType(position, expected, c.typeOf)))
          case Some(c) =>
            bound.flatMap(outside(_, position, c)) match
              case Some(cause) => Left(operational(cause))
              case None        => Right(Argument.Con(c))
