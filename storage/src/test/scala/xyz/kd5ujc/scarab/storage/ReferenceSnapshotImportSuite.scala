// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Handle
import fs2.io.file.Path
import java.nio.file.Files
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.storage.haskell.state.*

/** Original state/tables/meta written by cardano-node 11.1.3. These are consumed unchanged; the
  * separate command integration suite supplies matching captured immutable blocks.
  */
class ReferenceSnapshotImportSuite extends CatsEffectSuite:
  Vector("shelley-1000", "allegra-1800", "mary-2308", "alonzo-3013", "babbage-4000", "conway-4593")
    .foreach { fixture =>
      test(s"original reference $fixture tables reconstruct the state's exact instant stake") {
        val source = Path("fixtures/haskell-state") / fixture
        val state = Bytes.wrap(Files.readAllBytes((source / "state").toNioPath))
        val file = HaskellLedgerFile.decode(state).fold(e => fail(e.render), identity)
        val ext = file.ext.getOrElse(fail("expected Shelley-based reference state"))
        ext.ledgerState.state match
          case EraLedgerState.ByronCurrent(_) => fail("unexpected Byron state")
          case i: EraLedgerState.InShelleyEra[e] =>
            given ShelleyBasedEra[e] = i.era
            val original = i.state.nes.es.ls.utxo
            StorageTestKit.tempDir.use { dir =>
              StorageTestKit.ok {
                RocksUtxoStore.resource[IO](dir / "utxo").use { store =>
                  Handle
                    .allow[LedgerSnapshotImport.Error] {
                      Handle
                        .allow[SnapshotTables.Error] {
                          for
                            loaded <- LedgerSnapshotImport.loadTables[IO, e](source, store, 123)
                            (utxo, summary) = loaded
                            count <- store.size
                            stamp <- store.flushed
                          yield
                            assertEquals(
                              LedgerSnapshotImport.checkStake(i.era, original.instantStake, utxo),
                              Right(())
                            )
                            val meta = SnapshotMeta
                              .parse(Files.readString((source / "meta").toNioPath))
                              .fold(e => fail(e.toString), identity)
                            assertEquals(
                              SnapshotMeta.verify(meta, SnapshotCrc.of(state), summary.crc),
                              Right(())
                            )
                            assertEquals(count, utxo.count)
                            assertEquals(stamp, 123L)
                            assert(utxo.diff.isEmpty && utxo.resident.isEmpty)
                        }
                        .rescue(e => IO.raiseError(new AssertionError(e.toString)))
                    }
                    .rescue(e => IO.raiseError(new AssertionError(e.toString)))
                }
              }
            }
      }
    }

  test("parameter completion retains encoded zeroes and only fills fields absent from the era") {
    val genesis =
      Params.empty.copy(minFeeA = 77, drepDeposit = 42, costModels = Map(0 -> Vector(BigInt(1))))
    val read = Params.empty.copy(minFeeA = 0)
    val completed = Params.complete(ShelleyBasedEra.SShelley, read, genesis)
    assertEquals(completed.minFeeA, BigInt(0))
    assertEquals(completed.drepDeposit, BigInt(42))
    assertEquals(completed.costModels, genesis.costModels)
  }

  test("empty tables retain the same flushed stamp in the handle and checkpoint store") {
    StorageTestKit.tempDir.use { dir =>
      StorageTestKit.ok {
        RocksUtxoStore.resource[IO](dir / "store").use { store =>
          Handle
            .allow[LedgerSnapshotImport.Error] {
              Handle
                .allow[SnapshotTables.Error] {
                  for
                    _ <- IO.blocking(
                      Files.write((dir / "tables").toNioPath, Array(0x81.toByte, 0xa0.toByte))
                    )
                    loaded <- LedgerSnapshotImport
                      .loadTables[IO, xyz.kd5ujc.scarab.ledger.state.Conway](dir, store, 123)
                    stamp <- store.flushed
                  yield
                    assertEquals(loaded._2.entries, 0L)
                    assertEquals(loaded._1.count, 0L)
                    assertEquals(loaded._1.stamp, 123L)
                    assertEquals(loaded._1.flushed, stamp)
                    assertEquals(stamp, 123L)
                }
                .rescue(e => IO.raiseError(new AssertionError(e.toString)))
            }
            .rescue(e => IO.raiseError(new AssertionError(e.toString)))
        }
      }
    }
  }
