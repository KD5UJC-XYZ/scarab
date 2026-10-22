// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.models.leios.fetch.LeiosBlockFetchMessage
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosFetch, LeiosWireError, TxBitmaps}

/** The generated reader of the LeiosFetch messages (`models.conf`: the Blueprint's
  * `leios-fetch/messages.cddl` at `0460c5a6`, whose `base` is the consensus module plus `word16`
  * and which includes the `dijkstra` module for `dijkstra.block_transaction`) against the
  * hand-written codec of `protocol`: every message the one writes, the other reads as the same case
  * with the same fields, writes back as the same bytes, and both refuse the same malformed ones.
  *
  * Two differences are deliberate and tested below. The codec carries the bytes inside a
  * transaction's tag 24 without reading them, as the reference decoder does, while the Blueprint's
  * `.cbor dijkstra.block_transaction` makes the generated reader decode them. The CDDL has no bound
  * on the number of entries where the codec has `maxTxsPerEb`. The older form of `MsgLeiosBlockTxs`
  * (`fe4cceb9`) is read by the codec only (`LeiosFetch.Message.LegacyBlockTxs`).
  */
class LeiosFetchCddlSuite extends munit.FunSuite:
  private def big(x: Any): BigInt = BigInt(x.toString)
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)
  private def hash(n: Int): EbHash =
    EbHash.fromBytes(Bytes.wrap(Array.fill[Byte](32)(n.toByte))).getOrElse(fail("hash"))
  private val point = EbPoint(371614L, hash(7))
  private val bitmaps = TxBitmaps(Vector(0 -> BigInt(1), 3 -> BigInt("ffffffffffffffff", 16)))
  // two Dijkstra block transactions [body, witness set, auxiliary data, is_valid], with the inputs as the
  // generated writer writes a set (tag 258): the least one, and one with a time to live
  private val tx1Hex = "84" + "a3" + "00d9010280" + "0180" + "0200" + "a0f6f5"
  private val tx2Hex = "84" + "a4" + "00d9010280" + "0180" + "0205" + "031864" + "a0f6f5"
  private val transactions = Vector(hex(tx1Hex), hex(tx2Hex))

  private def written(m: LeiosFetch.Message): Bytes =
    LeiosFetch.encode(m).fold(e => fail(s"$m: $e"), identity)

  private def generated(bytes: Bytes) = LeiosBlockFetchMessage.decodeBytes(bytes)

  private val messages: Vector[LeiosFetch.Message] = Vector(
    LeiosFetch.Message.Done,
    LeiosFetch.Message.BlockRequest(point),
    LeiosFetch.Message.Block(Vector(hash(1) -> 100L, hash(2) -> 0xffffffffL)),
    LeiosFetch.Message.BlockTxsRequest(point, TxBitmaps(Vector.empty)),
    LeiosFetch.Message.BlockTxsRequest(point, bitmaps),
    LeiosFetch.Message.BlockTxs(Vector.empty),
    LeiosFetch.Message.BlockTxs(transactions)
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
        case (LeiosFetch.Message.BlockTxs(txs), MsgLeiosBlockTxs(list)) =>
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
    // [3, point, {_ 0: 1}] and [4, [_ #6.24(h'a0')...]] with an indefinite byte string inside the tag
    val p = "82" + "1a0005a91e" + "5820" + "07" * 32
    val request = hex("83" + "03" + p + "bf" + "0001" + "ff")
    val tx = tx1Hex
    val answer = hex("82" + "04" + "9f" + "d818" + "58" + "0e" + tx + "ff")
    // the same transaction as an indefinite-length byte string of two chunks inside the tag
    val chunked =
      hex(
        "82" + "04" + "81" + "d818" + "5f" + "44" + "84a300d9" + "4a" + "01028001800200a0f6f5" + "ff"
      )
    assert(generated(request).isRight)
    assert(generated(answer).isRight)
    assert(LeiosFetch.decodePrefix(LeiosFetch.State.Idle, request).isRight)
    assertEquals(
      LeiosFetch.decodePrefix(LeiosFetch.State.BlockTxs, answer).map(_._1),
      Right(LeiosFetch.Message.BlockTxs(Vector(hex(tx))))
    )
    // an indefinite-length endorser block map
    val eb = hex("82" + "02" + "bf" + "5820" + "01" * 32 + "01" + "ff")
    assert(generated(eb).isRight)
    assert(LeiosFetch.decodePrefix(LeiosFetch.State.Block, eb).isRight)
    assertEquals(
      LeiosFetch.decodePrefix(LeiosFetch.State.BlockTxs, chunked).map(_._1),
      Right(LeiosFetch.Message.BlockTxs(Vector(hex(tx))))
    )
    assert(generated(chunked).isRight)
  }

  test("an endorser block with no transaction is refused by both") {
    Vector("82" + "02" + "a0", "82" + "02" + "bf" + "ff").foreach { h =>
      val bytes = hex(h)
      assert(generated(bytes).isLeft, h)
      assertEquals(
        LeiosFetch.decodePrefix(LeiosFetch.State.Block, bytes),
        Left(LeiosWireError.EmptyEndorserBlock),
        h
      )
    }
  }

  test("a transaction that is not tag 24 around bytes is refused by both") {
    val tx = tx1Hex
    val untagged = hex("82" + "04" + "81" + "58" + "0e" + tx) // the old plain byte string
    val wrongTag = hex("82" + "04" + "81" + "d819" + "58" + "0e" + tx)
    val notBytes = hex("82" + "04" + "81" + "d818" + tx)
    Vector(untagged, wrongTag, notBytes).foreach { bytes =>
      assert(generated(bytes).isLeft, bytes.hex)
      assertEquals(
        LeiosFetch.decodePrefix(LeiosFetch.State.BlockTxs, bytes),
        Left(LeiosWireError.BadTransaction(0)),
        bytes.hex
      )
    }
  }

  test("the bytes inside tag 24 are not read by the codec, and are by the generated reader") {
    // a CBOR text string where a block transaction belongs: framing is right, the content is not
    val bytes = hex("82" + "04" + "81" + "d818" + "42" + "6161")
    assert(generated(bytes).isLeft)
    assertEquals(
      LeiosFetch.decodePrefix(LeiosFetch.State.BlockTxs, bytes).map(_._1),
      Right(LeiosFetch.Message.BlockTxs(Vector(hex("6161"))))
    )
  }

  test(
    "the entry count of an endorser block: the codec stops at maxTxsPerEb, the CDDL has no bound"
  ) {
    def eb(n: Int): Bytes =
      hex("82" + "02" + "b9" + f"$n%04x") ++ Bytes.wrap(
        Array
          .tabulate(n)(i => hex("5820").toArray ++ Array.fill[Byte](32)(i.toByte) ++ Array[Byte](1))
          .flatten
      )
    val ok = eb(LeiosFetch.MaxTxsPerEb)
    val over = eb(LeiosFetch.MaxTxsPerEb + 1)
    assert(LeiosFetch.decodePrefix(LeiosFetch.State.Block, ok).isRight)
    assert(generated(ok).isRight)
    assert(LeiosFetch.decodePrefix(LeiosFetch.State.Block, over).isLeft)
    assert(generated(over).isRight)
  }

  test(
    "the form of fe4cceb9 is read by the codec as LegacyBlockTxs and not by the generated reader"
  ) {
    val p = written(LeiosFetch.Message.BlockRequest(point)).drop(2).hex
    // [4, point, {0: 1}, [h'a0']]: the point, the bitmaps and a plain transaction
    val old = hex("84" + "04" + p + "a1" + "0001" + "81" + "a0")
    assert(generated(old).isLeft)
    assertEquals(
      LeiosFetch.decodePrefix(LeiosFetch.State.BlockTxs, old).map(_._1),
      Right(
        LeiosFetch.Message.LegacyBlockTxs(
          point,
          TxBitmaps(Vector(0 -> BigInt(1))),
          Vector(hex("a0"))
        )
      )
    )
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
    // [4, []] is the form of 0460c5a6; the point and the bitmaps before the list are the older form
    assert(generated(hex("820480")).isRight)
    assert(generated(hex("8404" + "82" + "1a0005a91e" + "5820" + "07" * 32 + "a0" + "80")).isLeft)
  }
end LeiosFetchCddlSuite
