// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
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

  property("the decision is the model's") {
    Prop.forAll(scenario)(s => assertEquals(decided(s), modelled(s)))
  }

  /** A peer with the blocks `ids` (block `n` at `slot(n)`) after the anchor `anchor`. */
  private def serving(
      id: Int,
      preference: Int,
      anchor: Int,
      ids: Vector[Int],
      slot: Int => Int = identity
  ): Candidate[Int] =
    Candidate(id, preference, hash(ids.last), Vector(fragment(anchor, ids, slot)))

  private def fragment(anchor: Int, ids: Vector[Int], slot: Int => Int = identity): Fragment =
    Fragment(Some(hash(anchor)), ids.map(n => header(n, slot(n))))

  private def witness(candidates: Vector[Candidate[Int]], fetched: Set[Int] = Set.empty) =
    Scenario(
      GenesisBlockFetch.State(Vector(1, 2)),
      0,
      GenesisBlockFetch.Starvation.EndedAt(-1),
      candidates,
      fetched
    )

  /** Each witness is decided as the model decides it, and as stated. */
  private def check(s: Scenario, expected: Vector[Decision[Int]]): Unit =
    val result = decided(s)
    assertEquals(result, modelled(s))
    assertEquals(result.decisions, expected)

  /** The slots of a fork's blocks 101 and 102 after block 3; other blocks at their own number. */
  private val forked: Int => Int = Map(101 -> 4, 102 -> 5).withDefault(identity)

  private val best = serving(2, 2, 0, Vector(1, 2, 3, 4, 5, 6))

  test("a peer without the oldest block is passed over before the trimming") {
    check(
      witness(Vector(serving(1, 1, 2, Vector(3, 4)), best)),
      Vector(
        Decision.Request(2, Vector(fragment(0, Vector(1, 2, 3, 4, 5, 6)))),
        Decision.Declined(1, Refusal.AlreadyFetched)
      )
    )
  }

  test("the chosen peer whose blocks are all in flight with it trims to nothing") {
    val dynamo = serving(1, 1, 0, Vector(1)).copy(inFlight = InFlight(1, 0, Set(hash(1))))
    check(
      witness(Vector(dynamo, best)),
      Vector(
        Decision.Declined(2, Refusal.GenesisConcurrency(1)),
        Decision.Declined(1, Refusal.AlreadyFetched)
      )
    )
  }

  test("a dynamo that forks after a shared block serves the blocks up to it") {
    check(
      witness(Vector(serving(1, 1, 0, Vector(1, 2, 3, 101, 102), forked), best)),
      Vector(
        Decision.Request(1, Vector(fragment(0, Vector(1, 2, 3)))),
        Decision.Declined(2, Refusal.GenesisConcurrency(1))
      )
    )
  }

  test("a dynamo whose candidate is anchored at the oldest block serves that block alone") {
    check(
      witness(Vector(serving(1, 1, 3, Vector(101, 102), forked), best), Set(1, 2)),
      Vector(
        Decision.Request(1, Vector(fragment(2, Vector(3)))),
        Decision.Declined(2, Refusal.GenesisConcurrency(1))
      )
    )
  }

  test("two blocks of one slot stay apart: the dynamo serves the first and not the second") {
    // block 2 is an epoch boundary block, and block 3 the block of the same slot after it
    val slot: Int => Int = Map(2 -> 2, 3 -> 2, 4 -> 3).withDefault(identity)
    check(
      witness(
        Vector(
          serving(1, 1, 0, Vector(1, 2), slot),
          serving(2, 2, 0, Vector(1, 2, 3, 4), slot)
        )
      ),
      Vector(
        Decision.Request(1, Vector(fragment(0, Vector(1, 2), slot))),
        Decision.Declined(2, Refusal.GenesisConcurrency(1))
      )
    )
  }
