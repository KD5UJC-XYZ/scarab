// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.node.mempool.LocalSubmission.message
import cats.data.NonEmptyList
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor
import xyz.kd5ujc.scarab.plutus.ExUnits

import Capacity.{component, exceeded, fits}
import TestLedger.txIn
import TxKit.*

/** The measures of a transaction and the capacity of the pool, as `Mempool/Update.hs` and
  * `Shelley/Ledger/Mempool.hs` of ouroboros-consensus define them: the refusal of a measure is the
  * only failure returned, the capacity is a vector of block-sized units, and both follow the
  * protocol parameters.
  */
class MempoolMeasureSuite extends munit.FunSuite with ScalaCheckSuite:
  private def pool(ledger: MempoolLedger, capacityOverride: Option[Long] = None) =
    MempoolState.empty(Right(ledger), 1000, capacityOverride)

  private def add(s: MempoolState, raw: Bytes): Either[Rejection, MempoolState] =
    s.add(raw, env, Source.Local).map(_._1)

  private def held(s: MempoolState, raw: Bytes): MempoolState =
    add(s, raw).fold(r => fail(r.message), identity)

  private def sizeOf(raw: Bytes): Long =
    MempoolState.parse(raw).fold(r => fail(r.message), _._2.size)

  private def refused(rejection: Rejection): Bytes =
    LocalSubmission.reasonItem(rejection).fold(e => fail(e.message), identity)

  // a transaction that no rule of the ledger would admit: its input does not exist
  private def unknownInput(exUnits: Option[(BigInt, BigInt)] = None): (Bytes, Bytes) =
    tx(
      Vector(txIn(900)),
      Vector(bob.address() -> (99 * ada)),
      ada,
      Vector(alice),
      exUnits = exUnits
    )

  private def limitsOf(r: Either[Rejection, Any]): List[PredicateFailure] = r match
    case Left(Rejection.Limits(_, failures)) => failures.toList
    case other => fail(s"expected a refusal of the measure, received $other")

  test("a transaction over the maximum size is refused with that failure alone") {
    val (raw, id) = unknownInput()
    val small = ledger(1).withParams(_.copy(maxTxSize = 100))
    val result = add(pool(small), raw)
    val expected =
      PredicateFailure.utxo(UtxoFailure.MaxTxSize(Mismatch(BigInt(sizeOf(raw)), BigInt(100))))
    assertEquals(limitsOf(result), List(expected))
    // the ledger did not run: with the default maximum the same bytes fail the MEMPOOL rule
    assertEquals(
      add(pool(ledger(1)), raw),
      Left(
        Rejection.Ledger(
          LedgerFailure.Rejected(id, PredicateFailure.Mempool(MempoolProblem.AllInputsSpent))
        )
      )
    )
    assertEquals(
      refused(result.left.getOrElse(fail("accepted"))),
      PredicateFailureCbor.applyTxError(Vector(expected))
    )
  }

  test("a transaction over its execution-unit maximum is refused with that failure alone") {
    val (raw, _) = unknownInput(Some((BigInt(11), BigInt(5))))
    val limited = ledger(1).withParams(_.copy(maxTxExUnits = ExUnits(10, 10)))
    val result = add(pool(limited), raw)
    val expected = PredicateFailure.utxo(
      UtxoFailure.ExUnitsTooBig(Mismatch(ExUnits(11, 5), ExUnits(10, 10)))
    )
    assertEquals(limitsOf(result), List(expected))
    assertEquals(
      refused(result.left.getOrElse(fail("accepted"))),
      PredicateFailureCbor.applyTxError(Vector(expected))
    )
  }

  test("a transaction over both maxima is refused with both, size first, as Validation collects") {
    val (raw, id) = unknownInput(Some((BigInt(11), BigInt(5))))
    val limited =
      ledger(1).withParams(_.copy(maxTxSize = 100, maxTxExUnits = ExUnits(10, 10)))
    val failures = List(
      PredicateFailure.utxo(UtxoFailure.MaxTxSize(Mismatch(BigInt(sizeOf(raw)), BigInt(100)))),
      PredicateFailure.utxo(UtxoFailure.ExUnitsTooBig(Mismatch(ExUnits(11, 5), ExUnits(10, 10))))
    )
    assertEquals(
      add(pool(limited), raw),
      Left(Rejection.Limits(id, NonEmptyList.fromListUnsafe(failures)))
    )
  }

  private def refScriptOutput(size: Int): TxOut =
    val (raw, _) = tx(
      Vector(txIn(1)),
      Vector(bob.address() -> (99 * ada)),
      ada,
      Vector(alice),
      referenceScript = Some(ScriptKind.PlutusV2 -> Bytes(Vector.fill(size)(1.toByte)))
    )
    MempoolState
      .parse(raw)
      .fold(r => fail(r.message), _._2.body.outputs.head)

  // a ledger with a reference script of `size` bytes at txIn(50)
  private def withRefScript(l: MempoolLedger, size: Int): MempoolLedger =
    l.withUtxo(l.utxo.updated(txIn(50), refScriptOutput(size)))

  private def spendRefScript: (Bytes, Bytes) =
    tx(Vector(txIn(50)), Vector(bob.address() -> (99 * ada)), ada, Vector(alice))

  test("reference scripts over the per-transaction maximum are refused with that failure alone") {
    val size = RefScripts.MaxRefScriptSizePerTx + 1
    val l = withRefScript(ledger(1), size)
    assertEquals(l.utxo.get(txIn(50)).map(_.referenceScriptSize), Some(size))
    val (raw, id) = spendRefScript
    val expected = PredicateFailure.TxRefScriptsSizeTooBig(
      Mismatch(size, RefScripts.MaxRefScriptSizePerTx)
    )
    val result = add(pool(l), raw)
    assertEquals(result, Left(Rejection.Ledger(LedgerFailure.Rejected(id, expected))))
    assertEquals(
      refused(result.left.getOrElse(fail("accepted"))),
      PredicateFailureCbor.applyTxError(Vector(expected))
    )
  }

  test("the reference scripts are measured in the state after the held transactions") {
    val size = RefScripts.MaxRefScriptSizePerTx + 1
    val s0 = pool(ledger(1))
    val (raw, _) = spendRefScript
    val afterHeld = s0.copy(current = Right(withRefScript(ledger(1), size)))
    assert(
      add(afterHeld, raw).left.exists {
        case Rejection.Ledger(
              LedgerFailure.Rejected.Single(_, _: PredicateFailure.TxRefScriptsSizeTooBig)
            ) =>
          true
        case _ => false
      }
    )
    // the base alone does not hold the output: the transaction is measured at zero and the rules
    // then refuse its input
    assert(
      add(s0, raw).left.exists {
        case Rejection.Ledger(
              LedgerFailure.Rejected
                .Single(_, PredicateFailure.Mempool(MempoolProblem.AllInputsSpent))
            ) =>
          true
        case _ => false
      }
    )
  }

  test("each component of the capacity vector can be the one that refuses") {
    val params = (l: MempoolLedger) =>
      l.withParams(
        _.copy(
          maxBlockBodySize = 1024 + 100000,
          maxTxExUnits = ExUnits(1000000, 1000000),
          maxBlockExUnits = ExUnits(100, 100)
        )
      )
    val l = params(ledger(2))
    val mem = unknownInput(Some((BigInt(250), BigInt(0))))._1
    val steps = unknownInput(Some((BigInt(0), BigInt(250))))._1
    def dimension(r: Either[Rejection, Any]): Option[(CapacityDimension, BigInt, BigInt)] =
      r.left.toOption.collect { case f: Rejection.Full =>
        val d = f.dimension
        (d, f.needed.component(d), f.free.component(d))
      }
    // two blocks of 100: 250 does not fit, in that component only
    assertEquals(
      dimension(add(pool(l), mem)),
      Some((CapacityDimension.ExUnitsMemory, BigInt(250), BigInt(200)))
    )
    assertEquals(
      dimension(add(pool(l), steps)),
      Some((CapacityDimension.ExUnitsSteps, BigInt(250), BigInt(200)))
    )
    // bytes: a block with room for a little more than one transaction
    val (t1, _) = pay(1)
    val unit = (sizeOf(t1) + 4L) * 3 / 4 + 1
    val tight = pool(ledger(2).withParams(_.copy(maxBlockBodySize = 1024 + unit)))
    val (t2, _) = pay(2)
    assertEquals(
      dimension(add(held(tight, t1), t2)).map(_._1),
      Some(CapacityDimension.Bytes)
    )
    // reference-script bytes: 100 000 needed with fewer than that free
    val roomy = withRefScript(ledger(2), 100000)
    val (spend, _) = spendRefScript
    val base = held(pool(roomy), pay(1)._1)
    val nearlyFull = base.copy(entries =
      base.entries.map(_.copy(refScriptBytes = 2 * RefScripts.MaxRefScriptSizePerBlock - 99999))
    )
    assertEquals(
      dimension(add(nearlyFull, spend)),
      Some((CapacityDimension.RefScriptBytes, BigInt(100000), BigInt(99999)))
    )
  }

  property("a measure fits the capacity exactly when no component exceeds it") {
    val component = Gen.choose(0L, 5L)
    val measure = for
      b <- component
      m <- component
      s <- component
      r <- component
    yield TxMeasure(b, m, s, r)
    forAll(measure, measure) { (m, limit) =>
      val over = m.exceeded(limit)
      (m.fits(limit) == over.isEmpty) && over.forall(d => m.component(d) > limit.component(d))
    }
  }

  test("the order: measure, duplicate, capacity, reference scripts, capacity, the ledger") {
    val (t1, id1) = pay(1)
    val (big, _) = unknownInput()
    val narrow = (l: MempoolLedger) => l.withParams(_.copy(maxBlockBodySize = 1024 + 50))
    // the capacity refuses a transaction that the measure admits, and the measure comes first
    assert(add(pool(narrow(ledger(2))), big).left.exists(_.isInstanceOf[Rejection.Full]))
    assert(
      add(pool(narrow(ledger(2)).withParams(_.copy(maxTxSize = 50))), big).left
        .exists(_.isInstanceOf[Rejection.Limits])
    )
    // a duplicate on a pool without room is answered as a duplicate, not as full
    val over = held(pool(ledger(2)), t1)
      .sync(Right(narrow(ledger(2))), 1000, env)
      .verdict
      ._1
    assert(over.free.bytes < 0)
    assertEquals(add(over, t1), Left(Rejection.AlreadyHeld(id1)))
    // the first capacity check does not look at reference scripts: a pool full in bytes makes a
    // transaction with too many of them wait (here: Full) instead of refusing it
    val l = withRefScript(ledger(2), RefScripts.MaxRefScriptSizePerTx + 1)
    val unit = (sizeOf(t1) + 4L) * 3 / 4 + 1
    val s1 = held(pool(l.withParams(_.copy(maxBlockBodySize = 1024 + unit))), t1)
    val (spend, _) = spendRefScript
    assertEquals(add(s1, spend).left.map(_.getClass), Left(classOf[Rejection.Full]))
  }

  test("the default capacity is two blocks and follows the parameters at the next revalidation") {
    val l = ledger(2)
    val unit = l.params.maxBlockBodySize.toLong - 1024
    val s0 = pool(l)
    assertEquals(s0.capacity, Capacity.of(l.params, None))
    assertEquals(s0.capacity.bytes, 2 * unit)
    assertEquals(s0.capacity.refScriptsBytes, 2L * RefScripts.MaxRefScriptSizePerBlock)
    val grown = l.withParams(_.copy(maxBlockBodySize = 1024 + 3 * unit))
    val (synced, dropped) = s0.sync(Right(grown), 1000, env).verdict
    assertEquals(dropped, Vector.empty)
    assertEquals(synced.capacity.bytes, 6 * unit)
    assertEquals(synced.capacity, Capacity.of(grown.params, None))
  }

  test("an override is rounded up to whole blocks, at least one") {
    val l = ledger(1)
    val unit = l.params.maxBlockBodySize.toLong - 1024
    assertEquals(pool(l, Some(1)).capacity.bytes, unit)
    assertEquals(pool(l, Some(unit)).capacity.bytes, unit)
    assertEquals(pool(l, Some(unit + 1)).capacity.bytes, 2 * unit)
    assertEquals(pool(l, Some(3 * unit - 1)).capacity.bytes, 3 * unit)
    assertEquals(
      pool(l, Some(3 * unit + 1)).capacity.exUnitsMemory,
      4 * l.params.maxBlockExUnits.mem
    )
  }

  test("a pool over its capacity after a parameter change keeps what it holds and takes no more") {
    val (t1, _) = pay(1)
    val (t2, _) = pay(2)
    val (t3, _) = pay(3)
    val s = held(held(pool(ledger(3)), t1), t2)
    val shrunk = ledger(3).withParams(_.copy(maxBlockBodySize = 1024 + 10))
    val (synced, dropped) = s.sync(Right(shrunk), 1000, env).verdict
    assertEquals(dropped, Vector.empty)
    assertEquals(synced.entries.size, 2)
    assert(!synced.used.fits(synced.capacity))
    assert(synced.free.bytes < 0, synced.free.toString)
    assert(add(synced, t3).left.exists(_.isInstanceOf[Rejection.Full]))
  }

  test("LocalTxMonitor reports the capacity the pool enforces") {
    val l = ledger(2)
    val unit = l.params.maxBlockBodySize.toLong - 1024
    val states = List(
      pool(l),
      pool(l, Some(1)),
      pool(l, Some(5 * unit)),
      held(pool(l), pay(1)._1),
      pool(l)
        .sync(Right(l.withParams(_.copy(maxBlockBodySize = 1024 + 7 * unit))), 1000, env)
        .verdict
        ._1,
      pool(l).copy(base = Left(xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.InByron))
    )
    states.foreach { s =>
      assertEquals(LocalMonitor.snapshot(s).capacity, s.capacity)
    }
    assertEquals(
      LocalMonitor.snapshot(states.last).capacity,
      TxMeasure.zero
    )
  }

  test("Full carries the measure that did not fit and the capacity left") {
    val (t1, _) = pay(1)
    val (t2, _) = pay(2)
    val unit = (sizeOf(t1) + 4L) * 3 / 4 + 1
    val s1 = held(pool(ledger(2).withParams(_.copy(maxBlockBodySize = 1024 + unit))), t1)
    add(s1, t2) match
      case Left(full: Rejection.Full) =>
        assertEquals(full.needed, TxMeasure(sizeOf(t2) + 4L, 0, 0, 0))
        assertEquals(full.free, s1.free)
        assertEquals(
          full.message,
          s"mempool full: ${sizeOf(t2) + 4L} bytes needed, ${s1.free.bytes} free"
        )
        assertEquals(
          refused(full),
          PredicateFailureCbor.applyTxError(Vector(PredicateFailure.Mempool(full.problem)))
        )
      case other => fail(s"expected a full pool, received $other")
  }
