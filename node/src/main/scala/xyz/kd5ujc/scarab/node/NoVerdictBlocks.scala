// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Async
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockNoVerdict, NoVerdictRetry}
import xyz.kd5ujc.scarab.ledger.Header

/** The blocks the node has no verdict on, so that chain selection does not judge them again on
  * every delivery (issue 567). Unlike [[InvalidBlocks]] no peer is blamed for them: a ChainSync
  * client does not punish a peer that announces one. The set lives in memory only, so a restart (a
  * fixed build) judges such a block again, and it is pruned at the immutable tip as the invalid set
  * is.
  *
  * A block whose cause can pass on its own ([[BlockNoVerdict.Recovery.Passing]]) is judged again on
  * a capped exponential schedule (issue 693, an amendment of the #567 deviation): the closest
  * equivalent of the reference, which stops on the exception and validates the block again after a
  * restart (`initialChainSelection`, ouroboros-consensus 82ecba32
  * `Storage/ChainDB/Impl/ChainSel.hs:115-166`). The schedule is measured from the end of an attempt
  * and has no limit on attempts; only a verdict ([[verdict]]), pruning or a restart ends it. A
  * delivery, a reconnect or an admission never touches it: only the selection worker's reselection
  * releases a due block ([[release]]), one at a time.
  */
final class NoVerdictBlocks[F[_]: Async] private (
    state: SignallingRef[F, NoVerdictBlocks.Entries]
):
  import NoVerdictBlocks.*
  private val F = Async[F]

  /** Record an attempt on `header` 's block that ended without a verdict, now: its first, or
    * another on a block judged again. Returns the attempt's number and when the block is judged
    * again.
    */
  def record(header: Header, cause: BlockNoVerdict): F[(Int, NoVerdictRetry)] =
    F.monotonic.flatMap(end => state.modify(_.recorded(header.hash, header.slot, cause, end)))

  /** A verdict on these blocks (adopted, or refused by the rules): their schedules end. */
  def verdict(hashes: Iterable[Bytes]): F[Unit] =
    // every adoption comes here: the common case, none of them withheld, writes nothing
    state.get.flatMap(entries =>
      state.update(_.removed(hashes)).whenA(hashes.exists(entries.byHash.contains))
    )

  def hashes: F[Set[Bytes]] = state.get.map(_.byHash.keySet)

  def lookup(hash: Bytes): F[Option[Withheld]] = state.get.map(_.byHash.get(hash))

  def pruneBefore(immutableSlot: BigInt): F[Unit] =
    state.update(_.pruned(immutableSlot))

  /** The block a reselection at `now` may judge again: the one due first, if any. */
  def release(now: FiniteDuration): F[Option[Bytes]] = state.get.map(_.due(now))

  /** The blocks selection excludes at `now`: all of them but `released` while it is still due. */
  def excluded(now: FiniteDuration, released: Option[Bytes]): F[Set[Bytes]] =
    state.get.map(_.excluded(now, released))

  /** A released block that the reselection did not reach (no preferred candidate goes through it):
    * not an attempt, so its count stays; it is due again after its current delay from now.
    */
  def unreached(hash: Bytes, released: FiniteDuration): F[Unit] =
    F.monotonic.flatMap(end => state.update(_.deferred(hash, released, end)))

  /** Return once a block is due, waking when the set changes. */
  def awaitDue: F[Unit] =
    F.tailRecM(())(_ =>
      (state.get, F.monotonic).flatMapN { (entries, now) =>
        val changed = state.waitUntil(_.revision != entries.revision)
        entries.nextAt match
          case Some(at) if at <= now => F.pure(Right(()))
          case Some(at)              => F.race(F.sleep(at - now), changed).as(Left(()))
          case None                  => changed.as(Left(()))
      }
    )

  /** The blocks withheld now, for a stall report: each one's hash, slot, cause, attempts and the
    * time to its next attempt (none when it is judged once).
    */
  def report: F[Vector[Report]] =
    (state.get, F.monotonic).mapN((entries, now) =>
      entries.byHash.toVector
        .sortBy((hash, withheld) => (withheld.slot, hash))
        .map((hash, withheld) =>
          Report(
            hash,
            withheld.slot,
            withheld.cause,
            withheld.attempts,
            withheld.schedule match
              case Schedule.Once     => None
              case Schedule.At(next) => Some((next - now).max(Duration.Zero))
          )
        )
    )

