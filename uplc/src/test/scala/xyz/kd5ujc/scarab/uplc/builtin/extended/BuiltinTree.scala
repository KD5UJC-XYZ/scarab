// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun}
import xyz.kd5ujc.scarab.uplc.builtin.{BuiltinFailure, Outcome}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreDenotations, CoreUnlifting, CoreVariant, Probe}

/** A value of a tree of builtin applications: a constant, a builtin waiting for forces or
  * arguments, or a value that is not a constant (a branch of `ifThenElse` that is not one).
  */
enum TreeValue:
  case Con(constant: Constant)
  case Partial(function: DefaultFun, forces: Int, arguments: Vector[Probe])
  case Opaque(probe: Probe)

/** Why a tree stops before a value. */
enum TreeStop:
  /** The builtin or the machine fails: the reference's "evaluation failure". */
  case Failed(failure: TreeFailure)

  /** The tree needs the machine (a lambda, `delay`, a variable, `constr`, `case`). */
  case NeedsMachine(form: String)

enum TreeFailure:
  case Builtin(function: DefaultFun, failure: BuiltinFailure)
  case ErrorTerm
  case NotAFunction
  case UnexpectedForce(function: DefaultFun)
  case UnexpectedArgument(function: DefaultFun)

/** Evaluates a tree of saturated builtin applications over constants as the CEK machine does,
  * function before argument and left to right, each builtin through the unlifting and then the
  * denotation ([[CoreUnlifting]], [[CoreDenotations]], [[ExtendedDenotations]]). No budget is
  * spent. It stands in for the machine until there is one: the cases it runs are those whose
  * result needs nothing else.
  */
final class BuiltinTree(variant: CoreVariant):
  private val unlifting = CoreUnlifting(variant)
  private val denotations = CoreDenotations(variant) ++ ExtendedDenotations(variant)

  def eval(term: CorpusTerm): Either[TreeStop, TreeValue] = term match
    case CorpusTerm.Con(c)            => Right(TreeValue.Con(c))
    case CorpusTerm.Builtin(f)        => saturate(f, 0, Vector.empty)
    case CorpusTerm.Error             => Left(TreeStop.Failed(TreeFailure.ErrorTerm))
    case CorpusTerm.Unsupported(form) => Left(TreeStop.NeedsMachine(form))
    case CorpusTerm.Force(t) =>
      eval(t).flatMap {
        case TreeValue.Partial(f, forces, args) if forces < f.forces && args.isEmpty =>
          saturate(f, forces + 1, args)
        case TreeValue.Partial(f, _, _) => Left(TreeStop.Failed(TreeFailure.UnexpectedForce(f)))
        case _                          => Left(TreeStop.Failed(TreeFailure.NotAFunction))
      }
    case CorpusTerm.Apply(t, a) =>
      eval(t).flatMap(f => eval(a).flatMap(apply(f, _)))

  private def probe(v: TreeValue): Probe = v match
    case TreeValue.Con(c)    => Probe.Con(c)
    case TreeValue.Opaque(p) => p
    case TreeValue.Partial(f, _, _) => Probe.Lambda(f.name)

  private def apply(f: TreeValue, a: TreeValue): Either[TreeStop, TreeValue] = f match
    case TreeValue.Partial(fun, forces, args) if forces == fun.forces && args.size < fun.arity =>
      saturate(fun, forces, args :+ probe(a))
    case TreeValue.Partial(fun, _, _) => Left(TreeStop.Failed(TreeFailure.UnexpectedArgument(fun)))
    case _                            => Left(TreeStop.Failed(TreeFailure.NotAFunction))

  private def saturate(
      f: DefaultFun,
      forces: Int,
      args: Vector[Probe]
  ): Either[TreeStop, TreeValue] =
    if forces < f.forces || args.size < f.arity then Right(TreeValue.Partial(f, forces, args))
    else
      unlifting(f, args) match
        case Left(failure) =>
          Left(TreeStop.Failed(TreeFailure.Builtin(f, BuiltinFailure.Unlifting(failure))))
        case Right(unlifted) =>
          denotations.get(f) match
            case None => Left(TreeStop.NeedsMachine(s"no denotation for $f"))
            case Some(d) =>
              d(unlifted) match
                case Outcome.Success(Probe.Con(c), _) => Right(TreeValue.Con(c))
                case Outcome.Success(other, _)        => Right(TreeValue.Opaque(other))
                case Outcome.Failure(failure, _) =>
                  Left(TreeStop.Failed(TreeFailure.Builtin(f, failure)))
