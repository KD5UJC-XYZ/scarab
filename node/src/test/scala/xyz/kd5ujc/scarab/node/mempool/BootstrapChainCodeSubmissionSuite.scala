// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.BootstrapChainCodesReferenceSuite as Corpus
import xyz.kd5ujc.scarab.ledger.TestLedger.{key, txIn}
import xyz.kd5ujc.scarab.protocol.Era

/** A submitted transaction with a bootstrap witness whose chain code is not 32 bytes long reaches
  * the ledger rules, as the reference's transaction decoder reads it (`instance DecCBOR ChainCode`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Keys/Bootstrap.hs:72-78`, cardano-ledger f649f975,
  * checks the length only from decoder version 12, and Conway is read at 9). The Conway
  * transactions of `fixtures/reference/bootstrap-chain-codes/cases.jsonl` ([[Corpus]] says what
  * they hold) are read and judged by the rules, which refuse them: their inputs are not in the
  * synthetic ledger. A transaction that spends an output of the ledger, signed by its key, with an
  * extra bootstrap witness that signs the body, is admitted whatever its chain code's length: the
  * reference checks a bootstrap witness's key and signature only (`verifyBootstrapWit`,
  * `Bootstrap.hs:161-169`), and the witnesses the rules need are a subset of those given.
  */
class BootstrapChainCodeSubmissionSuite extends FunSuite:
  import TxKit.*

  private val rows = Corpus.load.fold(e => fail(e.toString), identity)
  private val carol = key()
  private val attributes = Bytes(Vector(0xa0.toByte))

  private def admitted(raw: Bytes) =
    Source.values.toVector.map(source =>
      source -> MempoolState
        .empty(Right(TxKit.ledger(1)), 1000, None)
        .add(raw, TxKit.env, source)
        .map((pool, entry) => (pool.entries.size, entry.id))
    )

  test("the corpus has a Conway transaction for each chain code length") {
    assertEquals(
      rows
        .filter(r => r.era.contains(Era.Conway) && r.kind == Corpus.Kind.Transaction)
        .flatMap(_.lengths)
        .toSet,
      Corpus.Lengths.toSet
    )
  }

  for row <- rows if row.era.contains(Era.Conway) && row.kind == Corpus.Kind.Transaction do
    test(row.name) {
      assert(row.accepted, s"the reference refuses it: ${row.failure}")
      val tx = MempoolState
        .parseParts(row.raw)
        .fold(r => fail(s"the reference reads it: ${r.message}"), _.tx)
      assertEquals(tx.witnesses.bootstrap.map(_.chainCode), row.lengths.map(Corpus.chainCode))
      // past the decoder the ledger rules judge it, and refuse it: its inputs are not unspent
      for (source, answer) <- admitted(row.raw) do
        answer match
          case Left(Rejection.Ledger(_)) => ()
          case other => fail(s"$source: the ledger rules must answer it, the mempool gave $other")
    }

  for length <- Corpus.Lengths do
    test(
      s"a payment with an extra bootstrap witness whose chain code is $length bytes is admitted"
    ) {
      val code = Corpus.chainCode(length)
      val (raw, id) = tx(
        Vector(txIn(1)),
        Vector(bob.address() -> 99 * ada),
        ada,
        Vector(alice),
        bootstraps = Vector((carol, code, attributes))
      )
      val decoded = MempoolState
        .parseParts(raw)
        .fold(r => fail(s"it decodes: ${r.message}"), _.tx)
      assertEquals(decoded.witnesses.bootstrap.map(_.chainCode), Vector(code))
      for (source, answer) <- admitted(raw) do assertEquals(answer, Right((1, id)), s"$source")
    }
