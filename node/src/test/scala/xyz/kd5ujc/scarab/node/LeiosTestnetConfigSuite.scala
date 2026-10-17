// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** The published configuration of the public Leios testnet (`fixtures/leios/testnet/config`: the
  * files of `book.play.dev.cardano.org/environments-pre/leios`, unchanged; hashes in
  * `fixtures/leios/README.md`), read as the node reads any configuration. What the node accepts of
  * it and what it records as not implemented is the first line of the gap table in docs/leios.md.
  */
class LeiosTestnetConfigSuite extends FunSuite:
  private val dir = Path.of("fixtures", "leios", "testnet", "config")
  private val configText = Files.readString(dir.resolve("config.json"))
  private val config =
    NodeConfig.parse(configText, strict = true).fold(e => fail(e.message), identity)
  private def read(name: String): Option[Array[Byte]] =
    Option.when(Files.exists(dir.resolve(name)))(Files.readAllBytes(dir.resolve(name)))

  test("every genesis hash the configuration lists is the hash of the published file") {
    assertEquals(NodeSetup.verifyHashes(config, read), Right(()))
    // the Dijkstra hash is listed and checked too
    val dijkstra = Blake2b.hash256.hash(Bytes.fromArray(read("dijkstra-genesis.json").get))
    assertEquals(
      dijkstra.hex,
      "aa1238f505479a9b104d2cc001b4bf951062cd527200bea9a1857bdd0dc41085"
    )
    assertEquals(config.hashes.dijkstra.map(_.hex), Some(dijkstra.hex))
  }

  test("the chain starts in Conway at epoch 0, and the node records Dijkstra as not implemented") {
    assertEquals(config.hardForkEpochs.get(Era.Conway), Some(BigInt(0)))
    assertEquals(config.hardForkEpochs.get(Era.Shelley), Some(BigInt(0)))
    assertEquals(config.settings.experimentalHardForksEnabled, true)
    assert(
      config.settings.unsupported.exists(_.contains("Dijkstra")),
      config.settings.unsupported.toString
    )
    assertEquals(config.requiresNetworkMagic, Some(true))
  }

  test("the node setup reads the genesis, funds and pool from `extraConfig` included") {
    val setup =
      NodeSetup.fromConfigFile(dir.resolve("config.json")).fold(e => fail(e.message), identity)
    assertEquals(setup.identity.magic, 164L)
    assertEquals(setup.genesis.geometry.epochLength, 21600L)
    // `initialFunds` and `staking` are empty at the top level; the two funds and the one pool are
    // in `extraConfig`, which GenesisState reads (injection)
    val start = setup.mirrorStart
    assertEquals((start.utxo.size, start.utxo.coin), (2, BigInt("30000000900000000")))
    val (pools, distribution) = start
      .ledger(None)
      .toOption
      .flatten
      .fold(fail("the start state has no ledger state")) { case InEra.Of(_, l) =>
        (l.poolCount, l.leaderPools(nextEpoch = false))
      }
    assertEquals(pools, 1)
    // the pool's `blsKey` is null in the genesis, and PoolParams has no field for one anyway
    assertEquals(distribution.total, BigInt(900000000))
  }
end LeiosTestnetConfigSuite
