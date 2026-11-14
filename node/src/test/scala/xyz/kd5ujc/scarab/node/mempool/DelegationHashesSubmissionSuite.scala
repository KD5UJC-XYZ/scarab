// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{LedgerDecodeError, TransactionDecodeProblem}
import xyz.kd5ujc.scarab.ledger.DelegationHashesReferenceSuite as Corpus

/** A submitted transaction whose delegation certificate holds a hash that is not 28 bytes long, the
  * class of the November 2025 mainnet partition, is refused by the mempool before the ledger rules
  * run, as the reference's transaction decoder refuses it: the lines of
  * `fixtures/reference/delegation-hashes/cases.jsonl` with their verdicts ([[Corpus]] says what
  * they hold). An accepted line is read with the certificate it holds and reaches the ledger rules,
  * which refuse it: its inputs are not in the synthetic ledger.
  */
class DelegationHashesSubmissionSuite extends FunSuite:
  private val rows = Corpus.load.fold(e => fail(e.toString), identity)

  test("the lines are there, refused and accepted") {
    assert(rows.exists(_.accepted))
    assert(rows.exists(r => !r.accepted && r.length == 56))
  }

  for row <- rows do
    test(row.name) {
      val parsed = MempoolState.parseParts(row.raw)
      val admitted = Source.values.toVector.map(source =>
        source -> MempoolState
          .empty(Right(TxKit.ledger(1)), 1000, None)
          .add(row.raw, TxKit.env, source)
          .map((pool, entry) => (pool.entries.size, entry.id))
      )
      row.place match
        case None =>
          val tx = parsed.fold(r => fail(s"the reference accepts it: ${r.message}"), _.tx)
          assertEquals(tx.body.certificates, Vector(Corpus.expected(row)))
          // past the decoder the ledger rules judge it, and refuse it: its inputs are not unspent
          for (source, answer) <- admitted do
            answer match
              case Left(Rejection.Ledger(_)) => ()
              case other =>
                fail(s"$source: the ledger rules must answer it, the mempool gave $other")
        case Some(place) =>
          val refused = Rejection.Malformed(
            TransactionDecodeProblem.Ledger(
              LedgerDecodeError.ByteLength(place.field, 28, row.length)
            )
          )
          assertEquals(parsed.map(_.id), Left(refused))
          for (source, answer) <- admitted do assertEquals(answer, Left(refused), s"$source")
    }
