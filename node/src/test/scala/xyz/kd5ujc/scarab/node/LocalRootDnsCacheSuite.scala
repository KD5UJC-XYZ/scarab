// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyList
import munit.FunSuite
import scala.concurrent.duration.*

import LocalRootDnsCache.*

class LocalRootDnsCacheSuite extends FunSuite:
  private def complete[K, A](
      state: State[K, A],
      request: Request[K],
      now: FiniteDuration,
      result: LookupResult[A]
  ): State[K, A] =
    LocalRootDnsCache
      .complete(state, request, now, result)
      .fold(error => fail(s"cache completion failed: $error"), identity)

  private def requestFor[K, A](state: State[K, A], key: K, now: FiniteDuration): Request[K] =
    LocalRootDnsCache
      .due(state, Vector(key), now)
      .headOption
      .getOrElse(fail(s"expected $key to be due at $now"))

  test("deadlines and successful TTLs are independent per source") {
    val now = Duration.Zero
    val state0 =
      LocalRootDnsCache.track(LocalRootDnsCache.empty[String, String], Vector("a", "b"), now)
    val requestA = requestFor(state0, "a", now)
    val requestB = requestFor(state0, "b", now)
    val state1 = complete(
      state0,
      requestA,
      now,
      LookupResult.Success(Vector("a1"), Vector(75.seconds, 240.seconds))
    )
    val state2 =
      complete(state1, requestB, now, LookupResult.Success(Vector("b1"), Vector(60.seconds)))

    assertEquals(LocalRootDnsCache.nextDelay(state2, now), Some(60.seconds))
    assertEquals(LocalRootDnsCache.due(state2, Vector("a", "b"), 60.seconds), Vector(requestB))
    assertEquals(
      LocalRootDnsCache.due(state2, Vector("a", "b"), 240.seconds),
      Vector(requestA, requestB)
    )
  }

  test("success with an empty answer replaces cached values and uses the minimum interval") {
    val state0 =
      LocalRootDnsCache.track(LocalRootDnsCache.empty[String, String], Vector("domain"), 0.seconds)
    val request = requestFor(state0, "domain", 0.seconds)
    val populated =
      complete(state0, request, 0.seconds, LookupResult.Success(Vector("ip"), Vector(10.seconds)))
    val refreshed = requestFor(populated, "domain", 60.seconds)
    val empty =
      complete(populated, refreshed, 60.seconds, LookupResult.Success(Vector.empty, Vector.empty))

    assertEquals(
      LocalRootDnsCache.snapshot(empty, "domain"),
      Some(Snapshot(Vector.empty, 60.seconds, 120.seconds))
    )
    assertEquals(LocalRootDnsCache.due(empty, Vector("domain"), 119.seconds), Vector.empty)
    assertEquals(LocalRootDnsCache.due(empty, Vector("domain"), 120.seconds), Vector(refreshed))
  }

  test("failures retain the last success and apply per-key retry backoff") {
    val start =
      LocalRootDnsCache.track(LocalRootDnsCache.empty[String, String], Vector("domain"), 0.seconds)
    val first = requestFor(start, "domain", 0.seconds)
    val afterInitialFailure = complete(
      start,
      first,
      0.seconds,
      LookupResult.Failure(NonEmptyList.one(DnsFailureKind.Other))
    )
    assertEquals(
      LocalRootDnsCache.snapshot(afterInitialFailure, "domain"),
      Some(Snapshot(Vector.empty, 60.seconds, 60.seconds))
    )

    val second = requestFor(afterInitialFailure, "domain", 60.seconds)
    val afterSuccess = complete(
      afterInitialFailure,
      second,
      60.seconds,
      LookupResult.Success(Vector("stable-ip"), Vector(90.seconds))
    )
    val third = requestFor(afterSuccess, "domain", 150.seconds)
    val afterOther = complete(
      afterSuccess,
      third,
      150.seconds,
      LookupResult.Failure(NonEmptyList.one(DnsFailureKind.Other))
    )
    assertEquals(
      LocalRootDnsCache.snapshot(afterOther, "domain"),
      Some(Snapshot(Vector("stable-ip"), 185.seconds, 335.seconds))
    )
    val fourth = requestFor(afterOther, "domain", 335.seconds)
    val afterNameError = complete(
      afterOther,
      fourth,
      335.seconds,
      LookupResult.Failure(NonEmptyList.one(DnsFailureKind.NameError))
    )
    assertEquals(
      LocalRootDnsCache.snapshot(afterNameError, "domain"),
      Some(Snapshot(Vector("stable-ip"), 900.seconds, 1235.seconds))
    )
  }

  test("multiple errors choose the earliest retry deadline") {
    val state0 =
      LocalRootDnsCache.track(LocalRootDnsCache.empty[String, String], Vector("domain"), 0.seconds)
    val first = requestFor(state0, "domain", 0.seconds)
    val state1 = complete(
      state0,
      first,
      0.seconds,
      LookupResult.Success(Vector("ip"), Vector(60.seconds))
    )
    val second = requestFor(state1, "domain", 60.seconds)
    val failed = complete(
      state1,
      second,
      60.seconds,
      LookupResult.Failure(NonEmptyList.of(DnsFailureKind.NameError, DnsFailureKind.Other))
    )
    assertEquals(
      LocalRootDnsCache.snapshot(failed, "domain"),
      Some(Snapshot(Vector("ip"), 125.seconds, 185.seconds))
    )
  }

  test("tracking preserves active entries, prunes removed keys, and rejects stale completions") {
    val state0 =
      LocalRootDnsCache.track(LocalRootDnsCache.empty[String, String], Vector("a", "b"), 0.seconds)
    val requestA = requestFor(state0, "a", 0.seconds)
    val cached = complete(
      state0,
      requestA,
      0.seconds,
      LookupResult.Success(Vector("a-ip"), Vector(300.seconds))
    )
    val sameKeys = LocalRootDnsCache.track(cached, Vector("b", "a", "a"), 10.seconds)
    assertEquals(LocalRootDnsCache.keys(sameKeys), Vector("b", "a"))
    assertEquals(LocalRootDnsCache.snapshot(sameKeys, "a"), LocalRootDnsCache.snapshot(cached, "a"))

    val removed = LocalRootDnsCache.track(sameKeys, Vector("b"), 20.seconds)
    assertEquals(LocalRootDnsCache.keys(removed), Vector("b"))
    val readded = LocalRootDnsCache.track(removed, Vector("a", "b"), 30.seconds)
    val requestAgain = requestFor(readded, "a", 30.seconds)
    assertNotEquals(requestAgain.token, requestA.token)
    assertEquals(
      LocalRootDnsCache.complete(
        readded,
        requestA,
        40.seconds,
        LookupResult.Success(Vector("stale-ip"), Vector(900.seconds))
      ),
      Left(CacheError.StaleRequest)
    )
    assertEquals(
      LocalRootDnsCache.snapshot(readded, "a"),
      Some(Snapshot(Vector.empty, Duration.Zero, 30.seconds))
    )
  }

  test("empty state has no deadline and new sources are immediately due") {
    val empty = LocalRootDnsCache.empty[String, Int]
    assertEquals(LocalRootDnsCache.nextDelay(empty, 0.seconds), None)
    val tracked = LocalRootDnsCache.track(empty, Vector("a"), 15.seconds)
    assertEquals(LocalRootDnsCache.nextDelay(tracked, 15.seconds), Some(Duration.Zero))
    assertEquals(LocalRootDnsCache.due(tracked, Vector("a"), 15.seconds), Vector(Request("a", 0)))
  }

  test("reconfiguration retains moved sources, prunes removed names and fences every old request") {
    val oldKey = (0, "kept")
    val removedKey = (1, "removed")
    val movedKey = (2, "kept")
    val newKey = (0, "new")
    val tracked = LocalRootDnsCache.track(
      LocalRootDnsCache.empty[(Int, String), String],
      Vector(oldKey, removedKey),
      0.seconds
    )
    val oldRequest = requestFor(tracked, oldKey, 0.seconds)
    val cached = complete(
      tracked,
      oldRequest,
      0.seconds,
      LookupResult.Success(Vector("kept-ip"), Vector(300.seconds))
    )
    val reconfigured =
      LocalRootDnsCache.reconfigure(cached, Vector(movedKey, newKey), _._2, 10.seconds)
    assertEquals(LocalRootDnsCache.keys(reconfigured), Vector(movedKey, newKey))
    assertEquals(
      LocalRootDnsCache.snapshot(reconfigured, movedKey),
      Some(Snapshot(Vector("kept-ip"), 300.seconds, 10.seconds))
    )
    assertEquals(
      LocalRootDnsCache.snapshot(reconfigured, newKey),
      Some(Snapshot(Vector.empty, Duration.Zero, 10.seconds))
    )
    val sameKey = LocalRootDnsCache.reconfigure(cached, Vector(oldKey), _._2, 10.seconds)
    assertEquals(
      LocalRootDnsCache.complete(
        sameKey,
        oldRequest,
        11.seconds,
        LookupResult.Success(Vector("stale-ip"), Vector(900.seconds))
      ),
      Left(CacheError.StaleRequest)
    )
  }
