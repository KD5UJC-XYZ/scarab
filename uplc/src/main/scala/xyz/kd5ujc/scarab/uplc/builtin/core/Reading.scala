// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultUni, PlutusData, Signature, Text}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  BuiltinFailure,
  ErrorClass,
  EvaluationFailure,
  MachineValue,
  Outcome,
  UnliftingBound,
  UnliftingCause,
  UnliftingFailure
}

/** The Haskell value a monomorphic parameter of a denotation reads from its constant
  * (`readKnownConstant`, `PlutusCore/Builtin/KnownType.hs:310-323`).
  */
private[core] trait Reader[A]:
  /** The parameter's type, which a constant of another type is reported against. */
  def expected: Signature.Type
  def read(c: Constant): Option[A]

private[core] object Reader:
  private def of[A](t: Signature.Type)(f: PartialFunction[Constant, A]): Reader[A] = new Reader[A]:
    val expected: Signature.Type = t
    private val lifted = f.lift
    def read(c: Constant): Option[A] = lifted(c)

  given Reader[BigInt] = of(Signature.Type.Integer) { case Constant.Integer(n) => n }
  given Reader[Bytes] = of(Signature.Type.ByteString) { case Constant.ByteString(b) => b }
  given Reader[Text] = of(Signature.Type.String) { case Constant.String(t) => t }
  given Reader[Boolean] = of(Signature.Type.Bool) { case Constant.Bool(b) => b }
  given Reader[Unit] = of(Signature.Type.Unit) { case Constant.Unit => () }
  given Reader[PlutusData] = of(Signature.Type.Data) { case Constant.Data(d) => d }

  // A list constant holds elements of its element type only (`Constant.List.of`), so the
  // collections below drop nothing.
  given dataList: Reader[Vector[PlutusData]] =
    of(Signature.Type.List(Signature.Type.Data)) { case Constant.List(DefaultUni.Data, items) =>
      items.collect { case Constant.Data(d) => d }
    }

  given dataPairList: Reader[Vector[(PlutusData, PlutusData)]] =
    of(Signature.Type.List(Signature.Type.Pair(Signature.Type.Data, Signature.Type.Data))) {
      case Constant.List(DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data), items) =>
        items.collect { case Constant.Pair(Constant.Data(k), Constant.Data(v)) => (k, v) }
    }

/** What a denotation gives back on success: a value of the machine and the log lines it emits. */
private[core] final case class Done[V](value: V, logs: Vector[String])

/** The arguments of one application as a denotation reads them, position by position. A denotation
  * takes them as the unlifting left them; it checks again what it reads (the type, and the
  * variant's bound on a monomorphic parameter), so that it is total and exact for any caller, with
  * the failure the unlifting would have given.
  *
  * @param bounds
  *   the variant's bounds on the builtin's arguments, by position ([[CoreUnlifting.bounds]])
  */
private[core] final class Reading[V](
    arity: Int,
    bounds: Map[Int, UnliftingBound],
    arguments: Vector[Argument[V]]
)(using val machine: MachineValue[V]):

  private def unlifting(errorClass: ErrorClass, cause: UnliftingCause): BuiltinFailure =
    BuiltinFailure.Unlifting(UnliftingFailure(errorClass, cause))

  private def argument(position: Int): Either[BuiltinFailure, Argument[V]] =
    if arguments.size != arity then
      Left(unlifting(ErrorClass.Structural, UnliftingCause.Arity(arity, arguments.size)))
    else
      arguments
        .lift(position)
        .toRight(unlifting(ErrorClass.Structural, UnliftingCause.Arity(arity, arguments.size)))

  /** The constant at `position`, of any type (`SomeConstant`). */
  def someConstant(position: Int): Either[BuiltinFailure, Constant] =
    argument(position).flatMap {
      case Argument.Con(c) => Right(c)
      case Argument.Opaque(v) =>
        machine
          .constantOf(v)
          .toRight(unlifting(ErrorClass.Structural, UnliftingCause.NotAConstant(position)))
    }

  /** The value at `position` as it is (`Opaque`). */
  def opaque(position: Int): Either[BuiltinFailure, V] =
    argument(position).map {
      case Argument.Con(c)    => machine.ofConstant(c)
      case Argument.Opaque(v) => v
    }

  /** The monomorphic argument at `position`, within the variant's bound on it. */
  def apply[A](position: Int)(using reader: Reader[A]): Either[BuiltinFailure, A] =
    someConstant(position).flatMap { c =>
      reader.read(c) match
        case None =>
          Left(
            unlifting(
              ErrorClass.Structural,
              UnliftingCause.WrongType(position, reader.expected, c.typeOf)
            )
          )
        case Some(a) =>
          bounds
            .get(position)
            .flatMap(CoreUnlifting.outside(_, position, c))
            .fold(Right(a))(cause => Left(unlifting(ErrorClass.Operational, cause)))
    }

  /** The result `c`, with no log line. */
  def constant(c: Constant): Done[V] = Done(machine.ofConstant(c), Vector.empty)

  /** The result `v`, an argument passed through, with no log line. */
  def value(v: V): Done[V] = Done(v, Vector.empty)

private[core] object Reading:
  def structural(cause: UnliftingCause): BuiltinFailure =
    BuiltinFailure.Unlifting(UnliftingFailure(ErrorClass.Structural, cause))

  def failed(failure: EvaluationFailure): BuiltinFailure = BuiltinFailure.Evaluation(failure)

  /** The log lines of a failure: the message of an evaluation failure (`fail` puts it in the logs,
    * `PlutusCore/Builtin/Result.hs:88-90`) or of an operational unlifting error
    * ([[CoreUnlifting.log]]).
    */
  def logs(failure: BuiltinFailure): Vector[String] = failure match
    case BuiltinFailure.Evaluation(f) => f.message.toVector
    case BuiltinFailure.Unlifting(f)  => CoreUnlifting.log(f).toVector

  def outcome[V](result: Either[BuiltinFailure, Done[V]]): Outcome[V] = result match
    case Right(Done(value, logs)) => Outcome.Success(value, logs)
    case Left(failure)            => Outcome.Failure(failure, logs(failure))

  /** The constant as a list, or the structural error of a list builtin given something else. */
  def list(c: Constant, position: Int): Either[BuiltinFailure, Constant.List] = c match
    case l @ Constant.List(_, _) => Right(l)
    case other => Left(structural(UnliftingCause.ExpectedList(position, other.typeOf)))

  def pair(c: Constant, position: Int): Either[BuiltinFailure, Constant.Pair] = c match
    case p @ Constant.Pair(_, _) => Right(p)
    case other => Left(structural(UnliftingCause.ExpectedPair(position, other.typeOf)))

  /** A list constant built from elements that have its element type by construction; the refusal of
    * [[Constant.List.of]] cannot happen and is reported as the mismatch it would be.
    */
  def listOf(elementType: DefaultUni, items: Vector[Constant]): Either[BuiltinFailure, Constant] =
    Constant.List
      .of(elementType, items)
      .left
      .map(m => structural(UnliftingCause.ElementMismatch(m.expected, m.found)))
