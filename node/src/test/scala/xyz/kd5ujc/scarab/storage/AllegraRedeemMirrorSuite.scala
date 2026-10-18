// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import io.circe.parser
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{Block, ByronGenesisFile, Header, NodeError, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronOut}
import xyz.kd5ujc.scarab.node.{MirrorRules, MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era
import scala.jdk.CollectionConverters.*

/** The entry into Allegra on the era-indexed rules, over a store: the outputs to Byron redeem
  * (AVVM) addresses stashed by the hand-over are removed from the set and returned to the reserves
  * by the translation, and the deletions, which the store holds the outputs of while the state's
  * diff does not, reach the store.
  *
  * The chain is the era-walking capture of `fixtures/eras` (Allegra at epoch 2) with the mainnet
  * genesis AVVM balance in its Byron UTxO, run by [[MirrorRules]] with the store; each block's
  * state is made immutable at once, so the store holds every entry.
  */
class AllegraRedeemMirrorSuite extends CatsEffectSuite:
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
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))
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
      .fold(e => fail(e.message), identity)
      .headOption
      .getOrElse(fail("no AVVM balance"))

  private val key = avvm._1

  /** The state the chain starts from, as the mirror, with the AVVM balance in the Byron UTxO. */
  private val start: MirrorState =
    MirrorSteps.withConsensus(setup.mirrorStart)(c =>
      c.copy(byron = c.byron.copy(ledger = c.byron.ledger.map(l => l.copy(utxo = l.utxo + avvm))))
    )

  private final case class Run(
      shelleyEnd: MirrorState,
      allegra: MirrorState,
      storedBefore: Map[TxIn, TxOut],
      store: MemoryUtxoStore[IO]
  )

  private val run: IO[Run] =
    Handle
      .allow[NodeError] {
        for
          store <- MemoryUtxoStore[IO]
          backing = new UtxoBacking[IO, MirrorState](store, fs2.io.file.Path("unused"))
          rules = MirrorRules[IO](
            setup.praos,
            setup.globals,
            setup.byronRules,
            setup.handOver,
            setup.byronHistory,
            utxoStore = Some(store)
          )
          // each block's state becomes immutable at once: the store holds every entry and is at
          // the block, as on a chain where every block changes the set (mainnet)
          immutable = (s: MirrorState, i: Int) =>
            for
              next <- rules.apply(s, headers(i), raws(i))
              _ <- backing.advance(next)
              _ <- MirrorStep.utxoOf(next).traverse_(u => store.write(Vector.empty, u.stamp))
              settled <- backing.settled(next)
            yield settled
          shelleyEnd <- (0 until firstAllegra).toVector.foldLeftM(start)(immutable)
          storedBefore <- store.read(Vector(key))
          allegra <- immutable(shelleyEnd, firstAllegra)
        yield Run(shelleyEnd, allegra, storedBefore, store)
      }
      .rescue(e => IO.raiseError(new AssertionError(e.message)))

  test("the redeem output is in the store, and in no diff, before the block that enters Allegra") {
    assert(firstShelley > 0 && firstAllegra > firstShelley)
    run.map { r =>
      assertEquals(r.shelleyEnd.era, Era.Shelley)
      assertEquals(r.allegra.era, Era.Allegra)
      assertEquals(r.storedBefore.get(key).map(_.value.coin), Some(avvm._2.value))
      assert(r.shelleyEnd.utxo.matching(_ => true).get(key).isEmpty)
    }
  }

  test("entering Allegra deletes it from the store and returns its lovelace to the reserves") {
    run.flatMap { r =>
      val utxo = r.allegra.utxo
      for
        found <- r.store.read(Vector(key))
        whole <- UtxoStore.materialize(r.store, utxo)
      yield
        assertEquals(found, Map.empty)
        // the store and the state's running totals agree: the deletion was written
        assertEquals(whole.size.toLong, utxo.count)
        assertEquals(whole.valuesIterator.map(_.value.coin).sum, utxo.coin)
    }
  }
