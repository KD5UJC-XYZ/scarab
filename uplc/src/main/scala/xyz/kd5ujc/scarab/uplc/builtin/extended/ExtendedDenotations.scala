// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import cats.syntax.either.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, PlutusData, SemanticsVariant, Value}
import xyz.kd5ujc.scarab.uplc.bls.{Bls12_381, BlsDefect, BlsFailure, G1Element, G2Element, MlResult}
import xyz.kd5ujc.scarab.uplc.builtin.{
  BuiltinFailure,
  Denotation,
  ErrorClass,
  EvaluationFailure,
  UnliftingBound,
  UnliftingCause,
  UnliftingFailure
}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreVariant, UnsupportedVariant}

import Lifted.*

/** The denotations of the cryptographic, bitwise, conversion, `Value` and array builtins of plutus
  * 1.70.0.0 (`507cab8c`), the meanings of `toBuiltinMeaning` in `PlutusCore/Default/Builtins.hs`
  * (cited by line); the core builtins are [[xyz.kd5ujc.scarab.uplc.builtin.core.CoreDenotations]].
  *
  * Where the variants of one of these builtins differ only in the Haskell type an argument is read
  * as (`CByteString`, `Int`, `Word8`), the unlifting bounds the argument
  * ([[xyz.kd5ujc.scarab.uplc.builtin.core.CoreUnlifting.bounds]]) and the variants share the
  * meaning. Two meanings differ by variant (`ensurable`, `Builtins.hs:2788-2793`): the scalar of
  * `bls12_381_G1_scalarMul` and `bls12_381_G2_scalarMul` is bounded in D and E
  * (`Builtins.hs:1901-1917,1974-1990`), and `writeBits` refuses an input of more than 4096 bytes in
  * D and E (`Builtins.hs:2199-2229`).
  *
  * Batch 7 (`multiIndexArray`, `policies`, `assetCount`, `keepPolicies`, `dropPolicies`) is not
  * available at protocols 5 to 11 and has no denotation here.
  */
