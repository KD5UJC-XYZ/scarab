// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.ledger.DecodeField
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.*

/** Released f649f975 POOL (`Shelley/Rules/Pool.hs:228-262`, `Shelley/SoftForks.hs:14`). */
class PoolMetadataPredicateSuite extends FunSuite:
  private val owner = TestLedger.key()
  private val id = TestLedger.txIn(91).id
  private def metadata(size: Int, urlSize: Int = 1): Bytes =
    Cbor
      .encode(
        CborValue.Arr(
          Vector(
            xyz.kd5ujc.scarab.codec.Node(CborValue.Text("u" * urlSize), Bytes.empty),
            xyz.kd5ujc.scarab.codec
              .Node(CborValue.ByteString(Bytes(Vector.fill(size)(1.toByte))), Bytes.empty)
          )
        )
      )
      .fold(e => fail(e.toString), identity)

  private def params(size: Int): PoolParams = PoolParams(
    owner.hash,
    Bytes(Vector.fill(32)(2.toByte)),
    0,
    1,
    Rational.zero,
    RewardAccount(0, owner.credential),
    Set(owner.hash),
    Bytes.empty,
    Some(metadata(size))
  )
  private val initial = Empty.ledgerState[Conway](TestLedger.params(10)).cert.pState
  private def run(pp: ProtocolParams, p: PoolParams, state: PState = initial) =
    JudgedSts(Pool)(LedgerFailure.Rejected(id, _))(
      TestLedger.globals,
      CertEnv(id, pp, 5, None, Map.empty),
      state,
      PoolCert.RegPool(p)
    )

  test("only the metadata hash, rather than the encoded URL and metadata, is bounded") {
    for size <- Vector(0, 31, 32) do
      val p = params(size).copy(metadata = Some(metadata(size, 64)))
      assert(p.metadata.exists(_.size > 32))
      val state = run(TestLedger.params(10), p).fold(e => fail(e.toString), identity)
      assertEquals(state.stakePools.values.toVector.map(_.metadata), Vector(p.metadata))
    assert(run(TestLedger.params(10), params(33).copy(metadata = None)).isRight)
  }

  test("metadata hashes over 32 are accepted through 4.0, refused from 4.1") {
    for major <- Vector(2, 3, 4, 5, 7, 9, 10, 11); minor <- Vector(0, 1) do
      val pp = TestLedger.params(major).copy(protocolMinor = minor)
      val result = run(pp, params(33))
      if major < 4 || (major == 4 && minor == 0) then assert(result.isRight)
      else
        assertEquals(
          result,
          Left(
            LedgerFailure.Rejected(
              id,
              PredicateFailure.pool(PoolFailure.PoolMedataHashTooBig(owner.hash, 33))
            )
          )
        )
  }

  test("metadata refusal applies to re-registration and leaves pools, future and VRFs unchanged") {
    val pp = TestLedger.params(11)
    val state = run(pp, params(32)).fold(e => fail(e.toString), identity)
    val original = state
    for size <- Vector(33, 64, 256) do
      assertEquals(
        run(pp, params(size), state),
        Left(
          LedgerFailure.Rejected(
            id,
            PredicateFailure.pool(PoolFailure.PoolMedataHashTooBig(owner.hash, size))
          )
        )
      )
      assertEquals(state, original)
    val next = run(pp, params(31), state).fold(e => fail(e.toString), identity)
    assertEquals(
      next.future.values.toVector.map(_.metadata.map(_.bytes)),
      Vector(Some(metadata(31)))
    )
  }

  test("network precedes metadata and metadata precedes cost for a multiply invalid registration") {
    val pp = TestLedger.params(11).copy(minPoolCost = 10)
    val p = params(33).copy(cost = 0)
    assertEquals(
      run(pp, p),
      Left(
        LedgerFailure.Rejected(
          id,
          PredicateFailure.pool(PoolFailure.PoolMedataHashTooBig(owner.hash, 33))
        )
      )
    )
    val wrong = p.copy(rewardAccount = p.rewardAccount.copy(network = 1))
    assertEquals(
      run(pp, wrong),
      Left(
        LedgerFailure.Rejected(
          id,
          PredicateFailure.pool(PoolFailure.WrongNetworkPool(Mismatch(1, 0), owner.hash))
        )
      )
    )
  }

  test("signed LEDGER admission enforces metadata before spending inputs or paying pool deposits") {
    // Conway's LEDGER on the era types, on transactions with true bytes
    // ([[era.conway.ConwayTestKit.ledger]])
    import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit
    val pp = TestLedger.params(10)
    val input = TestLedger.txIn(89)
    val funds = BigInt(1000000000)
    val funded =
      NativeRules.stateUnder[Conway](pp, Vector(input -> ConwayTestKit.out(owner.address(), funds)))
    val fee = ConwayTestKit.maxFee(pp)
    def transaction(size: Int) = ConwayTestKit.tx(
      ConwayTestKit.Body(
        Vector(input),
        Vector(ConwayTestKit.out(owner.address(), funds - pp.poolDeposit - fee)),
        fee,
        certificates = Vector(Certificate.RegisterPool(params(size)))
      ),
      Vector(owner)
    )
    def admit(tx: ConwayTestKit.Built) = ConwayTestKit.ledger(funded, tx, TestLedger.env)
    val bad = transaction(33)
    val original = bad.parts
    assertEquals(
      admit(bad),
      Left(
        Vector(
          ConwayTestKit.Fails.pool(
            era.rules.ShelleyPoolPredFailure.PoolMedataHashTooBig(
              Convert.keyHash[StakePool](owner.hash).fold(e => fail(s"$e"), identity),
              33
            )
          )
        )
      )
    )
    assertEquals(bad.parts, original)
    val accepted = admit(transaction(32)).fold(e => fail(s"refused: $e"), _.es.ls)
    assertEquals(accepted.utxo.deposited.value, pp.poolDeposit)
    assertEquals(accepted.utxo.fees.value, fee)
    assert(!accepted.utxo.utxo.utxo.contains(input))
    assertEquals(accepted.cert.pState.stakePools.size, 1)
  }

  test("malformed metadata has a typed decode failure instead of becoming a size predicate") {
    val wrong = params(33).copy(metadata = Some(Bytes(Vector(0x80.toByte))))
    assertEquals(
      run(TestLedger.params(10), wrong),
      Left(
        LedgerFailure.Undecodable(
          id,
          TxDefect.PoolMetadata(LedgerDecodeError.ArrayLength(DecodeField.PoolMetadata, 2, 0))
        )
      )
    )
  }
