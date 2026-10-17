// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.models.leios.fetch.LeiosBlockFetchMessage
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosFetch, TxBitmaps}

/** The generated reader of the LeiosFetch messages (`models.conf`: the Blueprint's
  * `leios-fetch/messages.cddl` at `fe4cceb9`, whose `base` is the consensus module plus `word16`
  * and whose `tx` is our module of any one item, `overrides/leios-fetch-tx.cddl`) against the
  * hand-written codec of `protocol`: every message the one writes, the other reads as the same case
  * with the same fields, writes back as the same bytes, and both refuse the same malformed ones.
  */
class LeiosFetchCddlSuite extends munit.FunSuite:
  private def big(x: Any): BigInt = BigInt(x.toString)
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e), identity)
  private def hash(n: Int): EbHash =
    EbHash.fromBytes(Bytes.wrap(Array.fill[Byte](32)(n.toByte))).getOrElse(fail("hash"))
  private val point = EbPoint(371614L, hash(7))
  private val bitmaps = TxBitmaps(Vector(0 -> BigInt(1), 3 -> BigInt("ffffffffffffffff", 16)))
  private val transactions = Vector(hex("a0"), hex("83010281f6"), hex("d81842a0a1"))

  private def written(m: LeiosFetch.Message): Bytes =
    LeiosFetch.encode(m).fold(e => fail(s"$m: $e"), identity)

  private def generated(bytes: Bytes) = LeiosBlockFetchMessage.decodeBytes(bytes)

  private val messages: Vector[LeiosFetch.Message] = Vector(
    LeiosFetch.Message.Done,
    LeiosFetch.Message.BlockRequest(point),
    LeiosFetch.Message.Block(Vector.empty),
    LeiosFetch.Message.Block(Vector(hash(1) -> 100L, hash(2) -> 0xffffffffL)),
    LeiosFetch.Message.BlockTxsRequest(point, TxBitmaps(Vector.empty)),
    LeiosFetch.Message.BlockTxsRequest(point, bitmaps),
    LeiosFetch.Message.BlockTxs(point, bitmaps, Vector.empty),
    LeiosFetch.Message.BlockTxs(point, bitmaps, transactions)
  )

  test("each message the codec writes, the generated reader reads as the same case") {
    import LeiosBlockFetchMessage.*
    messages.foreach { m =>
      val bytes = written(m)
      (m, generated(bytes).fold(e => fail(e.toString), identity)) match
        case (LeiosFetch.Message.Done, MsgDone) => ()
        case (LeiosFetch.Message.BlockRequest(p), MsgLeiosBlockRequest(q)) =>
          assertEquals((big(q.slot.value), q.ebHash.value), (BigInt(p.slot), p.hash.bytes))
        case (LeiosFetch.Message.Block(txs), MsgLeiosBlock(eb)) =>
          assertEquals(
            eb.map((h, s) => (h.value, big(s.value))),
            txs.map((h, s) => (h.bytes, BigInt(s)))
          )
        case (LeiosFetch.Message.BlockTxsRequest(p, b), MsgLeiosBlockTxsRequest(q, bs)) =>
          assertEquals((big(q.slot.value), q.ebHash.value), (BigInt(p.slot), p.hash.bytes))
          assertEquals(
            bs.map((w, bits) => (big(w.value), big(bits.value))),
            b.windows.map((w, bits) => (BigInt(w), bits))
          )
        case (LeiosFetch.Message.BlockTxs(p, b, txs), MsgLeiosBlockTxs(q, bs, list)) =>
          assertEquals((big(q.slot.value), q.ebHash.value), (BigInt(p.slot), p.hash.bytes))
          assertEquals(
            bs.map((w, bits) => (big(w.value), big(bits.value))),
            b.windows.map((w, bits) => (BigInt(w), bits))
          )
          assertEquals(list.size, txs.size)
        case other => fail(s"$m read as $other")
      // and writes back as the very bytes the hand-written codec wrote
      assertEquals(
        generated(bytes).map(LeiosBlockFetchMessage.encodeUnchecked),
        Right(bytes),
        m.toString
      )
    }
  }

  test("an indefinite-length bitmaps map and transaction list are read by both") {
    // [3, point, {_ 0: 1}] and [4, point, {_ 0: 1}, [_ a0]]
    val p = "82" + "1a0005a91e" + "5820" + "07" * 32
    val request = hex("83" + "03" + p + "bf" + "0001" + "ff")
    val answer = hex("84" + "04" + p + "bf" + "0001" + "ff" + "9f" + "a0" + "ff")
    assert(generated(request).isRight)
    assert(generated(answer).isRight)
    assert(LeiosFetch.decodePrefix(LeiosFetch.State.Idle, request).isRight)
    assert(LeiosFetch.decodePrefix(LeiosFetch.State.BlockTxs, answer).isRight)
  }

  test("what the generated reader refuses, the codec refuses: bounds of the word types") {
    val p = "82" + "1a0005a91e" + "5820" + "07" * 32
    // a window index beyond uint16, a bitmap beyond uint64, a size beyond uint32, a short hash
    val wideWindow = hex("83" + "03" + p + "a1" + "1a00010000" + "01")
    val wideBitmap = hex("83" + "03" + p + "a1" + "00" + "c249" + "01" + "00" * 8)
    val wideSize = hex("82" + "02" + "a1" + "5820" + "01" * 32 + "1b0000000100000000")
    val shortHash = hex("82" + "01" + "82" + "1a0005a91e" + "5810" + "07" * 16)
    Vector(
      LeiosFetch.State.Idle -> wideWindow,
      LeiosFetch.State.Idle -> wideBitmap,
      LeiosFetch.State.Block -> wideSize,
      LeiosFetch.State.Idle -> shortHash
    ).foreach { (state, bytes) =>
      assert(generated(bytes).isLeft, bytes.hex)
      assert(LeiosFetch.decodePrefix(state, bytes).isLeft, bytes.hex)
    }
  }

  test("an unknown tag, a wrong arity and trailing bytes are refused by the generated reader") {
    assert(generated(hex("8105")).isLeft)
    assert(generated(hex("820000")).isLeft)
    assert(generated(hex("8100") ++ hex("00")).isLeft)
    // MsgLeiosBlockTxs without the point and bitmaps is the shape of a later Blueprint revision
    assert(generated(hex("820480")).isLeft)
  }
end LeiosFetchCddlSuite
