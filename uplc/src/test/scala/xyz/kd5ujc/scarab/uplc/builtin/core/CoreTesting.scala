// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultFun,
  DefaultUni,
  PlutusData,
  PlutusLanguage,
  ProtocolMajor,
  SemanticsVariant,
  Text,
  Value
}
import xyz.kd5ujc.scarab.uplc.bls.{G1Element, G2Element, MlResult}
import xyz.kd5ujc.scarab.uplc.builtin.{BuiltinFailure, MachineValue, Outcome}

/** A value of a test machine: a constant, or something that is not one (a lambda, a delay). */
enum Probe:
  case Con(constant: Constant)
  case Lambda(name: String)

object Probe:
  given MachineValue[Probe] with
    def constantOf(value: Probe): Option[Constant] = value match
      case Con(c)    => Some(c)
      case Lambda(_) => None
    def ofConstant(constant: Constant): Probe = Con(constant)

/** Applying the core builtins as the machine does, without the costing: unlift, then denote. */
object CoreTesting:
  def int(n: BigInt): Probe = Probe.Con(Constant.Integer(n))
  def bytes(hex: String): Probe = Probe.Con(Constant.ByteString(hexBytes(hex)))
  def bytes(b: Bytes): Probe = Probe.Con(Constant.ByteString(b))
  def string(s: String): Probe = Probe.Con(Constant.String(Text.fromString(s)))
  def bool(b: Boolean): Probe = Probe.Con(Constant.Bool(b))
  val unit: Probe = Probe.Con(Constant.Unit)
  def data(d: PlutusData): Probe = Probe.Con(Constant.Data(d))
  def con(c: Constant): Probe = Probe.Con(c)
  val lambda: Probe = Probe.Lambda("x")

  def hexBytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => sys.error(e.message), identity)

  def list(elementType: DefaultUni, items: Constant*): Constant =
    Constant.List.of(elementType, items.toVector).fold(e => sys.error(e.toString), identity)

  def dataList(items: PlutusData*): Constant = list(DefaultUni.Data, items.map(Constant.Data(_))*)

  def variant(v: SemanticsVariant): CoreVariant =
    CoreVariant.of(v).fold(e => sys.error(e.toString), identity)

  /** The variant of `language` (1 to 3, as the fixture rows number them) at `protocol`. */
  def variantOf(language: Int, protocol: Int): CoreVariant =
    val l =
      PlutusLanguage.values.find(_.id == language - 1).getOrElse(sys.error(s"language $language"))
    variant(SemanticsVariant.of(l, ProtocolMajor(protocol)))

  /** The application of `function` to `arguments` under `variant`: unlifting, then the denotation.
    * A failure of either is the outcome's failure, with the logs the reference emits.
    */
  def apply(variant: CoreVariant, function: DefaultFun, arguments: Probe*): Outcome[Probe] =
    CoreUnlifting(variant)(function, arguments.toVector) match
      case Left(failure) =>
        Outcome.Failure(BuiltinFailure.Unlifting(failure), CoreUnlifting.log(failure).toVector)
      case Right(unlifted) =>
        CoreDenotations(variant)
          .get(function)
          .fold(sys.error(s"$function has no core denotation"))(_(unlifted))

  /** The result as a constant, or the failure. */
  def result(outcome: Outcome[Probe]): Either[BuiltinFailure, Constant] = outcome match
    case Outcome.Success(Probe.Con(c), _)    => Right(c)
    case Outcome.Success(Probe.Lambda(n), _) => sys.error(s"not a constant: $n")
    case Outcome.Failure(failure, _)         => Left(failure)

  /** A constant of `uni`, within every bound. */
  def sample(uni: DefaultUni): Constant = uni match
    case DefaultUni.Integer    => Constant.Integer(1)
    case DefaultUni.ByteString => Constant.ByteString(hexBytes("01"))
    case DefaultUni.String     => Constant.String(Text.fromString("a"))
    case DefaultUni.Unit       => Constant.Unit
    case DefaultUni.Bool       => Constant.Bool(true)
    case DefaultUni.Data       => Constant.Data(PlutusData.I(0))
    case DefaultUni.Bls12_381_G1_Element =>
      Constant.Bls12_381_G1_Element(G1Element.fromCompressed(Bytes.empty))
    case DefaultUni.Bls12_381_G2_Element =>
      Constant.Bls12_381_G2_Element(G2Element.fromCompressed(Bytes.empty))
    case DefaultUni.Bls12_381_MlResult => Constant.Bls12_381_MlResult(MlResult.fromRaw(Bytes.empty))
    case DefaultUni.Value              => Constant.Value(Value.empty)
    case DefaultUni.List(e)            => list(e, sample(e))
    case DefaultUni.Pair(a, b)         => Constant.Pair(sample(a), sample(b))
    case DefaultUni.Array(e) =>
      Constant.Array.of(e, Vector(sample(e))).fold(err => sys.error(err.toString), identity)
