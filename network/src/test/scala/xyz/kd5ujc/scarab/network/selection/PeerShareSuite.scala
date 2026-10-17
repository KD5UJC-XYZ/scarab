// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import org.scalacheck.{Gen, Prop}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.protocol.PeerSharing
import xyz.kd5ujc.scarab.protocol.PeerSharing.{Address, Amount}

import PeerShare.*

/** The server policy of peer sharing (`PeerSharing.hs:203-237`, `KnownPeers.hs:580-592`). */
class PeerShareSuite extends munit.ScalaCheckSuite:
  private def amount(n: Int): Amount = Amount.from(n).getOrElse(fail(s"amount $n"))
  private def addr(i: Int): Address =
    Address.V4(
      PeerSharing.Word32.from(0x0a000000L + i).getOrElse(fail("word")),
      PeerSharing.Port.from(3000).getOrElse(fail("port"))
    )

  private def candidate(
      i: Int,
      advertise: Boolean = true,
      connected: Boolean = true,
      fails: Int = 0
  ) =
    Candidate(addr(i), advertise, connected, fails)

  test("only advertised peers that connected and have not failed since are shareable") {
    val known = Vector(
      candidate(1),
      candidate(2, advertise = false),
      candidate(3, connected = false),
      candidate(4, fails = 1),
      candidate(5),
      candidate(5) // a duplicate collapses
    )
    assertEquals(shareable(known), Vector(addr(1), addr(5)))
    assertEquals(shareable(Vector.empty[Candidate[Address]]), Vector.empty[Address])
  }

  test("the constants are the reference's") {
    assertEquals(StickyTime, 823.seconds)
    assertEquals(MaxPeers.value, 10)
  }

  private val everyone = Vector.tabulate(50)(addr)

  test("an answer holds at most min(amount, 10) of the shareable peers") {
    Vector(0, 1, 5, 10, 11, 255).foreach { n =>
      val got = answer(7L, amount(n), everyone)
      assertEquals(got.size, math.min(n, 10), s"amount $n")
      assert(got.forall(everyone.contains))
      assertEquals(got.distinct, got)
    }
    assertEquals(answer(7L, amount(255), everyone.take(3)).toSet, everyone.take(3).toSet)
    assertEquals(answer(7L, amount(5), Vector.empty[Address]), Vector.empty[Address])
    assertEquals(answer(7L, amount(255), everyone, amount(20)).size, 20)
  }

  property("the same salt gives the same answer, whatever the order the peers arrive in") {
    Prop.forAll(Gen.long, Gen.choose(0, 255), Gen.choose(0, 49)) { (salt, n, rotate) =>
      val shuffled = everyone.drop(rotate) ++ everyone.take(rotate)
      Prop(answer(salt, amount(n), everyone.sorted) == answer(salt, amount(n), shuffled.sorted))
    }
  }

  property("a request for more peers extends the answer to a smaller one") {
    Prop.forAll(Gen.long, Gen.choose(0, 10)) { (salt, n) =>
      val small = answer(salt, amount(n), everyone)
      val large = answer(salt, amount(10), everyone)
      Prop(large.startsWith(small))
    }
  }

  test("different salts rank the peers differently") {
    val answers = (1L to 20L).map(salt => answer(salt, amount(10), everyone)).toSet
    assert(answers.size > 10, answers.size.toString)
  }

  property("a peer added or removed changes the answer by at most one peer") {
    // "a newly added or removed peer can at most lead to one new peer being shared" (PeerSharing.hs:200-202)
    Prop.forAll(Gen.long, Gen.choose(0, 49)) { (salt, drop) =>
      val without = everyone.patch(drop, Nil, 1)
      val before = answer(salt, amount(10), everyone).toSet
      val after = answer(salt, amount(10), without).toSet
      Prop((after -- before).size <= 1 && (before -- after).size <= 1)
    }
  }

  test("the salt is held for 823 seconds and then drawn afresh") {
    val start = Salting.start(Rng(42))
    // due at once (reSaltAt = 0 <= now): this request draws the salt and advances the generator
    val (first, afterFirst) = start.at(100.seconds)
    assertEquals(afterFirst.reSaltAt, 923.seconds)
    // The reference stores the advanced generator and the later requests of the interval read the
    // first draw of that one (`PeerSharing.hs:219-227`), so they share a salt of their own, which
    // is not the salt of the request that re-salted. Mirrored as it is.
    val (held, afterHeld) = afterFirst.at(900.seconds)
    assertNotEquals(held, first)
    assertEquals(afterHeld, afterFirst)
    assertEquals(afterFirst.at(922.seconds)._1, held)
    // exactly at the time it is due: the request that re-salts reads the draw the interval held,
    // the generator advances, and the next interval holds the following draw for another 823 s
    val (next, afterNext) = afterFirst.at(923.seconds)
    assertEquals(afterNext.reSaltAt, 923.seconds + 823.seconds)
    assertEquals(next, held)
    val (stay, _) = afterNext.at(1000.seconds)
    assertNotEquals(stay, held)
    assertEquals(afterNext.at(1745.seconds)._1, stay)
  }

  test("the same asker gets the same answer within the interval") {
    val (_, state) = Salting.start(Rng(9)).at(0.seconds)
    val (salt1, _) = state.at(1.seconds)
    val (salt2, _) = state.at(822.seconds)
    assertEquals(answer(salt1, amount(10), everyone), answer(salt2, amount(10), everyone))
    val (_, renewed) = state.at(823.seconds)
    val (salt3, _) = renewed.at(824.seconds)
    assertNotEquals(answer(salt1, amount(10), everyone), answer(salt3, amount(10), everyone))
  }
