// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Applicative, Monad}
import cats.mtl.{Handle, Local, Stateful}
import scala.annotation.tailrec

/** The interpreter of the rule algebras ([[Sts]]): a pure function of the constants and the state
  * to the failure or the new state and a result. It is the fused form of
  * `Kleisli[StateT[Either[Fail, *], S, *], Globals, *]` (design §2.2), which the plan names as the
  * reference and replaces by this if it cost more than 10% of block-apply time. There was nothing
  * to measure when it was written (no rule is ported), and one allocation per bind instead of three
  * is the safe side of the choice: the algebras do not change with the interpreter, so J2.4
  * measures the two on a Preview epoch and either keeps this one or swaps it.
  *
  * The instances are the capabilities of D4: `Ask` and `Local` over [[Globals]], `Stateful` over
  * the state, and `Handle` over the failure (`Raise` and recovery with `Handle.allow`).
  */
opaque type RuleT[Fail, S, A] = (Globals, S) => Either[Fail, (S, A)]

object RuleT:
  /** The monad of a failure and a state, for the rules to be instantiated at. */
  type Of[Fail, S] = [A] =>> RuleT[Fail, S, A]

  def pure[Fail, S, A](a: A): RuleT[Fail, S, A] = (_, s) => Right((s, a))

  extension [Fail, S, A](rule: RuleT[Fail, S, A])
    /** Runs the rule: the state and the result, or the first failure. */
    def run(globals: Globals, state: S): Either[Fail, (S, A)] = rule(globals, state)

  given monad[Fail, S]: Monad[Of[Fail, S]] = new Monad[Of[Fail, S]]:
    def pure[A](a: A): RuleT[Fail, S, A] = RuleT.pure(a)

    def flatMap[A, B](fa: RuleT[Fail, S, A])(f: A => RuleT[Fail, S, B]): RuleT[Fail, S, B] =
      (g, s) =>
        fa(g, s) match
          case Right((s1, a)) => f(a)(g, s1)
          case Left(e)        => Left(e)

    // Stack safe: a loop over the steps, whatever the number of binds.
    def tailRecM[A, B](a: A)(f: A => RuleT[Fail, S, Either[A, B]]): RuleT[Fail, S, B] =
      (g, s0) =>
        @tailrec def loop(x: A, s: S): Either[Fail, (S, B)] =
          f(x)(g, s) match
            case Left(e)               => Left(e)
            case Right((s1, Left(a1))) => loop(a1, s1)
            case Right((s1, Right(b))) => Right((s1, b))
        loop(a, s0)

  given local[Fail, S]: Local[Of[Fail, S], Globals] = new Local[Of[Fail, S], Globals]:
    val applicative: Applicative[Of[Fail, S]] = monad
    def ask[E2 >: Globals]: RuleT[Fail, S, E2] = (g, s) => Right((s, g))
    def local[A](fa: RuleT[Fail, S, A])(f: Globals => Globals): RuleT[Fail, S, A] =
      (g, s) => fa(f(g), s)

  given stateful[Fail, S]: Stateful[Of[Fail, S], S] = new Stateful[Of[Fail, S], S]:
    val monad: Monad[Of[Fail, S]] = RuleT.monad
    def get: RuleT[Fail, S, S] = (_, s) => Right((s, s))
    def set(s: S): RuleT[Fail, S, Unit] = (_, _) => Right((s, ()))
    override def modify(f: S => S): RuleT[Fail, S, Unit] = (_, s) => Right((f(s), ()))
    override def inspect[A](f: S => A): RuleT[Fail, S, A] = (_, s) => Right((s, f(s)))

  // A recovered failure restores the state the recovered computation started from: the state of a
  // failed transition is lost, as in Haskell, where the failure aborts the judgment.
  given handle[Fail, S]: Handle[Of[Fail, S], Fail] = new Handle[Of[Fail, S], Fail]:
    val applicative: Applicative[Of[Fail, S]] = monad
    def raise[E2 <: Fail, A](e: E2): RuleT[Fail, S, A] = (_, _) => Left(e)
    def handleWith[A](fa: RuleT[Fail, S, A])(f: Fail => RuleT[Fail, S, A]): RuleT[Fail, S, A] =
      (g, s) =>
        fa(g, s) match
          case Left(e) => f(e)(g, s)
          case right   => right
