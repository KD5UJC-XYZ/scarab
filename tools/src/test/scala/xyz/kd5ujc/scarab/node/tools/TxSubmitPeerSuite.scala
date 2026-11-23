// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.ExitCode
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.{FailsOnUncaught, NetworkError}
import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, TxSubmission2}

import TxSubmitPeer.{Batch, Outcome, Signed, Unreadable}

/** What [[TxSubmitPeer]] offers: a transaction read from the text envelope `cardano-cli` writes,
  * with its id as the node reads it, the batch that refuses duplicates, the source that hands each
  * transaction out once, in order, and the exit each outcome gives.
  */
class TxSubmitPeerSuite extends FailsOnUncaught:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private val devnet = Path.of("fixtures", "dijkstra-devnet")
  private val leios = RelayedTransactions.of(ReferenceBuild.LeiosB004fe0c)
  private val dijkstra = TxSubmitPeer.eras("dijkstra")
  private val file = devnet.resolve("transaction.json")
  private def envelope: String = Files.readString(file)

  /** The id `manifest.json` records for the devnet's transaction. */
  private def recordedId: String =
    """"transaction"\s*:\s*\{[^}]*"id"\s*:\s*"([0-9a-f]{64})"""".r
      .findFirstMatchIn(Files.readString(devnet.resolve("manifest.json")))
      .map(_.group(1))
      .getOrElse(fail("no transaction id in manifest.json"))

  private def tx: Signed =
    TxSubmitPeer.signed(file, envelope, leios, dijkstra).fold(u => fail(u.message), identity)

  test("a cardano-cli text envelope gives its transaction and the id the devnet recorded") {
    assertEquals(tx.id.hex, recordedId)
    assert(tx.original.size > 0)
  }

  test("an envelope of another era, not an envelope, not hex or not a transaction is refused") {
    assertEquals(
      TxSubmitPeer.signed(file, envelope, leios, TxSubmitPeer.eras("conway")),
      Left(Unreadable.OtherEra(file, "Tx DijkstraEra"))
    )
    assert(
      TxSubmitPeer.signed(file, "{}", leios, dijkstra) match
        case Left(Unreadable.NotEnvelope(`file`, _)) => true
        case _                                       => false
    )
    assertEquals(
      TxSubmitPeer.signed(file, """{"type":"Tx DijkstraEra","cborHex":"zz"}""", leios, dijkstra),
      Left(Unreadable.NotHex(file))
    )
    assert(
      TxSubmitPeer.signed(
        file,
        """{"type":"Tx DijkstraEra","cborHex":"00"}""",
        leios,
        dijkstra
      ) match
        case Left(Unreadable.NotTransaction(`file`, _)) => true
        case _                                          => false
    )
  }

  test("a batch is not empty and holds no two transactions with the same body") {
    val again = file.resolveSibling("again.tx")
    assertEquals(Batch.of(Vector.empty).map(_.txs.length), Left(Unreadable.Empty))
    assertEquals(
      Batch.of(Vector(tx, tx.copy(file = again))).map(_.txs.length),
      Left(Unreadable.SameBody(file, again))
    )
    // the same body under other witnesses has the same id
    assertEquals(
      Batch.of(Vector(tx, Signed(again, hex("00"), tx.id))).map(_.txs.length),
      Left(Unreadable.SameBody(file, again))
    )
  }

  test("the source offers the batch in order, each once, and its bodies on request") {
    val other = Signed(file.resolveSibling("other.tx"), hex("01"), hex("ab" * 32))
    val third = Signed(file.resolveSibling("third.tx"), hex("02"), hex("cd" * 32))
    val batch = Batch.of(Vector(tx, other, third)).fold(u => fail(u.message), identity)
    val source = TxSubmitPeer.source(batch, Era.Dijkstra, leios)
    for
      first <- source.after(0L, 2)
      rest <- source.after(2L, 8)
      none <- source.after(3L, 8)
      _ <- source.awaitAfter(2L)
      body <- source.body(other.id)
      unknown <- source.body(hex("ef" * 32))
    yield
      assertEquals(first.map(h => (h.id, h.ticket)), Vector((tx.id, 1L), (other.id, 2L)))
      assertEquals(rest.map(h => (h.id, h.ticket)), Vector((third.id, 3L)))
      assertEquals(none, Vector.empty)
      assert(first.forall(_.era == Era.Dijkstra))
      assertEquals(body, Some(TxSubmission2.EraTx(Era.Dijkstra, other.original)))
      assertEquals(unknown, None)
  }

  test("only an offering whose every body was sent ends in success") {
    assertEquals(Outcome.Sent(ended = true).exitCode, ExitCode.Success)
    assertEquals(Outcome.Sent(ended = false).exitCode, ExitCode.Success)
    assertEquals(Outcome.Ended(Right(())).exitCode, ExitCode.Error)
    assertEquals(Outcome.Ended(Left(NetworkError.IngressLimit(4, 1L))).exitCode, ExitCode.Error)
    assertEquals(Outcome.TimedOut(10L).exitCode, ExitCode.Error)
  }
