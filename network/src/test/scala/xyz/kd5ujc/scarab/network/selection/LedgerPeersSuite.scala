// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}

/** Ledger peers and big ledger peers, against `Ouroboros.Network.PeerSelection.LedgerPeers` and
  * `.LedgerPeers.Utils` of ouroboros-network 1.2.0.0 (properties of the reference's
  * `Test.Ouroboros.Network.LedgerPeers`: `prop_pick100`, `prop_pick`,
  * `prop_accumulateBigLedgerStake`, `prop_recomputeRelativeStake`,
  * `prop_ledgerPeerSnapshot_requests`). The relay is a `String`; the pools are given as shares of 1
  * with their weights in tenths or by the exact fractions below.
  */
class LedgerPeersSuite extends ScalaCheckSuite:
  import LedgerKind.{All, Big}

  private def r(n: Long, d: Long = 1): Rational = Rational(n, d)

  private def pool(share: Rational, relays: String*): LedgerPool[String] =
    LedgerPool(PoolStake(share), NonEmptyVector.of(relays.head, relays.tail*))

  /** Pools "p0", "p1", ... with the given integer stakes, as shares of their sum. */
  private def pools(stakes: Long*): Vector[LedgerPool[String]] =
    val total = stakes.sum
    stakes.zipWithIndex.toVector.map((s, i) => pool(r(s, total), s"p$i"))

  private def keys[K <: LedgerKind](m: StakeMap[K, String]): Vector[Rational] =
    m.entries.map(_._1.share)
  private def names[K <: LedgerKind](m: StakeMap[K, String]): Vector[String] =
    m.entries.map(_._2.relays.head)

  private val seed = Rng(0x5eed1234abcdL)

  // ---------------------------------------------------------------------------- the quota

  test("big ledger peers hold 90%: the pool that crosses the quota is kept") {
    // 60, 35, 5 percent: after the first two the accumulated stake is 95% and the third is not kept
    val big = LedgerPeerStake.accumulate(pools(60, 35, 5))
    assertEquals(names(big), Vector("p0", "p1"))
    assertEquals(keys(big), Vector(r(60, 100), r(95, 100)))
  }

  test("when the stake reaches exactly 90% the next pool is kept as well") {
    // 50, 30, 10, 5, 3, 2: the accumulated stake is 50, 80, 90, 95, ...; 90 <= 0.9 so p3 is kept
    val big = LedgerPeerStake.accumulate(pools(50, 30, 10, 5, 3, 2))
    assertEquals(names(big), Vector("p0", "p1", "p2", "p3"))
    assertEquals(keys(big).last, r(95, 100))
  }

  test("pools are taken largest first, and equal stakes keep the order given") {
    val big = LedgerPeerStake.accumulate(pools(10, 40, 40, 10))
    // p1 and p2 are 80%, p0 brings it to exactly 90%, so p3 is kept as well
    assertEquals(names(big), Vector("p1", "p2", "p0", "p3"))
  }

  test("a single pool is the whole stake and a big ledger peer") {
    val big = LedgerPeerStake.accumulate(pools(7))
    assertEquals(keys(big), Vector(Rational.one))
  }

  test("pools without any stake give no ledger peers (the reference divides by zero)") {
    val none = Vector(pool(Rational.zero, "a"), pool(Rational.zero, "b"))
    assertEquals(LedgerPeerStake.accumulate(none).size, 0)
    assertEquals(LedgerPeerStake.weighted(none).size, 0)
    assertEquals(
      LedgerPeerStake.fromLedger(Vector.empty[LedgerPool[String]]),
      StakeMaps.empty[String]
    )
  }

  /** Stakes from 1 to 1000 for 1 to 60 pools. */
  private val someStakes: Gen[Vector[Long]] =
    Gen.choose(1, 60).flatMap(n => Gen.listOfN(n, Gen.choose(1L, 1000L))).map(_.toVector)

  property("accumulateBigLedgerStake: sorted, cumulative, and cut at the quota") {
    forAll(someStakes) { stakes =>
      val entries = LedgerPeerStake.accumulated(pools(stakes*))
      val shares = entries.map(_._2.stake.share)
      val acc = entries.map(_._1.share)
      val sortedDown = shares.zip(shares.drop(1)).forall(_ >= _)
      val cumulative = acc == shares.scanLeft(Rational.zero)(_ + _).drop(1)
      val beforeLast = acc.dropRight(1).forall(_ <= LedgerPeerStake.BigQuota.share)
      val pastOrAll = acc.lastOption.exists(l =>
        l > LedgerPeerStake.BigQuota.share
      ) || entries.size == stakes.size
      sortedDown && cumulative && beforeLast && pastOrAll
    }
  }

  property("recomputeRelativeStake: the shares sum to one, for both weightings") {
    forAll(someStakes) { stakes =>
      val ps = pools(stakes*)
      val all =
        LedgerPeerStake.recompute[All, String](ps).map(_.stake.share).foldLeft(Rational.zero)(_ + _)
      val big =
        LedgerPeerStake.recompute[Big, String](ps).map(_.stake.share).foldLeft(Rational.zero)(_ + _)
      all == Rational.one && big == Rational.one
    }
  }

  test("the ordinary weighting is the square root of the stake, the big weighting the stake") {
    val ps = pools(64, 25, 11)
    val all = LedgerPeerStake.recompute[All, String](ps).map(_.stake.share)
    val roots =
      ps.map(p => math.sqrt(p.stake.share.numerator.toDouble / p.stake.share.denominator.toDouble))
    val expected = roots.map(_ / roots.sum)
    all
      .zip(expected)
      .foreach((got, want) =>
        assertEqualsDouble(got.numerator.toDouble / got.denominator.toDouble, want, 1e-12)
      )
    assertEquals(
      LedgerPeerStake.recompute[Big, String](ps).map(_.stake.share),
      ps.map(_.stake.share)
    )
  }

  test("the root is taken through a Double and a zero stake has the weight zero") {
    assertEquals(LedgerPeerStake.sqrtThroughDouble(Rational.zero), Rational.zero)
    assertEquals(LedgerPeerStake.sqrtThroughDouble(r(1, 4)), r(1, 2))
    assertEquals(LedgerPeerStake.sqrtThroughDouble(r(-1, 4)), Rational.zero)
  }

  test("the accumulated stake of all ledger peers is in the order given and ends at one") {
    val all = LedgerPeerStake.weighted(pools(1, 4, 9))
    assertEquals(names(all), Vector("p0", "p1", "p2"))
    assertEquals(keys(all).last, Rational.one)
    // sqrt weights 1, 2, 3 of 6
    assertEquals(keys(all), Vector(r(1, 6), r(3, 6), r(6, 6)))
  }

  test("a key given twice keeps the last pool, as Map.fromList does") {
    val zeroFirst =
      Vector(pool(Rational.zero, "z0"), pool(Rational.zero, "z1"), pool(Rational.one, "a"))
    val all = LedgerPeerStake.weighted(zeroFirst)
    assertEquals(names(all), Vector("z1", "a"))
  }

  // ---------------------------------------------------------------------------- the draw

  /** The share of the draws that gave each of the names, over `n` draws. */
  private def drawShares[K <: LedgerKind](maps: StakeMaps[String], n: Int, rng: Rng)(using
      Kind[K]
  ): Map[String, Double] =
    val (_, picked) = maps.draw[K](n, rng)
    assertEquals(picked.size, n)
    picked.groupMapReduce(identity)(_ => 1)(_ + _).view.mapValues(_.toDouble / n).toMap

  /** Three standard deviations of a share `p` over `n` draws, plus a little. */
  private def tolerance(p: Double, n: Int): Double = 4.5 * math.sqrt(p * (1 - p) / n) + 1e-4

  test("ordinary ledger peers are drawn in proportion to the square root of the stake") {
    // stakes .64, .25, .11 -> roots .8, .5, .3317; the reference's formula is root / sum of roots
    val ps = pools(64, 25, 11)
    val roots = Vector(0.8, 0.5, math.sqrt(0.11))
    val n = 200000
    val got = drawShares[All](LedgerPeerStake.fromLedger(ps), n, seed)
    roots.zipWithIndex.foreach { (root, i) =>
      val p = root / roots.sum
      assertEqualsDouble(got.getOrElse(s"p$i", 0.0), p, tolerance(p, n), s"share of p$i")
    }
  }

  test("big ledger peers are drawn in proportion to the stake, from the first 90% of it") {
    // .64, .25, .11 accumulate to .64, .89, 1.0; a draw is uniform on [0, .9]: p0 gets .64/.9,
    // p1 gets .25/.9 and p2 gets the .01/.9 that is left of the crossing pool
    val n = 200000
    val got = drawShares[Big](LedgerPeerStake.fromLedger(pools(64, 25, 11)), n, seed)
    Map("p0" -> 64.0 / 90, "p1" -> 25.0 / 90, "p2" -> 1.0 / 90).foreach { (name, p) =>
      assertEqualsDouble(got.getOrElse(name, 0.0), p, tolerance(p, n), s"share of $name")
    }
  }

  test("the draw picks the entry at or above the number, and a relay of that pool uniformly") {
    val maps =
      LedgerPeerStake.fromLedger(Vector(pool(r(1, 2), "a1", "a2", "a3"), pool(r(1, 2), "b")))
    val picked = maps.draw[Big](60000, seed)._2
    val a = picked.filter(_.startsWith("a")).groupMapReduce(identity)(_ => 1)(_ + _)
    assertEquals(a.keySet, Set("a1", "a2", "a3"))
    val each = a.values.sum / 3.0
    a.values.foreach(c => assertEqualsDouble(c.toDouble, each, 0.05 * each))
    // the entry at or above: the number 1/2 is the key of the first pool
    assertEquals(maps.big.lookupGE(r(1, 2)).map(_._2.relays.head), Some("a1"))
    assertEquals(maps.big.lookupGE(r(1, 2) + r(1, 1000000)).map(_._2.relays.head), Some("b"))
    assertEquals(maps.big.lookupGE(r(2)), None)
  }

  test("the draw is a pure function of the generator: one seed, one answer") {
    val maps = LedgerPeerStake.fromLedger(pools(5, 4, 3, 2, 1))
    assertEquals(maps.draw[All](50, seed), maps.draw[All](50, seed))
    assertNotEquals(maps.draw[All](50, seed)._2, maps.draw[All](50, Rng(7L))._2)
    // the generator advances by the draws, so two draws in a row are one draw of twice the size
    val (g1, first) = maps.draw[Big](10, seed)
    val (_, second) = maps.draw[Big](10, g1)
    assertEquals(first ++ second, maps.draw[Big](20, seed)._2)
  }

  test("prop_pick100: a pool with all the stake is always picked") {
    val withZero =
      Vector.tabulate(5)(i => pool(Rational.zero, s"zero$i")) :+ pool(Rational.one, "all")
    val maps = LedgerPeerStake.fromLedger(withZero)
    assertEquals(maps.draw[All](200, seed)._2.distinct, Vector("all"))
    assertEquals(maps.draw[Big](200, seed)._2.distinct, Vector("all"))
  }

  test("nothing is drawn from empty maps and the generator is returned as it came") {
    val empty = StakeMaps.empty[String]
    assertEquals(empty.draw[All](10, seed), (seed, Vector.empty[String]))
    assertEquals(empty.draw[Big](10, seed), (seed, Vector.empty[String]))
    val maps = LedgerPeerStake.fromLedger(pools(1))
    assertEquals(maps.draw[All](0, seed), (seed, Vector.empty[String]))
    assertEquals(maps.draw[All](-3, seed), (seed, Vector.empty[String]))
  }

  property(
    "prop_pick: given a pool, as many peers are picked as were asked for, whatever the kind"
  ) {
    forAll(someStakes, Gen.choose(0, 300), Arbitrary.arbitrary[Long]) { (stakes, count, s) =>
      val maps = LedgerPeerStake.fromLedger(pools(stakes*))
      maps.draw[All](count, Rng(s))._2.size == count && maps
        .draw[Big](count, Rng(s))
        ._2
        .size == count
    }
  }

  property("a big ledger peer drawn is one of the big pools") {
    forAll(someStakes, Arbitrary.arbitrary[Long]) { (stakes, s) =>
      val maps = LedgerPeerStake.fromLedger(pools(stakes*))
      val big = names(maps.big).toSet
      maps.draw[Big](100, Rng(s))._2.forall(big)
    }
  }

  // ---------------------------------------------------------------------------- answers

  test(
    "an answer falls back to the public roots when both maps are empty, and says when pools are fewer"
  ) {
    val cache = LedgerPeersCache(LedgerPeerStake.fromLedger(pools(1, 1)), None, Some(0.seconds))
    LedgerPeerStake.answer[All, String](cache, 5, seed) match
      case (_, LedgerAnswer.Picked(relays, notEnough)) =>
        assertEquals((relays.size, notEnough), (5, true))
      case other => fail(s"$other")
    LedgerPeerStake.answer[Big, String](cache, 2, seed) match
      case (_, LedgerAnswer.Picked(relays, notEnough)) =>
        assertEquals((relays.size, notEnough), (2, false))
      case other => fail(s"$other")
    assertEquals(
      LedgerPeerStake.answer[All, String](LedgerPeersCache.empty, 5, seed),
      (seed, LedgerAnswer.FallBackToPublicRoots[String]())
    )
  }

  // ---------------------------------------------------------------------------- the cache

  private val ledgerPools = pools(3, 2, 1)
  private val enabled = UseLedgerPeers.Use(AfterSlot.Always)

  test("the cache lives 30 s while empty and ledger peers are on, else 1847 s") {
    val empty = LedgerPeersCache.empty[String]
    val full = LedgerPeersCache(LedgerPeerStake.fromLedger(ledgerPools), None, Some(0.seconds))
    assertEquals(LedgerPeerStake.lifetime(empty, enabled), 30.seconds)
    assertEquals(LedgerPeerStake.lifetime(empty, UseLedgerPeers.Disabled), 1847.seconds)
    assertEquals(LedgerPeerStake.lifetime(full, enabled), 1847.seconds)
    assertEquals(LedgerPeerStake.EmptyLifetime, 30.seconds)
    assertEquals(LedgerPeerStake.PopulatedLifetime, 1847.seconds)
  }

  test("expired is a strict comparison of the age with the lifetime; never built is expired") {
    val empty = LedgerPeersCache.empty[String].copy(stamp = Some(100.seconds))
    assert(!LedgerPeerStake.expired(empty, enabled, 130.seconds), "age 30 s of 30 s")
    assert(LedgerPeerStake.expired(empty, enabled, 130.seconds + 1.nanosecond))
    val full = LedgerPeersCache(LedgerPeerStake.fromLedger(ledgerPools), None, Some(100.seconds))
    assert(!LedgerPeerStake.expired(full, enabled, 100.seconds + 1847.seconds))
    assert(LedgerPeerStake.expired(full, enabled, 100.seconds + 1847.seconds + 1.nanosecond))
    assert(LedgerPeerStake.expired(LedgerPeersCache.empty[String], enabled, 1.second))
  }

  private def refresh(
      cache: LedgerPeersCache[String],
      use: UseLedgerPeers,
      now: FiniteDuration,
      slot: WithOrigin[BigInt],
      snapshot: Option[LedgerPeerSnapshot[String]] = None
  ): LedgerPeersCache[String] =
    LedgerPeerStake.refresh(cache, use, now, slot, ledgerPools, snapshot)

  test(
    "a refresh keeps a live cache, rebuilds an expired one and empties it when ledger peers are off"
  ) {
    val built = refresh(LedgerPeersCache.empty, enabled, 10.seconds, WithOrigin.At(5))
    assertEquals(built.stamp, Some(10.seconds))
    assertEquals(built.maps, LedgerPeerStake.fromLedger(ledgerPools))
    // 20 s later it is the same cache, stamp and all
    assertEquals(refresh(built, enabled, 30.seconds, WithOrigin.At(6)), built)
    // 1857 s later it is rebuilt and stamped again
    assertEquals(
      refresh(built, enabled, 1857.seconds + 1.nanosecond, WithOrigin.At(6)).stamp,
      Some(1857.seconds + 1.nanosecond)
    )
    val off = refresh(built, UseLedgerPeers.Disabled, 4000.seconds, WithOrigin.At(6))
    assertEquals(off, LedgerPeersCache[String](StakeMaps.empty, None, Some(4000.seconds)))
  }

  test(
    "before useLedgerAfterSlot the ledger gives nothing, and the empty list is asked for again in 30 s"
  ) {
    val after = UseLedgerPeers.Use(AfterSlot.After(BigInt(100)))
    val before = refresh(LedgerPeersCache.empty, after, 10.seconds, WithOrigin.At(99))
    assert(before.maps.isEmpty)
    assertEquals(before.stamp, Some(10.seconds))
    assert(!LedgerPeerStake.expired(before, after, 40.seconds))
    assert(LedgerPeerStake.expired(before, after, 41.seconds))
    val onTime = refresh(before, after, 41.seconds, WithOrigin.At(100))
    assertEquals(onTime.maps, LedgerPeerStake.fromLedger(ledgerPools))
  }

  test("getLedgerPeers: always, at or after the slot, and never at the origin of the chain") {
    def got(after: AfterSlot, at: WithOrigin[BigInt]) =
      LedgerPeerStake.supply(after, at, ledgerPools) match
        case LedgerSupply.Pools(_)     => "pools"
        case LedgerSupply.BeforeSlot() => "before"
    assertEquals(got(AfterSlot.Always, WithOrigin.Origin), "pools")
    assertEquals(got(AfterSlot.Always, WithOrigin.At(0)), "pools")
    assertEquals(got(AfterSlot.After(10), WithOrigin.At(10)), "pools")
    assertEquals(got(AfterSlot.After(10), WithOrigin.At(9)), "before")
    assertEquals(got(AfterSlot.After(0), WithOrigin.Origin), "before")
    // the pools are not computed before the slot
    var computed = false
    LedgerPeerStake.supply(AfterSlot.After(10), WithOrigin.At(9), { computed = true; ledgerPools })
    assert(!computed)
  }

  // ---------------------------------------------------------------------------- the snapshot

  private def snapshotPools(stakes: Long*): Vector[SnapshotPool[String]] =
    val entries = LedgerPeerStake.accumulated(pools(stakes*))
    entries.map((acc, p) => SnapshotPool(acc, p.stake, p.relays))

  private def v2(slot: WithOrigin[BigInt], stakes: Long*) =
    LedgerPeerSnapshot.V2(slot, snapshotPools(stakes*))
  private def v23(slot: BigInt, stakes: Long*) =
    LedgerPeerSnapshot.V23(
      SnapshotPoint.Block(slot, Bytes.fromArray(Array.fill(32)(1.toByte))),
      2L,
      snapshotPools(stakes*)
    )

  private def choose(
      ledgerSlot: WithOrigin[BigInt],
      ledger: LedgerSupply[String],
      snapshot: Option[LedgerPeerSnapshot[String]],
      after: AfterSlot,
      cached: LedgerPeersCache[String] = LedgerPeersCache.empty
  ) = LedgerPeerStake.stakeMapWithSlotOverSource(ledgerSlot, ledger, snapshot, cached, after)

  private val ledgerSupply = LedgerSupply.Pools(ledgerPools)

  test(
    "a snapshot not older than the ledger and than useLedgerAfterSlot is used instead of the ledger"
  ) {
    val snapshot = v23(BigInt(500), 50, 30, 20)
    val chosen = choose(WithOrigin.At(500), ledgerSupply, Some(snapshot), AfterSlot.After(500))
    assertEquals(chosen.source, StakeSource.Snapshot)
    assertEquals(chosen.cachedSlot, Some(BigInt(500)))
    assertEquals(chosen.maps, LedgerPeerStake.fromSnapshot(snapshot))
    // both maps are the snapshot's: the map of all ledger peers is weighted from its pools too
    assertEquals(names(chosen.maps.big), Vector("p0", "p1", "p2"))
    assertEquals(keys(chosen.maps.big), snapshot.pools.map(_.accumulated.share))
    assertEquals(
      chosen.maps.all,
      LedgerPeerStake.weighted(snapshot.pools.map(p => LedgerPool(p.stake, p.relays)))
    )
  }

  test(
    "the ledger is used when it is ahead of the snapshot, or the snapshot is before the configured slot"
  ) {
    val snapshot = v2(WithOrigin.At(500), 10, 5, 1)
    val ahead = choose(WithOrigin.At(501), ledgerSupply, Some(snapshot), AfterSlot.Always)
    assertEquals(ahead.source, StakeSource.Ledger)
    assertEquals(ahead.cachedSlot, None)
    assertEquals(ahead.maps, LedgerPeerStake.fromLedger(ledgerPools))
    val early = choose(WithOrigin.At(400), ledgerSupply, Some(snapshot), AfterSlot.After(501))
    assertEquals(early.source, StakeSource.Ledger)
  }

  test("a snapshot at the origin never wins; the origin of the ledger counts as slot 0") {
    val atOrigin = v2(WithOrigin.Origin, 10, 5)
    assertEquals(
      choose(WithOrigin.Origin, ledgerSupply, Some(atOrigin), AfterSlot.Always).source,
      StakeSource.Ledger
    )
    val early = v2(WithOrigin.At(0), 10, 5)
    assertEquals(
      choose(WithOrigin.Origin, ledgerSupply, Some(early), AfterSlot.Always).source,
      StakeSource.Snapshot
    )
  }

  test("the snapshot is used while the ledger is before the slot, if it is itself past it") {
    val snapshot = v23(BigInt(1000), 10, 5)
    val chosen =
      choose(WithOrigin.At(10), LedgerSupply.BeforeSlot(), Some(snapshot), AfterSlot.After(900))
    assertEquals(chosen.source, StakeSource.Snapshot)
    val tooEarly =
      choose(WithOrigin.At(10), LedgerSupply.BeforeSlot(), Some(snapshot), AfterSlot.After(1001))
    assertEquals(tooEarly.source, StakeSource.Nothing)
    assert(tooEarly.maps.isEmpty)
  }

  test("without a snapshot the ledger's pools are used, and with neither there are no peers") {
    assertEquals(
      choose(WithOrigin.At(1), ledgerSupply, None, AfterSlot.Always).source,
      StakeSource.Ledger
    )
    assertEquals(
      choose(WithOrigin.At(1), LedgerSupply.BeforeSlot(), None, AfterSlot.Always).source,
      StakeSource.Nothing
    )
  }

  test("maps made from the same snapshot are kept, by its slot") {
    val snapshot = v23(BigInt(500), 10, 5, 1)
    val first = choose(WithOrigin.At(400), ledgerSupply, Some(snapshot), AfterSlot.Always)
    val cache = LedgerPeersCache(first.maps, first.cachedSlot, Some(1.second))
    val again = choose(WithOrigin.At(450), ledgerSupply, Some(snapshot), AfterSlot.Always, cache)
    assert(again.maps eq first.maps, "the maps are reused, not rebuilt")
    val newer = v23(BigInt(600), 10, 5)
    val rebuilt = choose(WithOrigin.At(450), ledgerSupply, Some(newer), AfterSlot.Always, cache)
    assertEquals(rebuilt.cachedSlot, Some(BigInt(600)))
    assertNotEquals(rebuilt.maps, first.maps)
  }

  property(
    "prop_ledgerPeerSnapshot_requests: the source is the snapshot exactly when it is at a block at or after both slots"
  ) {
    val slots = Gen
      .option(Gen.choose(0L, 50L))
      .map(_.fold[WithOrigin[BigInt]](WithOrigin.Origin)(s => WithOrigin.At(BigInt(s))))
    val afters = Gen
      .option(Gen.choose(0L, 50L))
      .map(_.fold[AfterSlot](AfterSlot.Always)(s => AfterSlot.After(BigInt(s))))
    forAll(slots, slots, afters, Gen.oneOf(true, false)) {
      (ledgerSlot, snapSlot, after, hasLedger) =>
        val snapshot = v2(snapSlot, 4, 3, 2, 1)
        val supply = if hasLedger then ledgerSupply else LedgerSupply.BeforeSlot[String]()
        val chosen = choose(ledgerSlot, supply, Some(snapshot), after)
        def n(w: WithOrigin[BigInt]): BigInt = w match
          case WithOrigin.Origin => BigInt(0)
          case WithOrigin.At(s)  => s
        val expectSnapshot = snapSlot != WithOrigin.Origin && n(snapSlot) >= n(ledgerSlot) && n(
          snapSlot
        ) >= after.slotNo
        if expectSnapshot then chosen.source == StakeSource.Snapshot
        else chosen.source == (if hasLedger then StakeSource.Ledger else StakeSource.Nothing)
    }
  }

  // ---------------------------------------------------------------------------- scale (ADR 0011)

  /** A mainnet-like distribution: Zipf with exponent 1 over `n` pools, the shape of the stake of
    * the pools of a large network (a few at the top hold a large share, a long tail holds the
    * rest); the relative stake of rank `k` is `1 / k` of the sum.
    */
  private def zipf(n: Int): Vector[LedgerPool[String]] =
    val unnormalised = Vector.tabulate(n)(i => Rational(1, i + 1))
    val total = unnormalised.foldLeft(Rational.zero)(_ + _)
    unnormalised.zipWithIndex.map((s, i) => pool(s / total, s"p$i"))

  test("a mainnet-like distribution: the top pools that hold 90% are the big ledger peers") {
    val n = 1500
    val big = LedgerPeerStake.accumulate(zipf(n))
    // an independent count with doubles: the first rank at which the cumulative share passes .9
    val harmonic = (1 to n).map(1.0 / _)
    val cumulative = harmonic.scanLeft(0.0)(_ + _).drop(1).map(_ / harmonic.sum)
    val passes = cumulative.indexWhere(_ > 0.9) + 1
    assert(math.abs(big.size - passes) <= 1, s"${big.size} big pools, ${passes} by doubles")
    assert(big.size > 300 && big.size < 1200, s"${big.size} of $n")
    // the largest pool first, and ranks in order
    assertEquals(names(big).take(3), Vector("p0", "p1", "p2"))
    assert(keys(big).dropRight(1).forall(_ <= r(9, 10)))
    assert(keys(big).last > r(9, 10))
    // ordinary ledger peers are all pools, with a flatter distribution than the stake
    val all = LedgerPeerStake.weighted(zipf(n))
    assertEquals(all.size, n)
    val firstShare = keys(all).head.numerator.toDouble / keys(all).head.denominator.toDouble
    assert(firstShare < 1.0 / harmonic.sum, s"p0 has $firstShare of the ordinary weight")
  }

  test("100,000 pools are accumulated, weighted and drawn from on a thread with a small stack") {
    var result: Option[Either[Throwable, (Int, Int, Int)]] = None
    val worker = new Thread(
      null,
      () =>
        result = Some(
          try
            val ps = Vector.tabulate(100000) { i =>
              LedgerPool(
                PoolStake(Rational(1 + (i * 7919L) % 10007L, 1000000L)),
                NonEmptyVector.one(s"p$i")
              )
            }
            val maps = LedgerPeerStake.fromLedger(ps)
            val picked = maps.draw[Big](50, seed)._2.size + maps.draw[All](50, seed)._2.size
            Right((maps.all.size, maps.big.size, picked))
          catch case t: Throwable => Left(t)
        ),
      "small-stack",
      256L * 1024
    )
    worker.start()
    worker.join()
    result match
      case Some(Right((all, big, picked))) =>
        assertEquals(all, 100000)
        assert(big > 50000 && big < 90000, s"$big big pools")
        assertEquals(picked, 100)
      case Some(Left(e)) => fail(s"the maps need a deep stack: $e")
      case None          => fail("no result")
  }
