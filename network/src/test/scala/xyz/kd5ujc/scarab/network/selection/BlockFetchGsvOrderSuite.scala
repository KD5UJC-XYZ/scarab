// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.{Bytes, HaskellSort}

import BlockFetchPolicy.*
import FetchGsv.*

/** GSV ordering of `prioritisePeerChains` and preferred-peer admission (ouroboros-network 2fb69829,
  * BlockFetch/Decision.hs:783–784, 821–822 and 1028–1035) with `comparePeerGSV`, whose 5% band is
  * not transitive. Expected orders below were traced by hand through GHC 9.10.3 `Data.List.sortBy`.
  */
class BlockFetchGsvOrderSuite extends ScalaCheckSuite:
  private val Millisecond = PicosPerSecond / 1000

  private def peer(id: Int, geographic: BigInt, tie: Long, headers: Int*): Candidate[Int] =
    Candidate(
      id,
      0,
      Bytes(Vector(0.toByte)),
      Vector(
        Fragment(None, headers.toVector.map(n => Header(Bytes(Vector(n.toByte)), BigInt(n), 100)))
      ),
      PeerGsv(0, Gsv(geographic, 2000000), Gsv(0, 2000000)),
      tieBreak = tie
    )

  /** The comparator before this fix: an exact rational 5% band, applied through `sortWith`. */
  private def previous(a: Candidate[Int], b: Candidate[Int]): Int =
    val ga = a.performance.geographic
    val gb = b.performance.geographic
    if (ga - gb).abs * 100 < ga.max(gb) * 5 then java.lang.Long.compare(a.tieBreak, b.tieBreak)
    else ga.compare(gb)

  /** One deadline group (equal band, preference and chain head) keeps the GSV order unchanged. */
  private def ranked(candidates: Vector[Candidate[Int]]): Vector[Int] =
    rank(FetchMode.Deadline, candidates).map(_.peer)

  // Each neighbour is within 5% of the next and its tie breaker is lower, the ends are not.
  private val a = peer(1, 100 * Millisecond, 3)
  private val b = peer(2, 104 * Millisecond, 2)
  private val c = peer(3, 108 * Millisecond, 1)
  private val d = peer(4, 112 * Millisecond, 0)

  // Thirty-two peers 3 ms apart around 100 ms with shuffled tie breakers, the smallest input at
  // which TimSort checks the comparison contract (below it, it sorts by binary insertion).
  private val contended: Vector[Candidate[Int]] = Vector(
    9 -> 613,
    13 -> 77,
    10 -> 148,
    26 -> 911,
    8 -> 794,
    1 -> 715,
    29 -> 862,
    3 -> 283,
    0 -> 395,
    22 -> 787,
    14 -> 256,
    11 -> 131,
    7 -> 821,
    24 -> 219,
    12 -> 492,
    19 -> 968,
    30 -> 908,
    25 -> 595,
    15 -> 300,
    2 -> 292,
    4 -> 315,
    16 -> 376,
    27 -> 610,
    18 -> 981,
    23 -> 110,
    31 -> 602,
    17 -> 309,
    5 -> 192,
    6 -> 908,
    21 -> 693,
    28 -> 544,
    20 -> 862
  ).map((i, tie) => peer(i, 100 * Millisecond + i * 3 * Millisecond, tie.toLong, i))

  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.get.fold(throw _, identity)

  test(
    "the previous java.util ordering rejects a nontransitive GSV set; ranking and decisions do not"
  ) {
    intercept[IllegalArgumentException](contended.sortWith((x, y) => previous(x, y) < 0))
    val expected = HaskellSort.sortBy(contended)(compareGsv(Set.empty[Int])).map(_.peer)
    assertEquals(ranked(contended), expected)
    val config = Config(bulkConcurrency = 2)
    val decisions = decide(config, FetchMode.BulkSync, contended)
    assertEquals(decisions.size, contended.size)
    // The two preferred peers are the head of the same order; each fetches its own block.
    val requested = decisions.collect { case Decision.Request(p, _) => p }.toSet
    assert(expected.take(2).toSet.subsetOf(requested), requested)
  }

  test("every order of a three-peer cycle follows the native runs and merges") {
    val cases = Vector(
      Vector(a, b, c) -> Vector(3, 2, 1),
      Vector(a, c, b) -> Vector(1, 3, 2),
      Vector(b, a, c) -> Vector(2, 1, 3),
      Vector(b, c, a) -> Vector(1, 3, 2),
      Vector(c, a, b) -> Vector(2, 1, 3),
      Vector(c, b, a) -> Vector(3, 2, 1)
    )
    cases.foreach((input, expected) => assertEquals(ranked(input), expected, input.map(_.peer)))
    // Runs [a, d] and [c, b] merge to put d (112 ms) before c and b.
    assertEquals(ranked(Vector(d, a, c, b)), Vector(1, 4, 3, 2))
  }

  test("the 5% band and the incumbent advantage floor to picoseconds as DiffTime products") {
    // 0.05 * 105000000019 ps floors to 5250000000 ps, which the difference does not undercut.
    val high = peer(1, BigInt(105000000019L), 0)
    assertEquals(ranked(Vector(high, peer(2, BigInt(99750000019L), 1))), Vector(2, 1))
    assertEquals(ranked(Vector(high, peer(2, BigInt(99750000020L), 1))), Vector(1, 2))
    assertEquals(compareGsv(Set.empty[Int])(peer(1, 21, 0), peer(2, 20, 1)), 1)
    // 0.8 * 9 ps floors to 7 ps; equal 7 ps values have an empty band and compare equal.
    assertEquals(compareGsv(Set(1))(peer(1, 9, 1), peer(2, 7, 0)), 0)
  }

  test("preferred admission orders the cycle by the native sort before the target applies") {
    // Bulk ranks by duration (a, b, c); c heads the GSV order of [a, b, c], so it is preferred and
    // fetches although a already fills the target of one.
    val decisions =
      decide(
        Config(),
        FetchMode.BulkSync,
        Vector(
          peer(1, a.performance.geographic, 3, 1),
          peer(2, b.performance.geographic, 2, 2),
          peer(3, c.performance.geographic, 1, 3)
        )
      )
    assertEquals(
      decisions.map {
        case Decision.Request(p, _)      => p -> None
        case Decision.Declined(p, cause) => p -> Some(cause)
      },
      Vector(1 -> None, 2 -> Some(Refusal.Concurrency(FetchMode.BulkSync, 1)), 3 -> None)
    )
  }

  property(
    "ranking random GSVs equals the reference sort, never throws and keeps lawful ties stable"
  ) {
    val gsv = Gen.choose(90L, 120L).map(n => BigInt(n) * Millisecond)
    val input = for
      n <- Gen.choose(0, 64)
      gs <- Gen.listOfN(n, gsv)
      ties <- Gen.listOfN(n, Gen.choose(0L, 3L))
    yield gs.zip(ties).zipWithIndex.map { case ((g, tie), i) => peer(i, g, tie, i) }.toVector
    Prop.forAll(input) { candidates =>
      val expected = HaskellSort.sortBy(candidates)(compareGsv(Set.empty[Int])).map(_.peer)
      assertEquals(ranked(candidates), expected)
      assertEquals(
        decide(Config(bulkConcurrency = 3), FetchMode.BulkSync, candidates).size,
        candidates.size
      )
      // Equal GSVs and equal tie breakers compare equal; such peers keep their input order.
      val equal = candidates.map(_.copy(performance = default, tieBreak = 0))
      assertEquals(ranked(equal), equal.map(_.peer))
    }
  }

  test("ranking and decisions over many peers remain stack safe on a 256 KiB thread") {
    val many = Vector.tabulate(20000)(i =>
      peer(
        i,
        100 * Millisecond + (i * 7919 % 20000) * Millisecond / 1000,
        (i * 31 % 97).toLong,
        i % 200
      )
    )
    val (order, decisions) = onSmallStack(
      (ranked(many), decide(Config(bulkConcurrency = 4), FetchMode.BulkSync, many))
    )
    assertEquals(order.size, many.size)
    assertEquals(decisions.size, many.size)
  }
