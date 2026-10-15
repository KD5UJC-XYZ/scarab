// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.data.Kleisli
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger as node

/** The ledger-side mirror on the states the rule suites build around real transactions (recorded on
  * Preview, and the Preprod case): the UTxO each one spends, the accounts it withdraws from, the
  * protocol parameters of its era. Small states, but with real credentials, addresses (so the
  * instant stake is real) and the era's protocol version.
  */
class RulesMirrorSuite extends FunSuite:
  type Result[A] = Either[MirrorError, A]
  type M[A] = Kleisli[Result, MirrorEnv, A]

  private val mirror = LedgerMirror[M]
  private val ada = BigInt(1000000)
  private val at: WithOrigin[SlotNo] = WithOrigin.At(SlotNo.fromUnsigned(1000L))

  private val cases: Vector[node.PlutusCase] =
    node.PlutusCase.all :+ node.PlutusCase.load(Path.of("fixtures/preprod/plutus/tx-2edd684f.json"))

  private def world(c: node.PlutusCase): node.LedgerState =
    val conway = c.protocolMajor >= 9
    val withdrawing = c.tx.body.withdrawals.map { (account, amount) =>
      account.credential -> node.Account(
        amount,
        2 * ada,
        None,
        Option.when(conway)(node.DRep.AlwaysAbstain)
      )
    }
    node.TestLedger
      .state(c.protocolMajor, (c.spent ++ c.references ++ c.collateral).toVector)
      .copy(params = c.params, epoch = c.slot / 86400, accounts = withdrawing.toMap)

  private def roundTrip[E <: EraTag](s: node.LedgerState)(using
      ShelleyBasedEra[E]
  ): (Result[Mirrored[LedgerImage[E]]], Result[node.LedgerState]) =
    val image = mirror.to[E](at, s).run(FlatStateGen.env)
    (image, image.flatMap(m => mirror.from[E](at, m.value).run(FlatStateGen.env)))

  test("the fixtures hold cases to build states from") {
    assert(cases.nonEmpty)
  }

  test("the state around every recorded transaction comes back from its mirror") {
    cases.foreach { c =>
      val s = world(c)
      val (image, back) =
        if c.protocolMajor >= 9 then
          val (i, b) = roundTrip[Conway](s)
          (i.map(_.value.extras.residue.isEmpty), b)
        else
          val (i, b) = roundTrip[Babbage](s)
          (i.map(_.value.extras.residue.isEmpty), b)
      assertEquals(image, Right(true), c.name)
      assertEquals(back, Right(LedgerMirror.normalised(s)), c.name)
    }
  }
