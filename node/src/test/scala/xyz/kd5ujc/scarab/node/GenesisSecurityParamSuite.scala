// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.storage.SnapshotPolicy

/** The release Cardano node uses Byron's k for chain-wide consensus and storage, and Shelley's k
  * for Shelley-based protocols and ledger rules (ouroboros-consensus 82ecba32,
  * `Cardano/Node.hs:674-697, 847-929`). Skipping Byron does not change those sources.
  */
class GenesisSecurityParamSuite extends FunSuite:
  private val directory = Path.of("fixtures/devnet/lsq/genesis")

  private def setup(byronK: BigInt, shelleyK: BigInt, firstShelley: BigInt = 0) =
    def read(name: String): Option[Array[Byte]] =
      Option.when(Files.exists(directory.resolve(name))) {
        val bytes = Files.readAllBytes(directory.resolve(name))
        val patch = name match
          case "byron-genesis.json" =>
            Json.obj("protocolConsts" -> Json.obj("k" -> Json.fromBigInt(byronK)))
          case "shelley-genesis.json" =>
            Json.obj(
              "securityParam" -> Json.fromBigInt(shelleyK),
              "epochLength" -> Json.fromLong(Long.MaxValue)
            )
          case "configuration.yaml" =>
            Json.obj("TestShelleyHardForkAtEpoch" -> Json.fromBigInt(firstShelley))
          case _ => Json.obj()
        if patch.asObject.exists(_.nonEmpty) then
          parser
            .parse(new String(bytes, "UTF-8"))
            .fold(e => fail(e.message), identity)
            .deepMerge(patch)
            .noSpaces
            .getBytes("UTF-8")
        else bytes
      }
    NodeSetup.fromFiles(ReferenceBuild.Default)(read)

  List(BigInt(0), BigInt(1)).foreach { firstShelley =>
    test(s"unequal k with Shelley at epoch $firstShelley keeps consensus and storage on Byron") {
      val read = setup(2160, 10, firstShelley).fold(e => fail(e.message), identity)
      assertEquals(read.byron.isDefined, firstShelley != 0)
      assertEquals(read.chainStoreK.blocks, 2160)
      assertEquals(
        read.defaultSnapshotPolicy,
        SnapshotPolicy.Aligned(86400, SnapshotPolicy.ReferenceRateLimit)
      )
      assertEquals(read.immutableChunkSlots.map(_.value), Some(21600L))
      assertEquals(read.retainedHeaders, 12961)
      assertEquals(read.byronHistory.k, BigInt(2160))
      assertEquals(read.byronHistory.epochSlots, BigInt(21600))
      assertEquals(read.byronRules.map(_.ledger.k), Option.when(firstShelley != 0)(BigInt(2160)))
    }

    test(s"unequal k with Shelley at epoch $firstShelley preserves Shelley windows") {
      val read = setup(2160, 10, firstShelley).fold(e => fail(e.message), identity)
      val globals = Globals(read.globals, read.geometry)
      assertEquals(read.geometry.k, BigInt(10))
      assertEquals(read.praos.geometry.k, BigInt(10))
      assertEquals(read.praos.geometry.stabilityWindow.toBigInt, BigInt(600))
      assertEquals(read.praos.geometry.randomnessStabilisationWindow.toBigInt, BigInt(800))
      assertEquals(globals.securityParameter.value.toBigInt, BigInt(10))
      assertEquals(globals.stabilityWindow, BigInt(600))
      assertEquals(globals.randomnessStabilisationWindow, BigInt(800))
      assertEquals(read.globals.stabilityWindow, BigInt(600))
      assertEquals(read.globals.startRewards, BigInt(800))
      val history = GenesisHistory.parameters(read.geometry, read.byronHistory)
      assertEquals(history.get(Era.Byron).map(_.safeZone), Some(BigInt(4320)))
      assertEquals(history.get(Era.Conway).map(_.safeZone), Some(BigInt(600)))
      assertEquals(history.get(Era.Conway).map(_.genesisWindow), Some(BigInt(600)))
    }
  }

  test("header retention covers Shelley's forecast when its k exceeds Byron's") {
    val read = setup(10, 2160).fold(e => fail(e.message), identity)
    assertEquals(read.chainStoreK.blocks, 10)
    assertEquals(read.retainedHeaders, 259221)
  }

  test("a Byron security parameter beyond Int is refused at node setup, not truncated") {
    val k = BigInt(Int.MaxValue) + 1
    assertEquals(
      setup(k, 10).map(_.chainStoreK.blocks),
      Left(ConfigError(ConfigProblem.SecurityParamBeyondStorage(k)))
    )
  }

  test("a Shelley security parameter beyond Int does not configure the chain store") {
    val k = BigInt(Int.MaxValue) + 1
    val read = setup(10, k).fold(e => fail(e.message), identity)
    assertEquals(read.chainStoreK.blocks, 10)
    assertEquals(read.geometry.k, k)
  }
