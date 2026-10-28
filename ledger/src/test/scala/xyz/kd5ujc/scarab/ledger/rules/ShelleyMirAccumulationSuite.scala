// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.ledger.TestGeometry
import cats.data.NonEmptyChain
import cats.syntax.all.*
import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Certificate,
  Credential,
  DelegFailure,
  EpochGeometry,
  LedgerFailure,
  LedgerGlobals,
  Pot,
  PredicateFailure,
  Ptr,
  TestLedger,
  TxEnv
}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, ChainAccountState, Coin, Empty, ShelleyCertState}

/** Source-derived controls for Deleg.hs:351-361,406-428 at ledger f649f975: a recorded
  * negative-update failure does not discard the signed intermediate MIR map. Later certificates
  * must observe it even though the complete transaction will be refused.
  */
class ShelleyMirAccumulationSuite extends FunSuite:
  private type F[A] = RuleT[PredicateFailure, LedgerFailure, ShelleyCertState, A]
  private val credential = Credential.Key(Bytes(Vector.fill(28)(7.toByte)))
  private val globals = Globals(
    LedgerGlobals(BigInt(1000), 100, Rational(1, 20), network = 0),
    EpochGeometry(Instant.EPOCH, 1000, 100, TestGeometry.k(5), TestGeometry.f("0.05"))
  )

  for
    protocol <- Vector(5, 8)
    pot <- Vector(Pot.Reserves, Pot.Treasury)
  do
    test(s"protocol $protocol $pot: a later MIR offsets the negative intermediate entry") {
      val env = ShelleyDelegEnv(
        DelegsEnv(
          TxEnv(1, 0, 0, firstSlotNextEpoch = 100, stabilityWindow = 10),
          TestLedger.params(protocol),
          Bytes(Vector.fill(32)(0.toByte)),
          ChainAccountState(Coin(0), Coin(0))
        ),
        Ptr(1, 0, 0)
      )
      val program = Vector(BigInt(-1), BigInt(1)).traverse_(amount =>
        ShelleyDeleg
          .transition[F](env, Certificate.MoveRewards(pot, Right(Map(credential -> amount))))
      )
      assertEquals(
        program.judge(RuleT.Policy.Accumulate, globals, Empty.certState[Babbage]),
        RuleT.Outcome.Refused(
          NonEmptyChain.one(PredicateFailure.deleg(DelegFailure.MIRProducesNegativeUpdate))
        )
      )
    }
