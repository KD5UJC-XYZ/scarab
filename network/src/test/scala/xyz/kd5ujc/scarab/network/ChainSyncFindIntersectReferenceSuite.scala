// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{
  CardanoBlockFetch,
  ChainSync,
  ChainSyncEngine,
  ChainSyncRequest,
  ProtocolError,
  StreamingDecoder
}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Message, Point, Role, State, UInt64}

/** Executed released-codec observations at ouroboros-network 2fb69829. Compact recipes retain exact
  * wire identities for NtN-sized requests, large NtC requests and opaque blocks, outgoing lists,
  * and incremental declared-length boundaries. The fixture README states the finite scope.
  */
class ChainSyncFindIntersectReferenceSuite extends FunSuite:
  import ChainSyncFindIntersectReferenceSuite.*

  private val rows = load
  private val payload = new ChainSync.PayloadCodec[Bytes]:
    def encode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)
    def decode(bytes: Bytes): Either[ProtocolError, Bytes] = Right(bytes)

  private def value[A](result: Either[ProtocolError, A]): A =
    result.fold(e => fail(e.toString), identity)
  private def assertWire(bytes: Bytes, expected: Option[Wire]): Unit =
    assertEquals(Some(Wire(bytes.size, sha256(bytes))), expected)

  private def streamed(bytes: Bytes, width: Int): DecodeResult[ChainSyncRequest[Point]] =
    val decoder = ChainSyncRequest.decoder
    @scala.annotation.tailrec
    def feed(
        cursor: decoder.Cursor,
        held: Bytes,
        at: Int,
        consumed: Int
    ): DecodeResult[ChainSyncRequest[Point]] =
      decoder.resume(cursor, held) match
        case StreamingDecoder.Step.Failed(error)  => DecodeResult.Failed(error)
        case StreamingDecoder.Step.Done(value, n) => DecodeResult.Decoded(value, consumed + n)
        case StreamingDecoder.Step.More(next, n) =>
          if at == bytes.size then DecodeResult.NeedMore
          else
            val end = (at + width).min(bytes.size)
            feed(next, held.drop(n) ++ bytes.slice(at, end), end, consumed + n)
    feed(decoder.initial, Bytes.empty, 0, 0)

  for row <- rows if row.kind == Kind.Points || row.kind == Kind.Prefix do
    test(s"streamed Cardano request: ${row.name}") {
      Vector(1, 7, 64).foreach { width =>
        streamed(row.bytes, width) match
          case DecodeResult.Decoded(ChainSyncRequest.FindIntersect(points), consumed) =>
            assert(row.accepted, s"native rejected ${row.name}")
            assertEquals(consumed, row.bytes.size)
            assertEquals(Some(points.length), row.points.map(_.toLong))
            assertEquals(points.headOption.map(first), row.first)
          case DecodeResult.NeedMore => assert(row.needMore)
          case DecodeResult.Failed(error) =>
            assert(!row.accepted && !row.needMore, error.message)
          case other => fail(s"unexpected request $other")
      }
    }

  test("an invalid long point hash is consumed before its size is rejected") {
    val prefix = hex("82049f82005a00001000")
    val raw = prefix ++ Bytes.wrap(Array.fill(4096)(0.toByte))
    assertEquals(streamed(raw.take(raw.size - 1), 7), DecodeResult.NeedMore)
    assertEquals(
      streamed(raw, 7),
      DecodeResult.Failed(ProtocolError.CardanoPointHashMustContain32Bytes(4096))
    )
  }

  test("streaming hash lengths cross Int.MaxValue without retaining the payload") {
    val decoder = ChainSyncRequest.decoder
    val prefix = hex("82049f82005b0000000080000000")
    val chunk = Bytes.wrap(Array.fill(1024 * 1024)(0.toByte))
    val first = decoder.resume(decoder.initial, prefix) match
      case StreamingDecoder.Step.More(cursor, consumed) =>
        assertEquals(consumed, prefix.size)
        cursor
      case other => fail(s"expected hash continuation: $other")
    val beforeLast = (0 until 2047).foldLeft(first) { (cursor, _) =>
      decoder.resume(cursor, chunk) match
        case StreamingDecoder.Step.More(next, consumed) =>
          assertEquals(consumed, chunk.size)
          next
        case other => fail(s"premature hash outcome: $other")
    }
    assertEquals(
      decoder.resume(beforeLast, chunk),
      StreamingDecoder.Step.Failed(
        ProtocolError.CardanoPointHashMustContain32Bytes(BigInt(1) << 31)
      )
    )
  }

  test("a wide streamed offer remains stack safe") {
    val bytes = hex("82049f") ++ Bytes.wrap(Array.fill(50000)(0x80.toByte)) ++ hex("ff")
    val task = new java.util.concurrent.FutureTask[DecodeResult[ChainSyncRequest[Point]]](() =>
      streamed(bytes, bytes.size)
    )
    val worker = new Thread(null, task, "streamed-chain-sync-small-stack", 256 * 1024)
    worker.start()
    val result = task.get()
    worker.join()
    result match
      case DecodeResult.Decoded(ChainSyncRequest.FindIntersect(points), consumed) =>
        assertEquals(points.length, 50000L)
        assertEquals(consumed, bytes.size)
      case other => fail(s"expected complete offer: $other")
  }

  test("the native corpus crosses the former candidate, item, and NtC byte bounds") {
    assert(rows.exists(_.count == 1025))
    assert(rows.exists(_.bytes.size > 2500000))
    assert(rows.exists(_.points.exists(_ > 65535)))
    assert(rows.exists(_.needMore))
    assert(rows.exists(row => !row.accepted && !row.needMore))
  }

  for row <- rows do
    test(row.name) {
      if row.kind != Kind.Encode then assertWire(row.bytes, row.input)
      val limits = if row.local then ChainSync.Limits.NodeToClient else ChainSync.Limits()
      row.kind match
        case Kind.Points | Kind.Prefix | Kind.BlockPrefix =>
          val (state, role) =
            if row.kind == Kind.BlockPrefix then (State.NextCanAwait, Role.Server)
            else (State.Idle, Role.Client)
          ChainSync.decodePrefix(state, role, row.bytes, payload, limits) match
            case DecodeResult.Decoded(message, consumed) =>
              assert(row.accepted, s"the reference refuses it: ${row.failure}")
              assertEquals(consumed, row.bytes.size)
              message match
                case Message.FindIntersect(points) =>
                  assertEquals(Some(points.size), row.points.map(_.toLong))
                  assertEquals(points.headOption.map(first), row.first)
                  assertWire(
                    value(ChainSync.encode(State.Idle, Role.Client, message, payload)),
                    row.encoded
                  )
                case other => fail(s"not a FindIntersect: $other")
            case DecodeResult.Failed(error) =>
              assert(!row.accepted && !row.needMore, s"the reference does not reject it: $error")
            case DecodeResult.NeedMore =>
              assert(row.needMore, s"the reference completes decoding: ${row.failure}")
        case Kind.Encode =>
          val point = row.first match
            case Some(RefPoint(Some(true), _, _)) => Point.Origin
            case Some(RefPoint(_, Some(slot), Some(hash))) =>
              Point.Block(value(UInt64.from(slot)), hex(hash))
            case other => fail(s"missing native outgoing point: $other")
          val message = Message.FindIntersect(Vector.fill(row.count)(point))
          assertWire(
            value(ChainSync.encode(State.Idle, Role.Client, message, payload)),
            row.encoded
          )
        case Kind.Opaque =>
          val point = ChainSyncEngine.pointCodec[Bytes](Right(_), Right(_))
          val tip = ChainSyncEngine.tipCodec[Bytes](Right(_), Right(_))
          val body = ChainSyncEngine.payloadCodec[Bytes](Right(_), Right(_))
          ChainSyncEngine.decodePrefix(State.Idle, Role.Client, row.bytes, point, tip, body) match
            case DecodeResult.Decoded(message, consumed) =>
              assertEquals(consumed, row.bytes.size)
              assertWire(
                value(ChainSyncEngine.encode(State.Idle, Role.Client, message, point, tip, body)),
                row.encoded
              )
            case other => fail(s"the reference accepts the opaque point: $other")
        case Kind.Block | Kind.SerialisedPrefix =>
          val blockCodec = CardanoBlockFetch.localPayloadCodec
          ChainSync.decodePrefix(
            State.NextCanAwait,
            Role.Server,
            row.bytes,
            blockCodec,
            limits
          ) match
            case DecodeResult.Decoded(message @ Message.RollForward(block, _), consumed) =>
              assert(row.accepted, s"the reference refuses it: ${row.failure}")
              assertEquals(consumed, row.bytes.size)
              assertWire(
                block.bytes.toBytes(Int.MaxValue).fold(e => fail(e.toString), identity),
                row.payload
              )
              assertWire(
                value(
                  ChainSync.encode(State.NextCanAwait, Role.Server, message, blockCodec, limits)
                ),
                row.encoded
              )
            case DecodeResult.Failed(error) =>
              assert(!row.accepted && !row.needMore, s"the reference does not reject it: $error")
            case DecodeResult.NeedMore =>
              assert(row.needMore, s"the reference completes decoding: ${row.failure}")
            case other => fail(s"not an opaque serialized block: $other")
    }

