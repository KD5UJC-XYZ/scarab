// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import cats.mtl.Raise
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{ConwayTransactionCases, DetachedRecordsReferenceSuite}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.protocol.{Era, ProtocolError, TxSubmission2, TxSubmission2Fixtures}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role

import DetachedRecordsReferenceSuite.{Kind, Row}
import Peers.typed
import TxSubmission2.{Message, State}

/** The node-to-node transaction submission reads a Conway transaction as the reference's ledger
  * decoder does (#702): a transaction holding a `RecD` record written as an empty indefinite list
  * `9f ff` with its fields after it is one transaction, in a reply the node takes and in one it
  * sends, and its id is the hash of the body span the reference reads (`decodeCount (RecD cn) n`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`, cardano-ledger
  * f649f975; `txIdTx`, `Shelley/Ledger/Mempool.hs:204-206`, ouroboros-consensus 82ecba32).
  *
  * The Conway transactions of `fixtures/reference/recd-detached/cases.jsonl` carry the verdict of
  * the reference's decoder and, for an accepted one, its id.
  */
class RelayedTransactionsSuite extends FailsOnUncaught:
  private given TxSubmission2.TransactionReader = RelayedTransactions

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private val emptyId = hex("d36a2619a672494604e11bb447cbcf5231e9f2ba25c2169177edc941bd50ad6c")

  private val rows: Vector[Row] =
    DetachedRecordsReferenceSuite.load.filter(r =>
      r.kind == Kind.Transaction && r.era == Era.Conway
    )
  private val accepted = rows.filter(_.accepted)

  private def reply(tx: Bytes): Bytes =
    TxSubmission2
      .encode(State.Txs, Role.Client, Message.ReplyTxs(Vector(tx)))
      .fold(e => fail(e.message), identity)

  private def wire(tx: Bytes): Bytes =
    // [3, [_ [6, 24(tx)] ]], written by hand so that a transaction the codec refuses is sent too
    val head =
      if tx.size < 24 then hex(f"${0x40 | tx.size}%02x")
      else if tx.size < 256 then hex(f"58${tx.size}%02x")
      else hex(f"59${tx.size}%04x")
    hex("82039f8206d818") ++ head ++ tx ++ hex("ff")

  test("the corpus has Conway transactions with detached records, accepted and refused") {
    assert(accepted.size >= 6, accepted.map(_.name).toString)
    assert(rows.exists(!_.accepted))
    assert(accepted.exists(_.raw.headOption.contains(0x84.toByte)))
    assert(accepted.exists(_.raw.headOption.contains(0x9f.toByte)))
  }

  for row <- accepted do
    test(s"${row.name}: the codec takes it, and its id is the reference's") {
      assertEquals(TxSubmission2.bodyId(row.raw).map(_.hex), Right(row.txid.getOrElse("")))
      val message = Message.ReplyTxs(Vector(row.raw))
      assertEquals(TxSubmission2.decode(State.Txs, Role.Client, wire(row.raw)), Right(message))
      assertEquals(reply(row.raw), wire(row.raw))
      // the mempool reads it alike, so the id the peer offered is the one it holds
      assertEquals(MempoolState.parseParts(row.raw).map(_.id.hex), Right(row.txid.getOrElse("")))
      // read as a tree of items, as the codec read it before, its body ends at the first record's
      // break: the transaction is refused, or its id is not the reference's
      val tree = TxSubmission2.bodyId(row.raw)(using TxSubmission2Fixtures.structural)
      assert(tree.forall(_.hex != row.txid.getOrElse("")), tree.toString)
    }

  for row <- rows if !row.accepted do
    test(s"${row.name}: refused by the codec, as the reference's decoder refuses it") {
      val codec = TxSubmission2.decode(State.Txs, Role.Client, wire(row.raw))
      assert(codec.isLeft, codec.toString)
      // and by the mempool, which reads it alike
      val mempool = MempoolState.parseParts(row.raw)
      assert(mempool.left.exists(_.isInstanceOf[Rejection.Malformed]), mempool.toString)
    }

  test("every whole Conway transaction of the reference corpora: the codec's verdict is theirs") {
    // a reply's transaction is read whole by the ledger's decoders, as the reference's codec reads
    // it (#708): taken where the reference takes it, under its id, refused where it refuses it
    val cases = ConwayTransactionCases.all
    assert(cases.count(_.accepted) >= 10 && cases.count(!_.accepted) >= 10, cases.size.toString)
    cases.foreach { c =>
      val codec = TxSubmission2.decode(State.Txs, Role.Client, wire(c.raw))
      val label = s"${c.corpus}: ${c.name}"
      if c.accepted then
        assertEquals(codec, Right(Message.ReplyTxs(Vector(c.raw))), label)
        c.txid.foreach(id => assertEquals(TxSubmission2.bodyId(c.raw).map(_.hex), Right(id), label))
      else
        assert(
          codec.left.exists {
            case ProtocolError.UndecodableTransaction(_) | ProtocolError.Codec(_) => true
            case _                                                                => false
          },
          s"$label: $codec"
        )
    }
  }

  test(
    "a peer offers the transactions with detached records, and the node fetches each by its id"
  ) {
    // both sides are the node's: the client offers them under the reference's ids and sends their
    // bodies, which its codec refused before; the server requests those ids and takes the replies,
    // whose ids it checks against the request
    // several rows share a body, and so an id: one of each
    val distinct = accepted.distinctBy(_.txid)
    val offered = distinct.zipWithIndex.map((row, i) =>
      TxSubmissionClient.Held(hex(row.txid.getOrElse("")), row.raw.size, i.toLong + 1)
    )
    val bodies = distinct.map(row => hex(row.txid.getOrElse("")) -> row.raw).toMap
    val source = new TxSubmissionClient.Source[IO]:
      def reader = RelayedTransactions
      def after(ticket: Long, max: Int) = IO.pure(offered.filter(_.ticket > ticket).take(max))
      def awaitAfter(ticket: Long) =
        if offered.exists(_.ticket > ticket) then IO.unit else IO.never
      def body(id: Bytes) = IO.pure(bodies.get(id))
    Ref.of[IO, Vector[Bytes]](Vector.empty).flatMap { taken =>
      val sink = new TxSubmissionServer.Sink[IO]:
        def reader = RelayedTransactions
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes])(using Raise[IO, NetworkError]) =
          taken.update(_ ++ originals)
      val delivered = (IO.sleep(5.millis) *> taken.get).iterateUntil(_.size == distinct.size)
      typed {
        Peers.linked(protocols = Set(4)).use { (client, server) =>
          for
            c <- client.channel(4)
            s <- server.channel(4, Mux.Role.Responder)
            // both drivers end with the test; one that ends before every transaction is taken
            // ends the wait with its result
            drivers = (
              typed(TxSubmissionClient.run(c, source)).background,
              typed(TxSubmissionServer.run(s, sink)).background
            ).tupled
            first <- drivers.use((offering, taking) =>
              IO.race(delivered, IO.race(offering, taking)).timeout(10.seconds)
            )
          yield first
        }
      }.map {
        case Right(Left(all)) => assertEquals(all, distinct.map(_.raw))
        case other            => fail(s"a driver ended first: $other")
      }
    }
  }

  /** The node's client offers `tx` and the node's server, reading with `reading`, collects it: how
    * the server ends, and what reached its sink.
    */
  private def offerOne(
      tx: Bytes,
      reading: TxSubmission2.TransactionReader
  ): IO[(Either[NetworkError, Unit], Vector[Bytes])] =
    val id = TxSubmission2.bodyId(tx).fold(e => fail(e.message), identity)
    val one = TxSubmissionClient.Held(id, tx.size, 1L)
    val source = new TxSubmissionClient.Source[IO]:
      def reader = RelayedTransactions
      def after(ticket: Long, max: Int) = IO.pure(Vector(one).filter(_.ticket > ticket))
      def awaitAfter(ticket: Long) = if ticket < 1 then IO.unit else IO.never
      def body(wanted: Bytes) = IO.pure(Option.when(wanted == id)(tx))
    Ref.of[IO, Vector[Bytes]](Vector.empty).flatMap { taken =>
      val sink = new TxSubmissionServer.Sink[IO]:
        def reader = reading
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes])(using Raise[IO, NetworkError]) =
          taken.update(_ ++ originals)
      typed {
        Peers.linked(protocols = Set(4)).use { (client, server) =>
          for
            c <- client.channel(4)
            s <- server.channel(4, Mux.Role.Responder)
            ended <- typed(TxSubmissionClient.run(c, source)).background
              .use(_ => typed(TxSubmissionServer.run(s, sink)).timeout(10.seconds))
          yield ended
        }
      }.flatMap(ended => taken.get.map(t => (ended.flatten, t)))
    }

  test("a peer's reply whose transaction the ledger's decoders refuse ends the session (#708)") {
    // framed as a transaction (its id is its body's), refused by the decoders of its outputs
    val refused = rows
      .find(_.name == "conway transaction value in an output that counts its fields")
      .getOrElse(fail("the case is not in the corpus"))
    assert(RelayedTransactions.body(refused.raw).isRight)
    offerOne(refused.raw, RelayedTransactions).map { (ended, taken) =>
      assertEquals(taken, Vector.empty)
      ended match
        case Left(NetworkError.Decode(4, ProtocolError.UndecodableTransaction(_))) => ()
        case other => fail(s"expected the codec's refusal, got $other")
    }
  }

  test("a decoder that throws on a reply is a defect of the input, not the peer's refusal") {
    val throwing = new TxSubmission2.TransactionReader:
      def body(original: Bytes) = RelayedTransactions.body(original)
      def read(original: Bytes) = throw new IllegalStateException("injected decoder defect")
    offerOne(accepted.headOption.getOrElse(fail("no case")).raw, throwing).map { (ended, taken) =>
      assertEquals(taken, Vector.empty)
      ended match
        case Left(NetworkError.DecoderDefect(4, _: IllegalStateException)) => ()
        case other => fail(s"expected a decoder defect, got $other")
    }
  }

  test("definite and indefinite envelopes keep the body's own bytes as its identity") {
    Vector("a0", "a1001800", "bf001800ff").foreach { body =>
      val definite = hex("84" + body + "a0f5f6")
      val indefinite = hex("9f" + body + "a0f5f6ff")
      assertEquals(TxSubmission2.bodyId(indefinite), TxSubmission2.bodyId(definite))
      assertEquals(TxSubmission2.bodyId(indefinite), Right(Blake2b.hash256.hash(hex(body))))
      // a body without its required fields: framed and identified, but refused when read whole
      assert(TxSubmission2.decode(State.Txs, Role.Client, wire(indefinite)).isLeft)
    }
    assertEquals(TxSubmission2.bodyId(hex("84a0a10080f5f6")), Right(emptyId))
    assertNotEquals(
      TxSubmission2.bodyId(hex("9fa1001800a0f5f6ff")),
      TxSubmission2.bodyId(hex("9fa10000a0f5f6ff"))
    )
  }

  test(
    "an envelope of other than three or four parts, a body not a map or a wrong flag is refused"
  ) {
    Vector(
      "84a0a0f5f600",
      "9f",
      "9fff",
      "9fa0ff",
      "9fa0a0ff",
      "9fa0a0f5ff",
      "9fa0a0f5f6",
      "9fa0a0f5f600ff",
      "9fa0a0f5f6ffff",
      "9fa0a0f5f6ff00",
      "9fa0a0f5f6fe",
      "9fa0a0f5f6f8ff",
      "9f80a0f5f6ff",
      "9fa0a000f6ff",
      "9fbf00ffa0f5f6ff",
      "9fa0a0f5c0ff",
      "9fa0a0f5a100ff",
      "9fa0a0f59f00ff"
    ).foreach(raw => assert(TxSubmission2.bodyId(hex(raw)).isLeft, raw))
    assertEquals(
      RelayedTransactions.body(hex("8480a0f5f6")),
      Left(ProtocolError.TransactionBodyIsNotAMap)
    )
    assertEquals(
      RelayedTransactions.body(hex("84a0a000f6")),
      Left(ProtocolError.ExpectedConwayTransactionMembers)
    )
    assertEquals(
      RelayedTransactions.body(hex("83a0a0f5")),
      Left(ProtocolError.ExpectedConwayTransactionMembers)
    )
  }

  test("a transaction without its validity flag has its body's id, as `AlonzoTx` reads it (#699)") {
    // `decodeRecordNamed "AlonzoTx"` (`Alonzo/Tx.hs:532-553`, cardano-ledger f649f975): a third
    // member that is not a boolean is the auxiliary data, and the list holds three members
    val bodyId = Blake2b.hash256.hash(hex("a0"))
    Vector("83a0a0f6", "83a0a0a0", "83a0a0d90103a0", "9fa0a0f6ff", "9fa0a0a0ff", "84a0a0f4f6")
      .foreach(t => assertEquals(TxSubmission2.bodyId(hex(t)), Right(bodyId), t))
    // a boolean third member is the flag, and the auxiliary data must follow it; a member that is
    // not a boolean is not a flag, and the list then holds one member too many
    Vector(
      "83a0a0f5",
      "9fa0a0f5ff",
      "84a0a0f6f6",
      "9fa0a0f6f6ff",
      "84a0a0f6",
      "82a0a0",
      "85a0a0f5f6f6"
    )
      .foreach(t => assert(TxSubmission2.bodyId(hex(t)).isLeft, t))
  }
  test("nesting is bounded by the transaction's size, as in the reference, which has no bound") {
    val exact = hex("9fa0a10059fff6" + "00" * 65526 + "f5f6ff")
    assertEquals(exact.size, TxSubmission2.MaxOriginalBytes)
    assertEquals(TxSubmission2.bodyId(exact), Right(emptyId))
    assert(TxSubmission2.bodyId(Bytes(exact.value :+ 0.toByte)).isLeft)
    // 30,000 levels fit the 64 KiB
    val deep = hex("9fa0a0f5" + "81" * 30000 + "00ff")
    assertEquals(TxSubmission2.bodyId(deep), TxSubmission2.bodyId(hex("9fa0a0f5f6ff")))
    val tooMany = hex("9fa0a0f5" + "81" * 65535 + "00ff")
    assert(tooMany.size > TxSubmission2.MaxOriginalBytes && TxSubmission2.bodyId(tooMany).isLeft)
  }
