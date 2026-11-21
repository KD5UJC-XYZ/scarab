// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.bls.Certificate
import xyz.kd5ujc.scarab.ledger.{LeiosBlock, TestLedger, TxIn}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraRedeemer, DijkstraSubTx, DijkstraTx, DijkstraTxIn}
import xyz.kd5ujc.scarab.ledger.rules.DijkstraTxMeasures
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.Capacity
import xyz.kd5ujc.scarab.plutus.ExUnits

/** The partition of a Dijkstra ranking block ([[BlockBuilder.ranking]], `snapshotPartition` 's
  * first part, by `splitAfterTxSizeSpec`), its body (`mkBody`, [[BlockBuilder.Assembly.dijkstra]])
  * and the measure and capacities of Dijkstra's pool ([[Capacity]]), on the transactions of the
  * testnet's block 64,853 (`fixtures/leios/testnet/blocks-rc-2026w44.tsv`) and on measures and
  * parameters made for each case.
  */
class DijkstraSelectionSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private lazy val block: LeiosBlock =
    val raw = Files
      .readAllLines(Path.of("fixtures", "leios", "testnet", "blocks-rc-2026w44.tsv"))
      .asScala
      .map(_.split('\t'))
      .collectFirst { case r if r(0) == "announcing-with-txs" => hex(r(5)) }
      .getOrElse(fail("no block 64,853"))
    LeiosBlock.decode(raw).fold(e => fail(e.message), identity)

  private lazy val txs: Vector[DijkstraTx] = block.transactions

  private def held(measures: Vector[TxMeasure]): DijkstraSnapshot =
    DijkstraSnapshot(txs.zip(measures).map(DijkstraHeld(_, _)))

  private def bytes(n: Long) = TxMeasure(n, BigInt(0), BigInt(0), 0L)

  private val unbounded =
    TxMeasure(Long.MaxValue, BigInt(Long.MaxValue), BigInt(Long.MaxValue), Long.MaxValue)

  private lazy val measured: DijkstraSnapshot =
    DijkstraSnapshot(txs.map(tx => DijkstraHeld(tx, Capacity.dijkstraMeasure(tx, 0L))))

  test("the prefix ends at the exact capacity: a total equal to it fits, one byte less does not") {
    val sizes = measured.held.map(_.measure)
    (1 to txs.size).foreach { k =>
      val total = sizes.take(k).reduce(_ + _)
      assertEquals(BlockBuilder.ranking(measured, total).chosen.size, k, s"$k")
      assertEquals(BlockBuilder.ranking(measured, total).used, total, s"$k")
      val short = total.copy(bytes = total.bytes - 1)
      assertEquals(BlockBuilder.ranking(measured, short).chosen.size, k - 1, s"$k less a byte")
    }
    assertEquals(BlockBuilder.ranking(measured, TxMeasure.zero).chosen, Vector.empty)
  }

  test("the first transaction that does not fit ends the block, however small those after it") {
    // 100 bytes, then 1,000, then 1 each: a capacity of 150 takes the first only, not the ones of
    // one byte after the large one
    val snapshot = held(Vector(bytes(100), bytes(1000)) ++ Vector.fill(txs.size - 2)(bytes(1)))
    val ranking = BlockBuilder.ranking(snapshot, unbounded.copy(bytes = 150))
    assertEquals(ranking.transactions, txs.take(1))
    assertEquals(ranking.used, bytes(100))
  }

  test("every component of the measure bounds the block") {
    val one = TxMeasure(10, BigInt(10), BigInt(10), 10L)
    val snapshot = held(Vector.fill(txs.size)(one))
    val three = TxMeasure(30, BigInt(30), BigInt(30), 30L)
    assertEquals(BlockBuilder.ranking(snapshot, three).chosen.size, 3)
    Vector(
      three.copy(bytes = 29),
      three.copy(exUnitsMemory = BigInt(29)),
      three.copy(exUnitsSteps = BigInt(29)),
      three.copy(refScriptsBytes = 29L)
    ).foreach(limit => assertEquals(BlockBuilder.ranking(snapshot, limit).chosen.size, 2, limit))
  }

  test("an empty snapshot gives the minimal block") {
    val ranking = BlockBuilder.ranking(DijkstraSnapshot.empty, unbounded)
    assertEquals(
      BlockBuilder.Assembly.dijkstra(ranking.transactions, None),
      BlockBuilder.Assembly.minimalDijkstra
    )
  }

  test("a block that certifies carries the certificate and no transactions, as mkBody makes it") {
    val certified = LeiosBlock
      .decode(
        Files
          .readAllLines(Path.of("fixtures", "leios", "testnet", "blocks-rc-2026w44.tsv"))
          .asScala
          .map(_.split('\t'))
          .collectFirst { case r if r(0) == "certifying" => hex(r(5)) }
          .getOrElse(fail("no block 64,850"))
      )
      .fold(e => fail(e.message), identity)
    val cert: Certificate = certified.leiosCertificate.map(_.certificate).getOrElse(fail("no cert"))
    BlockBuilder.Assembly.dijkstra(txs, Some(cert)) match
      case BlockBuilder.Assembly.InDijkstra(body, _) =>
        assertEquals(body.transactions, Vector.empty)
        assertEquals(body.leiosCertificate, Some(cert))
        // the reference's certifying block 64,850 is that body, byte for byte
        assertEquals(body.encode, certified.body.encode)
        assertEquals(body.hash, certified.header.bodyHash)
      case other => fail(s"not a Dijkstra body: $other")
  }

  test("the measure: sizeTxF and the overhead, every redeemer's units, the given script bytes") {
    txs.foreach { tx =>
      val m = Capacity.dijkstraMeasure(tx, 7L)
      assertEquals(m.bytes, tx.forBlockInclusion.size.toLong - 1 + 4, tx.id.hex)
      assertEquals(ExUnits(m.exUnitsMemory, m.exUnitsSteps), DijkstraTxMeasures.totalExUnits(tx))
      assertEquals(m.refScriptsBytes, 7L)
    }
    // a sub-transaction's redeemers count towards the units (`getTotalExUnits`)
    val tx = txs.head
    val redeemer = DijkstraRedeemer(0, 0L, Bytes.empty, BigInt(5), BigInt(9))
    val sub = DijkstraSubTx(
      tx.body.raw,
      tx.body,
      tx.witnesses.copy(redeemers = Vector(redeemer)),
      None
    )
    val batch = tx.copy(body = tx.body.copy(subTransactions = Vector(sub)))
    val before = Capacity.dijkstraMeasure(tx, 0L)
    val after = Capacity.dijkstraMeasure(batch, 0L)
    assertEquals(after.exUnitsMemory - before.exUnitsMemory, BigInt(5))
    assertEquals(after.exUnitsSteps - before.exUnitsSteps, BigInt(9))
  }

  test("the measure's reference scripts are the top-level body's inputs and reference inputs") {
    val tx = txs.head
    val in = tx.body.inputs.head
    val ref = DijkstraTxIn(in.id, in.index + 1000)
    val other = DijkstraTxIn(in.id, in.index + 2000)
    val sizes = Map(
      TxIn(in.id, BigInt(in.index)) -> 100L,
      TxIn(ref.id, BigInt(ref.index)) -> 20L,
      TxIn(other.id, BigInt(other.index)) -> 3L
    )
    // an input that is also a reference input counts once
    val body = tx.body.copy(referenceInputs = Vector(ref, in))
    val sub = DijkstraSubTx(
      tx.body.raw,
      tx.body.copy(inputs = Vector(other), referenceInputs = Vector.empty),
      tx.witnesses,
      None
    )
    val batch = tx.copy(body = body.copy(subTransactions = Vector(sub)))
    assertEquals(Capacity.dijkstraRefScriptBytes(sizes.get, batch), 120L)
    // LEDGER's check counts the sub-transactions' too; the mempool's measure does not
    assertEquals(DijkstraTxMeasures.batchRefScriptsSize(sizes.get, batch), 123L)
  }

  test("an endorser block's references beyond the LeiosFetch limit stop the pool's capacity") {
    val params = TestLedger.params(12).copy(maxEndorserBlockTxsSize = BigInt(9))
    assert(
      Capacity
        .endorserBlockReservation(params.copy(maxEndorserBlockReferencesSize = 600000))
        .isRight
    )
    assertEquals(
      Capacity.ofDijkstra(params.copy(maxEndorserBlockReferencesSize = 600001), None),
      Left(Capacity.EndorserBlockReferencesOverWire(BigInt(600001), 600000))
    )
  }
