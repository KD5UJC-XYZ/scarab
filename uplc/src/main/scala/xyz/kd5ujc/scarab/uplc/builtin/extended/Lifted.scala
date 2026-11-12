// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultUni, PlutusData, Signature, Value}
import xyz.kd5ujc.scarab.uplc.bls.{BlsDefect, BlsFailure, G1Element, G2Element, MlResult}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  BuiltinFailure,
  Denotation,
  ErrorClass,
  EvaluationFailure,
  MachineValue,
  Outcome,
  UnliftingCause,
  UnliftingFailure
}
import xyz.kd5ujc.scarab.uplc.DefaultFun

/** How a constant argument of a builtin of this package is read: the type it has once the unlifting
  * has checked it ([[xyz.kd5ujc.scarab.uplc.builtin.Unlifting]]). The unlifting refuses any other
  * argument before the denotation runs, so a mismatch here is the structural error the unlifting
  * would have raised.
  */
trait Reads[A]:
  def expected: Signature.Type
  def read(c: Constant): Option[A]

object Reads:
  private def of[A](t: Signature.Type)(f: PartialFunction[Constant, A]): Reads[A] = new Reads[A]:
    def expected: Signature.Type = t
    def read(c: Constant): Option[A] = f.lift(c)

  given Reads[BigInt] = of(Signature.Type.Integer) { case Constant.Integer(i) => i }
  given Reads[Bytes] = of(Signature.Type.ByteString) { case Constant.ByteString(b) => b }
  given Reads[Boolean] = of(Signature.Type.Bool) { case Constant.Bool(b) => b }
  given Reads[PlutusData] = of(Signature.Type.Data) { case Constant.Data(d) => d }
  given Reads[Value] = of(Signature.Type.Value) { case Constant.Value(v) => v }
  given Reads[G1Element] = of(Signature.Type.Bls12_381_G1_Element) {
    case Constant.Bls12_381_G1_Element(e) => e
  }
  given Reads[G2Element] = of(Signature.Type.Bls12_381_G2_Element) {
    case Constant.Bls12_381_G2_Element(e) => e
  }
  given Reads[MlResult] = of(Signature.Type.Bls12_381_MlResult) {
    case Constant.Bls12_381_MlResult(m) => m
  }

  /** A monomorphic list (`[Integer]`, `[ByteString]`, `[G1.Element]`) whose elements read as `A`.
    */
  given [A](using element: Reads[A]): Reads[Vector[A]] = new Reads[Vector[A]]:
    def expected: Signature.Type = Signature.Type.List(element.expected)
    def read(c: Constant): Option[Vector[A]] = c match
      case l: Constant.List =>
        l.items.foldLeft(Option(Vector.empty[A]))((acc, x) =>
          acc.flatMap(xs => element.read(x).map(xs :+ _))
        )
      case _ => None

/** The result of a builtin of this package before it becomes a value of the machine. */
type Result = Either[BuiltinFailure, Constant]

