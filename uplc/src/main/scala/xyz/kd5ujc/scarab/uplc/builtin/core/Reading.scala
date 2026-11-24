// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import scala.annotation.tailrec
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

/** The Haskell value a monomorphic parameter of a denotation reads from its constant
  * (`readKnownConstant`, `PlutusCore/Builtin/KnownType.hs:310-323`, plutus 1.70.0.0).
  */
private[core] trait Reader[A]:
  /** The parameter's type, which a constant of another type is reported against. */
  def expected: Signature.Type

  /** The value of `c`, or the structural error of a constant of another type at `position`. */
  def read(c: Constant, position: Int): Either[BuiltinFailure, A]

private[core] object Reader:
  private def of[A](t: Signature.Type)(f: Constant => Option[A]): Reader[A] = new Reader[A]:
    val expected: Signature.Type = t
    def read(c: Constant, position: Int): Either[BuiltinFailure, A] = f(c) match
      case Some(a) => Right(a)
      case None    => Reading.mismatch(t, position, c)

  given Reader[BigInt] = new Reader[BigInt]:
    val expected: Signature.Type = Signature.Type.Integer
    def read(c: Constant, position: Int): Either[BuiltinFailure, BigInt] = c match
      case Constant.Integer(n) => Right(n)
      case other               => Reading.mismatch(expected, position, other)

  given Reader[Bytes] = new Reader[Bytes]:
    val expected: Signature.Type = Signature.Type.ByteString
    def read(c: Constant, position: Int): Either[BuiltinFailure, Bytes] = c match
      case Constant.ByteString(b) => Right(b)
      case other                  => Reading.mismatch(expected, position, other)

  given Reader[Text] = new Reader[Text]:
    val expected: Signature.Type = Signature.Type.String
    def read(c: Constant, position: Int): Either[BuiltinFailure, Text] = c match
      case Constant.String(t) => Right(t)
      case other              => Reading.mismatch(expected, position, other)

  given Reader[Boolean] = new Reader[Boolean]:
    val expected: Signature.Type = Signature.Type.Bool
    def read(c: Constant, position: Int): Either[BuiltinFailure, Boolean] = c match
      case Constant.Bool(b) => Right(b)
      case other            => Reading.mismatch(expected, position, other)

  given Reader[Unit] = new Reader[Unit]:
    val expected: Signature.Type = Signature.Type.Unit
    def read(c: Constant, position: Int): Either[BuiltinFailure, Unit] = c match
      case Constant.Unit => Right(())
      case other         => Reading.mismatch(expected, position, other)

  given Reader[PlutusData] = new Reader[PlutusData]:
    val expected: Signature.Type = Signature.Type.Data
    def read(c: Constant, position: Int): Either[BuiltinFailure, PlutusData] = c match
      case Constant.Data(d) => Right(d)
      case other            => Reading.mismatch(expected, position, other)

  // A list of `Data` and a list of pairs of `Data` each have one form, which keeps the values
  // themselves (`Constant.List`), so these read them as they are, in constant time, as the
  // reference does (`readKnownConstant`, `KnownType.hs:310-323`, plutus 1.71.0.0 `261766ad`).
  given dataList: Reader[Vector[PlutusData]] =
    of(Signature.Type.List(Signature.Type.Data)) {
      case Constant.List.OfData(ds) => Some(ds)
      case _                        => None
    }

  given dataPairList: Reader[Vector[(PlutusData, PlutusData)]] =
    of(Signature.Type.List(Signature.Type.Pair(Signature.Type.Data, Signature.Type.Data))) {
      case Constant.List.OfDataPairs(es) => Some(es)
      case _                             => None
    }

/** The arguments of one application as a denotation reads them, position by position, held as the
  * machine holds them, the last one first. A denotation takes them as the unlifting left them; it
  * checks again what it reads (the type, and the variant's bound on a monomorphic parameter), so
  * that it is total and exact for any caller, with the failure the unlifting would have given.
  *
  * @param bounds
  *   the variant's bounds on the builtin's arguments, by position ([[CoreUnlifting.bounds]])
  */
