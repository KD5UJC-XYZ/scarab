// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.Chain
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.{EraLedgerRun, EraTx, EraTxBody, EraUTxO, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayTx, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  ConwayUtxowPredFailure,
  LedgerEvent,
  LedgerPredFailure,
  ShelleyLedgersEvent,
  ShelleyLedgersPredFailure
}
import xyz.kd5ujc.scarab.ledger.rules.{
  Counted,
  Judgment,
  Ledger as FlatLedger,
  LedgerEnv as FlatLedgerEnv,
  Ledgers as FlatLedgers,
  LedgersEnv as FlatLedgersEnv,
  NativeRules,
  Sts
}
import xyz.kd5ujc.scarab.ledger.state.{Conway, ConwayNewEpochState, LedgerState, NewEpochState}
import xyz.kd5ujc.scarab.protocol.Era

import TestLedger.Supply

/** Conway's LEDGERS on the era types (`ShelleyLedgers.ledgersTransition`) against the flat LEDGERS
  * (`rules.Ledgers`, what BBODY runs) over the transactions of recorded Preview Conway blocks, in
  * the order the block holds them. The blocks are those of `fixtures/preview` every transaction of
  * which is a recorded case (`fixtures/preview/plutus`), so that the ledger around the block can be
  * built from the outputs its transactions spend, reference and put up as collateral
  * (`PreviewConwayWorld`); no ledger state of the chain at a block with transactions is recorded.
  * Each transaction is read from the block's own bytes, by Conway's decoder for the era rule.
  *
  * For each prefix of a block both rules run from the same state: the same ledger state after each
  * transaction, and the counters the scripts' events give through LEDGER's and LEDGERS' embeddings,
  * transaction by transaction, summing to the flat rules' (the integrity checks the flat rules
  * count aside, which no event tells). Two refusals show the failures in sequence: the block with
  * its first transaction again at its end, refused for what the transactions before it spent, and a
  * block with a proposal under another guardrails script.
  */
