// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** Actual pinned Haskell policy observations with identical unsigned Word32 inputs. These
  * comparisons exercise the selectors used by production, independently of PRNG seed mapping.
  */
class NativeSelectionPolicySuite extends FunSuite:
  private val path = Path.of("fixtures/peer-selection/policies/decisions.jsonl")

  test("all native decisions and complete hot-demotion score maps agree") {
    val lines = Files.readAllLines(path).asScala.toList
    assertEquals(lines.size, 912)
    lines.zipWithIndex.foreach { (line, index) =>
      val json = parse(line).fold(e => fail(e.message), identity)
      val c = json.hcursor
      def get[A: io.circe.Decoder](field: String): A =
        c.get[A](field).fold(e => fail(s"case $index $field: ${e.message}"), identity)
      val words = get[List[(Int, Long)]]("draws")
      val n = get[Int]("n")
      val tepid = get[Set[Int]]("tepid")
      val failures = get[List[(Int, Int)]]("failures").toMap
      val scores = get[List[io.circe.Json]]("scores").flatMap { entry =>
        val e = entry.hcursor
        val peer = e.get[Int]("peer").fold(err => fail(err.message), identity)
        val score = e.get[Option[Int]]("score").fold(err => fail(err.message), identity)
        val joined = e.get[Option[Long]]("joined").fold(err => fail(err.message), identity)
        score.map(value => peer -> HotScore(value, joined))
      }.toMap
      def check(name: String, actual: List[Int]): Unit =
        assertEquals(actual.sorted, get[List[Int]](name), s"case $index $name")
      check("promotion", Picks.promotionWords(words, n))
      check("warmPromotion", Picks.warmPromotionWords(words, n, tepid))
      check("warmDemotion", Picks.warmDemotionWords(words, n, tepid))
      check("forget", Picks.forgetWords(words, n, failures))
      check(
        "hotDemotion",
        Picks.hotDemotionWords(words, n, p => scores.getOrElse(p, HotScore.Unknown))
      )
      val traced = Picks.hotDemotionScores(words.map(_._1), scores.get)
      assertEquals(traced, get[List[(Int, Option[Int])]]("traced").toMap, s"case $index traced")
    }
  }
