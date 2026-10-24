// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Applicative, Functor, Monad}
import cats.mtl.{Ask, Raise, Stateful}
import cats.mtl.syntax.all.*
import monocle.Lens

/** A state transition system: the rule algebra of the era-indexed ledger (design
  * `ledger-mirror-step2.md` §2.2), Haskell's `STS` class (`Control/State/Transition/Extended.hs:
  * 209-268`) rendered with cats-mtl.
  *
  * The class's associated types are the type parameters: the environment `Env` (`Environment`), the
  * state `S` (`State`), the signal `Sig` (`Signal`) and the failure `Fail` (`PredicateFailure`).
  * The rules of Haskell are programs in the free monad `Rule sts ctx` over `BaseM sts`; here a rule
  * is polymorphic in the monad and asks for what it does, as the owner's style has it (design D4):
  *
  *   - `Ask[F, Globals]`: the reader environment `ShelleyBase = ReaderT Globals Identity`
  *     (`BaseTypes.hs:796`), read with [[Sts.globals]];
  *   - `Stateful[F, S]`: the state the transition changes (`TRC (env, st, sig)` becomes `st` in the
  *     state and the environment and signal as arguments, as D4 says);
  *   - `Raise[F, Fail]`: `PredicateFailure` s (`?!`, [[Sts.check]]); the first failure ends the
  *     transition.
  *
  * The reference records predicate failures and continues (`Extended.hs:668-730`, cardano-ledger
  * `f649f975`). The capability for that is [[Judge]], interpreted by [[RuleT]] and run by
  * [[Judgment]] under a [[RuleT.Policy]] (accumulate, or end at the first failure); the combinators
  * [[Sts.whenFailureFree]], [[Sts.whenFailureFreeDefault]] and [[Sts.ifFailureFree]] read its
  * failing flag. A rule raises until it records: [[Sts.apply]] and [[Judgment.run]] report the
  * first failure of such a rule under either policy.
  *
  * Leaf predicates stay pure `Either` /`Boolean` and enter through [[Sts.check]] and
  * [[Sts.require]]. A rule that is made of another (`trans`, `Extended.hs:475`) runs it on the part
  * of its state the other sees, through [[Embed]] (`Extended.hs:270-286`) and [[Sts.trans]]: CERTS
  * on the `CertState` of a `LedgerState` is `Stateful.focus` of the lens that `Embed` names.
  *
  * Haskell's `Event` (`tellEvent`, `Extended.hs:872`) is not modelled: no rule of the node consumes
  * events yet, and the capability (`Tell[F, Vector[Event]]`) is added with the first rule that
  * does.
  */
trait Sts[Env, S, Sig, Fail]:
  /** The transition judgment `env ⊢ s ⊢ signal ⇒ s'`: changes the state, or raises a failure. */
  def transition[F[_]: Monad](env: Env, signal: Sig)(using
      Ask[F, Globals],
      Stateful[F, S],
      Raise[F, Fail]
  ): F[Unit]

