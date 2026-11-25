// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import cats.effect.IO
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{Block, ByronGenesisFile, Header, NodeError, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.rules.EraNes
import xyz.kd5ujc.scarab.ledger.state.{ShelleyBasedEra, UtxoTables}
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronOut}
import xyz.kd5ujc.scarab.node.{MirrorRules, MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout
import scala.jdk.CollectionConverters.*

/** The entry into Allegra on the era-indexed rules, over a store: the outputs to Byron redeem
  * (AVVM) addresses stashed by the hand-over are removed from the set and returned to the reserves
  * by the translation, and the deletions, which the store holds the outputs of while the state's
  * diff does not, reach the store.
  *
  * The chain is the era-walking capture of `fixtures/eras` (Allegra at epoch 2) with the mainnet
  * genesis AVVM balance in its Byron UTxO, run by [[MirrorRules]] with the store; each block's
  * state is made immutable at once, so the store holds every entry. The last Shelley state is also
  * written to a snapshot and read back, as a node that restarts in Shelley restores it, and the
  * block that enters Allegra applied to that (#1448).
  */
class AllegraRedeemMirrorSuite extends CatsEffectSuite:
  private val setup: NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(JPath.of("fixtures/eras/genesis"))
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
    val json = ByronGenesisFile
      .read(
        Bytes.fromArray(
          JFiles.readAllBytes(JPath.of("fixtures/mainnet/byron-genesis-trimmed.json"))
        )
      )
      .fold(e => fail(e.message), _._1)
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
      store: MemoryUtxoStore[IO],
      restored: MirrorState,
      restoredAllegra: MirrorState
  )

  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private val format: SnapshotFormat[IO, MirrorState] =
    SnapshotV10.format[IO](SnapshotCheck.Full, ReferenceLayout.Release11_1)

  /** `s` written to a snapshot and read back, as a restart restores it. */
  private def restart(s: MirrorState, last: Header): IO[MirrorState] =
    val snapshot = StateSnapshot(
      Some(BlockRef(last.slot, last.hash, last.blockNo, last.isBoundary)),
      s,
      CheckpointBinding.Unbacked
    )
    format
      .encode(snapshot)
      .flatMap(r => IO(r.fold(e => fail(s"refused: $e"), identity)))
      .flatMap(format.decode)
      .map(_.fold(e => fail(e.message), _.state))

  /** The redeem outputs the Shelley state of `s` stashes for the translation into Allegra. */
  private def stash(s: MirrorState): Map[TxIn, TxOut] = MirrorStep.nesOf(s) match
    case Some(EraNes.InEra(ShelleyBasedEra.SShelley, nes)) => (nes.avvm: UtxoTables).utxo.toMap
    case other                                             => fail(s"not a Shelley state: $other")

  private def reserves(s: MirrorState): BigInt = MirrorStep.nesOf(s) match
    case Some(EraNes.InEra(_, nes)) => nes.es.chainAccount.reserves.value
    case None                       => fail("a Byron state has no reserves")

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
            xyz.kd5ujc.scarab.plutus.PlutusBackend.default,
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
          restored <- restart(shelleyEnd, headers(firstAllegra - 1))
          // applied, not made immutable: the store stays at the last Shelley block for the run
          restoredAllegra <- rules.apply(restored, headers(firstAllegra), raws(firstAllegra))
          allegra <- immutable(shelleyEnd, firstAllegra)
        yield Run(shelleyEnd, allegra, storedBefore, store, restored, restoredAllegra)
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
      StorageTestKit.ok {
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
  }

  test("a Shelley state read back from its snapshot keeps the stashed redeem output (#1448)") {
    run.map { r =>
      assertEquals(stash(r.shelleyEnd).get(key).map(_.value.coin), Some(avvm._2.value))
      assertEquals(stash(r.restored).keySet, stash(r.shelleyEnd).keySet)
      assertEquals(
        stash(r.restored).get(key).map(o => (o.address.raw, o.value)),
        stash(r.shelleyEnd).get(key).map(o => (o.address.raw, o.value))
      )
    }
  }

  test(
    "entering Allegra after a restart in Shelley returns the redeem lovelace to the reserves (#1448)"
  ) {
    run.map { r =>
      assertEquals(r.restoredAllegra.era, Era.Allegra)
      // the run without the restart returns it (the test above): the same reserves
      assertEquals(reserves(r.restoredAllegra), reserves(r.allegra))
      // the output is in the store, not in the diff: its deletion must be in the diff to reach it
      assertEquals(
        MirrorStep.utxoOf(r.restoredAllegra).flatMap(_.diff.get(key)).map(_.out),
        Some(None)
      )
    }
  }
