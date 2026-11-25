// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, PlutusData, Signature, Text}
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

// `Reading`, `Reader` and `Done` at `929f794a6`, before #1411, kept as the oracle of CekApplicationSuite:
// the machine before #1411 runs with it, so that the comparison shares no code #1411 changed.

/** The Haskell value a monomorphic parameter of a denotation reads from its constant
  * (`readKnownConstant`, `PlutusCore/Builtin/KnownType.hs:310-323`, plutus 1.70.0.0).
  */
private[core] trait PresentReader[A]:
  /** The parameter's type, which a constant of another type is reported against. */
  def expected: Signature.Type
  def read(c: Constant): Option[A]

private[core] object PresentReader:
  private def of[A](t: Signature.Type)(f: PartialFunction[Constant, A]): PresentReader[A] =
    new PresentReader[A]:
      val expected: Signature.Type = t
      private val lifted = f.lift
      def read(c: Constant): Option[A] = lifted(c)

  given PresentReader[BigInt] = of(Signature.Type.Integer) { case Constant.Integer(n) => n }
  given PresentReader[Bytes] = of(Signature.Type.ByteString) { case Constant.ByteString(b) => b }
  given PresentReader[Text] = of(Signature.Type.String) { case Constant.String(t) => t }
  given PresentReader[Boolean] = of(Signature.Type.Bool) { case Constant.Bool(b) => b }
  given PresentReader[Unit] = of(Signature.Type.Unit) { case Constant.Unit => () }
  given PresentReader[PlutusData] = of(Signature.Type.Data) { case Constant.Data(d) => d }

  // A list of `Data` and a list of pairs of `Data` each have one form, which keeps the values
  // themselves (`Constant.List`), so these read them as they are, in constant time, as the
  // reference does (`readKnownConstant`, `KnownType.hs:310-323`, plutus 1.71.0.0 `261766ad`).
  given dataList: PresentReader[Vector[PlutusData]] =
    of(Signature.Type.List(Signature.Type.Data)) { case Constant.List.OfData(ds) => ds }

  given dataPairList: PresentReader[Vector[(PlutusData, PlutusData)]] =
    of(Signature.Type.List(Signature.Type.Pair(Signature.Type.Data, Signature.Type.Data))) {
      case Constant.List.OfDataPairs(es) => es
    }

/** What a denotation gives back on success: a value of the machine and the log lines it emits. */
private[core] final case class PresentDone[V](value: V, logs: Vector[String])

/** The arguments of one application as a denotation reads them, position by position. A denotation
  * takes them as the unlifting left them; it checks again what it reads (the type, and the
  * variant's bound on a monomorphic parameter), so that it is total and exact for any caller, with
  * the failure the unlifting would have given.
  *
  * @param bounds
  *   the variant's bounds on the builtin's arguments, by position ([[CoreUnlifting.bounds]])
  */
private[core] final class PresentReading[V](
    arity: Int,
    bounds: Map[Int, UnliftingBound],
    arguments: Vector[Argument[V]]
)(using val machine: MachineValue[V]):

  private def unlifting(errorClass: ErrorClass, cause: UnliftingCause): BuiltinFailure =
    BuiltinFailure.Unlifting(UnliftingFailure(errorClass, cause))

  private def argument(position: Int): Either[BuiltinFailure, Argument[V]] =
    if arguments.sizeIs != arity then
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
  def apply[A](position: Int)(using reader: PresentReader[A]): Either[BuiltinFailure, A] =
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
  def constant(c: Constant): PresentDone[V] = PresentDone(machine.ofConstant(c), Vector.empty)

  /** The result `v`, an argument passed through, with no log line. */
  def value(v: V): PresentDone[V] = PresentDone(v, Vector.empty)

private[core] object PresentReading:
  def structural(cause: UnliftingCause): BuiltinFailure =
    BuiltinFailure.Unlifting(UnliftingFailure(ErrorClass.Structural, cause))

  def failed(failure: EvaluationFailure): BuiltinFailure = BuiltinFailure.Evaluation(failure)

  /** The outcome of a denotation. An evaluation failure logs its message ([[Outcome.failed]]); an
    * unlifting error found again here logs as the unlifting's would ([[UnliftingFailure.message]]):
    * the message of an operational one, nothing for a structural one, which is what the shape
    * checks of the polymorphic builtins raise (`throwError` in `BuiltinResult`,
    * `PlutusCore/Builtin/Result.hs:198-220`, plutus 1.70.0.0).
    */
  def outcome[V](result: Either[BuiltinFailure, PresentDone[V]]): Outcome[V] = result match
    case Right(PresentDone(value, logs))    => Outcome.Success(value, logs)
    case Left(BuiltinFailure.Evaluation(f)) => Outcome.failed(f)
    case Left(failure @ BuiltinFailure.Unlifting(f)) =>
      Outcome.Failure(failure, f.message.toVector)

  /** The constant as a list, or the structural error of a list builtin given something else. */
  def list(c: Constant, position: Int): Either[BuiltinFailure, Constant.List] = c match
    case l: Constant.List => Right(l)
    case other            => Left(structural(UnliftingCause.ExpectedList(position, other.typeOf)))

  def pair(c: Constant, position: Int): Either[BuiltinFailure, Constant.Pair] = c match
    case p @ Constant.Pair(_, _) => Right(p)
    case other => Left(structural(UnliftingCause.ExpectedPair(position, other.typeOf)))
