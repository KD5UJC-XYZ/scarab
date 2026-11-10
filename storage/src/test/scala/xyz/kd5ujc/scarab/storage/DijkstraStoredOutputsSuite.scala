// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Handle
import fs2.io.file.Path
import io.circe.{Json, parser}
import java.nio.file.Files
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  DecoderVersion,
  StorageError,
  TxIn,
  TxOut,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraFixture
import xyz.kd5ujc.scarab.ledger.rules.{RewardPulserFixture, Translate}
import xyz.kd5ujc.scarab.ledger.state.{Conway, NewEpochState, UtxoTables}
import xyz.kd5ujc.scarab.protocol.Era

/** Outputs that the backing store holds when the chain enters Dijkstra (#815): the translation
  * ([[Translate.upgradeUtxo]]) does not see them, and [[UtxoStore.enteredDijkstra]] upgrades them
  * after it, as the reference's default backend upgrades every stored output on the first block of
  * the era (ouroboros-consensus 2416493b `upgradeTables`,
  * `HardFork/Combinator/Ledger.hs:1250-1287`), and written back when the crossing block's changes
  * are flushed. The outputs and what the reference makes of them are the cases of
  * `fixtures/reference/dijkstra-translation`, in the memory and the RocksDB store.
  */
class DijkstraStoredOutputsSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val rows: Vector[Json] =
    Files
      .readAllLines(java.nio.file.Path.of("fixtures/reference/dijkstra-translation/cases.jsonl"))
      .toArray(Array.empty[String])
      .toVector
      .filter(_.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity))

  private def string(j: Json, path: String*): Option[String] =
    path.foldLeft(j.hcursor: io.circe.ACursor)(_.downField(_)).as[String].toOption

  private def required(j: Json, path: String*): String =
    string(j, path*).getOrElse(fail(s"${path.mkString(".")} missing"))

  /** A Conway output read as the stores read one (`TxOut.fromBytes`), at Conway's version. */
  private def conwayOutput(raw: Bytes): TxOut =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Conway))
    TxOut.fromBytes(raw, Address.Mode.of(Era.Conway)).fold(e => fail(e.toString), identity)

  /** One case: its key, the Conway output as stored, and the reference's Dijkstra script (bytes and
    * hash) and output encoding when it holds a reference script.
    */
  private final case class Case(
      name: String,
      in: TxIn,
      conway: TxOut,
      dijkstra: Option[(Bytes, Bytes, Bytes)]
  )

  private def key(n: Int): TxIn = TxIn(RewardPulserFixture.bytes(n, 32), 0)

  private val cases: Vector[Case] = rows.zipWithIndex.map { (row, i) =>
    Case(
      required(row, "name"),
      key(i),
      conwayOutput(hex(required(row, "txOut"))),
      string(row, "reference", "dijkstraScript", "bytes").map(b =>
        (
          hex(b),
          hex(required(row, "reference", "dijkstraScript", "hash")),
          hex(required(row, "reference", "dijkstraTxOut"))
        )
      )
    )
  }

  private val withGenesis = RewardPulserFixture.globals.copy(ledger =
    RewardPulserFixture.globals.ledger.copy(dijkstra = Some(DijkstraFixture.genesis))
  )

  /** A stored output with a native reference script that the crossing block's state has spent. */
  private val spent: (TxIn, TxOut) =
    key(1000) -> cases
      .find(_.name == "refScript/allOf/indefinite-short")
      .map(_.conway)
      .getOrElse(fail("no case"))

  /** An output a Dijkstra transaction of the crossing block creates. */
  private val created: (TxIn, TxOut) = key(2000) -> spent._2

  /** The Conway state before the tick: everything is in the store (flushed at stamp 0), and its
    * diff, at the crossing block's stamp 1, holds only the deletion of [[spent]].
    */
  private def conwayState(utxo: Utxo): NewEpochState[Conway] =
    val c = RewardPulserFixture.conway(5, 4, 1)
    c.copy(es =
      c.es.copy(ls =
        c.es.ls.copy[Conway](utxo = c.es.ls.utxo.copy[Conway](utxo = UtxoTables(utxo)))
      )
    )

  private def translate(utxo: Utxo): Utxo =
    Translate
      .conwayToDijkstra(withGenesis, conwayState(utxo))
      .fold(e => fail(e.toString), _.es.ls.utxo.utxo.utxo)

  private final case class Observed(
      translated: Utxo,
      crossed: Utxo,
      beforeFlush: Map[TxIn, TxOut],
      volatile: Utxo,
      afterFlush: Map[TxIn, TxOut],
      throughState: Map[TxIn, TxOut]
  )

  private def cross(store: UtxoStore[IO])(using Handle[IO, StorageError]): IO[Observed] =
    val keys = cases.map(_.in) :+ spent._1
    val diff = Utxo.empty.copy(flushed = 0).at(1).removeKnown(Map(spent))
    // the crossing block's own transaction creates an output with a native reference script, which
    // keeps its bytes in Dijkstra
    val translated = translate(diff) ++ Map(created)
    for
      _ <- store.write(cases.map(c => c.in -> Some(c.conway)) :+ (spent._1 -> Some(spent._2)), 0)
      crossed <- UtxoStore.enteredDijkstra(store, translated)
      beforeFlush <- store.read(keys)
      volatile <- UtxoStore.complete(Some(store), crossed.settled(0, force = true), keys)
      _ <- UtxoStore.advance(store, crossed)
      afterFlush <- store.read(keys)
      flushed <- store.flushed
      settled = crossed.settled(flushed, force = true)
      completed <- UtxoStore.complete(Some(store), settled, keys)
    yield Observed(
      translated,
      crossed,
      beforeFlush,
      volatile,
      afterFlush,
      keys.flatMap(k => completed.get(k).map(k -> _)).toMap
    )

  private def orFail[A](program: Handle[IO, StorageError] ?=> IO[A]): A =
    Handle
      .allow[StorageError](program)
      .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      .unsafeRunSync()

  private def memory: Observed = orFail(MemoryUtxoStore[IO].flatMap(cross))

  private def rocks: Observed = orFail {
    val dir = Files.createTempDirectory("dijkstra-stored-outputs")
    RocksUtxoStore
      .resource[IO](Path.fromNioPath(dir) / "live", 8L * 1024 * 1024)
      .use(cross)
      .guarantee(IO.blocking(RocksUtxoStore.deleteTree(dir)))
  }

  test("the fixture holds the reference's cases, most with a native reference script") {
    assertEquals(cases.size, 19)
    assertEquals(cases.count(c => Translate.reencodedInDijkstra(c.conway)), 17)
    rows
      .zip(cases)
      .foreach((row, c) =>
        assertEquals(
          c.conway.scriptRef.map(_.hash),
          string(row, "reference", "conwayScript", "hash").map(hex),
          c.name
        )
      )
  }

  for (label, run) <- Vector("memory" -> (() => memory), "rocksdb" -> (() => rocks)) do
    lazy val observed = run()

    test(s"$label: the translation alone does not see the stored outputs") {
      assertEquals(observed.translated.diff.keySet, Set(spent._1, created._1))
    }

    test(s"$label: the stored native reference scripts, and only those, become changes") {
      val wanted = cases.filter(c => Translate.reencodedInDijkstra(c.conway)).map(_.in).toSet
      assertEquals(observed.crossed.diff.keySet, wanted + spent._1 + created._1)
      assert(observed.crossed.diff.valuesIterator.forall(_.stamp == 1L))
    }

    test(s"$label: an output the state decides keeps its bytes") {
      assertEquals(observed.crossed.get(created._1), Some(created._2))
    }

    test(s"$label: the store keeps the Conway outputs until the crossing block is flushed") {
      cases.foreach(c => assertEquals(observed.beforeFlush.get(c.in), Some(c.conway), c.name))
    }

    test(s"$label: before the flush the state's changes read as the upgraded outputs") {
      cases.foreach(c =>
        assertEquals(observed.volatile.get(c.in), Some(Translate.upgradeTxOut(c.conway)), c.name)
      )
      assertEquals(observed.volatile.get(spent._1), None)
    }

    for c <- cases do
      test(s"$label: ${c.name}: read after the crossing as the reference upgrades it") {
        val stored = observed.afterFlush.get(c.in)
        assertEquals(stored, Some(Translate.upgradeTxOut(c.conway)))
        assertEquals(observed.throughState.get(c.in), stored)
        c.dijkstra match
          case Some((bytes, hash, txOut)) =>
            assertEquals(stored.flatMap(_.scriptRef).map(_.hash), Some(hash))
            assertEquals(stored.map(_.referenceScriptSize), Some(bytes.size.toLong))
            if c.conway.scriptRef.exists(_.native.isDefined) then
              assertEquals(stored.map(_.raw), Some(txOut))
          case None => assertEquals(stored, Some(c.conway))
      }

    test(s"$label: a stored output the crossing block spent stays spent") {
      assertEquals(observed.crossed.get(spent._1), None)
      assertEquals(observed.afterFlush.get(spent._1), None)
      assertEquals(observed.throughState.get(spent._1), None)
    }

    test(s"$label: the upgrades leave the totals unchanged") {
      val (b, a) = (observed.translated, observed.crossed)
      assertEquals((a.coin, a.count, a.stake, a.ptrStake), (b.coin, b.count, b.stake, b.ptrStake))
    }
