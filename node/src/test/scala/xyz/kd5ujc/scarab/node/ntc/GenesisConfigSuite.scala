// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite

/** `GetGenesisConfig` on the public networks' Shelley genesis files. `cardano-cli query tip` asks
  * for it (it reads the genesis parameters from the node), so a genesis that cannot be encoded
  * breaks the command on that network.
  */
class GenesisConfigSuite extends FunSuite:
  private def genesis(network: String) =
    val text = Files.readString(Path.of("fixtures", network, "genesis", "shelley-genesis.json"))
    parser.parse(text).fold(e => fail(e.message), identity)

  List("preview", "preprod", "mainnet").foreach { network =>
    test(s"the $network Shelley genesis encodes") {
      val encoded = GenesisConfig.encode(genesis(network))
      assert(encoded.isRight, encoded.toString)
    }
  }

  test("a missing field is a typed failure carrying its JSON field") {
    val missing = genesis("preview").mapObject(_.remove("networkMagic"))
    assert(GenesisConfig.encode(missing).left.exists {
      case GenesisConfig.Error.InvalidField("networkMagic", _) => true
      case _                                                   => false
    })
  }

  test("invalid decimal, system-start and nonce values have distinct typed errors") {
    val valid = genesis("preview")
    val decimal = valid.deepMerge(Json.obj("activeSlotsCoeff" -> Json.fromString("not a ratio")))
    assertEquals(
      GenesisConfig.encode(decimal),
      Left(GenesisConfig.Error.InvalidDecimal("activeSlotsCoeff"))
    )
    val start = valid.deepMerge(Json.obj("systemStart" -> Json.fromString("not a time")))
    assertEquals(
      GenesisConfig.encode(start),
      Left(GenesisConfig.Error.InvalidSystemStart("not a time"))
    )
    val nonce = valid.deepMerge(
      Json.obj(
        "protocolParams" -> Json.obj(
          "extraEntropy" -> Json.obj(
            "tag" -> Json.fromString("Nonce"),
            "contents" -> Json.fromString("gg")
          )
        )
      )
    )
    assert(GenesisConfig.encode(nonce).left.exists {
      case GenesisConfig.Error.InvalidHex("contents", _) => true
      case _                                             => false
    })
  }

  test("malformed genesis-delegate keys have a typed hexadecimal failure") {
    val valid = genesis("preview")
    val delegate =
      Json.obj("delegate" -> Json.fromString("00" * 28), "vrf" -> Json.fromString("00" * 32))
    val invalid = valid.mapObject(_.add("genDelegs", Json.obj("gg" -> delegate)))
    assert(GenesisConfig.encode(invalid).left.exists {
      case GenesisConfig.Error.InvalidHex("genesis key", _) => true
      case _                                                => false
    })
  }
