// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser
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
