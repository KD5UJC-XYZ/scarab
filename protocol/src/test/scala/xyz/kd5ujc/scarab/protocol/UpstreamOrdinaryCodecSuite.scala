// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

import ChainSync.DecodeResult

/** Original ordinary codecs use inline mock Block/BlockHeader values, unlike codecWrapped's tag-24
  * payloads. Structural CBOR callbacks preserve that domain; production engines supply framing,
  * agency and prefix parsing. This does not compare Cardano payload semantics or Haskell
  * interpreters.
  */
class UpstreamOrdinaryCodecSuite extends munit.FunSuite:
  private final case class Sample(
      seed: Int,
      state: String,
      sender: String,
      constructor: String,
      wire: Bytes,
      fields: Vector[Node]
  )

  private def right[E, A](result: Either[E, A]): A = result.fold(e => fail(e.toString), identity)
  private def bytes(hex: String): Bytes = right(Bytes.fromHex(hex))
  private def node(item: Bytes): Node = right(Cbor.decode(item))

  private def samples(family: String, expectedSha: String, seed: Int): Vector[Sample] =
    val name = s"$family-ordinary.tsv"
    val stream = Option(
      getClass.getResourceAsStream(s"/upstream/ouroboros-network-protocol-codecs/$name")
    ).getOrElse(fail(s"missing $name"))
    val raw =
      try stream.readAllBytes()
      finally stream.close()
    val digest =
      MessageDigest.getInstance("SHA-256").digest(raw).map(b => f"${b & 255}%02x").mkString
    assertEquals(digest, expectedSha)
    val lines = new String(raw, StandardCharsets.UTF_8).linesIterator.toVector
    assertEquals(lines.size, 64)
    lines.zipWithIndex.map { (line, ordinal) =>
      val columns = line.split("\t", -1).toVector
      assertEquals(columns.size, 15)
      assertEquals(
        columns.take(4),
        Vector(
          s"$family-ordinary-codec-v1",
          s"${seed + ordinal}",
          s"$ordinal",
          s"${8 + ordinal % 9}"
        )
      )
      assertEquals(
        columns.drop(10),
        Vector.fill(5)("pass"),
        s"original five property verdicts at ${seed + ordinal}"
      )
      assert(columns(9).nonEmpty, "retained original Haskell value rendering")
      val fields =
        if columns(8).isEmpty then Vector.empty
        else columns(8).split(",").toVector.map(h => node(bytes(h)))
      Sample(seed + ordinal, columns(4), columns(5), columns(6), bytes(columns(7)), fields)
    }

  private def decodeItem(item: Bytes): Either[ProtocolError, Node] =
    Cbor.decode(item).left.map(ProtocolError.Codec.apply)

  private def encodeItem(value: Node): Either[ProtocolError, Bytes] =
    Cbor.encode(value.value).left.map(ProtocolError.Codec.apply)

  private val point = ChainSyncEngine.pointCodec[Node](encodeItem, decodeItem)
  private val tip = ChainSyncEngine.tipCodec[Node](encodeItem, decodeItem)
  private val header = ChainSyncEngine.payloadCodec[Node](encodeItem, decodeItem)
  private val fetchPoint = BlockFetchEngine.pointCodec[Node](encodeItem, decodeItem)
  private val block = new BlockFetchEngine.PayloadCodec[Node]:
    def encode(value: Node): Either[ProtocolError, Bytes] = encodeItem(value)
    def decode(item: Bytes): Either[ProtocolError, Node] = decodeItem(item)

  /** Exhaustive ordered partitions, including empty fragments and early completion before trailing
    * empty fragments. Accumulation is the caller's responsibility in the stateless prefix API.
    */
  private def partitions[A](
      sample: Sample,
      expected: A,
      decode: Bytes => DecodeResult[A]
  ): (Int, Int) =
    val raw = sample.wire.value.toVector
    val complete = DecodeResult.Decoded(expected, raw.size)
    def verify(parts: Vector[Vector[Byte]]): Unit =
      parts.scanLeft(Vector.empty[Byte])(_ ++ _).tail.foreach { prefix =>
        val result = decode(Bytes(prefix))
        if prefix.size < raw.size then
          assertEquals(result, DecodeResult.NeedMore, s"seed ${sample.seed}")
        else assertEquals(result, complete, s"seed ${sample.seed}")
      }
    (0 to raw.size).foreach { cut => verify(Vector(raw.take(cut), raw.drop(cut))) }
    val three = for
      first <- 0 to raw.size
      second <- first to raw.size
    yield
      verify(Vector(raw.take(first), raw.slice(first, second), raw.drop(second)))
      1
    assertEquals(
      decode(Bytes(raw :+ 0.toByte)),
      complete,
      "one-message consumed length preserves suffix"
    )
    assertNotEquals(decode(Bytes(raw.dropRight(1))), complete, "truncated wire negative control")
    assertNotEquals(decode(Bytes(raw.reverse)), complete, "reordered wire negative control")
    (raw.size + 1, three.sum)

  private def array(value: Node, size: Int): Vector[Node] = value.value match
    case Value.Arr(items) =>
      assertEquals(items.size, size)
      items
    case other => fail(s"expected mock array, got $other")

  private def integer(value: Node): Unit = value.value match
    case Value.UInt(_) | Value.NInt(_) => ()
    case other                         => fail(s"expected original integer hash, got $other")

  private def mockHeader(value: Node): Unit =
    val fields = array(value, 5)
    integer(fields(0))
    fields(1).value match
      case Value.Arr(previous) =>
        assert(previous.size <= 1)
        previous.foreach(integer)
      case other => fail(s"expected previous chain hash, got $other")
    fields.drop(2).foreach(integer)

  test(
    "ordinary BlockFetch original fields, all split partitions and valid CBOR use production framing"
  ) {
    val input = samples(
      "blockfetch",
      "2e2ad018631d5845605b85a4e2c38c215e6fb10ab25002d0043c222c6331b942",
      509101
    )
    val counts = input.map { sample =>
      import BlockFetchEngine.{Message, Role, State}
      val state = sample.state match
        case "idle"      => State.Idle
        case "busy"      => State.Busy
        case "streaming" => State.Streaming
        case other       => fail(s"unexpected BlockFetch state $other")
      val sender = sample.sender match
        case "client" => Role.Client
        case "server" => Role.Server
        case other    => fail(s"unexpected sender $other")
      val expected: Message[Node, Node] = sample.constructor match
        case "request-range" =>
          assertEquals(sample.fields.size, 2)
          Message.RequestRange(sample.fields(0), sample.fields(1))
        case "client-done" => Message.ClientDone
        case "start-batch" => Message.StartBatch
        case "no-blocks"   => Message.NoBlocks
        case "batch-done"  => Message.BatchDone
        case "block" =>
          assertEquals(sample.fields.size, 1)
          val fields = array(sample.fields(0), 2)
          mockHeader(fields(0))
          assert(fields(1).value.isInstanceOf[Value.ByteString])
          Message.Block(sample.fields(0))
        case other => fail(s"unexpected constructor $other")
      val decode =
        (wire: Bytes) => BlockFetchEngine.decodePrefix(state, sender, wire, fetchPoint, block)
      assertEquals(
        BlockFetchEngine.decode(state, sender, sample.wire, fetchPoint, block),
        Right(expected)
      )
      assertEquals(
        BlockFetchEngine.encode(state, sender, expected, fetchPoint, block),
        Right(sample.wire)
      )
      assertEquals(right(Cbor.decode(sample.wire)).original, sample.wire)
      val wrongSender = if sender == Role.Client then Role.Server else Role.Client
      assert(BlockFetchEngine.decode(state, wrongSender, sample.wire, fetchPoint, block).isLeft)
      assert(BlockFetchEngine.decode(State.Done, sender, sample.wire, fetchPoint, block).isLeft)
      assert(
        BlockFetchEngine.decode(state, sender, sample.wire ++ bytes("00"), fetchPoint, block).isLeft
      )
      partitions(sample, expected, decode)
    }
    assertEquals(
      input.map(_.constructor).toSet,
      Set("request-range", "client-done", "start-batch", "no-blocks", "block", "batch-done")
    )
    assertEquals(counts.map(_._1).sum, input.map(_.wire.size + 1).sum)
    assertEquals(
      counts.map(_._2).sum,
      input.map(s => (s.wire.size + 1) * (s.wire.size + 2) / 2).sum
    )
  }

  test(
    "ordinary ChainSync original fields, all split partitions and valid CBOR use production framing"
  ) {
    val input = samples(
      "chainsync",
      "fd13afcf88bc065ef1126c66562838f4f7e1237189dd750c5af7c982c000e2ca",
      510101
    )
    val counts = input.map { sample =>
      import ChainSync.{Role, State}
      import ChainSyncEngine.Message
      val state = sample.state match
        case "idle"            => State.Idle
        case "next-can-await"  => State.NextCanAwait
        case "next-must-reply" => State.NextMustReply
        case "intersect"       => State.Intersect
        case other             => fail(s"unexpected ChainSync state $other")
      val sender = sample.sender match
        case "client" => Role.Client
        case "server" => Role.Server
        case other    => fail(s"unexpected sender $other")
      val expected: Message[Node, Node, Node] = sample.constructor match
        case "request-next" => Message.RequestNext
        case "await-reply"  => Message.AwaitReply
        case "roll-forward" =>
          assertEquals(sample.fields.size, 2)
          mockHeader(sample.fields(0))
          Message.RollForward(sample.fields(0), sample.fields(1))
        case "roll-backward"       => Message.RollBackward(sample.fields(0), sample.fields(1))
        case "find-intersect"      => Message.FindIntersect(sample.fields)
        case "intersect-found"     => Message.IntersectFound(sample.fields(0), sample.fields(1))
        case "intersect-not-found" => Message.IntersectNotFound(sample.fields(0))
        case "done"                => Message.Done
        case other                 => fail(s"unexpected constructor $other")
      val decode =
        (wire: Bytes) => ChainSyncEngine.decodePrefix(state, sender, wire, point, tip, header)
      assertEquals(decode(sample.wire), DecodeResult.Decoded(expected, sample.wire.size))
      assertEquals(
        ChainSyncEngine.encode(state, sender, expected, point, tip, header),
        Right(sample.wire)
      )
      assertEquals(right(Cbor.decode(sample.wire)).original, sample.wire)
      val wrongSender = if sender == Role.Client then Role.Server else Role.Client
      assert(
        ChainSyncEngine
          .decodePrefix(state, wrongSender, sample.wire, point, tip, header)
          .isInstanceOf[DecodeResult.Failed[?]]
      )
      assert(
        ChainSyncEngine
          .decodePrefix(State.Done, sender, sample.wire, point, tip, header)
          .isInstanceOf[DecodeResult.Failed[?]]
      )
      partitions(sample, expected, decode)
    }
    assertEquals(
      input.map(_.constructor).toSet,
      Set(
        "request-next",
        "await-reply",
        "roll-forward",
        "roll-backward",
        "find-intersect",
        "intersect-found",
        "intersect-not-found",
        "done"
      )
    )
    assertEquals(counts.map(_._1).sum, input.map(_.wire.size + 1).sum)
    assertEquals(
      counts.map(_._2).sum,
      input.map(s => (s.wire.size + 1) * (s.wire.size + 2) / 2).sum
    )
  }
