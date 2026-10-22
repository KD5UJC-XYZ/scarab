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
  // what the reference accepts: ascending windows below 268, none of them zero
  private val genBitmaps: Gen[TxBitmaps] =
    Gen
      .listOf(
        Gen.zip(Gen.choose(0, LeiosFetch.MaxBitmapEntries - 1), Gen.choose(1L, Long.MaxValue))
      )
      .map { xs =>
        TxBitmaps(xs.sortBy(_._1).distinctBy(_._1).map((w, b) => w -> BigInt(b)).toVector)
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
    rt(State.BlockTxs, Message.BlockTxs(Vector(bs(0x81, 0x01), bs(0x01))))
    rt(State.BlockTxs, Message.BlockTxs(Vector.empty))
    rt(
      State.BlockTxs,
      Message.LegacyBlockTxs(
        p,
        TxBitmaps(Vector(1 -> BigInt(5))),
        Vector(bs(0x81, 0x01), bs(0x01))
      )
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
    val block = ok(encode(Message.Block(Vector(hash(1) -> 1L))))
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
  private val p0 = EbPoint(5, hash(3))
  private def tag24(payload: Bytes): Bytes = // d8 18 and the byte string head of payload
    val n = payload.size
    val h =
      if n < 24 then bs(0x40 | n) else if n < 256 then bs(0x58, n) else bs(0x59, n >> 8, n & 0xff)
    bs(0xd8, 0x18) ++ h ++ payload

  test("LeiosFetch: a transaction is #6.24(bytes) and its payload is kept as it came") {
    import LeiosFetch.*
    // the payload holds a non-shortest integer (`18 02`), an indefinite-length array and a nested
    // tag: a hash over re-encoded bytes would differ from the hash over these
    val txs = Vector(bs(0x82, 0x18, 0x02, 0x01), bs(0x9f, 0x01, 0xff), bs(0xd8, 0x18, 0x41, 0x00))
    val bytes = ok(encode(Message.BlockTxs(txs)))
    assertEquals(bytes, bs(0x82, 0x04, 0x83) ++ txs.map(tag24).foldLeft(Bytes.empty)(_ ++ _))
    assertEquals(decodePrefix(State.BlockTxs, bytes), Right((Message.BlockTxs(txs), bytes.size)))
    // any payload is carried: the reference decoder checks the framing, not the CBOR inside
    val opaque = Vector(bs(0xff, 0xff), Bytes.empty)
    val enc = ok(encode(Message.BlockTxs(opaque)))
    assertEquals(decodePrefix(State.BlockTxs, enc), Right((Message.BlockTxs(opaque), enc.size)))
    // the list head is right for every width of the count
    Vector(0, 1, 23, 24, 255, 256, 1000).foreach { n =>
      val many = Message.BlockTxs(Vector.fill(n)(bs(0xf6)))
      val e = ok(encode(many))
      assertEquals(decodePrefix(State.BlockTxs, e), Right((many, e.size)))
    }
  }

  test("LeiosFetch: an indefinite-length transaction list and byte string are read") {
    import LeiosFetch.*
    val msg =
      bs(0x82, 0x04, 0x9f) ++ tag24(bs(0xa0)) ++ bs(0xd8, 0x18, 0x5f, 0x41, 0xa0, 0xff, 0xff)
    assertEquals(
      decodePrefix(State.BlockTxs, msg).map(_._1),
      Right(Message.BlockTxs(Vector(bs(0xa0), bs(0xa0))))
    )
  }

  test("LeiosFetch: a transaction without tag 24, or with another tag, is refused with its index") {
    import LeiosFetch.*
    val good = tag24(bs(0xa0))
    // an untagged byte string, a plain map, tag 24 around a non-bytes item, tag 25 around bytes
    Vector(bs(0x41, 0xa0), bs(0xa0), bs(0xd8, 0x18, 0xa0), bs(0xd8, 0x19, 0x41, 0xa0)).foreach {
      bad =>
        val msg = bs(0x82, 0x04, 0x82) ++ good ++ bad
        assertEquals(
          decodePrefix(State.BlockTxs, msg),
          Left(LeiosWireError.BadTransaction(1)),
          bad.hex
        )
    }
    assertEquals(
      decodePrefix(State.BlockTxs, bs(0x82, 0x04, 0xa0)),
      Left(LeiosWireError.BadField(4, LeiosField.Transactions))
    )
  }

  test("LeiosFetch: at most maxTxsPerEb transactions, in a definite or an indefinite list") {
    import LeiosFetch.*
    assertEquals(MaxTxsPerEb, 17142) // (600000 - 5) / 35, Ouroboros.Consensus.Leios.Types
    val one = tag24(bs(0xa0))
    def definite(n: Int) =
      bs(0x82, 0x04) ++ bs(0x99, n >> 8, n & 0xff) ++ Bytes.wrap(Array.fill(n)(one.toArray).flatten)
    def indefinite(n: Int) =
      bs(0x82, 0x04, 0x9f) ++ Bytes.wrap(Array.fill(n)(one.toArray).flatten) ++ bs(0xff)
    Vector(definite, indefinite).foreach { form =>
      assert(decodePrefix(State.BlockTxs, form(MaxTxsPerEb)).isRight)
      assertEquals(
        decodePrefix(State.BlockTxs, form(MaxTxsPerEb + 1)),
        Left(LeiosWireError.TooMany(4, LeiosField.Transactions, MaxTxsPerEb, MaxTxsPerEb + 1))
      )
    }
    assertEquals(
      encode(Message.BlockTxs(Vector.fill(MaxTxsPerEb + 1)(bs(0xa0)))),
      Left(LeiosWireError.Unencodable)
    )
  }

  test("LeiosFetch: an endorser block names at least one and at most maxTxsPerEb transactions") {
    import LeiosFetch.*
    val entry = bs(0x58, 0x20) ++ Bytes.wrap(Array.fill[Byte](32)(1)) ++ bs(0x01)
    assertEquals(
      decodePrefix(State.Block, bs(0x82, 0x02, 0xa0)),
      Left(LeiosWireError.EmptyEndorserBlock)
    )
    assertEquals(
      decodePrefix(State.Block, bs(0x82, 0x02, 0xbf, 0xff)),
      Left(LeiosWireError.EmptyEndorserBlock)
    )
    assertEquals(encode(Message.Block(Vector.empty)), Left(LeiosWireError.Unencodable))
    // an indefinite map of one entry is read; the keys here repeat, which the codec does not judge
    assert(decodePrefix(State.Block, bs(0x82, 0x02, 0xbf) ++ entry ++ bs(0xff)).isRight)
    def eb(n: Int) =
      bs(0x82, 0x02, 0xb9, n >> 8, n & 0xff) ++ Bytes.wrap(Array.fill(n)(entry.toArray).flatten)
    assert(decodePrefix(State.Block, eb(MaxTxsPerEb)).isRight)
    assertEquals(
      decodePrefix(State.Block, eb(MaxTxsPerEb + 1)),
      Left(LeiosWireError.TooMany(2, LeiosField.EndorserBlock, MaxTxsPerEb, MaxTxsPerEb + 1))
    )
  }

  test("LeiosFetch: the form of Blueprint fe4cceb9 is still read, as LegacyBlockTxs") {
    import LeiosFetch.*
    // [4, point, bitmaps, [tx...]] with each transaction any item, kept as the item's own bytes
    val txs = Vector(bs(0x82, 0x18, 0x02, 0x01), bs(0x41, 0xa0), bs(0xd8, 0x18, 0x41, 0x00))
    val m = Message.LegacyBlockTxs(p0, TxBitmaps(Vector(0 -> BigInt(7))), txs)
    val bytes = ok(encode(m))
    assert(bytes.hex.contains(txs.map(_.hex).mkString), bytes.hex)
    assertEquals(bytes.take(2), bs(0x84, 0x04))
    assertEquals(decodePrefix(State.BlockTxs, bytes), Right((m, bytes.size)))
    assertEquals(next(State.BlockTxs, m), Some(State.Idle))
    // indefinite bitmaps and list, as the Blueprint draws them
    val pt = ok(encode(Message.BlockRequest(p0))).drop(2)
    val old = bs(0x84, 0x04) ++ pt ++ bs(0xbf, 0x00, 0x07, 0xff, 0x9f, 0xa0, 0xff)
    assertEquals(
      decodePrefix(State.BlockTxs, old).map(_._1),
      Right(Message.LegacyBlockTxs(p0, TxBitmaps(Vector(0 -> BigInt(7))), Vector(bs(0xa0))))
    )
    // a transaction list that is not a list
    assertEquals(
      decodePrefix(State.BlockTxs, bs(0x84, 0x04) ++ pt ++ bs(0xa0, 0xa0)),
      Left(LeiosWireError.BadField(4, LeiosField.Transactions))
    )
    // neither one nor three fields is a form
    assertEquals(
      decodePrefix(State.BlockTxs, bs(0x83, 0x04) ++ pt ++ bs(0xa0)),
      Left(LeiosWireError.WrongArity(4, 1, 2))
    )
  }

  test("LeiosFetch: bitmaps are written as the indefinite-length map the reference writes") {
    import LeiosFetch.*
    val m = Message.BlockTxsRequest(p0, TxBitmaps(Vector(0 -> BigInt(1), 3 -> (BigInt(1) << 63))))
    val bytes = ok(encode(m))
    val entries = bs(0x00, 0x01, 0x03, 0x1b, 0x80, 0, 0, 0, 0, 0, 0, 0)
    assertEquals(bytes.drop(bytes.size - entries.size - 2), bs(0xbf) ++ entries ++ bs(0xff))
    assertEquals(decodePrefix(State.Idle, bytes), Right((m, bytes.size)))
    // the empty request is the empty indefinite map
    val none = ok(encode(Message.BlockTxsRequest(p0, TxBitmaps(Vector.empty))))
    assertEquals(none.drop(none.size - 2), bs(0xbf, 0xff))
  }

  test("LeiosFetch: the reference's bitmap checks, each refused with its own error") {
    import LeiosFetch.*
    val pt = ok(encode(Message.BlockRequest(p0))).drop(2)
    def request(entries: Bytes, indefinite: Boolean = false, pairs: Int = 2): Bytes =
      bs(0x83, 0x03) ++ pt ++ (if indefinite then bs(0xbf) ++ entries ++ bs(0xff)
                               else bs(0xa0 | pairs) ++ entries)
    def windowEntry(w: Int, bits: Int = 1): Bytes = bs(0x19, w >> 8, w & 0xff, bits)
    // a zero bitmap, in either map form
    assertEquals(
      decodePrefix(State.Idle, request(bs(0x05, 0x00), pairs = 1)),
      Left(LeiosWireError.ZeroBitmap(3, 5))
    )
    assertEquals(
      decodePrefix(State.Idle, request(bs(0x05, 0x00), indefinite = true)),
      Left(LeiosWireError.ZeroBitmap(3, 5))
    )
    // a window at the limit is refused, the one below is read
    assertEquals(
      decodePrefix(State.Idle, request(windowEntry(MaxBitmapEntries), pairs = 1)),
      Left(LeiosWireError.BitmapWindowOutOfRange(3, MaxBitmapEntries, MaxBitmapEntries))
    )
    assert(decodePrefix(State.Idle, request(windowEntry(MaxBitmapEntries - 1), pairs = 1)).isRight)
    assertEquals(MaxBitmapEntries, 268)
    // equal and descending windows
    assertEquals(
      decodePrefix(State.Idle, request(bs(0x02, 0x01, 0x02, 0x01))),
      Left(LeiosWireError.BitmapWindowsNotAscending(3, 2))
    )
    assertEquals(
      decodePrefix(State.Idle, request(bs(0x03, 0x01, 0x02, 0x01), indefinite = true)),
      Left(LeiosWireError.BitmapWindowsNotAscending(3, 2))
    )
    // a zero bitmap is reported before an out-of-range window of the same entry, as in the reference
    assertEquals(
      decodePrefix(State.Idle, request(windowEntry(1000, 0), pairs = 1)),
      Left(LeiosWireError.ZeroBitmap(3, 1000))
    )
    // at most 268 entries, definite or indefinite; 268 windows 0 to 267 are read
    def many(n: Int): Bytes =
      Bytes.wrap((0 until n).flatMap(w => windowEntry(w % 268).toArray).toArray)
    val full = bs(0x83, 0x03) ++ pt ++ bs(0xb9, 268 >> 8, 268 & 0xff) ++ many(268)
    assert(decodePrefix(State.Idle, full).isRight)
    val over = bs(0x83, 0x03) ++ pt ++ bs(0xb9, 269 >> 8, 269 & 0xff) ++ many(269)
    assertEquals(
      decodePrefix(State.Idle, over),
      Left(LeiosWireError.TooMany(3, LeiosField.Bitmaps, 268, 269))
    )
    val overIndef = bs(0x83, 0x03) ++ pt ++ bs(0xbf) ++ many(269) ++ bs(0xff)
    assertEquals(
      decodePrefix(State.Idle, overIndef),
      Left(LeiosWireError.TooMany(3, LeiosField.Bitmaps, 268, 269))
    )
    // the encoder refuses what the decoder refuses
    Vector(
      Vector(5 -> BigInt(0)),
      Vector(268 -> BigInt(1)),
      Vector(2 -> BigInt(1), 2 -> BigInt(1)),
      Vector(3 -> BigInt(1), 2 -> BigInt(1)),
      (0 to 268).map(_ -> BigInt(1)).toVector
    ).foreach { w =>
      assertEquals(
        encode(Message.BlockTxsRequest(p0, TxBitmaps(w))),
        Left(LeiosWireError.Unencodable),
        w.take(3).toString
      )
    }
    // the older form carries the same bitmaps and the same checks
    val legacy = bs(0x84, 0x04) ++ pt ++ bs(0xa1, 0x05, 0x00, 0x80)
    assertEquals(
      decodePrefix(State.BlockTxs, legacy),
      Left(LeiosWireError.ZeroBitmap(4, 5))
    )
  }

  test("LeiosFetch: the client checks an answer against its own request") {
    import LeiosFetch.*
    val request = TxBitmaps(Vector(0 -> BigInt(5), 3 -> (BigInt(1) << 63)))
    assertEquals(request.count, 3L)
    val txs = Vector.fill(3)(bs(0xa0))
    assertEquals(answers(request, txs), Right(txs))
    assertEquals(
      answers(request, txs.take(2)),
      Left(LeiosWireError.TransactionCountMismatch(3L, 2))
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
