// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.IO
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack
import xyz.kd5ujc.scarab.node.ntc.{CborOut, LedgerQuery, NtcQuery}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}
import xyz.kd5ujc.scarab.protocol.LocalTxSubmission

import TxKit.head

/** The reference reads what a local client sends with no byte, item or nesting budget: it runs
  * LocalTxSubmission and LocalStateQuery with no codec limit (`runPeer` and `Stateful.runPeer`,
  * `Ouroboros/Consensus/Network/NodeToClient.hs:455-478`, ouroboros-consensus 82ecba32), decodes a
  * transaction with the ledger's decoder and judges its size by `maxTxSize` after that. A
  * transaction or a query over the former 1 MiB, 2,000,000 byte and 100,000 or 200,000 item budgets
  * is read, so that the ledger, not the decoder, answers it (#749).
  */
class LocalDecodeBoundsSuite extends CatsEffectSuite:
  /** The 1,100,000 items of a metadatum list, more than a mebibyte in all. */
  private val Items = 1100000

  /** `TxKit.pay(1)` with auxiliary data `{0: [0, 0, ...]}` of [[Items]] items in place of null. */
  private val large: Bytes =
    val (signed, _) = TxKit.pay(1)
    val aux = head(5, 1) ++ head(0, 0) ++ head(4, Items) ++ Vector.fill(Items)(0.toByte)
    Bytes(signed.value.dropRight(1) ++ aux)

  test("a transaction of more than a mebibyte and 100,000 items is read whole") {
    assert(large.size > 1048576)
    val parsed = onSmallStack(MempoolState.parse(large)).map(_._1)
    assertEquals(
      parsed,
      MempoolState.parse(TxKit.pay(1)._1).map(_._1)
    )
  }

  test("such a transaction is judged by the ledger, not refused as malformed") {
    val judged =
      MempoolState.empty(Right(TxKit.ledger(1)), 1000, None).add(large, TxKit.env, Source.Local)
    assert(judged.isLeft, "a transaction over maxTxSize is not admitted")
    assert(
      judged.left.forall(r => !r.isInstanceOf[Rejection.Malformed]),
      s"the ledger refuses it, not the decoder: $judged"
    )
  }

  test("LocalTxSubmission reads a SubmitTx of more than a mebibyte") {
    // [0, [6, 24(h'...')]]: the Conway transaction in CBOR-in-CBOR after its era index
    val tx = head(4, 2) ++ head(0, 6) ++ head(6, 24) ++ TxKit.bstr(large)
    val message = Bytes(head(4, 2) ++ head(0, 0) ++ tx)
    LocalTxSubmission.decodePrefix(LocalTxSubmission.State.Idle, Role.Client, message) match
      case DecodeResult.Decoded(LocalTxSubmission.Message.SubmitTx(item), consumed) =>
        assertEquals(item, Bytes(tx))
        assertEquals(consumed, message.size)
      case other => fail(s"not read: $other")
  }

  test("the live submission handler returns the ledger rejection for the large transaction") {
    val wrapped = Bytes(head(4, 2) ++ head(0, 6) ++ head(6, 24) ++ TxKit.bstr(large))
    val expected = MempoolState
      .empty(Right(TxKit.ledger(1)), 1000, None)
      .add(large, TxKit.env, Source.Local)
      .swap
      .toOption
      .getOrElse(fail("the oversized transaction must be refused"))
    assert(!expected.isInstanceOf[Rejection.Malformed])
    Mempool
      .create[IO](Right(TxKit.ledger(1)), 1000, TxKit.env, None, MempoolEvents.none[IO])
      .flatMap(pool =>
        LocalSubmission.handler(pool)(fatal => IO(fail(s"unexpected fatal: $fatal")))(wrapped)
      )
      .map(result => assertEquals(result, LocalSubmission.response(expected)))
  }

  test("a query of more than 2,000,000 bytes and 200,000 items is read") {
    // [0, [0, [6, [15, [* [h'..', ix]]]]]]: GetUTxOByTxIn in Conway, 60,000 inputs
    val count = 60000
    val query = CborOut.build { w =>
      w.array(2).uint(0).array(2).uint(0).array(2).uint(6).array(2).uint(15).array(count)
      (0 until count).foreach(i => w.array(2).bytes(Bytes(Vector.fill(32)(0x11.toByte))).uint(i))
    }
    assert(query.size > 2000000)
    NtcQuery.decode(query) match
      case Right(NtcQuery.IfCurrent(_, LedgerQuery.GetUTxOByTxIn(inputs))) =>
        assertEquals(inputs.size, count)
      case other => fail(s"not read as GetUTxOByTxIn: ${other.left.map(_.message)}")
  }
