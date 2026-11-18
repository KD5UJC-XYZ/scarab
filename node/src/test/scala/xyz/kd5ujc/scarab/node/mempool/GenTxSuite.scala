// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{IO, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node}
import xyz.kd5ujc.scarab.ledger.TransactionDecodeProblem
import xyz.kd5ujc.scarab.network.{
  Bearer,
  HandshakeClient,
  LocalTxSubmissionServer,
  Mux,
  MuxChannel,
  NetworkError,
  NodeToClientServer
}
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.protocol.{Era, Handshake, LocalTxSubmission, ProtocolError, Protocols}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}

import TxKit.*

/** A LocalTxSubmission transaction judged while it has not all arrived (#924): refused where the
  * reference's decoder of a `GenTx` fails and the node refuses the message whole too, waiting
  * everywhere else.
  */
class GenTxSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ScalaCheckSuite:
  private given Assertions[IO] = Assertions.noop[IO]
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  /** What the node makes of a whole `GenTx`: read, then added to a pool of a Conway ledger. */
  private def admitted(item: Bytes): Either[Rejection, Bytes] =
    LocalSubmission
      .unwrap(item)
      .flatMap(MempoolState.empty(Right(ledger(4)), 1000, None).add(_, env, Source.Local))
      .map(_._2.id)

  private val (tx, txId) = pay(1)

  /** `#6.24(h'tx')` */
  private val wrappedTx: String = "d818" + Bytes(bstr(tx)).hex

  /** `tx` as a byte string of two chunks. */
  private val chunkedTx: String =
    val half = tx.size / 2
    "5f" + Bytes(bstr(tx.take(half))).hex + Bytes(bstr(tx.drop(half))).hex + "ff"

  private def short(prefix: Bytes): Boolean =
    GenTx.prefix(prefix) == LocalTxSubmission.TxPrefix.Short

  // (prefix, a completion of it, the refusal): every completion of a refused prefix is refused
  // whole too, so whether a transaction is admitted does not depend on how its bytes arrive; a tag
  // other than 24 is refused around a byte string and around the transaction itself
  private val refused = Vector(
    ("8208", wrappedTx, GenTx.Refusal.UnknownEra(8)),
    ("82190100", wrappedTx, GenTx.Refusal.EraIndex),
    ("83", "06" + wrappedTx + "00", GenTx.Refusal.EraEnvelope),
    ("81", "06", GenTx.Refusal.EraEnvelope),
    ("a1", "06" + wrappedTx, GenTx.Refusal.EraEnvelope),
    ("8220", wrappedTx, GenTx.Refusal.EraIndex),
    ("8241", "06" + wrappedTx, GenTx.Refusal.EraIndex),
    ("82008204", "5820" + "11" * 32, GenTx.Refusal.UnknownByronTag(4)),
    ("820083", "00" + wrappedTx + "00", GenTx.Refusal.ByronEnvelope),
    ("8200d818", Bytes(bstr(tx)).hex, GenTx.Refusal.ByronEnvelope),
    ("82009f", "00" + "5820" + "11" * 32 + "ff", GenTx.Refusal.ByronEnvelope),
    ("82008220", "5820" + "11" * 32, GenTx.Refusal.ByronTag),
    ("8206d819", Bytes(bstr(tx)).hex, GenTx.Refusal.NotCborInCbor(Era.Conway)),
    ("8201d81e", Bytes(bstr(tx)).hex, GenTx.Refusal.NotCborInCbor(Era.Shelley)),
    ("8206d819", tx.hex, GenTx.Refusal.NotCborInCbor(Era.Conway)),
    ("8206c1", tx.hex, GenTx.Refusal.NotCborInCbor(Era.Conway)),
    ("8206d81884", tx.drop(1).hex, GenTx.Refusal.ExpectedBytes(Era.Conway)),
    ("8206d8187f", "60ff", GenTx.Refusal.ExpectedBytes(Era.Conway)),
    ("820641", "aa", GenTx.Refusal.ExpectedTag(Era.Conway)),
    ("8206a1", "0000", GenTx.Refusal.ExpectedTag(Era.Conway)),
    ("820661", "61", GenTx.Refusal.ExpectedTag(Era.Conway)),
    ("8206f5", "", GenTx.Refusal.ExpectedTag(Era.Conway)),
    ("82063b" + "00" * 8, "", GenTx.Refusal.ExpectedTag(Era.Conway)),
    ("820784", tx.drop(1).hex, GenTx.Refusal.ExpectedTag(Era.Dijkstra)),
    ("82079f", tx.drop(1).hex + "ff", GenTx.Refusal.ExpectedTag(Era.Dijkstra)),
    ("8207d8185f", chunkedTx.drop(2), GenTx.Refusal.ExpectedBytes(Era.Dijkstra)),
    ("8207d819", Bytes(bstr(tx)).hex, GenTx.Refusal.NotCborInCbor(Era.Dijkstra)),
    ("9f", "06" + wrappedTx + "ff", GenTx.Refusal.EraEnvelope),
    // the transaction not under tag 24, and a chunked string under it
    ("820684", tx.drop(1).hex, GenTx.Refusal.ExpectedTag(Era.Conway)),
    ("82069f", tx.drop(1).hex + "ff", GenTx.Refusal.ExpectedTag(Era.Conway)),
    ("8201d8185f", chunkedTx.drop(2), GenTx.Refusal.ExpectedBytes(Era.Shelley)),
    ("8206d8185f", chunkedTx.drop(2), GenTx.Refusal.ExpectedBytes(Era.Conway))
  )

  test(
    "a prefix the reference's decoder fails on is refused there, and the node refuses it whole"
  ) {
    assertEquals(tx.take(1), hex("84"), "a transaction of four parts")
    refused.foreach { (prefix, completion, refusal) =>
      assertEquals(
        GenTx.prefix(hex(prefix)),
        LocalTxSubmission.TxPrefix.Refused(refusal),
        s"prefix $prefix"
      )
      // no shorter prefix is refused: the decoder fails at the token the prefix ends with
      (0 until hex(prefix).size).foreach(n =>
        assert(short(hex(prefix).take(n)), s"$prefix cut at $n")
      )
      assert(admitted(hex(prefix + completion)).isLeft, s"whole $prefix$completion")
    }
  }

  test("a token whose head has not all arrived waits") {
    Vector(
      "",
      "82",
      "98",
      "8219",
      "821901",
      "8206",
      "8206d8",
      "8206d818",
      "8206d81858",
      "8206d8185a0000",
      "8206d900",
      "8200",
      "820082",
      "82008219",
      "8200821900",
      "82073b0000",
      "9b00000000"
    )
      .foreach(prefix => assert(short(hex(prefix)), s"prefix $prefix"))
  }

  /** A Conway transaction in each form the reference reads: each head at any of its widths (the
    * envelope's length, the era index, tag 24 and the length of the definite string under it), as
    * cborg reads them (`decodeListLen`, `decodeWord8`, `decodeTag`, `decodeBytes`).
    */
  private val referenceForms: Gen[Bytes] =
    for
      envelope <- Gen.oneOf("82", "9802", "990002", "9a00000002", "9b0000000000000002")
      index <- Gen.oneOf("06", "1806", "190006", "1a00000006", "1b0000000000000006")
      tag <- Gen.oneOf("d818", "d90018", "da00000018", "db0000000000000018")
      length <- Gen.oneOf(
        Bytes(head(2, tx.size)).hex,
        f"59${tx.size}%04x",
        f"5a${tx.size}%08x",
        f"5b${tx.size}%016x"
      )
    yield hex(envelope + index + tag + length + tx.hex)

  property("a transaction in a form the reference reads is admitted, and no prefix is refused") {
    forAll(referenceForms) { item =>
      assertEquals(admitted(item), Right(txId), item.hex)
      (0 until item.size).foreach(n => assert(short(item.take(n)), s"${item.hex} cut at $n"))
    }
  }

  test("a form the reference refuses at its first token is refused there and whole") {
    // an indefinite envelope, a transaction not under tag 24, a chunked string under it
    Vector(
      (
        "9f06" + wrappedTx + "ff",
        1,
        GenTx.Refusal.EraEnvelope,
        Rejection.Malformed(
          TransactionDecodeProblem.EraEnvelope(Node.NotSized.Indefinite)
        )
      ),
      (
        "8206" + tx.hex,
        3,
        GenTx.Refusal.ExpectedTag(Era.Conway),
        Rejection.Malformed(
          TransactionDecodeProblem.NotCborInCbor(
            Cbor
              .decode(tx, Cbor.Limits.Unbounded)
              .fold(e => fail(e.message), _.value)
          )
        )
      ),
      (
        "8206d818" + chunkedTx,
        5,
        GenTx.Refusal.ExpectedBytes(Era.Conway),
        Rejection.Malformed(
          TransactionDecodeProblem.ChunkedPayload
        )
      )
    ).foreach { (whole, at, refusal, rejection) =>
      assertEquals(GenTx.prefix(hex(whole).take(at)), LocalTxSubmission.TxPrefix.Refused(refusal))
      assert(short(hex(whole).take(at - 1)), s"$whole cut at ${at - 1}")
      assertEquals(admitted(hex(whole)), Left(rejection), whole)
    }
  }

  test("a transaction of every era the reference reads waits until it has arrived") {
    // eras 1 to 7 under CBOR-in-CBOR, and Byron's four kinds of payload
    val forms = (1 to 7).map(era => f"82$era%02x" + wrappedTx) ++
      (0 to 3).map(constructor => f"820082$constructor%02x" + "8200")
    forms.foreach(item =>
      (0 until hex(item).size).foreach(n => assert(short(hex(item).take(n)), s"$item cut at $n"))
    )
  }

  private type Ended = Either[NetworkError, LocalTxSubmissionServer.End]

  private def receive(ch: MuxChannel[IO])(using Handle[IO, NetworkError]) =
    ch.recv(LocalTxSubmission.MaxMessageBytes)(
      LocalTxSubmission.decodePrefix(LocalTxSubmission.State.Busy, Role.Server, _)
    )

  /** One node-to-client connection to a node whose pool holds a Conway ledger, its
    * LocalTxSubmission served as the node serves it ([[GenTx.reader]]); the client runs `client`
    * with the channel and how the server ended. The server runs in a fiber of the connection's
    * scope.
    */
  private def onTheWire[A](
      client: (MuxChannel[IO], IO[Ended]) => Handle[IO, NetworkError] ?=> IO[A]
  ): IO[A] =
    val magic = 42L
    val connection = for
      mempool <- Resource.eval(
        Mempool.create[IO](Right(ledger(4)), 1000, env, None, MempoolEvents.none[IO])
      )
      ends <- Resource.eval(Bearer.inMemory[IO]())
      (clientEnd, serverEnd) = ends
      joined <- Handle
        .allow[NetworkError](
          NodeToClientServer
            .accept(
              serverEnd,
              magic,
              LocalSubmission.handler(mempool)(failure => IO(fail(s"unexpected fatal: $failure"))),
              GenTx.reader
            )
            .map(Right(_): Ended)
        )
        .rescue(e => IO.pure(Left(e)))
        .background
    yield (clientEnd, joined.flatMap(_.embedNever))
    connection.use { (clientEnd, server) =>
      Handle
        .allow[NetworkError] {
          Mux.resource(clientEnd, Set(0, 6), Mux.Config()).use { mux =>
            for
              hs <- mux.channel(0)
              _ <- HandshakeClient.run(
                hs,
                Vector(23).map(v => v -> Handshake.Data(magic)),
                suite = Handshake.Suite.NodeToClient
              )
              ch <- mux.channel(6)
              result <- client(ch, server)
            yield result
          }
        }
        .rescue(e => IO.raiseError(RuntimeException(e.message)))
    }

  /** Each part of a message sent as an SDU of its own. */
  private def sendParts(ch: MuxChannel[IO], parts: Vector[Bytes])(using
      Handle[IO, NetworkError]
  ): IO[Unit] =
    parts.traverse_(part => ch.send(part) *> ch.flush)

  test("on the wire, a prefix the decoder fails on ends the connection before the rest arrives") {
    // the task's rows of #924: an era past the last, and an envelope of three and Byron's fifth
    // constructor as LocalTxMonitor's probe had them
    Vector(
      "82008208" -> GenTx.Refusal.UnknownEra(8),
      "820083" -> GenTx.Refusal.EraEnvelope,
      "820082008204" -> GenTx.Refusal.UnknownByronTag(4),
      "82008206d819" -> GenTx.Refusal.NotCborInCbor(Era.Conway),
      // the three forms the reference refuses at their first token
      "82009f" -> GenTx.Refusal.EraEnvelope,
      "8200820684" -> GenTx.Refusal.ExpectedTag(Era.Conway),
      "82008206d8185f" -> GenTx.Refusal.ExpectedBytes(Era.Conway)
    ).traverse { (prefix, refusal) =>
      onTheWire { (ch, server) =>
        // the reply the server should not send, or none once the client sees the connection end
        val reply: IO[Option[LocalTxSubmission.Message]] =
          Handle.allow[NetworkError](receive(ch).map(Option(_))).rescue(_ => IO.pure(None))
        for
          _ <- sendParts(ch, Vector(hex(prefix)))
          first <- IO.race(server, reply)
          ended <- first match
            case Left(ended)         => IO.pure(ended)
            case Right(None)         => server
            case Right(Some(answer)) => IO(fail(s"$prefix was answered: $answer"))
        yield assertEquals(
          ended,
          Left(
            NetworkError.Decode(
              Protocols.LocalTxSubmission,
              ProtocolError.UndecodableTransaction(refusal)
            )
          ),
          prefix
        )
      }
    }.void
  }

  test("on the wire, a transaction split after its era or inside its tag is admitted") {
    val wrapped = hex("820082") ++ hex("06" + wrappedTx)
    Vector(
      Vector(wrapped.take(4), wrapped.drop(4)),
      Vector(wrapped.take(5), wrapped.drop(5)),
      Vector(wrapped.take(6), wrapped.slice(6, 9), wrapped.drop(9))
    ).traverse(parts =>
      onTheWire((ch, _) => sendParts(ch, parts) *> receive(ch))
        .map(reply => assertEquals(reply, LocalTxSubmission.Message.AcceptTx))
    ).void
  }

  test("on the wire, a whole message of an era past the last is answered as before") {
    // the reference ends the connection here too; the node's whole read answers the era mismatch
    onTheWire((ch, _) => sendParts(ch, Vector(hex("82008208" + wrappedTx))) *> receive(ch))
      .map {
        case LocalTxSubmission.Message.RejectTx(_) => ()
        case other                                 => fail(s"expected the era mismatch, got $other")
      }
  }

  test("the judge reads the transaction's first tokens only: the codec agrees at every cut") {
    val message = hex("8200" + "8206" + wrappedTx)
    (0 to message.size).foreach { n =>
      val prefix = message.take(n)
      assertEquals(
        LocalTxSubmission
          .incremental(LocalTxSubmission.State.Idle, Role.Client, GenTx.reader)
          .decodePrefix(prefix),
        LocalTxSubmission.decodePrefix(LocalTxSubmission.State.Idle, Role.Client, prefix),
        s"cut at $n"
      )
      if n < message.size then
        assertEquals(
          LocalTxSubmission.decodePrefix(
            LocalTxSubmission.State.Idle,
            Role.Client,
            prefix,
            GenTx.reader
          ),
          DecodeResult.NeedMore,
          s"cut at $n"
        )
    }
  }
