// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission.message
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor
import xyz.kd5ujc.scarab.ledger.era
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayLedgerPredFailure, ConwayUtxoPredFailure}
import xyz.kd5ujc.scarab.ledger.state.Conway
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
  private def pool(ledger: ConwayPoolLedger, capacityOverride: Option[Long] = None) =
    MempoolState.empty(Right(ledger), 1000, capacityOverride.map(word32))

  private def add(s: MempoolState, raw: Bytes): Either[Rejection, MempoolState] =
    s.add(raw, env, Source.Local).map(_._1)

  private def held(s: MempoolState, raw: Bytes): MempoolState =
    add(s, raw).fold(r => fail(r.message), identity)

  private def sizeOf(raw: Bytes): Long =
    MempoolState.parse(raw).fold(r => fail(r.message), _._2.size)

  private def refused(rejection: Rejection): Bytes =
    LocalSubmission.reasonItem(rejection, Some(Era.Conway)).fold(e => fail(e.message), identity)

  // a transaction that no rule of the ledger would admit: its input does not exist
  private def unknownInput(exUnits: Option[(BigInt, BigInt)] = None): (Bytes, Bytes) =
    tx(
      Vector(txIn(900)),
      Vector(bob.address() -> (99 * ada)),
      ada,
      Vector(alice),
      exUnits = exUnits
    )

  /** A refusal of the measure, on Conway's types, compared by its bytes with the refusal of
    * `failures`, in their order.
    */
  private def measuredAs(
      r: Either[Rejection, MempoolState],
      id: Bytes,
      first: ConwayRefusals.Failure,
      rest: ConwayRefusals.Failure*
  ): Unit =
    assert(r.left.exists(_.isInstanceOf[Rejection.ConwayRefused]), s"$r")
    assertEquals(answered(r), answered(Left(ConwayRefusals.refused(id, first, rest*))))

  private def maxTxSize(supplied: BigInt, limit: BigInt): ConwayRefusals.Failure =
    ConwayRefusals.utxo(
      ConwayUtxoPredFailure.MaxTxSizeUTxO[Conway](era.Mismatch(supplied, limit))
    )

  private def exUnitsTooBig(supplied: ExUnits, limit: ExUnits): ConwayRefusals.Failure =
    ConwayRefusals.utxo(
      ConwayUtxoPredFailure.ExUnitsTooBigUTxO[Conway](era.Mismatch(supplied, limit))
    )

  test("a transaction over the maximum size is refused with that failure alone") {
    val (raw, id) = unknownInput()
    val small = ledger(1).withParams(_.copy(maxTxSize = 100))
    val result = add(pool(small), raw)
    val expected = maxTxSize(BigInt(sizeOf(raw)), BigInt(100))
    measuredAs(result, id, expected)
    // the ledger did not run: with the default maximum the same bytes fail the MEMPOOL rule
    val spent = ConwayRefusals.refused(
      id,
      ConwayLedgerPredFailure.ConwayMempoolFailure[Conway](MempoolProblem.AllInputsSpent)
    )
    assertEquals(answered(add(pool(ledger(1)), raw)), answered(Left(spent)))
    assertEquals(
      refused(result.left.getOrElse(fail("accepted"))),
      refused(ConwayRefusals.refused(id, expected))
    )
  }

  test("a transaction over its execution-unit maximum is refused with that failure alone") {
    val (raw, id) = unknownInput(Some((BigInt(11), BigInt(5))))
    val limited = ledger(1).withParams(_.copy(maxTxExUnits = ExUnits(10, 10)))
    val result = add(pool(limited), raw)
    val expected = exUnitsTooBig(ExUnits(11, 5), ExUnits(10, 10))
    measuredAs(result, id, expected)
    assertEquals(
      refused(result.left.getOrElse(fail("accepted"))),
      refused(ConwayRefusals.refused(id, expected))
    )
  }

  test("a transaction over both maxima is refused with both, size first, as Validation collects") {
    val (raw, id) = unknownInput(Some((BigInt(11), BigInt(5))))
    val limited =
      ledger(1).withParams(_.copy(maxTxSize = 100, maxTxExUnits = ExUnits(10, 10)))
    measuredAs(
      add(pool(limited), raw),
      id,
      maxTxSize(BigInt(sizeOf(raw)), BigInt(100)),
      exUnitsTooBig(ExUnits(11, 5), ExUnits(10, 10))
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
  private def withRefScript(l: ConwayPoolLedger, size: Int): ConwayPoolLedger =
    l.withUtxo(l.utxo.updated(txIn(50), refScriptOutput(size)))

  private def spendRefScript: (Bytes, Bytes) =
    tx(Vector(txIn(50)), Vector(bob.address() -> (99 * ada)), ada, Vector(alice))

  test("reference scripts over the per-transaction maximum are refused with that failure alone") {
    val size = RefScripts.MaxRefScriptSizePerTx + 1
    val l = withRefScript(ledger(1), size)
    assertEquals(l.utxo.get(txIn(50)).map(_.referenceScriptSize), Some(size.toLong))
    val (raw, id) = spendRefScript
    val expected = ConwayRefusals.refused(
      id,
      ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig[Conway](
        era.Mismatch(size.toLong, RefScripts.MaxRefScriptSizePerTx.toLong)
      )
    )
    val result = add(pool(l), raw)
    assert(result.left.exists(_.isInstanceOf[Rejection.ConwayRefused]), s"$result")
    assertEquals(answered(result), answered(Left(expected)))
    assertEquals(refused(result.left.getOrElse(fail("accepted"))), refused(expected))
  }

  test("the reference scripts of a transaction are measured in 64 bits, as the reference's Int") {
    // `txMeasureRefScripts` measures `txNonDistinctRefScriptsSize :: ... -> Int`
    // (`Shelley/Ledger/Mempool.hs:714-726`, ouroboros-consensus 82ecba32): 2048 inputs, each naming
    // a script of 1 MiB, hold 2^31 bytes, one more than a 32-bit Int holds
    val script = refScriptOutput(1 << 20)
    val inputs = Vector.tabulate(2048)(i => TxIn(txIn(60).id, i))
    val base = ledger(1).withParams(_.copy(maxTxSize = 1 << 17))
    val l = base.withUtxo(inputs.foldLeft(base.utxo)(_.updated(_, script)))
    val (raw, _) = tx(inputs, Vector(bob.address() -> (99 * ada)), ada, Vector(alice))
    val total = BigInt(1) << 31
    add(pool(l), raw) match
      case Left(Rejection.ConwayRefused(_, failures)) if failures.length == 1 =>
        val m = failures.head match
          case ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig(m) => m
          case other => fail(s"expected the size of the reference scripts refused, got $other")
        assertEquals(
          (BigInt(m.supplied), BigInt(m.expected)),
          (total, BigInt(RefScripts.MaxRefScriptSizePerTx))
        )
      case other => fail(s"expected the size of the reference scripts refused, got $other")
  }

  test(
    "an output that is both spent and a reference input is measured once, as the reference does"
  ) {
    // `txNonDistinctRefScriptsSize` measures the union of the inputs and the reference inputs
    // (`Conway/UTxO.hs:164-170`, cardano-ledger f649f975); from protocol version 11 an input may
    // also be a reference input.
    // Its script of 150 000 bytes is within the per-transaction maximum once, not twice.
    val size = 150000
    val l = withRefScript(ledger(1), size).withProtocol(11)
    val (raw, id) = tx(
      Vector(txIn(50)),
      Vector(alice.address() -> (89 * ada)),
      10 * ada,
      Vector(bob),
      referenceInputs = Vector(txIn(50))
    )
    val admitted = add(pool(l), raw).fold(r => fail(r.message), identity)
    assertEquals(admitted.entries.map(e => (e.id, e.refScriptBytes)), Vector((id, size.toLong)))
  }

  test("the reference scripts are measured in the state after the held transactions") {
    val size = RefScripts.MaxRefScriptSizePerTx + 1
    val s0 = pool(ledger(1))
    val (raw, _) = spendRefScript
    val afterHeld = s0.withCurrent(withRefScript(ledger(1), size))
    assert(
      add(afterHeld, raw).left.exists {
        case Rejection.ConwayRefused(_, failures) =>
          failures.toChain.toList match
            case List(ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig(_)) => true
            case _                                                             => false
        case _ => false
      }
    )
    // the base alone does not hold the output: the transaction is measured at zero and the rules
    // then refuse its input
    assert(
      add(s0, raw).left.exists {
        case Rejection.ConwayRefused(_, failures) =>
          failures.toChain.toList match
            case List(
                  ConwayLedgerPredFailure.ConwayMempoolFailure(MempoolProblem.AllInputsSpent)
                ) =>
              true
            case _ => false
        case _ => false
      }
    )
  }

  test("each component of the capacity vector can be the one that refuses") {
    val params = (l: ConwayPoolLedger) =>
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
    val nearlyFull = base.holding(
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

  test("the order: measure, capacity, reference scripts, capacity, the duplicate and the ledger") {
    val (t1, id1) = pay(1)
    val (big, _) = unknownInput()
    val narrow = (l: ConwayPoolLedger) => l.withParams(_.copy(maxBlockBodySize = 1024 + 50))
    // the capacity refuses a transaction that the measure admits, and the measure comes first
    assert(add(pool(narrow(ledger(2))), big).left.exists(_.isInstanceOf[Rejection.Full]))
    assert(
      add(pool(narrow(ledger(2)).withParams(_.copy(maxTxSize = 50))), big).left
        .exists(_.isInstanceOf[Rejection.ConwayRefused])
    )
    // a duplicate on a pool without room waits for room, as the reference's MEMPOOL rule, which
    // refuses it, comes after the capacity; with room it is refused as the duplicate it is
    val over = held(pool(ledger(2)), t1)
      .sync(Right(narrow(ledger(2))), 1000, env)
      ._1
    assert(over.free.bytes < 0)
    assert(add(over, t1).left.exists(_.isInstanceOf[Rejection.Full]), s"${add(over, t1)}")
    assertEquals(add(held(pool(ledger(2)), t1), t1), Left(Rejection.AlreadyHeld(id1)))
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
    val (synced, dropped) = s0.sync(Right(grown), 1000, env)
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
    val (synced, dropped) = s.sync(Right(shrunk), 1000, env)
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
        ._1,
      pool(l).withBase(Left(xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.InByron))
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
          PredicateFailureCbor
            .applyTxError(Vector(PredicateFailure.Mempool(full.problem)))
            .fold(error => fail(error.message), identity)
        )
      case other => fail(s"expected a full pool, received $other")
  }

  /** A pool whose block takes 64 KiB of transactions (a body of 66,560 bytes less the overhead of
    * 1,024) under an override of `bytes`.
    */
  private def sixtyFourKiB(l: ConwayPoolLedger, bytes: Long): MempoolState =
    pool(l.withParams(_.copy(maxBlockBodySize = 66560)), Some(bytes))

  // The reference takes the capacity in `Word32` and wraps (ouroboros-consensus 82ecba32
  // `Mempool/Capacity.hs:62-79`, `Ledger/SupportsMempool.hs:468-504`); Scarab does not (approved
  // deviation, https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849).
  test("the capacity does not wrap: 4,096 blocks of 1 MiB of reference scripts are 2^32 bytes") {
    val l = ledger(2)
    val units = l.params.maxBlockExUnits
    assertEquals(
      sixtyFourKiB(l, 268435456L).capacity,
      TxMeasure(268435456L, 4096 * units.mem, 4096 * units.steps, 4294967296L)
    )
    // the number of blocks is the exact ceiling at the top of the override's range, where the
    // reference's rounds down (65,535)
    assertEquals(sixtyFourKiB(l, 4294967295L).capacity.bytes, 65536L * 65536)
  }

  test("a reference-script spend the reference's wrapped capacity would hold back is held") {
    val l = withRefScript(ledger(2), 1000)
    // with the reference's wrap the pool's reference-script capacity would be 0
    val s = sixtyFourKiB(l, 268435456L)
    val (spend, spent) =
      tx(Vector(txIn(50)), Vector(alice.address() -> (98 * ada)), ada, Vector(bob))
    assertEquals(
      add(s, spend).map(_.entries.map(e => (e.id, e.refScriptBytes))),
      Right(Vector((spent, 1000L)))
    )
  }

  test("LocalTxMonitor saturates a capacity above a Word32 in GetSizes, and the reply encodes") {
    val snapshot = LocalMonitor.snapshot(sixtyFourKiB(ledger(2), 4294967295L))
    assertEquals(snapshot.capacity.bytes, 4294967296L)
    assertEquals(snapshot.sizes.capacityInBytes, 4294967295L)
    assert(
      xyz.kd5ujc.scarab.protocol.LocalTxMonitor
        .encode(
          xyz.kd5ujc.scarab.protocol.LocalTxMonitor.State
            .Busy(xyz.kd5ujc.scarab.protocol.LocalTxMonitor.Kind.GetSizes),
          xyz.kd5ujc.scarab.protocol.ChainSync.Role.Server,
          xyz.kd5ujc.scarab.protocol.LocalTxMonitor.Message.ReplyGetSizes(snapshot.sizes)
        )
        .isRight
    )
    assertEquals(
      xyz.kd5ujc.scarab.network.LocalTxMonitorServer
        .measures(snapshot.txs, snapshot.capacity)
        .byName("reference_scripts_bytes")
        .capacity,
      BigInt(65536L) << 20
    )
  }
