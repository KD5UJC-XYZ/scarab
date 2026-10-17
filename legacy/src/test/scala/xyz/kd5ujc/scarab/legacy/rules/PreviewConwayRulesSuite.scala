// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.legacy.*
import scala.annotation.unused
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.plutus.ScalusEvaluator
import munit.FunSuite
import xyz.kd5ujc.scarab.legacy.FlatTestLedger.*

import TestLedger.*

/** The Conway transaction rules on real Preview transactions: governance proposals that carry the
  * constitution's guardrails script (Plutus V3). The ledger state around each transaction is built
  * from the outputs it spends, its collateral, the registered return accounts and the roots its
  * proposals name; everything else is the transaction as it was on chain. UTXOW (witnesses,
  * scripts, the script data hash), UTXO (fee, balance with the proposal deposit, collateral), GOV
  * and phase 2 (the real script against our script context) must accept it.
  */
class PreviewConwayRulesSuite extends FunSuite:
  private val rs = RuleSet
  private val ada = BigInt(1000000)

  private val cases =
    PlutusCase.all.filter(c => c.tx.body.proposals.nonEmpty && c.collateral.nonEmpty)

  /** Transactions that need no more ledger state than their inputs and their withdrawals. */
  private val spends = PlutusCase.all.filter(c =>
    c.protocolMajor >= 10 && c.collateral.nonEmpty && c.tx.isValid &&
      c.tx.body.proposals.isEmpty && c.tx.body.votes.isEmpty && c.tx.body.certificates.isEmpty
  )

  private def worldOf(c: PlutusCase): LedgerState =
    val body = c.tx.body
    val utxo = c.spent ++ c.references ++ c.collateral
    val policy = body.proposals.collectFirst {
      case Proposal(_, _, GovAction.ParameterChange(_, _, Some(p)), _)  => p
      case Proposal(_, _, GovAction.TreasuryWithdrawals(_, Some(p)), _) => p
    }
    val roots = body.proposals.foldLeft(PrevIds()) { (r, p) =>
      p.action match
        case GovAction.ParameterChange(prev, _, _) => r.copy(paramUpdate = prev)
        case _                                     => r
    }
    val withdrawing = body.withdrawals.map { case (account, amount) =>
      account.credential -> Account(amount, 2 * ada, None, Some(DRep.AlwaysAbstain))
    }
    val credentials =
      body.proposals.map(_.returnAccount.credential) ++ body.proposals.flatMap(_.action match
        case GovAction.TreasuryWithdrawals(ws, _) => ws.map(_._1.credential)
        case _                                    => Vector.empty)
    val params = TestLedger
      .params(c.protocolMajor)
      .copy(
        minFeeA = 44,
        minFeeB = 155381,
        maxTxSize = 16384,
        maxValueSize = 5000,
        coinsPerUTxOByte = 4310,
        keyDeposit = 2 * ada,
        priceMem = Rational(577, 10000),
        priceSteps = Rational(721, 10000000),
        maxTxExUnits = ExUnits(17500000, BigInt("10000000000")),
        maxBlockExUnits = ExUnits(77500000, BigInt("20000000000")),
        collateralPercentage = 150,
        maxCollateralInputs = 3,
        govActionDeposit = 1000 * ada,
        govActionLifetime = 30,
        minFeeRefScriptCostPerByte = Rational(15),
        costModels = c.costModels
      )
    val base = state(c.protocolMajor, utxo.toVector).copy(
      params = params,
      epoch = c.slot / 86400,
      accounts =
        credentials.distinct.map(_ -> Account(0, 2 * ada, None, None)).toMap ++ withdrawing,
      treasury = 1000000000L * ada,
      gov = GovState.empty.copy(
        constitution = Constitution(GovState.noAnchor, policy),
        proposals = Proposals(Vector.empty, roots)
      )
    )
    val registered = base.copy(deposited = FlatTestLedger.obligation(base))
    registered.copy(reserves = Supply - (registered.total - registered.reserves))

  private def envOf(c: PlutusCase, @unused s: LedgerState): TxEnv =
    TxEnv(
      slot = c.slot,
      epoch = c.slot / 86400,
      network = 0,
      firstSlotNextEpoch = (c.slot / 86400 + 1) * 86400,
      stabilityWindow = 129600,
      clock = Some(slot => slot * 1000 + 1666656000000L),
      evaluator = ScalusEvaluator.default
    )

  test("the captured proposal transactions are present") {
    assert(cases.size >= 4, cases.size)
    assert(
      cases.exists(_.tx.body.proposals.exists(_.action.isInstanceOf[GovAction.ParameterChange]))
    )
    assert(
      cases.exists(_.tx.body.proposals.exists(_.action.isInstanceOf[GovAction.TreasuryWithdrawals]))
    )
  }

  cases.foreach { c =>
    test(
      s"${c.name}: the Conway rules accept the proposal transaction and run its guardrails script"
    ) {
      val s = worldOf(c)
      assertEquals(s.total, Supply)
      val after = rs.tx(s, c.tx, envOf(c, s)).fold(f => fail(f.message), identity)
      val body = c.tx.body
      assertEquals(after.fees, body.fee)
      assertEquals(after.gov.proposals.actions.size, body.proposals.size)
      assertEquals(after.deposited, s.deposited + body.proposals.map(_.deposit).sum)
      assertEquals(after.plutus.succeeded, 1L)
      assertEquals(after.plutus.integrityChecked, 1L)
      assertEquals(after.total, Supply)
      // and the same transaction with another guardrails script is refused
      val other =
        s.copy(gov = s.gov.copy(constitution = s.gov.constitution.copy(script = Some(body.id))))
      rs.tx(other, c.tx, envOf(c, other)) match
        case Left(
              LedgerFailure.Rejected(
                _,
                PredicateFailure.Gov(GovFailure.InvalidGuardrailsScriptHash(_, _))
              )
            ) =>
          ()
        case other => fail(s"$other")
    }
  }

  test("the transactions that only spend, mint and withdraw are present") {
    assert(spends.size >= 4, spends.size)
  }

  spends.foreach { c =>
    test(s"${c.name}: the Conway rules accept the Plutus transaction (${c.kind})") {
      val s = worldOf(c)
      val after = rs.tx(s, c.tx, envOf(c, s)).fold(f => fail(f.message), identity)
      assertEquals(after.fees, c.tx.body.fee)
      assert(after.plutus.succeeded >= 1)
      assertEquals(after.total, Supply)
      c.tx.body.inputs.foreach(i => assert(!after.utxo.contains(i)))
    }
  }

  /** Votes of script committee members on a parameter-change proposal. */
  private val voting = PlutusCase.all.filter(c =>
    c.protocolMajor >= 10 && c.collateral.nonEmpty && c.tx.isValid && c.tx.body.votes.nonEmpty
  )

  test("the captured committee votes are present") {
    assert(voting.size >= 3, voting.size)
  }

  voting.foreach { c =>
    test(s"${c.name}: the Conway rules accept the committee vote of a script member") {
      val body = c.tx.body
      val voters = body.votes.collect { case (Voter.CommitteeHot(h), vs) => h -> vs }
      assertEquals(voters.size, body.votes.size)
      val epoch = c.slot / 86400
      val world = worldOf(c)
      val cold = voters.zipWithIndex.map { case ((hot, _), i) =>
        Credential.Key(
          xyz.kd5ujc.scarab.crypto.Blake2b.hash224
            .hash(xyz.kd5ujc.scarab.codec.Bytes(Vector(i.toByte, 9)))
        ) -> hot
      }
      val actions = voters
        .flatMap(_._2.map(_._1))
        .distinct
        .map { id =>
          GovActionState(
            id,
            Map.empty,
            Map.empty,
            Map.empty,
            Proposal(
              1000 * ada,
              RewardAccount(0, cold.head._1),
              GovAction
                .ParameterChange(
                  None,
                  ParamUpdate(Map(16 -> xyz.kd5ujc.scarab.codec.Bytes(Vector(0x01)))),
                  None
                )
            ),
            epoch - 5,
            epoch + 5
          )
        }
      val s = world.copy(
        gov = world.gov.copy(
          committee = Some(Committee(cold.map(_._1 -> (epoch + 50)).toMap, Rational(2, 3))),
          proposals = Proposals(actions, PrevIds())
        ),
        committeeState = cold.map { case (c, hot) => c -> CommitteeAuth.Hot(hot) }.toMap
      )
      val after = rs.tx(s, c.tx, envOf(c, s)).fold(f => fail(f.message), identity)
      val recorded = after.gov.proposals.actions.flatMap(_.committeeVotes.values)
      assertEquals(recorded.size, voters.flatMap(_._2).size)
      assert(recorded.forall(_ == Vote.Yes))
      assertEquals(after.total, Supply)
    }
  }
