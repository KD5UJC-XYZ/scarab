// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*

import MockEnvironment.*

/** Signals over a recording: the analogue of the reference's `Test.Ouroboros.Network.Signal`
  * (`keyedTimeout`, `keyedLinger`). A signal is a value that holds from one instant to the next, so
  * the properties sample a recording at its instants and at the instants where a signal changes by
  * itself (a linger that runs out).
  */
object Signals:
  type Series[K] = Vector[(Time, Set[K])]

  /** The last state at or before `t`. */
  def stateAt(rec: Recording, t: Time): Option[GovernorState[Peer]] =
    rec.states.takeWhile(_._1 <= t).lastOption.map(_._2)

  /** The instants at which a series of events changes a signal, and the instants a linger of
    * `linger` after each of them ends.
    */
  def breakpoints(rec: Recording, extra: Iterable[Time], linger: FiniteDuration): Vector[Time] =
    val times = rec.states.map(_._1) ++ extra ++ extra.map(_ + linger)
    SortedSet.from(times.filter(_ <= rec.horizon)).toVector

  /** The peers that failed within the last `linger` at `t`, from the instants they failed at. */
  def recent(failures: Vector[(Time, Peer)], t: Time, linger: FiniteDuration): Set[Peer] =
    failures.collect { case (at, p) if at <= t && t < at + linger => p }.toSet

  /** The `keyedTimeout` of a series: a key that is present for longer than `d` without a break is
    * reported with the instant it appeared. A key still present at the horizon counts only if it
    * had `d` before it.
    */
  def ignoredTooLong[K](series: Series[K], horizon: Time, d: FiniteDuration): Option[(K, Time)] =
    @scala.annotation.tailrec
    def go(
        rest: List[(Time, Set[K])],
        since: Map[K, Time]
    ): Option[(K, Time)] = rest match
      case Nil => None
      case (at, present) :: more =>
        val kept = since.view.filterKeys(present).toMap
        val fresh = present.filterNot(kept.contains).map(_ -> at).toMap
        val started = kept ++ fresh
        val until = more.headOption.map(_._1).getOrElse(horizon)
        // a key that has been present since `s` is overdue when `until` is later than `s + d`
        started.collectFirst { case (k, s) if until > s + d => (k, s) } match
          case Some(found) => Some(found)
          case None        => go(more, started)
    go(series.toList, Map.empty)

  /** The first violation of a property of the series, with the instant. */
  def firstViolation[A](values: Vector[(Time, A)])(ok: A => Boolean): Option[(Time, A)] =
    values.find((_, a) => !ok(a))

  /** The failures of a recording, as instants and peers: connections that failed, were lost, or
    * could not be activated.
    */
  def failures(rec: Recording): Vector[(Time, Peer)] =
    rec.traces.collect {
      case (t, Trace.PromoteFailed(_, peer, _, _, _))         => t -> peer
      case (t, Trace.DemoteAsynchronous(peer, _, _, _, _, _)) => t -> peer
    }
