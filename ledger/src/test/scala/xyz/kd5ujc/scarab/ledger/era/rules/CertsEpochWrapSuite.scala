// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.NonEmptyChain
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Mismatch, PoolFailure, PredicateFailure, TestLedger}
import xyz.kd5ujc.scarab.ledger.era.{PParams, PoolCert}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPParams
import xyz.kd5ujc.scarab.ledger.rules.Judgment
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  Credential,
  DRepRole,
  DRepState,
  Empty,
  EpochInterval,
  EpochNo,
  KeyHash,
  Sorted,
  StakePool,
  Staking,
  VState
}

/** The epoch arithmetic of the era-typed certificate rules at the `Word64` bounds, where the
  * reference's `addEpochInterval` and `binOpEpochNo` (cardano-base c1e1ed8d
  * `cardano-slotting/src/Cardano/Slotting/Slot.hs:153-158`) wrap modulo 2^64. Each expected value
  * is the reference's result written out.
  */
class CertsEpochWrapSuite extends FunSuite:
  private val top: BigInt = (BigInt(1) << 64) - 1

  private def epoch(n: BigInt): EpochNo =
    EpochNo.fromBigInt(n).fold(e => fail(s"epoch $n: $e"), identity)

  private def interval(n: Long): EpochInterval =
    EpochInterval.fromBigInt(BigInt(n)).fold(e => fail(s"interval $n: $e"), identity)

  test("computeDRepExpiry wraps: (2^64 - 1) + 2 - 0 is 1, and 0 + 0 - 1 is 2^64 - 1"):
    assertEquals(GOVCERT.computeDRepExpiry(interval(2), epoch(top), epoch(0)).toBigInt, BigInt(1))
    assertEquals(GOVCERT.computeDRepExpiry(interval(0), epoch(0), epoch(1)).toBigInt, top)
    assertEquals(
      GOVCERT.computeDRepExpiry(interval(20), epoch(100), epoch(3)).toBigInt,
      BigInt(117)
    )

  test(
    "computeDRepExpiryVersioned wraps: at 9 (2^64 - 1) + 1 is 0, at 10 the dormant epochs come off"
  ):
    def pp(major: Int, activity: Int): PParams[Conway] =
      PParams[Conway](
        ConwayPParams
          .fromStored(TestLedger.params(major).copy(drepActivity = activity))
          .fold(e => fail(e.toString), identity)
      )
    assertEquals(
      GOVCERT.computeDRepExpiryVersioned[Conway](pp(9, 1), epoch(top), epoch(5)).toBigInt,
      BigInt(0)
    )
    assertEquals(
      GOVCERT.computeDRepExpiryVersioned[Conway](pp(10, 1), epoch(top), epoch(5)).toBigInt,
      top - 4
    )

  test("updateDormantDRepExpiry: an expiry that wraps below the current epoch is kept"):
    val wraps = Credential.KeyHashObj[DRepRole](keyHash[DRepRole](1))
    val moves = Credential.KeyHashObj[DRepRole](keyHash[DRepRole](2))
    val stays = Credential.KeyHashObj[DRepRole](keyHash[DRepRole](3))
    def drep(expiry: BigInt) =
      DRepState(
        epoch(expiry),
        None,
        xyz.kd5ujc.scarab.ledger.state.CompactCoin.zero,
        Sorted.emptySet[Credential[Staking]]
      )
    val before = VState(
      Sorted.map(Vector(wraps -> drep(top), moves -> drep(20), stays -> drep(5))),
      Empty.certState[Conway].vState.committeeState,
      epoch(2)
    )
    val after = CERTS.updateDormantDRepExpiry(epoch(10), before)
    // (2^64 - 1) + 2 wraps to 1, below epoch 10: kept; 20 + 2 = 22; 5 + 2 = 7, below 10: kept
    assertEquals(
      after.dreps.toVector.map((c, d) => c -> d.expiry.toBigInt),
      Vector(wraps -> top, moves -> BigInt(22), stays -> BigInt(5))
    )
    assertEquals(after.numDormantEpochs.toBigInt, BigInt(0))

  test("a retirement against a limit that wraps: epoch 2^64 - 2 and eMax 2 refuse 2^64 - 1"):
    val pp = PParams[Conway](
      ConwayPParams
        .fromStored(TestLedger.params(10).copy(eMax = 2))
        .fold(e => fail(e.toString), identity)
    )
    val pool = keyHash[StakePool](9)
    val verdict = Judgment.run(POOL[Conway], Judgment.Policy.Accumulate)(
      TestLedger.globals,
      PoolEnv[Conway](epoch(top - 1), pp),
      Empty.pState,
      PoolCert.RetirePool(pool, epoch(top))
    )
    // the limit is (2^64 - 2) + 2, which wraps to 0, so no retirement epoch is within it; the
    // failures are in the order the reference reports them, the last recorded first
    assertEquals(
      verdict,
      Right(
        Left(
          NonEmptyChain(
            PredicateFailure.pool(
              PoolFailure.StakePoolRetirementWrongEpoch(
                Mismatch(top, top - 1),
                Mismatch(top, BigInt(0))
              )
            ),
            PredicateFailure.pool(PoolFailure.StakePoolNotRegisteredOnKey(pool.bytes))
          )
        )
      )
    )

  private def keyHash[R <: xyz.kd5ujc.scarab.ledger.state.KeyRole](seed: Int): KeyHash[R] =
    KeyHash
      .fromBytes[R](Bytes.fromArray(Array.fill(28)(seed.toByte)))
      .fold(e => fail(e.toString), identity)