class PreviewConwayBlockLedgersSuite extends FunSuite:
  import PreviewConwayWorld.{envOf, gov, total}

  /** The files of recorded Preview blocks that hold Conway blocks. */
  private val files = Vector(
    "conway-governance.hex",
    "conway.hex",
    "duplicate-redeemer-conway.hex",
    "empty-redeemers.hex"
  )

  private val byId: Map[Bytes, PlutusCase] = PlutusCase.all.map(c => c.tx.body.id -> c).toMap

  /** A recorded Conway block, its transactions as both rules read them, and their cases. */
  private final case class Recorded(
      file: String,
      line: Int,
      block: Block.Shelley,
      flat: Vector[Tx],
      typed: Vector[ConwayTx],
      cases: Vector[PlutusCase]
  ):
    val label = s"$file line $line (slot ${block.header.slot})"
    val slot: BigInt = block.header.slot

    /** The ledger the block is judged in: its cases' world, and the committee their votes need. */
    def world: NewEpochState[Conway] =
      val built = PreviewConwayWorld.worldOf(cases)
      if cases.exists(_.tx.body.votes.nonEmpty) then PreviewConwayWorld.withCommittee(built, cases)
      else built

  private val conwayBlocks: Vector[(String, Int, Block.Shelley)] =
    files.flatMap { name =>
      Files
        .readAllLines(Path.of("fixtures/preview", name))
        .asScala
        .toVector
        .zipWithIndex
        .filter(_._1.nonEmpty)
        .map { (l, i) =>
          val raw = Bytes.fromHex(l).fold(e => fail(s"$name line $i: $e"), identity)
          Block.decodeShelley(raw).fold(e => fail(s"$name line $i: ${e.message}"), identity) match
            case b: Block.Shelley => (name, i, b)
            case other            => fail(s"$name line $i: not a Shelley-based block $other")
        }
        .filter(_._3.era == Era.Conway)
    }

  /** The Conway blocks with transactions every one of which is a recorded case. */
  private val recorded: Vector[Recorded] =
    conwayBlocks.collect {
      case (name, i, b)
          if b.transactions.nonEmpty && b.transactions.forall(t => byId.contains(t.id)) =>
        val what = s"$name line $i"
        val cases = b.transactions.map(t => byId(t.id))
        b.transactions.zip(cases).foreach { (t, c) =>
          assertEquals(c.slot, b.header.slot, s"$what: ${c.name}'s slot")
          assertEquals(c.tx.body.raw, t.body, s"$what: ${c.name}'s body")
          assertEquals(c.tx.witnesses.raw, Some(t.witnesses), s"$what: ${c.name}'s witnesses")
          assertEquals(c.tx.isValid, t.isValid, s"$what: ${c.name}'s flag")
        }
        Recorded(
          name,
          i,
          b,
          b.transactions.map(t =>
            Tx.fromBlock(t, Era.Conway).fold(e => fail(s"$what ${t.id.hex}: $e"), identity)
          ),
          b.transactions.map(t => PreviewConwayWorld.eraTx(s"$what ${t.id.hex}", t)),
          cases
        )
    }

  /** The flat LEDGERS on `txs` from the ledger state of `s` at `slot`, from no script run. */
  private def flatLedgers(
      s: NewEpochState[Conway],
      slot: BigInt,
      txs: Vector[Tx]
  ): Either[LedgerFailure, Counted[LedgerState[Conway]]] =
    val ctx = envOf(slot)
    Sts(FlatLedgers)(
      NativeRules.globalsOf(ctx),
      FlatLedgersEnv(ctx, s.es.chainAccount.treasury),
      Counted(s.es.ls, PlutusStats.zero),
      txs
    )

  /** The flat LEDGER on `tx` at index `ix` of a block from `ls`, its failures as `policy` keeps
    * them.
    */
  private def flatLedger(
      what: String,
      s: NewEpochState[Conway],
      ls: LedgerState[Conway],
      slot: BigInt,
      ix: Int,
      tx: Tx,
      policy: Judgment.Policy
  ): Either[cats.data.NonEmptyChain[PredicateFailure], Counted[LedgerState[Conway]]] =
    val ctx = envOf(slot).copy(txIndex = ix)
    Judgment
      .run(FlatLedger, policy)(
        NativeRules.globalsOf(ctx),
        FlatLedgerEnv(ctx, s.es.chainAccount.treasury),
        Counted(ls, PlutusStats.zero),
        tx
      )
      .fold(defect => fail(s"$what: flat defect $defect"), identity)

  /** The events of LEDGERS unwrapped to LEDGER's. */
  private def ledgerEvents(
      events: Chain[ShelleyLedgersEvent[Conway]]
  ): Vector[LedgerEvent[Conway]] =
    events.toVector.map { case ShelleyLedgersEvent.LedgerEvent(e) => e }

  /** An era failure of `tx`, judged on `ls`, in the node's flat form (`EraLedgerRun.flat`), which
    * keeps a translation failure's contents: a missing redeemer's purpose by the scripts `tx` needs
    * there, an action by its proposals.
    */
  private def eraFlat(what: String, ls: LedgerState[Conway], tx: ConwayTx)(
      failure: LedgerPredFailure[Conway]
  ): PredicateFailure =
    val u = EraUTxO.conway
    val t = EraTx.conway
    val b = EraTxBody.conway
    val body = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
    val utxo =
      ConwayUTxO.fromStored(ls.utxo.utxo.utxo.toMap).fold(e => fail(s"$what: $e"), identity)
    val purposes = ConwayUtxowPredFailure.storedPurposes(
      u.getScriptsNeeded[TxLevel.Top](utxo, body)(using u.txOf.bodyOf.top),
      utxo
    )
    val read =
      ConwayGovPredFailure.readOf(b.proposalProcedures[TxLevel.Top](body)(using b.top).toVector)
    EraLedgerRun
      .flat(failure, purposes, read)
      .fold(e => fail(s"$what: no flat form for $failure: $e"), identity)

  private def scripts(c: Counted[?]): (Long, Long, Long) =
    (c.plutus.succeeded, c.plutus.failed, c.plutus.scripts)

  private def plus(a: (Long, Long, Long), b: (Long, Long, Long)): (Long, Long, Long) =
    (a._1 + b._1, a._2 + b._2, a._3 + b._3)

  test("the recorded Conway blocks whose every transaction is a recorded case are present") {
    // conway-governance.hex: three committee votes at slot 117938233, a proposal and a spend at
    // 122335579, and at 122488843 a proposal spending the proposal's output
    assertEquals(
      recorded.map(r => (r.file, r.line, r.slot, r.flat.size)),
      Vector(
        ("conway-governance.hex", 4, BigInt(122335579), 2),
        ("conway-governance.hex", 5, BigInt(122488843), 1),
        ("conway-governance.hex", 19, BigInt(117938233), 3)
      )
    )
  }

  recorded.foreach { r =>
    test(
      s"${r.label}: the era LEDGERS and the flat LEDGERS admit the block's transactions in sequence (${r.flat.size}), with the same state after each"
    ) {
      val world = r.world
      assertEquals(total(world), Supply, s"${r.label}: the supply before")
      val (_, _, flatAfter) =
        (1 to r.flat.size).foldLeft(
          (Vector.empty[LedgerEvent[Conway]], (0L, 0L, 0L), Counted(world.es.ls, PlutusStats.zero))
        ) { case ((seen, counted, _), k) =>
          val what = s"${r.label}, its first $k transactions"
          val flat =
            flatLedgers(world, r.slot, r.flat.take(k)).fold(f => fail(s"$what: $f"), identity)
          val (ls, events) = PreviewConwayWorld
            .eraLedgers(what, world, r.slot, r.typed.take(k))
            .fold(fs => fail(s"$what: the era LEDGERS refused $fs"), identity)
          assert(cats.Eq[LedgerState[Conway]].eqv(ls, flat.state), s"$what: the state")
          val all = ledgerEvents(events)
          // the transactions before judged as they were in the shorter prefix
          assertEquals(all.take(seen.size), seen, s"$what: the events before")
          val tx = r.typed(k - 1)
          val own = EraLedgerRun
            .counters(
              EraTx.conway.isValid(tx) == era.IsPhase2Valid.Phase2Valid,
              Chain.fromSeq(all.drop(seen.size))
            )
            .getOrElse(fail(s"$what: the events of the last ${all.drop(seen.size)}"))
          val sum = plus(counted, own)
          assertEquals(sum, scripts(flat), s"$what: the counters")
          (all, sum, flat)
        }
      // after the block: its fees collected, its inputs spent, its outputs held, the supply kept
      val after = ConwayNewEpochState.ls.replace(flatAfter.state)(world)
      assertEquals(after.es.ls.utxo.fees.value, r.flat.map(_.body.fee).sum, s"${r.label}: fees")
      r.flat.foreach { t =>
        t.body.inputs.foreach(i => assert(!after.es.ls.utxo.utxo.utxo.contains(i), s"$i spent"))
        t.body.outputs.indices.foreach(ix =>
          assert(after.es.ls.utxo.utxo.utxo.contains(TxIn(t.body.id, BigInt(ix))), s"output $ix")
        )
      }
      assertEquals(
        gov(after).proposals.actions.size,
        gov(world).proposals.actions.size + r.flat.map(_.body.proposals.size).sum,
        s"${r.label}: the proposals"
      )
      assertEquals(total(after), Supply, s"${r.label}: the supply after")
      assert(flatAfter.plutus.succeeded >= 1, s"${r.label}: scripts ran")
    }

    test(
      s"${r.label}: the block with its first transaction again at its end is refused by both, for what the transactions before it spent"
    ) {
      val world = r.world
      val n = r.flat.size
      val what = s"${r.label}, its first transaction again at index $n"
      val before = flatLedgers(world, r.slot, r.flat).fold(f => fail(s"$what: $f"), identity)
      val repeated = r.flat.head
      def flatAlone(policy: Judgment.Policy) =
        flatLedger(what, world, before.state, r.slot, n, repeated, policy) match
          case Left(fs) => fs.toChain.toList
          case Right(_) => fail(s"$what: the flat LEDGER admitted it again")
      val accumulated = flatAlone(Judgment.Policy.Accumulate)
      // refused for the inputs the block spent: exactly the transaction's own, and its scripts'
      // contexts cannot be built for them
      val consumed = repeated.body.inputs.toSet
      assertEquals(
        accumulated.collect {
          case PredicateFailure.Utxow(UtxowFailure.Utxo(UtxoFailure.BadInputs(inputs))) => inputs
        },
        List(consumed),
        s"$what: the inputs it finds spent"
      )
      accumulated
        .collect {
          case PredicateFailure.Utxow(
                UtxowFailure.Utxo(UtxoFailure.Utxos(UtxosFailure.CollectErrors(es)))
              ) =>
            es.toVector
        }
        .flatten
        .foreach {
          case CollectError.BadTranslation(TranslationError.UnresolvedInput(input)) =>
            assert(consumed.contains(input), s"$what: $input is not an input it spent before")
          case other => fail(s"$what: a collection's failure $other")
        }
      // the flat LEDGERS: the block's verdict is the transaction's first failure, as the flat
      // LEDGER at its first failure gives it
      flatLedgers(world, r.slot, r.flat :+ repeated) match
        case Left(LedgerFailure.Rejected(id, fs)) =>
          assertEquals(id, repeated.body.id, what)
          assertEquals(
            fs.toChain.toList,
            flatAlone(Judgment.Policy.FirstFailure),
            s"$what: the flat LEDGERS' failure"
          )
        case other => fail(s"$what: the flat LEDGERS gave $other")
      // the era LEDGER at the root, from the state after the block: the flat LEDGER's failures in
      // their order, contents and all
      val tx = r.typed.head
      val flatOf = eraFlat(what, before.state, tx)
      def eraAlone(depth: Int) =
        PreviewConwayWorld.eraLedger(what, world, before.state, r.slot, n, tx, depth) match
          case Left(fs) => fs.toChain.toList.map(flatOf)
          case Right(_) => fail(s"$what: the era LEDGER admitted it again")
      assertEquals(eraAlone(0), accumulated, s"$what: the era LEDGER at the root")
      // the era LEDGERS, the transactions before admitted: the sole failing transaction's failures
      // put in front of LEDGERS' list one at a time (`Extended.hs:721`, cardano-ledger f649f975),
      // so the root LEDGER's reversed, which is LEDGER's one level down
      PreviewConwayWorld.eraLedgers(what, world, r.slot, r.typed :+ tx) match
        case Left(fs) =>
          val ledgers = fs.toChain.toList.map { case ShelleyLedgersPredFailure.LedgerFailure(f) =>
            flatOf(f)
          }
          assertEquals(ledgers, accumulated.reverse, s"$what: the flat LEDGER's failures reversed")
          assertEquals(ledgers, eraAlone(1), s"$what: LEDGER one level down")
        case Right(_) => fail(s"$what: the era LEDGERS admitted it again")
    }
  }

  test(
    "the block of a proposal and a spend under another guardrails script: the era LEDGERS and the flat LEDGERS refuse the proposal alone, by GOV"
  ) {
    val r = recorded
      .find(r => r.flat.size > 1 && r.flat.head.body.proposals.nonEmpty)
      .getOrElse(fail("no block of more than one transaction, a proposal first"))
    val world = r.world
    val another = xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(r.flat.head.body.id)
    val other = ConwayNewEpochState.gov.modify(g =>
      g.copy(constitution = g.constitution.copy(script = Some(another)))
    )(world)
    val flat = flatLedgers(other, r.slot, r.flat) match
      case Left(
            rejected @ LedgerFailure.Rejected.Single(
              _,
              PredicateFailure.Gov(GovFailure.InvalidGuardrailsScriptHash(_, _))
            )
          ) =>
        assertEquals(rejected.txId, r.flat.head.body.id, r.label)
        rejected.failures.toChain.toList
      case unexpected => fail(s"${r.label}: the flat LEDGERS gave $unexpected")
    PreviewConwayWorld.eraLedgers(r.label, other, r.slot, r.typed) match
      case Left(fs) =>
        fs.toChain.toList match
          case ShelleyLedgersPredFailure.LedgerFailure(
                f @ ConwayLedgerPredFailure.ConwayGovFailure(
                  ConwayGovPredFailure.InvalidGuardrailsScriptHash(_, _)
                )
              ) :: Nil =>
            assertEquals(
              List(eraFlat(r.label, other.es.ls, r.typed.head)(f)),
              flat,
              r.label
            )
          case unexpected => fail(s"${r.label}: the era LEDGERS refused $unexpected")
      case Right(_) => fail(s"${r.label}: the era LEDGERS admitted another guardrails script")
  }
