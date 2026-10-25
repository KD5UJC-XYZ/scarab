// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import io.circe.{ACursor, Decoder}
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, HaskellSort}
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment
import xyz.kd5ujc.scarab.ledger.{Header, HeaderVrf, VrfCert}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}

/** Selection-only synthetic headers; crypto/transport admission is a separate boundary. */
class CandidateSelectionSuite extends FunSuite:
  private def bytes(n: Int, size: Int = 32): Bytes =
    Bytes.fromArray(Array.fill[Byte](size)(n.toByte))
  private def header(
      id: Int,
      number: Int,
      slot: Int,
      vrf: Int,
      parent: Option[Bytes] = None,
      issuer: Int = 99,
      issue: Int = 0
  ): Header.Shelley =
    Header.Shelley(
      Era.Conway,
      bytes(id),
      bytes(id),
      bytes(id),
      number,
      slot,
      parent,
      bytes(issuer),
      bytes(0),
      HeaderVrf.Praos(VrfCert(bytes(vrf, 64), Bytes.empty)),
      0,
      bytes(0),
      Header.OpCert(Bytes.empty, issue, 0, Bytes.empty),
      (10, 0),
      Bytes.empty
    )
  private def candidate(headers: Header*): ChainSelection.Candidate =
    ChainSelection.Candidate(ChainSync.Point.Origin, headers.toVector)
  private val origin =
    ChainSelection.Local(ChainSync.Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
  private def selected(h: Header): ChainSelection.Local =
    origin.copy(blocks = Vector(ChainSelection.LocalBlock(h.point, SelectView.of(h))))

  test(
    "project every path before choosing: a raw longer LoE-blocked path cannot hide a preferred fork"
  ) {
    val s1 = header(1, 1, 1, 200)
    val s2 = header(2, 2, 2, 200, Some(s1.hash))
    val ours = origin.copy(blocks =
      Vector(s1, s2).map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
    )
    val a1 = header(10, 1, 1, 100)
    val a2 = header(11, 2, 2, 100, Some(a1.hash))
    val a3 = header(12, 3, 3, 100, Some(a2.hash))
    val b2 = header(20, 2, 3, 1, Some(s1.hash), issuer = 1)
    val b3 = header(21, 3, 4, 1, Some(b2.hash), issuer = 1)
    val limit = Fragment(
      ChainSync.Point.Origin,
      Vector(s1.point).collect { case p: ChainSync.Point.Block => p }
    )
    assertEquals(
      CandidateSelection.project(
        1,
        ours,
        Vector(candidate(a1, a2, a3), ChainSelection.Candidate(s1.point, Vector(b2, b3))),
        Some(limit)
      ),
      Vector(ChainSelection.Candidate(s1.point, Vector(b2)))
    )
  }

  test("restricted eligibility and unrestricted native ranking have separate roles") {
    val a = header(1, 20, 0, 3, issuer = 1)
    val b = header(2, 20, 4, 2, issuer = 2)
    val c = header(3, 20, 8, 1, issuer = 3)
    val all = Vector(candidate(a), candidate(c), candidate(b))
    val shorter = selected(header(90, 19, 0, 250))
    assertEquals(
      CandidateSelection.project(10, shorter, all, None),
      Vector(candidate(c), candidate(b), candidate(a))
    )
    val equal = selected(header(90, 20, 0, 250))
    assertEquals(
      CandidateSelection.project(10, equal, all, None),
      Vector(candidate(b), candidate(a))
    )
  }

  test("empty and detached candidates do not enter the sorted eligible pool") {
    val detached = ChainSelection.Candidate(header(9, 1, 1, 1).point, Vector(header(2, 3, 3, 1)))
    assertEquals(
      CandidateSelection.project(2, origin, Vector(candidate(), detached), None),
      Vector.empty
    )
  }

  test("all native candidate orders, eligibility decisions and pairwise Ord results agree") {
    def field[A: Decoder](c: ACursor, name: String): A =
      c.get[A](name).fold(e => fail(e.message), identity)
    def view(c: ACursor, kind: String): SelectView =
      if kind == "pbft" then
        SelectView(field[BigInt](c, "blockNo"), 0, None, 0, None, field[Boolean](c, "isEBB"))
      else
        SelectView(
          field[BigInt](c, "blockNo"),
          field[BigInt](c, "slot"),
          Some(bytes(field[Int](c, "issuer"))),
          field[BigInt](c, "issueNo"),
          Some(bytes(field[Int](c, "vrf"), 64))
        )
    val rows = Files
      .readString(Path.of("fixtures/reference/candidate-order/native.jsonl"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(line => parse(line).fold(e => fail(e.message), identity).hcursor)
      .toVector
    assertEquals(rows.size, 24)
    rows.foreach { row =>
      val input = row.downField("input")
      val output = row.downField("output")
      val label = field[String](input, "label")
      val kind = field[String](input, "kind")
      val local = view(input.downField("local"), kind)
      val items = field[Vector[io.circe.Json]](input, "input").map { json =>
        field[Int](json.hcursor, "id") -> view(json.hcursor, kind)
      }
      val eligible = items.filter((_, candidate) =>
        ChainSelection.prefers(
          if kind == "pbft" then ChainSelection.Tiebreaker.Unrestricted
          else ChainSelection.Tiebreaker.Restricted(field[BigInt](input, "restriction")),
          local,
          candidate
        )
      )
      def ordered(values: Vector[(Int, SelectView)]) =
        HaskellSort
          .sortBy(values)((a, b) => CandidateSelection.compareViews(a._2, b._2))
          .map(_._1)
      assertEquals(eligible.map(_._1), field[Vector[Int]](output, "eligibleIds"), label)
      assertEquals(ordered(items), field[Vector[Int]](output, "sortedIds"), label)
      assertEquals(ordered(eligible), field[Vector[Int]](output, "eligibleSortedIds"), label)
      val comparisons = items.map { (_, a) =>
        items.map { (_, b) =>
          val compared = CandidateSelection.compareViews(a, b)
          if compared < 0 then "GT" else if compared > 0 then "LT" else "EQ"
        }
      }
      assertEquals(comparisons, field[Vector[Vector[String]]](output, "pairwiseOrd"), label)

    }
  }

  test("every raw native Data.List.sortBy synthetic comparison case agrees") {
    val rows = Files
      .readString(Path.of("fixtures/reference/candidate-order/sort.jsonl"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(line => parse(line).fold(e => fail(e.message), identity).hcursor)
      .toVector
    assertEquals(rows.size, 7)
    def field[A: Decoder](c: ACursor, name: String): A =
      c.get[A](name).fold(e => fail(e.message), identity)
    def compare(a: Int, b: Int): Int = if (a - b).abs > 5 then 0 else Integer.compare(a, b)
    rows.foreach { row =>
      val input = field[Vector[Int]](row, "input")
      assertEquals(HaskellSort.sortBy(input)(compare), field[Vector[Int]](row, "sorted"))
      val matrix = input.map(a =>
        input.map(b =>
          val result = compare(a, b)
          if result < 0 then "LT" else if result > 0 then "GT" else "EQ"
        )
      )
      assertEquals(matrix, field[Vector[Vector[String]]](row, "pairwiseOrd"))
    }
  }

  test("initial selection caps active LoE paths at k even when a live limit has advanced") {
    val headers = (1 to 8).toVector.map(n => header(n, n, n, 0, Option.when(n > 1)(bytes(n - 1))))
    val resumed = origin.copy(blocks =
      headers.take(3).map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
    )
    val advanced = Fragment(
      ChainSync.Point.Origin,
      headers.take(3).map(_.point).collect { case point: ChainSync.Point.Block =>
        point
      }
    )
    val candidates = Vector(ChainSelection.Candidate(ChainSync.Point.Origin, headers))
    val bounded = CandidateSelection.startup(
      3,
      resumed,
      candidates,
      Some(advanced),
      ChainSelection.tiebreakerFor
    )
    val unbounded =
      CandidateSelection.startup(3, resumed, candidates, None, ChainSelection.tiebreakerFor)
    assertEquals(bounded.map(_.headers), Vector(headers.take(3)))
    assertEquals(unbounded.map(_.headers), Vector(headers))
  }
