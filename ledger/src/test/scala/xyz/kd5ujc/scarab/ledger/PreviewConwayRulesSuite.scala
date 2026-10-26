// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.plutus.ScalusEvaluator
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Counted, EraLedger, NativeRules}
import xyz.kd5ujc.scarab.ledger.state.{
  Build,
  ColdCommittee,
  Coin,
  CommitteeAuthorization,
  CommitteeState,
  CompactCoin,
  Conway,
  ConwayAccountState,
  ConwayAccounts,
  ConwayGovState,
  ConwayLedgerState,
  ConwayNewEpochState,
  HotCommittee,
  LedgerState,
  NewEpochState,
  Sorted,
  Staking,
  UTxOState,
  VState,
  VocabError
}
import xyz.kd5ujc.scarab.protocol.Era

import TestLedger.*

/** The Conway transaction rules on real Preview transactions: governance proposals that carry the
  * constitution's guardrails script (Plutus V3). The ledger state around each transaction is built
  * from the outputs it spends, its collateral, the registered return accounts and the roots its
  * proposals name; everything else is the transaction as it was on chain. UTXOW (witnesses,
  * scripts, the script data hash), UTXO (fee, balance with the proposal deposit, collateral), GOV
  * and phase 2 (the real script against our script context) must accept it.
  */
