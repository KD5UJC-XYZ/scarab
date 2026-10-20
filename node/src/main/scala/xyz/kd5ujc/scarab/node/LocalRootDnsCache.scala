// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyList
import scala.concurrent.duration.*

/** Pure per-source cache and refresh deadlines for local-root DNS results.
  *
  * The reference policy is `RootPeersDNS/LocalRootPeers.hs:195-253, 294-308`: a successful answer
  * replaces the source value (including an empty answer), with the maximum clipped answer TTL or
  * the minimum TTL for an empty answer. A failed answer retains the last successful value and uses
  * the earliest retry among its errors. Each key is independent; `track` prunes removed sources.
  */
object LocalRootDnsCache:
  private val MinInterval: FiniteDuration = 60.seconds
  private val MaxInterval: FiniteDuration = 900.seconds
  private val RetryIncrement: FiniteDuration = 5.seconds

  enum DnsFailureKind:
    case NameError
    case Other

  enum LookupResult[+A]:
    case Success(values: Vector[A], recordTtls: Vector[FiniteDuration])
    case Failure(errors: NonEmptyList[DnsFailureKind])

  enum CacheError:
    case StaleRequest

  final case class Request[K](key: K, token: BigInt)

  final case class Snapshot[A] private[node] (
      values: Vector[A],
      interval: FiniteDuration,
      refreshAt: FiniteDuration
  )

  private final case class Entry[K, A](
      key: K,
      token: BigInt,
      values: Vector[A],
      interval: FiniteDuration,
      refreshAt: FiniteDuration
  )

  final class State[K, A] private[LocalRootDnsCache] (
      private[LocalRootDnsCache] val entries: Vector[Entry[K, A]],
      private[LocalRootDnsCache] val nextToken: BigInt
  )

  def empty[K, A]: State[K, A] = new State(Vector.empty, BigInt(0))

  /** Keep active keys in caller order, retain unchanged entries, prune removed entries, and make
    * newly active sources immediately due. Tokens distinguish a re-added source from its old
    * in-flight lookup.
    */
  def track[K, A](state: State[K, A], activeKeys: Vector[K], now: FiniteDuration): State[K, A] =
    val existing = state.entries.iterator.map(entry => entry.key -> entry).toMap
    val distinctKeys = activeKeys.distinct
    val (entries, nextToken) = distinctKeys.foldLeft((Vector.empty[Entry[K, A]], state.nextToken)) {
      case ((accumulated, token), key) =>
        existing.get(key) match
          case Some(entry) => (accumulated :+ entry, token)
          case None =>
            val created = Entry[K, A](key, token, Vector.empty[A], Duration.Zero, now)
            (accumulated :+ created, token + 1)
    }
    new State(entries, nextToken)

  /** A topology reload keeps answers by DNS input even when that input moves between groups. Every
    * resulting entry has a fresh token, so a request from before the reload cannot complete against
    * the new configuration. Unchanged positions keep their own answer when a source occurs more
    * than once; a moved source takes the first previous occurrence deterministically. Lookups
    * become due immediately, as when the reference restarts its domain monitors.
    */
  def reconfigure[K, A, S](
      state: State[K, A],
      activeKeys: Vector[K],
      source: K => S,
      now: FiniteDuration
  ): State[K, A] =
    val byKey = state.entries.iterator.map(entry => entry.key -> entry).toMap
    val bySource = state.entries.groupBy(entry => source(entry.key))
    val (entries, nextToken) =
      activeKeys.distinct.foldLeft((Vector.empty[Entry[K, A]], state.nextToken)) {
        case ((accumulated, token), key) =>
          val previous = byKey.get(key).orElse(bySource.get(source(key)).flatMap(_.headOption))
          val entry = previous.fold(Entry(key, token, Vector.empty[A], Duration.Zero, now))(
            _.copy(key = key, token = token, refreshAt = now)
          )
          (accumulated :+ entry, token + 1)
      }
    new State(entries, nextToken)

  /** Due requests retain caller key order. A caller should dispatch each request once and pass its
    * token back to `complete`; stale results cannot mutate a pruned or re-added source.
    */
  def due[K, A](
      state: State[K, A],
      activeKeys: Vector[K],
      now: FiniteDuration
  ): Vector[Request[K]] =
    val byKey = state.entries.iterator.map(entry => entry.key -> entry).toMap
    activeKeys.distinct.flatMap { key =>
      byKey.get(key).filter(_.refreshAt <= now).map(entry => Request(key, entry.token))
    }

  /** Time until the nearest tracked key is due, clamped to zero when a request is ready now. */
  def nextDelay[K, A](state: State[K, A], now: FiniteDuration): Option[FiniteDuration] =
    state.entries.map(entry => (entry.refreshAt - now).max(Duration.Zero)).minOption

  def snapshot[K, A](state: State[K, A], key: K): Option[Snapshot[A]] =
    state.entries
      .find(_.key == key)
      .map(entry => Snapshot(entry.values, entry.interval, entry.refreshAt))

  def keys[K, A](state: State[K, A]): Vector[K] = state.entries.map(_.key)

  /** Apply one completed lookup only to the matching key incarnation. Success with no addresses
    * clears the old value. Failure keeps it and advances only this source's retry deadline.
    */
  def complete[K, A](
      state: State[K, A],
      request: Request[K],
      now: FiniteDuration,
      result: LookupResult[A]
  ): Either[CacheError, State[K, A]] =
    state.entries.indexWhere(_.key == request.key) match
      case -1 => Left(CacheError.StaleRequest)
      case index =>
        val entry = state.entries(index)
        if entry.token != request.token then Left(CacheError.StaleRequest)
        else
          val updated = result match
            case LookupResult.Success(values, ttls) =>
              val interval = ttls.map(clipPositiveTtl).maxOption.getOrElse(MinInterval)
              entry.copy(values = values, interval = interval, refreshAt = now + interval)
            case LookupResult.Failure(errors) =>
              val interval = errors.toList.map(retryInterval(entry.interval, _)).min
              entry.copy(interval = interval, refreshAt = now + interval)
          Right(new State(state.entries.updated(index, updated), state.nextToken))

  private def clipPositiveTtl(ttl: FiniteDuration): FiniteDuration =
    ttl.max(MinInterval).min(MaxInterval)

  private def retryInterval(
      current: FiniteDuration,
      failure: DnsFailureKind
  ): FiniteDuration =
    failure match
      case DnsFailureKind.NameError => MaxInterval
      case DnsFailureKind.Other =>
        (current * 2 + RetryIncrement).max(MinInterval).min(MaxInterval)
