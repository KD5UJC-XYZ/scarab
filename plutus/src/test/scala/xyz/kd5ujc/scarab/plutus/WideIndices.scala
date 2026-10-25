// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.data.State
import cats.syntax.all.*
import scalus.uplc.Term
import xyz.kd5ujc.scarab.codec.TreeFold

/** Scalus 1.3.0 stores a de Bruijn index in an Int, and its flat decoder narrows a wider Word64. A
  * wider index is necessarily unbound in any ledger script, but narrowing it can capture a
  * variable. [[FlatScan.decode]] keeps those variables explicitly open (index -1); this is the same
  * repair of a term Scalus decoded, which the parity check against Scalus's decoder applies before
  * comparing. Scalus's lookupVarName rejects a negative index before indexing the environment
  * (Cek.scala:1465-1478 in the pinned 1.3.0 sources).
  */
private[plutus] object WideIndices:
  def repair(root: Term, wide: Set[Int]): Term =
    if wide.isEmpty then root
    else
      type Rebuild = State[Int, Term]
      import TreeFold.Step
      def branch(children: Vector[Term])(
          build: Vector[Term] => Term
      ): Step[Term, Rebuild, Nothing] =
        Step.Branch(children, done => Right(done.sequence.map(build)))
      val rebuilt = TreeFold.fold[Term, Rebuild](root) {
        case Term.Var(name, annotation) =>
          Step.Leaf(State { ordinal =>
            val variable =
              if wide(ordinal) then Term.Var(name.copy(index = -1), annotation)
              else Term.Var(name, annotation)
            (ordinal + 1, variable)
          })
        // Each fixed arity below is the arity of the children handed to TreeFold.
        case Term.LamAbs(name, body, annotation) =>
          branch(Vector(body))(ts => Term.LamAbs(name, ts(0), annotation))
        case Term.Apply(function, argument, annotation) =>
          branch(Vector(function, argument))(ts => Term.Apply(ts(0), ts(1), annotation))
        case Term.Force(body, annotation) =>
          branch(Vector(body))(ts => Term.Force(ts(0), annotation))
        case Term.Delay(body, annotation) =>
          branch(Vector(body))(ts => Term.Delay(ts(0), annotation))
        case Term.Constr(tag, fields, annotation) =>
          branch(fields.toVector)(ts => Term.Constr(tag, ts.toList, annotation))
        case Term.Case(scrutinee, branches, annotation) =>
          branch(scrutinee +: branches.toVector)(ts => Term.Case(ts(0), ts.tail.toList, annotation))
        case leaf => Step.Leaf(State.pure(leaf))
      }
      rebuilt.runA(0).value
