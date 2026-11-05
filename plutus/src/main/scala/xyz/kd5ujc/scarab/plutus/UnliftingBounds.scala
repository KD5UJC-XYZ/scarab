// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scalus.uplc.{Constant, DefaultFun}
import scalus.uplc.eval.CekValue

/** The bounds the `ensurable` semantics variants put on the arguments of some builtins (plutus
  * 1.70.0.0, `507cab8c`, `PlutusCore/Default/Builtins.hs`: `ensurable` is variants D and E,
  * PlutusV1 and PlutusV2 and PlutusV3 from protocol 11, `PlutusLedgerApi/MachineParameters.hs`).
  * Those builtins take the argument as `CInteger` or `CByteString`, whose unlifting refuses an
  * integer outside `-2^262143 .. 2^262143 - 1` and a byte string longer than 65,536 bytes
  * (`PlutusCore/Default/Universe/Cardano.hs` lines 9-19, `PlutusCore/Default/Universe.hs` lines
  * 875-925). Unlifting is deferred to the saturated application, and a failed one ends the
  * evaluation with no cost for the application (`toMonoF`, `PlutusCore/Builtin/Meaning.hs` lines
  * 245-262). Results are not bounded. Scalus 1.4.0 has no such bound (issue 498).
  *
  * Variants F and G of plutus 1.71.0.0 (`261766ad`, PlutusV1 to PlutusV4 from protocol 12) keep the
  * byte-string bounds (`ensurableByteString`) and drop the integer ones (`ensurableInteger`,
  * `PlutusCore/Default/Builtins.hs:2806-2820`): [[SemanticsVariant]].
  */
private[plutus] object UnliftingBounds:
  /** The type an argument is unlifted as. */
  enum Bound:
    case CInteger, CByteString

  val MinInteger: BigInt = -(BigInt(1) << 262143)
  val MaxInteger: BigInt = (BigInt(1) << 262143) - 1
  val MaxByteString: Int = 65536

  private val integers = Vector(0 -> Bound.CInteger, 1 -> Bound.CInteger)
  private val bytes2 = Vector(0 -> Bound.CByteString, 1 -> Bound.CByteString)
  private val bytes1 = Vector(0 -> Bound.CByteString)

  /** The bounded arguments of each builtin, by position, as the `ensurable` branches of
    * `toBuiltinMeaning` type them. `ConsByteString` takes its first argument as `CInteger` in
    * variant D, as a `Word8` in variants E and G and as an unbounded `Integer` in variant F, which
    * only variant D bounds here.
    */
  def of(fun: DefaultFun, variant: SemanticsVariant): Vector[(Int, Bound)] =
    if !variant.boundsByteStrings then Vector.empty
    else
      val ints = if variant.boundsIntegers then integerArgs(fun, variant) else Vector.empty
      (ints ++ byteStrings(fun)).sortBy(_._1)

  private def integerArgs(fun: DefaultFun, variant: SemanticsVariant): Vector[(Int, Bound)] =
    import DefaultFun.*
    fun match
      case AddInteger | SubtractInteger | MultiplyInteger | DivideInteger | QuotientInteger |
          RemainderInteger | ModInteger | LessThanInteger | LessThanEqualsInteger =>
        integers
      case ConsByteString if variant == SemanticsVariant.D => Vector(0 -> Bound.CInteger)
      case _                                               => Vector.empty

  private def byteStrings(fun: DefaultFun): Vector[(Int, Bound)] =
    import DefaultFun.*
    fun match
      case AppendByteString | EqualsByteString | LessThanByteString | LessThanEqualsByteString =>
        bytes2
      case Sha2_256 | Sha3_256 | Blake2b_256 | Blake2b_224 | Keccak_256 | Ripemd_160 | DecodeUtf8 |
          ComplementByteString | CountSetBits | FindFirstSetBit | IndexByteString | ReadBit |
          Bls12_381_G1_hashToGroup | Bls12_381_G2_hashToGroup =>
        bytes1
      case SliceByteString => Vector(2 -> Bound.CByteString)
      case VerifyEd25519Signature | VerifySchnorrSecp256k1Signature | ByteStringToInteger =>
        Vector(1 -> Bound.CByteString)
      case AndByteString | OrByteString | XorByteString =>
        Vector(1 -> Bound.CByteString, 2 -> Bound.CByteString)
      case ConsByteString => Vector(1 -> Bound.CByteString)
      case _              => Vector.empty

  /** Whether `value` unlifts as `bound`; a value of another type is left to the builtin's own
    * unlifting.
    */
  def within(bound: Bound, value: CekValue): Boolean = (bound, value) match
    case (Bound.CInteger, CekValue.VCon(Constant.Integer(n))) => n >= MinInteger && n <= MaxInteger
    case (Bound.CByteString, CekValue.VCon(Constant.ByteString(b))) => b.size <= MaxByteString
    case _                                                          => true

  /** Whether every bounded argument of `args` is within its bound. */
  def admits(bounded: Vector[(Int, Bound)], args: collection.Seq[CekValue]): Boolean =
    bounded.forall((at, bound) => args.lift(at).forall(within(bound, _)))
