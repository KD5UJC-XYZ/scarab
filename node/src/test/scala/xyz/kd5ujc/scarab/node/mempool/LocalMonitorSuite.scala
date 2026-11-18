// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.node.assertions.Assertions
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import cats.mtl.Handle
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{Hash32, ShelleyBasedEra, VocabError}
import xyz.kd5ujc.scarab.network.{
  Bearer,
  HandshakeClient,
  LocalTxMonitorServer,
  LocalTxSubmissionServer,
  Mux,
  MuxChannel,
  NetworkError,
  NodeToClientServer,
  Settled
}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.Snapshot
import xyz.kd5ujc.scarab.protocol.{Era, Handshake, LocalTxMonitor, ProtocolError, Protocols}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import xyz.kd5ujc.scarab.protocol.LocalTxMonitor.{Kind, Message, State}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*

import TxKit.*

/** The mempool as LocalTxMonitor reports it, against the rules the reference's server applies. */
class LocalMonitorSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ScalaCheckSuite:
  private given Assertions[IO] = Assertions.noop[IO]
  private given cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: xyz.kd5ujc.scarab.ledger.StorageError, A](error: E): IO[A] = IO(
      fail(error.message)
    )
  private def pool(capacityOverride: Option[Long] = None) =
    MempoolState.empty(Right(ledger(4)), 1000, capacityOverride.map(word32))

  private def held(raws: Bytes*): MempoolState =
    raws.foldLeft(pool())((s, raw) =>
      s.add(raw, env, Source.Local).fold(r => fail(r.message), _._1)
    )

  test("the snapshot is validated for the slot after the tip, in arrival order") {
    val (t1, id1) = pay(1)
    val (t2, id2) = pay(2)
    val snap = LocalMonitor.snapshot(held(t1, t2))
    assertEquals(snap.slot, BigInt(1001))
    assertEquals(
      snap.txs.map(_.tx),
      Vector(LocalMonitor.wrapTx(Era.Conway, t1), LocalMonitor.wrapTx(Era.Conway, t2))
    )
    assertEquals(snap.has(LocalMonitor.wrapId(Era.Conway, id1)), Right(true))
    assertEquals(snap.has(LocalMonitor.wrapId(Era.Conway, id2)), Right(true))
    assertEquals(
      snap.has(LocalMonitor.wrapId(Era.Conway, Bytes(Vector.fill(32)(7.toByte)))),
      Right(false)
    )
  }

  private def hex(s: String): Bytes = Bytes.fromHex(s).toOption.get
  private def refused(cause: GenTxId.Refusal) = Left(ProtocolError.UndecodableTxId(cause))

  /** `[era, h'id']`, an id of a Shelley-based era. */
  private def tagged(era: Int, id: Bytes): Bytes = hex(f"82$era%02x5820") ++ id

  /** `[0, [constructor, h'hash']]`, one of Byron's four ids. */
  private def byron(constructor: Int, hash: Bytes): Bytes =
    hex(f"820082$constructor%02x5820") ++ hash

  test("a held transaction is found under every era's tag, and under Byron's four ids") {
    val (t1, id1) = pay(1)
    val snap = LocalMonitor.snapshot(held(t1))
    (1 to 7).foreach(era => assertEquals(snap.has(tagged(era, id1)), Right(true), s"era $era"))
    (0 to 3).foreach(c => assertEquals(snap.has(byron(c, id1)), Right(true), s"Byron $c"))
  }

  test("an id that no held transaction has answers false under every tag") {
    val (t1, _) = pay(1)
    val snap = LocalMonitor.snapshot(held(t1))
    val absent = Bytes(Vector.fill(32)(7.toByte))
    (1 to 7).foreach(era => assertEquals(snap.has(tagged(era, absent)), Right(false), s"era $era"))
    (0 to 3).foreach(c => assertEquals(snap.has(byron(c, absent)), Right(false), s"Byron $c"))
    assertEquals(LocalMonitor.snapshot(pool()).has(tagged(6, absent)), Right(false))
  }

  property("membership is the hash's, whatever era or Byron id names it") {
    val (t1, id1) = pay(1)
    val held1 = Set(id1)
    val anyId = Gen.oneOf(
      Gen.const(id1),
      Gen.listOfN(32, Gen.choose(0, 255)).map(bs => Bytes(bs.map(_.toByte).toVector))
    )
    val names = Gen.oneOf(
      Gen.choose(1, 7).map(era => (id: Bytes) => tagged(era, id)),
      Gen.choose(0, 3).map(c => (id: Bytes) => byron(c, id))
    )
    val snap = LocalMonitor.snapshot(held(t1))
    forAll(anyId, names) { (id, name) =>
      assertEquals(snap.has(name(id)), Right(held1(id)))
    }
  }

  test("an id the era's decoder refuses is refused with where it stops") {
    val (t1, id1) = pay(1)
    val snap = LocalMonitor.snapshot(held(t1))
    val h = id1.hex
    val short = "11" * 28
    // a 28-byte id in Conway
    assertEquals(
      snap.has(hex(s"8206581c$short")),
      refused(
        GenTxId.Refusal
          .HashLength(Era.Conway, VocabError.WrongHashLength(VocabError.Kind.Hash32, 32, 28))
      )
    )
    // a bare id in Byron, whose decoder reads a list of its constructor and hash
    assertEquals(snap.has(hex(s"82005820$h")), refused(GenTxId.Refusal.ByronEnvelope))
    // an era past the last, and an era index that is not a Word8
    assertEquals(snap.has(hex(s"82085820$h")), refused(GenTxId.Refusal.UnknownEra(8)))
    assertEquals(snap.has(hex(s"821901005820$h")), refused(GenTxId.Refusal.EraIndex))
    assertEquals(snap.has(hex(s"82205820$h")), refused(GenTxId.Refusal.EraIndex))
    // the envelope: an indefinite list, three items, no list
    assertEquals(snap.has(hex(s"9f065820${h}ff")), refused(GenTxId.Refusal.EraEnvelope))
    assertEquals(snap.has(hex(s"83065820${h}00")), refused(GenTxId.Refusal.EraEnvelope))
    assertEquals(snap.has(hex(s"5820$h")), refused(GenTxId.Refusal.EraEnvelope))
    // Byron: a fifth constructor, a constructor past a Word8, a short hash, an indefinite list
    assertEquals(snap.has(hex(s"820082045820$h")), refused(GenTxId.Refusal.UnknownByronTag(4)))
    assertEquals(snap.has(hex(s"8200821901005820$h")), refused(GenTxId.Refusal.ByronTag))
    assertEquals(
      snap.has(hex(s"82008200581c$short")),
      refused(
        GenTxId.Refusal
          .HashLength(Era.Byron, VocabError.WrongHashLength(VocabError.Kind.Hash32, 32, 28))
      )
    )
    assertEquals(snap.has(hex(s"82009f005820${h}ff")), refused(GenTxId.Refusal.ByronEnvelope))
    // a chunked id, below decoder version 12 (Byron at 1, Conway at 9)
    val chunked = s"5f5810${h.take(32)}5810${h.drop(32)}ff"
    assertEquals(
      snap.has(hex(s"82008200$chunked")),
      refused(GenTxId.Refusal.IndefiniteBytes(Era.Byron))
    )
    assertEquals(
      snap.has(hex(s"8206$chunked")),
      refused(GenTxId.Refusal.IndefiniteBytes(Era.Conway))
    )
    assertEquals(
      snap.has(hex(s"8201$chunked")),
      refused(GenTxId.Refusal.IndefiniteBytes(Era.Shelley))
    )
    // not a byte string: text, a tagged string
    assertEquals(snap.has(hex("82066141")), refused(GenTxId.Refusal.ExpectedBytes(Era.Conway)))
    assertEquals(
      snap.has(hex(s"8206d8185820$h")),
      refused(GenTxId.Refusal.ExpectedBytes(Era.Conway))
    )
    // a float or a simple value is refused where the reference's decoder meets it: after the era
    assertEquals(snap.has(hex("8206f93c00")), refused(GenTxId.Refusal.ExpectedBytes(Era.Conway)))
    assertEquals(snap.has(hex("8206f7")), refused(GenTxId.Refusal.ExpectedBytes(Era.Conway)))
    assertEquals(snap.has(hex("8208f93c00")), refused(GenTxId.Refusal.UnknownEra(8)))
    assertEquals(snap.has(hex("82f93c005820" + h)), refused(GenTxId.Refusal.EraIndex))
    // text that is not UTF-8 is not a well-formed item
    snap.has(hex("820661ff")) match
      case Left(ProtocolError.UndecodableTxId(GenTxId.Refusal.Malformed(_))) => ()
      case other => fail(s"text that is not UTF-8 is malformed: $other")
  }

  test(
    "an id in the forms the reference's decoders also read: any width of a head, chunked from 12"
  ) {
    val (t1, id1) = pay(1)
    val snap = LocalMonitor.snapshot(held(t1))
    val h = id1.hex
    // the era index and the list length at a wider head than needed
    assertEquals(snap.has(hex(s"8218065820$h")), Right(true))
    assertEquals(snap.has(hex(s"9802065820$h")), Right(true))
    assertEquals(snap.has(hex(s"82008218005820$h")), Right(true))
    // Dijkstra reads at version 12, where a chunked byte string is read with its chunks joined
    assertEquals(snap.has(hex(s"82075f5810${h.take(32)}5810${h.drop(32)}ff")), Right(true))
  }

  private val h32 = "11" * 32

  /** Prefixes the reference's decoder fails on, each with a completion and the refusal: the same
    * one [[GenTxId.decode]] gives the whole id.
    */
  private val refusedPrefixes: Vector[(String, String, GenTxId.Refusal)] = Vector(
    ("8208", s"5820$h32", GenTxId.Refusal.UnknownEra(8)),
    ("83", s"065820${h32}00", GenTxId.Refusal.EraEnvelope),
    ("9f", s"065820${h32}ff", GenTxId.Refusal.EraEnvelope),
    ("5820", h32, GenTxId.Refusal.EraEnvelope),
    ("82190100", s"5820$h32", GenTxId.Refusal.EraIndex),
    ("8220", s"5820$h32", GenTxId.Refusal.EraIndex),
    ("823b0000000000000000", s"5820$h32", GenTxId.Refusal.EraIndex),
    ("82008204", s"5820$h32", GenTxId.Refusal.UnknownByronTag(4)),
    ("820083", s"005820${h32}00", GenTxId.Refusal.ByronEnvelope),
    ("82005820", h32, GenTxId.Refusal.ByronEnvelope),
    ("820082190100", s"5820$h32", GenTxId.Refusal.ByronTag),
    ("820082005f", s"5820${h32}ff", GenTxId.Refusal.IndefiniteBytes(Era.Byron)),
    ("82065f", s"5820${h32}ff", GenTxId.Refusal.IndefiniteBytes(Era.Conway)),
    ("82015f", s"5820${h32}ff", GenTxId.Refusal.IndefiniteBytes(Era.Shelley)),
    ("820682", "0102", GenTxId.Refusal.ExpectedBytes(Era.Conway)),
    ("8206d818", s"5820$h32", GenTxId.Refusal.ExpectedBytes(Era.Conway))
  )

  /** Prefixes it waits on: the rest may still make an id it reads, or its next token, or the hash's
    * length, which is judged once the string has arrived whole, has not arrived.
    */
  private val shortPrefixes: Vector[String] = Vector(
    "",
    "82",
    "98",
    "9802",
    "8206",
    "8218",
    "821801",
    "821901",
    "823b00",
    "82075f",
    "82075f41",
    "820658",
    "8206582011",
    "8206581c11",
    "82008200",
    "8200820358",
    "8200821901"
  )

  test("a prefix of an id is refused at the token the reference's decoder fails on, not before") {
    refusedPrefixes.foreach { (prefix, completion, refusal) =>
      assertEquals(
        GenTxId.prefix(hex(prefix)),
        LocalTxMonitor.TxIdPrefix.Refused(refusal),
        s"prefix $prefix"
      )
      assertEquals(GenTxId.decode(hex(prefix + completion)), Left(refusal), s"whole $prefix")
    }
    shortPrefixes.foreach(prefix =>
      assertEquals(GenTxId.prefix(hex(prefix)), LocalTxMonitor.TxIdPrefix.Short, s"prefix $prefix")
    )
  }

  /** An id the decoder reads: every era, Byron's four constructors, the heads at any width, a
    * chunked hash in Dijkstra.
    */
  private val readIds: Gen[Bytes] =
    // an index or constructor below 24, at each width of its head
    def word(n: Int): Gen[String] =
      Gen.oneOf(f"$n%02x", f"18$n%02x", f"1900$n%02x", f"1a000000$n%02x", f"1b00000000000000$n%02x")
    val pair = Gen.oneOf("82", "9802", "990002")
    for
      era <- Gen.choose(0, 7)
      hash <- Gen.listOfN(32, Gen.choose(0, 255)).map(_.map(b => f"$b%02x").mkString)
      envelope <- pair
      index <- word(era)
      id <-
        if era == 0 then
          for
            inner <- pair
            constructor <- Gen.choose(0, 3).flatMap(word)
          yield s"$inner${constructor}5820$hash"
        else if era == 7 then
          Gen.oneOf(s"5820$hash", s"5f5810${hash.take(32)}5810${hash.drop(32)}ff")
        else Gen.const(s"5820$hash")
    yield hex(s"$envelope$index$id")

  property("no prefix of an id the decoder reads is refused") {
    forAll(readIds) { id =>
      assert(GenTxId.decode(id).isRight, id.hex)
      (0 until id.size).foreach(n =>
        assertEquals(
          GenTxId.prefix(Bytes(id.value.take(n))),
          LocalTxMonitor.TxIdPrefix.Short,
          s"${id.hex} cut at $n"
        )
      )
    }
  }

  test("the decoded id keeps its era and Byron's constructor; the hash is what is compared") {
    val id = Bytes(Vector.fill(32)(0x11.toByte))
    val hash = Hash32.fromBytes(id).toOption.get
    assertEquals(
      GenTxId.decode(tagged(6, id)),
      Right(GenTxId.Shelley(ShelleyBasedEra.SConway, hash))
    )
    assertEquals(
      GenTxId.decode(tagged(7, id)),
      Right(GenTxId.Shelley(ShelleyBasedEra.SDijkstra, hash))
    )
    assertEquals(
      GenTxId.decode(tagged(1, id)),
      Right(GenTxId.Shelley(ShelleyBasedEra.SShelley, hash))
    )
    assertEquals(GenTxId.decode(byron(0, id)), Right(GenTxId.Byron(ByronGenTxId.ByronTxId(hash))))
    assertEquals(GenTxId.decode(byron(1, id)), Right(GenTxId.Byron(ByronGenTxId.ByronDlgId(hash))))
    assertEquals(
      GenTxId.decode(byron(2, id)),
      Right(GenTxId.Byron(ByronGenTxId.ByronUpdateProposalId(hash)))
    )
    assertEquals(
      GenTxId.decode(byron(3, id)),
      Right(GenTxId.Byron(ByronGenTxId.ByronUpdateVoteId(hash)))
    )
    assertEquals(
      GenTxId.decode(byron(3, id)).map(_.hash),
      GenTxId.decode(tagged(5, id)).map(_.hash)
    )
  }

  test("the wire forms: [6, #6.24(tx)] and [6, h'id']") {
    assertEquals(LocalMonitor.wrapTx(Era.Conway, Bytes(Vector(1, 2, 3))).hex, "8206d81843010203")
    assertEquals(
      LocalMonitor.wrapId(Era.Conway, Bytes(Vector.fill(32)(0x11.toByte))).hex,
      "82065820" + "11" * 32
    )
  }

  private type Ended = Either[NetworkError, LocalTxSubmissionServer.End]

  private def send(ch: MuxChannel[IO], state: State, m: Message)(using Handle[IO, NetworkError]) =
    IO.fromEither(
      LocalTxMonitor.encode(state, Role.Client, m).left.map(e => RuntimeException(e.message))
    ).flatMap(ch.send)

  private def receive(ch: MuxChannel[IO], state: State)(using Handle[IO, NetworkError]) =
    ch.recv(LocalTxMonitor.MaxMessageBytes)(LocalTxMonitor.decodePrefix(state, Role.Server, _))

  /** One node-to-client connection to the node's LocalTxMonitor server of `snapshot`, whose client
    * acquires it and then runs `client` with the channel and how the server ended. The server runs
    * in a fiber of the connection's scope, cancelled when it closes.
    */
  private def onTheWire[A](snapshot: Snapshot)(
      client: (MuxChannel[IO], IO[Ended]) => Handle[IO, NetworkError] ?=> IO[A]
  ): IO[A] =
    val magic = 42L
    val source = new LocalTxMonitorServer.Source[IO]:
      def acquire = IO.pure(snapshot)
      def awaitChange(seen: Snapshot) = IO.never
      def txIds = GenTxId.reader
    val connection = Resource.eval(Bearer.inMemory[IO]()).flatMap { (clientEnd, serverEnd) =>
      Handle
        .allow[NetworkError](
          NodeToClientServer
            .accept(
              serverEnd,
              magic,
              _ => IO.pure(LocalTxSubmissionServer.Response.Accepted),
              GenTx.reader,
              txMonitor = Some(source)
            )
            .map(Right(_): Ended)
        )
        .rescue(e => IO.pure(Left(e)))
        .background
        .map(joined => (clientEnd, joined.flatMap(_.embedNever)))
    }
    connection.use { (clientEnd, server) =>
      Handle
        .allow[NetworkError] {
          Mux.resource(clientEnd, Set(0, 9), Mux.Config()).use { mux =>
            for
              hs <- mux.channel(0)
              _ <- HandshakeClient.run(
                hs,
                Vector(23).map(v => v -> Handshake.Data(magic)),
                suite = Handshake.Suite.NodeToClient
              )
              ch <- mux.channel(9)
              _ <- send(ch, State.Idle, Message.Acquire)
              _ <- receive(ch, State.Acquiring)
              result <- client(ch, server)
            yield result
          }
        }
        .rescue(e => IO.raiseError(RuntimeException(e.message)))
    }

  /** [[onTheWire]]: the client asks `HasTx` of each of `answered` and reads the replies, then sends
    * `request`, a `HasTx` message written as raw bytes, and waits for the server to end. The
    * replies, and how the server ended; a reply to `request` fails the test.
    */
  private def refusedOnTheWire(
      snapshot: Snapshot,
      answered: Vector[Bytes],
      request: Bytes
  ): IO[(Vector[Message], Ended)] =
    onTheWire(snapshot) { (ch, server) =>
      // the reply the server should not send, or none once the client sees the connection end
      val reply: IO[Option[Message]] =
        Handle
          .allow[NetworkError](receive(ch, State.Busy(Kind.HasTx)).map(Option(_)))
          .rescue(_ => IO.pure(None))
      for
        replies <- answered.traverse(item =>
          send(ch, State.Acquired, Message.HasTx(item)) *> receive(ch, State.Busy(Kind.HasTx))
        )
        _ <- ch.send(request)
        _ <- ch.flush
        first <- IO.race(server, reply)
        ended <- first match
          case Left(ended)         => IO.pure(ended)
          case Right(None)         => server
          case Right(Some(answer)) => IO(fail(s"a refused id was answered: $answer"))
      yield (replies, ended)
    }

  test(
    "on the wire, a held id is found under another era's tag; a refused id ends the connection"
  ) {
    val (t1, id1) = pay(1)
    val snap = LocalMonitor.snapshot(held(t1))
    val h = id1.hex
    // a 28-byte id in Conway, a bare id in Byron, an era past the last
    val shapes = Vector(
      hex("8206581c" + "11" * 28) -> GenTxId.Refusal.HashLength(
        Era.Conway,
        VocabError.WrongHashLength(VocabError.Kind.Hash32, 32, 28)
      ),
      hex(s"82005820$h") -> GenTxId.Refusal.ByronEnvelope,
      hex(s"82085820$h") -> GenTxId.Refusal.UnknownEra(8)
    )
    shapes.traverse { (shape, refusal) =>
      refusedOnTheWire(
        snap,
        Vector(tagged(5, id1), byron(0, id1), tagged(6, Bytes(Vector.fill(32)(7.toByte)))),
        hex("8207") ++ shape
      ).map((replies, ended) =>
        assertEquals(
          replies,
          Vector(Message.ReplyHasTx(true), Message.ReplyHasTx(true), Message.ReplyHasTx(false))
        )
        assertEquals(
          ended,
          Left(
            NetworkError.Decode(Protocols.LocalTxMonitor, ProtocolError.UndecodableTxId(refusal))
          )
        )
      )
    }.void
  }

  test(
    "on the wire, a prefix of an id the decoder fails on ends the connection before the rest arrives"
  ) {
    val (t1, id1) = pay(1)
    val snap = LocalMonitor.snapshot(held(t1))
    // an era past the last, an envelope of three, Byron's fifth constructor: the id has not ended
    val prefixes = Vector(
      "82078208" -> GenTxId.Refusal.UnknownEra(8),
      "820783" -> GenTxId.Refusal.EraEnvelope,
      "820782008204" -> GenTxId.Refusal.UnknownByronTag(4)
    )
    prefixes.traverse { (prefix, refusal) =>
      refusedOnTheWire(snap, Vector(tagged(6, id1)), hex(prefix)).map((replies, ended) =>
        assertEquals(replies, Vector(Message.ReplyHasTx(true)), prefix)
        assertEquals(
          ended,
          Left(
            NetworkError.Decode(Protocols.LocalTxMonitor, ProtocolError.UndecodableTxId(refusal))
          ),
          prefix
        )
      )
    }.void
  }

  test("on the wire, a prefix of an id the decoder reads waits for the rest and is answered") {
    val (t1, id1) = pay(1)
    val snap = LocalMonitor.snapshot(held(t1))
    // the era-6 envelope alone, then the hash's head and half the hash, then the rest
    val whole = hex("8207") ++ tagged(6, id1)
    val parts = Vector(whole.slice(0, 4), whole.slice(4, 22), whole.slice(22, whole.size))
    onTheWire(snap) { (ch, _) =>
      parts.traverse_(part => ch.send(part) *> ch.flush) *> receive(ch, State.Busy(Kind.HasTx))
    }.map(reply => assertEquals(reply, Message.ReplyHasTx(true)))
  }

  test(
    "a transaction counts for its ledger size plus 4 bytes, and the sizes are those of the snapshot"
  ) {
    val (t1, _) = pay(1)
    val (t2, _) = pay(2)
    val snap = LocalMonitor.snapshot(held(t1, t2))
    // the ledger's `sizeTxF` leaves out the one byte of the is_valid flag (devnet: 200 of 201 bytes)
    val (s1, s2) = (t1.size - 1L, t2.size - 1L)
    assertEquals(snap.txs.map(_.measure.bytes), Vector(s1 + 4, s2 + 4))
    assertEquals(snap.sizes.sizeInBytes, s1 + s2 + 8L)
    assertEquals(snap.sizes.numberOfTxs, 2L)
    assertEquals(snap.txs.map(_.measure.exUnitsMemory), Vector(BigInt(0), BigInt(0)))
  }

  test(
    "the capacity is a whole number of block units: 2 by default, ceil(bytes / unit) otherwise"
  ) {
    val params = ledger(1).params
    val unit = params.maxBlockBodySize.toLong - 1024
    assertEquals(unit, 64512L)
    val one = Capacity.blockUnit(params)
    assertEquals(one.refScriptsBytes, 1048576L)
    assertEquals(Capacity.of(params, None).bytes, 2 * unit)
    assertEquals(Capacity.of(params, Some(word32(2 * unit))).bytes, 2 * unit)
    assertEquals(Capacity.of(params, Some(word32(2 * unit - 1))).bytes, 2 * unit)
    assertEquals(Capacity.of(params, Some(word32(2 * unit + 1))).bytes, 3 * unit)
    assertEquals(Capacity.of(params, Some(word32(1))).bytes, unit)
    assertEquals(Capacity.of(params, Some(word32(0))).bytes, unit)
    assertEquals(Capacity.of(params, Some(word32(2 * unit))).refScriptsBytes, 2L * 1048576)
    assertEquals(LocalMonitor.snapshot(pool()).sizes.capacityInBytes, 2 * unit)
    assertEquals(LocalMonitor.snapshot(pool(Some(1))).sizes.capacityInBytes, unit)
  }

  property("capacity is monotone in the byte capacity and never below one unit") {
    forAll(Gen.choose(0L, 5000000L), Gen.choose(0L, 5000000L)) { (a, b) =>
      val params = ledger(1).params
      val (lo, hi) = (a.min(b), a.max(b))
      val cl = Capacity.of(params, Some(word32(lo)))
      val ch = Capacity.of(params, Some(word32(hi)))
      cl.bytes <= ch.bytes && cl.bytes >= Capacity.blockUnit(params).bytes &&
      ch.bytes >= hi
    }
  }

  test("GetMeasures counts the transactions NextTx has not handed out, GetSizes all of them") {
    val (t1, _) = pay(1)
    val (t2, _) = pay(2)
    val snap = LocalMonitor.snapshot(held(t1, t2))
    val all = LocalTxMonitorServer.measures(snap.txs, snap.capacity)
    assertEquals(all.txCount, 2L)
    assertEquals(all.byName.keys.toList, LocalTxMonitorServer.MeasureNames)
    assertEquals(all.byName("transaction_bytes").size, BigInt(t1.size - 1 + t2.size - 1 + 8))
    val rest = LocalTxMonitorServer.measures(snap.txs.drop(1), snap.capacity)
    assertEquals(rest.txCount, 1L)
    assertEquals(rest.byName("transaction_bytes").size, BigInt(t2.size - 1 + 4))
    assertEquals(rest.byName("transaction_bytes").capacity, BigInt(snap.capacity.bytes))
  }

  test("a client awaiting a new snapshot is woken by a transaction and by a new tip") {
    val (t1, id1) = pay(1)
    Settled { settle =>
      for
        mempool <- Mempool.create[IO](Right(ledger(4)), 1000, env, None, MempoolEvents.none[IO])
        source = LocalMonitor.source(mempool)
        first <- source.acquire
        waiting <- source.awaitChange(first).start
        // the client waits for the pool to change
        _ <- settle
        _ <- mempool.add(t1, Source.Local)
        second <- waiting.joinWithNever
        again <- source.awaitChange(second).start
        _ <- settle
        _ <- mempool.sync(Right(ledger(4)), 1001)
        third <- again.joinWithNever
      yield
        assertEquals(first.txs.size, 0)
        assertEquals(second.txs.size, 1)
        assertEquals(second.has(LocalMonitor.wrapId(Era.Conway, id1)), Right(true))
        assertEquals(second.slot, BigInt(1001))
        // the transaction still applies at the new tip, only the slot moved
        assertEquals(third.slot, BigInt(1002))
        assertEquals(third.txs.size, 1)
    }
  }

  test("awaiting a snapshot the pool already differs from returns at once") {
    val (t1, _) = pay(1)
    for
      mempool <- Mempool.create[IO](Right(ledger(4)), 1000, env, None, MempoolEvents.none[IO])
      source = LocalMonitor.source(mempool)
      stale <- source.acquire
      _ <- mempool.add(t1, Source.Local)
      now <- source.awaitChange(stale).timeout(2.seconds)
    yield assertEquals(now.txs.size, 1)
  }
