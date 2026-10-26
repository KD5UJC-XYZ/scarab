// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.data.{Chain, NonEmptyChain}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, LedgerFailure, PredicateFailure}

/** How the failure a rule raises divides into a defect, which ends the judgment with no verdict,
  * and the predicate failures of a refusal. A rule of the transaction raises both today; once it
  * records its predicate failures ([[Judge]]) it raises only defects.
  */
trait Refusal[Fail]:
  type Defect
  type Predicate

  def split(failure: Fail): Either[Defect, NonEmptyChain[Predicate]]

object Refusal:
  type Aux[Fail, D, P] = Refusal[Fail] { type Defect = D; type Predicate = P }

  /** A refused transaction holds its predicate failures; the other cases are defects. */
  given ledger: Aux[LedgerFailure, LedgerDefect, PredicateFailure] = new Refusal[LedgerFailure]:
    type Defect = LedgerDefect
    type Predicate = PredicateFailure
    def split(failure: LedgerFailure): Either[LedgerDefect, NonEmptyChain[PredicateFailure]] =
      failure match
        case LedgerFailure.Rejected(_, failures) => Right(failures)
        case defect: LedgerDefect                => Left(defect)

/** `applySTS` (`Extended.hs:592-622`) over the judgment interpreter [[RuleT]]: the predicate
  * failures of a refusal in the order the reference reports them, or the state after the
  * transition, or a defect.
  */
object Judgment:
  export RuleT.Policy

  /** The failures of a judgment in the order of the reference's list.
    *
    * The reference keeps each rule's failures in a list it prepends to: a predicate failure is put
    * in front (`Extended.hs:708`), and a sub-rule's list, built the same way, is put in front one
    * element at a time (`traverse_ (\a -> modify (first (a :)))`, `Extended.hs:720-722`), which
    * reverses it. The root's list is returned as it is (`Extended.hs:610-622`). So the root's
    * failures and blocks are reversed, a block one level down is in the order it was recorded, the
    * next level reversed again, and so on: the parity of the depth decides. For CERTS and DELEGS,
    * which recurse on the prefix of the certificates (`gamma :|> c`), this is the zigzag the
    * reference reports, not plain reversal.
    *
    * An explicit stack of pending blocks, each with the direction it is read in (ADR 0011): linear
    * in the number of recorded failures and blocks, constant in the JVM stack for any depth.
    *
    * @param root
    *   what the applied rule recorded ([[Trace]]), in the order it was recorded
    */
  def flatten[Rec](root: Chain[Trace[Rec]]): Chain[Rec] =
    def ordered(children: Chain[Trace[Rec]], reversed: Boolean): List[Trace[Rec]] =
      if reversed then children.reverseIterator.toList else children.toList

    @tailrec
    def go(pending: List[(List[Trace[Rec]], Boolean)], out: Chain[Rec]): Chain[Rec] =
      pending match
        case Nil              => out
        case (Nil, _) :: rest => go(rest, out)
        case ((next :: siblings), reversed) :: rest =>
          next match
            case Trace.Leaf(failure) => go((siblings, reversed) :: rest, out :+ failure)
            case Trace.Node(children) =>
              go((ordered(children, !reversed), !reversed) :: (siblings, reversed) :: rest, out)

    go(List((ordered(root, true), true)), Chain.nil)

  /** Runs a judged program under `policy`: a raised defect, or the refusal's predicate failures in
    * the reference's order, or the state and the value. A defect raised after failures were
    * recorded wins: the implementation has no verdict for the transaction.
    */
  def judge[Rec, Defect, S, A](program: RuleT[Rec, Defect, S, A], policy: Policy)(
      globals: Globals,
      state: S
  ): Either[Defect, Either[NonEmptyChain[Rec], (S, A)]] =
    program.judge(policy, globals, state) match
      case RuleT.Outcome.Raised(defect)    => Left(defect)
      case RuleT.Outcome.Refused(failures) => Right(Left(failures))
      case RuleT.Outcome.Passed(s, a)      => Right(Right((s, a)))

  /** Applies the judged `rule` under `policy`: a defect, or the predicate failures of the refusal
    * in the reference's order, or the state after the transition. A refusal a rule still raises
    * ends that rule only ([[Judge.trans]]); under `FirstFailure` it is the first failure.
    */
  def run[Env, S, Sig, Fail, D, P](rule: JudgedSts[Env, S, Sig, P, Fail], policy: Policy)(
      globals: Globals,
      env: Env,
      state: S,
      signal: Sig
  )(using refusal: Refusal.Aux[Fail, D, P]): Either[D, Either[NonEmptyChain[P], S]] =
    rule
      .transition[RuleT.Judged[P, Fail, S]](env, signal)
      .judge(policy, globals, state, refusal.split(_).toOption) match
      case RuleT.Outcome.Raised(raised)    => refusal.split(raised).map(Left(_))
      case RuleT.Outcome.Refused(failures) => Right(Left(failures))
      case RuleT.Outcome.Passed(after, _)  => Right(Right(after))
