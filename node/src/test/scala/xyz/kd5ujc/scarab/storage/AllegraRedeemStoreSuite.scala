// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import io.circe.parser
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.ledger.{Block, ByronGenesisFile, Epoch, Header, NodeError, TxIn}
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronOut}
import xyz.kd5ujc.scarab.node.{NodeRules, NodeSetup}
import xyz.kd5ujc.scarab.protocol.Era
import scala.jdk.CollectionConverters.*

/** Mainnet sync from genesis, stop 5: the boundary that enters Allegra returns the outputs to Byron
  * redeem (AVVM) addresses to the reserves (`returnRedeemAddrsToReserves`). On a node whose UTxO
  * set is in a store, those outputs are stored and trimmed from the ledger state's diff long before
  * the boundary: the node fetches them into the read set for the block that enters Allegra, and the
  * deletions, stamped with that block, reach the store.
  *
  * The chain is the era-walking capture of `fixtures/eras` (Allegra at epoch 2), whose Byron UTxO
  * is given the mainnet genesis AVVM balance of `fixtures/mainnet/byron-genesis-trimmed.json`; each
  * block's state is made immutable at once, so the store holds every entry.
  */
class AllegraRedeemStoreSuite extends CatsEffectSuite:
  private val setup: NodeSetup =
    NodeSetup
      .fromDirectory(JPath.of("fixtures/eras/genesis"))
      .fold(e => fail(e.message), identity)

  private val raws: Vector[Bytes] =
    JFiles
      .readAllLines(JPath.of("fixtures/eras/blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))
  private val headers: Vector[Header] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), _.header))
  private val firstShelley = headers.indexWhere(_.isInstanceOf[Header.Shelley])
  private val firstAllegra = headers.indexWhere(_.era == Era.Allegra)

  /** The mainnet genesis AVVM balance, at its redeem address. */
  private val avvm: (TxIn, ByronOut) =
    val json = parser
      .parse(JFiles.readString(JPath.of("fixtures/mainnet/byron-genesis-trimmed.json")))
      .fold(e => fail(e.getMessage), identity)
    val (redeem, nonAvvm) =
      ByronGenesisFile.genesisBalances(json).fold(e => fail(e.message), identity)
    ByronLedger
      .genesisUtxo(redeem, nonAvvm, None)
      .fold(e => fail(e), identity)
      .headOption
      .getOrElse(fail("no AVVM balance"))

  private val key = avvm._1

  private val start: NodeState =
    val s = setup.start
    s.withByron(s.byron.copy(ledger = s.byron.ledger.map(l => l.copy(utxo = l.utxo + avvm))))

  /** What a run shows: the state before and after the block that enters Allegra, what the store
    * held of the redeem output before that block, and the store after it.
    */
  private final case class Run(
      shelleyEnd: NodeState,
      allegra: NodeState,
      storedBefore: Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut],
      store: MemoryUtxoStore[IO]
  )

  private val run: IO[Run] =
    Handle
      .allow[NodeError] {
        for
          store <- MemoryUtxoStore[IO]
          backing = new UtxoBacking[IO, NodeState](store, fs2.io.file.Path("unused"))
          rules = NodeRules[IO](
            setup.praos,
            setup.globals,
            setup.byronRules,
            utxoStore = Some(store)
          )
          byronEnd = headers.indices.take(firstShelley).foldLeft(start) { (s, i) =>
            NodeRules.applyBlock(
              s,
              headers(i),
              raws(i),
              setup.praos,
              setup.globals,
              setup.byronRules
            ) match
              case Right(next) => next
              case Left(f)     => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
          }
          // each block's state becomes immutable at once: the store holds every entry and is at
          // the block, as on a chain where every block changes the set (mainnet)
          immutable = (s: NodeState, i: Int) =>
            for
              next <- rules.apply(s, headers(i), raws(i))
              _ <- backing.advance(next)
              _ <- store.write(Vector.empty, next.ledger.utxo.stamp)
              settled <- backing.settled(next)
            yield settled
          shelleyEnd <- (firstShelley until firstAllegra).toVector.foldLeftM(byronEnd)(immutable)
          storedBefore <- store.read(Vector(key))
          allegra <- immutable(shelleyEnd, firstAllegra)
        yield Run(shelleyEnd, allegra, storedBefore, store)
      }
      .rescue(e => IO.raiseError(new AssertionError(e.message)))

  test("the fixture enters Allegra at epoch 2 and its AVVM output is to a redeem address") {
    assert(firstShelley > 0 && firstAllegra > firstShelley)
    val genesis = start.byron.ledger.getOrElse(fail("no Byron ledger"))
    assertEquals(genesis.utxo.get(key), Some(avvm._2))
    run.map { r =>
      assertEquals(r.shelleyEnd.ledger.params.protocolMajor, BigInt(2))
      assertEquals(r.allegra.ledger.params.protocolMajor, BigInt(3))
      assertEquals(r.allegra.ledger.epoch, BigInt(2))
      assert(r.storedBefore.get(key).exists(Epoch.isRedeemer))
    }
  }

  test("before the block that enters Allegra the redeem output is in the store, not the diff") {
    run.map { r =>
      assertEquals(r.storedBefore.get(key).map(_.value.coin), Some(avvm._2.value))
      assert(r.shelleyEnd.ledger.utxo.matching(Epoch.isRedeemer).isEmpty)
    }
  }

  test("entering Allegra deletes it from the store and returns its lovelace to the reserves") {
    run.flatMap { r =>
      val allegra = r.allegra.ledger
      for
        found <- r.store.read(Vector(key))
        whole <- UtxoStore.materialize(r.store, allegra.utxo)
      yield
        assertEquals(found, Map.empty)
        // the store and the state's running totals agree: the deletion was written
        assertEquals(whole.size.toLong, allegra.utxo.count)
        assertEquals(whole.valuesIterator.map(_.value.coin).sum, allegra.utxo.coin)
        assertEquals(allegra.total, setup.globals.maxSupply)
    }
  }
