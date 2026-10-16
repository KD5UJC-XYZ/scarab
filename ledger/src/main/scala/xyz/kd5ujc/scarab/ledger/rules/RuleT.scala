// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Applicative, Monad}
import cats.mtl.{Handle, Local, Stateful}
import scala.annotation.tailrec

/** The interpreter of the rule algebras ([[Sts]]): a pure program over the constants, a state and a
  * failure, run by a loop with an explicit stack. It stands where the plan has
  * `Kleisli[StateT[Either[Fail, *], S, *], Globals, *]` (design §2.2); the plan allows a fused form
  * if the stack costs more than 10% of block-apply time, and J2.4 measures it on a Preview epoch,
  * the first time there is a rule to measure.
  *
  * Stack safety is the point of the shape: a rule sequences a block's transactions, a transaction's
  * certificates, an epoch's pools, and a function that calls the next step from its continuation
  * grows the JVM stack with the length of the sequence (the preprod restart that died in
  * `Phase2.loop` did exactly that). A [[Prog]] is data: `flatMap` and the handlers build nodes, and
  * [[RuleT.run]] walks them with a typed stack of [[Frame]]s, in constant JVM stack for any
  * nesting, to the right (`go.flatMap(_ => go)`), to the left (`foldLeft(unit)(_.flatMap(...))`)
  * and under handlers. `tailRecM` is therefore the plain recursion, and `traverse_`, `traverse` and
  * `foldM` of cats are safe over it.
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

/** What is left to do when the program in hand has a value, or fails. */
private[rules] enum Frame[Fail, S]:
  /** Feed the value to the continuation. */
  case Next(next: Any => Prog[Fail, S, Any])

  /** A recovery: a failure resumes at `handler`, from the state `saved` the protected program
    * started from (the state of a failed program is lost, as in Haskell, where a failure aborts the
    * judgment); a value passes through.
    */
  case Handler(handler: Fail => Prog[Fail, S, Any], saved: S)

  /** Leave a scope: the constants as they were outside it. */
  case Restore(globals: Globals)

object RuleT:
  /** The monad of a failure and a state, for the rules to be instantiated at. */
  type Of[Fail, S] = [A] =>> RuleT[Fail, S, A]

  def pure[Fail, S, A](a: A): RuleT[Fail, S, A] = Prog.Pure(a)

  extension [Fail, S, A](rule: RuleT[Fail, S, A])
    /** Runs the rule: the state and the result, or the first failure not recovered. */
    def run(globals: Globals, state: S): Either[Fail, (S, A)] =
      // One loop, so that it is a tail call: the machine is either running a program or failing
      // (dropping the continuations up to the nearest handler, restoring the constants of the
      // scopes it leaves, and resuming there; with no handler left the run ends).
      @tailrec
      def go(
          current: Either[Fail, Prog[Fail, S, Any]],
          g: Globals,
          s: S,
          stack: List[Frame[Fail, S]]
      ): Either[Fail, (S, Any)] =
        current match
          case Left(e) =>
            stack match
              case Nil                          => Left(e)
              case Frame.Next(_) :: rest        => go(current, g, s, rest)
              case Frame.Restore(outer) :: rest => go(current, outer, s, rest)
              case Frame.Handler(handler, saved) :: rest =>
                go(Right(handler(e)), g, saved, rest)
          case Right(program) =>
            program match
              case Prog.Pure(a) =>
                stack match
                  case Nil                          => Right((s, a))
                  case Frame.Next(next) :: rest     => go(Right(next(a)), g, s, rest)
                  case Frame.Handler(_, _) :: rest  => go(current, g, s, rest)
                  case Frame.Restore(outer) :: rest => go(current, outer, s, rest)
              case Prog.Step(step) =>
                step(g, s) match
                  case Right((s1, a)) => go(Right(Prog.Pure(a)), g, s1, stack)
                  case Left(e)        => go(Left(e), g, s, stack)
              case b: Prog.Bind[Fail, S, ?, Any] @unchecked =>
                go(
                  Right(b.from.asInstanceOf[Prog[Fail, S, Any]]),
                  g,
                  s,
                  Frame.Next(b.next.asInstanceOf[Any => Prog[Fail, S, Any]]) :: stack
                )
              case r: Prog.Recover[Fail, S, Any] =>
                go(Right(r.program), g, s, Frame.Handler(r.handler, s) :: stack)
              case sc: Prog.Scoped[Fail, S, Any] =>
                go(Right(sc.program), sc.change(g), s, Frame.Restore(g) :: stack)

      go(Right(rule.asInstanceOf[Prog[Fail, S, Any]]), globals, state, Nil)
        .asInstanceOf[Either[Fail, (S, A)]]

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
