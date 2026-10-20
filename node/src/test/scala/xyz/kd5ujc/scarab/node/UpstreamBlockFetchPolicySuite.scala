// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, FetchGsv, FetchMode}
import BlockFetchPolicy.*

/** Exact observations of native Decision.hs, with generated mock headers retained losslessly. */
class UpstreamBlockFetchPolicySuite extends FunSuite:
  private val base = Path.of("fixtures/reference/block-fetch-scheduling")
  private def json(text: String): Json = parse(text).fold(e => fail(e.message), identity)
  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(s"$name: $e"), identity)
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.message), identity)
  private def optionalBytes(j: Json, name: String): Option[Bytes] =
    field[Option[String]](j, name).map(bytes)
  private def gsv(j: Json): FetchGsv.Gsv =
    FetchGsv.Gsv(field[BigInt](j, "g"), field[BigInt](j, "s"), field[BigInt](j, "v"))
  private def performance(j: Json): FetchGsv.PeerGsv = FetchGsv.PeerGsv(
    field[BigInt](j, "time"),
    gsv(field[Json](j, "outbound")),
    gsv(field[Json](j, "inbound"))
  )
  private def fragment(j: Json): Fragment = Fragment(
    optionalBytes(j, "anchor"),
    field[Vector[Json]](j, "headers").map(h =>
      Header(bytes(field[String](h, "hash")), field[BigInt](h, "slot"), field[Long](h, "bytes"))
    )
  )
  private def refusal(j: Json, mode: FetchMode): Refusal = field[String](j, "tag") match
    case "ChainNotPlausible"   => Refusal.ChainNotPlausible
    case "IntersectionTooDeep" => Refusal.IntersectionTooDeep
    case "AlreadyFetched"      => Refusal.AlreadyFetched
    case "InFlightThisPeer"    => Refusal.InFlightThisPeer
    case "InFlightOtherPeer"   => Refusal.InFlightOtherPeer
    case "PeerShutdown"        => Refusal.PeerShutdown
    case "PeerStarting"        => Refusal.PeerStarting
    case "PeerSlow"            => Refusal.PeerSlow
    case "RequestsInFlight"    => Refusal.RequestsInFlight(field[Int](j, "limit"))
    case "Concurrency"         => Refusal.Concurrency(mode, field[Int](j, "limit"))
    case "BytesInFlight" =>
      Refusal.BytesInFlight(field[Long](j, "bytes"), field[Long](j, "low"), field[Long](j, "high"))
    case "PeerBusy" =>
      Refusal.PeerBusy(field[Long](j, "bytes"), field[Long](j, "low"), field[Long](j, "high"))
    case other => fail(s"unknown native refusal $other")
  private def candidate(j: Json, mode: FetchMode): Candidate[Int] = Candidate(
    field[Int](j, "peer"),
    field[Int](j, "preference"),
    optionalBytes(j, "head").getOrElse(Bytes.empty),
    field[Vector[Json]](j, "fragments").map(fragment),
    performance(field[Json](j, "gsv")),
    InFlight(
      field[Int](j, "requests"),
      field[Long](j, "bytes"),
      field[Vector[String]](j, "blocks").map(bytes).toSet
    ),
    Status.valueOf(field[String](j, "status")),
    field[Long](j, "tie"),
    field[BigInt](j, "headSlot"),
    field[Option[Json]](j, "refusal").map(refusal(_, mode))
  )
  private val manifest = json(Files.readString(base.resolve("manifest.json")))
  private val rows =
    Files.readString(base.resolve("decisions.jsonl")).linesIterator.map(json).toVector

  test("the native scheduling corpus retains its source pin, generation and payload hashes") {
    assertEquals(
      field[String](manifest, "sourceCommit"),
      "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
    )
    val hash = MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(base.resolve("decisions.jsonl")))
      .map(b => f"${b & 0xff}%02x")
      .mkString
    assertEquals(hash, field[String](manifest, "sha256"))
    val gsvHash = MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(base.resolve("decisions.jsonl.gsv.jsonl")))
      .map(b => f"${b & 0xff}%02x")
      .mkString
    assertEquals(gsvHash, field[String](manifest, "gsvSha256"))
    assertEquals(rows.size, field[Int](manifest, "cases"))
    assertEquals(rows.size, 289)
    assertEquals(field[Int](manifest, "boundaryCases"), 33)
    assertEquals(rows.count(j => field[String](j, "label").startsWith("generated-")), 256)
    val generated = rows.filter(j => field[String](j, "label").startsWith("generated-"))
    assertEquals(
      generated.map(field[Int](_, "seed")).distinct.sorted,
      (148001 until 148129).toVector
    )
    generated.groupBy(field[Int](_, "seed")).values.foreach { cases =>
      assertEquals(cases.map(field[String](_, "mode")).toSet, Set("BulkSync", "Deadline"))
    }
    assert(rows.exists(j => field[String](j, "label") == "boundary-preferred-handover"))
    assert(rows.exists(j => field[String](j, "label") == "boundary-whole-block-overshoot"))
  }

  rows.foreach { row =>
    val label = field[String](row, "label")
    val mode = FetchMode.valueOf(field[String](row, "mode"))
    test(s"native BlockFetch policy $label / $mode") {
      val input = field[Vector[Json]](row, "candidates")
      val candidates = input.map(candidate(_, mode))
      input.zip(candidates).foreach { case (original, c) =>
        assertEquals(c.performance.window.lowBytes, field[Long](original, "windowLow"))
        assertEquals(c.performance.window.highBytes, field[Long](original, "windowHigh"))
        val requested = c.fragments.flatMap(_.headers).map(_.bytes).sum
        assertEquals(
          c.performance.duration(c.inFlight.bytes, requested),
          field[BigInt](original, "duration")
        )
        assertEquals(
          c.performance.probability(c.inFlight.bytes, requested),
          field[Double](original, "probability")
        )
      }
      assertEquals(
        BlockFetchPolicy.rank(mode, candidates).map(_.peer),
        field[Vector[Int]](row, "rank")
      )
      val cfg = field[Json](row, "config")
      val config =
        Config(field[Int](cfg, "bulk"), field[Int](cfg, "deadline"), field[Int](cfg, "requests"))
      val expected = field[Vector[Json]](row, "decisions").map { d =>
        field[Option[Json]](d, "refusal") match
          case Some(r) => Decision.Declined(field[Int](d, "peer"), refusal(r, mode))
          case None =>
            Decision.Request(
              field[Int](d, "peer"),
              field[Vector[Json]](d, "fragments").map(fragment)
            )
      }
      assertEquals(BlockFetchPolicy.decide(config, mode, candidates), expected)
    }
  }

  test("native GSV default, samples and smoothing preserve picosecond boundary behavior") {
    val samples =
      Files.readString(base.resolve("decisions.jsonl.gsv.jsonl")).linesIterator.map(json).toVector
    assertEquals(samples.size, 8)
    samples.foreach { sample =>
      assertEquals(FetchGsv.default, performance(field[Json](sample, "default")))
      val fresh = FetchGsv.fromSample(field[BigInt](sample, "start"), field[BigInt](sample, "end"))
      assertEquals(fresh, performance(field[Json](sample, "fresh")))
      assertEquals(
        fresh.update(performance(field[Json](sample, "previous"))),
        performance(field[Json](sample, "combined")),
        s"sample $sample"
      )
    }
  }
