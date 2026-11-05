// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.data.Chain
import java.security.MessageDigest
import java.nio.file.{Files, Path}
import io.circe.parser.parse
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, ChunkedBytes}

import ChainSync.{DecodeResult, Message, Point, Role, State, Tip}

class ChunkedChainSyncSuite extends munit.FunSuite:
  private val codec = CardanoBlockFetch.localPayloadCodec
  private val limits = ChainSync.Limits.NodeToClient
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def hex(value: String): Bytes = right(Bytes.fromHex(value))
  private def sha(bytes: ChunkedBytes): String =
    val digest = MessageDigest.getInstance("SHA-256")
    bytes.foldChunks(())((_, chunk) => chunk.withRange(digest.update))
    Bytes.fromArray(digest.digest()).hex

  private def streamed[A](
      decoder: StreamingDecoder[A],
      chunks: Chain[Bytes]
  ): Either[ProtocolError, (A, Long)] =
    @tailrec def loop(
        cursor: decoder.Cursor,
        rest: Chain[Bytes],
        held: Bytes,
        used: Long
    ): Either[ProtocolError, (A, Long)] =
      decoder.resume(cursor, held) match
        case StreamingDecoder.Step.Failed(error) => Left(error)
        case StreamingDecoder.Step.Done(value, consumed) =>
          assert(consumed >= 0 && consumed <= held.size)
          Right(value -> (used + consumed))
        case StreamingDecoder.Step.More(next, consumed) =>
          assert(consumed >= 0 && consumed <= held.size)
          rest.uncons match
            case None => Left(ProtocolError.TruncatedCbor)
            case Some((chunk, tail)) =>
              val suffix = held.drop(consumed)
              loop(next, tail, if suffix.isEmpty then chunk else suffix ++ chunk, used + consumed)
    loop(decoder.initial, chunks, Bytes.empty, 0L)

  test("native opaque serialized value crosses 2 GiB without a whole-message array") {
    val expected = right(
      parse(
        Files.readString(
          Path.of(
            "fixtures/reference/chainsync-wide-serialised/expected.json"
          )
        )
      )
    ).hcursor
    assertEquals(right(expected.get[Int]("schemaVersion")), 1)
    assertEquals(right(expected.get[Boolean]("accepted")), true)
    val recipe = expected.downField("recipe")
    val length = right(recipe.get[Long]("payloadLength"))
    assert(length > Int.MaxValue.toLong)
    val chunkSize = right(expected.get[Int]("feedChunkBytes"))
    val byte = right(recipe.get[Int]("payloadByte"))
    assert(chunkSize > 0 && byte >= 0 && byte <= 255)
    val chunk = Bytes.fromArray(Array.fill[Byte](chunkSize)(byte.toByte))
    @tailrec def repeat(remaining: Long, out: Chain[Bytes]): Chain[Bytes] =
      if remaining == 0 then out
      else
        val count = math.min(remaining, chunkSize.toLong).toInt
        repeat(remaining - count, out.append(chunk.take(count)))
    val body = right(ChunkedBytes.fromChunks(repeat(length, Chain.empty)))
    val prefix = hex(right(recipe.get[String]("prefixHex")))
    val suffix = hex(right(recipe.get[String]("suffixHex")))
    val payloadHash = right(expected.downField("payload").get[String]("sha256"))
    val block = CardanoBlockFetch.localBlock(body)
    val message = Message.RollForward(block, Tip.Origin)
    val wire =
      right(ChainSync.encodeChunked(State.NextCanAwait, Role.Server, message, codec, limits))
    // Executed pinned native codec/plain runPeer witness; opaque bytes, not a valid ledger block.
    assert(body.size > Int.MaxValue.toLong)
    assertEquals(body.size, right(expected.downField("payload").get[Long]("bytes")))
    assertEquals(wire.size, right(expected.downField("wire").get[Long]("bytes")))
    assertEquals(right(right(wire.take(prefix.size)).toBytes(prefix.size)), prefix)
    assertEquals(
      right(right(wire.slice(wire.size - suffix.size, wire.size)).toBytes(suffix.size)),
      suffix
    )
    assertEquals(sha(body), payloadHash)
    assertEquals(sha(wire), right(expected.downField("wire").get[String]("sha256")))
    assertEquals(
      codec.encode(block),
      Left(
        ProtocolError.ChunkedRepresentation(
          ChunkedBytes.Error.ContiguousLimitExceeded(body.size + prefix.size - 2, Int.MaxValue)
        )
      )
    )
    val (decoded, used) =
      right(streamed(ChainSync.responseDecoder(State.NextCanAwait, codec), wire.chunks))
    assertEquals(used, wire.size)
    decoded match
      case Message.RollForward(value, tip) =>
        assertEquals(value.bytes.size, body.size)
        assertEquals(tip, Tip.Origin)
        assertEquals(sha(value.bytes), payloadHash)
      case other => fail(s"unexpected response: $other")
  }

  test(
    "fragmented local responses preserve wrapper refusals, body bytes, tips and coalesced suffix"
  ) {
    val cases = Vector(
      State.NextCanAwait -> "8101",
      State.NextCanAwait -> "8302d81843ff0080828000",
      State.NextCanAwait -> "830380828000",
      State.Intersect -> "830580828000",
      State.Intersect -> "8206828000",
      State.NextCanAwait -> "8302d819",
      State.NextCanAwait -> "8302d8185f",
      State.NextCanAwait -> "8302d8185b8000000000000000",
      State.NextCanAwait -> "8302d8185a80000001",
      State.Intersect -> "820682820040"
    )
    cases.foreach { (state, literal) =>
      val input = hex(literal)
      val expected = ChainSync.decodePrefix(state, Role.Server, input, codec, limits)
      (1 to 17).foreach { width =>
        val chunks = Chain.fromSeq(input.toArray.grouped(width).map(Bytes.fromArray).toVector)
        val actual = streamed(ChainSync.responseDecoder(state, codec), chunks)
        expected match
          case DecodeResult.Decoded(value, used) =>
            assertEquals(actual, Right(value -> used.toLong))
          case DecodeResult.Failed(error) => assertEquals(actual, Left(error))
          case DecodeResult.NeedMore      => assertEquals(actual, Left(ProtocolError.TruncatedCbor))
      }
    }
    val input = hex("8302d81841ff8280008101")
    val chunks = Chain.fromSeq(input.toArray.map(b => Bytes.fromArray(Array(b))).toVector)
    val (_, used) = right(streamed(ChainSync.responseDecoder(State.NextCanAwait, codec), chunks))
    assertEquals(used, 9L)
  }

  test("segmented offers and one-byte response chunks are stack safe") {
    val task = new java.util.concurrent.FutureTask[Unit](() => {
      val offers = Chain.fromSeq(Vector.fill(50000)(Point.Origin))
      val encoded = right(
        ChainSync.encodeChunked(
          State.Idle,
          Role.Client,
          Message.FindIntersect(offers),
          codec,
          limits
        )
      )
      assertEquals(encoded.size, 50004L)
      val body = right(ChunkedBytes.fromChunks(Chain.fromSeq(Vector.fill(50000)(hex("a5")))))
      val wire = right(
        ChainSync.encodeChunked(
          State.NextCanAwait,
          Role.Server,
          Message.RollForward(CardanoBlockFetch.localBlock(body), Tip.Origin),
          codec,
          limits
        )
      )
      val (_, used) =
        right(streamed(ChainSync.responseDecoder(State.NextCanAwait, codec), wire.chunks))
      assertEquals(used, wire.size)
    })
    val thread = new Thread(null, task, "chunked-chainsync-small-stack", 256 * 1024)
    thread.start()
    try task.get(30, java.util.concurrent.TimeUnit.SECONDS)
    finally
      if thread.isAlive then thread.interrupt()
      thread.join(30000)
      assert(!thread.isAlive, "small-stack decoder worker must finish")
  }
