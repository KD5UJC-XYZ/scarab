// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Applicative, Functor, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Handle, Local, Stateful}
import scala.annotation.tailrec

/** The interpreter of the rule algebras ([[Sts]]): a pure program over the constants, a state, the
  * predicate failures it records and the failure it raises, run by a loop with an explicit,
  * type-aligned stack. It implements the capabilities of `Kleisli[StateT[Either[Fail, *], S, *],
  * Globals, *]` and the judgment of the reference's `applyRuleInternal` (cardano-ledger `f649f975`,
  * `libs/small-steps/src/Control/State/Transition/Extended.hs:668-730`) without nesting those
  * interpreters. The design, reference-interpreter comparisons and measurements are recorded in
  * `docs/design/rule-interpreter-as-built.md`.
  *
  * Stack safety is the point of the shape: a rule sequences a block's transactions, a transaction's
  * certificates, an epoch's pools, and a function that calls the next step from its continuation
  * grows the JVM stack with the length of the sequence (the preprod restart that died in
  * `Phase2.loop` did exactly that). A [[Prog]] is data: `flatMap` and the handlers build nodes, and
  * [[RuleT.judge]] walks them with a type-aligned stack of [[Frame]]s, in constant JVM stack for
  * any nesting, to the right (`go.flatMap(_ => go)`), to the left
  * (`foldLeft(unit)(_.flatMap(...))`), under handlers and under judgment nests. `tailRecM` is
  * therefore the plain recursion, and `traverse_`, `traverse` and `foldM` of cats are safe over it.
  *
  * The machine is typed end to end (AUD-14): the frames form a type-aligned sequence
  * `Frames[Rec, Fail, S, From, To]`, so a continuation always receives exactly the result type of
  * the program before it — across recovery, scoped globals and nests — with no cast and no `Any`
  * -typed value channel (GADT pattern matching is the compiler-enforced proof).
  *
  * The instances are the capabilities of D4: `Local` (so `Ask`) over [[Globals]], `Stateful` over
  * the state, `Handle` over the raised failure (`Raise` and recovery with `Handle.allow`) and
  * [[Judge]] over the recorded predicate failures `Rec`. A program that records nothing is a
  * `RuleT[Nothing, Fail, S, A]` ([[RuleT.Of]]), the monad every rule runs in through [[Sts.apply]].
  */
opaque type RuleT[Rec, Fail, S, A] = Prog[Rec, Fail, S, A]

/** The program: a value, a step of the interpreter's own (reads and writes the state, may raise), a
  * sequence, a recovery from a raised failure, a change of the constants for a sub-program, the
  * record of predicate failures (`Predicate`, `Extended.hs:704-709`), the read of the failing flag
  * (`isFailing`, `Extended.hs:690-697`) or the boundary of a sub-rule (`SubTrans`,
  * `Extended.hs:713-722`).
  */
private[rules] enum Prog[Rec, Fail, S, A]:
  case Pure(value: A)
  case Step(run: (Globals, S) => Either[Fail, (S, A)])
  case Bind[Rec, Fail, S, X, A](from: Prog[Rec, Fail, S, X], next: X => Prog[Rec, Fail, S, A])
      extends Prog[Rec, Fail, S, A]
  case Recover(program: Prog[Rec, Fail, S, A], handler: Fail => Prog[Rec, Fail, S, A])
  case Scoped(program: Prog[Rec, Fail, S, A], change: Globals => Globals)
  case Record[Rec, Fail, S](failures: Chain[Rec]) extends Prog[Rec, Fail, S, Unit]
  case Failing[Rec, Fail, S]() extends Prog[Rec, Fail, S, Boolean]
  case Nest(program: Prog[Rec, Fail, S, A], resume: Option[A])

/** The rest of the computation with a hole of type `X` and a final result of type `A`: a
  * type-aligned sequence of [[Frame]]s (`Done` closes the sequence, `Cons` extends it by one step
  * whose input type is the previous hole). Every continuation in the machine is therefore applied
  * to exactly the result type of the program before it.
  */
private[rules] enum Frames[Rec, Fail, S, X, A]:
  case Done[Rec, Fail, S, A]() extends Frames[Rec, Fail, S, A, A]
  case Cons[Rec, Fail, S, X, Y, A](
      head: Frame[Rec, Fail, S, X, Y],
      tail: Frames[Rec, Fail, S, Y, A]
  ) extends Frames[Rec, Fail, S, X, A]

