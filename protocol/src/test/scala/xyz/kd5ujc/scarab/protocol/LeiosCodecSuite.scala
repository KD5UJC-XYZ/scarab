// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.crypto.bls.{BlsError, SeatId, Seed, SecretKey, Vote}
import xyz.kd5ujc.scarab.codec.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

/** Round trips and malformed input for the LeiosNotify and LeiosFetch codecs. */
class LeiosCodecSuite extends ScalaCheckSuite:
  private def bs(xs: Int*): Bytes = Bytes.wrap(xs.map(_.toByte).toArray)
  private def ok[A, E](e: Either[E, A]): A =
    e.fold(x => throw new AssertionError(x.toString), identity)
  private def hash(n: Int): EbHash =
    EbHash.fromBytes(Bytes.wrap(Array.fill[Byte](32)(n.toByte))).get

  private lazy val sk: SecretKey =
    ok(
      SecretKey.generate(
        ok(Seed.fromBytes(Bytes.wrap(Array.tabulate[Byte](32)(i => (i + 3).toByte))))
      )
    )
  private def vote(seat: Int, n: Int): Vote =
    ok(
      Vote.sign(
        sk,
        ok(SeatId.fromInt(seat)),
        xyz.kd5ujc.scarab.crypto.bls.RbHash
          .fromBytes(Bytes.wrap(Array.fill[Byte](32)(n.toByte)))
          .get
      )
    )

  private val genPoint: Gen[EbPoint] =
    for
      slot <- Gen.choose(0L, Long.MaxValue)
      h <- Gen.choose(0, 255)
    yield EbPoint(slot, hash(h))
  private val genBitmaps: Gen[TxBitmaps] =
    Gen.listOf(Gen.zip(Gen.choose(0, 0xffff), Gen.choose(0L, Long.MaxValue))).map { xs =>
      TxBitmaps(xs.map { case (w, b) => w -> BigInt(b) }.toVector)
    }

  private def notifyRoundTrip(state: LeiosNotify.State, m: LeiosNotify.Message): Unit =
    val bytes = ok(LeiosNotify.encode(m))
    assertEquals(LeiosNotify.decodePrefix(state, bytes), Right((m, bytes.size)))
    assert(LeiosNotify.next(state, m).isDefined)

  test("LeiosNotify: every message round-trips in the state that admits it") {
    import LeiosNotify.*
    notifyRoundTrip(State.Idle, Message.RequestNext)
    notifyRoundTrip(State.Idle, Message.Quit)
    notifyRoundTrip(State.Busy, Message.Canceled)
    notifyRoundTrip(State.Quit, Message.Done)
    notifyRoundTrip(State.Busy, Message.BlockOffer(EbPoint(77, hash(1)), 123456))
    notifyRoundTrip(State.Busy, Message.BlockTxsOffer(EbPoint(77, hash(1))))
    val vs = Vector(vote(1, 1), vote(2, 1), vote(900, 2))
    val raw = vs.map(_.encode)
    notifyRoundTrip(State.Busy, Message.Votes(vs, raw))
    // an announcement carries a header item verbatim
    val header = bs(0x82, 0x01, 0x42, 0xab, 0xcd)
    notifyRoundTrip(State.Busy, Message.BlockAnnouncement(header))
  }

  property("LeiosNotify offers round-trip") {
    forAll(genPoint, Gen.choose(0L, 0xffffffffL)) { (p, size) =>
      notifyRoundTrip(LeiosNotify.State.Busy, LeiosNotify.Message.BlockOffer(p, size))
      notifyRoundTrip(LeiosNotify.State.Busy, LeiosNotify.Message.BlockTxsOffer(p))
    }
  }

  test("LeiosNotify: messages outside their state are refused, with the tag") {
    import LeiosNotify.*
    val request = ok(encode(Message.RequestNext))
    assertEquals(decodePrefix(State.Busy, request), Left(LeiosWireError.NotAdmitted(3)))
    assertEquals(decodePrefix(State.Quit, request), Left(LeiosWireError.NotAdmitted(3)))
    assertEquals(decodePrefix(State.Done, request), Left(LeiosWireError.NotAdmitted(3)))
    assertEquals(
      decodePrefix(State.Idle, bs(0x81, 0x09)),
      Left(LeiosWireError.UnknownTag(BigInt(9)))
    )
    assertEquals(next(State.Idle, Message.Done), None)
    assertEquals(next(State.Busy, Message.RequestNext), None)
  }

  test("LeiosNotify: incomplete input asks for more, at every prefix length") {
    import LeiosNotify.*
    val bytes = ok(encode(Message.BlockOffer(EbPoint(5, hash(3)), 9)))
    (0 until bytes.size).foreach { n =>
      assertEquals(
        decodePrefix(State.Busy, bytes.take(n)),
        Left(LeiosWireError.Incomplete),
        s"prefix $n"
      )
    }
    // two messages back to back: the first is consumed alone
    val twice = bytes ++ bytes
    assertEquals(decodePrefix(State.Busy, twice).map(_._2), Right(bytes.size))
  }

  test("LeiosNotify: malformed shapes are typed errors") {
    import LeiosNotify.*
    assertEquals(decodePrefix(State.Busy, bs(0x80)), Left(LeiosWireError.EmptyMessage))
    assertEquals(decodePrefix(State.Busy, bs(0x01)), Left(LeiosWireError.NotAnArray))
    assertEquals(
      decodePrefix(State.Idle, bs(0x82, 0x03, 0x00)),
      Left(LeiosWireError.WrongArity(3, 0, 1))
    )
    // a point whose hash is 31 bytes
    val shortHash =
      bs(0x83, 0x05, 0x82, 0x01, 0x58, 31) ++ Bytes.wrap(new Array[Byte](31)) ++ bs(0x05)
    assertEquals(
      decodePrefix(State.Busy, shortHash),
      Left(LeiosWireError.BadField(5, LeiosField.Hash))
    )
    // size above 32 bits
    val bigSize =
      bs(0x83, 0x05, 0x82, 0x01, 0x58, 32) ++ Bytes.wrap(new Array[Byte](32)) ++ bs(0x1b, 0, 0, 0,
        1, 0, 0, 0, 0)
    assertEquals(
      decodePrefix(State.Busy, bigSize),
      Left(LeiosWireError.BadField(5, LeiosField.Word32))
    )
    // an empty vote list, a non-array vote list
    assertEquals(
      decodePrefix(State.Busy, bs(0x82, 0x07, 0x80)),
      Left(LeiosWireError.BadField(7, LeiosField.Votes))
    )
    assertEquals(
      decodePrefix(State.Busy, bs(0x82, 0x07, 0x01)),
      Left(LeiosWireError.BadField(7, LeiosField.Votes))
    )
  }

  test("LeiosNotify: a vote that fails strict decoding names its index and the reason") {
    import LeiosNotify.*
    val good = vote(1, 1).encode
    val bad = bs(0x83, 0x01, 0x02, 0x03)
    val message = bs(0x82, 0x07, 0x82) ++ good ++ bad
    decodePrefix(State.Busy, message) match
      case Left(LeiosWireError.BadVote(1, _: BlsError)) => ()
      case other                                        => fail(other.toString)
  }

  test("LeiosNotify: at most sixteen votes in one message (the consensus prototype's bound)") {
    import LeiosNotify.*
    def votes(n: Int): Bytes =
      bs(0x82, 0x07, 0x80 + n) ++ (1 to n).map(i => vote(i, i).encode).reduce(_ ++ _)
    assert(decodePrefix(State.Busy, votes(MaxVotes)).isRight)
    assertEquals(
      decodePrefix(State.Busy, votes(MaxVotes + 1)),
      Left(LeiosWireError.BadField(7, LeiosField.Votes))
    )
  }

  test("LeiosFetch: every message round-trips in the state that admits it") {
    import LeiosFetch.*
    def rt(state: State, m: Message): Unit =
      val bytes = ok(encode(m))
      assertEquals(decodePrefix(state, bytes), Right((m, bytes.size)))
      assert(next(state, m).isDefined)
    val p = EbPoint(42, hash(7))
    rt(State.Idle, Message.Done)
    rt(State.Idle, Message.BlockRequest(p))
    rt(
      State.Idle,
      Message.BlockTxsRequest(p, TxBitmaps(Vector(0 -> BigInt(1), 3 -> (BigInt(1) << 63))))
    )
    rt(State.Block, Message.Block(Vector(hash(1) -> 100L, hash(2) -> 0xffffffffL)))
    rt(State.Block, Message.Block(Vector.empty))
    rt(
      State.BlockTxs,
      Message.BlockTxs(p, TxBitmaps(Vector(1 -> BigInt(5))), Vector(bs(0x81, 0x01), bs(0x01)))
    )
  }

  property("LeiosFetch requests round-trip") {
    forAll(genPoint, genBitmaps) { (p, b) =>
      val m = LeiosFetch.Message.BlockTxsRequest(p, b)
      val bytes = ok(LeiosFetch.encode(m))
      assertEquals(LeiosFetch.decodePrefix(LeiosFetch.State.Idle, bytes), Right((m, bytes.size)))
    }
  }

  test("LeiosFetch: an indefinite-length bitmaps map (as the Blueprint draws it) is accepted") {
    import LeiosFetch.*
    val p = ok(encode(Message.BlockRequest(EbPoint(1, hash(9))))).drop(2) // the point item
    val msg = bs(0x83, 0x03) ++ p ++ bs(0xbf, 0x00, 0x05, 0xff)
    assertEquals(
      decodePrefix(State.Idle, msg).map(_._1),
      Right(Message.BlockTxsRequest(EbPoint(1, hash(9)), TxBitmaps(Vector(0 -> BigInt(5)))))
    )
  }

  test("LeiosFetch: agency and malformed input") {
    import LeiosFetch.*
    val block = ok(encode(Message.Block(Vector.empty)))
    assertEquals(decodePrefix(State.Idle, block), Left(LeiosWireError.NotAdmitted(2)))
    assertEquals(
      decodePrefix(State.Block, bs(0x82, 0x02, 0x01)),
      Left(LeiosWireError.BadField(2, LeiosField.EndorserBlock))
    )
    assertEquals(
      decodePrefix(State.Idle, bs(0x81, 0x05)),
      Left(LeiosWireError.UnknownTag(BigInt(5)))
    )
    assertEquals(next(State.Block, Message.Done), None)
    assertEquals(decodePrefix(State.Idle, bs(0x82, 0x01)), Left(LeiosWireError.Incomplete))
  }
  test("LeiosFetch: transactions are written as the bytes they came with, never re-encoded") {
    import LeiosFetch.*
    // a non-shortest integer (`18 02`), an indefinite-length array, and a tagged byte string: a hash
    // over the re-encoded bytes would differ from the hash over these
    val txs = Vector(bs(0x82, 0x18, 0x02, 0x01), bs(0x9f, 0x01, 0xff), bs(0xd8, 0x18, 0x41, 0x00))
    val m = Message.BlockTxs(EbPoint(5, hash(3)), TxBitmaps(Vector(0 -> BigInt(7))), txs)
    val bytes = ok(encode(m))
    assert(bytes.hex.contains(txs.map(_.hex).mkString), bytes.hex)
    assertEquals(decodePrefix(State.BlockTxs, bytes), Right((m, bytes.size)))
    // the list head is right for every width of the count
    Vector(0, 1, 23, 24, 255, 256, 1000).foreach { n =>
      val many =
        Message.BlockTxs(EbPoint(5, hash(3)), TxBitmaps(Vector.empty), Vector.fill(n)(bs(0xf6)))
      val enc = ok(encode(many))
      assertEquals(decodePrefix(State.BlockTxs, enc), Right((many, enc.size)))
    }
    // something that is not exactly one item is refused, not spliced into the message
    assertEquals(
      encode(
        Message.BlockTxs(EbPoint(5, hash(3)), TxBitmaps(Vector.empty), Vector(bs(0x01, 0x02)))
      ),
      Left(LeiosWireError.Syntax(ProtocolError.Codec(xyz.kd5ujc.scarab.codec.Cbor.Error.TrailingBytes)))
    )
    assertEquals(
      encode(
        Message.BlockTxs(EbPoint(5, hash(3)), TxBitmaps(Vector.empty), Vector(bs(0x82, 0x01)))
      ),
      Left(LeiosWireError.Syntax(ProtocolError.Codec(xyz.kd5ujc.scarab.codec.Cbor.Error.UnexpectedEnd)))
    )
  }

  test("LeiosNotify: an announced header is written as the bytes it came with") {
    import LeiosNotify.*
    val header = bs(0x83, 0x18, 0x02, 0x01, 0x9f, 0xff) // a non-shortest integer inside
    val bytes = ok(encode(Message.BlockAnnouncement(header)))
    assertEquals(bytes, bs(0x82, 0x04) ++ header)
    assertEquals(
      decodePrefix(State.Busy, bytes),
      Right((Message.BlockAnnouncement(header), bytes.size))
    )
  }
end LeiosCodecSuite
