// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.IO
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*

import TxKit.*

/** The mempool as LocalTxMonitor reports it, against the rules the reference's server applies. */
class LocalMonitorSuite extends CatsEffectSuite with ScalaCheckSuite:
  private def pool(capacity: Long = 100000) = MempoolState.empty(Some(ledger(4)), 1000, capacity)

  private def held(raws: Bytes*): MempoolState =
    raws.foldLeft(pool())((s, raw) => s.add(raw, env).fold(r => fail(r.message), _._1))

  test("the snapshot is validated for the slot after the tip, in arrival order") {
    val (t1, id1) = pay(1)
    val (t2, id2) = pay(2)
    val snap = LocalMonitor.snapshot(held(t1, t2))
    assertEquals(snap.slot, BigInt(1001))
    assertEquals(
      snap.txs.map(_.tx),
      Vector(LocalMonitor.wrapTx(t1), LocalMonitor.wrapTx(t2))
    )
    assert(snap.has(LocalMonitor.wrapId(id1)))
    assert(snap.has(LocalMonitor.wrapId(id2)))
    assert(!snap.has(LocalMonitor.wrapId(Bytes(Vector.fill(32)(7.toByte)))))
    // an id of another era is never held
    assert(!snap.has(Bytes(Vector(0x82, 0x05, 0x41, 0x01).map(_.toByte))))
    assert(!snap.has(Bytes(Vector(0x01))))
  }

  test("the wire forms: [6, #6.24(tx)] and [6, h'id']") {
    assertEquals(LocalMonitor.wrapTx(Bytes(Vector(1, 2, 3))).hex, "8206d81843010203")
    assertEquals(
      LocalMonitor.wrapId(Bytes(Vector.fill(32)(0x11.toByte))).hex,
      "82065820" + "11" * 32
    )
    assertEquals(
      LocalMonitor.unwrapId(LocalMonitor.wrapId(Bytes(Vector.fill(32)(0x11.toByte)))).map(_.hex),
      Some("11" * 32)
    )
  }

  test(
    "a transaction counts for its ledger size plus 4 bytes, and the sizes are those of the snapshot"
  ) {
    val (t1, _) = pay(1)
    val (t2, _) = pay(2)
    val snap = LocalMonitor.snapshot(held(t1, t2))
    // the ledger's `sizeTxF` leaves out the one byte of the is_valid flag (devnet: 200 of 201 bytes)
    val (s1, s2) = (t1.size - 1L, t2.size - 1L)
    assertEquals(snap.txs.map(_.measure.bytes), Vector(s1 + 4, s2 + 4))
    assertEquals(snap.sizes.sizeInBytes, s1 + s2 + 8L)
    assertEquals(snap.sizes.numberOfTxs, 2L)
    assertEquals(snap.txs.map(_.measure.exUnitsMemory), Vector(BigInt(0), BigInt(0)))
  }

  test(
    "the capacity is a whole number of block units: 2 by default, ceil(bytes / unit) otherwise"
  ) {
    val params = ledger(1).params
    val unit = params.maxBlockBodySize.toLong - 1024
    assertEquals(unit, 64512L)
    val one = LocalMonitor.blockUnit(params)
    assertEquals(one.refScriptsBytes, 1048576L)
    assertEquals(LocalMonitor.capacity(params, 2 * unit).bytes, 2 * unit)
    assertEquals(LocalMonitor.capacity(params, 2 * unit - 1).bytes, 2 * unit)
    assertEquals(LocalMonitor.capacity(params, 2 * unit + 1).bytes, 3 * unit)
    assertEquals(LocalMonitor.capacity(params, 1).bytes, unit)
    assertEquals(LocalMonitor.capacity(params, 0).bytes, unit)
    assertEquals(LocalMonitor.capacity(params, 2 * unit).refScriptsBytes, 2L * 1048576)
    assertEquals(LocalMonitor.snapshot(pool(100000)).sizes.capacityInBytes, 2 * unit)
  }

  property("capacity is monotone in the byte capacity and never below one unit") {
    forAll(Gen.choose(0L, 5000000L), Gen.choose(0L, 5000000L)) { (a, b) =>
      val params = ledger(1).params
      val (lo, hi) = (a.min(b), a.max(b))
      val cl = LocalMonitor.capacity(params, lo)
      val ch = LocalMonitor.capacity(params, hi)
      cl.bytes <= ch.bytes && cl.bytes >= LocalMonitor.blockUnit(params).bytes &&
      ch.bytes >= hi
    }
  }

  test("GetMeasures counts the transactions NextTx has not handed out, GetSizes all of them") {
    val (t1, _) = pay(1)
    val (t2, _) = pay(2)
    val snap = LocalMonitor.snapshot(held(t1, t2))
    val all = LocalTxMonitorServer.measures(snap.txs, snap.capacity)
    assertEquals(all.txCount, 2L)
    assertEquals(all.byName.keys.toList, LocalTxMonitorServer.MeasureNames)
    assertEquals(all.byName("transaction_bytes").size, BigInt(t1.size - 1 + t2.size - 1 + 8))
    val rest = LocalTxMonitorServer.measures(snap.txs.drop(1), snap.capacity)
    assertEquals(rest.txCount, 1L)
    assertEquals(rest.byName("transaction_bytes").size, BigInt(t2.size - 1 + 4))
    assertEquals(rest.byName("transaction_bytes").capacity, BigInt(snap.capacity.bytes))
  }

  test("a client awaiting a new snapshot is woken by a transaction and by a new tip") {
    val (t1, id1) = pay(1)
    for
      mempool <- Mempool.create[IO](Some(ledger(4)), 1000, env, 100000, MempoolEvents.none[IO])
      source = LocalMonitor.source(mempool)
      first <- source.acquire
      waiting <- source.awaitChange(first).start
      _ <- IO.sleep(30.millis)
      _ <- mempool.add(t1)
      second <- waiting.joinWithNever
      again <- source.awaitChange(second).start
      _ <- IO.sleep(30.millis)
      _ <- mempool.sync(Right(ledger(4)), 1001)
      third <- again.joinWithNever
    yield
      assertEquals(first.txs.size, 0)
      assertEquals(second.txs.size, 1)
      assert(second.has(LocalMonitor.wrapId(id1)))
      assertEquals(second.slot, BigInt(1001))
      // the transaction still applies at the new tip, only the slot moved
      assertEquals(third.slot, BigInt(1002))
      assertEquals(third.txs.size, 1)
  }

  test("awaiting a snapshot the pool already differs from returns at once") {
    val (t1, _) = pay(1)
    for
      mempool <- Mempool.create[IO](Some(ledger(4)), 1000, env, 100000, MempoolEvents.none[IO])
      source = LocalMonitor.source(mempool)
      stale <- source.acquire
      _ <- mempool.add(t1)
      now <- source.awaitChange(stale).timeout(2.seconds)
    yield assertEquals(now.txs.size, 1)
  }