object Lifted:
  /** The outcome of `result`: its constant as a value of the machine, or its failure with the log
    * lines the reference emits for it ([[LogLines]]; a structural error emits none).
    */
  def outcome[V](result: Result)(using mv: MachineValue[V]): Outcome[V] = result match
    case Right(c)                               => Outcome.succeeded(mv.ofConstant(c))
    case Left(f @ BuiltinFailure.Evaluation(e)) => Outcome.Failure(f, logs(e))
    case Left(f)                                => Outcome.Failure(f, Vector.empty)

  /** The lines the reference emits for `failure`, one per `emit` or `fail`. */
  def logs(failure: EvaluationFailure): Vector[String] = failure match
    case EvaluationFailure.SignatureInput(f) => f.lines
    case EvaluationFailure.Bitwise(f)        => f.lines
    case EvaluationFailure.ExpMod(f)         => f.lines
    case EvaluationFailure.ValueOperation(f) => f.lines
    case EvaluationFailure.ArrayIndices(f)   => f.lines
    case other                               => other.message.toVector

  def failed(failure: EvaluationFailure): BuiltinFailure = BuiltinFailure.Evaluation(failure)

  def structural(cause: UnliftingCause): BuiltinFailure =
    BuiltinFailure.Unlifting(UnliftingFailure(ErrorClass.Structural, cause))

  /** The structural error of an argument that is not of the type the unlifting checked. */
  def wrongType(position: Int, expected: Signature.Type, actual: DefaultUni): BuiltinFailure =
    BuiltinFailure.Unlifting(
      UnliftingFailure(ErrorClass.Structural, UnliftingCause.WrongType(position, expected, actual))
    )

  /** A failure of the BLS facade: the reference's own, or a defect of `function`. */
  def bls(function: DefaultFun)(failure: BlsFailure | BlsDefect): BuiltinFailure = failure match
    case f: BlsFailure => failed(EvaluationFailure.Bls(f))
    case BlsDefect.Thrown(cause) =>
      failed(EvaluationFailure.Defect(function, LibraryDefect.Thrown(cause)))
    case BlsDefect.NotInGroup(group) =>
      failed(EvaluationFailure.Defect(function, LibraryDefect.HashedOutsideGroup(group)))
    case BlsDefect.DetachedMlResult =>
      failed(EvaluationFailure.Defect(function, LibraryDefect.DetachedMlResult))

  /** A builtin over the constants of its arguments. */
  def denotation(body: Vector[Constant] => Result): Denotation = new Denotation:
    def apply[V](arguments: Vector[Argument[V]])(using mv: MachineValue[V]): Outcome[V] =
      val constants = arguments.zipWithIndex.foldLeft[Either[BuiltinFailure, Vector[Constant]]](
        Right(Vector.empty)
      ) {
        case (Left(f), _)                      => Left(f)
        case (Right(cs), (Argument.Con(c), _)) => Right(cs :+ c)
        case (Right(cs), (Argument.Opaque(v), i)) =>
          mv.constantOf(v)
            .toRight(
              BuiltinFailure.Unlifting(
                UnliftingFailure(ErrorClass.Structural, UnliftingCause.NotAConstant(i))
              )
            )
            .map(cs :+ _)
      }
      outcome(constants.flatMap(body))

  /** The argument at `position`, read as `A`. */
  def arg[A](constants: Vector[Constant], position: Int)(using
      r: Reads[A]
  ): Either[BuiltinFailure, A] =
    constants.lift(position) match
      case Some(c) => r.read(c).toRight(wrongType(position, r.expected, c.typeOf))
      case None =>
        Left(
          BuiltinFailure.Unlifting(
            UnliftingFailure(ErrorClass.Structural, UnliftingCause.NotAConstant(position))
          )
        )

  def one[A: Reads](f: A => Result): Denotation =
    denotation(cs => arg[A](cs, 0).flatMap(f))

  def two[A: Reads, B: Reads](f: (A, B) => Result): Denotation =
    denotation(cs =>
      for
        a <- arg[A](cs, 0)
        b <- arg[B](cs, 1)
        r <- f(a, b)
      yield r
    )

  def three[A: Reads, B: Reads, C: Reads](f: (A, B, C) => Result): Denotation =
    denotation(cs =>
      for
        a <- arg[A](cs, 0)
        b <- arg[B](cs, 1)
        c <- arg[C](cs, 2)
        r <- f(a, b, c)
      yield r
    )

  def four[A: Reads, B: Reads, C: Reads, D: Reads](f: (A, B, C, D) => Result): Denotation =
    denotation(cs =>
      for
        a <- arg[A](cs, 0)
        b <- arg[B](cs, 1)
        c <- arg[C](cs, 2)
        d <- arg[D](cs, 3)
        r <- f(a, b, c, d)
      yield r
    )
