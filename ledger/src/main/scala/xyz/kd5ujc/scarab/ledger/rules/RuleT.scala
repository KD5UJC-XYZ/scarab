// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Applicative, Monad}
import cats.mtl.{Handle, Local, Stateful}
import scala.annotation.tailrec

/** The interpreter of the rule algebras ([[Sts]]): a pure program over the constants, a state and a
  * failure, run by a loop with an explicit, type-aligned stack. It implements the capabilities of
  * `Kleisli[StateT[Either[Fail, *], S, *], Globals, *]` without nesting those interpreters. The
  * design, reference-interpreter comparisons and measurements are recorded in
  * `docs/design/rule-interpreter-as-built.md`.
  *
  * Stack safety is the point of the shape: a rule sequences a block's transactions, a transaction's
  * certificates, an epoch's pools, and a function that calls the next step from its continuation
  * grows the JVM stack with the length of the sequence (the preprod restart that died in
  * `Phase2.loop` did exactly that). A [[Prog]] is data: `flatMap` and the handlers build nodes, and
  * [[RuleT.run]] walks them with a type-aligned stack of [[Frame]]s, in constant JVM stack for any
  * nesting, to the right (`go.flatMap(_ => go)`), to the left (`foldLeft(unit)(_.flatMap(...))`)
  * and under handlers. `tailRecM` is therefore the plain recursion, and `traverse_`, `traverse` and
  * `foldM` of cats are safe over it.
  *
  * The machine is typed end to end (AUD-14): the frames form a type-aligned sequence
  * `Frames[Fail, S, From, To]`, so a continuation always receives exactly the result type of the
  * program before it — across recovery and scoped globals — with no cast and no `Any` -typed value
  * channel (GADT pattern matching is the compiler-enforced proof).
  *
  * The instances are the capabilities of D4: `Local` (so `Ask`) over [[Globals]], `Stateful` over
  * the state, and `Handle` over the failure (`Raise` and recovery with `Handle.allow`).
  */
opaque type RuleT[Fail, S, A] = Prog[Fail, S, A]

/** The program: a value, a step of the interpreter's own (reads and writes the state, may fail), a
  * sequence, a recovery from a failure, or a change of the constants for a sub-program.
  */
private[rules] enum Prog[Fail, S, A]:
  case Pure(value: A)
  case Step(run: (Globals, S) => Either[Fail, (S, A)])
  case Bind[Fail, S, X, A](from: Prog[Fail, S, X], next: X => Prog[Fail, S, A])
      extends Prog[Fail, S, A]
  case Recover(program: Prog[Fail, S, A], handler: Fail => Prog[Fail, S, A])
  case Scoped(program: Prog[Fail, S, A], change: Globals => Globals)

/** The rest of the computation with a hole of type `X` and a final result of type `A`: a
  * type-aligned sequence of [[Frame]]s (`Done` closes the sequence, `Cons` extends it by one step
  * whose input type is the previous hole). Every continuation in the machine is therefore applied
  * to exactly the result type of the program before it.
  */
private[rules] enum Frames[Fail, S, X, A]:
  case Done[Fail, S, A]() extends Frames[Fail, S, A, A]
  case Cons[Fail, S, X, Y, A](head: Frame[Fail, S, X, Y], tail: Frames[Fail, S, Y, A])
      extends Frames[Fail, S, X, A]

/** What is left to do when the program in hand has a value of type `X`, or fails. */
private[rules] enum Frame[Fail, S, X, Y]:
  /** Feed the value to the continuation. */
  case Next[Fail, S, X, Y](next: X => Prog[Fail, S, Y]) extends Frame[Fail, S, X, Y]

  /** A recovery: a failure resumes at `handler`, from the state `saved` the protected program
    * started from (the state of a failed program is lost, as in Haskell, where a failure aborts the
    * judgment); a value passes through.
    */
  case Handler[Fail, S, A](handler: Fail => Prog[Fail, S, A], saved: S) extends Frame[Fail, S, A, A]

  /** Leave a scope: the constants as they were outside it. */
  case Restore[Fail, S, A](globals: Globals) extends Frame[Fail, S, A, A]

