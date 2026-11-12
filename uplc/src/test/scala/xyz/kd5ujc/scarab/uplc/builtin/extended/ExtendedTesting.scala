// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni}
import xyz.kd5ujc.scarab.uplc.builtin.{BuiltinFailure, EvaluationFailure, Outcome}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreTesting, CoreUnlifting, CoreVariant, Probe}

/** Applying the builtins of this package as the machine does, without the costing: the unlifting of
  * [[CoreUnlifting]], then the denotation.
  */
object ExtendedTesting:
  export CoreTesting.{bool, bytes, con, data, hexBytes, int, lambda, list}

  def apply(variant: CoreVariant, function: DefaultFun, arguments: Probe*): Outcome[Probe] =
    CoreUnlifting(variant)(function, arguments.toVector) match
      case Left(failure) =>
        Outcome.Failure(BuiltinFailure.Unlifting(failure), failure.message.toVector)
      case Right(unlifted) =>
        ExtendedDenotations(variant)
          .get(function)
          .fold(sys.error(s"$function has no extended denotation"))(_(unlifted))

  /** Under variant E, the corpus's. */
  def e(function: DefaultFun, arguments: Probe*): Outcome[Probe] =
    apply(CoreVariant.E, function, arguments*)

  def result(outcome: Outcome[Probe]): Either[BuiltinFailure, Constant] =
    CoreTesting.result(outcome)

  def value(outcome: Outcome[Probe]): Constant =
    result(outcome).fold(f => sys.error(s"expected a value, got $f"), identity)

  def bytesOf(outcome: Outcome[Probe]): Bytes = value(outcome) match
    case Constant.ByteString(b) => b
    case other                  => sys.error(s"expected a byte string, got $other")

  def integerOf(outcome: Outcome[Probe]): BigInt = value(outcome) match
    case Constant.Integer(i) => i
    case other               => sys.error(s"expected an integer, got $other")

  def boolOf(outcome: Outcome[Probe]): Boolean = value(outcome) match
    case Constant.Bool(b) => b
    case other            => sys.error(s"expected a bool, got $other")

  /** The evaluation failure and the log lines of a failing outcome. */
  def failureOf(outcome: Outcome[Probe]): (EvaluationFailure, Vector[String]) = outcome match
    case Outcome.Failure(BuiltinFailure.Evaluation(f), logs) => (f, logs)
    case other => sys.error(s"expected an evaluation failure, got $other")

  def integers(xs: BigInt*): Probe = con(list(DefaultUni.Integer, xs.map(Constant.Integer(_))*))
