// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Applicative, Functor}
import cats.data.Chain
import cats.mtl.{Raise, Tell}

/** The judgment of a rule as the reference's small-steps interpreter keeps it (cardano-ledger
  * `f649f975`, `libs/small-steps/src/Control/State/Transition/Extended.hs:668-730`): a writer of
  * predicate failures that no predicate aborts, with the two operations a state transition system
  * adds to a writer.
  *
  *   - `tell` / [[fail]]: a predicate failure is recorded and the rule continues (`?!`,
  *     `failBecause`, `failOnJust`, `failOnNonEmpty`, `?!:`, `runTest`: every one is a `Predicate`
  *     clause, `Extended.hs:394-456`, and `Extended.hs:704-709` records it). Several failures told
  *     at once are those failures recorded one after the other.
  *   - [[failing]]: whether anything was recorded so far anywhere in the judgment (`isFailing`,
  *     `Extended.hs:690-697`): the parent's flag at the moment it ran this rule, or a failure of
  *     the rule itself or of a sub-rule run before. It is read by `whenFailureFree` and
  *     `ifFailureFree` (`Extended.hs:479-487`), the only clauses that skip a step.
  *   - [[nest]]: the boundary of a sub-rule (`trans`, `Extended.hs:475`, interpreted at
  *     `Extended.hs:713-722`): the sub-rule always runs, inherits the failing flag, and what it
  *     records is one block of the parent's failures. The block decides the order the reference
  *     reports ([[Judgment.flatten]]).
  *   - [[policy]]: whether a predicate is checked at all ([[ValidationPolicy]]). Under
  *     `ValidateNone` a predicate's condition is never evaluated ([[Sts.validating]]).
  *
  * A raised failure (`Raise`) is not a predicate failure: it ends the judgment with no verdict and
  * is kept for the defects of the implementation, which the reference has no counterpart for.
  */
trait Judge[F[_], Fail] extends Tell[F, Chain[Fail]]:
  /** Records one predicate failure. */
  def fail(failure: Fail): F[Unit] = tell(Chain.one(failure))

  /** Whether any predicate failure was recorded so far in the judgment. */
  def failing: F[Boolean]

  /** Runs `fa` as a sub-rule: its failures are recorded as one block of the enclosing rule's. */
  def nest[A](fa: F[A]): F[A]

  /** Whether the judgment checks its predicates; a sub-rule's judgment is its parent's. */
  def policy: ValidationPolicy

  /** The judgment of a sub-rule whose failures are wrapped into this one's (`wrapFailed`, as
    * `Raise.contramap` does for raised failures).
    */
  def contramap[G](wrap: G => Fail): Judge[F, G] =
    val outer = this
    new Judge[F, G]:
      val functor: Functor[F] = outer.functor
      def tell(failures: Chain[G]): F[Unit] = outer.tell(failures.map(wrap))
      def failing: F[Boolean] = outer.failing
      def nest[A](fa: F[A]): F[A] = outer.nest(fa)
      def policy: ValidationPolicy = outer.policy

object Judge:
  def apply[F[_], Fail](using judge: Judge[F, Fail]): Judge[F, Fail] = judge

  /** The judgment of a rule run to its first failure, as a raise: a recorded failure is raised,
    * wrapped (`wrap`), so nothing is ever recorded and the failing flag is always down. This is the
    * [[RuleT.Policy.FirstFailure]] judgment for a rule whose caller raises its failures: the
    * block's rules (LEDGERS under BBODY), whose verdict is the first failure of the first refused
    * transaction. It is not a lawful `Tell`: a told failure ends the program.
    */
  def raising[F[_], Rec, Fail](wrap: Rec => Fail)(using
      F: Applicative[F],
      raise: Raise[F, Fail]
  ): Judge[F, Rec] = new Judge[F, Rec]:
    val functor: Functor[F] = F
    def tell(failures: Chain[Rec]): F[Unit] =
      failures.headOption.fold(F.unit)(f => raise.raise[Fail, Unit](wrap(f)))
    def failing: F[Boolean] = F.pure(false)
    def nest[A](fa: F[A]): F[A] = fa
    def policy: ValidationPolicy = ValidationPolicy.ValidateAll

  /** The judgment of `ValidateNone` (`Control/State/Transition/Extended.hs:715-724, 739-742`,
    * cardano-ledger 9df9f98c): no predicate is checked, so its condition is never evaluated and
    * nothing is recorded, the failing flag stays down (`isFailing`, `:701-707`), and every sub-rule
    * runs as the rule's own.
    */
  def validateNone[F[_], Fail](using F: Applicative[F]): Judge[F, Fail] =
    new Judge[F, Fail]:
      val functor: Functor[F] = F
      def tell(failures: Chain[Fail]): F[Unit] = F.unit
      def failing: F[Boolean] = F.pure(false)
      def nest[A](fa: F[A]): F[A] = fa
      def policy: ValidationPolicy = ValidationPolicy.ValidateNone

/** `ValidationPolicy` (`Control/State/Transition/Extended.hs:526-529`, cardano-ledger 9df9f98c):
  * whether a judgment checks its predicates. The reference's third, `ValidateSuchThat`, selects the
  * labelled parts of the rules, which the node selects with
  * [[xyz.kd5ujc.scarab.ledger.TxEnv.Validation]].
  */
enum ValidationPolicy derives CanEqual:
  case ValidateAll, ValidateNone
