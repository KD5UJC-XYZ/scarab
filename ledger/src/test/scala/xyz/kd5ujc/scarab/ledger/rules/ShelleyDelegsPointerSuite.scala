// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Certificate,
  Credential,
  EpochGeometry,
  LedgerFailure,
  LedgerGlobals,
  PredicateFailure,
  Ptr,
  TestGeometry,
  TestLedger
}
import xyz.kd5ujc.scarab.ledger.state.*

/** The pointer DELEGS gives a certificate (cardano-ledger f649f975
  * `Shelley/Rules/Delegs.hs:192-194`): `Ptr (SlotNo32 (fromIntegral slot64)) txIx certIx`, so the
  * reference keeps the low 32 bits of the slot (`Credential.hs:233`) and 16 of the certificate
  * index, where the node gives no verdict (overflow-improvements.md#delegs-pointer); the
  * transaction index is the bounded `TxIx` LEDGERS enumerates (`Shelley/Rules/Ledgers.hs:201-211`,
  * `BaseTypes.hs:918-921`).
  */
class ShelleyDelegsPointerSuite extends FunSuite:
  private type F[A] = RuleT[PredicateFailure, LedgerFailure, ShelleyCertState, A]
  private val geometry =
    EpochGeometry(Instant.EPOCH, 1000, 10000, TestGeometry.k(5), TestGeometry.f("0.05"))

  private val globals = Globals(
    LedgerGlobals(TestLedger.Supply, 100, Rational(1, 2), network = 0, updateQuorum = 2),
    geometry
  )

  private def delegs(
      slot: BigInt,
      txIx: Int,
      params: xyz.kd5ujc.scarab.ledger.ProtocolParams = TestLedger.params(2),
      key: Bytes = Bytes.fromArray(Array.fill(28)(1.toByte))
  ): Either[LedgerFailure, ShelleyCertState] =
    val start = NewEpochState
      .empty[Shelley](EpochNo.fromBigInt(5).fold(e => fail(e.toString), identity), params)
      .es
      .ls
      .cert
    val env = DelegsEnv(
      TestLedger.env.copy(slot = slot, txIndex = txIx),
      params,
      Bytes.empty,
      ChainAccountState(Coin.zero, Coin.zero)
    )
    ShelleyDelegs
      .transition[F](env, Vector(Certificate.Register(Credential.Key(key), None)))
      .judge(RuleT.Policy.Accumulate, globals, start) match
      case RuleT.Outcome.Raised(failure)   => Left(failure)
      case RuleT.Outcome.Refused(failures) => Left(LedgerFailure.Rejected(Bytes.empty, failures))
      case RuleT.Outcome.Passed(state, _)  => Right(state)

  private def pointers(slot: BigInt, txIx: Int): Set[Ptr] =
    delegs(slot, txIx).fold(e => fail(s"refused: $e"), _.dState.accounts.ptrs.keySet.toSet)

  // `compactCoinOrError (pp ^. ppKeyDepositL)` (`Shelley/Rules/Deleg.hs:268`, `Coin.hs:184-188`)
  test("a key deposit of the parameters outside a CompactForm Coin is the state's, not a refusal") {
    val deposit = BigInt(1) << 64
    assertEquals(
      delegs(10, 0, TestLedger.params(2).copy(keyDeposit = deposit)),
      Left(
        LedgerFailure.StateOutOfRange(
          Bytes.empty,
          VocabError.OutOfRange(VocabError.Kind.CompactCoin, deposit)
        )
      )
    )
  }

  test("a credential of the transaction outside its vocabulary type stays a refusal") {
    delegs(10, 0, key = Bytes.fromArray(Array.fill(3)(1.toByte))) match
      case Left(LedgerFailure.Vocab(_)) => ()
      case other                        => fail(s"expected a refusal, got $other")
  }

  test("a registration past slot 2^32 - 1 is no verdict, where the reference keeps 32 bits") {
    val slot = (BigInt(1) << 32) + 7
    assertEquals(
      delegs(slot, 3),
      Left(
        LedgerFailure.StateOutOfRange(
          Bytes.empty,
          VocabError.OutOfRange(VocabError.Kind.Word32, slot)
        )
      )
    )
    assertEquals(
      delegs(BigInt(1) << 32, 3),
      Left(
        LedgerFailure.StateOutOfRange(
          Bytes.empty,
          VocabError.OutOfRange(VocabError.Kind.Word32, BigInt(1) << 32)
        )
      )
    )
  }

  test("a slot that fits 32 bits is kept") {
    assertEquals(pointers(BigInt(0xffffffffL), 0), Set(Ptr(BigInt(0xffffffffL), 0, 0)))
  }

  test("a certificate index past 16 bits is refused, where the reference keeps its low 16 bits") {
    assertEquals(
      Ptr.ofCertificate(10, 1, 0x10002),
      Left(VocabError.OutOfRange(VocabError.Kind.Word16, 0x10002))
    )
    assertEquals(Ptr.ofCertificate(10, 1, 0xffff), Right(Ptr(10, 1, 0xffff)))
  }

  // `zip [minBound ..] txs`: `[minBound ..] :: [TxIx]` ends at 65,535 and `zip` stops there
  test("LEDGERS enumerates positions 0 to 65,535 and gives no position to a later transaction") {
    val txs = Vector.tabulate(TxIx.count + 2)(identity)
    val positions = TxIx.enumerate(txs)
    assertEquals(positions.size, 65536)
    assertEquals(positions.map(_._2.index), Vector.range(0, 65536))
    assertEquals(positions.last._1, 65535)
    assertEquals(
      TxIx.enumerate(Vector("a", "b")).map((a, ix) => (a, ix.index)),
      Vector("a" -> 0, "b" -> 1)
    )
  }

  test("the pointer of a certificate of the last position fits the persisted Word16") {
    val last = pointers(BigInt(12), TxIx.count - 1)
    assertEquals(last, Set(Ptr(12, 65535, 0)))
    assert(last.forall(_.fits))
    assert(!Ptr(12, 65536, 0).fits)
  }
