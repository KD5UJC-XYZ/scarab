// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.ledger

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.{Term, TermF, Word64}

/** Why the scope check refuses a term: the reference's `FreeVariableError`
  * (`PlutusCore/DeBruijn/Internal.hs:252-256`, plutus 1.70.0.0 `507cab8c`). Its other case,
  * `FreeUnique`, is about named terms, which the ledger never checks.
  */
enum FreeVariableError:
  /** `FreeIndex`: a variable no enclosing `lam` binds, or index 0. */
  case FreeIndex(index: Word64)

/** The scope check that `mkTermToEvaluate` makes of the applied term before the machine runs
  * (`PlutusLedgerApi/Common/Eval.hs:129-130`): `checkScope`
  * (`UntypedPlutusCore/Check/Scope.hs:28-55`, plutus 1.70.0.0 `507cab8c`).
  *
  * It descends into `lam`, `apply`, `force` and `delay` only (`Scope.hs:38-54`); every other term
  * passes as it is (`Scope.hs:55`). So the fields of a `constr` and the whole of a `case`, its
  * scrutinee included, are not checked: a free variable there is found by the machine only if it is
  * evaluated (`OpenTermEvaluatedMachineError`, `Cek/Internal.hs:1082-1088`).
  *
  * The reference also requires every binder to carry index 0 (`Scope.hs:45-50`). A decoded binder
  * always does: its Flat encoding is empty and decodes as `deBruijnInitIndex`
  * (`PlutusCore/FlatInstances.hs:375-378,409-412`), and [[TermF.LamAbs]] keeps no binder.
  */
object Scope:

  /** `checkScope`: the first free index in the reference's order (pre-order, the function before
    * the argument, `Scope.hs:52`), on the heap, so as deep as the term.
    */
  def check(term: Term): Either[FreeVariableError, Unit] =
    // the term to check and the number of `lam` around it (the reference's `lvl`, a `Word`)
    @tailrec def go(pending: List[(Term, Long)]): Either[FreeVariableError, Unit] =
      pending match
        case Nil => Right(())
        case (t, level) :: rest =>
          t.unfix match
            // `i > 0 && fromIntegral i <= lvl`, unsigned (`Scope.hs:38-44`)
            case TermF.Var(name) =>
              val i = name.index.bits
              if i != 0L && java.lang.Long.compareUnsigned(i, level) <= 0 then go(rest)
              else Left(FreeVariableError.FreeIndex(name.index))
            case TermF.LamAbs(body) => go((body, level + 1) :: rest)
            case TermF.Apply(function, argument) =>
              go((function, level) :: (argument, level) :: rest)
            case TermF.Force(body)                                  => go((body, level) :: rest)
            case TermF.Delay(body)                                  => go((body, level) :: rest)
            case TermF.Constant(_) | TermF.Builtin(_) | TermF.Error => go(rest)
            case TermF.Constr(_, _) | TermF.Case(_, _)              => go(rest)
    go((term, 0L) :: Nil)
