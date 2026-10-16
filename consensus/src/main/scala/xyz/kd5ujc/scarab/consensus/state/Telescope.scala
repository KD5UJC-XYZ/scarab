// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import monocle.{Focus, Lens}
import xyz.kd5ujc.scarab.protocol.Era

// The hard-fork combinator's state, as the mirror holds it. Haskell indexes the telescope by a
// type-level list of eras (`xs`); here the index is the era a state is in (`Era`: Byron, then the
// six Shelley-based eras), and the invariant the type-level list gives for free, that a telescope in
// era `n` has `n` past eras, is the one the smart constructor [[Telescope.make]] enforces.

/** A point on the era history (`Bound`, `HardFork/History/Summary.hs:82-88`): time since the system
  * start in picoseconds, slot and epoch.
  *
  * Deviations from Haskell, both for the node-to-client answer that already uses this type
  * (`EraHistory`): the three numbers are `BigInt` s rather than `RelativeTime` /`SlotNo`/`EpochNo`,
  * and the Peras round (`boundPerasRound`, `NoPerasEnabled` on every Cardano era today) is absent.
  */
final case class Bound(timePs: BigInt, slot: BigInt, epoch: BigInt) derives Eq, Show

object Bound:
  /** The bound of a telescope the node holds before it is put on the era history: see
    * [[Telescope.unplaced]].
    */
  val zero: Bound = Bound(0, 0, 0)

  val timePsLens: Lens[Bound, BigInt] = Focus[Bound](_.timePs)
  val slotLens: Lens[Bound, BigInt] = Focus[Bound](_.slot)
  val epochLens: Lens[Bound, BigInt] = Focus[Bound](_.epoch)

/** A past era (`Past`, `HardFork/Combinator/State/Types.hs:97-101`): where it started and ended. */
final case class Past(start: Bound, end: Bound) derives Eq, Show

object Past:
  val start: Lens[Past, Bound] = Focus[Past](_.start)
  val end: Lens[Past, Bound] = Focus[Past](_.end)

/** The current era (`Current f blk`, `State/Types.hs:90-93`): where it started and its state. */
final case class Current[A](start: Bound, state: A)

object Current:
  given [A: Eq]: Eq[Current[A]] = Eq.instance((a, b) => a.start === b.start && a.state === b.state)

  given [A: Show]: Show[Current[A]] =
    Show.show(c => s"Current(start = ${c.start.show}, state = ${c.state.show})")

  def start[A]: Lens[Current[A], Bound] = Focus[Current[A]](_.start)
  def state[A]: Lens[Current[A], A] = Focus[Current[A]](_.state)

/** Why a telescope cannot be built. */
enum TelescopeError derives Eq, Show:
  /** More past eras than the chain has eras before the last (Byron and six Shelley-based eras). */
  case TooManyPast(count: Int)

  /** The state cannot be in the era that the number of past eras puts it in. */
  case WrongEra(era: Era)

  /** The past era at `index` does not end where the next one (or the current one) starts. */
  case Discontinuity(index: Int)

object TelescopeError:
  given Eq[Era] = Eq.fromUniversalEquals
  given Show[Era] = Show.fromToString

/** The eras a state can be in: what ties a state to the number of past eras of its telescope. The
  * ledger state of an era knows its era exactly; a protocol state only the family (PBFT: Byron,
  * TPraos: Shelley to Alonzo, Praos: Babbage and Conway).
  */
trait EraIndexed[A]:
  def admits(era: Era, state: A): Boolean

object EraIndexed:
  def instance[A](f: (Era, A) => Boolean): EraIndexed[A] = f(_, _)

/** The hard-fork combinator's telescope (`HardForkState f xs`, `State/Types.hs:43-87`, a
  * `Telescope (K Past) (Current f) xs`): the past eras, then the current one with its state. Era
  * `n` has `n` past eras, and consecutive eras abut; [[Telescope.make]] is the only way in, so
  * every telescope satisfies both.
  */
final case class Telescope[A] private (era: Era, past: Vector[Past], current: Current[A]):
  def state: A = current.state

  /** The telescope one era later: the current era ends at `end` and `next` starts there
    * (`Telescope.extend`).
    */
  def extend(end: Bound, next: A)(using EraIndexed[A]): Either[TelescopeError, Telescope[A]] =
    Telescope.make(past :+ Past(current.start, end), Current(end, next))

object Telescope:
  /** The telescope of `past` and `current`, or why it is none. The era of the current state is
    * `Era.values(past.size)`.
    */
  def make[A](past: Vector[Past], current: Current[A])(using
      indexed: EraIndexed[A]
  ): Either[TelescopeError, Telescope[A]] =
    for
      era <- Era.values.lift(past.size).toRight(TelescopeError.TooManyPast(past.size))
      _ <- Either.cond(indexed.admits(era, current.state), (), TelescopeError.WrongEra(era))
      starts = past.drop(1).map(_.start) :+ current.start
      broken = past.map(_.end).zip(starts).indexWhere(_ =!= _)
      _ <- Either.cond(broken < 0, (), TelescopeError.Discontinuity(broken))
    yield new Telescope(era, past, current)

  /** The telescope of `era` that has not been put on the era history: every bound at
    * [[Bound.zero]]. This is how the node holds its chain-dependent state (it has no use for the
    * bounds, which are a function of the configuration and of the eras it has seen begin,
    * `EraHistory.summary`); [[StateMirror]] gives it the real ones. The caller chooses an `era`
    * that the state can be in (see [[ChainDepState.firstEra]]); nothing here checks it.
    */
  def unplaced[A](era: Era, state: A): Telescope[A] =
    new Telescope(
      era,
      Vector.fill(era.index)(Past(Bound.zero, Bound.zero)),
      Current(Bound.zero, state)
    )

  /** The telescope of the first era: no past. */
  def first[A: EraIndexed](start: Bound, state: A): Either[TelescopeError, Telescope[A]] =
    make(Vector.empty, Current(start, state))

  given [A: Eq]: Eq[Telescope[A]] =
    Eq.instance((a, b) => a.past === b.past && a.current === b.current)

  given [A: Show]: Show[Telescope[A]] =
    Show.show(t => s"Telescope(past = ${t.past.show}, current = ${t.current.show})")