private[core] final class Reading[V](
    arity: Int,
    bounds: Map[Int, UnliftingBound],
    arguments: List[V]
)(using val machine: MachineValue[V]):

  private def unlifting(errorClass: ErrorClass, cause: UnliftingCause): BuiltinFailure =
    BuiltinFailure.Unlifting(UnliftingFailure(errorClass, cause))

  private def arityFailure: BuiltinFailure =
    unlifting(ErrorClass.Structural, UnliftingCause.Arity(arity, arguments.size))

  /** The value at `position`, `arity - 1 - position` from the head of the list. */
  private def argument(position: Int): Either[BuiltinFailure, V] =
    @tailrec def at(rest: List[V], index: Int): Either[BuiltinFailure, V] = rest match
      case Nil              => Left(arityFailure)
      case value :: earlier => if index == 0 then Right(value) else at(earlier, index - 1)
    if arguments.sizeIs != arity || position < 0 then Left(arityFailure)
    else at(arguments, arity - 1 - position)

  /** The constant at `position`, of any type (`SomeConstant`). */
  def someConstant(position: Int): Either[BuiltinFailure, Constant] = argument(position) match
    case Right(value) =>
      machine.asConstant(value) match
        case c: Constant => Right(c)
        case MachineValue.NoConstant =>
          Left(unlifting(ErrorClass.Structural, UnliftingCause.NotAConstant(position)))
    case Left(failure) => Left(failure)

  /** The value at `position` as it is (`Opaque`). */
  def opaque(position: Int): Either[BuiltinFailure, V] = argument(position)

  /** The monomorphic argument at `position`, within the variant's bound on it. */
  def apply[A](position: Int)(using reader: Reader[A]): Either[BuiltinFailure, A] =
    someConstant(position) match
      case Left(failure) => Left(failure)
      case Right(c) =>
        val read = reader.read(c, position)
        bounds.getOrElse[UnliftingBound | Reading.Unbounded.type](position, Reading.Unbounded) match
          case Reading.Unbounded => read
          case bound: UnliftingBound =>
            CoreUnlifting.outside(bound, position, c) match
              case None => read
              case Some(cause) =>
                read match
                  case Right(_)      => Left(unlifting(ErrorClass.Operational, cause))
                  case Left(failure) => Left(failure)

  /** The result `c`, with no log line. */
  def constant(c: Constant): Outcome[V] = Outcome.Success(machine.ofConstant(c), Vector.empty)

  /** The result `v`, an argument passed through, with no log line. */
  def value(v: V): Outcome[V] = Outcome.Success(v, Vector.empty)

  /** The result of a builtin that computes a constant. */
  def result(computed: Either[BuiltinFailure, Constant]): Either[BuiltinFailure, Outcome[V]] =
    computed match
      case Right(c)      => Right(constant(c))
      case Left(failure) => Left(failure)

private[core] object Reading:
  /** A position the variant does not bound. */
  case object Unbounded

  /** The arguments of `arguments` held as the machine holds them, the last one first: a constant as
    * the value the machine makes of it, which reads back as that constant.
    */
  def held[V](arguments: Vector[Argument[V]])(using mv: MachineValue[V]): List[V] =
    arguments.foldLeft(List.empty[V]) {
      case (done, Argument.Con(c))    => mv.ofConstant(c) :: done
      case (done, Argument.Opaque(v)) => v :: done
    }

  def structural(cause: UnliftingCause): BuiltinFailure =
    BuiltinFailure.Unlifting(UnliftingFailure(ErrorClass.Structural, cause))

  /** The structural error of a constant `c` at `position` that is not of the type `expected`. */
  def mismatch(
      expected: Signature.Type,
      position: Int,
      c: Constant
  ): Either[BuiltinFailure, Nothing] =
    Left(structural(UnliftingCause.WrongType(position, expected, c.typeOf)))

  def failed(failure: EvaluationFailure): BuiltinFailure = BuiltinFailure.Evaluation(failure)

  /** The outcome of a denotation. An evaluation failure logs its message ([[Outcome.failed]]); an
    * unlifting error found again here logs as the unlifting's would ([[UnliftingFailure.message]]):
    * the message of an operational one, nothing for a structural one, which is what the shape
    * checks of the polymorphic builtins raise (`throwError` in `BuiltinResult`,
    * `PlutusCore/Builtin/Result.hs:198-220`, plutus 1.70.0.0).
    */
  def outcome[V](result: Either[BuiltinFailure, Outcome[V]]): Outcome[V] = result match
    case Right(done)                        => done
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