object ExtendedDenotations:

  /** The denotations of `variant`, refused for the variants plutus 1.70.0.0 does not have. */
  def of(variant: SemanticsVariant): Either[UnsupportedVariant, Map[DefaultFun, Denotation]] =
    CoreVariant.of(variant).map(apply)

  def apply(variant: CoreVariant): Map[DefaultFun, Denotation] = tables(variant.ordinal)

  /** The builtins whose denotations are here. */
  def functions: Set[DefaultFun] = tables(CoreVariant.E.ordinal).keySet

  private val tables: Vector[Map[DefaultFun, Denotation]] =
    CoreVariant.values.toVector.map(table)

  private def integer(n: BigInt): Constant = Constant.Integer(n)
  private def bytes(b: Bytes): Constant = Constant.ByteString(b)
  private def bool(b: Boolean): Constant = Constant.Bool(b)
  private def value(v: Value): Constant = Constant.Value(v)
  private def g1(e: G1Element): Constant = Constant.Bls12_381_G1_Element(e)
  private def g2(e: G2Element): Constant = Constant.Bls12_381_G2_Element(e)
  private def ml(m: MlResult): Constant = Constant.Bls12_381_MlResult(m)

  private def pure(c: Constant): Result = Right(c)

  private def blsOf[A](function: DefaultFun)(
      result: Either[BlsFailure | BlsDefect, A]
  )(constant: A => Constant): Result =
    result.leftMap(Lifted.bls(function)).map(constant)

  private def bitwise[A](result: Either[BitwiseFailure, A])(constant: A => Constant): Result =
    result.leftMap(f => failed(EvaluationFailure.Bitwise(f))).map(constant)

  private def valueResult[A](result: Either[ValueFailure, A])(constant: A => Constant): Result =
    result.leftMap(f => failed(EvaluationFailure.ValueOperation(f))).map(constant)

  private def verdict(function: DefaultFun)(result: Verified): Result =
    result
      .leftMap {
        case f: SignatureInputFailure => failed(EvaluationFailure.SignatureInput(f))
        case d: LibraryDefect         => failed(EvaluationFailure.Defect(function, d))
      }
      .map(bool)

  /** The argument at `position` as any constant (`SomeConstant`). */
  private def someConstant(cs: Vector[Constant], position: Int): Either[BuiltinFailure, Constant] =
    cs.lift(position).toRight(structural(UnliftingCause.NotAConstant(position)))

  /** A `SomeConstant` array: anything else is "Expected an array but got something else". */
  private def array(c: Constant, position: Int): Either[BuiltinFailure, Constant.Array] = c match
    case a: Constant.Array => Right(a)
    case other             => Left(structural(UnliftingCause.ExpectedArray(position, other.typeOf)))

  /** A `Word8` the unlifting has bounded already (`readKnownAsInteger`), checked again. */
  private def word8(n: BigInt, position: Int): Either[BuiltinFailure, Int] =
    Either.cond(
      n.signum >= 0 && n <= 255,
      n.toInt,
      BuiltinFailure.Unlifting(
        UnliftingFailure(
          ErrorClass.Operational,
          UnliftingCause.NotWithinBounds(position, UnliftingBound.Word8, n)
        )
      )
    )

  private def table(variant: CoreVariant): Map[DefaultFun, Denotation] =
    import DefaultFun.*
    val ensurable = variant.ensurable
    Map(
      // Hashes (`Builtins.hs:1397-1441,2059-2092,2297-2313`).
      Sha2_256 -> one[Bytes](b => pure(bytes(Hashes.sha2_256(b)))),
      Sha3_256 -> one[Bytes](b => pure(bytes(Hashes.sha3_256(b)))),
      Blake2b_256 -> one[Bytes](b => pure(bytes(Hashes.blake2b_256(b)))),
      Blake2b_224 -> one[Bytes](b => pure(bytes(Hashes.blake2b_224(b)))),
      Keccak_256 -> one[Bytes](b => pure(bytes(Hashes.keccak_256(b)))),
      Ripemd_160 -> one[Bytes](b => pure(bytes(Hashes.ripemd_160(b)))),
      // Signatures (`Builtins.hs:1442-1504`): key, message, signature.
      VerifyEd25519Signature -> three[Bytes, Bytes, Bytes]((k, m, s) =>
        verdict(VerifyEd25519Signature)(Signatures.verifyEd25519(k, m, s))
      ),
      VerifyEcdsaSecp256k1Signature -> three[Bytes, Bytes, Bytes]((k, m, s) =>
        verdict(VerifyEcdsaSecp256k1Signature)(Signatures.verifyEcdsa(k, m, s))
      ),
      VerifySchnorrSecp256k1Signature -> three[Bytes, Bytes, Bytes]((k, m, s) =>
        verdict(VerifySchnorrSecp256k1Signature)(Signatures.verifySchnorr(k, m, s))
      ),
      // BLS12-381 G1 (`Builtins.hs:1886-1958,2410-2417`).
      Bls12_381_G1_add -> two[G1Element, G1Element]((a, b) =>
        blsOf(Bls12_381_G1_add)(Bls12_381.G1.add(a, b))(g1)
      ),
      Bls12_381_G1_neg -> one[G1Element](a => blsOf(Bls12_381_G1_neg)(Bls12_381.G1.neg(a))(g1)),
      Bls12_381_G1_scalarMul -> two[BigInt, G1Element]((k, a) =>
        blsOf(Bls12_381_G1_scalarMul)(Bls12_381.G1.scalarMul(k, a, bounded = ensurable))(g1)
      ),
      Bls12_381_G1_equal -> two[G1Element, G1Element]((a, b) =>
        pure(bool(a.compressed == b.compressed))
      ),
      Bls12_381_G1_compress -> one[G1Element](a => pure(bytes(a.compressed))),
      Bls12_381_G1_uncompress -> one[Bytes](b =>
        blsOf(Bls12_381_G1_uncompress)(Bls12_381.G1.uncompress(b))(g1)
      ),
      Bls12_381_G1_hashToGroup -> two[Bytes, Bytes]((m, dst) =>
        blsOf(Bls12_381_G1_hashToGroup)(Bls12_381.G1.hashToGroup(m, dst))(g1)
      ),
      Bls12_381_G1_multiScalarMul -> two[Vector[BigInt], Vector[G1Element]]((ks, ps) =>
        blsOf(Bls12_381_G1_multiScalarMul)(Bls12_381.G1.multiScalarMul(ks, ps))(g1)
      ),
      // BLS12-381 G2 (`Builtins.hs:1959-2032,2418-2425`).
      Bls12_381_G2_add -> two[G2Element, G2Element]((a, b) =>
        blsOf(Bls12_381_G2_add)(Bls12_381.G2.add(a, b))(g2)
      ),
      Bls12_381_G2_neg -> one[G2Element](a => blsOf(Bls12_381_G2_neg)(Bls12_381.G2.neg(a))(g2)),
      Bls12_381_G2_scalarMul -> two[BigInt, G2Element]((k, a) =>
        blsOf(Bls12_381_G2_scalarMul)(Bls12_381.G2.scalarMul(k, a, bounded = ensurable))(g2)
      ),
      Bls12_381_G2_equal -> two[G2Element, G2Element]((a, b) =>
        pure(bool(a.compressed == b.compressed))
      ),
      Bls12_381_G2_compress -> one[G2Element](a => pure(bytes(a.compressed))),
      Bls12_381_G2_uncompress -> one[Bytes](b =>
        blsOf(Bls12_381_G2_uncompress)(Bls12_381.G2.uncompress(b))(g2)
      ),
      Bls12_381_G2_hashToGroup -> two[Bytes, Bytes]((m, dst) =>
        blsOf(Bls12_381_G2_hashToGroup)(Bls12_381.G2.hashToGroup(m, dst))(g2)
      ),
      Bls12_381_G2_multiScalarMul -> two[Vector[BigInt], Vector[G2Element]]((ks, ps) =>
        blsOf(Bls12_381_G2_multiScalarMul)(Bls12_381.G2.multiScalarMul(ks, ps))(g2)
      ),
      // Pairing (`Builtins.hs:2033-2058`).
      Bls12_381_millerLoop -> two[G1Element, G2Element]((a, b) =>
        blsOf(Bls12_381_millerLoop)(Bls12_381.Pairing.millerLoop(a, b))(ml)
      ),
      Bls12_381_mulMlResult -> two[MlResult, MlResult]((a, b) =>
        blsOf(Bls12_381_mulMlResult)(Bls12_381.Pairing.mulMlResult(a, b))(ml)
      ),
      Bls12_381_finalVerify -> two[MlResult, MlResult]((a, b) =>
        blsOf(Bls12_381_finalVerify)(Bls12_381.Pairing.finalVerify(a, b))(bool)
      ),
      // Conversions (`Builtins.hs:2093-2118`).
      IntegerToByteString -> three[Boolean, BigInt, BigInt]((e, n, x) =>
        bitwise(Bitwise.integerToByteString(e, n, x))(bytes)
      ),
      ByteStringToInteger -> two[Boolean, Bytes]((e, b) =>
        pure(integer(Bitwise.byteStringToInteger(e, b)))
      ),
      // Logical and bitwise operations (`Builtins.hs:2119-2296`).
      AndByteString -> three[Boolean, Bytes, Bytes]((pad, x, y) =>
        pure(bytes(Bitwise.combine(pad, x, y)(_ & _)))
      ),
      OrByteString -> three[Boolean, Bytes, Bytes]((pad, x, y) =>
        pure(bytes(Bitwise.combine(pad, x, y)(_ | _)))
      ),
      XorByteString -> three[Boolean, Bytes, Bytes]((pad, x, y) =>
        pure(bytes(Bitwise.combine(pad, x, y)(_ ^ _)))
      ),
      ComplementByteString -> one[Bytes](b => pure(bytes(Bitwise.complement(b)))),
      ReadBit -> two[Bytes, BigInt]((b, i) => bitwise(Bitwise.readBit(b, i))(bool)),
      WriteBits -> three[Bytes, Vector[BigInt], Boolean]((b, is, bit) =>
        bitwise(Bitwise.writeBits(b, is, bit, limited = ensurable))(bytes)
      ),
      ReplicateByte -> two[BigInt, BigInt]((n, w) =>
        word8(w, 1).flatMap(byte => bitwise(Bitwise.replicateByte(n, byte))(bytes))
      ),
      ShiftByteString -> two[Bytes, BigInt]((b, n) => pure(bytes(Bitwise.shift(b, n)))),
      RotateByteString -> two[Bytes, BigInt]((b, n) => pure(bytes(Bitwise.rotate(b, n)))),
      CountSetBits -> one[Bytes](b => pure(integer(Bitwise.countSetBits(b)))),
      FindFirstSetBit -> one[Bytes](b => pure(integer(Bitwise.findFirstSetBit(b)))),
      // `expModInteger` (`Builtins.hs:2314-2327`).
      ExpModInteger -> three[BigInt, BigInt, BigInt]((b, e, m) =>
        ExpMod
          .expMod(b, e, m)
          .leftMap(f => failed(EvaluationFailure.ExpMod(f)))
          .map(integer)
      ),
      // Arrays (`Builtins.hs:2378-2409`).
      LengthOfArray -> denotation(cs =>
        someConstant(cs, 0).flatMap(array(_, 0)).map(a => integer(a.items.size))
      ),
      ListToArray -> denotation(cs =>
        someConstant(cs, 0).flatMap {
          case l: Constant.List => Right(l.toArray)
          case other            => Left(structural(UnliftingCause.ExpectedList(0, other.typeOf)))
        }
      ),
      IndexArray -> denotation(cs =>
        for
          a <- someConstant(cs, 0).flatMap(array(_, 0))
          i <- arg[BigInt](cs, 1)
          element <- Option
            .when(i.signum >= 0 && i < a.items.size)(i.toInt)
            .flatMap(a.items.lift)
            .toRight(failed(EvaluationFailure.ArrayIndexOutOfBounds(i, a.items.size)))
        yield element
      ),
      // `Value` (`Builtins.hs:2426-2481`).
      InsertCoin -> four[Bytes, Bytes, BigInt, Value]((c, t, q, v) =>
        valueResult(Values.insertCoin(c, t, q, v))(value)
      ),
      LookupCoin -> three[Bytes, Bytes, Value]((c, t, v) =>
        pure(integer(Values.lookupCoin(c, t, v)))
      ),
      UnionValue -> two[Value, Value]((a, b) => valueResult(Values.unionValue(a, b))(value)),
      ValueContains -> two[Value, Value]((a, b) => valueResult(Values.valueContains(a, b))(bool)),
      ValueData -> one[Value](v => valueResult(Values.valueData(v))(Constant.Data(_))),
      UnValueData -> one[PlutusData](d => valueResult(Values.unValueData(d))(value)),
      ScaleValue -> two[BigInt, Value]((c, v) => valueResult(Values.scaleValue(c, v))(value))
    )