/** What is left to do when the program in hand has a value of type `X`, or raises. */
private[rules] enum Frame[Rec, Fail, S, X, Y]:
  /** Feed the value to the continuation. */
  case Next[Rec, Fail, S, X, Y](next: X => Prog[Rec, Fail, S, Y]) extends Frame[Rec, Fail, S, X, Y]

  /** A recovery: a raised failure resumes at `handler`, from the state, the recorded failures and
    * the failing flag the protected program started from (what the failed program did is lost, as
    * in Haskell, where a raise aborts the judgment); a value passes through.
    */
  case Handler[Rec, Fail, S, A](
      handler: Fail => Prog[Rec, Fail, S, A],
      saved: S,
      trace: Chain[Trace[Rec]],
      failing: Boolean
  ) extends Frame[Rec, Fail, S, A, A]

  /** Leave a scope: the constants as they were outside it. */
  case Restore[Rec, Fail, S, A](globals: Globals) extends Frame[Rec, Fail, S, A, A]

  /** Leave a nest: the failures recorded inside it become one block of the enclosing rule's, after
    * those the enclosing rule had recorded before it (`parent`); a nest that recorded nothing adds
    * nothing. A raise leaves it the same way. A nest with a `resume` value is a sub-rule that still
    * raises its predicate failures: a raised refusal ends the sub-rule only, its failures closing
    * the block, and the enclosing rule continues with `resume` (see [[Judge.trans]]).
    */
  case Unnest[Rec, Fail, S, A](parent: Chain[Trace[Rec]], resume: Option[A])
      extends Frame[Rec, Fail, S, A, A]

/** The failures a judgment recorded, in the order they were recorded: a predicate failure of the
  * rule itself, or the block of a sub-rule run through a nest ([[Judge.nest]]).
  * [[Judgment.flatten]] turns it into the order the reference reports.
  */
enum Trace[+Rec]:
  case Leaf(failure: Rec)
  case Node(children: Chain[Trace[Rec]])

