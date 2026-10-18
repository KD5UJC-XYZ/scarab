// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.Bytes

import PeerSharing.*

class PeerSharingSuite extends munit.ScalaCheckSuite:
  private def b(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private def amount(n: Int): Amount = Amount.from(n).getOrElse(fail(s"amount $n"))
  private def word(n: Long): Word32 = Word32.from(n).getOrElse(fail(s"word $n"))
  private def port(n: Int): Port = Port.from(n).getOrElse(fail(s"port $n"))
  private def v4(a: Long, p: Int): Address = Address.V4(word(a), port(p))
  private def v6(a: Long, bb: Long, c: Long, d: Long, p: Int): Address =
    Address.V6(word(a), word(bb), word(c), word(d), port(p))

  private def failure(state: State, sender: Role, hex: String): Error =
    decodePrefix(state, sender, b(hex)) match
      case Decoding.Failed(error) => error
      case other                  => fail(s"expected Failed for $hex, got $other")

  // Literal fixtures written from the pinned codec (Codec.hs:42-93, PeerSharing/Codec.hs:29-65), not
  // produced by this encoder: a peer list is indefinite unless empty, integers are the shortest form.
  private val loopback = v4(0x7f000001L, 3001)
  private val loopback6 = v6(0, 0, 0, 1, 3001)
  private val literals: Vector[(String, State, Role, Message, State)] = Vector(
    ("820000", State.Idle, Role.Client, Message.ShareRequest(amount(0)), State.Busy),
    ("820008", State.Idle, Role.Client, Message.ShareRequest(amount(8)), State.Busy),
    ("820018ff", State.Idle, Role.Client, Message.ShareRequest(amount(255)), State.Busy),
    ("8102", State.Idle, Role.Client, Message.Done, State.Done),
    ("820180", State.Busy, Role.Server, Message.SharePeers(Vector.empty), State.Idle),
    (
      "82019f83001a7f000001190bb9ff",
      State.Busy,
      Role.Server,
      Message.SharePeers(Vector(loopback)),
      State.Idle
    ),
    (
      "82019f83001a7f000001190bb9860100000001190bb9ff",
      State.Busy,
      Role.Server,
      Message.SharePeers(Vector(loopback, loopback6)),
      State.Idle
    ),
    (
      "82019f83000018ffff",
      State.Busy,
      Role.Server,
      Message.SharePeers(Vector(v4(0, 255))),
      State.Idle
    )
  )

  literals.foreach { case (hex, state, role, message, next) =>
    test(s"literal $hex: exact encode, decode, every truncation, consumed prefix") {
      val wire = b(hex)
      assertEquals(encode(state, role, message), Right(wire))
      assertEquals(decode(state, role, wire), Right(message))
      assertEquals(transition(state, role, message), Right(next))
      (0 until wire.size).foreach { cut =>
        assertEquals(decodePrefix(state, role, Bytes(wire.value.take(cut))), Decoding.NeedMore)
      }
      val joined = Bytes(wire.value ++ b("8102").value)
      assertEquals(decodePrefix(state, role, joined), Decoding.Decoded(message, wire.size))
      assertEquals(decode(state, role, joined), Left(Error.Trailing(2)))
      assertEquals(decode(state, role, Bytes(wire.value.dropRight(1))), Left(Error.Truncated))
    }
  }

  test("a definite list of peers and wider integers are accepted, as the reference accepts them") {
    val definite = b("820181" + "83001a7f000001190bb9")
    assertEquals(
      decode(State.Busy, Role.Server, definite),
      Right(Message.SharePeers(Vector(loopback)))
    )
    // amount 8 in two bytes, port in eight bytes
    assertEquals(
      decode(State.Idle, Role.Client, b("8200190008")),
      Right(Message.ShareRequest(amount(8)))
    )
    assertEquals(
      decode(State.Busy, Role.Server, b("820181" + "83001a7f0000011b0000000000000bb9")),
      Right(Message.SharePeers(Vector(loopback)))
    )
  }

  test("the ranges of the CDDL: word8, word32, word16") {
    assertEquals(
      failure(State.Idle, Role.Client, "8200190100"),
      Error.OutOfRange(Field.Amount, 256)
    )
    assertEquals(
      failure(State.Busy, Role.Server, "820181" + "83001a7f0000011a00010000"),
      Error.OutOfRange(Field.Port, 65536)
    )
    assertEquals(
      failure(State.Busy, Role.Server, "820181" + "83001b00000001000000000100"),
      Error.OutOfRange(Field.Word32, BigInt(0x100000000L))
    )
    assertEquals(
      failure(State.Busy, Role.Server, "820181" + "83021a7f000001190bb9"),
      Error.OutOfRange(Field.AddressTag, 2)
    )
  }

  test("the key and the arity must fit the state and the message") {
    assertEquals(failure(State.Idle, Role.Client, "820100"), Error.UnexpectedKey(State.Idle, 1))
    assertEquals(failure(State.Idle, Role.Client, "8103"), Error.UnexpectedKey(State.Idle, 3))
    assertEquals(failure(State.Busy, Role.Server, "820000"), Error.UnexpectedKey(State.Busy, 0))
    assertEquals(failure(State.Busy, Role.Server, "8102"), Error.UnexpectedKey(State.Busy, 2))
    assertEquals(failure(State.Idle, Role.Client, "83000000"), Error.Arity(2, 3))
    assertEquals(failure(State.Idle, Role.Client, "820200"), Error.Arity(1, 2))
    assertEquals(failure(State.Busy, Role.Server, "8301" + "80" + "00"), Error.Arity(2, 3))
    assertEquals(
      failure(State.Busy, Role.Server, "820181" + "82001a7f000001"),
      Error.Arity(3, 2)
    )
    assertEquals(
      failure(State.Busy, Role.Server, "820181" + "8501000000" + "00"),
      Error.Arity(6, 5)
    )
  }

  test("what is not CBOR of the shape wanted") {
    assert(failure(State.Idle, Role.Client, "a0").isInstanceOf[Error.Unexpected])
    assert(failure(State.Idle, Role.Client, "9f0000ff").isInstanceOf[Error.Unexpected])
    assert(failure(State.Idle, Role.Client, "82616100").isInstanceOf[Error.Unexpected])
    assert(failure(State.Busy, Role.Server, "8201a0").isInstanceOf[Error.Unexpected])
    assert(failure(State.Busy, Role.Server, "820181" + "a0").isInstanceOf[Error.Unexpected])
    assertEquals(failure(State.Idle, Role.Client, "821c00"), Error.ReservedHead(0x1c))
    assertEquals(failure(State.Idle, Role.Client, "821f00"), Error.ReservedHead(0x1f))
  }

  test("agency: nothing in StDone, the sender must be the side that has agency") {
    literals.foreach { case (hex, state, role, message, _) =>
      for s <- State.values; r <- Role.values do
        val legal = s == state && r == role
        assertEquals(encode(s, r, message).isRight, legal, s"encode $hex in $s from $r")
        assertEquals(transition(s, r, message).isRight, legal)
        if !legal then
          assert(
            decodePrefix(s, r, b(hex)).isInstanceOf[Decoding.Failed[?]],
            s"decode $hex in $s from $r"
          )
    }
    assertEquals(failure(State.Done, Role.Client, "8102"), Error.Terminal)
    assertEquals(
      failure(State.Idle, Role.Server, "8102"),
      Error.WrongAgency(State.Idle, Role.Server)
    )
    assertEquals(
      failure(State.Busy, Role.Client, "820180"),
      Error.WrongAgency(State.Busy, Role.Client)
    )
  }

  test("a message over the limit is refused when encoded and when decoded") {
    val many = Vector.tabulate(300)(i =>
      v6(0xffffffffL, 0xfffffff0L + i % 16, 0xffffffffL, 0xffffffffL, 65535 - i)
    )
    val reply = Message.SharePeers(many)
    val big = Limits(1_000_000)
    assertEquals(encode(State.Busy, Role.Server, reply), Left(Error.TooLarge(UpstreamMessageBytes)))
    val wire = encode(State.Busy, Role.Server, reply, big).fold(e => fail(e.message), identity)
    assert(wire.size > UpstreamMessageBytes, wire.size)
    assertEquals(
      decodePrefix(State.Busy, Role.Server, wire),
      Decoding.Failed(Error.TooLarge(UpstreamMessageBytes))
    )
    assertEquals(
      decodePrefix(State.Busy, Role.Server, wire, big),
      Decoding.Decoded(reply, wire.size)
    )
    // the largest answer of ten peers fits
    assert(encode(State.Busy, Role.Server, Message.SharePeers(many.take(10))).isRight)
    // a list that claims far more items than the limit holds fails on the first byte past it
    val claims = b("82019b00ffffffffffffff" + "83001a7f000001190bb9" * 800)
    assertEquals(
      decodePrefix(State.Busy, Role.Server, claims),
      Decoding.Failed(Error.TooLarge(UpstreamMessageBytes))
    )
  }

  test("the byte limit of the state, from the reference") {
    assertEquals(UpstreamMessageBytes, 5760)
    assertEquals(UpstreamTimeLimits.busy.toSeconds, 60L)
    assertEquals(MiniProtocolId, 10)
  }

  test("an address in bytes, network order, and the order of addresses") {
    assertEquals(loopback.toBytes.hex, "7f000001" + "0bb9")
    assertEquals(loopback6.toBytes.hex, "00000000000000000000000000000001" + "0bb9")
    val ordering = summon[Ordering[Address]]
    assert(ordering.lt(v4(1, 1), v4(1, 2)))
    assert(ordering.lt(v4(1, 2), v4(2, 0)))
    assertEquals(ordering.compare(loopback, loopback), 0)
  }

  private val genWord: Gen[Word32] = Gen
    .oneOf(
      Gen.choose(0L, 23L),
      Gen.choose(0L, 255L),
      Gen.choose(0L, 65535L),
      Gen.choose(0L, Word32.Max)
    )
    .map(word)
  private val genPort: Gen[Port] = Gen.oneOf(Gen.choose(0, 255), Gen.choose(0, Port.Max)).map(port)
  private val genAddress: Gen[Address] = Gen.oneOf(
    for a <- genWord; p <- genPort yield Address.V4(a, p),
    for a <- genWord; x <- genWord; y <- genWord; z <- genWord; p <- genPort
    yield Address.V6(a, x, y, z, p)
  )
  private val genAmount: Gen[Amount] = Gen.choose(0, 255).map(amount)
  private val genMessage: Gen[(State, Role, Message)] = Gen.oneOf(
    genAmount.map(a => (State.Idle, Role.Client, Message.ShareRequest(a))),
    Gen.const((State.Idle, Role.Client, Message.Done)),
    Gen
      .choose(0, 12)
      .flatMap(n => Gen.listOfN(n, genAddress))
      .map(peers => (State.Busy, Role.Server, Message.SharePeers(peers.toVector)))
  )

  property("decode (encode m) = m, the whole input, at every cut a NeedMore") {
    Prop.forAll(genMessage) { case (state, role, message) =>
      encode(state, role, message) match
        case Left(error) => Prop.falsified :| error.message
        case Right(wire) =>
          val whole = decode(state, role, wire) == Right(message)
          val cuts = (0 until wire.size).forall(c =>
            decodePrefix(state, role, wire.take(c)) == Decoding.NeedMore
          )
          Prop(whole && cuts) :| s"${wire.hex}"
    }
  }

  property("decodePrefix is total: any bytes give NeedMore, Failed or a message within the input") {
    Prop.forAll(
      Gen.listOf(Gen.choose(-128, 127).map(_.toByte)),
      Gen.oneOf(State.values.toSeq),
      Gen.oneOf(Role.values.toSeq)
    ) { (bytes, state, role) =>
      val input = Bytes(bytes.toVector)
      decodePrefix(state, role, input) match
        case Decoding.Decoded(_, used) => Prop(used > 0 && used <= input.size)
        case _                         => Prop.passed
    }
  }

  test("amounts, words and ports are checked at construction") {
    assertEquals(Amount.from(-1), None)
    assertEquals(Amount.from(256), None)
    assertEquals(Amount.saturating(1000).value, 255)
    assertEquals(Amount.saturating(-3).value, 0)
    assertEquals(Word32.from(-1), None)
    assertEquals(Word32.from(0x100000000L), None)
    assertEquals(Word32.ofBits(-1).value, 0xffffffffL)
    assertEquals(Port.from(65536), None)
    assertEquals(Port.from(-1), None)
  }

  test("a violation names the request and the answer") {
    assertEquals(
      Violation.TooManyPeers(amount(2), 3).message,
      "PeerSharing answered 3 peers to a request for 2"
    )
  }