object NoVerdictBlocks:
  /** When a withheld block is judged again. */
  enum Schedule:
    /** Not in this run: its cause does not pass on its own. */
    case Once

    /** At the monotonic time `next`. */
    case At(next: FiniteDuration)

  final case class Withheld(slot: BigInt, cause: BlockNoVerdict, attempts: Int, schedule: Schedule)

  /** One withheld block as a stall report names it. */
  final case class Report(
      hash: Bytes,
      slot: BigInt,
      cause: BlockNoVerdict,
      attempts: Int,
      retryIn: Option[FiniteDuration]
  )

  /** The first wait. */
  val FirstDelay: FiniteDuration = 1.second

  /** The longest wait. */
  val MaxDelay: FiniteDuration = 30.seconds

  /** The wait after attempt `attempts` (from 1): 1 s, doubled after each attempt, at most 30 s. */
  def delay(attempts: Int): FiniteDuration =
    val doublings = (attempts - 1).max(0)
    // 2^5 s already exceeds the cap, so the shift never overflows
    if doublings >= 5 then MaxDelay else (FirstDelay * (1L << doublings)).min(MaxDelay)

  /** The withheld blocks by hash, and a revision that every change advances. */
  final case class Entries(byHash: Map[Bytes, Withheld], revision: Long):
    private def changed(next: Map[Bytes, Withheld]): Entries = Entries(next, revision + 1)

    /** An attempt ended at `end` without a verdict. Another attempt on a withheld block counts on
      * from its earlier ones, whatever the cause is now; the cause of the latest attempt decides
      * whether it is judged again.
      */
    def recorded(
        hash: Bytes,
        slot: BigInt,
        cause: BlockNoVerdict,
        end: FiniteDuration
    ): (Entries, (Int, NoVerdictRetry)) =
      val attempts = byHash.get(hash).fold(1)(_.attempts + 1)
      val (schedule, retry) = cause.recovery match
        case BlockNoVerdict.Recovery.Passing =>
          val wait = delay(attempts)
          (Schedule.At(end + wait), NoVerdictRetry.After(wait))
        case BlockNoVerdict.Recovery.Deterministic => (Schedule.Once, NoVerdictRetry.Never)
      (changed(byHash.updated(hash, Withheld(slot, cause, attempts, schedule))), (attempts, retry))

    def removed(hashes: Iterable[Bytes]): Entries =
      val present = hashes.filter(byHash.contains)
      if present.isEmpty then this else changed(byHash -- present)

    def pruned(immutableSlot: BigInt): Entries =
      val kept = byHash.filter((_, withheld) => withheld.slot >= immutableSlot)
      if kept.size == byHash.size then this else changed(kept)

    /** Whether `hash` is due at `now`. */
    private def dueAt(hash: Bytes, now: FiniteDuration): Boolean =
      byHash
        .get(hash)
        .exists(_.schedule match
          case Schedule.At(next) => next <= now
          case Schedule.Once     => false)

    /** The earliest next attempt of any block judged again. */
    def nextAt: Option[FiniteDuration] =
      byHash.valuesIterator.collect { case Withheld(_, _, _, Schedule.At(next)) => next }.minOption

    /** The block due first at `now`; a tie goes to the lower slot, then the lower hash. */
    def due(now: FiniteDuration): Option[Bytes] =
      byHash.toVector
        .collect {
          case (hash, Withheld(slot, _, _, Schedule.At(next))) if next <= now => (next, slot, hash)
        }
        .minOption
        .map(_._3)

    def excluded(now: FiniteDuration, released: Option[Bytes]): Set[Bytes] =
      released.filter(dueAt(_, now)).fold(byHash.keySet)(byHash.keySet - _)

    /** `hash`, released at `released`, was not reached: when its schedule is still the one it was
      * released on (no attempt recorded since), it is due again after its current delay from `end`.
      */
    def deferred(hash: Bytes, released: FiniteDuration, end: FiniteDuration): Entries =
      byHash.get(hash) match
        case Some(withheld @ Withheld(_, _, attempts, Schedule.At(next))) if next <= released =>
          changed(
            byHash.updated(hash, withheld.copy(schedule = Schedule.At(end + delay(attempts))))
          )
        case _ => this

  object Entries:
    val empty: Entries = Entries(Map.empty, 0L)

  def create[F[_]: Async]: F[NoVerdictBlocks[F]] =
    SignallingRef.of[F, Entries](Entries.empty).map(new NoVerdictBlocks(_))
