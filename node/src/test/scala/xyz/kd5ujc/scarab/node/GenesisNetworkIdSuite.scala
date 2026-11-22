// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{
  ConfigProblem,
  Genesis,
  GenesisFieldError,
  GenesisFile,
  GenesisSchema
}
import xyz.kd5ujc.scarab.ledger.json.JsonKind
import xyz.kd5ujc.scarab.ledger.state.Network
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The ledger's network is the Shelley genesis's `networkId`, as the reference's `Globals` take it
  * (`networkId = sgNetworkId genesis`, `mkShelleyGlobals`, cardano-ledger 9df9f98c
  * `Shelley/Genesis.hs:775`), whatever the network magic is (#1334).
  */
class GenesisNetworkIdSuite extends FunSuite:
  private val dir = Path.of("fixtures/eras/genesis")
  private val MainnetMagic = 764824073L

  /** The setup of `fixtures/eras/genesis`, its Shelley genesis with `networkMagic` and `networkId`
    * set as given.
    */
  private def setupWith(magic: Long, networkId: String): NodeSetup =
    def read(name: String): Option[Array[Byte]] =
      Option.when(Files.exists(dir.resolve(name))) {
        val bytes = Files.readAllBytes(dir.resolve(name))
        if name == "shelley-genesis.json" then
          parser
            .parse(new String(bytes, "UTF-8"))
            .fold(e => fail(e.message), identity)
            .deepMerge(
              Json.obj(
                "networkMagic" -> Json.fromLong(magic),
                "networkId" -> Json.fromString(networkId)
              )
            )
            .noSpaces
            .getBytes("UTF-8")
        else bytes
      }
    NodeSetup.fromFiles(ReferenceBuild.Default)(read).fold(e => fail(e.message), identity)

  private def assertNetwork(setup: NodeSetup, magic: Long, network: Network): Unit =
    assertEquals(setup.genesis.networkMagic, magic)
    assertEquals(setup.genesis.networkId, network)
    assertEquals(setup.globals.network, network)
    assertEquals(setup.mirrorEnv().ledger.network, network.wire)

  test("a test network's magic with networkId Mainnet gives the main network") {
    assertNetwork(setupWith(1082026L, "Mainnet"), 1082026L, Network.Mainnet)
  }

  test("mainnet's magic with networkId Testnet gives the test network") {
    assertNetwork(setupWith(MainnetMagic, "Testnet"), MainnetMagic, Network.Testnet)
  }

  test("a networkId that agrees with the magic gives the same network") {
    assertNetwork(setupWith(1082026L, "Testnet"), 1082026L, Network.Testnet)
    assertNetwork(setupWith(MainnetMagic, "Mainnet"), MainnetMagic, Network.Mainnet)
  }

  // On the public networks the magic and `networkId` agree, so the network the ledger takes is
  // the one the magic gave before #1334.
  List(
    "fixtures/mainnet/genesis/shelley-genesis.json" -> Network.Mainnet,
    "fixtures/preprod/genesis/shelley-genesis.json" -> Network.Testnet,
    "fixtures/preview/genesis/shelley-genesis.json" -> Network.Testnet,
    "fixtures/leios/testnet/config/shelley-genesis.json" -> Network.Testnet
  ).foreach { (file, network) =>
    test(s"$file: networkId is $network, as its magic says") {
      val genesis = Genesis
        .shelley(new String(Files.readAllBytes(Path.of(file)), "UTF-8"))
        .fold(e => fail(e.message), identity)
      assertEquals(genesis.networkId, network)
      assertEquals(
        genesis.networkMagic == MainnetMagic,
        network == Network.Mainnet,
        s"magic ${genesis.networkMagic}"
      )
    }
  }

  // `Genesis.shelley` reads `networkId` before `GenesisSchema.shelley` checks the file: a refusal of
  // the field is the check's, whichever reads it first.
  private val shelley: Json =
    parser
      .parse(new String(Files.readAllBytes(dir.resolve("shelley-genesis.json")), "UTF-8"))
      .fold(e => fail(e.message), identity)

  private def refusal(edited: Json): ConfigProblem =
    def read(name: String): Option[Array[Byte]] =
      Option.when(Files.exists(dir.resolve(name))) {
        if name == "shelley-genesis.json" then edited.noSpaces.getBytes("UTF-8")
        else Files.readAllBytes(dir.resolve(name))
      }
    NodeSetup.fromFiles(ReferenceBuild.Default)(read).fold(_.problem, _ => fail("accepted"))

  test(
    "a genesis without networkId, or with one of another name or type, is refused at the field"
  ) {
    def at(cause: GenesisFieldError) =
      ConfigProblem.GenesisField(GenesisFile.Shelley, Vector("networkId"), cause)
    List(
      shelley.mapObject(_.remove("networkId")) -> at(GenesisFieldError.Missing),
      shelley.deepMerge(Json.obj("networkId" -> Json.fromString("Preview"))) ->
        at(GenesisFieldError.Choice("Preview", Vector("Mainnet", "Testnet"))),
      shelley.deepMerge(Json.obj("networkId" -> Json.fromInt(1))) ->
        at(GenesisFieldError.Kind(JsonKind.String, JsonKind.Number))
    ).foreach { (edited, expected) =>
      assertEquals(refusal(edited), expected)
      assertEquals(GenesisSchema.shelley(edited).left.map(_.problem), Left(expected))
    }
  }
