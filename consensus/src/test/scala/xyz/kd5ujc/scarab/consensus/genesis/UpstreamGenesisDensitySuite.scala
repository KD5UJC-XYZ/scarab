// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.genesis

import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}

import GenesisDensity.*

/** Every native output field is compared, including ordered losses and every density bound. */
class UpstreamGenesisDensitySuite extends FunSuite:
  private def field[A: Decoder](json: Json, key: String): A =
    json.hcursor.get[A](key).fold(error => fail(error.toString), identity)
  private def point(json: Json): Point =
    if json.isNull then Point.Origin
    else
      Point.Block(
        UInt64.from(field[BigInt](json, "slot")).fold(error => fail(error.toString), identity),
        Bytes.fromHex(field[String](json, "hash")).fold(error => fail(error.toString), identity)
      )
  private def fragment(json: Json): Fragment =
    Fragment(
      point(field[Json](json, "anchor")),
      field[Vector[Json]](json, "headers").map { header =>
        point(header) match
          case block: Point.Block => block
          case Point.Origin       => fail("native header is Origin")
      }
    )
  private def latest(json: Json): LatestSlot = field[String](json, "tag") match
    case "unknown" => LatestSlot.Unknown
    case "origin"  => LatestSlot.Origin
    case "at"      => LatestSlot.At(field[BigInt](json, "slot"))
    case tag       => fail(s"unknown native LatestSlot tag: $tag")
  private def bounds(json: Json): Bounds = Bounds(
    fragment(field[Json](json, "clippedFragment")),
    field[Boolean](json, "offersMoreThanK"),
    field[BigInt](json, "lowerBound"),
    field[BigInt](json, "upperBound"),
    field[Boolean](json, "hasBlockAfter"),
    latest(field[Json](json, "latestSlot")),
    field[Boolean](json, "idling")
  )

  private val path = Path.of("fixtures/reference/genesis-density/density.jsonl")
  private val rows = Files.readAllLines(path).asScala.toVector.map { line =>
    parse(line).fold(error => fail(error.toString), identity)
  }
  rows.foreach { row =>
    test(field[String](row, "label")) {
      val states = field[Vector[Json]](row, "states").map { state =>
        field[Int](state, "peer") -> PeerState(
          fragment(field[Json](state, "candidate")),
          latest(field[Json](state, "latestSlot")),
          field[Boolean](state, "idling")
        )
      }
      val expectedPrefix = fragment(field[Json](row, "sharedPrefix"))
      val expectedSuffixes = field[Vector[Json]](row, "suffixes").map { suffix =>
        field[Int](suffix, "peer") -> fragment(field[Json](suffix, "fragment"))
      }
      val shared = sharedCandidatePrefix(
        fragment(field[Json](row, "current")),
        states.map { case (p, s) => p -> s.candidate }
      )
      assertEquals(shared, Shared(expectedPrefix, expectedSuffixes))
      val expectedBounds = field[Vector[Json]](row, "bounds").map { b =>
        field[Int](b, "peer") -> bounds(b)
      }
      val expected = Result(field[Vector[Int]](row, "losingPeers"), expectedBounds)
      assertEquals(
        densityDisconnect(
          field[BigInt](row, "genesisWindow"),
          field[Int](row, "k"),
          states.toMap,
          shared.suffixes,
          shared.prefix
        ),
        expected
      )
    }
  }
