// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.rules.{Counted, NativeRules}
import xyz.kd5ujc.scarab.ledger.state.{Conway, ConwayNewEpochState, LedgerState, NewEpochState}

import TestLedger.*

/** The Conway transaction rules on real Preview transactions: governance proposals that carry the
  * constitution's guardrails script (Plutus V3). The ledger state around each transaction is built
  * from the outputs it spends, its collateral, the registered return accounts and the roots its
  * proposals name; everything else is the transaction as it was on chain. UTXOW (witnesses,
  * scripts, the script data hash), UTXO (fee, balance with the proposal deposit, collateral), GOV
  * and phase 2 (the real script against our script context) must accept it, and the era-typed
  * LEDGERS, on the bytes the flat transaction keeps, with the same state, counters and refusals.
  */
class PreviewConwayRulesSuite extends FunSuite:
  import PreviewConwayWorld.{gov, total}

  private val cases =
    PlutusCase.all.filter(c => c.tx.body.proposals.nonEmpty && c.collateral.nonEmpty)

  /** Transactions that need no more ledger state than their inputs and their withdrawals. */
  private val spends = PlutusCase.all.filter(c =>
    c.protocolMajor >= 10 && c.collateral.nonEmpty && c.tx.isValid &&
      c.tx.body.proposals.isEmpty && c.tx.body.votes.isEmpty && c.tx.body.certificates.isEmpty
  )

  private def deposited(nes: NewEpochState[Conway]): BigInt = nes.es.ls.utxo.deposited.value

  private def fees(nes: NewEpochState[Conway]): BigInt = nes.es.ls.utxo.fees.value

  /** The ledger around the transaction of `c` (`PreviewConwayWorld.worldOf`). */
  private def worldOf(c: PlutusCase): NewEpochState[Conway] = PreviewConwayWorld.worldOf(Vector(c))

  private def envOf(c: PlutusCase): TxEnv = PreviewConwayWorld.envOf(c.slot)

  /** The era-typed LEDGERS (`ShelleyLedgers.ledgersTransition`) on the transaction of `c` alone,
    * read by Conway's decoder from the bytes the flat transaction keeps, from the ledger state of
    * `s` (`PreviewConwayWorld.eraLedgers`).
    */
  private def eraLedgers(s: NewEpochState[Conway], c: PlutusCase) =
    val witnesses = c.tx.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    val tx = PreviewConwayWorld.eraTx(
      c.name,
      Block.Transaction(c.tx.body.raw, witnesses, c.tx.auxiliary, c.tx.isValid)
    )
    (tx, PreviewConwayWorld.eraLedgers(c.name, s, c.slot, Vector(tx)))

  /** The flat rules' state after `c` from `s`; the era-typed LEDGERS, on the same transaction,
    * admits it with the same ledger state, and the counters its scripts' events give through
    * LEDGER's and LEDGERS's embeddings are the flat rules' (`integrityChecked`, which no event
    * tells, aside). Its events are returned with it.
    */
  private def acceptedWithEvents(
      s: NewEpochState[Conway],
      c: PlutusCase
  ): (Counted[NewEpochState[Conway]], Vector[era.rules.LedgerEvent[Conway]]) =
    val after = NativeRules.txIn[Conway](s, c.tx, envOf(c)).fold(f => fail(f.message), identity)
    val (tx, judged) = eraLedgers(s, c)
    val events = judged match
      case Right((ls, es)) =>
        assert(cats.Eq[LedgerState[Conway]].eqv(ls, after.state.es.ls), s"${c.name}: era state")
        es.map { case era.rules.ShelleyLedgersEvent.LedgerEvent(e) => e }
      case Left(fs) => fail(s"${c.name}: the era LEDGERS refused $fs")
    val t = era.EraTx.conway
    assertEquals(
      era.EraLedgerRun.counters(t.isValid(tx) == era.IsPhase2Valid.Phase2Valid, events),
      Some((after.plutus.succeeded, after.plutus.failed, after.plutus.scripts)),
      s"${c.name}: the counters"
    )
    (after, events.toVector)

  private def accepted(s: NewEpochState[Conway], c: PlutusCase): Counted[NewEpochState[Conway]] =
    acceptedWithEvents(s, c)._1

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
      val (after, events) = acceptedWithEvents(s, c)
      val body = c.tx.body
      // GOV tells the proposals after the transaction's, LEDGER and LEDGERS wrapping its event
      // (`Conway/Rules/Gov.hs` `GovNewProposals`, cardano-ledger f649f975)
      assertEquals(
        events.collect {
          case era.rules.ConwayLedgerEvent
                .GovEvent(era.rules.ConwayGovEvent.GovNewProposals(id, proposals)) =>
            (id.bytes, era.rules.ProposalsRecord.conway.write(proposals).toOption)
        },
        Vector((body.id, Some(gov(after.state).proposals)))
      )
      assertEquals(fees(after.state), body.fee)
      assertEquals(gov(after.state).proposals.actions.size, body.proposals.size)
      assertEquals(deposited(after.state), deposited(s) + body.proposals.map(_.deposit).sum)
      assertEquals(deposited(after.state), NativeRules.obligation(after.state))
      assertEquals(after.plutus.succeeded, 1L)
      assertEquals(after.plutus.integrityChecked, 1L)
      assertEquals(total(after.state), Supply)
      // and the same transaction with another guardrails script is refused: a script hash of 28
      // bytes, which the era vocabulary reads, not the proposal's
      val another = xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(body.id)
      val other = ConwayNewEpochState.gov.modify(g =>
        g.copy(constitution = g.constitution.copy(script = Some(another)))
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
      eraLedgers(other, c)._2 match
        case Left(fs) =>
          fs.toChain.toList match
            case era.rules.ShelleyLedgersPredFailure.LedgerFailure(
                  era.rules.ConwayLedgerPredFailure.ConwayGovFailure(
                    era.rules.ConwayGovPredFailure.InvalidGuardrailsScriptHash(_, _)
                  )
                ) :: Nil =>
              ()
            case other => fail(s"${c.name}: the era LEDGERS refused $other")
        case Right(_) => fail(s"${c.name}: the era LEDGERS admitted another guardrails script")
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
      val s = PreviewConwayWorld.withCommittee(worldOf(c), Vector(c))
      val after = accepted(s, c)
      val recorded = gov(after.state).proposals.actions.flatMap(_.committeeVotes.values)
      assertEquals(recorded.size, voters.flatMap(_._2).size)
      assert(recorded.forall(_ == Vote.Yes))
      assertEquals(total(after.state), Supply)
    }
  }
