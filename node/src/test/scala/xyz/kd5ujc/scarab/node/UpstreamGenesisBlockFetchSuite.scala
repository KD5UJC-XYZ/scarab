// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.selection.{
  BlockFetchPolicy,
  FetchGsv,
  FetchMode,
  GenesisBlockFetch
}

import BlockFetchPolicy.*

/** Exact observations of native Decision.hs, with generated mock headers retained losslessly. */
class UpstreamGenesisBlockFetchSuite extends FunSuite:
  private val base = Path.of("fixtures/reference/genesis-block-fetch")
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
    case "GenesisConcurrency"  => Refusal.GenesisConcurrency(field[Int](j, "limit"))
    case "Concurrency"         => Refusal.Concurrency(mode, field[Int](j, "limit"))
    case "BytesInFlight" =>
      Refusal.BytesInFlight(field[Long](j, "bytes"), field[Long](j, "low"), field[Long](j, "high"))
    case "PeerBusy" =>
      Refusal.PeerBusy(field[Long](j, "bytes"), field[Long](j, "low"), field[Long](j, "high"))
    case other => fail(s"unknown native refusal $other")
  private def candidate(j: Json): Candidate[Int] = Candidate(
    peer = field[Int](j, "peer"),
    chainPreference = field[Int](j, "preference"),
    chainHead = Bytes.empty,
    fragments = field[Vector[Json]](j, "fragments").map(fragment),
    performance = performance(field[Json](j, "gsv")),
    inFlight = InFlight(
      field[Int](j, "requests"),
      field[Long](j, "bytes"),
      field[Vector[String]](j, "blocks").map(bytes).toSet
    ),
    status = Status.valueOf(field[String](j, "status")),
    refusal = field[Option[Json]](j, "refusal").map(refusal(_, FetchMode.BulkSync))
  )
  private def state(j: Json): GenesisBlockFetch.State[Int] = GenesisBlockFetch.State(
    field[Vector[Int]](j, "order"),
    field[Option[Int]](j, "current"),
    field[BigInt](j, "start")
  )
  private def decision(j: Json): Decision[Int] =
    field[Option[Json]](j, "refusal") match
      case Some(reason) =>
        Decision.Declined(field[Int](j, "peer"), refusal(reason, FetchMode.BulkSync))
      case None =>
        Decision.Request(field[Int](j, "peer"), field[Vector[Json]](j, "fragments").map(fragment))
  private val rows =
    Files.readString(base.resolve("decisions.jsonl")).linesIterator.map(json).toVector
  test("native Genesis corpus pin, all generated seeds and payload hash") {
    val manifest = json(Files.readString(base.resolve("manifest.json")))
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
    assertEquals(rows.size, field[Int](manifest, "cases"))
    assertEquals(
      rows
        .filter(j => field[String](j, "label").startsWith("generated-"))
        .map(field[Int](_, "seed"))
        .sorted,
      (330001 to 330128).toVector
    )
  }
  rows.foreach { row =>
    test(s"native Genesis decision ${field[String](row, "label")}") {
      val starvation = field[Json](row, "starvation")
      val starved = field[Option[BigInt]](starvation, "ended").fold[GenesisBlockFetch.Starvation](
        GenesisBlockFetch.Starvation.Ongoing
      )(GenesisBlockFetch.Starvation.EndedAt(_))
      val actual = GenesisBlockFetch.decide(
        Config(maxRequestsPerPeer = field[Int](row, "maxRequests")),
        state(field[Json](row, "before")),
        field[BigInt](row, "now"),
        starved,
        field[Vector[Json]](row, "candidates").map(candidate),
        field[BigInt](row, "grace"),
        field[Vector[String]](row, "fetched").map(bytes).toSet
      )
      assertEquals(actual.state, state(field[Json](row, "after")))
      assertEquals(actual.decisions, field[Vector[Json]](row, "decisions").map(decision))
      assertEquals(actual.demoteDynamo.toVector, field[Vector[Int]](row, "demotions"))
    }
  }
