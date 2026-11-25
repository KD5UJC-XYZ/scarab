// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.rng.Seed
import xyz.kd5ujc.scarab.codec.Bytes

import BlockFetchPolicy.*

/** The Genesis BlockFetch decision with its trimming to the serving peer's candidate read from one
  * set of the candidate's points (issue 810) decides exactly as it did when it scanned the
  * candidate for each header ([[GenesisBlockFetchModel]]). The inputs are drawn from a small pool
  * of blocks, each header a fresh `Bytes` of equal content, as each peer's headers are: candidates
  * that stop short of the best one, fork from it or split into fragments, blocks fetched or in
  * flight, and blocks of one slot under two hashes, as a Byron epoch boundary block and the block
  * after it.
  */
class GenesisBlockFetchTrimSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(2000)

  /** Block `id`'s hash, a new instance each call. Ids from 100 are forks off the base chain. */
  private def hash(id: Int): Bytes = Bytes.wrap(Array.tabulate(32)(i => (id * 7 + i * 13).toByte))

  /** A base chain: ids `1..n` with slots that never decrease, two blocks sharing a slot at times.
    */
  private val base: Gen[Vector[(Int, Int)]] =
    for
      n <- Gen.choose(0, 24)
      steps <- Gen.listOfN(n, Gen.frequency(1 -> Gen.const(0), 4 -> Gen.choose(1, 3)))
    yield steps.scanLeft(0)(_ + _).tail.zipWithIndex.map((slot, i) => (i + 1, slot)).toVector

  private def header(id: Int, slot: Int): Header = Header(hash(id), slot, 100 + id % 7)

  /** A peer's candidate: a stretch of the base chain, its tip forked away at times, in one fragment
    * or two.
    */
  private def candidate(chain: Vector[(Int, Int)]): Gen[Vector[Fragment]] =
    for
      until <- Gen.choose(0, chain.size)
      from <- Gen.choose(0, until)
      forkAt <- Gen.option(Gen.choose(from, until))
      forkLength <- Gen.choose(1, 4)
      forkName <- Gen.choose(1, 3)
      split <- Gen.option(Gen.choose(0, until - from))
      anchored <- Gen.frequency(4 -> true, 1 -> false)
    yield
      val stretch = chain.slice(from, until)
      val forked = forkAt.fold(stretch) { at =>
        val kept = stretch.take(at - from)
        val slot = kept.lastOption.orElse(chain.lift(from - 1)).fold(0)(_._2)
        kept ++ (1 to forkLength).map(k => (100 * forkName + k, slot + k))
      }
      val headers = forked.map(header)
      val anchor = Option.when(anchored)(hash(chain.lift(from - 1).fold(0)(_._1)))
      split.filter(at => at > 0 && at < headers.size).fold(Vector(Fragment(anchor, headers))) {
        at =>
          Vector(
            Fragment(anchor, headers.take(at)),
            Fragment(Some(headers(at - 1).hash), headers.drop(at))
          )
      }

  private def subset(chain: Vector[(Int, Int)]): Gen[Set[Int]] =
    Gen.someOf((chain.map(_._1) ++ Vector(101, 102, 201)).distinct).map(_.toSet)

  private def peer(id: Int, chain: Vector[(Int, Int)]): Gen[Candidate[Int]] =
    for
      fragments <- candidate(chain)
      preference <- Gen.choose(0, 3)
      inFlight <- Gen.frequency(2 -> Gen.const(Set.empty[Int]), 1 -> subset(chain))
      requests <- Gen.choose(0, 2)
      refused <- Gen.frequency(8 -> false, 1 -> true)
    yield Candidate(
      id,
      preference,
      fragments.lastOption.flatMap(_.headers.lastOption).fold(Bytes.empty)(_.hash),
      fragments,
      inFlight = InFlight(requests, 0, inFlight.map(hash)),
      refusal = Option.when(refused)(Refusal.InvalidCandidate)
    )

  private final case class Scenario(
      before: GenesisBlockFetch.State[Int],
      now: BigInt,
      starvation: GenesisBlockFetch.Starvation,
      candidates: Vector[Candidate[Int]],
      fetched: Set[Int]
  ):
    def fetchedBlocks: Fetched = Fetched.of(fetched.map(hash))

  private val scenario: Gen[Scenario] =
    for
      chain <- base
      count <- Gen.choose(1, 4)
      peers <- Gen.pick(count, 1 to 5)
      candidates <- Gen.sequence[Vector[Candidate[Int]], Candidate[Int]](
        peers.toVector.map(peer(_, chain))
      )
      order <- Gen.someOf(1 to 6).flatMap(ids => Gen.pick(ids.size, ids))
      current <- Gen.option(Gen.oneOf(1 to 6))
      start <- Gen.choose(0, 20)
      now <- Gen.choose(0, 30)
      starvation <- Gen.oneOf(
        Gen.const(GenesisBlockFetch.Starvation.Ongoing),
        Gen.choose(-1, 30).map(t => GenesisBlockFetch.Starvation.EndedAt(t))
      )
      fetched <- Gen.frequency(2 -> Gen.const(Set.empty[Int]), 1 -> subset(chain))
    yield Scenario(
      GenesisBlockFetch.State(order.toVector, current, start),
      now,
      starvation,
      candidates,
      fetched
    )

  private def decided(s: Scenario): GenesisBlockFetch.Result[Int] =
    GenesisBlockFetch.decide(
      Config(),
      s.before,
      s.now,
      s.starvation,
      s.candidates,
      BigInt(10),
      s.fetchedBlocks
    )

  private def modelled(s: Scenario): GenesisBlockFetch.Result[Int] =
    GenesisBlockFetchModel.decide(
      Config(),
      s.before,
      s.now,
      s.starvation,
      s.candidates,
      BigInt(10),
      s.fetchedBlocks
    )

  /** What to trim and to what: the base chain without some blocks, as fetched or in-flight blocks
    * leave it, against a peer's candidate.
    */
  private val trimming: Gen[(Vector[Fragment], Vector[Fragment])] =
    for
      chain <- base
      fragments <- candidate(chain)
      held <- subset(chain)
      peerCandidate <- candidate(chain)
    yield (filterFragments(fragments, h => !held.map(hash)(h.hash)), peerCandidate)

  property("the decision is the model's") {
    Prop.forAll(scenario)(s => assertEquals(decided(s), modelled(s)))
  }

  property("the trimming is the model's") {
    Prop.forAll(trimming) { (fragments, peerCandidate) =>
      assertEquals(
        GenesisBlockFetch.trim(fragments, peerCandidate),
        GenesisBlockFetchModel.trim(fragments, peerCandidate)
      )
    }
  }

  /** The kinds of trimming the inputs reach, each of which the properties above must have seen. */
  private enum Trimmed:
    case Shortened, Whole, Dropped, AtCandidateAnchor, AtFragmentAnchorOnly, Forked, SameSlot

  private def kinds(fragments: Vector[Fragment], peerCandidate: Vector[Fragment]): Set[Trimmed] =
    val anchors = peerCandidate.flatMap(_.anchor).toSet
    val headers = peerCandidate.flatMap(_.headers.map(_.hash)).toSet
    val trimmed = GenesisBlockFetchModel.trim(fragments, peerCandidate)
    val perFragment = fragments.flatMap { fragment =>
      val last = fragment.headers.lastIndexWhere(h => anchors(h.hash) || headers(h.hash))
      val atAnchor = Option.when(
        last >= 0 && anchors(fragment.headers(last).hash) && !headers(fragment.headers(last).hash)
      )(Trimmed.AtCandidateAnchor)
      val outcome =
        if last < 0 then
          if fragment.anchor.exists(a => anchors(a) || headers(a)) then
            Vector(Trimmed.Dropped, Trimmed.AtFragmentAnchorOnly)
          else Vector(Trimmed.Dropped)
        else if last == fragment.headers.size - 1 then Vector(Trimmed.Whole)
        else Vector(Trimmed.Shortened)
      outcome ++ atAnchor
    }
    val forked = Option.when(
      peerCandidate.exists(
        _.headers.exists(h => !fragments.exists(_.headers.exists(_.hash == h.hash)))
      )
        && trimmed.nonEmpty
    )(Trimmed.Forked)
    val sameSlot = Option.when(
      fragments.exists(f => f.headers.map(_.slot).distinct.size < f.headers.size)
    )(Trimmed.SameSlot)
    (perFragment ++ forked ++ sameSlot).toSet

  private enum Outcome:
    case Requested, InFlightThisPeer, TrimmedAway, Unavailable, Concurrency, Demoted, AllRefused

  private def outcomes(s: Scenario): Set[Outcome] =
    val result = modelled(s)
    val reasons = result.decisions.collect { case Decision.Declined(_, reason) => reason }
    Set(
      Option.when(result.decisions.exists {
        case _: Decision.Request[Int] => true
        case _                        => false
      })(Outcome.Requested),
      Option.when(reasons.contains(Refusal.InFlightThisPeer))(Outcome.InFlightThisPeer),
      Option.when(reasons.contains(Refusal.AlreadyFetched))(Outcome.TrimmedAway),
      Option.when(
        s.candidates.count(_.refusal.isEmpty) > 1 && reasons.count(_ == Refusal.AlreadyFetched) > 0
      )(Outcome.Unavailable),
      Option.when(reasons.exists {
        case Refusal.GenesisConcurrency(_) => true
        case _                             => false
      })(Outcome.Concurrency),
      result.demoteDynamo.map(_ => Outcome.Demoted),
      Option.when(reasons.nonEmpty && reasons.forall(_ == Refusal.InvalidCandidate))(
        Outcome.AllRefused
      )
    ).flatten

  private def sampled[A](gen: Gen[A], count: Int): Vector[A] =
    (0 until count).toVector.flatMap(i => gen(Gen.Parameters.default, Seed(810L + i)))

  test("the inputs reach every kind of trimming") {
    val seen = sampled(trimming, 2000).flatMap(kinds(_, _)).toSet
    assertEquals(Trimmed.values.toSet -- seen, Set.empty[Trimmed])
  }

  test("the scenarios reach every outcome of the decision") {
    val seen = sampled(scenario, 2000).flatMap(outcomes).toSet
    assertEquals(Outcome.values.toSet -- seen, Set.empty[Outcome])
  }
