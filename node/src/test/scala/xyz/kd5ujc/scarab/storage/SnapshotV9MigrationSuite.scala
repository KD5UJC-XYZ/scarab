// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.syntax.all.*
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.BinaryError
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, ProtocolParams}
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.node.{NativeSnapshots, NodeSetup}
import xyz.kd5ujc.scarab.protocol.Era

import StorageTestKit.*

/** V9 names Dijkstra among the eras of the sidecar and holds the protocol parameters with the
  * fields Dijkstra adds (#54); V8 and the versions before it named Byron to Conway and held
  * Conway's parameters. The readers of those versions take the frozen era and parameter codecs
  * ([[FrozenEra]], [[FrozenProtocolParams]]), so a V8 file reads as it was written and converts to
  * V9 with no loss: every era it can name is an era of V9, encoded the same way, and its parameters
  * take Dijkstra's fields at their pre-Dijkstra defaults (ADR 0006).
  */
class SnapshotV9MigrationSuite extends CatsEffectSuite:
  private val env: ConsensusMirrorEnv = NodeSetup
    .fromDirectory(JPath.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), _.mirrorEnv())

  private def content(dir: String, name: String): Array[Byte] =
    FileLedgerSnapshots
      .verified(JFiles.readAllBytes(JPath.of(dir).resolve(name)))
      .fold(e => fail(e.message), identity)

  private val names = Vector(
    "eras/byron-25.bin",
    "eras/shelley-149.bin",
    "eras/babbage-727.bin",
    "eras/conway-730.bin",
    "conway-native-730.bin"
  )

  test(
    "the frozen V8 writer reproduces the committed V8 files: it is the schema V8 was written with"
  ) {
    names.foreach { name =>
      val v8 = content("fixtures/snapshot-v8", name)
      val (binding, sidecar, haskell) = SnapshotV8.sections(v8).fold(e => fail(e.message), identity)
      assert(java.util.Arrays.equals(SnapshotV8.framed(binding, sidecar, haskell), v8), name)
    }
  }

  test("a committed V8 file migrates once to V9 and reads as the maintained V9 file") {
    names.traverse_ { name =>
      tempDir.use { dir =>
        val file = dir / "snapshot-00000000000000000025-25.bin"
        for
          _ <- IO.blocking(
            JFiles.copy(JPath.of("fixtures/snapshot-v8").resolve(name), file.toNioPath)
          )
          first <- ok(SnapshotMigration.toCurrent[IO].run(dir))
          second <- ok(SnapshotMigration.toCurrent[IO].run(dir))
          after <- IO.blocking(JFiles.readAllBytes(file.toNioPath))
          migrated <- NativeSnapshots.decode[IO](after, env)
          maintained <- NativeSnapshots
            .read[IO](JPath.of("fixtures/snapshot-v9").resolve(name), env)
        yield
          assertEquals(
            first,
            SnapshotMigration.Report(
              List(Migrated(FileKind.SnapshotFile, FormatVersion.V8, FormatVersion.V9, 1)),
              Nil
            ),
            name
          )
          assertEquals(second, SnapshotMigration.Report.empty, name)
          assertEquals(
            after.toVector,
            JFiles.readAllBytes(JPath.of("fixtures/snapshot-v9").resolve(name)).toVector,
            name
          )
          assert(migrated.isRight, s"$name: the migrated file does not read")
          assertEquals(migrated, maintained, name)
          assertEquals(
            SnapshotV8.sidecarOf(content("fixtures/snapshot-v8", name)),
            SnapshotV9.sidecarOf(
              FileLedgerSnapshots.verified(after).fold(e => fail(e.message), identity)
            ),
            s"$name: the sidecar converts with no loss"
          )
      }
    }
  }

  test("every era a previous file names is the live era of the same index") {
    assertEquals(
      FrozenEra.Era.values.toVector.map(FrozenEra.live),
      Era.values.toVector.filter(_ != Era.Dijkstra)
    )
    FrozenEra.Era.values.foreach(e =>
      assertEquals(FrozenEra.live(e).ordinal, e.ordinal, e.toString)
    )
  }

  test("V9 keeps a Dijkstra era in the sidecar; the frozen schema of V5 to V8 holds none") {
    val v9 = content("fixtures/snapshot-v9", "eras/conway-730.bin")
    val (_, sidecar, _) = SnapshotV9.sections(v9).fold(e => fail(e.message), identity)
    val dijkstra = sidecar.copy(observed = sidecar.observed + (Era.Dijkstra -> BigInt(9)))
    assertEquals(
      Codec.decode(Codec.encode(dijkstra)(using SnapshotSidecar.codec))(using
        SnapshotSidecar.codec
      ),
      Right(dijkstra)
    )
    val frozen = Vector(SnapshotSidecarV8.codec, SnapshotSidecarV7.codec)
    frozen.foreach { codec =>
      Codec.decode(Codec.encode(dijkstra)(using codec))(using codec) match
        case Left(BinaryError.InvalidVariant("Era", 7L, 7)) => ()
        case other => fail(s"a frozen schema read a Dijkstra era: $other")
    }
  }

  private def shelleyParams(sidecar: SnapshotSidecar): ProtocolParams = sidecar.chain match
    case SnapshotSidecar.Chain.InShelley(_, params, _, _) => params
    case other => fail(s"not a Shelley-based sidecar: $other")

  test("a V8 sidecar's parameters read with the fields Dijkstra adds at their defaults") {
    val (_, sidecar, _) = SnapshotV8
      .sections(content("fixtures/snapshot-v8", "eras/conway-730.bin"))
      .fold(e => fail(e.message), identity)
    val params = shelleyParams(sidecar)
    assertEquals(
      FrozenProtocolParams.live(FrozenProtocolParams.previous(params)),
      params,
      "a V8 sidecar's parameters are a node's before Dijkstra"
    )
    assertEquals(params.maxRefScriptSizePerBlock, BigInt(0))
    assertEquals(params.refScriptCostMultiplier, Rational.zero)
    assertEquals(params.maxPledgeLeverage, None)
    assertEquals(params.maxEndorserBlockExUnits, ExUnits.zero)
    assertEquals(params.perasBootstrapRound, None)
    assertEquals(params.refInputsCostPerDatumByte, BigInt(0))
  }

  test("V9 keeps Dijkstra's parameter fields; the frozen schema of V5 to V8 drops them") {
    val v9 = content("fixtures/snapshot-v9", "eras/conway-730.bin")
    val (_, sidecar, _) = SnapshotV9.sections(v9).fold(e => fail(e.message), identity)
    val dijkstra = shelleyParams(sidecar).copy(
      maxRefScriptSizePerBlock = 1048576,
      refScriptCostMultiplier = Rational(6, 5),
      maxPledgeLeverage = Some(Rational(3, 1)),
      leiosCommitteeSize = 900,
      maxEndorserBlockExUnits = ExUnits(7000000000L, 2000000000000L),
      perasBootstrapRound = Some(BigInt(0)),
      refInputsCostPerDatumByte = 7
    )
    val withDijkstra = sidecar.chain match
      case c: SnapshotSidecar.Chain.InShelley => sidecar.copy(chain = c.copy(params = dijkstra))
      case other                              => fail(s"not a Shelley-based sidecar: $other")
    val back = Codec
      .decode(Codec.encode(withDijkstra)(using SnapshotSidecar.codec))(using SnapshotSidecar.codec)
      .fold(e => fail(e.toString), identity)
    assertEquals(shelleyParams(back), dijkstra)
    Vector(SnapshotSidecarV8.codec, SnapshotSidecarV7.codec).foreach { codec =>
      val frozen = Codec
        .decode(Codec.encode(withDijkstra)(using codec))(using codec)
        .fold(e => fail(e.toString), identity)
      assertEquals(shelleyParams(frozen), shelleyParams(sidecar))
    }
  }
