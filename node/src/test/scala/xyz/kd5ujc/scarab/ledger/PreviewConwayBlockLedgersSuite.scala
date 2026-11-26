// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.Chain
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import cats.data.NonEmptyChain
import xyz.kd5ujc.scarab.ledger.era.{EraLedgerRun, EraTx}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTx
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  ConwayUtxoPredFailure,
  ConwayUtxosPredFailure,
  ConwayUtxowPredFailure,
  LedgerEvent,
  LedgerPredFailure,
  ShelleyLedgersEvent,
  ShelleyLedgersPredFailure
}
import xyz.kd5ujc.scarab.ledger.state.{Conway, ConwayNewEpochState, NewEpochState}
import xyz.kd5ujc.scarab.node.ntc.FlatRecordings
import xyz.kd5ujc.scarab.protocol.Era

import TestLedger.Supply

/** Conway's LEDGERS on the era types (`ShelleyLedgers.ledgersTransition`) over the transactions of
  * recorded Preview Conway blocks, in the order the block holds them. The blocks are those of
  * `fixtures/preview` every transaction of which is a recorded case (`fixtures/preview/plutus`), so
  * that the ledger around the block can be built from the outputs its transactions spend, reference
  * and put up as collateral (`PreviewConwayWorld`); no ledger state of the chain at a block with
  * transactions is recorded. Each transaction is read from the block's own bytes, by Conway's
  * decoder.
  *
  * For each prefix of a block the ledger state after each transaction, and the counters the
  * scripts' events give through LEDGER's and LEDGERS' embeddings, are the flat LEDGERS's, recorded
  * before it was deleted (`fixtures/conway-flat-refusals/ledger-judgments.jsonl`). Two refusals
  * show the failures in sequence, their bytes the flat rules' recorded too: the block with its
  * first transaction again at its end, refused for what the transactions before it spent, and a
  * block with a proposal under another guardrails script.
  */