object Sts:
  /** The constants of the network (Haskell: `liftSTS ask` in the `ShelleyBase` monad). */
  def globals[F[_]](using ask: Ask[F, Globals]): F[Globals] = ask.ask[Globals]

  /** `cond ?! failure` (`Extended.hs:394-399`): raises `failure` unless `cond` holds. */
  def check[F[_]: Applicative, Fail](cond: Boolean, failure: => Fail)(using
      Raise[F, Fail]
  ): F[Unit] =
    if cond then Applicative[F].unit else failure.raise[F, Unit]

  /** Raises the failure a pure predicate returned, if it returned one (`failureOnJust`). */
  def require[F[_]: Applicative, Fail](failure: Option[Fail])(using Raise[F, Fail]): F[Unit] =
    failure.fold(Applicative[F].unit)(_.raise[F, Unit])

  /** The value of a pure `Either` predicate, or its failure raised. */
  def lift[F[_]: Applicative, Fail, A](result: Either[Fail, A])(using Raise[F, Fail]): F[A] =
    result.fold(_.raise[F, A], Applicative[F].pure)

  /** Replaces the state by what a pure step makes of it, or raises the step's failure: the shape of
    * a rule whose body is one function of the state (`modify` that can refuse).
    */
  def modifyE[F[_]: Monad, S, Fail](f: S => Either[Fail, S])(using
      state: Stateful[F, S],
      raise: Raise[F, Fail]
  ): F[Unit] =
    Monad[F].flatMap(state.get)(s => Monad[F].flatMap(lift[F, Fail, S](f(s)))(state.set))

  /** `ifFailureFree yes no` (`Extended.hs:479-481`): `yes` if nothing was recorded so far anywhere
    * in the judgment, `no` otherwise. With `whenFailureFree`, the only clause of the reference that
    * skips a step.
    */
  def ifFailureFree[F[_]: Monad, Fail, A](yes: => F[A], no: => F[A])(using
      judge: Judge[F, Fail]
  ): F[A] =
    Monad[F].flatMap(judge.failing)(failing => if failing then no else yes)

  /** `whenFailureFree` (`Extended.hs:483-484`): runs `fa` only if nothing was recorded so far. */
  def whenFailureFree[F[_]: Monad, Fail](fa: => F[Unit])(using Judge[F, Fail]): F[Unit] =
    ifFailureFree[F, Fail, Unit](fa, Monad[F].unit)

  /** `whenFailureFreeDefault` (`Extended.hs:486-487`): `fa` if nothing was recorded so far, the
    * default otherwise.
    */
  def whenFailureFreeDefault[F[_]: Monad, Fail, A](default: => A)(fa: => F[A])(using
      Judge[F, Fail]
  ): F[A] =
    ifFailureFree[F, Fail, A](fa, Monad[F].pure(default))

  /** `trans @sub` (`Extended.hs:475`): runs `sub` on the part of the state `S0` that [[Embed]]
    * names, with its failures wrapped into `Fail0` (`wrapFailed`).
    */
  def trans[F[_]: Monad, Env, S, S0, Sig, Fail, Fail0](
      sub: Sts[Env, S, Sig, Fail],
      env: Env,
      signal: Sig
  )(using
      embed: Embed[S, S0, Fail, Fail0],
      ask: Ask[F, Globals],
      state: Stateful[F, S0],
      raise: Raise[F, Fail0]
  ): F[Unit] =
    sub.transition[F](env, signal)(using
      summon[Monad[F]],
      ask,
      state.focus(embed.state),
      raise.contramap(embed.failure)
    )

  /** `applySTS` (`Extended.hs:610`) on the fused interpreter [[RuleT]]: the state after the
    * transition, or the first failure.
    */
  def apply[Env, S, Sig, Fail](rule: Sts[Env, S, Sig, Fail])(
      globals: Globals,
      env: Env,
      state: S,
      signal: Sig
  ): Either[Fail, S] =
    rule.transition[RuleT.Of[Fail, S]](env, signal).run(globals, state).map(_._1)

/** `Embed sub super` (`Extended.hs:270-286`): how the state and the failures of a sub-rule sit in
  * those of the rule that runs it. The state is a lens (Haskell's rules pass the part of the state
  * as the `State sub` of the sub-judgment and put the result back); `failure` is `wrapFailed`.
  * Instances live in the companion of the rule that embeds, as Haskell's `Embed` instances do.
  */
final case class Embed[S, S0, Fail, Fail0](state: Lens[S0, S], failure: Fail => Fail0)

object Embed:
  /** `instance STS sts => Embed sts sts` (`Extended.hs:288`). */
  given refl[S, Fail]: Embed[S, S, Fail, Fail] =
    Embed(Lens[S, S](identity)(s => _ => s), identity)

/** The combinators that turn one capability into another over a part of its subject. */
extension [F[_], S](state: Stateful[F, S])
  /** The same state seen through a lens: what `trans` hands a sub-rule (Haskell passes `State sub`
    * and puts the result back). Law-abiding when the lens is.
    */
  def focus[T](lens: Lens[S, T]): Stateful[F, T] = new Stateful[F, T]:
    val monad = state.monad
    def get: F[T] = state.monad.map(state.get)(lens.get)
    def set(t: T): F[Unit] = state.modify(lens.replace(t))
    override def modify(f: T => T): F[Unit] = state.modify(lens.modify(f))
    override def inspect[A](f: T => A): F[A] = state.inspect(s => f(lens.get(s)))

extension [F[_], E](outer: Raise[F, E])
  /** The failures of a sub-rule, raised as those of the rule that runs it (`wrapFailed`). `Raise`
    * is contravariant in its error, and this is the contramap for failures that need wrapping.
    */
  def contramap[E2](wrap: E2 => E): Raise[F, E2] = new Raise[F, E2]:
    val functor: Functor[F] = outer.functor
    def raise[E3 <: E2, A](e: E3): F[A] = outer.raise(wrap(e))
