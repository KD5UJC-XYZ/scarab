// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.{Bytes, Tree}

import Handshake.*

class HandshakeSuite extends munit.FunSuite:
  private val ntn = Suite.NodeToNode
  private val ntc = Suite.NodeToClient
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private def right[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
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
    // the default is the latest release, 14 and 15; 16 is experimental (docs/running/configuration.md)
    assertEquals(defaultNodeToNode(2), Vector(14 -> Data(2), 15 -> Data(2)))
    assertEquals(
      nodeToNodeOffers(NtnVersion.V16, Data(2)).map(_._1),
      Vector(14, 15, 16)
    )
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
    assertEquals(
      decode(ntn, State.Confirm, bytes("820283010e7f6161ff")),
      Left(DecodeError(ProtocolError.InvalidOrOversizedRefusalText))
    )
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
    // a declared length the message cannot hold is refused at once, not waited for
    val attacks = Vector(
      "8200a1186361ff" -> ProtocolError.InvalidUTF8,
      "8200a118639bffffffffffffffff" -> ProtocolError.HandshakeByteLimitExceeded,
      "8200a118635a00010000" -> ProtocolError.HandshakeByteLimitExceeded,
      "8200a11863ff" -> ProtocolError.UnexpectedBreak,
      "8200a118631c" -> ProtocolError.ReservedCBORArgument
    )
    attacks.foreach { (hex, error) =>
      assertEquals(decodePrefix(ntn, State.Propose, bytes(hex)), Left(DecodeError(error)), hex)
    }
    val largeString = Term.ByteString(Bytes(Vector.fill(3000)(0.toByte)))
    val oversized = Message.Propose(Vector(14 -> Term.Arr(Vector.fill(2)(largeString))))
    // written as the reference writes it, with no bound, and refused when read
    val oversizedWire = bytes("8200a10e82590bb8" + "00" * 3000 + "590bb8" + "00" * 3000)
    assertEquals(encode(ntn, oversized), Right(oversizedWire))
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

  /** Run `body` on a thread whose stack (256 KiB) a recursion over a term thousands of levels deep
    * overflows.
    */
  private def onSmallStack[A](body: => A): A =
    val outcome = new java.util.concurrent.CompletableFuture[A]()
    val thread = new Thread(
      null,
      () =>
        try outcome.complete(body)
        catch case cause: Throwable => outcome.completeExceptionally(cause)
        ()
      ,
      "handshake-small-stack",
      256 * 1024
    )
    thread.start()
    thread.join()
    outcome.join()

  private def hexOf(n: Int, width: Int): String = s"%0${width * 2}x".format(n)

  /** A refusal of `version` (Refused, tag 2) whose text is `n` bytes of `a`: 8 + n bytes. */
  private def refusedText(n: Int): String =
    "8202" + "83" + "02" + "0e" + "79" + hexOf(n, 2) + "61" * n

  // decodeRefuseReason (Codec.hs:264-288, ouroboros-network c45735a5) reads the reason's length and
  // ignores it (268): it reads the tag, then the list of versions (0) or a version and a text (1,
  // 2), whatever the length says.
  test("a refusal's declared length is read and ignored, as decodeRefuseReason reads it (#1029)") {
    val bad = Message.Refuse(Reason.DecodeError(14, "bad"))
    Vector("85", "81", "80", "9b7fffffffffffffff").foreach { header =>
      val wire = bytes("8202" + header + "010e63626164")
      assertEquals(decodePrefix(ntn, State.Confirm, wire), Right((bad, Bytes.empty)), header)
    }
    assertEquals(
      read("8202" + "80" + "02" + "10" + "6178"),
      Message.Refuse(Reason.Refused(16, "x"))
    )
    assertEquals(
      read("8202" + "83" + "00" + "820e10"),
      Message.Refuse(Reason.VersionMismatch(Vector(14, 16)))
    )
    // the list of versions is read past a reason declared of one item: the pinned codec waits for it
    assertEquals(
      decodePrefix(ntn, State.Confirm, bytes("82028100")),
      Left(DecodeError(ProtocolError.IncompleteCbor, incomplete = true))
    )
    // a length cborg's decodeListLen cannot hold in an Int is refused
    val huge = decodePrefix(ntn, State.Confirm, bytes("8202" + "9b8000000000000000" + "010e6178"))
    assert(huge.isLeft && !huge.swap.toOption.exists(_.incomplete), huge)
    // only a refusal is read so: in StPropose the message is refused as before
    assert(decode(ntn, State.Propose, bytes("8202" + "85" + "010e63626164")).isLeft)
  }

  // The reference bounds a handshake message at maxTransmissionUnit, 5760 bytes (Codec.hs:54-68),
  // and its decodeTerm and decodeString at nothing else (Codec.hs:152-195, 217-243, 264-288,
  // ouroboros-network c45735a5).
  test("a message the pinned codec decodes within 5760 bytes is decoded: no local caps (#1029)") {
    // a version map of three hundred unknown versions (600 terms) and one known
    val unknown = (0 until 300).map(i => "19" + hexOf(0x4000 + i, 2) + "f6").mkString
    assertEquals(
      read("8200" + "b9" + hexOf(301, 2) + unknown + "0e8402f500f4", state = State.Propose),
      Message.Propose(Vector(14 -> data(14)))
    )
    // a refusal text of 2000 bytes and one of 5000, past the former caps of 1024 and 4096
    Vector(2000, 5000).foreach { n =>
      assertEquals(read(refusedText(n)), Message.Refuse(Reason.Refused(14, "a" * n)), n)
    }
    // one of exactly 5760 bytes is decoded, one of 5761 refused
    assertEquals(bytes(refusedText(5752)).size, MaxMessageBytes)
    assertEquals(read(refusedText(5752)), Message.Refuse(Reason.Refused(14, "a" * 5752)))
    assertEquals(
      decodePrefix(ntn, State.Confirm, bytes(refusedText(5753))),
      Left(DecodeError(ProtocolError.HandshakeByteLimitExceeded))
    )
    // the encoder writes what the decoder reads: a long text, many terms
    val long = Message.Refuse(Reason.Refused(14, "a" * 2000))
    assertEquals(read(encoded(long)), long)
    val many = Message.Propose(Vector(14 -> Term.Arr(Vector.fill(600)(Term.Bool(false)))))
    assertEquals(read(encoded(many), state = State.Propose), many)
  }

  test("a term nested as deep as 5760 bytes allow decodes and encodes on a small stack (#1029)") {
    def nested(depth: Int): Term =
      (0 until depth).foldLeft[Term](Term.Integer(0))((inner, _) => Term.Arr(Vector(inner)))
    val depth = 5000
    // an unknown version's data, skipped, and a selected version's data, kept
    val skipped = "8200a11863" + "81" * depth + "00"
    val accepted = "83010e" + "81" * depth + "00"
    val (proposal, accept, written) = onSmallStack {
      val p = decode(ntn, State.Propose, bytes(skipped))
      val a = decode(ntn, State.Confirm, bytes(accepted))
      (p, a, a.toOption.map(encode(ntn, _)))
    }
    assertEquals(proposal, Right(Message.Propose(Vector.empty)))
    assertEquals(accept, Right(Message.Accept(14, nested(depth))))
    assertEquals(written, Some(Right(bytes(accepted))))
    // past the bound the message is written, as the reference writes it, and refused when read
    val (tooDeep, written6000) = onSmallStack {
      (
        decodePrefix(ntn, State.Confirm, bytes("83010e" + "81" * 6000 + "00")),
        encode(ntn, Message.Accept(14, nested(6000)))
      )
    }
    assertEquals(tooDeep, Left(DecodeError(ProtocolError.HandshakeByteLimitExceeded)))
    assertEquals(written6000, Right(bytes("83010e" + "81" * 6000 + "00")))
  }

  test(
    "deep tags, maps and indefinite arrays decode, encode, compare and render on a small stack"
  ) {
    def nested(depth: Int)(level: Term => Term): Term =
      (0 until depth).foldLeft[Term](Term.Integer(0))((inner, _) => level(inner))
    // the selected version's data of an accepted message, and how to build it
    val shapes: Vector[(String, String, () => Term)] = Vector(
      ("arrays", "81" * 5000 + "00", () => nested(5000)(t => Term.Arr(Vector(t)))),
      ("tags", "c1" * 5000 + "00", () => nested(5000)(Term.Tag(1, _))),
      (
        "maps",
        "a100" * 2500 + "00",
        () => nested(2500)(t => Term.Map(Vector(Term.Integer(0) -> t)))
      ),
      (
        "indefinite arrays",
        "9f" * 2500 + "00" + "ff" * 2500,
        () => nested(2500)(t => Term.Arr(Vector(t), definite = false))
      )
    )
    final case class Outcome(
        decoded: Boolean,
        written: Option[Either[ProtocolError, Bytes]],
        equal: Boolean,
        hashed: Boolean,
        rendered: Int
    )
    val outcomes = onSmallStack {
      shapes.map { (_, hex, build) =>
        val wire = bytes("83010e" + hex)
        val read = decode(ntn, State.Confirm, wire)
        Outcome(
          read == Right(Message.Accept(14, build())),
          read.toOption.map(encode(ntn, _)),
          build() == build() && build() != Term.Integer(0),
          build().hashCode == build().hashCode,
          build().toString.length
        )
      }
    }
    shapes.zip(outcomes).foreach { case ((name, hex, _), outcome) =>
      assert(outcome.decoded, s"$name: decodes to the term built")
      assertEquals(outcome.written, Some(Right(bytes("83010e" + hex))), name)
      assert(outcome.equal, s"$name: structural equality")
      assert(outcome.hashed, s"$name: equal terms hash alike")
      assert(outcome.rendered <= Tree.DefaultRenderLimit + 3, s"$name: rendering is truncated")
    }
  }

  // cborg's decodeTerm keeps an indefinite string's chunks, the empty ones dropped (Term.hs:203-216,
  // cborg 0.2.10.0), and its encodeTerm writes them back between 0x7f or 0x5f and a break
  // (Term.hs:95-102): an empty indefinite text is 7fff, not 7f60ff.
  test("indefinite strings are written again as cborg writes them, chunk by chunk (#1029)") {
    def accepted(hex: String) = bytes("83010e" + hex)
    def roundTrip(hex: String) =
      decode(ntn, State.Confirm, accepted(hex)).left.map(_.cause).flatMap(encode(ntn, _))
    Vector(
      "7fff" -> "7fff",
      "7f60ff" -> "7fff",
      "7f616160626262ff" -> "7f6161626262ff",
      "5fff" -> "5fff",
      "5f41004042010240ff" -> "5f4100420102ff",
      "9f7fff5f4100ff7f6161ffff" -> "9f7fff5f4100ff7f6161ffff"
    ).foreach { (input, output) =>
      assertEquals(roundTrip(input), Right(accepted(output)), input)
    }
    assertEquals(
      decode(ntn, State.Confirm, accepted("7f616160626262ff")),
      Right(Message.Accept(14, Term.TextChunks(Vector("a", "bb"))))
    )
    // equal by content, whatever the chunks, as a lazy Text is
    assertEquals(Term.TextChunks(Vector("ab")), Term.TextChunks(Vector("a", "b")))
    assertEquals(Term.TextChunks(Vector("ab")).hashCode, Term.TextChunks(Vector("a", "b")).hashCode)
    // the message of 2876 empty indefinite texts, 5757 bytes, decodes and is written to the same
    // bytes; with each written 7f60ff it would have passed 5760 bytes
    val empties = "9f" + "7fff" * 2876 + "ff"
    assertEquals(accepted(empties).size, 5757)
    assertEquals(roundTrip(empties), Right(accepted(empties)))
  }

  // cborg's decodeTerm reads 0xf8 and any byte after it as TSimple (tryConsumeSimple, Read.hs:2457,
  // cborg 0.2.10.0), and its encodeTerm writes TSimple n as simpleMP does (Write.hs:563-566): 0xe0 + n
  // up to 23, else 0xf8 and n. So f814 is written f4, which reads back as a boolean, as in cborg.
  test("a simple value after 0xf8 of any value is read, and written as cborg writes it (#1029)") {
    def accepted(hex: String) = bytes("83010e" + hex)
    Vector(
      ("f800", 0, "e0"),
      ("f813", 19, "f3"),
      ("f814", 20, "f4"),
      ("f815", 21, "f5"),
      ("f816", 22, "f6"),
      ("f817", 23, "f7"),
      ("f818", 24, "f818"),
      ("f81f", 31, "f81f"),
      ("f8ff", 255, "f8ff")
    ).foreach { (input, value, output) =>
      val read = decode(ntn, State.Confirm, accepted(input))
      assertEquals(read, Right(Message.Accept(14, Term.Simple(value))), input)
      assertEquals(read.left.map(_.cause).flatMap(encode(ntn, _)), Right(accepted(output)), input)
    }
    // the unknown version's data is skipped: the proposal the reference decodes is decoded
    assertEquals(read("8200a11863f818", state = State.Propose), Message.Propose(Vector.empty))
    // 20 and 21 written are the booleans' heads, and read back as booleans
    assertEquals(read("83010ef4"), Message.Accept(14, Term.Bool(false)))
    assertEquals(
      encode(ntn, Message.Accept(14, Term.Simple(256))),
      Left(ProtocolError.InvalidSimpleValue)
    )
  }
