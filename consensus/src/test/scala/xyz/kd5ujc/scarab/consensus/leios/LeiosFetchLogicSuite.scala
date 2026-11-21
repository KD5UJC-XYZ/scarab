// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

import LeiosOrders.given

/** The Leios fetch decision against the reference's own cases: those of `Test/LeiosDemoLogic.hs`
  * (the body fetch, the self-forged endorser block, the priority order) and the pure ones of
  * `Test/LeiosDemoLogic/Invariants.hs` (acquisition, pruning, onsets, the start-up seeding, the
  * big-ledger budget, the job draw, the focus), both of ouroboros-consensus 2416493b, each built as
  * the reference's fixture builds it; and the bookkeeping the arrivals do, as the reference's
  * handlers call it.
  */
class LeiosFetchLogicSuite extends ScalaCheckSuite:
  private type Pid = Int
  private val peerA: Pid = 0
  private val peerB: Pid = 1

  private def slot(n: Long): Word64 = Word64.from(BigInt(n)).fold(e => fail(e.toString), identity)
  private def eb(c: Char): EbHash = EbHash.fromBytes(Bytes(Vector.fill(32)(c.toByte)))
  private def point(s: Long, c: Char): EbPoint = EbPoint(slot(s), eb(c))
  private def size(n: Long): BytesSize = BytesSize.wrap(n)

  // `fixtureIssuer`: one pool announced everything, so an election is just its slot
  private val fixtureIssuer = Bytes(Vector[Byte](0))

  private val env = LeiosFetchStaticEnv.demo
  private def empty: LeiosOutstanding[Pid] = LeiosOutstanding.empty[Pid](StdGen.mk(0), slot(0))
  private val anyClosureSize: Word64 => Either[Unit, BytesSize] = _ => Right(size(0xffffffffL))

  /** `Scenario`: the state, the peers' offers and the size each point is offered at. */
  private final case class Scenario(
      outstanding: LeiosOutstanding[Pid],
      offerings: SortedMap[Pid, SortedMap[EbPoint, WhetherTxsClosureOffered]],
      sizes: Map[EbPoint, BytesSize]
  ):
    /** `withMissingBody`: tracked and focused, as the announcement path would. */
    def withMissingBody(p: EbPoint, n: Long): Scenario =
      copy(
        outstanding = outstanding
          .recordMaxAnnouncementSlot(p.hash, p.slot, None)
          .focusElectionIfUnfocused(ElId(p.slot, fixtureIssuer), p.hash),
        sizes = sizes.updated(p, size(n))
      )
    def withForgedEb(p: EbPoint): Scenario =
      copy(outstanding = outstanding.markBodyImminent(p.hash, p.slot))
    def alreadyRequestedEbFrom(h: EbHash, peers: Pid*): Scenario =
      copy(outstanding =
        outstanding.copy(requestedEbPeers =
          outstanding.requestedEbPeers.updated(h, NonEmptySet.of(peers.head, peers.tail*))
        )
      )
    def withRequestedBytesPerPeer(peer: Pid, n: BytesSize): Scenario =
      copy(outstanding =
        outstanding.copy(requestedBytesSizePerPeer =
          outstanding.requestedBytesSizePerPeer.updated(peer, n)
        )
      )
    def offers(peer: Pid, closure: WhetherTxsClosureOffered, points: EbPoint*): Scenario =
      val old = offerings.getOrElse(peer, SortedMap.empty[EbPoint, WhetherTxsClosureOffered])
      copy(offerings =
        offerings.updated(peer, points.foldLeft(old)((m, p) => m.updated(p, closure)))
      )
    def offersBody(peer: Pid, points: EbPoint*): Scenario =
      offers(peer, WhetherTxsClosureOffered.NotOffered, points*)
    def offersBodyAndClosure(peer: Pid, points: EbPoint*): Scenario =
      offers(peer, WhetherTxsClosureOffered.Offered, points*)

    /** `runIteration`: any known slot, no big-ledger peer. */
    def run: SortedMap[Pid, NonEmptyVector[LeiosFetchRequest]] =
      LeiosFetchLogic
        .iteration(
          env,
          anyClosureSize,
          Some(slot(0)),
          offerings.map((peer, offers) =>
            peer -> offers.map((p, c) => p -> PeerOffer(None, sizes.get(p), c))
          ),
          Map.empty,
          outstanding
        )
        .requests

  private val scenario = Scenario(empty, SortedMap.empty, Map.empty)

  private def bodyRequests(reqs: NonEmptyVector[LeiosFetchRequest]): Vector[(EbHash, BytesSize)] =
    reqs.toVector.collect { case LeiosFetchRequest.Block(p, sz, _) => (p.hash, sz) }

  test("single missing body with one offering peer issues one request") {
    val reqs = scenario.withMissingBody(point(1, 'a'), 1024).offersBody(peerA, point(1, 'a')).run
    assertEquals(reqs.get(peerA).map(bodyRequests), Some(Vector(eb('a') -> size(1024))))
  }

  test("no request when no peer offers the body") {
    val reqs = scenario.withMissingBody(point(1, 'a'), 1024).offersBody(peerA, point(1, 'b')).run
    assertEquals(reqs.keySet, Set.empty[Pid])
  }

  test("a peer already asked for a body is not asked again") {
    val reqs = scenario
      .withMissingBody(point(1, 'a'), 1024)
      .alreadyRequestedEbFrom(eb('a'), peerA)
      .offersBody(peerA, point(1, 'a'))
      .offersBody(peerB, point(1, 'a'))
      .run
    assertEquals(reqs.keySet, Set(peerB))
  }

  test("both offering peers are selected (no per-EB cap)") {
    val reqs = scenario
      .withMissingBody(point(1, 'a'), 1024)
      .offersBody(peerA, point(1, 'a'))
      .offersBody(peerB, point(1, 'a'))
      .run
    assertEquals(reqs.size, 2)
  }

  test("per-peer byte budget exhausted skips that peer") {
    val reqs = scenario
      .withMissingBody(point(1, 'a'), 1024)
      .withRequestedBytesPerPeer(peerA, env.maxRequestedBytesSizePerPeer + size(1))
      .offersBody(peerA, point(1, 'a'))
      .offersBody(peerB, point(1, 'a'))
      .run
    assertEquals(reqs.keySet, Set(peerB))
  }

  test("an offer of a self-forged EB is never re-fetched") {
    val reqs = scenario.withForgedEb(point(1, 'a')).offersBodyAndClosure(peerA, point(1, 'a')).run
    assertEquals(reqs.keySet, Set.empty[Pid])
  }

  test("freshest window oldest-first, then rest freshest-first") {
    val offers =
      SortedMap.from(Vector[Long](0, 105, 90, 50, 100, 89, 101, 95).map(a => point(a, 'x') -> ()))
    val (hi, lo) = LeiosFetchLogic.priorityTiers(Some(slot(100)), BigInt(10), offers)
    assertEquals(
      (hi ++ lo).map(_._1.slot.toBigInt),
      Vector[BigInt](90, 95, 100, 101, 105, 89, 50, 0)
    )
    // with the slot unknown, everything is the high tier, oldest first
    val (all, none) = LeiosFetchLogic.priorityTiers(None, BigInt(10), offers)
    assertEquals(all.map(_._1.slot.toBigInt), Vector[BigInt](0, 50, 89, 90, 95, 100, 101, 105))
    assertEquals(none, Vector.empty)
  }

  // the transactions of the reference's invariant fixtures
  private def txBytesOf(i: Int): Bytes = Bytes((i + 1).toByte +: Vector.fill[Byte](31)(0))
  private def txHashOf(i: Int): LeiosTxHash =
    LeiosTxHash.fromBytes(Blake2b.hash256.hash(txBytesOf(i)))
  private def txSizeOf(i: Int): BytesSize = size(txBytesOf(i).size.toLong)
  private def misses(ids: Seq[Int]): SortedMap[Int, (LeiosTxHash, BytesSize)] =
    SortedMap.from(ids.zipWithIndex.map((i, off) => off -> (txHashOf(i), txSizeOf(i))))
  private def demoPool(ids: Seq[Int]): LeiosJobPool =
    LeiosJobPool.of(env.maxJobBytesSize, env.maxJobTxCount, misses(ids))

  test("a body is claimed acquired only by a settled write, and once") {
    val h = eb('a')
    val pool = demoPool(Seq(0, 1))
    val announced = empty.recordMaxAnnouncementSlot(h, slot(5), None)
    assertEquals(announced.ebState.get(h).map(_.fetch), Some(EbFetchState.NoBody))
    assertEquals(
      announced.acquireEbBody(h, pool).ebState.get(h),
      Some(EbState(slot(5), None, EbFetchState.BodyAcquired(pool)))
    )
    // a concurrent redundant delivery keeps the pool it found
    assertEquals(
      announced
        .acquireEbBody(h, pool)
        .acquireEbBody(h, LeiosJobPool.empty)
        .ebState
        .get(h)
        .map(_.fetch),
      Some(EbFetchState.BodyAcquired(pool))
    )
    // an endorser block pruned before its write landed is not tracked again
    assertEquals(empty.acquireEbBody(h, pool), empty)
  }

  test("acquired EB kept until its greatest slot is below the immutable tip") {
    val h = eb('a')
    val pool = LeiosJobPool.of(size(1000), 10, SortedMap.empty)
    val o = empty
      .recordMaxAnnouncementSlot(h, slot(5), None)
      .recordMaxAnnouncementSlot(h, slot(3), None)
      .acquireEbBody(h, pool)
    assertEquals(o.ebState.get(h), Some(EbState(slot(5), None, EbFetchState.BodyAcquired(pool))))
    assertEquals(o.prunedToImmTip(slot(4))._2.ebState.get(h), o.ebState.get(h))
    val (dropped, pruned) = o.prunedToImmTip(slot(6))
    assertEquals(pruned.ebState.get(h), None)
    assertEquals(dropped, Set(h))
    assertEquals(pruned.outstandingPrunedSlot, slot(6))
  }

  test("an announcement raises a forged EB's max slot (so it isn't pruned early)") {
    val h = eb('a')
    val o = empty.markBodyImminent(h, slot(5)).recordMaxAnnouncementSlot(h, slot(10), None)
    assertEquals(o.ebState.get(h), Some(EbState(slot(10), None, EbFetchState.BodyImminent)))
    assert(o.prunedToImmTip(slot(9))._2.ebState.contains(h))
    assert(!o.prunedToImmTip(slot(11))._2.ebState.contains(h))
    // a forged body is not missing
    assertEquals(o.numMissingBodies, 0)
  }

  test("an announcement's onset is recorded (earliest kept); an offer never clobbers it") {
    import scala.concurrent.duration.*
    val h = eb('a')
    def onsetOf(o: LeiosOutstanding[Pid]) = o.ebState.get(h).map(_.onset)
    assertEquals(
      onsetOf(empty.recordMaxAnnouncementSlot(h, slot(5), Some(5.seconds))),
      Some(Some(5.seconds))
    )
    assertEquals(
      onsetOf(
        empty
          .recordMaxAnnouncementSlot(h, slot(5), Some(5.seconds))
          .recordMaxAnnouncementSlot(h, slot(8), Some(3.seconds))
      ),
      Some(Some(3.seconds))
    )
    assertEquals(
      onsetOf(
        empty
          .recordMaxAnnouncementSlot(h, slot(5), Some(5.seconds))
          .recordMaxAnnouncementSlot(h, slot(9), None)
      ),
      Some(Some(5.seconds))
    )
    assertEquals(onsetOf(empty.markBodyImminent(h, slot(5))), Some(None))
  }

  test("start-up seeding marks each completed EB held, with an empty pool") {
    val (hA, hB) = (eb('a'), eb('b'))
    val o = LeiosOutstanding.initialized[Pid](
      StdGen.mk(0),
      Vector(EbPoint(slot(5), hA), EbPoint(slot(8), hA), EbPoint(slot(6), hB)),
      slot(4)
    )
    assertEquals(
      o.ebState.get(hB),
      Some(EbState(slot(6), None, EbFetchState.BodyAcquired(LeiosJobPool.empty)))
    )
    assertEquals(
      o.ebState.get(hA),
      Some(EbState(slot(8), None, EbFetchState.BodyAcquired(LeiosJobPool.empty)))
    )
    assert(o.ebState.values.forall(_.hasBody))
    assertEquals(o.numMissingBodies, 0)
    assert(
      o.requestedBytesSizePerPeer.isEmpty && o.requestedEbPeers.isEmpty && o.requestedJobsPerPeer.isEmpty
    )
    assertEquals(o.outstandingPrunedSlot, slot(4))
  }

  test("start-up seeding: a peer's offer of a seeded EB is not re-fetched") {
    val points = Vector(EbPoint(slot(8), eb('a')), EbPoint(slot(6), eb('b')))
    val o = LeiosOutstanding.initialized[Pid](StdGen.mk(0), points, slot(4))
    val offers = SortedMap(
      peerA -> SortedMap.from(
        points.map(p => p -> PeerOffer(None, Some(size(99)), WhetherTxsClosureOffered.Offered))
      )
    )
    val decision =
      LeiosFetchLogic.iteration(env, anyClosureSize, Some(slot(10)), offers, Map.empty, o)
    assertEquals(decision.requests, SortedMap.empty[Pid, NonEmptyVector[LeiosFetchRequest]])
  }

  test("a big-ledger peer has a larger, but still finite, closure budget") {
    val ids = Vector(0, 1, 2, 3, 4)
    val h = eb('c')
    val p = EbPoint(slot(10), h)
    val offers =
      SortedMap(peerA -> SortedMap(p -> PeerOffer(None, None, WhetherTxsClosureOffered.Offered)))
    def run(big: Map[Pid, IsBigLedgerPeer], used: BytesSize): SortedSet[Int] =
      val o = empty
        .recordMaxAnnouncementSlot(h, slot(10), None)
        .acquireEbBody(h, demoPool(ids))
        .focusElectionIfUnfocused(ElId(slot(10), Bytes(Vector[Byte](1))), h)
      val withUsed = o.copy(requestedBytesSizePerPeer = SortedMap(peerA -> used))
      val reqs = LeiosFetchLogic
        .iteration(env, anyClosureSize, Some(slot(11)), offers, big, withUsed)
        .requests
      SortedSet.from(
        reqs.values
          .flatMap(_.toVector)
          .collect { case LeiosFetchRequest.BlockTxs(_, jobs) =>
            jobs.toSortedMap.values.flatMap(_.offsets.toSortedSet)
          }
          .flatten
      )
    val ordinaryCap = env.maxRequestedBytesSizePerPeer
    val bigCap = env.maxRequestedBytesSizePerBigLedgerPeer
    assertEquals(run(Map.empty, ordinaryCap + size(1)), SortedSet.empty[Int])
    assertEquals(
      run(Map(peerA -> IsBigLedgerPeer.BigLedgerPeer), ordinaryCap + size(1)),
      SortedSet.from(ids)
    )
    assertEquals(
      run(Map(peerA -> IsBigLedgerPeer.BigLedgerPeer), bigCap + size(1)),
      SortedSet.empty[Int]
    )
  }

  test("job assignment draws within the least-requested bucket, at random, respecting exclusions") {
    // `maxJobTxCount` 1: each transaction is its own job, all at multiplicity 0
    val pool0 = LeiosJobPool.of(size(1000000), 1, misses(0 to 5))
    def pickId(pool: LeiosJobPool, excluded: SortedSet[Int], s: Long): Option[Int] =
      pool.pickLeastRequestedExcept(StdGen.mk(s), excluded).map(_._1.toInt)
    val drawn = (0L to 99L).flatMap(pickId(pool0, SortedSet.empty, _)).toSet
    assert(drawn.sizeIs > 1, "shuffles")
    assert(drawn.subsetOf((0 to 5).toSet))
    assertEquals((0L to 50L).flatMap(pickId(pool0, SortedSet(0, 1, 2, 3, 4), _)).toSet, Set(5))
    val pool1 = pool0
      .pickLeastRequestedExcept(StdGen.mk(0), SortedSet(1, 2, 3, 4, 5))
      .fold(fail("the sole job was not picked"))(_._3)
    val drawn1 = (0L to 50L).flatMap(pickId(pool1, SortedSet.empty, _)).toSet
    assert(!drawn1.contains(0), "the least-requested bucket wins")
    assert(drawn1.sizeIs > 1)
    // every job excluded: nothing, and no draw
    assertEquals(pool0.pickLeastRequestedExcept(StdGen.mk(0), SortedSet.from(0 to 5)), None)
  }

  test("the jobs drawn are the reference's: its StdGen's uniform index into the ascending bucket") {
    // The orders `Picks.hs` (scripts/oracle/leios-stdgen) printed on 2026-11-21 with the Leios
    // build's `random-1.3.1-126384cd` (GHC 9.10.3, the Leios reference image). The program is a
    // transcription of the draw loop of `pickLeastRequestedJobExcept` and `pickJobs`, not the
    // reference's functions: one bucket of ids, `uniformR (0, size - 1)` of the generator the last
    // draw returned picking the id at that index of the ascending list, and that id then excluded.
    def order(seed: Long, n: Int): Vector[Int] =
      LeiosFetchLogic
        .pickJobs(
          StdGen.mk(seed),
          SortedSet.empty,
          LeiosJobPool.of(size(1000000), 1, misses(0 until n)),
          Long.MaxValue
        )
        ._1
        .map(_._1.toInt)
    assertEquals(order(0, 6), Vector(0, 1, 2, 5, 3, 4))
    assertEquals(order(4, 6), Vector(5, 3, 4, 1, 2, 0))
    assertEquals(order(7, 10), Vector(3, 0, 4, 5, 7, 8, 6, 9, 2, 1))
    // the budget: each job is 32 bytes, so three of them spend 96, and the pool is not exhausted
    val (picked, _, _, exhausted) =
      LeiosFetchLogic.pickJobs(
        StdGen.mk(4),
        SortedSet.empty,
        LeiosJobPool.of(size(1000000), 1, misses(0 to 5)),
        96L
      )
    assertEquals(picked.map(_._1.toInt), Vector(5, 3, 4))
    assert(!exhausted)
    val (_, rest, _, done) =
      LeiosFetchLogic.pickJobs(
        StdGen.mk(0),
        SortedSet.empty,
        LeiosJobPool.of(size(1000000), 1, misses(0 to 5)),
        Long.MaxValue
      )
    assert(done)
    assertEquals(rest.jobs.values.map(_.multiplicity).toSet, Set(1))
  }

  test("only the announcement that takes an election's focus is tracked") {
    val (hFocused, hRival) = (eb('a'), eb('b'))
    val el = ElId(slot(5), Bytes(Vector[Byte](1)))
    val (o1, woke1) = empty.announced(AnnouncementFields(el, hFocused, size(99)), None)
    val (o2, woke2) = o1.announced(AnnouncementFields(el, hRival, size(99)), None)
    assert(o2.ebState.contains(hFocused))
    assert(!o2.ebState.contains(hRival))
    assertEquals(o2.elFocus.get(el), Some(hFocused))
    assert(woke1 && !woke2)
    // too old: nothing
    val pruned = empty.prunedToImmTip(slot(6))._2
    assertEquals(
      pruned.announced(AnnouncementFields(el, hFocused, size(99)), None),
      (pruned, false)
    )
  }

  test("a certificate tracks an endorser block no announcement did, and moves the focus") {
    val h = eb('a')
    val el = ElId(slot(7), Bytes(Vector[Byte](2)))
    val o = empty.focusCertifiedEb(AnnouncementFields(el, h, size(99)))
    assertEquals(o.ebState.get(h), Some(EbState(slot(7), None, EbFetchState.NoBody)))
    assertEquals(o.elFocus.get(el), Some(h))
    assert(o.isFocused(h))
    // a certificate for another endorser block of the election moves the focus off the first
    val moved = o.focusCertifiedEb(AnnouncementFields(el, eb('b'), size(99)))
    assert(!moved.isFocused(h) && moved.isFocused(eb('b')))
  }

  test("a certificate leaves a body we already hold held") {
    val h = eb('a')
    val el = ElId(slot(7), Bytes(Vector[Byte](2)))
    val o = empty
      .recordMaxAnnouncementSlot(h, slot(7), None)
      .acquireEbBody(h, LeiosJobPool.of(size(1000), 10, SortedMap.empty))
      .focusCertifiedEb(AnnouncementFields(el, h, size(99)))
    assert(o.ebState.get(h).exists(_.hasBody))
  }

  test("two elections focusing one endorser block: pruning one leaves it focused") {
    val h = eb('a')
    val o = empty
      .focusElectionIfUnfocused(ElId(slot(3), fixtureIssuer), h)
      .focusElectionIfUnfocused(ElId(slot(9), fixtureIssuer), h)
    val pruned = o.prunedToImmTip(slot(5))._2
    assert(pruned.isFocused(h))
    assert(!pruned.prunedToImmTip(slot(10))._2.isFocused(h))
  }

  test("jobs partition the misses greedily by count and bytes") {
    // 32-byte transactions, at most 64 bytes or 3 transactions a job
    val byBytes = LeiosJobPool.of(size(64), 3, misses(0 to 4))
    assertEquals(
      byBytes.jobs.values.map(_.job.offsets.toSortedSet.toVector).toVector,
      Vector(Vector(0, 1), Vector(2, 3), Vector(4))
    )
    val byCount = LeiosJobPool.of(size(1000), 2, misses(0 to 4))
    assertEquals(
      byCount.jobs.values.map(_.job.bytes).toVector,
      Vector(size(64), size(64), size(32))
    )
    // the commitment is the hash of the covered hashes in offset order
    assertEquals(
      byCount.lookup(LeiosJobId(0)).map(_.root),
      Some(JobRootHash.of(Vector(txHashOf(0), txHashOf(1))))
    )
    // an oversized transaction forms a job of its own
    assertEquals(LeiosJobPool.of(size(10), 5, misses(0 to 1)).jobs.size, 2)
    assert(LeiosJobPool.of(size(10), 5, SortedMap.empty).isEmpty)
  }

  test("jobs partition the misses in ascending offset order, whatever the map's own order") {
    // three 30 KiB transactions and 64 KiB jobs: {0, 1} and {2}, as `IntMap.toAscList` walks them
    val big = SortedMap.from((0 to 2).map(off => off -> (txHashOf(off), size(30L * 1024L))))
    val descending = SortedMap.from(big)(using Ordering.Int.reverse)
    val ascendingPool = LeiosJobPool.of(env.maxJobBytesSize, env.maxJobTxCount, big)
    val descendingPool = LeiosJobPool.of(env.maxJobBytesSize, env.maxJobTxCount, descending)
    assertEquals(descendingPool, ascendingPool)
    assertEquals(
      descendingPool.jobs.values.map(_.job.offsets.toSortedSet.toVector).toVector,
      Vector(Vector(0, 1), Vector(2))
    )
    // a correct answer to the job is ingested
    val job = descendingPool.lookup(LeiosJobId(0)).fold(fail("no job"))(identity)
    val sized = SortedMap(0 -> txBytesOf(0), 1 -> txBytesOf(1))
    val small =
      LeiosJobPool.of(size(1000), 10, SortedMap.from(misses(0 to 1))(using Ordering.Int.reverse))
    val smallJob = small.lookup(LeiosJobId(0)).fold(fail("no job"))(identity)
    assertEquals(
      LeiosFetchLogic.ingestJob(sized, LeiosJobId(0), smallJob).map(_.map(_._1)),
      Right(Vector(0, 1))
    )
    assertEquals(job.root, JobRootHash.of(Vector(txHashOf(0), txHashOf(1))))
  }

  test(
    "the decision walks offers in point order and peers in their order, whatever the maps' own"
  ) {
    // a budget that one body request of 600 bytes exhausts: only the first point walked is asked for
    val tight = env.copy(maxRequestedBytesSizePerPeer = size(600))
    val points = Vector(95L, 100L, 90L).map(s => point(s, ('a' + s % 26).toChar))
    val o = points.foldLeft(empty)((acc, p) =>
      acc
        .recordMaxAnnouncementSlot(p.hash, p.slot, None)
        .focusElectionIfUnfocused(ElId(p.slot, fixtureIssuer), p.hash)
    )
    val offer = PeerOffer(None, Some(size(600)), WhetherTxsClosureOffered.NotOffered)
    def offersIn(ordering: Ordering[EbPoint]) =
      SortedMap.from(points.map(_ -> offer))(using ordering)
    def run(peerOrdering: Ordering[Pid], pointOrdering: Ordering[EbPoint]) =
      LeiosFetchLogic
        .iteration(
          tight,
          anyClosureSize,
          Some(slot(100)),
          SortedMap.from(Vector(peerA, peerB).map(_ -> offersIn(pointOrdering)))(using
            peerOrdering
          ),
          Map.empty,
          o
        )
        .requests
        .toVector
    val reference = run(Ordering.Int, ebPointOrdering)
    // the freshest window oldest first: slot 90 is walked first, for both peers
    assertEquals(
      reference.map((peer, reqs) => peer -> bodyRequests(reqs).map(_._1)),
      Vector(peerA -> Vector(points(2).hash), peerB -> Vector(points(2).hash))
    )
    assertEquals(run(Ordering.Int.reverse, ebPointOrdering.reverse), reference)
    assertEquals(run(Ordering.Int, ebPointOrdering.reverse), reference)
    // the tiers alone, from a newest-first map
    val (hi, lo) =
      LeiosFetchLogic.priorityTiers(Some(slot(100)), BigInt(10), offersIn(ebPointOrdering.reverse))
    assertEquals((hi ++ lo).map(_._1.slot.toBigInt), Vector[BigInt](90, 95, 100))
  }

  test("the peers draw their closure jobs in their order, whatever the offerings map's own") {
    // one job per 32-byte transaction, and a budget of one job: the generator's first draw goes to
    // the peer walked first, as the reference's fold over `Map (PeerId pid)` walks them
    val h = eb('c')
    val p = EbPoint(slot(10), h)
    val o = empty
      .recordMaxAnnouncementSlot(h, slot(10), None)
      .acquireEbBody(h, LeiosJobPool.of(size(1000), 1, misses(0 to 5)))
      .focusElectionIfUnfocused(ElId(slot(10), fixtureIssuer), h)
    val offer = PeerOffer(None, None, WhetherTxsClosureOffered.Offered)
    def run(peerOrdering: Ordering[Pid]) =
      LeiosFetchLogic
        .iteration(
          env.copy(maxRequestedBytesSizePerPeer = size(32)),
          anyClosureSize,
          Some(slot(11)),
          SortedMap.from(Vector(peerA, peerB).map(_ -> Map(p -> offer)))(using peerOrdering),
          Map.empty,
          o
        )
        .requests
        .map((peer, reqs) =>
          peer -> reqs.toVector.collect { case LeiosFetchRequest.BlockTxs(_, jobs) =>
            jobs.keys.toSortedSet.toVector
          }
        )
    val reference = run(Ordering.Int)
    assertEquals(run(Ordering.Int.reverse), reference)
    // the draws are the reference's: `uniformR (0, 5)` of `mkStdGen 0` is 0, then `uniformR (0, 4)`
    // of the generator it returns picks among ids 1 to 5 (StdGenSuite, the Picks.hs order 0, 1)
    assertEquals(reference.get(peerA), Some(Vector(Vector(0))))
    assertEquals(reference.get(peerB), Some(Vector(Vector(1))))
  }

  test("requests are cut at the request bound, a job never split") {
    val pool = LeiosJobPool.of(size(64), 2, misses(0 to 5))
    val picked =
      NonEmptyVector.fromVectorUnsafe(pool.jobs.toVector.map((i, st) => LeiosJobId(i) -> st.job))
    val small = env.copy(maxRequestBytesSize = size(130))
    val reqs = LeiosFetchLogic.batchTxsRequests(small, point(1, 'a'), picked)
    assertEquals(
      reqs.collect { case LeiosFetchRequest.BlockTxs(_, jobs) => jobs.keys.toSortedSet.toVector },
      Vector(Vector(0, 1), Vector(2))
    )
  }

  test("popLeftmostOffset as the reference's examples") {
    def all(w: Long) = Vector.unfold(w)(LeiosFetchLogic.popLeftmostOffset)
    assertEquals(all(0L), Vector.empty)
    assertEquals(all(1L), Vector(63))
    assertEquals(all(1L << 34), Vector(29))
    assertEquals(all(Long.MinValue | (1L << 62) | 8L), Vector(0, 1, 60))
  }

  property("bitmapOffsets inverts offsetsToBitmap") {
    Prop.forAll(Gen.containerOf[Set, Int](Gen.choose(0, 17141))) { offsets =>
      val sorted = SortedSet.from(offsets)
      val bitmaps = LeiosFetchLogic.offsetsToBitmap(sorted)
      LeiosFetchLogic.bitmapOffsets(bitmaps) == sorted.toVector &&
      bitmaps.map(_._1) == bitmaps.map(_._1).sorted.distinct &&
      bitmaps.forall(_._2 != 0L)
    }
  }

  test("a removed peer's budget, body requests and jobs are released") {
    val h = eb('c')
    val o = empty
      .recordMaxAnnouncementSlot(h, slot(10), None)
      .acquireEbBody(h, demoPool(0 to 4))
      .focusElectionIfUnfocused(ElId(slot(10), fixtureIssuer), h)
      .copy(requestedEbPeers = Map(eb('z') -> NonEmptySet.of(peerA, peerB)))
    val offers = SortedMap(
      peerA -> SortedMap(
        EbPoint(slot(10), h) -> PeerOffer(None, None, WhetherTxsClosureOffered.Offered)
      )
    )
    val after = LeiosFetchLogic
      .iteration(env, anyClosureSize, Some(slot(11)), offers, Map.empty, o)
      .outstanding
    assertEquals(after.requestedJobsPerPeer.get(peerA).map(_.keySet), Some(Set(h)))
    val removed = LeiosFetchLogic.removePeer(peerA, after)
    assertEquals(removed.requestedBytesSizePerPeer.get(peerA), None)
    assertEquals(removed.requestedJobsPerPeer.get(peerA), None)
    assertEquals(removed.requestedEbPeers.get(eb('z')).map(_.toSortedSet), Some(SortedSet(peerB)))
    removed.ebState.get(h).map(_.fetch) match
      case Some(EbFetchState.BodyAcquired(pool)) =>
        assertEquals(pool.jobs.values.map(_.multiplicity).toSet, Set(0))
      case other => fail(s"not held: $other")
  }

  test("a refund is made once: a peer refunded in bulk is not refunded again") {
    val h = eb('a')
    val o = empty.copy(
      requestedBytesSizePerPeer = SortedMap(peerA -> size(100)),
      requestedEbPeers = Map(h -> NonEmptySet.of(peerA))
    )
    val once = LeiosFetchLogic.refundEbRequest(peerA, h, size(100), o)
    assertEquals(once.requestedBytesSizePerPeer.get(peerA), None)
    assertEquals(once.requestedEbPeers.get(h), None)
    assertEquals(LeiosFetchLogic.refundEbRequest(peerA, h, size(100), once), once)
    val partial = LeiosFetchLogic.refundTxRequest(peerA, size(40), o)
    assertEquals(partial.requestedBytesSizePerPeer.get(peerA), Some(size(60)))
    assertEquals(LeiosFetchLogic.refundTxRequest(peerB, size(40), o), o)
  }

  test("completed jobs leave the pool for every peer and the peer's jobs in flight") {
    val h = eb('c')
    val o = empty
      .recordMaxAnnouncementSlot(h, slot(10), None)
      .acquireEbBody(h, LeiosJobPool.of(size(64), 1, misses(0 to 2)))
      .focusElectionIfUnfocused(ElId(slot(10), fixtureIssuer), h)
    val offers = SortedMap(
      peerA -> SortedMap(
        EbPoint(slot(10), h) -> PeerOffer(None, None, WhetherTxsClosureOffered.Offered)
      )
    )
    val decision =
      LeiosFetchLogic.iteration(env, anyClosureSize, Some(slot(11)), offers, Map.empty, o)
    val txsRequests = decision.requests.get(peerA).toVector.flatMap(_.toVector).collect {
      case r: LeiosFetchRequest.BlockTxs => r
    }
    assertEquals(txsRequests.size, 1)
    val done = txsRequests.foldLeft(decision.outstanding)((acc, r) =>
      LeiosFetchLogic.completeTxRequest(peerA, r, acc)
    )
    assertEquals(done.requestedJobsPerPeer.get(peerA), None)
    assert(done.ebState.get(h).exists {
      case EbState(_, _, EbFetchState.BodyAcquired(pool)) => pool.isEmpty
      case _                                              => false
    })
    // with nothing left to fetch the offer is dropped
    val again =
      LeiosFetchLogic.iteration(env, anyClosureSize, Some(slot(11)), offers, Map.empty, done)
    assertEquals(again.requests, SortedMap.empty[Pid, NonEmptyVector[LeiosFetchRequest]])
    assertEquals(again.drops.get(peerA).map(_.toSortedSet), Some(SortedSet(EbPoint(slot(10), h))))
  }

  test("an answer is checked against each job's count, size and commitment") {
    val job = LeiosJobPool
      .of(size(1000), 10, misses(0 to 2))
      .lookup(LeiosJobId(0))
      .fold(fail("no job"))(identity)
    val good = SortedMap(0 -> txBytesOf(0), 1 -> txBytesOf(1), 2 -> txBytesOf(2))
    val id = LeiosJobId(0)
    assertEquals(LeiosFetchLogic.checkJobSize(good, id, job), Right(()))
    assertEquals(LeiosFetchLogic.ingestJob(good, id, job).map(_.map(_._1)), Right(Vector(0, 1, 2)))
    assertEquals(
      LeiosFetchLogic.checkJobSize(good - 2, id, job),
      Left(LeiosTxsReplyFault.JobCountMismatch(id))
    )
    assertEquals(
      LeiosFetchLogic
        .checkJobSize(good.updated(2, txBytesOf(2) ++ Bytes(Vector[Byte](0))), id, job),
      Left(LeiosTxsReplyFault.JobByteSizeMismatch(id))
    )
    // the right sizes, the wrong transaction
    assertEquals(
      LeiosFetchLogic.ingestJob(good.updated(2, txBytesOf(7)), id, job),
      Left(LeiosTxsReplyFault.JobRootHashMismatch(id))
    )
  }

  test("a body request names the tracked slot and the offer's bound before the forecast") {
    val h = eb('a')
    val o = empty
      .recordMaxAnnouncementSlot(h, slot(3), None)
      .recordMaxAnnouncementSlot(h, slot(7), None)
      .focusElectionIfUnfocused(ElId(slot(3), fixtureIssuer), h)
    def run(offer: PeerOffer, forecast: Word64 => Either[Unit, BytesSize]) =
      LeiosFetchLogic
        .iteration(
          env,
          forecast,
          Some(slot(7)),
          SortedMap(peerA -> SortedMap(EbPoint(slot(3), h) -> offer)),
          Map.empty,
          o
        )
        .requests
        .get(peerA)
        .map(_.toVector)
    val viaNotify = PeerOffer(None, Some(size(50)), WhetherTxsClosureOffered.NotOffered)
    assertEquals(
      run(viaNotify, _ => Right(size(9))),
      Some(Vector(LeiosFetchRequest.Block(EbPoint(slot(7), h), size(50), size(9))))
    )
    // beyond the forecast and no bound of its own: not this pass
    assertEquals(run(viaNotify, _ => Left(())), None)
    val viaChainSync = PeerOffer(Some(size(11)), Some(size(50)), WhetherTxsClosureOffered.Offered)
    assertEquals(
      run(viaChainSync, _ => Left(())),
      Some(Vector(LeiosFetchRequest.Block(EbPoint(slot(7), h), size(50), size(11))))
    )
    // an offer of the closure only asks for no body
    assertEquals(
      run(PeerOffer(None, None, WhetherTxsClosureOffered.Offered), _ => Right(size(9))),
      None
    )
  }

  test("an offer of an endorser block no election focuses is skipped, not dropped") {
    val h = eb('a')
    val o = empty.recordMaxAnnouncementSlot(h, slot(3), None)
    val offers = SortedMap(
      peerA -> SortedMap(
        EbPoint(slot(3), h) -> PeerOffer(None, Some(size(5)), WhetherTxsClosureOffered.NotOffered)
      )
    )
    val d = LeiosFetchLogic.iteration(env, anyClosureSize, Some(slot(3)), offers, Map.empty, o)
    assertEquals(d.requests, SortedMap.empty[Pid, NonEmptyVector[LeiosFetchRequest]])
    assertEquals(d.drops, SortedMap.empty[Pid, NonEmptySet[EbPoint]])
  }

  test("endorser-block hashes order as the reference's PackedBytes 32") {
    // `Order.hs` (scripts/oracle/leios-stdgen) printed [GT, LT, LT, LT] for these four pairs with
    // the `cardano-crypto-class-2.6.0.0` unit of the Leios reference image on 2026-11-21. The pairs
    // separate the unsigned lexicographic order from the signed order and the word-reversed order.
    def h(bytes: Int*): EbHash = EbHash.fromBytes(Bytes(bytes.map(_.toByte).toVector))
    val a = h(0x80 +: Seq.fill(31)(0)*)
    val b = h(0x7f +: Seq.fill(31)(0xff)*)
    val c = h(Seq.fill(31)(0) :+ 0xff*)
    val d = h(Seq.fill(30)(0) ++ Seq(1, 0)*)
    val e = h(Seq.fill(8)(0) ++ Seq(0x80) ++ Seq.fill(23)(0)*)
    val f = h(Seq.fill(7)(0) ++ Seq(1) ++ Seq.fill(24)(0)*)
    val order = LeiosOrders.ebHashOrder
    assertEquals(
      Vector(order.compare(a, b), order.compare(c, d), order.compare(e, f), order.compare(b, a))
        .map(_.sign),
      Vector(1, -1, -1, -1)
    )
  }

  test("offers combine field by field, the newer size winning") {
    val older = PeerOffer(None, Some(size(1)), WhetherTxsClosureOffered.Offered)
    val newer = PeerOffer(Some(size(9)), Some(size(2)), WhetherTxsClosureOffered.NotOffered)
    assertEquals(
      newer.combine(older),
      PeerOffer(Some(size(9)), Some(size(2)), WhetherTxsClosureOffered.Offered)
    )
    assertEquals(PeerOffer.empty.combine(older), older)
  }

  test("sizes are Word32s: their sums and differences wrap") {
    assertEquals(size(0xffffffffL) + size(2), size(1))
    assertEquals(size(1) - size(2), size(0xffffffffL))
    assertEquals(BytesSize.wrap(1L << 32), BytesSize.zero)
  }
