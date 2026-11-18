// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.effect.{IO, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.{
  Bearer,
  HandshakeClient,
  LocalStateQueryServer,
  LocalTxSubmissionServer,
  Mux,
  MuxChannel,
  NetworkError,
  NodeToClientServer
}
import xyz.kd5ujc.scarab.protocol.{Handshake, LocalStateQuery, ProtocolError, Protocols}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.{Message, QueryPrefix, State, Target}

import NtcQueryPrefix.{Level, Refusal}

/** A LocalStateQuery query judged while it has not all arrived (#924): refused at the token where
  * the reference's decoder of queries fails, which [[NtcQuery.decode]] refuses whole too, and
  * waiting everywhere else.
  */
class NtcQueryPrefixSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ScalaCheckSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def short(prefix: Bytes): Boolean = NtcQueryPrefix.prefix(prefix) == QueryPrefix.Short

  /** `[0, [0, [era, q]]]`, up to `q`. */
  private def inEra(era: Int): String = f"8200820082$era%02x"

  // (query prefix, a completion of it, the refusal)
  private val refused = Vector(
    ("820f", "00", Refusal.Constructor(Level.Query, 2, 15)),
    ("8300", "0000", Refusal.Constructor(Level.Query, 3, 0)),
    ("8204", "00", Refusal.Constructor(Level.Query, 2, 4)),
    ("9f", "00ff", Refusal.Envelope(Level.Query)),
    ("a1", "0000", Refusal.Envelope(Level.Query)),
    ("8220", "00", Refusal.Word8(Level.Query)),
    ("82190100", "00", Refusal.Word8(Level.Query)),
    ("82008300", "0000", Refusal.Constructor(Level.BlockQuery, 3, 0)),
    ("82008203", "00", Refusal.Constructor(Level.BlockQuery, 2, 3)),
    ("82009f", "00ff", Refusal.Envelope(Level.BlockQuery)),
    ("8200820083", "060000", Refusal.Size(Level.EraQuery, 3)),
    ("820082009f", "0600ff", Refusal.Envelope(Level.EraQuery)),
    ("8200820082" + "08", "8101", Refusal.UnknownEra(Level.EraQuery, 8)),
    ("8200820082" + "190100", "8101", Refusal.Word8(Level.EraQuery)),
    ("8200830182", "0000" + "06", Refusal.Size(Level.Anytime, 2)),
    ("820083018101", "06", Refusal.UnknownTag(Level.Anytime, 1)),
    ("82008301810008", "", Refusal.UnknownEra(Level.AnytimeEra, 8)),
    ("8200820282", "0000", Refusal.Size(Level.HardFork, 2)),
    ("820082028102", "", Refusal.UnknownTag(Level.HardFork, 2)),
    (inEra(0) + "82", "0000", Refusal.Word8(Level.Byron)),
    (inEra(0) + "01", "", Refusal.UnknownTag(Level.Byron, 1)),
    (inEra(6) + "8300", "0000", Refusal.Constructor(Level.Ledger, 3, 0)),
    (inEra(6) + "821828", "00", Refusal.Constructor(Level.Ledger, 2, 40)),
    (inEra(6) + "810f", "", Refusal.Constructor(Level.Ledger, 1, 15)),
    (inEra(6) + "9f", "00ff", Refusal.Envelope(Level.Ledger)),
    (inEra(5) + "82181a", "80", Refusal.ConwayOnly(26)),
    (inEra(1) + "8117", "", Refusal.ConwayOnly(23)),
    (inEra(6) + "8209821828", "00", Refusal.Constructor(Level.Ledger, 2, 40)),
    (inEra(6) + "82098209" + "8220", "00", Refusal.Word8(Level.Ledger)),
    (inEra(4) + "8209" + "82181a", "80", Refusal.ConwayOnly(26)),
    (inEra(6) + "82182282", "0000", Refusal.Word8(Level.PeerKind)),
    (inEra(6) + "82182202", "", Refusal.UnknownTag(Level.PeerKind, 2))
  )

  test("a prefix the reference's decoder fails on is refused there, and refused whole too") {
    refused.foreach { (prefix, completion, refusal) =>
      assertEquals(NtcQueryPrefix.prefix(hex(prefix)), QueryPrefix.Refused(refusal), prefix)
      (0 until hex(prefix).size).foreach(n =>
        assert(short(hex(prefix).take(n)), s"$prefix cut at $n")
      )
      assert(NtcQuery.decode(hex(prefix + completion)).isLeft, s"whole $prefix$completion")
    }
  }

  test("a token whose head has not all arrived, or a size whose tag has not, waits") {
    Vector(
      "",
      "83",
      "82",
      "8219",
      "9b00000000",
      "8200",
      "820083",
      "82008200",
      "8200820082",
      "820082008219",
      "82008301",
      "8200830181",
      "820083018100",
      "8200820281",
      inEra(6),
      inEra(6) + "83",
      inEra(6) + "8218",
      inEra(6) + "8209",
      inEra(6) + "820982",
      inEra(6) + "820f",
      inEra(6) + "82181a",
      inEra(7) + "82181a",
      inEra(6) + "821822",
      inEra(6) + "8418",
      inEra(0),
      inEra(0) + "19"
    )
      .foreach(prefix => assert(short(hex(prefix)), s"prefix $prefix"))
  }

  /** Each ledger query `decodeShelleyQuery` has a case for (`Shelley/Ledger/Query.hs:944-998`,
    * ouroboros-consensus 82ecba32) but `GetCBOR`, whole: its tag and one argument of each that
    * takes any, read by every era from Shelley on (an empty set, tagged where `fromCBOR` reads it
    * and plain where the ledger's decoder does; no set of pools; a pool's key hash; a peer kind).
    */
  private val ledgerQueries: Vector[(Int, String)] = Vector(
    0 -> "8100",
    1 -> "8101",
    2 -> "8202d9010280",
    3 -> "8103",
    4 -> "8104",
    5 -> "8105",
    6 -> "820680",
    7 -> "8107",
    8 -> "8108",
    10 -> "820a80",
    11 -> "810b",
    12 -> "810c",
    13 -> "810d",
    14 -> "810e",
    15 -> "820f80",
    16 -> "8110",
    17 -> "8211d9010280",
    18 -> "8112",
    19 -> "821380",
    20 -> "821480",
    21 -> "821580",
    22 -> "8216d9010280",
    23 -> "8117",
    24 -> "811818",
    25 -> "821819d9010280",
    26 -> "82181a80",
    27 -> "84181bd9010280d901028080",
    28 -> "82181c80",
    29 -> "81181d",
    30 -> "82181e80",
    31 -> "82181f80",
    32 -> "811820",
    33 -> "811821",
    34 -> "811822",
    34 -> "82182201",
    35 -> ("821823581c" + "ab" * 28),
    36 -> "82182480",
    37 -> "811825",
    38 -> "811826",
    39 -> "82182780"
  )

  /** The ledger queries of [[ledgerQueries]] an era reads: Conway's governance from Conway on. */
  private def ledgerQueriesOf(era: Int): Vector[String] =
    ledgerQueries.collect {
      case (tag, query) if era >= 6 || !NtcQuery.governanceQueries(tag) => query
    }

  /** A whole query the node reads: every top-level and hard-fork query, Byron's, and each era's
    * ledger queries, bare or under `GetCBOR`.
    */
  private val queries: Gen[Bytes] =
    val top = Gen.oneOf(
      Vector(
        "8101",
        "8102",
        "8103",
        "8104",
        "820082028100",
        "820082028101",
        "82008301810006",
        "82008301810000"
      )
    )
    val ledger = for
      era <- Gen.choose(1, 7)
      query <- Gen.oneOf(ledgerQueriesOf(era))
      layers <- Gen.choose(0, 3)
    yield inEra(era) + "8209" * layers + query
    val byron = Gen.const(inEra(0) + "00")
    Gen.oneOf(top, ledger, byron).map(hex)

  property("no prefix of a query the node reads is refused") {
    forAll(queries) { query =>
      assert(NtcQuery.decode(query).isRight, s"${query.hex}: ${NtcQuery.decode(query)}")
      (0 until query.size).foreach(n => assert(short(query.take(n)), s"${query.hex} cut at $n"))
    }
  }

  test("the ledger queries judged are the ones the whole read takes, in every era") {
    // every size and tag: one the judge lets through has a completion the whole read takes, and
    // one it refuses is refused whole, whatever its arguments
    val arguments = Vector("", "80", "d9010280", "00", "581c" + "ab" * 28)
    for era <- 1 to 7; size <- 0 to 5; tag <- 0 to 63 do
      val head = f"${0x80 + size}%02x" + (if tag < 24 then f"$tag%02x" else f"18$tag%02x")
      if short(hex(inEra(era) + head)) then
        val readable = ledgerQueriesOf(era).filter(_.startsWith(head))
        assert(readable.nonEmpty || (size, tag) == (2, 9), s"era $era ($size, $tag)")
        readable.foreach(q => assert(NtcQuery.decode(hex(inEra(era) + q)).isRight, q))
      else
        arguments.foreach { a =>
          val whole = inEra(era) + head + a * math.max(0, size - 1)
          assert(NtcQuery.decode(hex(whole)).isLeft, s"era $era ($size, $tag) $whole")
        }
  }

  test("the codec asks the judge only while a query has not all arrived") {
    def decoded(wire: Bytes) =
      LocalStateQuery.decodePrefix(State.Acquired, Role.Client, wire, NtcQuery.reader)
    // the task's rows of #924
    assertEquals(
      decoded(hex("8203820f")),
      DecodeResult.Failed(
        ProtocolError.UndecodableQueryPrefix(Refusal.Constructor(Level.Query, 2, 15))
      )
    )
    assertEquals(
      decoded(hex("8203" + "8200820082" + "08")),
      DecodeResult.Failed(
        ProtocolError.UndecodableQueryPrefix(Refusal.UnknownEra(Level.EraQuery, 8))
      )
    )
    // a whole query the decoder refuses is framed as before, and refused when it is read
    val whole = hex("8203820f00")
    assertEquals(decoded(whole), DecodeResult.Decoded(Message.Query(hex("820f00")), whole.size))
  }

  private def send(ch: MuxChannel[IO], state: State, m: Message)(using
      Handle[IO, NetworkError]
  ): IO[Unit] =
    LocalStateQuery
      .encode(state, Role.Client, m)
      .fold(error => IO(fail(error.message)), ch.send)

  private def receive(ch: MuxChannel[IO], state: State)(using Handle[IO, NetworkError]) =
    ch.recv(LocalStateQuery.MaxResultBytes)(LocalStateQuery.decodePrefix(state, Role.Server, _))

  private type Ended = Either[NetworkError, LocalTxSubmissionServer.End]

  /** A node-to-client connection whose LocalStateQuery server reads queries as the node does
    * ([[NtcQuery.reader]]) and answers each with `[]`; the client acquires the tip and runs
    * `client` with the channel and how the server ended.
    */
  private def onTheWire[A](
      client: (MuxChannel[IO], IO[Ended]) => Handle[IO, NetworkError] ?=> IO[A]
  ): IO[A] =
    val magic = 42L
    val source = new LocalStateQueryServer.Source[IO, Nothing]:
      def queries = NtcQuery.reader
      def acquire(target: Target) = Resource.pure(
        Right(
          new LocalStateQueryServer.Session[IO, Nothing]:
            def answer(query: Bytes) = IO.pure(Right(Right(hex("80"))))
        )
      )
    val connection = Resource.eval(Bearer.inMemory[IO]()).flatMap { (clientEnd, serverEnd) =>
      Handle
        .allow[NetworkError](
          NodeToClientServer
            .accept(
              serverEnd,
              magic,
              _ => IO.pure(LocalTxSubmissionServer.Response.Accepted),
              xyz.kd5ujc.scarab.node.mempool.GenTx.reader,
              Some(source)
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
          Mux.resource(clientEnd, Set(0, 7), Mux.Config()).use { mux =>
            for
              hs <- mux.channel(0)
              _ <- HandshakeClient.run(
                hs,
                Vector(23).map(v => v -> Handshake.Data(magic)),
                suite = Handshake.Suite.NodeToClient
              )
              ch <- mux.channel(7)
              _ <- send(ch, State.Idle, Message.Acquire(Target.VolatileTip))
              _ <- receive(ch, State.Acquiring)
              result <- client(ch, server)
            yield result
          }
        }
        .rescue(e => IO.raiseError(RuntimeException(e.message)))
    }

  test("on the wire, a query prefix the decoder fails on ends the connection at once") {
    Vector(
      "8203820f" -> Refusal.Constructor(Level.Query, 2, 15),
      ("8203" + "8200820082" + "08") -> Refusal.UnknownEra(Level.EraQuery, 8)
    ).traverse { (prefix, refusal) =>
      onTheWire { (ch, server) =>
        val reply: IO[Option[Message]] =
          Handle
            .allow[NetworkError](receive(ch, State.Querying).map(Option(_)))
            .rescue(_ => IO.pure(None))
        for
          _ <- ch.send(hex(prefix)) *> ch.flush
          first <- IO.race(server, reply)
          ended <- first match
            case Left(ended)         => IO.pure(ended)
            case Right(None)         => server
            case Right(Some(answer)) => IO(fail(s"$prefix was answered: $answer"))
        yield assertEquals(
          ended,
          Left(
            NetworkError.Decode(
              Protocols.LocalStateQuery,
              ProtocolError.UndecodableQueryPrefix(refusal)
            )
          ),
          prefix
        )
      }
    }.void
  }

  test("on the wire, a query split at each envelope waits for the rest and is answered") {
    val wire = hex("8203" + inEra(6) + "8209" + "820f" + "80")
    val parts = Vector(wire.take(4), wire.slice(4, 8), wire.slice(8, 10), wire.drop(10))
    onTheWire((ch, _) =>
      parts.traverse_(part => ch.send(part) *> ch.flush) *> receive(ch, State.Querying)
    ).map(reply => assertEquals(reply, Message.Result(hex("80"))))
  }
