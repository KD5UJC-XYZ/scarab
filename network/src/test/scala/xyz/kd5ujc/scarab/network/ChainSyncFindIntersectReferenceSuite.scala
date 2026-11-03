// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Message, Point, Role, State}

/** `fixtures/reference/chainsync-find-intersect/cases.jsonl` holds client messages of node-to-node
  * ChainSync with the verdict of the released `codecChainSync` (ouroboros-network 1.2.0.0) and the
  * point decoder consensus gives it, `decodePoint (decodeRawHash p)`, read in the server's
  * `StIdle`. `MsgFindIntersect` takes as many points as its 65535 bytes hold, and a point's hash
  * exactly 32 bytes. The node's server must read each as the reference does: the same verdict, and
  * for an accepted one the same points (#755).
  */
class ChainSyncFindIntersectReferenceSuite extends FunSuite:
  import ChainSyncFindIntersectReferenceSuite.*

  private val rows = load

  private val payload = new ChainSync.PayloadCodec[Bytes]:
    def encode(value: Bytes): Either[xyz.kd5ujc.scarab.protocol.ProtocolError, Bytes] = Right(value)
    def decode(bytes: Bytes): Either[xyz.kd5ujc.scarab.protocol.ProtocolError, Bytes] = Right(bytes)

  test("the corpus has accepted and refused cases, and messages up to the codec's bound") {
    assert(rows.exists(_.accepted) && rows.exists(!_.accepted))
    assert(rows.exists(_.bytes.size > 60000))
    assert(rows.forall(_.bytes.size <= 65535))
  }

  for row <- rows do
    test(row.name) {
      ChainSync.decodePrefix(State.Idle, Role.Client, row.bytes, payload) match
        case DecodeResult.Decoded(message, consumed) =>
          assert(row.accepted, s"the reference refuses it: ${row.failure}")
          assertEquals(consumed, row.bytes.size)
          message match
            case Message.FindIntersect(points) =>
              assertEquals(Some(points.size), row.points)
              assertEquals(points.headOption.map(first), row.first.flatten)
            case other => fail(s"not a FindIntersect: $other")
        case DecodeResult.Failed(error) =>
          assert(!row.accepted, s"the reference reads it: $error")
        case DecodeResult.NeedMore =>
          fail(s"a complete message is read as incomplete; the reference: ${row.failure}")
    }

object ChainSyncFindIntersectReferenceSuite:
  /** A point as the oracle reports it: the origin, or a slot and a hash. */
  final case class RefPoint(origin: Option[Boolean], slot: Option[BigInt], hash: Option[String])

  final case class Row(
      name: String,
      bytes: Bytes,
      accepted: Boolean,
      failure: Option[String],
      points: Option[Int],
      first: Option[Option[RefPoint]]
  )

  private def first(p: Point): RefPoint = p match
    case Point.Origin         => RefPoint(Some(true), None, None)
    case Point.Block(s, hash) => RefPoint(None, Some(s.value), Some(hash.hex))

  private given Decoder[RefPoint] =
    Decoder.forProduct3("origin", "slot", "hash")(RefPoint.apply)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => sys.error(e.toString), identity)

  private given Decoder[Row] = Decoder.instance { c =>
    for
      name <- c.get[String]("name")
      prefix <- c.get[String]("prefix")
      unit <- c.get[String]("unit")
      count <- c.get[Int]("count")
      suffix <- c.get[String]("suffix")
      accepted <- c.get[Boolean]("accepted")
      failure <- c.get[Option[String]]("failure")
      points <- c.get[Option[Int]]("points")
      first <- c.get[Option[Option[RefPoint]]]("first")
    yield Row(
      name,
      hex(prefix) ++ Bytes(Vector.fill(count)(hex(unit).value).flatten) ++ hex(suffix),
      accepted,
      failure,
      points,
      first
    )
  }

  private def load: Vector[Row] =
    val path = Path.of("fixtures/reference/chainsync-find-intersect/cases.jsonl")
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => parser.decode[Row](line).fold(e => sys.error(s"$path: $e"), identity))