object RuleT:
  /** The monad of a failure and a state, for the rules to be instantiated at. */
  type Of[Fail, S] = [A] =>> RuleT[Fail, S, A]

  def pure[Fail, S, A](a: A): RuleT[Fail, S, A] = Prog.Pure(a)

  extension [Fail, S, A](rule: RuleT[Fail, S, A])
    /** Runs the rule: the state and the result, or the first failure not recovered. */
    def run(globals: Globals, state: S): Either[Fail, (S, A)] =
      // One loop, so that it is a tail call: the machine is either running a program or failing
      // (dropping the continuations up to the nearest handler, restoring the constants of the
      // scopes it leaves, and resuming there; with no handler left the run ends). The frames are
      // type-aligned: `go` is called with the exact type the hole was fed, so every transition is
      // compiler-checked to connect the result of one step to the continuation of the next.
      @tailrec
      def go[X](
          current: Either[Fail, Prog[Fail, S, X]],
          g: Globals,
          s: S,
          frames: Frames[Fail, S, X, A]
      ): Either[Fail, (S, A)] =
        current match
          case Left(e) =>
            frames match
              case Frames.Done() => Left(e)
              case Frames.Cons(head, tail) =>
                head match
                  case Frame.Next(_)                 => go(Left(e), g, s, tail)
                  case Frame.Restore(outer)          => go(Left(e), outer, s, tail)
                  case Frame.Handler(handler, saved) => go(Right(handler(e)), g, saved, tail)
          case Right(program) =>
            program match
              case Prog.Pure(a) =>
                frames match
                  case Frames.Done() => Right((s, a))
                  case Frames.Cons(head, tail) =>
                    head match
                      case Frame.Next(next)     => go(Right(next(a)), g, s, tail)
                      case Frame.Handler(_, _)  => go(Right(Prog.Pure(a)), g, s, tail)
                      case Frame.Restore(outer) => go(Right(Prog.Pure(a)), outer, s, tail)
              case Prog.Step(step) =>
                step(g, s) match
                  case Right((s1, a)) => go(Right(Prog.Pure(a)), g, s1, frames)
                  case Left(e)        => go(Left(e), g, s, frames)
              case Prog.Bind(from, next) =>
                go(Right(from), g, s, Frames.Cons(Frame.Next(next), frames))
              case Prog.Recover(program, handler) =>
                go(Right(program), g, s, Frames.Cons(Frame.Handler(handler, s), frames))
              case Prog.Scoped(program, change) =>
                go(Right(program), change(g), s, Frames.Cons(Frame.Restore(g), frames))

      go(Right(rule), globals, state, Frames.Done[Fail, S, A]())

  private def step[Fail, S, A](f: (Globals, S) => Either[Fail, (S, A)]): RuleT[Fail, S, A] =
    Prog.Step(f)

  given monad[Fail, S]: Monad[Of[Fail, S]] = new Monad[Of[Fail, S]]:
    def pure[A](a: A): RuleT[Fail, S, A] = Prog.Pure(a)

    def flatMap[A, B](fa: RuleT[Fail, S, A])(f: A => RuleT[Fail, S, B]): RuleT[Fail, S, B] =
      Prog.Bind(fa, f)

    // The program is data, so the recursion is not on the stack.
    def tailRecM[A, B](a: A)(f: A => RuleT[Fail, S, Either[A, B]]): RuleT[Fail, S, B] =
      Prog.Bind(
        f(a),
        {
          case Left(next) => tailRecM(next)(f)
          case Right(b)   => Prog.Pure(b)
        }
      )

  given local[Fail, S]: Local[Of[Fail, S], Globals] = new Local[Of[Fail, S], Globals]:
    val applicative: Applicative[Of[Fail, S]] = monad
    def ask[E2 >: Globals]: RuleT[Fail, S, E2] = step((g, s) => Right((s, g)))
    def local[A](fa: RuleT[Fail, S, A])(f: Globals => Globals): RuleT[Fail, S, A] =
      Prog.Scoped(fa, f)

  given stateful[Fail, S]: Stateful[Of[Fail, S], S] = new Stateful[Of[Fail, S], S]:
    val monad: Monad[Of[Fail, S]] = RuleT.monad
    def get: RuleT[Fail, S, S] = step((_, s) => Right((s, s)))
    def set(s: S): RuleT[Fail, S, Unit] = step((_, _) => Right((s, ())))
    override def modify(f: S => S): RuleT[Fail, S, Unit] = step((_, s) => Right((f(s), ())))
    override def inspect[A](f: S => A): RuleT[Fail, S, A] = step((_, s) => Right((s, f(s))))

  given handle[Fail, S]: Handle[Of[Fail, S], Fail] = new Handle[Of[Fail, S], Fail]:
    val applicative: Applicative[Of[Fail, S]] = monad
    def raise[E2 <: Fail, A](e: E2): RuleT[Fail, S, A] = step((_, _) => Left(e))
    def handleWith[A](fa: RuleT[Fail, S, A])(f: Fail => RuleT[Fail, S, A]): RuleT[Fail, S, A] =
      Prog.Recover(fa, f)
