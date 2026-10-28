// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import munit.FunSuite
import xyz.kd5ujc.scarab.network.LocalTxSubmissionServer

/** A decoder that throws on a submitted transaction (issue 465): no verdict on the transaction and
  * no refusal of it, but a defect of the connection it came from. The local client gets no answer
  * and its connection ends with a `DecoderDefect` of LocalTxSubmission (`LocalTxSubmissionServer`,
  * reported without stopping the node: `PeerDefectPolicySuite`); a remote peer's TxSubmission2
  * session ends the same way (`Mempool.sink`).
  */
class InputDefectSuite extends FunSuite:
  private val cause = new IllegalStateException("injected transaction decoder defect")

  test("an exception of the transaction's decoder is an InputDefect, with its cause") {
    MempoolState.decoding[Unit](throw cause) match
      case Left(Rejection.InputDefect(thrown)) => assert(thrown eq cause)
      case other                               => fail(s"unexpected $other")
  }

  test("control: a decoder's own refusal passes through unchanged") {
    val refused = Rejection.WrongEra(5)
    assertEquals(MempoolState.decoding[Unit](Left(refused)), Left(refused))
  }

  test("the local client gets no rejection bytes: its connection ends with the defect") {
    LocalSubmission.response(Rejection.InputDefect(cause)) match
      case LocalTxSubmissionServer.Response.InputDefect(thrown) => assert(thrown eq cause)
      case other                                                => fail(s"unexpected $other")
    assert(LocalSubmission.reasonItem(Rejection.InputDefect(cause)).isLeft)
  }
