// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{Tx, TxEnvelopeReferenceSuite}
import xyz.kd5ujc.scarab.protocol.Era

/** The mempool reads a submitted Conway transaction as the reference reads it, with or without its
  * validity flag, definite or indefinite (`decodeRecordNamed "AlonzoTx"`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:532-553`, cardano-ledger f649f975), and would
  * relay what the reference relays for it: the Conway lines of
  * `fixtures/reference/tx-envelope/cases.jsonl` with their verdicts, ids and relayed bytes (#699).
  */
class TxEnvelopeSubmissionSuite extends FunSuite:
  private val rows = TxEnvelopeReferenceSuite.load.filter(_.era == Era.Conway)

  test("the Conway lines are there, with and without the flag") {
    assert(rows.exists(r => r.accepted && r.name.endsWith(" without its flag")))
    assert(rows.exists(r => !r.accepted))
  }

  for row <- rows do
    test(row.name) {
      MempoolState.parseParts(row.raw) match
        case Right(parsed) =>
          assert(row.accepted, s"the reference refuses this encoding: ${row.failure}")
          row.txid.foreach(id => assertEquals(parsed.id.hex, id, "the transaction id"))
          row.wire.foreach(w => assertEquals(Tx.envelope(parsed.parts).hex, w, "the relayed bytes"))
        case Left(r) => assert(!row.accepted, s"the reference accepts this encoding: ${r.message}")
    }
