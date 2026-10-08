// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import ChainSync.{Point, UInt64, DecodeResult}
import BlockFetch.*
import CardanoBlockFetch.*
import scala.concurrent.duration.*

class BlockFetchSuite extends munit.FunSuite:
  private def b(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)
  private def right[A](value: Either[String, A]): A = value.fold(fail(_), identity)
  private def failed[A](value: DecodeResult[A]): Unit = value match
    case DecodeResult.Failed(_) => ()
    case other                  => fail(s"expected Failed, got $other")
  private val codec = payloadCodec()
  private val toy = right(RawNtNBlock.from(b("80")))
  private val literals: Vector[(String, State, Role, Message[RawNtNBlock], State)] = Vector(
    (
      "83008080",
      State.Idle,
      Role.Client,
      Message.RequestRange(Point.Origin, Point.Origin),
      State.Busy
    ),
    ("8101", State.Idle, Role.Client, Message.ClientDone, State.Done),
    ("8102", State.Busy, Role.Server, Message.StartBatch, State.Streaming),
    ("8103", State.Busy, Role.Server, Message.NoBlocks, State.Idle),
    ("8204d8184180", State.Streaming, Role.Server, Message.Block(toy), State.Streaming),
    ("8105", State.Streaming, Role.Server, Message.BatchDone, State.Idle)
  )

  literals.foreach { case (hex, state, role, message, next) =>
    test(s"source-derived literal $hex, prefix cuts, suffix and exact decode") {
      val wire = b(hex)
      assertEquals(right(encode(state, role, message, codec)), wire)
      assertEquals(right(decode(state, role, wire, codec)), message)
      assertEquals(transition(state, role, message), Right(next))
      (0 until wire.size).foreach { cut =>
        val partial = Bytes(wire.value.take(cut))
        assertEquals(decodePrefix(state, role, partial, codec), DecodeResult.NeedMore)
        assert(decode(state, role, partial, codec).isLeft)
      }
      val suffixed = Bytes(wire.value ++ b("8105").value)
      assertEquals(
        decodePrefix(state, role, suffixed, codec),
        DecodeResult.Decoded(message, wire.size)
      )
      assert(decode(state, role, suffixed, codec).isLeft)
    }
  }

  test("all six messages across all four states and both senders: exactly six valid transitions") {
    for
      state <- State.values
      role <- Role.values
      (hex, goodState, goodRole, message, next) <- literals
    do
      val valid = state == goodState && role == goodRole
      assertEquals(transition(state, role, message).isRight, valid, s"$state/$role/$message")
      assertEquals(encode(state, role, message, codec).isRight, valid)
      if valid then
        assertEquals(
          decodePrefix(state, role, b(hex), codec),
          DecodeResult.Decoded(message, b(hex).size)
        )
      else failed(decodePrefix(state, role, b(hex), codec))
  }

  test("generic empty batch and sequential repeated batches are legal; no wire cancel") {
    val sequence: Vector[(Role, Message[RawNtNBlock])] = Vector(
      Role.Client -> Message.RequestRange(Point.Origin, Point.Origin),
      Role.Server -> Message.StartBatch,
      Role.Server -> Message.BatchDone,
      Role.Client -> Message.RequestRange(Point.Origin, Point.Origin),
      Role.Server -> Message.NoBlocks,
      Role.Client -> Message.ClientDone
    )
    val end = sequence.foldLeft(State.Idle) { case (state, (role, message)) =>
      right(transition(state, role, message))
    }
    assertEquals(end, State.Done)
    for state <- Vector(State.Busy, State.Streaming) do
      assert(transition(state, Role.Client, Message.ClientDone).isLeft)
      assert(
        transition(state, Role.Client, Message.RequestRange(Point.Origin, Point.Origin)).isLeft
      )
  }

  test("exact array arity, uint tag and known tags; oversized tag never narrows") {
    val malformed =
      Vector("8000", "8201", "83048080", "9f01ff", "8120", "8106", "811b0000000100000001", "81ff")
    malformed.foreach(hex => failed(decodePrefix(State.Idle, Role.Client, b(hex), codec)))
    failed(decodePrefix(State.Streaming, Role.Server, b("8104"), codec))
    failed(decodePrefix(State.Busy, Role.Server, b("8202"), codec))
    failed(decodePrefix(State.Idle, Role.Client, b("8300810080"), codec))
    failed(decodePrefix(State.Idle, Role.Client, b("83009fff80"), codec))
  }

  test("slot1/hash32-zero request is a source-derived literal and endpoints are inclusive") {
    val pointHex = "82015820" + "00" * 32
    val point = Point.Block(right(UInt64.from(1)), b("00" * 32))
    val specific = right(SpecificPoint.from(point))
    val range = InclusiveRange.single(specific)
    assertEquals(range.from, range.to)
    val wire = b("8300" + pointHex + pointHex)
    assertEquals(right(encode(State.Idle, Role.Client, range.request, codec)), wire)
    assertEquals(right(checkedRange(right(decode(State.Idle, Role.Client, wire, codec)))), range)
    assert(InclusiveRange.fromPoints(Point.Origin, point).isLeft)
    assert(InclusiveRange.fromPoints(point, Point.Origin).isLeft)
    for size <- Vector(0, 31, 33, 64) do
      assert(
        SpecificPoint.from(Point.Block(UInt64.Zero, Bytes(Vector.fill(size)(0.toByte)))).isLeft
      )
  }

  test("full uint64 slot range, same slot different hash, no false ordering inference") {
    for slot <- Vector(BigInt(0), BigInt(Long.MaxValue), BigInt(Long.MaxValue) + 1, UInt64.Max) do
      val point = Point.Block(right(UInt64.from(slot)), b("ff" * 32))
      val message: Message[RawNtNBlock] = Message.RequestRange(point, point)
      val wire = right(encode(State.Idle, Role.Client, message, codec))
      assertEquals(right(decode(State.Idle, Role.Client, wire, codec)), message)
    assert(UInt64.from(-1).isLeft)
    assert(UInt64.from(UInt64.Max + 1).isLeft)
    val first = Point.Block(UInt64.Zero, b("00" * 32))
    val second = Point.Block(UInt64.Zero, b("01" * 32))
    val range = right(InclusiveRange.fromPoints(first, second))
    assertNotEquals(range.from, range.to)
    // Reversed endpoints are wire-valid; only independently selected chain points can reject them.
    assert(InclusiveRange.fromPoints(second, first).isRight)
  }

  test("tag24 and definite byte string required; invalid prefix rejected before its body") {
    Vector("8204d8174180", "82044180", "8204d8188180", "8204d8185f4180ff", "8204d8187f60ff")
      .foreach(hex => failed(decodePrefix(State.Streaming, Role.Server, b(hex), codec)))
    failed(decodePrefix(State.Streaming, Role.Server, b("8204d817"), codec))
    failed(decodePrefix(State.Streaming, Role.Server, b("8204d8185f"), codec))
    failed(decodePrefix(State.Streaming, Role.Server, b("8204d8185bffffffffffffffff"), codec))
    val oneByte = payloadCodec(RawLimits(1))
    failed(decodePrefix(State.Streaming, Role.Server, b("8204d81842"), oneByte))
    assertEquals(
      decodePrefix(State.Streaming, Role.Server, b("8204d81841"), oneByte),
      DecodeResult.NeedMore
    )
    assert(payloadCodec(RawLimits(-1)).decode(b("d81840")).isLeft)
  }

  test("opaque raw bytes preserved exactly without validating or normalizing inner CBOR") {
    for raw <- Vector("", "1800", "00ff", "ff", "9f00ff", "3c43415244414e4f5f424c4f434b3e") do
      val block = right(RawNtNBlock.from(b(raw)))
      val encoded = right(codec.encode(block))
      assertEquals(right(codec.decode(encoded)).bytes, b(raw))
    val nonCanonicalEnvelope = b("8204d90018580180")
    val message = right(decode(State.Streaming, Role.Server, nonCanonicalEnvelope, codec))
    assertEquals(message, Message.Block(toy))
    assertEquals(right(encode(State.Streaming, Role.Server, message, codec)), b("8204d8184180"))
    assert(codec.decode(b("d818418000")).isLeft)
  }

  test("raw arrays copied at ownership boundary") {
    val array = Array(0x80.toByte)
    val raw = right(RawNtNBlock.from(Bytes.fromArray(array)))
    array(0) = 0
    assertEquals(raw.bytes, b("80"))
    val exported = raw.bytes.toArray
    exported(0) = 1
    assertEquals(raw.bytes, b("80"))
  }

  test("raw and whole-message limits at boundary and plus one") {
    val c = payloadCodec(RawLimits(1))
    assert(c.encode(toy).isRight)
    assert(c.encode(right(RawNtNBlock.from(b("8080")))).isLeft)
    val wire = b("8204d8184180")
    val exact = Limits(streamingMessageBytes = wire.size)
    assertEquals(
      right(decode(State.Streaming, Role.Server, wire, codec, exact)),
      Message.Block(toy)
    )
    assert(encode(State.Streaming, Role.Server, Message.Block(toy), codec, exact).isRight)
    val short = exact.copy(streamingMessageBytes = wire.size - 1)
    failed(decodePrefix(State.Streaming, Role.Server, wire, codec, short))
    assert(encode(State.Streaming, Role.Server, Message.Block(toy), codec, short).isLeft)
    failed(decodePrefix(State.Idle, Role.Client, b("8101"), codec, Limits(idleMessageBytes = 0)))
    failed(decodePrefix(State.Streaming, Role.Server, wire, codec, Limits(maxDepth = 0)))
    failed(decodePrefix(State.Streaming, Role.Server, wire, codec, Limits(maxItems = 1)))
  }

  test(
    "large block above ChainSync and SDU limits; targeted splits and full upstream message ceiling"
  ) {
    val bytes = Bytes(Vector.fill(2499991)(0x80.toByte))
    val block = right(RawNtNBlock.from(bytes))
    val wire = right(encode(State.Streaming, Role.Server, Message.Block(block), codec))
    assertEquals(wire.size, 2500000)
    assertEquals(right(decode(State.Streaming, Role.Server, wire, codec)), Message.Block(block))
    for cut <- Vector(2, 4, 8, 65535, 65536, 648000, 2499999) do
      assertEquals(
        decodePrefix(State.Streaming, Role.Server, Bytes(wire.value.take(cut)), codec),
        DecodeResult.NeedMore
      )
    assert(RawNtNBlock.from(Bytes(bytes.value :+ 0.toByte)).isLeft)
    assert(
      encode(
        State.Streaming,
        Role.Server,
        Message.Block(block),
        codec,
        Limits(streamingMessageBytes = 2499999)
      ).isLeft
    )
  }

  test("aggregate accounting is bounded and overflow-safe") {
    val limits = RequestLimits(maxBlocks = 2, maxRawBytes = 3)
    val one = right(Received.add(Received.Empty, 1, limits))
    val two = right(Received.add(one, 2, limits))
    assertEquals(two.blocks, 2)
    assertEquals(two.rawBytes, 3L)
    assert(Received.add(two, 0, limits).isLeft)
    assert(Received.add(one, 3, limits).isLeft)
    assert(Received.add(one, -1, limits).isLeft)
    assert(Received.add(one, 1, RequestLimits(0, 3)).isLeft)
    assert(Received.add(one, 1, RequestLimits(2, Long.MaxValue)).isRight)
  }

  test("independent time policy and mux metadata, no transport timer claim") {
    assertEquals(MiniProtocolId, 3)
    assertEquals(direction(Role.Client), Mux.Direction.Initiator)
    assertEquals(direction(Role.Server), Mux.Direction.Responder)
    assertEquals(TimeLimits().idle, None)
    assertEquals(TimeLimits().busy, 60.seconds)
    assertEquals(TimeLimits().streaming, 60.seconds)
    assert(TimeLimits().valid)
    assert(!TimeLimits(wholeRequest = 0.seconds).valid)
    assert(!TimeLimits(idle = Some((-1).seconds)).valid)
    assertEquals(Limits().messageBytes(State.Idle), 65535)
    assertEquals(Limits().messageBytes(State.Streaming), 2500000)
    assertEquals(Limits().messageBytes(State.Done), 0)
  }

  test("generic payload seam validates one bounded item independently of Cardano tag24") {
    val generic = new PayloadCodec[Bytes]:
      def encode(value: Bytes): Either[String, Bytes] = Right(value)
      def decode(value: Bytes): Either[String, Bytes] = Right(value)
    assertEquals(
      right(decode(State.Streaming, Role.Server, b("82041800"), generic)),
      Message.Block(b("1800"))
    )
    assertEquals(
      right(encode(State.Streaming, Role.Server, Message.Block(b("1800")), generic)),
      b("82041800")
    )
    assert(encode(State.Streaming, Role.Server, Message.Block(b("0000")), generic).isLeft)
    failed(decodePrefix(State.Streaming, Role.Server, b("82045bffffffffffffffff"), generic))
    failed(
      decodePrefix(State.Streaming, Role.Server, b("8204818100"), generic, Limits(maxDepth = 1))
    )
    failed(
      decodePrefix(State.Streaming, Role.Server, b("8204820000"), generic, Limits(maxItems = 3))
    )
  }