object RuleT:
  /** The monad of a rule that records predicate failures `Rec`, raises `Fail` and changes `S`. */
  type Judged[Rec, Fail, S] = [A] =>> RuleT[Rec, Fail, S, A]

  /** The monad of a failure and a state, for the rules to be instantiated at: nothing is recorded,
    * every failure is raised.
    */
  type Of[Fail, S] = [A] =>> RuleT[Nothing, Fail, S, A]

  /** How a judgment treats a recorded predicate failure.
    *
    *   - `Accumulate`: as the reference does: recorded and the judgment continues
    *     (`Extended.hs:708`); the failures are reported together at the end.
    *   - `FirstFailure`: the first recorded failure ends the judgment, as a raise does. A refusal
    *     under one policy is a refusal under the other: a judgment that records nothing is the same
    *     run under both, and the first failure `FirstFailure` reports is the first the reference
    *     records.
    */
  enum Policy:
    case Accumulate, FirstFailure

  /** How a judgment ended: a raised failure, a refusal with the failures it recorded in the order
    * the reference reports them ([[Judgment.flatten]]), or the state and the value.
    */
  enum Outcome[+Rec, +Fail, +S, +A]:
    case Raised(failure: Fail)
    case Refused(failures: NonEmptyChain[Rec])
    case Passed(state: S, value: A)

  def pure[Rec, Fail, S, A](a: A): RuleT[Rec, Fail, S, A] = Prog.Pure(a)

  extension [Rec, Fail, S, A](rule: RuleT[Rec, Fail, S, A])
    /** Runs the rule under `policy`: how it ended. A raised failure not recovered ends it whatever
      * was recorded before (the reference has no raise; this is the defect that leaves no verdict),
      * unless `absorb` takes it for the predicate failures of a refusal and a sub-rule boundary
      * ([[Judge.trans]]) is there to end: then it ends that sub-rule only.
      */
    def judge(
        policy: Policy,
        globals: Globals,
        state: S,
        absorb: Fail => Option[NonEmptyChain[Rec]] = (_: Fail) => None
    ): Outcome[Rec, Fail, S, A] =
      val first = policy == Policy.FirstFailure
      // One loop, so that it is a tail call: the machine is either running a program or unwinding
      // a raise (dropping the continuations and closing the nests up to the nearest handler,
      // restoring the constants of the scopes it leaves, and resuming there; with no handler left
      // the run ends). A sub-rule boundary with a `resume` value takes a refusal `absorb` accepts:
      // its failures are recorded at the end of the sub-rule's block and the parent resumes.
      // `trace` holds what the innermost rule recorded so far, `failing` whether anything was
      // recorded anywhere in the judgment (the flag of `Extended.hs:690-697`, which a nest
      // inherits from its parent and passes on). The frames are type-aligned: `go` is called with
      // the exact type the hole was fed, so every transition is compiler-checked to connect the
      // result of one step to the continuation of the next.
      @tailrec
      def go[X](
          current: Either[Fail, Prog[Rec, Fail, S, X]],
          g: Globals,
          s: S,
          trace: Chain[Trace[Rec]],
          failing: Boolean,
          frames: Frames[Rec, Fail, S, X, A]
      ): Outcome[Rec, Fail, S, A] =
        current match
          case Left(e) =>
            frames match
              case Frames.Done() =>
                absorb(e) match
                  case Some(failures) if first => Outcome.Refused(NonEmptyChain.one(failures.head))
                  // the root's failures come out reversed, its last first (Judgment.flatten)
                  case Some(failures) =>
                    Outcome.Refused(failures.reverse.appendChain(Judgment.flatten(trace)))
                  case None => Outcome.Raised(e)
              case Frames.Cons(head, tail) =>
                head match
                  case Frame.Next(_)        => go(Left(e), g, s, trace, failing, tail)
                  case Frame.Restore(outer) => go(Left(e), outer, s, trace, failing, tail)
                  case Frame.Unnest(parent, resume) =>
                    resume.flatMap(a => absorb(e).map(a -> _)) match
                      case Some((_, failures)) if first =>
                        Outcome.Refused(NonEmptyChain.one(failures.head))
                      case Some((a, failures)) =>
                        val block = trace ++ failures.toChain.map(Trace.Leaf(_))
                        go(Right(Prog.Pure(a)), g, s, parent :+ Trace.Node(block), true, tail)
                      case None => go(Left(e), g, s, closed(parent, trace), failing, tail)
                  case Frame.Handler(handler, saved, before, wasFailing) =>
                    go(Right(handler(e)), g, saved, before, wasFailing, tail)
          case Right(program) =>
            program match
              case Prog.Pure(a) =>
                frames match
                  case Frames.Done() =>
                    if trace.isEmpty then Outcome.Passed(s, a)
                    else
                      NonEmptyChain
                        .fromChain(Judgment.flatten(trace))
                        .fold(Outcome.Passed(s, a))(Outcome.Refused(_))
                  case Frames.Cons(head, tail) =>
                    head match
                      case Frame.Next(next) => go(Right(next(a)), g, s, trace, failing, tail)
                      case Frame.Handler(_, _, _, _) =>
                        go(Right(Prog.Pure(a)), g, s, trace, failing, tail)
                      case Frame.Restore(outer) =>
                        go(Right(Prog.Pure(a)), outer, s, trace, failing, tail)
                      case Frame.Unnest(parent, _) =>
                        go(Right(Prog.Pure(a)), g, s, closed(parent, trace), failing, tail)
              case Prog.Step(step) =>
                step(g, s) match
                  case Right((s1, a)) => go(Right(Prog.Pure(a)), g, s1, trace, failing, frames)
                  case Left(e)        => go(Left(e), g, s, trace, failing, frames)
              case Prog.Bind(from, next) =>
                go(Right(from), g, s, trace, failing, Frames.Cons(Frame.Next(next), frames))
              case Prog.Recover(program, handler) =>
                val frame = Frame.Handler(handler, s, trace, failing)
                go(Right(program), g, s, trace, failing, Frames.Cons(frame, frames))
              case Prog.Scoped(program, change) =>
                go(
                  Right(program),
                  change(g),
                  s,
                  trace,
                  failing,
                  Frames.Cons(Frame.Restore(g), frames)
                )
              case Prog.Record(failures) =>
                failures.uncons match
                  case None => go(Right(Prog.Pure(())), g, s, trace, failing, frames)
                  case Some((head, _)) if first =>
                    Outcome.Refused(NonEmptyChain.one(head))
                  case Some(_) =>
                    val recorded = trace ++ failures.map(Trace.Leaf(_))
                    go(Right(Prog.Pure(())), g, s, recorded, true, frames)
              case Prog.Failing() =>
                go(Right(Prog.Pure(failing)), g, s, trace, failing, frames)
              case Prog.Nest(program, resume) =>
                val frame = Frame.Unnest[Rec, Fail, S, X](trace, resume)
                go(Right(program), g, s, Chain.nil, failing, Frames.Cons(frame, frames))

      go(Right(rule), globals, state, Chain.nil, false, Frames.Done[Rec, Fail, S, A]())

  /** The trace of the enclosing rule once a nest closes: its block after what the rule had recorded
    * before it, and nothing for a nest that recorded nothing.
    */
  private def closed[Rec](parent: Chain[Trace[Rec]], block: Chain[Trace[Rec]]): Chain[Trace[Rec]] =
    if block.isEmpty then parent else parent :+ Trace.Node(block)

  extension [Fail, S, A](rule: RuleT[Nothing, Fail, S, A])
    /** Runs a rule that records nothing: the state and the result, or the first failure not
      * recovered.
      */
    def run(globals: Globals, state: S): Either[Fail, (S, A)] =
      rule.judge(Policy.FirstFailure, globals, state) match
        case Outcome.Passed(s, a) => Right((s, a))
        case Outcome.Raised(e)    => Left(e)
        // Nothing is recorded, so a refusal holds no value: its head is of type `Nothing`.
        case Outcome.Refused(failures) => Left(failures.head)

  private def step[Rec, Fail, S, A](
      f: (Globals, S) => Either[Fail, (S, A)]
  ): RuleT[Rec, Fail, S, A] =
    Prog.Step(f)

  given monad[Rec, Fail, S]: Monad[Judged[Rec, Fail, S]] = new Monad[Judged[Rec, Fail, S]]:
    def pure[A](a: A): RuleT[Rec, Fail, S, A] = Prog.Pure(a)

    def flatMap[A, B](fa: RuleT[Rec, Fail, S, A])(
        f: A => RuleT[Rec, Fail, S, B]
    ): RuleT[Rec, Fail, S, B] =
      Prog.Bind(fa, f)

    // The program is data, so the recursion is not on the stack.
    def tailRecM[A, B](a: A)(f: A => RuleT[Rec, Fail, S, Either[A, B]]): RuleT[Rec, Fail, S, B] =
      Prog.Bind(
        f(a),
        {
          case Left(next) => tailRecM(next)(f)
          case Right(b)   => Prog.Pure(b)
        }
      )

  given local[Rec, Fail, S]: Local[Judged[Rec, Fail, S], Globals] =
    new Local[Judged[Rec, Fail, S], Globals]:
      val applicative: Applicative[Judged[Rec, Fail, S]] = monad
      def ask[E2 >: Globals]: RuleT[Rec, Fail, S, E2] = step((g, s) => Right((s, g)))
      def local[A](fa: RuleT[Rec, Fail, S, A])(f: Globals => Globals): RuleT[Rec, Fail, S, A] =
        Prog.Scoped(fa, f)

  given stateful[Rec, Fail, S]: Stateful[Judged[Rec, Fail, S], S] =
    new Stateful[Judged[Rec, Fail, S], S]:
      val monad: Monad[Judged[Rec, Fail, S]] = RuleT.monad
      def get: RuleT[Rec, Fail, S, S] = step((_, s) => Right((s, s)))
      def set(s: S): RuleT[Rec, Fail, S, Unit] = step((_, _) => Right((s, ())))
      override def modify(f: S => S): RuleT[Rec, Fail, S, Unit] =
        step((_, s) => Right((f(s), ())))
      override def inspect[A](f: S => A): RuleT[Rec, Fail, S, A] = step((_, s) => Right((s, f(s))))

  given handle[Rec, Fail, S]: Handle[Judged[Rec, Fail, S], Fail] =
    new Handle[Judged[Rec, Fail, S], Fail]:
      val applicative: Applicative[Judged[Rec, Fail, S]] = monad
      def raise[E2 <: Fail, A](e: E2): RuleT[Rec, Fail, S, A] = step((_, _) => Left(e))
      def handleWith[A](fa: RuleT[Rec, Fail, S, A])(
          f: Fail => RuleT[Rec, Fail, S, A]
      ): RuleT[Rec, Fail, S, A] =
        Prog.Recover(fa, f)

  given judge[Rec, Fail, S]: Judge[Judged[Rec, Fail, S], Rec] =
    new Judge[Judged[Rec, Fail, S], Rec]:
      val functor: Functor[Judged[Rec, Fail, S]] = monad
      def tell(failures: Chain[Rec]): RuleT[Rec, Fail, S, Unit] = Prog.Record(failures)
      def failing: RuleT[Rec, Fail, S, Boolean] = Prog.Failing()
      def nest[A](fa: RuleT[Rec, Fail, S, A]): RuleT[Rec, Fail, S, A] = Prog.Nest(fa, None)
      override def trans(fa: RuleT[Rec, Fail, S, Unit]): RuleT[Rec, Fail, S, Unit] =
        Prog.Nest(fa, Some(()))
