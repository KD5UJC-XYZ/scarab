// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Id
import java.util.concurrent.atomic.AtomicInteger
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Phase2, PosixClock, PreparedBlockFixture, TestLedger, TxIn, Utxo}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoStAnnTx,
  EraBlockBody,
  EraTx,
  KeptContexts,
  PlutusWithContext,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayImp, ConwayScriptedBlocks, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.rules.StAnnTx
import xyz.kd5ujc.scarab.ledger.state.{Conway, ShelleyBasedEra}
import xyz.kd5ujc.scarab.plutus.{EvalRequest, ExUnits, ScriptEvaluator}

/** The collections of scripts made ahead of the rules and the rules' taking of them (#1499): a kept
  * collection is taken only where it is the collection the rules would make, by construction, so
  * whatever is kept, what the rules get is their own collection. Its key is everything the
  * collection reads: the era, the transaction, the parameters (and with them the cost models and
  * the protocol version), the scripts it has, the outputs it spends and reads, and the clock's
  * answers to the slots it asks for; for Conway's contexts the version, the outputs, the
  * transaction, the scripts used as resolved and the cost models. Each part changed alone is not
  * taken (and a mutation of the comparison that ignores the part fails here), and a property checks
  * that for any changes the rules get their own collection.
  */
class ScriptsAheadKeySuite extends ScalaCheckSuite:
  import ScriptsAheadSuite.{block, counting}

  private val fixture = PreparedBlockFixture
  private val tx = fixture.bounded(0)
  private val clock: PosixClock =
    PosixClock.total(TestLedger.globals.systemStart, TestLedger.globals.geometry)
  private val posix: Phase2.Posix = slot => clock.posix(slot)

  private val read = (tx.body.inputs ++ tx.body.referenceInputs)
    .map(in => in -> ScriptsAheadSuite.tipOutputs(in).getOrElse(fail(s"no output for $in")))
    .toMap
  private val ann = StAnnTx(tx, Utxo.fromMap(read))
  private val inputs: Phase2.Inputs[?] = Phase2.Inputs(
    ShelleyBasedEra.SBabbage,
    tx,
    fixture.params,
    ann.scripts,
    ann.spentByInput,
    ann.referencedByInput
  )
  private val spentIn = tx.body.inputs.head

  /** Each part of the collection's key changed alone, with the clock the rules collect with. */
  private val changes: Vector[(String, Phase2.Inputs[?] => Phase2.Inputs[?], Phase2.Posix)] =
    Vector(
      ("era", _.copy(era = ShelleyBasedEra.SAlonzo), posix),
      ("transaction", _.copy(tx = fixture.bounded(1)), posix),
      (
        "redeemer",
        i =>
          i.copy(tx =
            tx.copy(witnesses =
              tx.witnesses
                .copy(redeemers = tx.witnesses.redeemers.map(_.copy(data = Bytes(Vector(1)))))
            )
          ),
        posix
      ),
      ("protocol version", i => i.copy(pp = i.pp.copy(protocolMajor = 9)), posix),
      (
        "cost models",
        i =>
          i.copy(pp =
            i.pp.copy(costModels =
              i.pp.costModels.map((k, v) =>
                k -> v.updated(0, v.headOption.getOrElse(BigInt(0)) + 1)
              )
            )
          ),
        posix
      ),
      ("scripts", _.copy(scripts = Map.empty), posix),
      (
        "spent output",
        i =>
          i.copy(spent =
            i.spent.updatedWith(spentIn)(
              _.map(out => out.copy(value = out.value.copy(coin = out.value.coin + 1)))
            )
          ),
        posix
      ),
      (
        "reference outputs",
        i => i.copy(references = i.references.updated(TxIn(block(9), 0), read(spentIn))),
        posix
      ),
      ("era history", identity, slot => clock.posix(slot).map(_ + 1000))
    )

  /** A collection kept for `inputs` with `posix`, held for the rules. */
  private def holding: ScriptsAhead.Results =
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    val kept = Phase2.Kept.of(inputs, posix).getOrElse(fail("the fixture does not collect"))
    assertEquals(results.collected(block(1), Vector(ScriptsAhead.Collection.Flat(kept))), 1)
    results

  /** Whether `got` is the collection `results` keeps for the fixture's transaction, its requests
    * the very objects kept: the collection was taken.
    */
  private def taken(
      results: ScriptsAhead.Results,
      got: Phase2.Collected[Vector[(xyz.kd5ujc.scarab.ledger.Redeemer, EvalRequest)]]
  ): Boolean =
    results.flat
      .flat(inputs)
      .exists(kept =>
        got.toOption.exists(rs =>
          rs.sizeIs == kept.requests.size && rs.zip(kept.requests).forall((a, b) => a._2 eq b._2)
        )
      )

  test("a kept collection is taken for its own inputs and clock, its requests themselves") {
    val results = holding
    val again = inputs.copy(tx = tx.copy(), spent = inputs.spent.map(identity))
    val got = collectedOf(again, posix, results.flat)
    assertEquals(got, inputs.collect(posix))
    assert(taken(results, got))
  }

  changes.foreach { (part, change, clocked) =>
    test(s"a kept collection is not taken where its $part differs") {
      val results = holding
      val other = change(inputs)
      val got = collectedOf(other, clocked, results.flat)
      // the rules' own collection, which this change makes another
      assertEquals(got, other.collect(clocked))
      // a part the collection reads here gives another collection; the key is finer than what
      // this transaction reads in two parts: it reads no reference input, so outputs added there
      // are compared but not read, and its V2 context is the same in Alonzo as in Babbage
      if !Set("reference outputs", "era")(part) then
        assertNotEquals(got, inputs.collect(posix), s"the $part is not read")
      assert(!taken(results, got), s"taken for another $part")
    }
  }

  test("a clock that answers otherwise only for slots the collection does not ask: taken") {
    val results = holding
    val apart: Phase2.Posix =
      slot => if slot == BigInt(5000) then clock.posix(slot) else clock.posix(slot).map(_ + 1)
    val got = collectedOf(inputs, apart, results.flat)
    assertEquals(got, inputs.collect(apart))
    assert(taken(results, got))
  }

  property("whatever is kept, the rules get the collection of their own inputs and clock") {
    forAll(Gen.someOf(changes.indices)) { picked =>
      val results = holding
      val chosen = picked.toVector.sorted.map(changes(_))
      val other = chosen.foldLeft(inputs)((i, c) => c._2(i))
      val clocked = chosen.find(_._1 == "era history").fold(posix)(_._3)
      val got = collectedOf(other, clocked, results.flat)
      (got == other.collect(clocked)) :| "the rules' own collection" &&
      (taken(results, got) == chosen.isEmpty) :| "taken only where nothing changed"
    }
  }

  /** [[Phase2.collected]] of inputs of any era, as the rules call it with theirs. */
  private def collectedOf(
      in: Phase2.Inputs[?],
      clocked: Phase2.Posix,
      ahead: Phase2.Ahead
  ): Phase2.Collected[Vector[(xyz.kd5ujc.scarab.ledger.Redeemer, EvalRequest)]] =
    in match
      case i: Phase2.Inputs[e] => Phase2.collected[e](i, clocked, ahead)

  // ---- Conway's contexts -------------------------------------------------------------------

  private given EraTx.conway.bodyOf.Level[TxLevel.Top] = EraTx.conway.bodyOf.top

  test("a Conway block's contexts and scripts made ahead are found, to the same result") {
    // each transaction spends an input and takes a collateral input of its own
    val scripted = ConwayScriptedBlocks.honest(3, 20)
    val txs = EraBlockBody.conway.txSeqBlockBody(ConwayImp.decoded(scripted.raw).blockBody)
    val stored = scripted.state.utxo.utxo.utxo
    val underlying: ScriptEvaluator[Id] = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.pure
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    // collected as the speculator collects them, from the stored outputs it reads
    val collections = txs.map(e =>
      ScriptsAheadEras
        .conwayCollection(ConwayScriptedBlocks.params, ConwayImp.slotToPosix, e, stored.get)
        .getOrElse(fail("not collected"))
    )
    assertEquals(results.collected(block(1), collections), 3)
    collections
      .flatMap(_.requests)
      .foreach(r => assert(results.put(block(1), r, underlying.evaluate(r))))
    val calls = new AtomicInteger()
    val ahead = ConwayImp
      .programAhead(scripted.raw, scripted.state, ConwayScriptedBlocks.params)(
        results.evaluator(counting(underlying, calls)),
        None,
        results.conway
      )
      .serial
    val base = scripted.program(underlying).serial
    assertEquals(ahead.toString, base.toString)
    assertEquals(calls.get, 0)
    assertEquals(results.collectionCounts, ScriptsAhead.Collections(3, 0, 3, 3))
    assertEquals(results.counts, ScriptsAhead.Counts(3, 0, 0))
  }

  /** The first transaction of `scripted` 's block, its outputs read from its state, and what its
    * contexts are collected from on `outputs` (its own by default).
    */
  private final case class Read(scripted: ConwayScriptedBlocks.Scripted):
    val tx = EraBlockBody.conway.txSeqBlockBody(ConwayImp.decoded(scripted.raw).blockBody).head
    private val t = EraTx.conway
    val found: Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut] =
      val stored = scripted.state.utxo.utxo.utxo
      t.bodyOf
        .allInputs(t.body[TxLevel.Top](tx))
        .iterator
        .flatMap(in => stored.get(in).map(in -> _))
        .toMap
    def inputs(outputs: Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut] = found) =
      AlonzoStAnnTx
        .resolve[Conway](
          ConwayScriptedBlocks.params,
          ConwayUTxO.fromStored(outputs).getOrElse(fail("outputs not read")),
          Map.empty,
          tx
        )
        .inputs

  test("Conway's contexts kept are not taken for other outputs, transaction or clock answers") {
    val script = xyz.kd5ujc.scarab.ledger.era.PlutusLoop.script(20)
    val units = ConwayScriptedBlocks.spent(script)
    val own = Read(ConwayScriptedBlocks.declaring(1, script, units, ttl = Some(5000)))
    val kept = KeptContexts.of(own.inputs(), ConwayImp.slotToPosix).getOrElse(fail("not collected"))
    // its validity interval ends at slot 5,000: the contexts ask the clock there
    assertEquals(kept.asked.map(_._1), Vector(BigInt(5000)))
    assert(kept.answers(own.inputs(), ConwayImp.slotToPosix), "its own inputs")
    val (in0, out0) = own.found.head
    val moved = own.inputs(
      own.found.updated(in0, out0.copy(value = out0.value.copy(coin = out0.value.coin + 1)))
    )
    assert(!kept.answers(moved, ConwayImp.slotToPosix), "another output taken")
    // the same script spending the same outputs, its redeemer declaring other units
    val other =
      Read(ConwayScriptedBlocks.declaring(1, script, units + ExUnits(1, 1), ttl = Some(5000)))
    assert(!kept.answers(other.inputs(), ConwayImp.slotToPosix), "another transaction taken")
    val shifted: Phase2.Posix = s => ConwayImp.slotToPosix(s).map(_ + 1)
    assert(!kept.answers(own.inputs(), shifted), "another clock's answer taken")
    assertNotEquals(
      own.inputs().collect(shifted).map(_.map(PlutusWithContext.evalRequest)),
      own.inputs().collect(ConwayImp.slotToPosix).map(_.map(PlutusWithContext.evalRequest))
    )
    // answers only for the slot it asked are compared
    val apart: Phase2.Posix = s =>
      if s == BigInt(5000) then ConwayImp.slotToPosix(s) else ConwayImp.slotToPosix(s).map(_ + 1)
    assert(kept.answers(own.inputs(), apart))
  }

  test("a collection kept asks its clock nothing once made, its requests made with it") {
    val calls = new AtomicInteger()
    def counted(clock: Phase2.Posix): Phase2.Posix = slot =>
      val _ = calls.incrementAndGet()
      clock(slot)
    val flat = Phase2.Kept.of(inputs, counted(posix)).getOrElse(fail("not collected"))
    val flatAsked = calls.get
    assertEquals(flatAsked, flat.asked.size)
    flat.requests.foreach((_, r) => assert(r.args.nonEmpty))
    assertEquals(calls.get, flatAsked)
    val script = xyz.kd5ujc.scarab.ledger.era.PlutusLoop.script(20)
    val own = Read(
      ConwayScriptedBlocks.declaring(1, script, ConwayScriptedBlocks.spent(script), Some(5000))
    )
    calls.set(0)
    val conway =
      KeptContexts.of(own.inputs(), counted(ConwayImp.slotToPosix)).getOrElse(fail("not collected"))
    val conwayAsked = calls.get
    assertEquals(conwayAsked, conway.asked.size)
    // every request the rules would evaluate, made: the clock is not asked again
    conway.scripts.foreach(pwc => assert(PlutusWithContext.evalRequest(pwc).args.nonEmpty))
    assertEquals(calls.get, conwayAsked)
    // and the request is the one made, each time it is asked for
    conway.scripts.foreach(pwc =>
      assert(PlutusWithContext.evalRequest(pwc) eq PlutusWithContext.evalRequest(pwc))
    )
  }
