// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import ChainSync.*

class ChainSyncSuite extends munit.FunSuite:
  private def b(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)
  private def right[A](value: Either[String, A]): A = value.fold(fail(_), identity)
  private val payload = ChainSyncFixtures.rawItem
  private def decoded[A](value: DecodeResult[A]): A = value match
    case DecodeResult.Decoded(v, _) => v
    case other                      => fail(s"expected decoded: $other")
  private def failed[A](value: DecodeResult[A]): Unit = value match
    case DecodeResult.Failed(_) => ()
    case other                  => fail(s"expected failed: $other")
  private val literals: Vector[(String, State, Role, Message[Bytes], State)] = Vector(
    ("8100", State.Idle, Role.Client, Message.RequestNext, State.NextCanAwait),
    ("8101", State.NextCanAwait, Role.Server, Message.AwaitReply, State.NextMustReply),
    (
      "83021800828000",
      State.NextMustReply,
      Role.Server,
      Message.RollForward(b("1800"), Tip.Origin),
      State.Idle
    ),
    (
      "830380828000",
      State.NextCanAwait,
      Role.Server,
      Message.RollBackward(Point.Origin, Tip.Origin),
      State.Idle
    ),
    ("820480", State.Idle, Role.Client, Message.FindIntersect(Vector.empty), State.Intersect),
    (
      "82049f80ff",
      State.Idle,
      Role.Client,
      Message.FindIntersect(Vector(Point.Origin)),
      State.Intersect
    ),
    (
      "830580828000",
      State.Intersect,
      Role.Server,
      Message.IntersectFound(Point.Origin, Tip.Origin),
      State.Idle
    ),
    ("8206828000", State.Intersect, Role.Server, Message.IntersectNotFound(Tip.Origin), State.Idle),
    ("8107", State.Idle, Role.Client, Message.Done, State.Done)
  )
  literals.foreach { case (hex, state, role, message, next) =>
    test(s"literal $hex: exact encoding, prefix boundaries, suffix, state") {
      val wire = b(hex)
      assertEquals(right(encode(state, role, message, payload)), wire)
      assertEquals(
        decodePrefix(state, role, wire, payload),
        DecodeResult.Decoded(message, wire.size)
      )
      assertEquals(right(transition(state, role, message)), next)
      (0 until wire.size).foreach { cut =>
        assertEquals(
          decodePrefix(state, role, Bytes(wire.value.take(cut)), payload),
          DecodeResult.NeedMore,
          s"cut=$cut"
        )
      }
      assertEquals(
        decodePrefix(state, role, Bytes(wire.value ++ Vector.fill(70000)(0.toByte)), payload),
        DecodeResult.Decoded(message, wire.size)
      )
    }
  }
  test("all message/state/agency combinations use independently specified transition matrix") {
    val messages = literals.filterNot(_._1 == "82049f80ff")
    val allowed = Map(
      0 -> Set(State.Idle),
      1 -> Set(State.NextCanAwait),
      2 -> Set(State.NextCanAwait, State.NextMustReply),
      3 -> Set(State.NextCanAwait, State.NextMustReply),
      4 -> Set(State.Idle),
      5 -> Set(State.Intersect),
      6 -> Set(State.Intersect),
      7 -> Set(State.Idle)
    )
    messages.zipWithIndex.foreach { case ((hex, _, expectedRole, message, _), tag) =>
      State.values.foreach { state =>
        Role.values.foreach { role =>
          val legal = role == expectedRole && allowed(tag)(state)
          assertEquals(transition(state, role, message).isRight, legal, s"tag=$tag $state $role")
          assertEquals(encode(state, role, message, payload).isRight, legal)
          val result = decodePrefix(state, role, b(hex), payload)
          if legal then assertEquals(decoded(result), message) else failed(result)
        }
      }
    }
  }
  test("all tags reject wrong outer arity and indefinite outer arrays") {
    literals.foreach { case (hex, state, role, _, _) =>
      val wire = b(hex)
      Vector(0x80, 0x84, 0x9f).foreach { first =>
        failed(decodePrefix(state, role, Bytes(first.toByte +: wire.value.tail), payload))
      }
    }
  }
  test("intersection encoder indefinite nonempty, decoder accepts definite and indefinite") {
    assertEquals(
      decoded(decodePrefix(State.Idle, Role.Client, b("82048180"), payload)),
      Message.FindIntersect(Vector(Point.Origin))
    )
    assertEquals(
      decoded(decodePrefix(State.Idle, Role.Client, b("82049fff"), payload)),
      Message.FindIntersect(Vector.empty)
    )
  }
  test("point/tip source manual literals and Origin tip normalization") {
    assertEquals(right(encodePoint(Point.Origin)).hex, "80")
    assertEquals(right(encodeTip(Tip.Origin)).hex, "828000")
    assertEquals(decoded(decodeTip(b("828001"))), Tip.Origin)
    assertEquals(decoded(decodeTip(b("82801bffffffffffffffff"))), Tip.Origin)
    assertEquals(right(encodeTip(decoded(decodeTip(b("828001"))))).hex, "828000")
    Vector("818000", "9f8000ff", "828020", "8280f6").foreach(h => failed(decodeTip(b(h))))
  }
  test("uint64 max and signed boundary preserve exact values without truncation") {
    Vector(BigInt(0), BigInt(Long.MaxValue), BigInt(Long.MaxValue) + 1, UInt64.Max).foreach { n =>
      val p = Point.Block(right(UInt64.from(n)), b("aabb"))
      assertEquals(decoded(decodePoint(right(encodePoint(p)))), p)
      val t = Tip(p, right(UInt64.from(n)))
      assertEquals(decoded(decodeTip(right(encodeTip(t)))), t)
    }
    assert(UInt64.from(-1).isLeft)
    assert(UInt64.from(UInt64.Max + 1).isLeft)
    failed(decodePoint(b("822040")))
  }
  test("nonminimal valid uint and payload encodings preserve opaque payload bytes") {
    assertEquals(decoded(decodePoint(b("82180040"))), Point.Block(UInt64.Zero, Bytes.empty))
    val message =
      decoded(decodePrefix(State.NextCanAwait, Role.Server, b("83021800828000"), payload))
    assertEquals(
      right(encode(State.NextCanAwait, Role.Server, message, payload)).hex,
      "83021800828000"
    )
  }
  test("malformed and overlong arguments fail; bounded truncated fields need more") {
    Vector(
      "82045bffffffffffffffff",
      "82049bffffffffffffffff",
      "820498ff",
      "83025bffffffffffffffff",
      "83021c",
      "8302ff",
      "8302f818",
      "83029fff82800000"
    ).take(7).foreach { h =>
      val state = if h.startsWith("8204") then State.Idle else State.NextCanAwait
      val role = if state == State.Idle then Role.Client else Role.Server
      failed(decodePrefix(state, role, b(h), payload))
    }
    assertEquals(
      decodePrefix(State.NextCanAwait, Role.Server, b("83025820"), payload),
      DecodeResult.NeedMore
    )
    failed(decodePrefix(State.NextCanAwait, Role.Server, b("83027802c328828000"), payload))
    failed(decodePrefix(State.NextCanAwait, Role.Server, b("8302bf00ff828000"), payload))
  }
  test("bounded generic item handles floats/simple values/indefinite chunks without normalizing") {
    Vector(
      "f90000",
      "fa7fc00000",
      "fb0000000000000000",
      "f820",
      "5f41004101ff",
      "7f61616162ff",
      "bf001800ff",
      "9ff6f7ff"
    ).foreach { hex =>
      val m = Message.RollForward(b(hex), Tip.Origin)
      val wire = right(encode(State.NextCanAwait, Role.Server, m, payload))
      assertEquals(decoded(decodePrefix(State.NextCanAwait, Role.Server, wire, payload)), m)
    }
  }
  test("declared lengths, candidates, nesting and item counts are bounded") {
    val small = Limits(maxMessageBytes = 20, maxStringBytes = 8, maxCandidates = 1)
    failed(decodePrefix(State.Idle, Role.Client, b("820482"), payload, small))
    failed(decodePrefix(State.Idle, Role.Client, b("82049f8080ff"), payload, small))
    failed(decodePrefix(State.NextCanAwait, Role.Server, b("83025809"), payload, small))
    assert(
      encode(
        State.Idle,
        Role.Client,
        Message.FindIntersect(Vector.fill(2)(Point.Origin)),
        payload,
        small
      ).isLeft
    )
    assert(validateItem(b("818180"), Limits(maxDepth = 1)).isLeft)
    assert(validateItem(b("820000"), Limits(maxItems = 2)).isLeft)
    assert(validateItem(b("5f44000000004400000000ff"), Limits(maxStringBytes = 7)).isLeft)
    failed(
      decodePrefix(
        State.NextCanAwait,
        Role.Server,
        b("83025820"),
        payload,
        Limits(maxMessageBytes = 20)
      )
    )
  }
  test("standalone point and tip honor full structural bounds") {
    val p = Point.Block(UInt64.Zero, b("00"))
    val small = Limits(maxDepth = 0, maxItems = 1)
    assert(encodePoint(p, small).isLeft)
    assert(encodeTip(Tip.Origin, small).isLeft)
    failed(decodePoint(b("82004100"), small))
    failed(decodeTip(b("828000"), small))
  }
  test("invalid local limits and exact byte caps fail without throwing") {
    Vector(
      Limits(maxMessageBytes = 0),
      Limits(maxDepth = 65),
      Limits(maxItems = 0),
      Limits(maxCandidates = -1)
    ).foreach { lim =>
      failed(decodePrefix(State.Idle, Role.Client, b("8100"), payload, lim))
      assert(encode(State.Idle, Role.Client, Message.RequestNext, payload, lim).isLeft)
    }
    assertEquals(
      decoded(
        decodePrefix(State.Idle, Role.Client, b("8100"), payload, Limits(maxMessageBytes = 2))
      ),
      Message.RequestNext
    )
    failed(decodePrefix(State.Idle, Role.Client, b("81"), payload, Limits(maxMessageBytes = 1)))
  }
  test("opaque adapters distinguish NtN header and NtC block; placeholders stay unvalidated") {
    val header = b("8206d818483c4845414445523e")
    val block = b("d8184f3c43415244414e4f5f424c4f434b3e")
    assertEquals(right(ChainSyncFixtures.ntnHeader.decode(header)).bytes, header)
    assertEquals(right(ChainSyncFixtures.ntcBlock.decode(block)).bytes, block)
    assert(ChainSyncFixtures.ntnHeader.decode(block).isLeft)
    assert(ChainSyncFixtures.ntcBlock.decode(header).isLeft)
    Vector(
      "8205d8184100",
      "8206d8174100",
      "8206d81800",
      "8306d818410000",
      "9f06d8184101ff",
      "8206d8185f4101ff"
    ).foreach(h => assert(ChainSyncFixtures.ntnHeader.decode(b(h)).isLeft))
    Vector("d8174100", "d81800", "d818410000", "d8185f4101ff").foreach(h =>
      assert(ChainSyncFixtures.ntcBlock.decode(b(h)).isLeft)
    )
    assert(ChainSyncFixtures.cardanoPoint(Point.Block(UInt64.Zero, b("00"))).isLeft)
    assertEquals(decoded(decodePoint(b("82004100"))), Point.Block(UInt64.Zero, b("00")))
  }
  test("exact 65535-byte message succeeds; one extra byte is rejected") {
    val raw = Bytes(Vector(0x59.toByte, 0xff.toByte, 0xf7.toByte) ++ Vector.fill(65527)(0.toByte))
    val message = Message.RollForward(raw, Tip.Origin)
    val wire = right(encode(State.NextCanAwait, Role.Server, message, payload))
    assertEquals(wire.size, 65535)
    assertEquals(decoded(decodePrefix(State.NextCanAwait, Role.Server, wire, payload)), message)
    val tooLarge =
      Bytes(Vector(0x59.toByte, 0xff.toByte, 0xf8.toByte) ++ Vector.fill(65528)(0.toByte))
    assert(
      encode(
        State.NextCanAwait,
        Role.Server,
        Message.RollForward(tooLarge, Tip.Origin),
        payload
      ).isLeft
    )
    failed(
      decodePrefix(
        State.NextCanAwait,
        Role.Server,
        Bytes(
          Vector(0x83.toByte, 2.toByte) ++ tooLarge.value ++ Vector(
            0x82.toByte,
            0x80.toByte,
            0.toByte
          )
        ),
        payload
      )
    )
  }
  test("independent literal script drives wire decoder and semantic follower through Done") {
    val script = Vector(
      Role.Client -> "82049f80ff",
      Role.Server -> "830580828000",
      Role.Client -> "8100",
      Role.Server -> "8101",
      Role.Server -> "830380828000",
      Role.Client -> "8107"
    )
    var model = right(FixtureChainModel.start())
    script.foreach { case (sender, hex) =>
      val message = decoded(decodePrefix(model.state, sender, b(hex), payload))
      model = right(model.step(sender, message))
    }
    assertEquals(model.state, State.Done)
    assertEquals(model.cursor, Point.Origin)
    assertEquals(
      model.trace,
      Vector(
        "find-intersect",
        "intersection-found",
        "request-next",
        "await-reply",
        "roll-backward",
        "done"
      )
    )
  }
