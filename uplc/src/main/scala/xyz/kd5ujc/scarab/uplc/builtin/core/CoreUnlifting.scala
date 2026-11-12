// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni, SemanticsVariant, Signature}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  ErrorClass,
  MachineValue,
  UnliftingBound,
  UnliftingCause,
  UnliftingFailure,
  Unlifting
}

/** The unlifting of every builtin of plutus 1.70.0.0 (`507cab8c`) under variants A to E: each
  * argument in order, the first failure winning, as `toMonoF` reads them at full saturation
  * (`PlutusCore/Builtin/Meaning.hs:304-330`).
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

  /** The unlifting of `variant`, refused for the variants plutus 1.70.0.0 does not have. */
  def of(variant: SemanticsVariant): Either[UnsupportedVariant, Unlifting] =
    CoreVariant.of(variant).map(apply)

  def apply(variant: CoreVariant): Unlifting = tables(variant.ordinal)

  /** The log line the reference emits when unlifting fails this way: the message of an operational
    * error (the `MonadError BuiltinResult` instance puts it in the logs,
    * `Builtin/Result.hs:198-220`, which `builtinRuntimeFailure` raises,
    * `Builtin/Runtime.hs:110-111`, and `evalBuiltinApp` emits, `Cek/Internal.hs:1071-1073`); none
    * for a structural one.
    */
  def log(failure: UnliftingFailure): Option[String] = failure.errorClass match
    case ErrorClass.Structural  => None
    case ErrorClass.Operational => operationalMessage(failure.cause)

  private def operationalMessage(cause: UnliftingCause): Option[String] = cause match
    case UnliftingCause.OutOfBounds(_, UnliftingBound.CInteger)    => Some("Integer out of bounds")
    case UnliftingCause.OutOfBounds(_, UnliftingBound.CByteString) => Some("ByteString overflow")
    case UnliftingCause.NotWithinBounds(_, bound, value) =>
      Some(s"$value is not within the bounds of ${typeName(bound)}")
    case _ => None

  /** The name `typeRep` shows for the type a bound reads through: `Int` reads as an `Int64`
    * (`Universe.hs:615-617`).
    */
  private def typeName(bound: UnliftingBound): String = bound match
    case UnliftingBound.Int | UnliftingBound.Int64 => "Int64"
    case UnliftingBound.Word64                     => "Word64"
    case UnliftingBound.Word8                      => "Word8"
    case UnliftingBound.CInteger                   => "CInteger"
    case UnliftingBound.CByteString                => "CByteString"

  /** The bounds on `function` 's arguments under `variant`, by position: where the Haskell type of
    * its denotation in `toBuiltinMeaning` (`PlutusCore/Default/Builtins.hs`, cited by line) refines
    * the argument's universe type. Every other argument is unbounded.
    */
  def bounds(variant: CoreVariant, function: DefaultFun): Map[Int, UnliftingBound] =
    import DefaultFun.*
    import UnliftingBound.{CByteString, CInteger, Word64 as W64, Word8 as W8}
    val int = UnliftingBound.Int
    val ensurable = variant.ensurable
    def onChain(entries: (Int, UnliftingBound)*): Map[Int, UnliftingBound] =
      if ensurable then entries.toMap else Map.empty
    val none = Map.empty[Int, UnliftingBound]
    function match
      // `CInteger -> CInteger` in D and E (`Builtins.hs:1099-1203,1211-1240`).
      case AddInteger | SubtractInteger | MultiplyInteger | DivideInteger | QuotientInteger |
          RemainderInteger | ModInteger | LessThanInteger | LessThanEqualsInteger =>
        onChain(0 -> CInteger, 1 -> CInteger)
      // `Integer -> Integer` in every variant (`Builtins.hs:1204-1210`).
      case EqualsInteger => none
      // `CByteString -> CByteString` in D and E (`Builtins.hs:1242-1257,1351-1396`).
      case AppendByteString | EqualsByteString | LessThanByteString | LessThanEqualsByteString =>
        onChain(0 -> CByteString, 1 -> CByteString)
      // `Integer` (A, B), `Word8` (C), `CInteger -> CByteString` (D), `Word8 -> CByteString` (E)
      // (`Builtins.hs:1258-1308`).
      case ConsByteString =>
        variant match
          case CoreVariant.A | CoreVariant.B => none
          case CoreVariant.C                 => Map(0 -> W8)
          case CoreVariant.D                 => Map(0 -> CInteger, 1 -> CByteString)
          case CoreVariant.E                 => Map(0 -> W8, 1 -> CByteString)
      // `Int -> Int`, and `CByteString` in D and E (`Builtins.hs:1309-1323`).
      case SliceByteString => Map(0 -> int, 1 -> int) ++ onChain(2 -> CByteString)
      // `BS.ByteString` in every variant (`Builtins.hs:1324-1330`).
      case LengthOfByteString => none
      // `CByteString` in D and E, then `Int` (`Builtins.hs:1331-1350`); `readBit` likewise
      // (`Builtins.hs:2184-2198`).
      case IndexByteString | ReadBit => Map(1 -> int) ++ onChain(0 -> CByteString)
      // `CByteString` in D and E (`Builtins.hs:1397-1441,1588-1611,2059-2092,2167-2183,
      // 2267-2313`).
      case Sha2_256 | Sha3_256 | Blake2b_256 | Keccak_256 | Blake2b_224 | Ripemd_160 | DecodeUtf8 |
          ComplementByteString | CountSetBits | FindFirstSetBit =>
        onChain(0 -> CByteString)
      // The message only, in D and E (`Builtins.hs:1442-1478,1487-1504`).
      case VerifyEd25519Signature | VerifySchnorrSecp256k1Signature => onChain(1 -> CByteString)
      // `BS.ByteString` in every variant (`Builtins.hs:1479-1486`).
      case VerifyEcdsaSecp256k1Signature => none
      // `Text` (A to C) or `TextCostedByByteLength` (D, E), a costing measure, not a bound
      // (`Builtins.hs:1507-1587`).
      case AppendString | EqualsString | EncodeUtf8 => none
      // Polymorphic, `()`, `Bool`, `Text`, `Data` and lists of them (`Builtins.hs:1612-1743,
      // 1760-1884`).
      case IfThenElse | ChooseUnit | Trace | FstPair | SndPair | ChooseList | MkCons | HeadList |
          TailList | NullList | ChooseData | MapData | ListData | IData | BData | UnConstrData |
          UnMapData | UnListData | UnIData | UnBData | EqualsData | SerialiseData | MkPairData |
          MkNilData | MkNilPairData =>
        none
      // `Word64` in D and E (`Builtins.hs:1745-1759`).
      case ConstrData => onChain(0 -> W64)
      // The message (the first argument) in D and E (`Builtins.hs:1933-1950,2007-2024`).
      case Bls12_381_G1_hashToGroup | Bls12_381_G2_hashToGroup => onChain(0 -> CByteString)
      // Group elements, `Integer` scalars and `BS.ByteString` in every variant
      // (`Builtins.hs:1886-1932,1951-2006,2025-2058,2410-2425`).
      case Bls12_381_G1_add | Bls12_381_G1_neg | Bls12_381_G1_scalarMul | Bls12_381_G1_equal |
          Bls12_381_G1_compress | Bls12_381_G1_uncompress | Bls12_381_G2_add | Bls12_381_G2_neg |
          Bls12_381_G2_scalarMul | Bls12_381_G2_equal | Bls12_381_G2_compress |
          Bls12_381_G2_uncompress | Bls12_381_millerLoop | Bls12_381_mulMlResult |
          Bls12_381_finalVerify | Bls12_381_G1_multiScalarMul | Bls12_381_G2_multiScalarMul =>
        none
      // `NumBytesCostedAsNumWords` is a costing measure (`Builtins.hs:2093-2102`).
      case IntegerToByteString => none
      // `CByteString` in D and E (`Builtins.hs:2103-2118`).
      case ByteStringToInteger => onChain(1 -> CByteString)
      // `Bool -> CByteString -> CByteString` in D and E (`Builtins.hs:2119-2166`).
      case AndByteString | OrByteString | XorByteString =>
        onChain(1 -> CByteString, 2 -> CByteString)
      // `BS.ByteString -> [Integer] -> Bool` in every variant (`Builtins.hs:2199-2229`).
      case WriteBits => none
      // `NumBytesCostedAsNumWords -> Word8` in every variant (`Builtins.hs:2230-2236`).
      case ReplicateByte => Map(1 -> W8)
      // `Int` in D and E, `IntegerCostedLiterally` before (`Builtins.hs:2237-2266`).
      case ShiftByteString | RotateByteString => onChain(1 -> int)
      // `Integer`s and `IntegerCostedLiterally` (`Builtins.hs:2314-2377`).
      case ExpModInteger | DropList => none
      // `SomeConstant` arrays; `indexArray` takes an `Int` (`Builtins.hs:2378-2409`).
      case LengthOfArray | ListToArray => none
      case IndexArray                  => Map(1 -> int)
      // `ByteString`, `Integer`, `[Integer]`, `[ByteString]` and the `Value` measures, which
      // are costing measures (`Builtins.hs:2426-2527`).
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
  private final class Table(byFunction: Vector[Vector[Check]]) extends Unlifting:
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
      if arguments.size != expected.size then
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