class PreviewConwayBlockLedgersSuite extends FunSuite:
  import PreviewConwayWorld.{gov, total}

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

  /** The recording of the flat LEDGERS's and LEDGER's judgments of these blocks. */
  private val recording = FlatRecordings.judgments("preview-blocks/")

  /** The id of a judgment of the block `r`. */
  private def idOf(r: Recorded, what: String): String = s"preview-blocks/${r.file}:${r.line}/$what"

  /** `cells`, of the judgment `id`, are the recording's. */
  private def asRecorded(id: String, cells: Vector[String]): Unit =
    assertEquals(cells, recording.getOrElse(id, fail(s"no recording of $id")), id)

  /** The events of LEDGERS unwrapped to LEDGER's. */
  private def ledgerEvents(
      events: Chain[ShelleyLedgersEvent[Conway]]
  ): Vector[LedgerEvent[Conway]] =
    events.toVector.map { case ShelleyLedgersEvent.LedgerEvent(e) => e }

  /** LEDGERS's failures unwrapped to LEDGER's. */
  private def ledgerFailures(
      fs: NonEmptyChain[ShelleyLedgersPredFailure[Conway]]
  ): Vector[LedgerPredFailure[Conway]] =
    fs.toChain.toVector.map { case ShelleyLedgersPredFailure.LedgerFailure(f) => f }

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
      s"${r.label}: the era LEDGERS admits the block's transactions in sequence (${r.flat.size}), with the flat LEDGERS's state after each"
    ) {
      val world = r.world
      assertEquals(total(world), Supply, s"${r.label}: the supply before")
      val (_, scripts, afterBlock) =
        (1 to r.flat.size).foldLeft(
          (Vector.empty[LedgerEvent[Conway]], (0L, 0L, 0L), world.es.ls)
        ) { case ((seen, counted, _), k) =>
          val what = s"${r.label}, its first $k transactions"
          val (ls, events) = PreviewConwayWorld
            .eraLedgers(what, world, r.slot, r.typed.take(k))
            .fold(fs => fail(s"$what: the era LEDGERS refused $fs"), identity)
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
          // the state after the prefix and the counters of its last transaction: the flat
          // LEDGERS's, recorded
          asRecorded(
            idOf(r, k.toString),
            Vector(FlatRecordings.admitted(ls, own._1, own._2, own._3))
          )
          (all, plus(counted, own), ls)
        }
      // after the block: its fees collected, its inputs spent, its outputs held, the supply kept
      val after = ConwayNewEpochState.ls.replace(afterBlock)(world)
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
      assert(scripts._1 >= 1, s"${r.label}: scripts ran")
    }

    test(
      s"${r.label}: the block with its first transaction again at its end is refused, for what the transactions before it spent"
    ) {
      val world = r.world
      val n = r.flat.size
      val what = s"${r.label}, its first transaction again at index $n"
      val (before, _) = PreviewConwayWorld
        .eraLedgers(what, world, r.slot, r.typed)
        .fold(fs => fail(s"$what: the era LEDGERS refused $fs"), identity)
      val tx = r.typed.head
      def eraAlone(depth: Int) =
        PreviewConwayWorld.eraLedger(what, world, before, r.slot, n, tx, depth) match
          case Left(fs) => fs.toChain.toVector
          case Right(_) => fail(s"$what: the era LEDGER admitted it again")
      val root = eraAlone(0)
      // refused for the inputs the block spent: exactly the transaction's own, and its scripts'
      // contexts cannot be built for them
      val consumed = r.flat.head.body.inputs.toSet
      assertEquals(
        root.collect {
          case ConwayLedgerPredFailure.ConwayUtxowFailure(
                ConwayUtxowPredFailure.UtxoFailure(ConwayUtxoPredFailure.BadInputsUTxO(inputs))
              ) =>
            inputs.toSortedSet.toSet
        },
        Vector(consumed),
        s"$what: the inputs it finds spent"
      )
      root
        .collect {
          case ConwayLedgerPredFailure.ConwayUtxowFailure(
                ConwayUtxowPredFailure.UtxoFailure(
                  ConwayUtxoPredFailure.UtxosFailure(ConwayUtxosPredFailure.CollectErrors(es))
                )
              ) =>
            es.toList
        }
        .flatten
        .foreach {
          case era.CollectError.BadTranslation(
                era.conway.ConwayContextError.BabbageContextError(
                  era.BabbageContextError.AlonzoContextError(
                    era.AlonzoContextError.TranslationLogicMissingInput(input)
                  )
                )
              ) =>
            assert(consumed.contains(input), s"$what: $input is not an input it spent before")
          case other => fail(s"$what: a collection's failure $other")
        }
      // the era LEDGERS, the transactions before admitted: the sole failing transaction's failures
      // put in front of LEDGERS' list one at a time (`Extended.hs:721`, cardano-ledger f649f975),
      // so the root LEDGER's reversed, which is LEDGER's one level down
      PreviewConwayWorld.eraLedgers(what, world, r.slot, r.typed :+ tx) match
        case Left(fs) =>
          val ledgers = ledgerFailures(fs)
          assertEquals(ledgers, root.reverse, s"$what: the root LEDGER's failures reversed")
          assertEquals(ledgers, eraAlone(1), s"$what: LEDGER one level down")
          // the flat LEDGER's failures in their order, contents and all, recorded: at the root,
          // one level down, and LEDGERS's
          asRecorded(
            idOf(r, "repeated"),
            Vector(
              FlatRecordings.refused(root),
              FlatRecordings.refused(eraAlone(1)),
              FlatRecordings.refused(ledgers)
            )
          )
        case Right(_) => fail(s"$what: the era LEDGERS admitted it again")
    }
  }

  /** The block of a proposal and a spend, the first of more than one transaction a proposal. */
  private lazy val proposing: Recorded = recorded
    .find(r => r.flat.size > 1 && r.flat.head.body.proposals.nonEmpty)
    .getOrElse(fail("no block of more than one transaction, a proposal first"))

  test(
    "the block of a proposal and a spend under another guardrails script: the era LEDGERS refuses the proposal alone, by GOV"
  ) {
    val r = proposing
    val world = r.world
    val another = xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(r.flat.head.body.id)
    val other = ConwayNewEpochState.gov.modify(g =>
      g.copy(constitution = g.constitution.copy(script = Some(another)))
    )(world)
    PreviewConwayWorld.eraLedgers(r.label, other, r.slot, r.typed) match
      case Left(fs) =>
        fs.toChain.toList match
          case ShelleyLedgersPredFailure.LedgerFailure(
                f @ ConwayLedgerPredFailure.ConwayGovFailure(
                  ConwayGovPredFailure.InvalidGuardrailsScriptHash(_, _)
                )
              ) :: Nil =>
            // the flat LEDGERS's failure, recorded
            asRecorded(idOf(r, "guardrails"), Vector(FlatRecordings.refused(Vector(f))))
          case unexpected => fail(s"${r.label}: the era LEDGERS refused $unexpected")
      case Right(_) => fail(s"${r.label}: the era LEDGERS admitted another guardrails script")
  }

  test("the recording holds the judgments of these blocks, each once, and no other") {
    // the judgments the tests above make, of the blocks they judge
    val expected = recorded.flatMap(r =>
      (1 to r.flat.size).map(k => idOf(r, k.toString)) :+ idOf(r, "repeated")
    ) :+ idOf(proposing, "guardrails")
    assertEquals(expected.toSet, recording.keySet)
    assertEquals(expected.size, recording.size)
  }
