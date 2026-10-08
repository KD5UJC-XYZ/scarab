// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import Handshake.*

class HandshakeSuite extends munit.FunSuite:
  private val ntn = Suite.NodeToNode
  private val ntc = Suite.NodeToClient
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)
  private def right[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def read(hex: String, suite: Suite = ntn, state: State = State.Confirm): Message =
    decode(suite, state, bytes(hex)).fold(e => fail(e.message), identity)
  private def data(v: Int, d: Data = Data(2), suite: Suite = ntn): Term = right(
    dataTerm(suite, v, d)
  )
  private def encoded(m: Message, suite: Suite = ntn): String = right(encode(suite, m)).hex
  private def client(offers: Vector[(Int, Data)], suite: Suite = ntn): Client = right(
    clientStart(suite, offers)
  )._1
  private def result(c: Client, m: Message): Result = right(c.receive(m))._2
  private val fixtures = Vector(
    (ntn, "8200a10e8402f500f4", Message.Propose(Vector(14 -> data(14)))),
    (ntn, "830110851a2d964a09f500f4f4", Message.Accept(16, data(16, Data(764824073)))),
    (ntn, "82028200820e10", Message.Refuse(Reason.VersionMismatch(Vector(14, 16)))),
    (ntn, "83010e8402f500f4", Message.Accept(14, data(14))),
    (ntc, "8200a11980108202f4", Message.Propose(Vector(16 -> data(16, suite = ntc)))),
    (ntc, "8301198010821a2d964a09f4", Message.Accept(16, data(16, Data(764824073), ntc))),
    (
      ntc,
      "8200a11980178202f5",
      Message.Propose(Vector(23 -> data(23, Data(2, query = true), ntc)))
    ),
    (ntc, "8203a11980178202f4", Message.QueryReply(Vector(23 -> data(23, suite = ntc))))
  )

  test(
    "literal upstream-manual/source-derived fixtures encode and decode (not runtime interoperability)"
  ) {
    fixtures.foreach { case (suite, hex, message) =>
      assertEquals(encoded(message, suite), hex)
      assertEquals(read(hex, suite), message)
    }
  }
  test("every proper split is incomplete; full fixture preserves coalesced suffix") {
    fixtures.foreach { case (suite, hex, message) =>
      val full = bytes(hex)
      (0 until full.size).foreach { cut =>
        val e = decodePrefix(suite, State.Confirm, Bytes(full.value.take(cut))).swap.toOption.get
        assert(e.incomplete, s"$hex at $cut: $e")
      }
      assertEquals(
        decodePrefix(suite, State.Confirm, Bytes(full.value ++ full.value)),
        Right((message, full))
      )
      assert(decode(suite, State.Confirm, Bytes(full.value ++ full.value)).isLeft)
    }
  }
  test("valid nonminimal integer and container widths remain accepted") {
    assertEquals(read("98031801180e841802f51800f4"), Message.Accept(14, data(14)))
  }
  test("version numbers are exact; NtC bit is present on mainnet and testnet") {
    Vector(2L, 764824073L).foreach { magic =>
      (16 to 23).foreach { v =>
        assertEquals(wireVersion(ntc, v), Right(v | 0x8000))
        val m = Message.Accept(v, data(v, Data(magic), ntc))
        assertEquals(read(encoded(m, ntc), ntc), m)
      }
    }
    assert(wireVersion(ntn, 17).isLeft)
    assert(wireVersion(ntc, 24).isLeft)
    assert(decode(ntc, State.Confirm, bytes("8301108202f4")).isLeft)
    assert(decode(ntn, State.Confirm, bytes("8301118502f500f4f4")).isLeft)
    assertEquals(defaultNodeToNode(2), Vector(14 -> Data(2)))
  }
  test("uint32 magic extremes and bounds") {
    Vector(0L, 0xffffffffL).foreach { magic =>
      Vector(ntn -> 14, ntn -> 15, ntn -> 16, ntc -> 16, ntc -> 23).foreach { case (s, v) =>
        assertEquals(decodeData(s, v, data(v, Data(magic), s)), Right(Data(magic)))
      }
    }
    assert(dataTerm(ntn, 14, Data(-1)).isLeft)
    assert(dataTerm(ntn, 14, Data(0x100000000L)).isLeft)
    assert(
      decodeData(
        ntn,
        14,
        Term.Arr(Vector(Term.Integer(-1), Term.Bool(true), Term.Integer(0), Term.Bool(false)))
      ).isLeft
    )
    assert(
      decodeData(ntc, 16, Term.Arr(Vector(Term.Integer(BigInt(1) << 32), Term.Bool(false)))).isLeft
    )
  }
  test("selected data exact arity, definite list and typed flags") {
    assert(decodeData(ntn, 14, data(16)).isLeft)
    assert(decodeData(ntn, 16, data(14)).isLeft)
    assert(dataTerm(ntn, 15, Data(2, peras = true)).isLeft)
    assert(dataTerm(ntc, 16, Data(2, peerSharing = true)).isLeft)
    val invalid = Vector("83010e8402f502f4", "83010e84020000f4", "83010e9f02f500f4ff")
    invalid.foreach(hex =>
      read(hex) match
        case Message.Accept(v, d) => assert(decodeData(ntn, v, d).isLeft)
        case _                    => fail("expected accept")
    )
  }
  test("unknown map keys skipped including arbitrary generic CBOR and unordered unknown keys") {
    val expected = Message.Propose(Vector(14 -> data(14), 15 -> data(15)))
    // Future 99 has indefinite nested term; key 1 has float16; string key has null.
    val hex = "8200a50e8402f500f418639f01a101f6ff01f93c006178f60f8402f500f4"
    assertEquals(read(hex), expected)
    assertEquals(read("8200a21863f601f6"), Message.Propose(Vector.empty))
  }
  test("recognized ordering enforced even across unknown entries; duplicates rejected") {
    Vector(
      "8200a20f8402f500f40e8402f500f4",
      "8200a20e8402f500f40e8402f500f4",
      "8200a30e8402f500f41863f60e8402f500f4"
    ).foreach { hex =>
      assert(decode(ntn, State.Propose, bytes(hex)).isLeft)
    }
    assert(encode(ntn, Message.Propose(Vector(14 -> data(14), 14 -> data(14)))).isLeft)
    assertEquals(
      encoded(Message.Propose(Vector(15 -> data(15), 14 -> data(14)))),
      "8200a20e8402f500f40f8402f500f4"
    )
  }
  test("definite top arrays/maps, tags, arities and state-specific messages") {
    Vector(
      "9f00a0ff",
      "8200bfff",
      "8100",
      "8204a0",
      "8300a000",
      "82028100",
      "820283038000",
      "8202830118186178"
    ).foreach { hex => assert(decode(ntn, State.Confirm, bytes(hex)).isLeft) }
    assert(decode(ntn, State.Propose, bytes("83010e8402f500f4")).isLeft)
    assert(decode(ntn, State.Propose, bytes("8203a0")).isLeft)
    assert(decode(ntn, State.Done, bytes("8200a0")).isLeft)
  }
  test("refusals preserve unknown integer versions; typed errors and bounded text") {
    assertEquals(
      read("82028200830e18636178"),
      Message.Refuse(Reason.VersionMismatch(Vector(14), Vector(BigInt(99))))
    )
    Vector(Reason.DecodeError(14, "bad"), Reason.Refused(16, "wrong network")).foreach { reason =>
      val m = Message.Refuse(reason)
      assertEquals(read(encoded(m)), m)
    }
    assert(encode(ntn, Message.Refuse(Reason.Refused(14, "x" * (MaxRefusalBytes + 1)))).isLeft)
    assert(decode(ntn, State.Confirm, bytes("820283010e7f6161ff")).isLeft)
  }
  test("selected malformed version produces refusal without fallback") {
    val offers = Vector(14 -> Data(2), 16 -> Data(2))
    val malformed = Message.Propose(Vector(14 -> data(14), 16 -> Term.Bool(false)))
    val (_, r) = right(responder(ntn, offers, malformed))
    r match
      case Result.Rejected(Reason.DecodeError(16, _)) => ()
      case _                                          => fail(s"unexpected $r")
    val incompatible = Message.Propose(Vector(14 -> data(14), 16 -> data(16, Data(1))))
    right(responder(ntn, offers, incompatible))._2 match
      case Result.Rejected(Reason.Refused(16, _)) => ()
      case r                                      => fail(s"unexpected $r")
    assertEquals(
      right(responder(ntn, offers, Message.Propose(Vector(15 -> data(15)))))._2,
      Result.Rejected(Reason.VersionMismatch(Vector(14, 16)))
    )
  }
  test("highest common selection and feature agreement follow pinned Acceptable instance") {
    val local = Data(2, initiatorOnly = false, peerSharing = true, peras = true)
    val remote = Data(2, initiatorOnly = true, peerSharing = false, peras = false)
    val (_, r) = right(
      responder(
        ntn,
        Vector(14 -> Data(2), 16 -> local),
        Message.Propose(Vector(14 -> data(14), 16 -> data(16, remote)))
      )
    )
    assertEquals(r, Result.Negotiated(16, remote))
    assertEquals(
      result(client(Vector(16 -> remote)), Message.Accept(16, data(16, local))),
      Result.Negotiated(16, remote)
    )
  }
  test("client validates offered membership, magic and shape; terminal state rejects repeats") {
    val c = client(Vector(14 -> Data(2)))
    assert(c.receive(Message.Accept(16, data(16))).isLeft)
    assert(c.receive(Message.Accept(14, data(14, Data(764824073)))).isLeft)
    assert(c.receive(Message.Accept(14, Term.Bool(false))).isLeft)
    val (done, r) = right(c.receive(Message.Accept(14, data(14))))
    assertEquals(done.state, State.Done)
    assertEquals(r, Result.Negotiated(14, Data(2)))
    assert(done.receive(Message.Accept(14, data(14))).isLeft)
    assert(responder(ntn, Vector(14 -> Data(2)), Message.Accept(14, data(14))).isLeft)
  }
  test("query is a distinct terminal result with per-version decode errors") {
    val offered = Vector(23 -> Data(2, query = true))
    val (_, p) = right(clientStart(ntc, offered))
    val (reply, serverResult) = right(responder(ntc, Vector(23 -> Data(2)), p))
    assertEquals(reply, Message.QueryReply(Vector(23 -> data(23, suite = ntc))))
    assertEquals(serverResult, Result.QueryResult(Vector(23 -> Right(Data(2, query = true)))))
    assertEquals(
      result(client(offered, ntc), reply),
      Result.QueryResult(Vector(23 -> Right(Data(2))))
    )
    result(client(offered, ntc), Message.QueryReply(Vector(23 -> Term.Bool(false)))) match
      case Result.QueryResult(Vector((23, Left(_)))) => ()
      case r                                         => fail(s"unexpected $r")
  }
  test(
    "simultaneous open selects greatest common and matches source negotiation result even with query flag"
  ) {
    val c = client(Vector(14 -> Data(2), 15 -> Data(2, query = true)))
    val p = Message.Propose(Vector(14 -> data(14), 15 -> data(15)))
    assertEquals(result(c, p), Result.Negotiated(15, Data(2, query = true)))
  }
  test(
    "bounded generic parser rejects resource attacks and invalid UTF8 without incomplete retry"
  ) {
    val attacks = Vector(
      "8200a1186361ff",
      "8200a118639bffffffffffffffff",
      "8200a118635a00010000",
      "8200a11863" + "81" * 26 + "00",
      "8200a11863f818",
      "8200a11863ff",
      "8200a118631c"
    )
    attacks.foreach { hex =>
      val e = decodePrefix(ntn, State.Propose, bytes(hex)).swap.toOption.get
      assert(!e.incomplete, s"$hex: $e")
    }
    val tooMany = Message.Propose(Vector(14 -> Term.Arr(Vector.fill(MaxItems)(Term.Bool(false)))))
    assert(encode(ntn, tooMany).isLeft)
    val largeString = Term.ByteString(Bytes(Vector.fill(3000)(0.toByte)))
    val oversized = Message.Propose(Vector(14 -> Term.Arr(Vector.fill(2)(largeString))))
    assert(encode(ntn, oversized).isLeft)
    val oversizedWire = bytes("8200a10e82590bb8" + "00" * 3000 + "590bb8" + "00" * 3000)
    val capError = decodePrefix(ntn, State.Propose, oversizedWire).swap.toOption.get
    assert(!capError.incomplete)
    assert(capError.message.contains("byte limit"))
  }
  test("float/simple/tag/bytes in known generic data retain raw syntax until selected validation") {
    Vector(
      "f93c00",
      "fa3f800000",
      "fb3ff0000000000000",
      "f6",
      "f7",
      "f820",
      "c14100",
      "5f41004101ff"
    )
      .foreach { term =>
        read("8200a10e" + term) match
          case Message.Propose(Vector((14, t))) => assert(decodeData(ntn, 14, t).isLeft)
          case m                                => fail(s"unexpected $m")
      }
  }
