// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.data.NonEmptyChain
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, PredicateFailure, UtxowFailure}
import xyz.kd5ujc.scarab.ledger.TestLedger.{Key, key, txIn}

/** A submitted transaction with two bootstrap witnesses of one address root. The reference reads
  * them into a `Set` whose `Ord` compares the roots alone, which keeps the last one read (`Ord
  * BootstrapWitness`, `libs/cardano-ledger-core/src/Cardano/Ledger/Keys/Bootstrap.hs:108-109`,
  * cardano-ledger f649f975; `fixtures/reference/bootstrap-duplicates`), and checks the signature of
  * that one only (`validateVerifiedWits`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/
  * Utxow.hs:391-407`). A payment that spends an output of the ledger, signed by its key, with an
  * extra bootstrap witness whose signature does not verify followed by one of the same root whose
  * signature does, is admitted, and with the two the other way round refused with
  * `InvalidWitnesses`, through local and remote submission; of three, the last decides, also when
  * it repeats the first byte for byte. Witnesses of two roots are both checked.
  */
class BootstrapDuplicateSubmissionSuite extends FunSuite:
  import TxKit.*

  private val carol = key()
  private val dave = key()
  private val code = Bytes(Vector.fill(32)(0x92.toByte))
  private val attributes = Bytes(Vector(0xa0.toByte))

  private def judged(raw: Bytes) =
    Source.values.toVector.map(source =>
      source -> MempoolState
        .empty(Right(TxKit.ledger(1)), 1000, None)
        .add(raw, TxKit.env, source)
        .map((pool, entry) => (pool.entries.size, entry.id))
    )

  private def payment(bootstraps: Vector[Key], invalid: Set[Int]) =
    tx(
      Vector(txIn(1)),
      Vector(bob.address() -> 99 * ada),
      ada,
      Vector(alice),
      bootstraps = bootstraps.map(k => (k, code, attributes)),
      invalidBootstraps = invalid
    )

  private def invalidWitnesses(id: Bytes, keys: Key*) =
    Left(
      Rejection.Ledger(
        LedgerFailure.Rejected(
          id,
          NonEmptyChain.one(
            PredicateFailure.utxow(UtxowFailure.InvalidWitnesses(keys.toVector.map(_.vkey)))
          )
        )
      )
    )

  test("an invalid bootstrap witness followed by a valid one of its root is admitted") {
    val (raw, id) = payment(Vector(carol, carol), Set(0))
    for (source, answer) <- judged(raw) do assertEquals(answer, Right((1, id)), s"$source")
    val decoded = MempoolState.parseParts(raw).fold(r => fail(s"it decodes: ${r.message}"), _.tx)
    assertEquals(decoded.witnesses.bootstrap.size, 1, "one witness per root")
  }

  test("a valid bootstrap witness followed by an invalid one of its root is refused") {
    val (raw, id) = payment(Vector(carol, carol), Set(1))
    for (source, answer) <- judged(raw) do
      assertEquals(answer, invalidWitnesses(id, carol), s"$source")
  }

  test("the last of three witnesses of one root decides, a repeated copy of the first included") {
    val (refused, refusedId) = payment(Vector(carol, carol, carol), Set(0, 2))
    for (source, answer) <- judged(refused) do
      assertEquals(answer, invalidWitnesses(refusedId, carol), s"$source, invalid last")
    val (admitted, admittedId) = payment(Vector(carol, carol, carol), Set(1))
    for (source, answer) <- judged(admitted) do
      assertEquals(answer, Right((1, admittedId)), s"$source, valid last")
  }

  test("an invalid bootstrap witness of another root is refused whatever its place") {
    for invalid <- Vector(0, 1) do
      val keys = if invalid == 0 then Vector(dave, carol) else Vector(carol, dave)
      val (raw, id) = payment(keys, Set(invalid))
      for (source, answer) <- judged(raw) do
        assertEquals(answer, invalidWitnesses(id, dave), s"$source, the invalid one at $invalid")
  }
