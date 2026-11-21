// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

/** The generator against the reference's own: every expected value below was printed by a program
  * compiled with GHC 9.10.3 against the units the Leios build links, `random-1.3.1-126384cd` and
  * `splitmix-0.1.3.2-f11367c6` (the cabal store of the Leios reference image), on 2026-11-21. The
  * program printed `unseedSMGen` of `mkStdGen` for seeds 0, 1, 4, 42 and -1; the first words of
  * `genWord64` from `mkStdGen 0` and `mkStdGen 4`; the first draws of `uniformR (0 :: Int, n)` for
  * the seeds and widths below, each from the generator the last one returned; the state after one
  * draw; and both halves of `splitGen`. A 64-bit word is printed unsigned there and compared here
  * by its bits.
  */
class StdGenSuite extends munit.FunSuite:
  private def word(s: String): Long = java.lang.Long.parseUnsignedLong(s)

  private def draws(seed: Long, high: Long, n: Int): Vector[Long] =
    Vector.unfold((StdGen.mk(seed), n))((g, left) =>
      Option.when(left > 0)(g.uniformR(0L, high)).map((x, next) => (x, (next, left - 1)))
    )

  private def words(seed: Long, n: Int): Vector[Long] =
    Vector.unfold((StdGen.mk(seed), n))((g, left) =>
      Option.when(left > 0)(g.nextWord64).map((x, next) => (x, (next, left - 1)))
    )

  test("mkStdGen gives the reference's seed and gamma") {
    val expected = Vector(
      0L -> ("0", "16294208416658607535"),
      1L -> ("12994781566227106604", "10451216379200822465"),
      4L -> ("5156626420896634997", "7958955049054603979"),
      42L -> ("9297814886316923340", "13679457532755275413"),
      -1L -> ("7256831767414464289", "16490336266968443937")
    )
    expected.foreach { case (seed, (s, g)) =>
      assertEquals(StdGen.mk(seed), StdGen.seeded(word(s), word(g)), s"seed $seed")
    }
  }

  test("nextWord64 gives the reference's words") {
    assertEquals(
      words(0, 4),
      Vector(
        "10697439907134545880",
        "17914770565119747336",
        "14757382890117164952",
        "8417835797766134635"
      )
        .map(word)
    )
    assertEquals(
      words(4, 4),
      Vector(
        "7758512093868915741",
        "3565601448639051278",
        "13248600118356758839",
        "12727429489933374557"
      )
        .map(word)
    )
  }

  test("uniformR on an Int gives the reference's draws") {
    assertEquals(draws(0, 5, 12), Vector[Long](0, 0, 0, 3, 4, 5, 0, 1, 1, 4, 1, 0))
    assertEquals(draws(4, 0, 12), Vector.fill[Long](12)(0))
    assertEquals(draws(4, 1, 12), Vector[Long](1, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0))
    assertEquals(draws(7, 2, 12), Vector[Long](0, 2, 2, 2, 2, 1, 2, 2, 0, 0, 2, 1))
    assertEquals(
      draws(99, 183, 12),
      Vector[Long](122, 60, 18, 86, 118, 140, 177, 30, 110, 24, 101, 173)
    )
    assertEquals(
      draws(3, 1000000, 12),
      Vector[Long](320297, 52569, 22974, 821238, 138503, 543169, 266621, 794041, 956435, 143897,
        192800, 513522)
    )
    // a range of one value takes no draw
    assertEquals(StdGen.mk(4).uniformR(0L, 0L)._2, StdGen.mk(4))
    assertEquals(
      StdGen.mk(0).uniformR(0L, 5L)._2,
      StdGen.seeded(word("16294208416658607535"), word("16294208416658607535"))
    )
  }

  test("splitGen gives the reference's halves") {
    assertEquals(
      StdGen.mk(0).split,
      (
        StdGen.seeded(word("14141672759607663454"), word("16294208416658607535")),
        StdGen.seeded(word("10697439907134545880"), word("15321243935405834545"))
      )
    )
    assertEquals(
      StdGen.mk(4).split,
      (
        StdGen.seeded(word("2627792445296291339"), word("7958955049054603979")),
        StdGen.seeded(word("7758512093868915741"), word("8963027016231436901"))
      )
    )
  }
