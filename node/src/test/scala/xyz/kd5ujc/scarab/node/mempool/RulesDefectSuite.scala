// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import munit.FunSuite
import xyz.kd5ujc.scarab.network.LocalTxSubmissionServer

/** The ledger rules that throw while they judge a submitted transaction (issue 564): no verdict on
  * it and no refusal of it, and the connection it came from ends while the node goes on, as in the
  * reference, where the mempool adds a transaction in the mini-protocol's thread (consensus
  * 82ecba329 `Mempool/Update.hs` `doAddTx`). The local client gets no answer and its connection
  * ends with a `ServingDefect` of LocalTxSubmission; a peer's TxSubmission2 session ends the same
  * way (`Mempool.sink`), and the peer is not charged (`PeerDefectPolicySuite`,
  * `PeerSelectionSuite`).
  */
class RulesDefectSuite extends FunSuite:
  private val cause = new IllegalStateException("injected ledger rule defect")

  test("an exception of the rules is a RulesDefect, with its cause") {
    MempoolState.judging[Unit](throw cause) match
      case Left(Rejection.RulesDefect(thrown)) => assert(thrown eq cause)
      case other                               => fail(s"unexpected $other")
  }

  test("control: the rules' own refusal passes through unchanged") {
    val refused = Rejection.WrongEra(5)
    assertEquals(MempoolState.judging[Unit](Left(refused)), Left(refused))
  }

  test("the local client gets no rejection bytes: its connection ends, the node goes on") {
    LocalSubmission.response(Rejection.RulesDefect(cause)) match
      case LocalTxSubmissionServer.Response.ServingDefect(thrown) => assert(thrown eq cause)
      case other                                                  => fail(s"unexpected $other")
    assert(LocalSubmission.reasonItem(Rejection.RulesDefect(cause)).isLeft)
  }
