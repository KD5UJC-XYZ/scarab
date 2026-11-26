// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.rules.NativeRules
import xyz.kd5ujc.scarab.node.ntc.FlatRecordings
import xyz.kd5ujc.scarab.ledger.state.{Conway, ConwayNewEpochState, NewEpochState}

import TestLedger.*

/** The Conway transaction rules on real Preview transactions: governance proposals that carry the
  * constitution's guardrails script (Plutus V3). The ledger state around each transaction is built
  * from the outputs it spends, its collateral, the registered return accounts and the roots its
  * proposals name; everything else is the transaction as it was on chain. UTXOW (witnesses,
  * scripts, the script data hash), UTXO (fee, balance with the proposal deposit, collateral), GOV
  * and phase 2 (the real script against our script context) must accept it: the era-typed LEDGERS,
  * on the bytes the flat decoder read. Its states, counters and refusals are the flat LEDGER's,
  * recorded before it was deleted (`fixtures/conway-flat-refusals/ledger-judgments.jsonl`).
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

  /** The recording of the flat LEDGER's judgments of these transactions. */
  private val recording = FlatRecordings.judgments("preview-rules/")

  /** `cell`, of the judgment `id`, is the recording's. */
  private def asRecorded(id: String, cell: String): Unit =
    assertEquals(Vector(cell), recording.getOrElse(id, fail(s"no recording of $id")), id)

  /** The node's phase-2 counters of the block of the one transaction `tx` whose LEDGERS told
    * `events`, as the block run counts them: each event as BBODY wraps it, read by
    * `ScriptsTold.of`, and `ConwayPhase2.counted`, which `ConwayBlockRun` gives a block's counters
    * by, `integrityChecked` among them.
    */
  private def phase2(
      tx: era.conway.ConwayTx,
      events: cats.data.Chain[era.rules.ShelleyLedgersEvent[Conway]]
  ): PlutusStats =
    era.rules.ConwayPhase2.counted(
      Vector(tx),
      events.flatMap(e =>
        cats.data.Chain.fromOption(
          era.rules.ScriptsTold.of(
            era.rules.AlonzoBbodyEvent
              .ShelleyInAlonzoEvent(era.rules.ShelleyBbodyEvent.LedgersEvent[Conway](e))
          )
        )
      )
    )

  /** The era-typed LEDGERS on the transaction of `c` from `s`, in the world of `group`: it admits
    * it, and what it admitted is returned with its events and the node's phase-2 counters of it
    * ([[phase2]]): the state after it, and the counters its scripts' events give through LEDGER's
    * and LEDGERS's embeddings ([[era.EraLedgerRun.counters]]), both the flat LEDGER's.
    */
  private def acceptedWithEvents(
      s: NewEpochState[Conway],
      c: PlutusCase,
      group: String
  ): (era.conway.ConwayTestKit.Admitted, Vector[era.rules.LedgerEvent[Conway]], PlutusStats) =
    val (tx, judgedHere) = eraLedgers(s, c)
    val (ls, told, events) = judgedHere match
      case Right((ls, es)) =>
        (ls, es, es.map { case era.rules.ShelleyLedgersEvent.LedgerEvent(e) => e })
      case Left(fs) => fail(s"${c.name}: the era LEDGERS refused $fs")
    val t = era.EraTx.conway
    val (succeeded, failed, scripts) = era.EraLedgerRun
      .counters(t.isValid(tx) == era.IsPhase2Valid.Phase2Valid, events)
      .getOrElse(fail(s"${c.name}: the events $events"))
    asRecorded(
      s"preview-rules/$group/${c.name}",
      FlatRecordings.admitted(ls, succeeded, failed, scripts)
    )
    val stats = phase2(tx, told)
    assertEquals(
      (stats.succeeded, stats.failed, stats.scripts),
      (succeeded, failed, scripts),
      s"${c.name}: the block run's counters"
    )
    (
      era.conway.ConwayTestKit
        .Admitted(ConwayNewEpochState.ls.replace(ls)(s), succeeded, failed, scripts),
      events.toVector,
      stats
    )

  private def accepted(
      s: NewEpochState[Conway],
      c: PlutusCase,
      group: String
  ): era.conway.ConwayTestKit.Admitted =
    acceptedWithEvents(s, c, group)._1

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
      val (after, events, counted) = acceptedWithEvents(s, c, "proposal")
      val body = c.tx.body
      // GOV tells the proposals after the transaction's, LEDGER and LEDGERS wrapping its event
      // (`Conway/Rules/Gov.hs` `GovNewProposals`, cardano-ledger f649f975)
      assertEquals(
        events.collect {
          case era.rules.ConwayLedgerEvent
                .GovEvent(era.rules.ConwayGovEvent.GovNewProposals(id, proposals)) =>
            (id.bytes, era.rules.ProposalsRecord.conway.write(proposals))
        },
        Vector((body.id, gov(after.nes).proposals))
      )
      assertEquals(fees(after.nes), body.fee)
      assertEquals(gov(after.nes).proposals.actions.size, body.proposals.size)
      assertEquals(deposited(after.nes), deposited(s) + body.proposals.map(_.deposit).sum)
      assertEquals(deposited(after.nes), NativeRules.obligation(after.nes))
      assertEquals(after.succeeded, 1L)
      // the proposal's guardrails script ran, so its body declares a script integrity hash, which
      // UTXOW checked: one check, as the node counts it for the block (`ConwayPhase2.counted`)
      assertEquals(counted.integrityChecked, 1L)
      assertEquals(total(after.nes), Supply)
      // and the same transaction with another guardrails script is refused: a script hash of 28
      // bytes, which the era vocabulary reads, not the proposal's
      val another = xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(body.id)
      val other = ConwayNewEpochState.gov.modify(g =>
        g.copy(constitution = g.constitution.copy(script = Some(another)))
      )(s)
      eraLedgers(other, c)._2 match
        case Left(fs) =>
          fs.toChain.toList match
            case era.rules.ShelleyLedgersPredFailure.LedgerFailure(
                  era.rules.ConwayLedgerPredFailure.ConwayGovFailure(
                    era.rules.ConwayGovPredFailure.InvalidGuardrailsScriptHash(_, _)
                  )
                ) :: Nil =>
              asRecorded(
                s"preview-rules/guardrails/${c.name}",
                FlatRecordings.refused(fs.toChain.toVector.map {
                  case era.rules.ShelleyLedgersPredFailure.LedgerFailure(f) => f
                })
              )
            case other => fail(s"${c.name}: the era LEDGERS refused $other")
        case Right(_) => fail(s"${c.name}: the era LEDGERS admitted another guardrails script")
    }
  }

  test("the transactions that only spend, mint and withdraw are present") {
    assert(spends.size >= 4, spends.size)
  }

  spends.foreach { c =>
    test(s"${c.name}: the Conway rules accept the Plutus transaction (${c.kind})") {
      val after = accepted(worldOf(c), c, "spend")
      assertEquals(fees(after.nes), c.tx.body.fee)
      assert(after.succeeded >= 1)
      assertEquals(total(after.nes), Supply)
      assertEquals(deposited(after.nes), NativeRules.obligation(after.nes))
      c.tx.body.inputs.foreach(i => assert(!after.nes.es.ls.utxo.utxo.utxo.contains(i)))
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
      val after = accepted(s, c, "vote")
      val recorded = gov(after.nes).proposals.actions.flatMap(_.committeeVotes.values)
      assertEquals(recorded.size, voters.flatMap(_._2).size)
      assert(recorded.forall(_ == Vote.Yes))
      assertEquals(total(after.nes), Supply)
    }
  }

  test("the recording holds the judgments of these transactions, each once, and no other") {
    // the judgments the tests above make, of the cases they judge
    val expected = cases.flatMap(c =>
      Vector(s"preview-rules/proposal/${c.name}", s"preview-rules/guardrails/${c.name}")
    ) ++ spends.map(c => s"preview-rules/spend/${c.name}") ++
      voting.map(c => s"preview-rules/vote/${c.name}")
    assertEquals(expected.toSet, recording.keySet)
    assertEquals(expected.size, recording.size)
  }
