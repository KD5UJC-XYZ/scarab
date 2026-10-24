// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath, StandardCopyOption}
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorExtras}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.AncillaryManifest

/** Bounded, offline acceptance using the signed pinned preprod ancillary and recorded immutable
  * chunks. The optional output directory preserves the imported artifacts for export/replay.
  */
class PreprodSnapshotImportSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 10.minutes

  test(
    "the authenticated original preprod snapshot imports all 4,526,058 outputs and restores V5"
  ) {
    val fixture = sys.env.get("SCARAB_IMPORT_FIXTURE")
    assume(
      fixture.nonEmpty,
      "SCARAB_IMPORT_FIXTURE names the verified ancillary + recorded-db cache"
    )
    fixture.traverse_ { name =>
      val source = Path(name)
      val setup = NodeSetup
        .fromDirectory(JPath.of("fixtures/preprod/genesis"))
        .fold(e => fail(e.message), identity)
      given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
      val params = setup.handOver.fold(fail("preprod starts in Byron"))(_.protocolParams)
      val boot = setup.mirrorStart.extras match
        case MirrorExtras.InByron(_, b, _) => b
        case MirrorExtras.InShelley(_, c)  => c.boot
      val key = AncillaryManifest
        .verificationKey(JFiles.readString(JPath.of("fixtures/mempack/ancillary/preprod.vkey")))
        .fold(e => fail(e.toString), identity)
      StorageTestKit.tempDir.use { temp =>
        val db = sys.env.get("SCARAB_IMPORT_DB").fold(temp / "db")(Path(_))
        for
          _ <- IO.blocking {
            JFiles.createDirectories(db.toNioPath)
            val recorded = (source / "recorded-db").toNioPath
            val walk = JFiles.walk(recorded)
            try
              walk.iterator().asScala.foreach { file =>
                val to = db.toNioPath.resolve(recorded.relativize(file))
                if JFiles.isDirectory(file) then JFiles.createDirectories(to)
                else JFiles.copy(file, to, StandardCopyOption.COPY_ATTRIBUTES)
              }
            finally walk.close()
          }
          began <- IO.monotonic
          report <- Handle
            .allow[LedgerSnapshotImport.Error] {
              LedgerSnapshotImport.run[IO](
                LedgerSnapshotImport.Config(
                  source / "ledger" / "136511997",
                  db,
                  setup.identity,
                  setup.geometry.byron.epochSlots,
                  params,
                  boot,
                  LedgerSnapshotImport.Trust.Verify(source / "ancillary_manifest.json", key)
                )
              )
            }
            .rescue(e => IO.raiseError(new AssertionError(e.message)))
          elapsed <- IO.monotonic.map(_ - began)
          content <- IO.blocking(
            JFiles.readAllBytes(
              (db / "snapshots" / FileLedgerSnapshots.nameOf(report.anchor)).toNioPath
            )
          )
          original <- IO.blocking(
            JFiles.readAllBytes((source / "ledger" / "136511997" / "state").toNioPath)
          )
          back <- SnapshotV7.format[IO](SnapshotCheck.Full).decode(content.dropRight(32))
          _ <- StorageTestKit.ok {
            RocksUtxoStore.resource[IO](temp / "readback").use { store =>
              for
                restored <- store.restore(
                  db / "utxo" / "checkpoints" / StateBacking.stem(report.anchor)
                )
                count <- store.size
                stamp <- store.flushed
              yield
                assert(restored)
                assertEquals(count, 4526058L)
                assertEquals(stamp, report.anchor.blockNo.toLong)
            }
          }
        yield
          assertEquals(report.count, 4526058L)
          assertEquals(report.anchor.slot, BigInt(136511997))
          assertEquals(report.era, Era.Conway)
          assert(report.evidence.isInstanceOf[LedgerSnapshotImport.Evidence.Verified])
          assert(content.dropRight(32).takeRight(original.length).sameElements(original))
          assert(back.isRight, back.left.map(_.message).toString)
          val sidecar =
            SnapshotV7.sidecarOf(content.dropRight(32)).fold(e => fail(e.message), identity)
          assertEquals(sidecar.gaps, Set.empty)
          println(
            s"PREPROD_IMPORT slot=${report.anchor.slot} outputs=${report.count} coin=${report.coin} authenticated=true originalState=true elapsed=$elapsed"
          )
      }
    }
  }
