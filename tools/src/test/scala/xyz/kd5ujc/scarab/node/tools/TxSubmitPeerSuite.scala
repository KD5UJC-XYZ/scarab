// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{IO, Ref}
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, TxSubmission2}
import TxSubmitPeer.{Signed, Unreadable}

/** What [[TxSubmitPeer]] offers: a transaction read from the text envelope `cardano-cli` writes,
  * with its id as the node reads it, and the source that hands each one out once, in order.
  */
class TxSubmitPeerSuite extends FailsOnUncaught:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private val devnet = Path.of("fixtures", "dijkstra-devnet")
  private val leios = RelayedTransactions.of(ReferenceBuild.LeiosB004fe0c)
  private def envelope: String = Files.readString(devnet.resolve("transaction.json"))
  private val file = devnet.resolve("transaction.json")

  /** The id `manifest.json` records for the devnet's transaction. */
  private def recordedId: String =
    """"transaction"\s*:\s*\{[^}]*"id"\s*:\s*"([0-9a-f]{64})"""".r
      .findFirstMatchIn(Files.readString(devnet.resolve("manifest.json")))
      .map(_.group(1))
      .getOrElse(fail("no transaction id in manifest.json"))

  test("a cardano-cli text envelope gives its transaction and the id the devnet recorded") {
    TxSubmitPeer.signed(file, envelope, leios, Era.Dijkstra) match
      case Right(Signed(_, original, id)) =>
        assertEquals(id.hex, recordedId)
        assert(original.size > 0)
      case Left(unreadable) => fail(unreadable.message)
  }

  test("a file that is not an envelope, not hex or not a transaction of the era is refused") {
    assert(
      TxSubmitPeer.signed(file, "{}", leios, Era.Dijkstra) match
        case Left(Unreadable.NotEnvelope(`file`, _)) => true
        case _                                       => false
    )
    assertEquals(
      TxSubmitPeer.signed(file, """{"cborHex":"zz"}""", leios, Era.Dijkstra),
      Left(Unreadable.NotHex(file))
    )
    assert(
      TxSubmitPeer.signed(file, """{"cborHex":"00"}""", leios, Era.Dijkstra) match
        case Left(Unreadable.NotTransaction(`file`, _)) => true
        case _                                          => false
    )
  }

  test("the source offers the transactions in order, once, and tells each body asked") {
    val tx =
      TxSubmitPeer.signed(file, envelope, leios, Era.Dijkstra).fold(u => fail(u.message), identity)
    val other = Signed(file, hex("01"), hex("ab" * 32))
    for
      asked <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      source = TxSubmitPeer.source(
        Vector(tx, other),
        Era.Dijkstra,
        leios,
        id => asked.update(_ :+ id)
      )
      first <- source.after(0L, 10)
      rest <- source.after(1L, 10)
      none <- source.after(2L, 10)
      _ <- source.awaitAfter(1L)
      body <- source.body(tx.id)
      unknown <- source.body(hex("cd" * 32))
      told <- asked.get
    yield
      assertEquals(
        first.map(h => (h.id, h.ticket, h.era)),
        Vector((tx.id, 1L, Era.Dijkstra), (other.id, 2L, Era.Dijkstra))
      )
      assertEquals(rest.map(_.id), Vector(other.id))
      assertEquals(none, Vector.empty)
      assertEquals(body, Some(TxSubmission2.EraTx(Era.Dijkstra, tx.original)))
      assertEquals(unknown, None)
      assertEquals(told, Vector(tx.id))
  }
