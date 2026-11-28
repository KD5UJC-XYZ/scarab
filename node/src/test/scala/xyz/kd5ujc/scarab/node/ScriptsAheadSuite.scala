// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Id
import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  Header,
  Phase2,
  PlutusStats,
  PosixClock,
  PreparedBlockFixture,
  ProtocolParams,
  TestLedger,
  Tx,
  TxIn,
  TxOut
}
import xyz.kd5ujc.scarab.ledger.rules.{EraBlock, EraNes}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.plutus.{
  BackendFailure,
  EvalFailure,
  EvalRequest,
  EvalResult,
  ExUnits,
  PlutusData,
  PlutusLanguage,
  ScarabEvaluator,
  ScriptEvaluator,
  ScriptFailure
}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError

/** The Plutus scripts of queued blocks evaluated ahead of the worker ([[ScriptsAhead]], #1499 item
  * 4): a kept result is given only for a request equal to its own, field by field; the speculator
  * keeps, skips, abandons and drops the results of the blocks it is given as the worker reaches or
  * passes them; and the flat body of real Babbage transactions applied with results evaluated ahead
  * comes to the same state, verdicts, budgets and traces as without them, under every schedule: all
  * found, an input made by an earlier queued block, an input that changed between the speculation
  * and the application (a rollback), parameters changed at an epoch boundary, failing scripts with
  * either validity flag, and the bodies prepared through the node's workers.
  */
class ScriptsAheadSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  import ScriptsAheadSuite.*

  // ---- the kept results ------------------------------------------------------------------

  private val request = EvalRequest(
    PlutusLanguage.V2,
    8,
    Bytes(Vector(1, 2, 3)),
    Vector(PlutusData.int(1), PlutusData.bytes(Bytes(Vector(9)))),
    Vector(1L, 2L, 3L),
    ExUnits(100, 200)
  )

  /** A request changed in exactly one field, each field once: a result kept for `request` must not
    * be given for any of them.
    */
  private val changed: Vector[(String, EvalRequest)] = Vector(
    "language" -> request.copy(language = PlutusLanguage.V3),
    "protocol" -> request.copy(protocolMajor = 9),
    "script" -> request.copy(script = Bytes(Vector(1, 2, 4))),
    "arguments" -> request.copy(args =
      Vector(PlutusData.int(2), PlutusData.bytes(Bytes(Vector(9))))
    ),
    "cost model" -> request.copy(costModel = Vector(1L, 2L, 4L)),
    "budget" -> request.copy(budget = ExUnits(100, 201))
  )

  test("a kept result is given for an equal request and not evaluated again") {
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    val calls = new AtomicInteger()
    val evaluator = results.evaluator(counting(deterministic(_ => false), calls))
    val kept = deterministic(_ => false).evaluate(request)
    assert(results.put(block(1), request, kept))
    // an equal request built anew, its data and bytes fresh objects
    val again = request.copy(
      script = Bytes(Vector(1, 2, 3)),
      args = Vector(PlutusData.int(1), PlutusData.bytes(Bytes(Vector(9))))
    )
    assertEquals(evaluator.evaluate(again), kept)
    assertEquals(calls.get, 0)
    assertEquals(results.counts, ScriptsAhead.Counts(1, 0, 0))
  }

  changed.foreach { (field, other) =>
    test(s"a result kept for one request is not given for one whose $field differs") {
      val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
      val calls = new AtomicInteger()
      val underlying = deterministic(_ => false)
      // a result no evaluation gives, so that one given wrongly shows
      val planted = EvalResult.Success(ExUnits(7, 7), Vector("planted"))
      assert(results.put(block(1), request, planted))
      val answered = results.evaluator(counting(underlying, calls)).evaluate(other)
      assertEquals(answered, underlying.evaluate(other))
      assertNotEquals(answered, planted)
      assertEquals(calls.get, 1)
      // the same script and budget kept for another request is stale; another script a miss
      val expected =
        if Set("arguments", "cost model")(field) then ScriptsAhead.Counts(0, 0, 1)
        else ScriptsAhead.Counts(0, 1, 0)
      assertEquals(results.counts, expected)
    }
  }

  test("the results kept stay within their bounds, the oldest block dropped first") {
    val results =
      ScriptsAhead.Results.create(ScriptsAhead.Limits(blocks = 2, entries = 3, bytes = 1L << 20))
    val result = EvalResult.Success(ExUnits(1, 1), Vector.empty)
    def numbered(i: Int) = request.copy(budget = ExUnits(i, i))
    assert(results.put(block(1), numbered(1), result))
    assert(results.put(block(2), numbered(2), result))
    // a third block: the oldest block's results go
    assert(results.put(block(3), numbered(3), result))
    assertEquals(results.lookup(numbered(1)), None)
    assertEquals(results.held._1, 2)
    assert(results.put(block(3), numbered(4), result))
    // three entries kept: a fourth is not kept, and those kept stay
    assert(!results.put(block(3), numbered(5), result))
    assertEquals(results.lookup(numbered(2)), Some(result))
    results.drop(block(3))
    assertEquals(results.held._1, 1)
    assertEquals(results.lookup(numbered(4)), None)
    // a result heavier than the bytes bound is not kept
    val small = ScriptsAhead.Results.create(ScriptsAhead.Limits(4, 4, 100))
    assert(!small.put(block(1), request, result))
  }

  test("a kept result's weight is computed on deep data without exhausting the stack") {
    val deep = (1 to 200000).foldLeft(PlutusData.int(0))((d, _) => PlutusData.List(Vector(d)))
    val weight = ScriptsAhead.Results.weight(
      request.copy(args = Vector(deep)),
      EvalResult.Success(ExUnits(1, 1), Vector.empty)
    )
    assert(weight > 200000L * 48)
  }

  // ---- the speculator ----------------------------------------------------------------------

  test("a queued block's scripts are evaluated ahead and found by the rules") {
    withSpeculator(Map(block(1) -> Vector(request))) { (ahead, calls, _) =>
      for
        done <- settled(ahead, block(1))
        _ <- ahead.ahead(header(1, 0), Bytes.empty)
        _ <- done
        _ <- ahead.reached(block(1))
        rules = ahead.results.evaluator(counting(deterministic(_ => false), calls))
        answered <- IO(rules.evaluate(request))
        tallies <- ahead.tallied
      yield
        assertEquals(answered, deterministic(_ => false).evaluate(request))
        assertEquals(calls.get, 1) // the speculator's evaluation only
        assertEquals(tallies.evaluated, 1L)
        assertEquals(tallies.reached, ScriptsAhead.Reached.zero.copy(done = 1))
        assertEquals(ahead.results.counts.hit, 1L)
    }
  }

  test("a block the worker reaches before its evaluation starts is not evaluated ahead") {
    for
      gate <- Deferred[IO, Unit]
      _ <- withSpeculator(Map(block(1) -> Vector(request)), gate = Some(gate)) {
        (ahead, calls, _) =>
          for
            running <- ahead.held.discrete
              .find(_.get(block(1)).contains(ScriptsAhead.Slot.Preparing))
              .compile
              .drain
              .start
            _ <- ahead.ahead(header(1, 0), Bytes.empty)
            _ <- running.joinWithNever
            _ <- ahead.reached(block(1))
            _ <- gate.complete(())
            // the slot stays until the speculation of the block ends, the gate holding it before
            _ <- ahead.held.discrete.find(!_.contains(block(1))).compile.drain
            tallies <- ahead.tallied
          yield
            assertEquals(calls.get, 0)
            assertEquals(tallies.abandoned, 1L)
            assertEquals(tallies.reached, ScriptsAhead.Reached.zero.copy(preparing = 1))
            assertEquals(ahead.results.held._1, 0)
      }
    yield ()
  }

  test("a block passed by has its results dropped, and the block before the next one taken too") {
    withSpeculator(
      Map(block(1) -> Vector(request), block(2) -> Vector(request.copy(budget = ExUnits(1, 2))))
    ) { (ahead, _, _) =>
      for
        one <- settled(ahead, block(1))
        two <- settled(ahead, block(2))
        _ <- ahead.ahead(header(1, 0), Bytes.empty)
        _ <- ahead.ahead(header(2, 1), Bytes.empty)
        _ <- one *> two
        _ <- IO(assertEquals(ahead.results.held._1, 2))
        _ <- ahead.passed(block(1))
        _ <- IO(assertEquals(ahead.results.held._1, 1))
        _ <- ahead.reached(block(2))
        _ <- IO(assertEquals(ahead.results.held._1, 1))
        // the worker is done with block 2 once it takes the next, here one it passes by
        _ <- ahead.passed(block(3))
      yield assertEquals(ahead.results.held._1, 0)
    }
  }

  test("a block whose predecessors do not lead back to the tip is not evaluated ahead") {
    withSpeculator(Map(block(5) -> Vector(request))) { (ahead, calls, _) =>
      for
        done <- settled(ahead, block(5))
        _ <- ahead.ahead(header(5, 4), Bytes.empty)
        _ <- done
        tallies <- ahead.tallied
      yield
        assertEquals(calls.get, 0)
        assertEquals(tallies.skipped, 1L)
    }
  }

  test("a block's outputs are seen by the queued blocks after it, through their predecessors") {
    val made = TxIn(block(1), 0)
    withSpeculator(
      Map(block(1) -> Vector.empty, block(2) -> Vector(request)),
      created = Map(block(1) -> Map(made -> TestLedger.out(TestLedger.key().address(), 1))),
      needs = Map(block(2) -> made)
    ) { (ahead, calls, _) =>
      for
        one <- settled(ahead, block(1))
        two <- settled(ahead, block(2))
        _ <- ahead.ahead(header(1, 0), Bytes.empty)
        _ <- ahead.ahead(header(2, 1), Bytes.empty)
        _ <- one *> two
        tallies <- ahead.tallied
      yield
        assertEquals(calls.get, 1)
        assertEquals(tallies.evaluated, 1L)
        assertEquals(ahead.results.lookup(request).isDefined, true)
    }
  }

  test("a block whose requests budget more than the block's limit is not evaluated ahead") {
    // a redeemer collected for two scripts budgets twice: here the requests' budgets exceed the limit
    val over = Vector(
      request.copy(budget = ExUnits(BigInt(1) << 39, 1)),
      request.copy(budget = ExUnits(BigInt(1) << 39, 2)),
      request.copy(budget = ExUnits(BigInt(1) << 39, 3))
    )
    withSpeculator(Map(block(1) -> over)) { (ahead, calls, _) =>
      for
        done <- settled(ahead, block(1))
        _ <- ahead.ahead(header(1, 0), Bytes.empty)
        _ <- done
        tallies <- ahead.tallied
      yield
        assertEquals(calls.get, 0)
        assertEquals(tallies.skipped, 1L)
    }
  }

  test("a failure holding a term of the machine is not kept, one without is") {
    // a cause never discharged: nothing reads it
    val cause = xyz.kd5ujc.scarab.uplc.cek.Cause(fail("the cause was discharged"))
    val withCause: ScriptEvaluator[Id] = _ =>
      EvalResult.Failure(
        EvalFailure.Script(
          ScriptFailure.Uplc(
            EvaluationError.Cek(
              xyz.kd5ujc.scarab.uplc.cek.CekFailure(
                xyz.kd5ujc.scarab.uplc.cek.CekError.ErrorTerm,
                Some(cause)
              )
            ),
            Vector("trace")
          )
        ),
        ExUnits(1, 1),
        Vector("trace")
      )
    for
      _ <- withSpeculator(Map(block(1) -> Vector(request)), underlying = withCause) {
        (ahead, _, _) =>
          for
            done <- settled(ahead, block(1))
            _ <- ahead.ahead(header(1, 0), Bytes.empty)
            _ <- done
            tallies <- ahead.tallied
          yield
            assertEquals(tallies.retaining, 1L)
            assertEquals(ahead.results.held._1, 0)
      }
      _ <- withSpeculator(Map(block(1) -> Vector(request)), underlying = deterministic(_ => true)) {
        (ahead, _, _) =>
          for
            done <- settled(ahead, block(1))
            _ <- ahead.ahead(header(1, 0), Bytes.empty)
            _ <- done
            tallies <- ahead.tallied
          yield
            assertEquals(tallies.evaluated, 1L)
            assertEquals(ahead.results.held._1, 1)
      }
    yield ()
  }

  test("an answer that is no verdict is not kept") {
    val unsupported: ScriptEvaluator[Id] =
      _ =>
        EvalResult.Failure(
          EvalFailure.Unsupported(BackendFailure.StackExhausted),
          ExUnits.zero,
          Vector.empty
        )
    withSpeculator(Map(block(1) -> Vector(request)), underlying = unsupported) { (ahead, _, _) =>
      for
        done <- settled(ahead, block(1))
        _ <- ahead.ahead(header(1, 0), Bytes.empty)
        _ <- done
        tallies <- ahead.tallied
      yield
        assertEquals(tallies.unanswered, 1L)
        assertEquals(ahead.results.held._1, 0)
    }
  }

  // ---- the speculation's view of real blocks ------------------------------------------------

  test("on the era-walking chain: speculated within an era, and the outputs created as stored") {
    import xyz.kd5ujc.scarab.node.read.ErasStates
    import xyz.kd5ujc.scarab.consensus.state.{MirrorStep, Placement}
    val setup = ErasStates.setup
    val placement = Placement(setup.praos.geometry, setup.byronHistory, setup.praos.forks)
    val samples = ErasStates.samples
    val seen =
      samples.indices.drop(1).foldLeft((0, 0, 0)) { case ((within, entering, outputs), i) =>
        val before = samples(i - 1).mirror
        samples(i).block.header match
          case h: Header.Shelley =>
            val scripts =
              ScriptsAheadEras.scripts(
                setup.globals.systemStart,
                placement,
                before,
                h,
                ErasStates.raws(i)
              )
            val sameEra = MirrorStep.nesOf(before).exists { case n: EraNes.InEra[e] =>
              EraNes.eraOf(n.era) == h.era
            }
            assertEquals(scripts.isDefined, sameEra, s"block $i of ${h.era}")
            val after = MirrorStep.utxoOf(samples(i).mirror).getOrElse(fail("no UTxO set"))
            // every output a block creates is the output the rules stored, where none spent it since
            val made = scripts.fold(0)(_.created.count { (in, out) =>
              after.get(in).fold(false) { stored =>
                assertEquals(stored, out, s"block $i output $in")
                true
              }
            })
            if sameEra then (within + 1, entering, outputs + made)
            else (within, entering + 1, outputs)
          case _ =>
            assertEquals(
              ScriptsAheadEras.scripts(
                setup.globals.systemStart,
                placement,
                before,
                PreparedBlockFixture.babbageHeader,
                ErasStates.raws(i)
              ),
              None
            )
            (within, entering, outputs)
      }
    // blocks of every Shelley-based era are speculated, the first block of each era is not, and the
    // transactions' outputs were compared
    assert(seen._1 > 600, seen.toString)
    assert(seen._2 >= 6, seen.toString)
    assert(seen._3 > 0, seen.toString)
  }

  // ---- equivalence on real Babbage transactions --------------------------------------------

  private val fixture = PreparedBlockFixture

  test("all found: the same outcome, verdicts, budgets and traces, serially and prepared") {
    equivalent(fixture.txs, failing = _ => false, hits = 3)
  }

  test(
    "collections taken with no result kept: the scripts evaluated by the rules, counted handed"
  ) {
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    val own = created(fixture.txs)
    val collected = ScriptsAhead.flat[Babbage](
      fixture.txs,
      fixture.params,
      slot => posixOf(slot),
      in => own.get(in).orElse(tipOutputs(in))
    )(using ShelleyBasedEra.SBabbage)
    assertEquals(results.collected(block(1), collected.collections), 3)
    val calls = new AtomicInteger()
    val underlying = deterministic(_ => false)
    val (outcome, observed) =
      applied(
        fixture.state,
        fixture.txs,
        results.evaluator(counting(underlying, calls)),
        results.flat
      )
    val (base, baseObserved) = applied(fixture.state, fixture.txs, underlying)
    assertEquals(outcome, base)
    assertEquals(observed, baseObserved)
    assertEquals(calls.get, 3)
    assertEquals(results.collectionCounts, ScriptsAhead.Collections(3, 0, 3, 0))
    // dropped with their block: no request handed any more
    results.drop(block(1))
    val _ = applied(fixture.state, fixture.txs, results.evaluator(underlying), results.flat)
    assertEquals(results.collectionCounts, ScriptsAhead.Collections(3, 3, 3, 0))
  }

  test("an input made by an earlier queued block: the second block's scripts found") {
    val a = Vector(fixture.dependent(0))
    val b = Vector(fixture.dependent(1), fixture.dependent(2))
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    val calls = new AtomicInteger()
    val underlying = deterministic(_ => false)
    // the speculation of both blocks on the tip, the second through the first's outputs
    val first = speculate(results, block(1), a, fixture.params, tipOutputs, underlying)
    val second =
      speculate(
        results,
        block(2),
        b,
        fixture.params,
        in => created(a).get(in).orElse(tipOutputs(in)),
        underlying
      )
    assertEquals((first.skipped, second.skipped), (0, 0))
    assertEquals(second.requests.size, 2)
    val rules = results.evaluator(counting(underlying, calls))
    val (afterA, observedA) = applied(fixture.state, a, rules, results.flat)
    val (baseA, baseObservedA) = applied(fixture.state, a, underlying)
    assertEquals(afterA, baseA)
    assertEquals(observedA, baseObservedA)
    val nes = babbage(afterA)
    val (afterB, observedB) = applied(nes, b, rules, results.flat)
    val (baseB, baseObservedB) = applied(babbage(baseA), b, underlying)
    assertEquals(afterB, baseB)
    assertEquals(observedB, baseObservedB)
    assertEquals(calls.get, 0)
    assertEquals(results.counts.hit, 3L)
    assertEquals(results.collectionCounts, ScriptsAhead.Collections(3, 0, 3, 3))
  }

  test("an input that changed between the speculation and the application is not found") {
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    val underlying = deterministic(_ => false)
    val spent = fixture.txs(0).body.inputs.head
    // as after a rollback onto a chain where the input holds another output
    val other: TxIn => Option[TxOut] = in =>
      tipOutputs(in).map(out =>
        if in == spent then out.copy(value = out.value.copy(coin = out.value.coin + 1)) else out
      )
    speculate(results, block(1), fixture.txs, fixture.params, other, underlying)
    check(
      results,
      fixture.txs,
      underlying,
      hits = 2,
      stale = 1,
      calls = 1,
      collected = 2,
      collectedStale = 1
    )
  }

  test(
    "parameters changed at an epoch boundary: the scripts speculated on the old ones are not found"
  ) {
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    val underlying = deterministic(_ => false)
    val models = fixture.params.costModels.map((k, v) =>
      k -> v.updated(0, v.headOption.getOrElse(BigInt(0)) + 1)
    )
    speculate(
      results,
      block(1),
      fixture.txs,
      fixture.params.copy(costModels = models),
      tipOutputs,
      underlying
    )
    check(
      results,
      fixture.txs,
      underlying,
      hits = 0,
      stale = 3,
      calls = 3,
      collected = 0,
      collectedStale = 3
    )
    val versioned = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    speculate(
      versioned,
      block(1),
      fixture.txs,
      fixture.params.copy(protocolMajor = 9),
      tipOutputs,
      underlying
    )
    check(
      versioned,
      fixture.txs,
      underlying,
      hits = 0,
      stale = 0,
      calls = 3,
      collected = 0,
      collectedStale = 3
    )
  }

  test("failing scripts: a valid-flagged block refused, an invalid-flagged one taking collateral") {
    // the first transaction's script fails: the block is refused there
    equivalent(fixture.txs, failing = _ => true, hits = 1)
    val invalid = fixture.txs.map(tx => tx.copy(isValid = false))
    equivalent(invalid, failing = _ => true, hits = 3)
    // flagged invalid with scripts that pass: refused at the first alike
    equivalent(invalid, failing = _ => false, hits = 1)
  }

  test("another era history: the collections made with its clock not taken where it differs") {
    val txs = fixture.bounded
    val underlying = deterministic(_ => false)
    // the transactions' validity intervals end at slot 5,000: their contexts read the clock there
    val same = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    speculate(same, block(1), txs, fixture.params, tipOutputs, underlying)
    check(same, txs, underlying, hits = 3, stale = 0, calls = 0, collected = 3)
    // a history whose slots start a second later: no collection taken, every context another
    val later = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    speculate(later, block(1), txs, fixture.params, tipOutputs, underlying, posixLater)
    check(later, txs, underlying, hits = 0, stale = 3, calls = 3, collected = 0, collectedStale = 3)
  }

  test("the speculator reads and collects the next block while the scripts of one run") {
    val second = request.copy(budget = ExUnits(1, 2))
    for
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      _ <- withSpeculator(
        Map(block(1) -> Vector(request), block(2) -> Vector(second)),
        hold = r => (entered.complete(()) *> release.get).whenA(r == request)
      ) { (ahead, _, _) =>
        for
          one <- settled(ahead, block(1))
          two <- settled(ahead, block(2))
          _ <- ahead.ahead(header(1, 0), Bytes.empty)
          // block 1's script is being evaluated, and held there
          _ <- entered.get
          _ <- ahead.ahead(header(2, 1), Bytes.empty)
          // block 2 is read and collected, and its script run, while block 1's is still held
          _ <- two
          _ <- IO(assertEquals(ahead.results.lookup(second).isDefined, true))
          _ <- IO(assertEquals(ahead.results.lookup(request), None))
          _ <- release.complete(())
          _ <- one
          tallies <- ahead.tallied
        yield
          assertEquals(tallies.evaluated, 2L)
          assertEquals(ahead.results.lookup(request).isDefined, true)
      }
    yield ()
  }

  test("a collection is charged its requests' arguments whole, and kept only within the bytes") {
    val collection = ScriptsAhead
      .flat[Babbage](fixture.txs.take(1), fixture.params, slot => posixOf(slot), tipOutputs)(using
        ShelleyBasedEra.SBabbage
      )
      .collections
      .headOption
      .getOrElse(fail("not collected"))
    val weighed = ScriptsAhead.Results.Weighed.of(collection)
    val total = weighed.own + weighed.shared.iterator.map(_._2).sum
    // each request's arguments, as a result kept for it is charged them
    val args = collection.requests.iterator.map { r =>
      ScriptsAhead.Results.weight(r, EvalResult.Success(ExUnits.zero, Vector.empty)) - 256L -
        r.script.size - 24L * r.costModel.size
    }.sum
    assert(total >= args + 4L * fixture.txs(0).size, s"$total < $args")
    val tight = ScriptsAhead.Results.create(ScriptsAhead.Limits(4, 4, total - 1))
    assertEquals(tight.collected(block(1), Vector(collection)), 0)
    val room = ScriptsAhead.Results.create(ScriptsAhead.Limits(4, 4, total))
    assertEquals(room.collected(block(1), Vector(collection)), 1)
    assertEquals(room.held, (1, total))
    room.drop(block(1))
    assertEquals(room.held, (0, 0L))
  }

  test("the own evaluator on the recorded script: the same outcome with its results found") {
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    val real = ScarabEvaluator.pure
    speculate(results, block(1), fixture.txs, fixture.params, tipOutputs, real)
    val (outcome, observed) =
      applied(fixture.state, fixture.txs, results.evaluator(ScarabEvaluator.pure), results.flat)
    val (base, baseObserved) = applied(fixture.state, fixture.txs, ScarabEvaluator.pure)
    // the script fails, and a failure's machine cause compares by identity: compared by its text,
    // which names its error, trace, request and budget
    assertEquals(outcome.toString, base.toString)
    assertEquals(observed.map(_.toString), baseObserved.map(_.toString))
    assert(results.counts.hit >= 1L)
    assertEquals((results.counts.miss, results.counts.stale), (0L, 0L))
  }

  /** `txs` applied with their scripts speculated on the tip and found, serially and prepared
    * through four workers, against the same block without: equal outcomes and observations.
    */
  private def equivalent(txs: Vector[Tx], failing: EvalRequest => Boolean, hits: Long): Unit =
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    val underlying = deterministic(failing)
    speculate(results, block(1), txs, fixture.params, tipOutputs, underlying)
    check(results, txs, underlying, hits = hits, stale = 0, calls = 0, collected = hits)

  /** `txs` applied with the rules taking what `results` holds against the same block without: the
    * same outcome and observations, serially and prepared through four workers; the rules found
    * `hits` results and `stale` results kept under other requests, evaluated `calls` scripts
    * themselves, and took `collected` collections kept ahead (`collectedStale` of those kept for
    * their transactions not theirs).
    */
  private def check(
      results: ScriptsAhead.Results,
      txs: Vector[Tx],
      underlying: ScriptEvaluator[Id],
      hits: Long,
      stale: Long,
      calls: Int,
      collected: Long,
      collectedStale: Long = 0
  ): Unit =
    val counted = new AtomicInteger()
    val rules = results.evaluator(counting(underlying, counted))
    val (outcome, observed) = applied(fixture.state, txs, rules, results.flat)
    val (base, baseObserved) = applied(fixture.state, txs, underlying)
    assertEquals(outcome, base)
    assertEquals(observed, baseObserved)
    assertEquals(results.counts.hit, hits)
    assertEquals(results.counts.stale, stale)
    assertEquals(counted.get, calls)
    // a collection kept for each transaction looked for; a taken one's script found by its request
    assertEquals(results.collectionCounts.offered, collected + collectedStale)
    assertEquals(results.collectionCounts.missed, 0L)
    assertEquals(results.collectionCounts.handed, collected)
    assertEquals(results.collectionCounts.byRequest, collected)
    // prepared through the node's workers, the same again
    val prepared = ParallelPlutus
      .create[IO](ParallelPlutus.Workers.from(4).fold(e => fail(e.toString), identity))
      .flatMap(workers =>
        (
          workers(AlwaysPrepared(program(fixture.state, txs, rules, _ => (), results.flat))),
          workers(AlwaysPrepared(program(fixture.state, txs, underlying, _ => ())))
        ).tupled
      )
    import cats.effect.unsafe.implicits.global
    val (ahead, without) = prepared.unsafeRunSync()
    assertEquals(ahead, without)
    assertEquals(ahead, base)

