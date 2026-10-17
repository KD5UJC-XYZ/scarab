// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalacheck.Prop.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** The values the decision function is built from: the generator, the targets, the local roots and
  * the pick policies (reference properties: `LocalRootPeers.fromGroups` repairs any input and
  * `clampToLimit` has `size == min limit size`, `State/LocalRootPeers.hs` 159-220).
  */
class PrimitivesSuite extends ScalaCheckSuite:

  // ------------------------------------------------------------------ Rng

  property("the generator is a pure function of its seed") {
    forAll { (seed: Long) =>
      val (a, _) = Rng(seed).nextLong
      val (b, _) = Rng(seed).nextLong
      a == b
    }
  }

  property("a draw between two bounds stays inside them and the generator advances") {
    forAll(Gen.choose(-10000L, 10000L), Gen.choose(0L, 20000L), Arbitrary.arbitrary[Long]) {
      (low, width, seed) =>
        val (x, next) = Rng(seed).between(low, low + width)
        x >= low && x <= low + width && next != Rng(seed)
    }
  }

  property("a split yields two generators with different streams") {
    forAll { (seed: Long) =>
      val (a, b) = Rng(seed).split
      a.nextLong._1 != b.nextLong._1
    }
  }

  test("the generator is uniform enough: every bucket of ten is hit in 10000 draws") {
    val (_, buckets) = (0 until 10000).foldLeft((Rng(42L), Map.empty[Long, Int])) {
      case ((g, m), _) =>
        val (x, next) = g.between(0, 9)
        (next, m.updated(x, m.getOrElse(x, 0) + 1))
    }
    assertEquals(buckets.keySet, (0L to 9L).toSet)
    assert(buckets.values.forall(n => n > 800 && n < 1200), buckets.toString)
  }

  // ------------------------------------------------------------------ Targets

  test("the defaults of cardano-node 11.1.3 are sane and are the numbers of the reference") {
    assertEquals(
      Targets.validated(60, 150, 30, 20, 15, 10, 5).toOption,
      Some(Targets.relay)
    )
    assertEquals(
      Targets.validated(100, 100, 30, 20, 15, 10, 5).toOption,
      Some(Targets.blockProducer)
    )
    assertEquals(Targets.validated(0, 150, 10, 5, 100, 40, 30).toOption, Some(Targets.sync))
    assertEquals(Targets.validated(0, 0, 0, 0, 0, 0, 0).toOption, Some(Targets.zero))
  }

  property("validated is the reference's sanePeerSelectionTargets") {
    val target = Gen.choose(-5, 12000)
    forAll(target, target, target, target, target, target, target) { (r, k, e, a, kb, eb, ab) =>
      def kind(k: Int, e: Int, a: Int, root: Option[Int]): Boolean =
        0 <= a && a <= e && e <= k && root.forall(r => 0 <= r && r <= k) &&
          a <= 100 && e <= 1000 && k <= 10000
      val expected = kind(k, e, a, Some(r)) && kind(kb, eb, ab, None)
      Targets.validated(r, k, e, a, kb, eb, ab).isRight == expected
    }
  }

  test("a refused set of targets says which set and why") {
    assertEquals(
      Targets.validated(0, 10, 20, 5, 0, 0, 0),
      Left(Targets.Problem.NotNested(Targets.Kind.Ordinary))
    )
    assertEquals(
      Targets.validated(0, 0, 0, 0, 5, 5, 6),
      Left(Targets.Problem.NotNested(Targets.Kind.BigLedger))
    )
    assertEquals(
      Targets.validated(0, 10000, 1000, 101, 0, 0, 0),
      Left(Targets.Problem.AboveCap(Targets.Kind.Ordinary, Targets.ActiveCap))
    )
    assertEquals(
      Targets.validated(-1, 10, 10, 5, 0, 0, 0),
      Left(Targets.Problem.Negative(Targets.Kind.Ordinary))
    )
  }

  // ------------------------------------------------------------------ LocalRoots

  private val config =
    RootConfig(false, DiffusionMode.InitiatorAndResponder, Provenance.Outbound, false)
  private def spec(hot: Int, warm: Int, members: Int*): RootGroupSpec[Int] =
    RootGroupSpec(hot, warm, members.toList.map(_ -> config))

  private val groupSpecs: Gen[List[RootGroupSpec[Int]]] =
    Gen.listOfN(
      4,
      for
        hot <- Gen.choose(-1, 5)
        warm <- Gen.choose(-1, 6)
        members <- Gen.listOf(Gen.choose(0, 12))
      yield spec(hot, warm, members*)
    )

  private def valid(roots: LocalRoots[Int]): Boolean =
    val members = roots.groups.flatMap(_.members)
    members.size == members.toSet.size && members.toSet == roots.keys.toSet &&
    roots.groups.forall(g =>
      0 < g.hot.value && g.hot.value <= g.warm.value && g.warm.value <= g.members.size
    )

  property("fromGroups repairs any input to a valid value") {
    forAll(groupSpecs)(specs => valid(LocalRoots.fromGroups(specs)))
  }

  property("clampToLimit keeps min(limit, size) roots and stays valid") {
    forAll(groupSpecs, Gen.choose(0, 40)) { (specs, limit) =>
      val roots = LocalRoots.fromGroups(specs)
      val clamped = roots.clampToLimit(limit)
      clamped.size == limit.min(roots.size) && valid(clamped)
    }
  }

  test("a group is repaired the way fromGroups does: later duplicates dropped, valencies lowered") {
    val roots = LocalRoots.fromGroups(List(spec(9, 9, 1, 2, 3), spec(2, 3, 3, 4), spec(1, 1)))
    assertEquals(
      roots.groups.map(g => (g.hot.value, g.warm.value, g.members.toList)).toList,
      List(
        (3, 3, List(1, 2, 3)),
        (1, 1, List(4))
      )
    )
  }

  test("an empty group, as the official Preview topology has, is dropped") {
    assert(LocalRoots.fromGroups(List(spec(1, 1))).isEmpty)
  }

  test("clampToTrustable keeps the trustable members with valencies lowered to fit") {
    val trusted = config.copy(trustable = true)
    val roots = LocalRoots.fromGroups(
      List(RootGroupSpec(2, 3, List(1 -> trusted, 2 -> config, 3 -> trusted)))
    )
    val clamped = roots.clampToTrustable
    assertEquals(clamped.keys.toList, List(1, 3))
    assertEquals(clamped.groups.map(g => (g.hot.value, g.warm.value)).toList, List((2, 2)))
    assert(LocalRoots.fromGroups(List(spec(1, 1, 5))).clampToTrustable.isEmpty)
  }

  // ------------------------------------------------------------------ Picks

  private val candidates: Gen[SortedSet[Int]] = Gen.listOf(Gen.choose(0, 60)).map(SortedSet.from(_))

  property("a pick takes min(n, size) distinct candidates and is determined by the generator") {
    forAll(candidates, Gen.choose(0, 70), Arbitrary.arbitrary[Long]) { (set, n, seed) =>
      val (a, next) = Picks.promotion(set, n, Rng(seed))
      val (b, _) = Picks.promotion(set, n, Rng(seed))
      a == b && a.size == n.min(set.size) && a.toSet.size == a.size && a.forall(set) &&
      (set.isEmpty || n <= 0 || next != Rng(seed))
    }
  }

  property("hot demotion takes the lowest scores first, whatever the random numbers") {
    forAll(candidates.suchThat(_.size > 3), Arbitrary.arbitrary[Long]) { (set, seed) =>
      val score = (p: Int) => HotScore(p % 7, None)
      val (picked, _) = Picks.hotDemotion(set, 3, Rng(seed), score)
      val worst = picked.map(score(_).value).max
      set.filterNot(picked.contains).forall(p => score(p).value >= worst)
    }
  }

  test(
    "warm demotion prefers a peer that was hot, a failure makes a peer likelier to be forgotten"
  ) {
    val set = SortedSet(1, 2)
    val runs = 4000
    val tepid = (0 until runs).count { i =>
      Picks.warmDemotion(set, 1, Rng(i.toLong), _ == 1)._1 == List(1)
    }
    // r/2 against r: the tepid one is picked with probability 3/4
    assert(tepid > runs * 70 / 100 && tepid < runs * 80 / 100, tepid.toString)
    val failed = (0 until runs).count { i =>
      Picks.forget(set, 1, Rng(i.toLong), p => if p == 1 then 4 else 0)._1 == List(1)
    }
    // r/5 against r: probability 9/10
    assert(failed > runs * 86 / 100 && failed < runs * 94 / 100, failed.toString)
  }
