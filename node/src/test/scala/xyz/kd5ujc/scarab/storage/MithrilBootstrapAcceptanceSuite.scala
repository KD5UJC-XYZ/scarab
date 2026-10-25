// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Ask
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.Json
import java.nio.file.{Files, Path as JPath}
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorStep}
import xyz.kd5ujc.scarab.ledger.Utxo
import xyz.kd5ujc.scarab.node.{NodeSetup, Projection, StateDump}

/** Optional recorded-fixture acceptance: project the actual bootstrap's untouched V5/checkpoint.
  * This uses the same projection/digest as follow, before replaying any post-snapshot blocks. It
  * verifies artifact provenance, restored anchor and UTxO count, then constructs (and optionally
  * exports) a projection. It does not load or compare an independent native projection body.
  */
class MithrilBootstrapAcceptanceSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 5.minutes

  test("the actual certified Preview bootstrap restores its original checkpoint and projection") {
    val directory = sys.env.get("SCARAB_MITHRIL_DB")
    assume(directory.nonEmpty, "SCARAB_MITHRIL_DB names a completed authentic bootstrap")
    directory.traverse_ { name =>
      val db = Path(name)
      val setup = NodeSetup
        .fromDirectory(
          JPath.of(sys.env.getOrElse("SCARAB_MITHRIL_GENESIS", "fixtures/preview/genesis"))
        )
        .fold(e => fail(e.message), identity)
      given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
      val evidence = io.circe.parser
        .parse(Files.readString((db / "mithril-bootstrap.json").toNioPath))
        .fold(e => fail(e.message), identity)
      assert(evidence.hcursor.get[Boolean]("mithrilArtifactVerified").contains(true))
      assert(evidence.hcursor.get[Boolean]("ancillaryVerified").contains(true))
      assert(evidence.hcursor.get[Boolean]("ancillaryFilesMithrilCertified").contains(false))
      val slot = evidence.hcursor.get[BigInt]("ledgerSlot").fold(e => fail(e.message), identity)
      val hash = evidence.hcursor.get[String]("ledgerHash").fold(e => fail(e.message), identity)
      assertEquals(
        evidence.hcursor.get[String]("artifact").toOption,
        Some("20bfc5b5377f478b7d48e4c0f2c78fe4246b8d93d08a2292cba02411b94e7cbd")
      )
      val candidates = Files.list((db / "snapshots").toNioPath)
      val selected =
        try
          import scala.jdk.CollectionConverters.*
          candidates.iterator().asScala.toVector match
            case Vector(one) => one
            case other       => fail(s"expected untouched imported snapshot, found ${other.size}")
        finally candidates.close()
      val bytes = Files.readAllBytes(selected)
      for
        decoded <- SnapshotV7.format[IO](SnapshotCheck.Full).decode(bytes.dropRight(32))
        restored = decoded.fold(e => fail(e.message), identity)
        anchor = restored.anchor.getOrElse(fail("snapshot origin"))
        _ <- IO { assertEquals(anchor.slot, slot); assertEquals(anchor.hash.hex, hash) }
        _ <- StorageTestKit.tempDir.use { scratch =>
          StorageTestKit.ok(RocksUtxoStore.resource[IO](scratch / "readback").use { store =>
            for
              loaded <- store.restore(db / "utxo" / "checkpoints" / StateBacking.stem(anchor))
              _ <- IO(assert(loaded))
              count <- store.size
              _ <- IO(assertEquals(count, 3192290L))
              utxo <- StateDump
                .utxoDigest(Some(store), MirrorStep.utxoOf(restored.state).getOrElse(Utxo.empty))
              projection = Projection
                .node(anchor.point, restored.state, Utxo.empty)
                .fold(e => fail(e.show), identity)
              body = StateDump
                .reduce(projection)
                .deepMerge(Json.obj("utxo" -> utxo, "format" -> Json.fromString(StateDump.Format)))
              _ <- sys.env.get("SCARAB_MITHRIL_DUMP").traverse_ { output =>
                IO.blocking {
                  val dir = JPath.of(output)
                  Files.createDirectories(dir)
                  Files.writeString(dir.resolve(f"${slot.toLong}%012d-$hash.json"), body.noSpaces)
                  ()
                }
              }
            yield ()
          })
        }
      yield ()
    }
  }
