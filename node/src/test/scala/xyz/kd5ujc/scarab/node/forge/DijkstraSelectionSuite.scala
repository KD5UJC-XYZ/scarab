// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.bls.Certificate
import xyz.kd5ujc.scarab.ledger.{LeiosBlock, LeiosHeader, TestLedger}
import xyz.kd5ujc.scarab.ledger.era.{BothLevels, EraTx, Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxMeasures
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
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

  private def recorded(name: String): Bytes =
    Files
      .readAllLines(Path.of("fixtures", "leios", "testnet", "blocks-rc-2026w44.tsv"))
      .asScala
      .map(_.split('\t'))
      .collectFirst { case r if r(0) == name => hex(r(5)) }
      .getOrElse(fail(s"no block $name"))

  private lazy val block: LeiosBlock =
    LeiosBlock.decode(recorded("announcing-with-txs")).fold(e => fail(e.message), identity)

  /** The block's transactions, read by the era's block decoder. */
  private lazy val txs: Vector[Tx[TxLevel.Top, Dijkstra]] =
    DijkstraEraTxs.ofBlock(recorded("announcing-with-txs"))

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
    val raw = recorded("certifying")
    val certified = LeiosBlock.decode(raw).fold(e => fail(e.message), identity)
    val cert: Certificate = certified.leiosCertificate.map(_.certificate).getOrElse(fail("no cert"))
    BlockBuilder.Assembly.dijkstra(txs, Some(cert)) match
      case BlockBuilder.Assembly.InDijkstra(body, _) =>
        assertEquals(body.transactions, Vector.empty)
        assertEquals(body.leiosCertificate, Some(cert))
        // the reference's certifying block 64,850 is that body, byte for byte
        val header = LeiosHeader.signed(certified.header.signable, certified.header.kesSignature)
        assertEquals(LeiosBlock.encode(header, body.encode), raw)
        assertEquals(body.hash, certified.header.bodyHash)
      case other => fail(s"not a Dijkstra body: $other")
  }

  test("the measure: sizeTxF and the overhead, every redeemer's units, the given script bytes") {
    txs.zip(block.transactions).foreach { (tx, flat) =>
      val m = Capacity.dijkstraMeasure(tx, 7L)
      assertEquals(m.bytes, DijkstraTx.forBlockInclusion(tx).size.toLong - 1 + 4, tx.id.hex)
      assertEquals(
        ExUnits(m.exUnitsMemory, m.exUnitsSteps),
        EraTx.dijkstra.getTotalExUnits[TxLevel.Top](tx)(using BothLevels.Top)
      )
      assertEquals(m.refScriptsBytes, 7L)
      // the flat transaction's measure the forger took until #85: its size and its units
      assertEquals(m.bytes, flat.size + 4, tx.id.hex)
      assertEquals(ExUnits(m.exUnitsMemory, m.exUnitsSteps), DijkstraTxMeasures.totalExUnits(flat))
    }
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