class PreviewConwayRulesSuite extends FunSuite:
  private val ada = BigInt(1000000)

  private val cases =
    PlutusCase.all.filter(c => c.tx.body.proposals.nonEmpty && c.collateral.nonEmpty)

  /** Transactions that need no more ledger state than their inputs and their withdrawals. */
  private val spends = PlutusCase.all.filter(c =>
    c.protocolMajor >= 10 && c.collateral.nonEmpty && c.tx.isValid &&
      c.tx.body.proposals.isEmpty && c.tx.body.votes.isEmpty && c.tx.body.certificates.isEmpty
  )

  private def ok[A](value: Either[VocabError, A]): A = value.fold(e => fail(e.toString), identity)

  private def compact(n: BigInt): CompactCoin = ok(CompactCoin.fromBigInt(n))

  /** Everything the ledger holds, in lovelace: the UTxO, the fees, the deposits, the two pots, the
    * account balances and the donations. It is the supply when the ledger is consistent.
    */
  private def total(nes: NewEpochState[Conway]): BigInt =
    val es = nes.es
    es.ls.utxo.utxo.utxo.coin + es.ls.utxo.fees.value + es.ls.utxo.deposited.value +
      es.chainAccount.treasury.value + es.chainAccount.reserves.value +
      EraLedger.accounts(es.ls).iterator.map(_._2.balance.toBigInt).sum +
      es.ls.utxo.donation.value

  private def deposited(nes: NewEpochState[Conway]): BigInt = nes.es.ls.utxo.deposited.value

  private def fees(nes: NewEpochState[Conway]): BigInt = nes.es.ls.utxo.fees.value

  private def gov(nes: NewEpochState[Conway]): ConwayGovState = ConwayNewEpochState.gov.get(nes)

  private val vState = ConwayNewEpochState.ls.andThen(ConwayLedgerState.vState)

  private val deposits =
    ConwayNewEpochState.ls.andThen(LedgerState.utxo[Conway]).andThen(UTxOState.deposited[Conway])

  /** The ledger around the transaction of `c`: its outputs, the accounts its proposals and
    * withdrawals name (the withdrawing ones holding what they withdraw and delegated to abstain), a
    * treasury of a billion ada, the constitution's guardrails script and the roots its proposals
    * follow, the deposits its accounts hold and the reserves making up the rest of the supply.
    */
  private def worldOf(c: PlutusCase): NewEpochState[Conway] =
    assertEquals(NativeRules.eraOf(c.protocolMajor), Era.Conway, "a Conway transaction")
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
    val abstain = ok(Convert.drep(DRep.AlwaysAbstain))
    val withdrawing = body.withdrawals.map { case (account, amount) =>
      account.credential -> ConwayAccountState(
        compact(amount.value),
        compact(2 * ada),
        None,
        Some(abstain)
      )
    }
    val credentials =
      body.proposals.map(_.returnAccount.credential) ++ body.proposals.flatMap(_.action match
        case GovAction.TreasuryWithdrawals(ws, _) => ws.map(_._1.credential)
        case _                                    => Vector.empty)
    val accounts = credentials.distinct
      .map(_ -> ConwayAccountState(compact(0), compact(2 * ada), None, None))
      .toMap ++ withdrawing
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
    val funded = Build.funded(Build.nes[Conway]((c.slot / 86400).toLong, params), utxo)
    val base = ConwayNewEpochState.accounts
      .replace(ConwayAccounts(Sorted.map(accounts.toList.map { (credential, account) =>
        ok(Convert.credential[Staking](credential)) -> account
      })))
      .andThen(ConwayNewEpochState.treasury.replace(ok(Coin.fromBigInt(1000000000L * ada))))
      .andThen(
        ConwayNewEpochState.gov.modify(g =>
          g.copy(
            constitution = g.constitution.copy(script = policy),
            proposals = Proposals(Vector.empty, roots)
          )
        )
      )(funded)
    val registered = deposits.replace(ok(Coin.fromBigInt(NativeRules.obligation(base))))(base)
    val reserves = Supply - (total(registered) - registered.es.chainAccount.reserves.value)
    ConwayNewEpochState.reserves.replace(ok(Coin.fromBigInt(reserves)))(registered)

  private def envOf(c: PlutusCase): TxEnv =
    TxEnv(
      slot = c.slot,
      epoch = c.slot / 86400,
      network = 0,
      firstSlotNextEpoch = (c.slot / 86400 + 1) * 86400,
      stabilityWindow = 129600,
      clock = Some(xyz.kd5ujc.scarab.ledger.PosixClock.Total(slot => slot * 1000 + 1666656000000L)),
      evaluator = ScalusEvaluator.default
    )

  private def accepted(s: NewEpochState[Conway], c: PlutusCase): Counted[NewEpochState[Conway]] =
    NativeRules.txIn[Conway](s, c.tx, envOf(c)).fold(f => fail(f.message), identity)

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
      assertEquals(total(s), Supply)
      val after = accepted(s, c)
      val body = c.tx.body
      assertEquals(fees(after.state), body.fee)
      assertEquals(gov(after.state).proposals.actions.size, body.proposals.size)
      assertEquals(deposited(after.state), deposited(s) + body.proposals.map(_.deposit).sum)
      assertEquals(deposited(after.state), NativeRules.obligation(after.state))
      assertEquals(after.plutus.succeeded, 1L)
      assertEquals(after.plutus.integrityChecked, 1L)
      assertEquals(total(after.state), Supply)
      // and the same transaction with another guardrails script is refused
      val other = ConwayNewEpochState.gov.modify(g =>
        g.copy(constitution = g.constitution.copy(script = Some(body.id)))
      )(s)
      NativeRules.txIn[Conway](other, c.tx, envOf(c)) match
        case Left(
              LedgerFailure.Rejected.Single(
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
      val after = accepted(worldOf(c), c)
      assertEquals(fees(after.state), c.tx.body.fee)
      assert(after.plutus.succeeded >= 1)
      assertEquals(total(after.state), Supply)
      assertEquals(deposited(after.state), NativeRules.obligation(after.state))
      c.tx.body.inputs.foreach(i => assert(!after.state.es.ls.utxo.utxo.utxo.contains(i)))
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
                  xyz.kd5ujc.scarab.ledger.TestLedger.conwayUpdate(16 -> 1),
                  None
                )
            ),
            epoch - 5,
            epoch + 5
          )
        }
      val authorized = CommitteeState(Sorted.map(cold.map { (member, hot) =>
        ok(Convert.credential[ColdCommittee](member)) ->
          CommitteeAuthorization.CommitteeHotCredential(ok(Convert.credential[HotCommittee](hot)))
      }))
      val s = ConwayNewEpochState.gov
        .modify(g =>
          g.copy(
            committee = Some(Committee(cold.map(_._1 -> (epoch + 50)).toMap, Rational(2, 3))),
            proposals = Proposals(actions, PrevIds())
          )
        )
        .andThen(vState.andThen(VState.committeeState).replace(authorized))(world)
      val after = accepted(s, c)
      val recorded = gov(after.state).proposals.actions.flatMap(_.committeeVotes.values)
      assertEquals(recorded.size, voters.flatMap(_._2).size)
      assert(recorded.forall(_ == Vote.Yes))
      assertEquals(total(after.state), Supply)
    }
  }
