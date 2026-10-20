// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra

class GenesisVrfRegistrySuite extends FunSuite:
  private val directory = Path.of("fixtures/devnet/lsq/genesis")
  private val operator = "11" * 28
  private val vrf = "22" * 32
  private val reward = "33" * 28

  private def setup(major: Int): NodeSetup =
    val pool = Json.obj(
      "vrf" -> Json.fromString(vrf),
      "pledge" -> Json.fromInt(0),
      "cost" -> Json.fromInt(0),
      "margin" -> Json.fromInt(0),
      "accountAddress" -> Json.obj(
        "network" -> Json.fromString("Testnet"),
        "credential" -> Json.obj("keyHash" -> Json.fromString(reward))
      ),
      "owners" -> Json.arr()
    )
    def read(name: String): Option[Array[Byte]] =
      Option.when(Files.exists(directory.resolve(name))) {
        val bytes = Files.readAllBytes(directory.resolve(name))
        if name == "shelley-genesis.json" then
          val original =
            parser.parse(new String(bytes, "UTF-8")).fold(e => fail(e.message), identity)
          original
            .deepMerge(
              Json.obj(
                "protocolParams" -> Json.obj(
                  "protocolVersion" -> Json.obj("major" -> Json.fromInt(major))
                ),
                "extraConfig" -> Json.obj(
                  "stakePools" -> Json.obj("data" -> Json.obj(operator -> pool))
                )
              )
            )
            .noSpaces
            .getBytes("UTF-8")
        else bytes
      }
    NodeSetup.fromFiles(read).fold(e => fail(e.message), identity)

  test("native genesis retains injected pools without deriving VRF registrations") {
    List(9, 10, 11).foreach { major =>
      setup(major).mirrorStart.ext.ledgerState.state match
        case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state) =>
          val pools = state.nes.es.ls.cert.pState
          assertEquals(pools.stakePools.size, 1, s"protocol $major injected pool")
          assertEquals(
            pools.stakePools.head._2.vrf.bytes.hex,
            vrf,
            s"protocol $major original VRF"
          )
          assert(pools.vrfKeyHashes.isEmpty, s"protocol $major genesis registry must be empty")
        case other => fail(s"expected Conway, got ${other.era}")
    }
  }
