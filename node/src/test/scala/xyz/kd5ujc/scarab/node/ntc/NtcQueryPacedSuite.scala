// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.DetachedRecordsReferenceSuite
import xyz.kd5ujc.scarab.ledger.DetachedRecordsReferenceSuite.Kind
import xyz.kd5ujc.scarab.protocol.{
  ChainSync,
  LocalStateQuery,
  LocalStateQueryEngine,
  Paced,
  ProtocolError
}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}

/** A `MsgQuery` a local client paces a byte an SDU, read by the node's reader of queries: the
  * codec, the walk of a query that may hold a detached record and the judge of its envelopes each
  * go on from where they stopped, and none reads again what it read whole; the verdict on each
  * prefix is the one of the codec started afresh on it.
  */
class NtcQueryPacedSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val decoder = LocalStateQueryEngine.incremental(
    LocalStateQuery.State.Acquired,
    Role.Client,
    ChainSync.enginePointCodec,
    NtcQuery.reader
  )

  /** `[3, [0, [0, [6, q]]]]`: `MsgQuery` of `q`, a query of Conway's ledger. */
  private def inConway(q: Bytes): Bytes = hex("8203820082008206") ++ q

  /** As [[xyz.kd5ujc.scarab.protocol.PacedDecodingSuite]]: paced after `head` bytes, the bytes of
    * `[from, until)` poisoned once read; the verdicts are those of the codec started afresh, and
    * those of one that reads each prefix from its start are not.
    */
  private def readsOnce(message: Bytes, head: Int, from: Int, until: Int)(using
      munit.Location
  ): DecodeResult[LocalStateQuery.Message] =
    val inputs = Paced.poisoned(message, head, from, until, message.size)
    val expected = Paced.afresh(decoder, message, Paced.dribbled(message, head))
    assertEquals(Paced.resumedOn(decoder, inputs), expected)
    assertNotEquals(Paced.resumedOn(Paced.restarting(decoder), inputs), expected)
    expected.lastOption.getOrElse(fail("no verdict"))

  test("a query that may hold a detached record is walked on, not walked again") {
    // GetUTxOByAddress, [6, [_ h'9fff', 1, 1, ...]]: its argument holds the bytes 9f ff
    val message = inConway(hex("82069f429fff") ++ hex("01" * 6000) ++ hex("ff"))
    readsOnce(message, 4000, 20, 3990) match
      case DecodeResult.Decoded(LocalStateQuery.Message.Query(query), consumed) =>
        assertEquals(consumed, message.size)
        assertEquals(query.size, message.size - 2)
      case other => fail(s"not the query: $other")
  }

  test(
    "a query of detached records read past the items a tree of items reads is read on, not again"
  ) {
    // GetProposals of 400 ids, each a record written as 9f ff with its two fields after it: a tree of
    // items closes the list early, and the rest of what has arrived is read as items one after another
    val id = "9fff" + "5820" + "77" * 32 + "01"
    val message = inConway(hex("82181f" + "990190" + id * 400))
    val head = message.size - 37 * 100
    readsOnce(message, head, 20, head - 37) match
      case DecodeResult.Decoded(LocalStateQuery.Message.Query(_), consumed) =>
        assertEquals(consumed, message.size)
      case other => fail(s"not the query: $other")
  }

  test("a query of detached records paced a field an SDU is not copied on every SDU") {
    // GetProposals of n detached ids, each 9f ff, the id and the index, every field its own SDU:
    // once the tree of items has closed, each prefix that ends on a field is read as items one
    // after another; before, those items were copied as one span on every such prefix
    val id = "9fff" + "5820" + "77" * 32 + "01"
    def run(n: Int): Unit =
      val message = inConway(hex("82181f" + "99" + f"$n%04x" + id * n))
      val head = message.size - 37 * n
      val cuts = (0 until n).toVector.flatMap(k =>
        Vector(head + 37 * k + 2, head + 37 * k + 36, head + 37 * k + 37)
      )
      val verdicts = Paced.resumed(decoder, message, cuts)
      assert(
        verdicts.lastOption.exists(_.isInstanceOf[DecodeResult.Decoded[?]]),
        verdicts.lastOption
      )
    val growth = Paced.growth(400)(run)
    assert(growth < 8.0, s"allocation grew $growth times for four times the ids")
  }

  test("a query under many GetCBOR is judged on from the last one read") {
    val message = inConway(hex("8209" * 3000) ++ hex("820601"))
    readsOnce(message, 4000, 20, 3990) match
      case DecodeResult.Decoded(LocalStateQuery.Message.Query(_), consumed) =>
        assertEquals(consumed, message.size)
      case other => fail(s"not the query: $other")
  }

  test("queries, their detached records and the prefixes refused are the same paced and whole") {
    val rows = DetachedRecordsReferenceSuite.load.filter(_.kind == Kind.GovActionIds)
    val proposals = rows.map(row => inConway(hex("82181f") ++ row.raw))
    val wrapped = rows.map(row => inConway(hex("820982181f") ++ row.raw))
    val refused = Vector("8203820f", "820382008203", "82038200820082081f").map(hex)
    assert(rows.nonEmpty)
    assertEquals(Paced.differences(decoder, proposals ++ wrapped ++ refused, 21L), Vector.empty)
    assertEquals(
      decoder.decodePrefix(hex("8203820f")),
      DecodeResult.Failed(
        ProtocolError.UndecodableQueryPrefix(
          NtcQueryPrefix.Refusal.Constructor(NtcQueryPrefix.Level.Query, 2, 15)
        )
      )
    )
  }