object ChainSyncFindIntersectReferenceSuite:
  enum Kind:
    case Points, Prefix, BlockPrefix, Encode, Opaque, Block, SerialisedPrefix

  final case class RefPoint(origin: Option[Boolean], slot: Option[BigInt], hash: Option[String])
  final case class Wire(bytes: Int, sha256: String)
  final case class Row(
      name: String,
      kind: Kind,
      local: Boolean,
      bytes: Bytes,
      count: Int,
      accepted: Boolean,
      needMore: Boolean,
      failure: Option[String],
      points: Option[Int],
      first: Option[RefPoint],
      input: Option[Wire],
      encoded: Option[Wire],
      payload: Option[Wire]
  )

  private def first(p: Point): RefPoint = p match
    case Point.Origin         => RefPoint(Some(true), None, None)
    case Point.Block(s, hash) => RefPoint(None, Some(s.value), Some(hash.hex))

  private given Decoder[Kind] = Decoder.decodeString.emap {
    case "points"            => Right(Kind.Points)
    case "prefix"            => Right(Kind.Prefix)
    case "block-prefix"      => Right(Kind.BlockPrefix)
    case "encode"            => Right(Kind.Encode)
    case "opaque"            => Right(Kind.Opaque)
    case "block"             => Right(Kind.Block)
    case "serialised-prefix" => Right(Kind.SerialisedPrefix)
    case other               => Left(s"unknown native case kind: $other")
  }
  private given Decoder[RefPoint] = Decoder.forProduct3("origin", "slot", "hash")(RefPoint.apply)
  private given Decoder[Wire] = Decoder.forProduct2("bytes", "sha256")(Wire.apply)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => sys.error(e.toString), identity)
  private def sha256(bytes: Bytes): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes.value.toArray)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private given Decoder[Row] = Decoder.instance { c =>
    for
      name <- c.get[String]("name")
      kind <- c.get[Kind]("kind")
      transport <- c.get[Option[String]]("transport")
      prefix <- c.getOrElse[String]("prefix")("")
      unit <- c.getOrElse[String]("unit")("")
      count <- c.get[Int]("count")
      suffix <- c.getOrElse[String]("suffix")("")
      accepted <- c.get[Boolean]("accepted")
      needMore <- c.getOrElse[Boolean]("needMore")(false)
      failure <- c.get[Option[String]]("failure")
      points <- c.get[Option[Int]]("points")
      first <- c.get[Option[RefPoint]]("first")
      input <- c.get[Option[Wire]]("input")
      encoded <- c.get[Option[Wire]]("encoded")
      payload <- c.get[Option[Wire]]("payload")
    yield Row(
      name,
      kind,
      transport.contains("ntc"),
      hex(prefix) ++ Bytes(Vector.fill(count)(hex(unit).value).flatten) ++ hex(suffix),
      count,
      accepted,
      needMore,
      failure,
      points,
      first,
      input,
      encoded,
      payload
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