object ScriptsAheadSuite:
  def block(i: Int): Bytes = Bytes(Vector.fill(32)(i.toByte))

  def header(i: Int, prev: Int): Header.Shelley =
    PreparedBlockFixture.babbageHeader.copy(hash = block(i), prevHash = Some(block(prev)))

  /** An evaluator whose every answer is its request's: its budget and trace from the request's
    * structural hash, a failure where `failing` says, so a result given for another request shows.
    */
  def deterministic(failing: EvalRequest => Boolean): ScriptEvaluator[Id] = request =>
    val h = BigInt(request.hashCode.abs % 100000)
    if failing(request) then
      EvalResult.Failure(
        EvalFailure.Script(ScriptFailure.Uplc(EvaluationError.InvalidReturnValue, Vector(s"f$h"))),
        ExUnits(h, h + 1),
        Vector(s"f$h")
      )
    else EvalResult.Success(ExUnits(h, h * 2), Vector(s"s$h"))

  def counting(underlying: ScriptEvaluator[Id], calls: AtomicInteger): ScriptEvaluator[Id] =
    request =>
      val _ = calls.incrementAndGet()
      underlying.evaluate(request)

  /** The slot to time translation the fixture's body runs with (its context has no clock). */
  private val posix: PosixClock =
    PosixClock.total(TestLedger.globals.systemStart, TestLedger.globals.geometry)

  /** The fixture's slot to time translation, as a collection asks it. */
  def posixOf(slot: BigInt): Either[xyz.kd5ujc.scarab.ledger.TranslationError, BigInt] =
    posix.posix(slot)

  /** Another era history: each slot starting a second later. */
  val posixLater: Phase2.Posix = slot => posix.posix(slot).map(_ + 1000)

  def tipOutputs(in: TxIn): Option[TxOut] = PreparedBlockFixture.state.es.ls.utxo.utxo.utxo.get(in)

  def created(txs: Vector[Tx]): Map[TxIn, TxOut] =
    txs.foldLeft(Map.empty[TxIn, TxOut])(_ ++ ScriptsAhead.created(_))

  /** The scripts of the block `hash` of `txs` speculated as the speculator does on a Babbage tip,
    * with `pp` and the outputs `outputs` gives, and kept in `results`.
    */
  def speculate(
      results: ScriptsAhead.Results,
      hash: Bytes,
      txs: Vector[Tx],
      pp: ProtocolParams,
      outputs: TxIn => Option[TxOut],
      underlying: ScriptEvaluator[Id],
      clock: Phase2.Posix = slot => posix.posix(slot)
  ): ScriptsAhead.Collected =
    val own = created(txs)
    val collected = ScriptsAhead.flat[Babbage](
      txs.filter(_.witnesses.redeemers.nonEmpty),
      pp,
      clock,
      in => own.get(in).orElse(outputs(in))
    )(using ShelleyBasedEra.SBabbage)
    assert(results.collected(hash, collected.collections) == collected.collections.size)
    collected.requests.foreach(r => assert(results.put(hash, r, underlying.evaluate(r))))
    collected

  def program(
      state: NewEpochState[Babbage],
      txs: Vector[Tx],
      evaluator: ScriptEvaluator[Id],
      observe: Phase2.Observation => Unit,
      ahead: Phase2.Ahead = Phase2.Ahead.none
  ): EraBlock.Body[Babbage] =
    val header = PreparedBlockFixture.babbageHeader
    val body = Block.Shelley(
      Era.Babbage,
      header,
      txs.map(tx => Block.Transaction(tx.body.raw, Bytes.empty, None, tx.isValid)),
      100,
      Bytes.empty
    )
    val ctx = EraBlock.Context(
      TestLedger.globals,
      _ => None,
      0L,
      evaluator = evaluator,
      observer = Some(observe),
      decoder = ScarabEvaluator.defaultDecoder,
      ahead = ahead
    )
    EraBlock.program(ctx)(state, header, body, txs.map(Right(_)), PlutusStats.zero)

  /** What a block's observations are compared by: all but the clock, a function. */
  type Seen = (
      Tx,
      ProtocolParams,
      BigInt,
      Map[TxIn, TxOut],
      Map[TxIn, TxOut],
      Phase2.Collected[Phase2.Outcome]
  )

  def applied(
      state: NewEpochState[Babbage],
      txs: Vector[Tx],
      evaluator: ScriptEvaluator[Id],
      ahead: Phase2.Ahead = Phase2.Ahead.none
  ): (EraBlock.Outcome, Vector[Seen]) =
    val seen = new ConcurrentLinkedQueue[Seen]()
    val outcome = program(
      state,
      txs,
      evaluator,
      o => { val _ = seen.add((o.tx, o.params, o.slot, o.spent, o.references, o.outcome)) },
      ahead
    ).serial
    (outcome, seen.asScala.toVector)

  def babbage(outcome: EraBlock.Outcome): NewEpochState[Babbage] = outcome match
    case EraBlock.Outcome.Applied(i: EraNes.InEra[e], _) =>
      i.era match
        case ShelleyBasedEra.SBabbage => i.nes
        case other                    => throw new AssertionError(s"not Babbage: $other")
    case other => throw new AssertionError(s"not applied: $other")

  /** A speculator over a fake tip at `block(0)`: each block `blocks` names has one transaction
    * whose requests are those given, collected only if the outputs give `needs` ' input; it creates
    * the outputs `created` names. The tip is read once `gate` completes.
    */
  def withSpeculator(
      blocks: Map[Bytes, Vector[EvalRequest]],
      created: Map[Bytes, Map[TxIn, TxOut]] = Map.empty,
      needs: Map[Bytes, TxIn] = Map.empty,
      gate: Option[Deferred[IO, Unit]] = None,
      underlying: ScriptEvaluator[Id] = deterministic(_ => false),
      hold: EvalRequest => IO[Unit] = _ => IO.unit
  )(body: (ScriptsAhead[IO], AtomicInteger, ScriptsAhead.Results) => IO[Unit]): IO[Unit] =
    val calls = new AtomicInteger()
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    val tip = new ScriptsAhead.Tip[IO]:
      def hash: Option[Bytes] = Some(block(0))
      def scripts(header: Header, raw: Bytes): Option[ScriptsAhead.Scripts] =
        blocks.get(header.hash).map { requests =>
          ScriptsAhead.Scripts(
            1,
            created.getOrElse(header.hash, Map.empty),
            needs.get(header.hash).toSet,
            ExUnits(BigInt(1) << 40, BigInt(1) << 40),
            outputs =>
              if needs.get(header.hash).forall(outputs(_).isDefined) then
                ScriptsAhead.Collected(requests, Vector.empty, 0)
              else ScriptsAhead.Collected(Vector.empty, Vector.empty, 1)
          )
        }
      def outputs(keys: Set[TxIn]): IO[Map[TxIn, TxOut]] = IO.pure(Map.empty)
    val speculator = ScriptsAhead.Speculator[IO](
      _ => IO.pure(true),
      gate.fold(IO.unit)(_.get).as(tip),
      request => hold(request) *> IO.blocking(counting(underlying, calls).evaluate(request)),
      2
    )
    ScriptsAhead.resource[IO](speculator, results).use(ahead => body(ahead, calls, results))

  /** Completes once the speculation of `hash` is over (its slot done or gone), started before the
    * block is asked for, so that its change is not missed.
    */
  def settled(ahead: ScriptsAhead[IO], hash: Bytes): IO[IO[Unit]] =
    ahead.held.discrete
      .dropWhile(!_.contains(hash))
      .find(held => held.get(hash).forall(_ == ScriptsAhead.Slot.Done))
      .compile
      .drain
      .start
      .map(_.joinWithNever)
