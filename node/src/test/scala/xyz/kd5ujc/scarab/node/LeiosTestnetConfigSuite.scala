// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NtnVersion}

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
      "868a465b7115ae653ec532f46eca1a31a89211a96e810d78f82d6e60e3cdca87"
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

  test("the testnet enables experimental protocols, so the node offers version 16 to its relay") {
    assertEquals(config.settings.experimentalProtocolsEnabled, true)
    assertEquals(config.settings.maxNtnVersion, NtnVersion.V16)
  }

  test("the live configuration retains the Leios and LedgerDB settings and reports them unused") {
    val settings = config.settings
    assertEquals(settings.dijkstraHardForkEpoch, Some(BigInt(0)))
    assertEquals(settings.leios.egressBudgetMbps, 250.0)
    assertEquals(settings.leios.database, LeiosDbBackend.SQLite)
    assertEquals(settings.leios.minOfferLead.map(_.toBigInt), Some(BigInt(1200)))
    assertEquals(settings.leios.adaptiveEndorsements, Some(LeiosAdaptiveSettings()))
    assertEquals(settings.ledgerDb.backend, LedgerDbBackend.V2InMemory)
    assertEquals(
      settings.ledgerDb.queryBatchSize,
      LedgerQueryBatchSize.Requested(xyz.kd5ujc.scarab.ledger.state.Word64.fromUnsigned(100000))
    )
    val interval = settings.ledgerDb.snapshots.interval match
      case LedgerSnapshotInterval.Requested(slots) => Some(slots.value.toBigInt)
      case LedgerSnapshotInterval.Default          => None
    assertEquals(interval, Some(BigInt(4320)))
    assertEquals(settings.ledgerDb.snapshots.minimumDelaySeconds, BigInt(300))
    assertEquals(settings.ledgerDb.snapshots.maximumDelaySeconds, BigInt(1080))
    for key <- List(
        "EgressBudgetMbps",
        "LeiosDbConfig",
        "LeiosMinOfferLead",
        "LeiosAdaptiveEndorsements",
        "LedgerDB"
      )
    do assert(settings.unsupported.contains(s"$key: recognised but unused"))
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

  test(
    "Dijkstra's epoch-0 trigger is carried, and the node starts in Conway: it does not enter it"
  ) {
    val setup =
      NodeSetup.fromConfigFile(dir.resolve("config.json")).fold(e => fail(e.message), identity)
    val forks = setup.praos.forks
    assertEquals(
      forks.triggers.get(Era.Dijkstra),
      Some(xyz.kd5ujc.scarab.consensus.ForkTrigger.AtEpoch(0))
    )
    assertEquals(forks.maxMajorProtVer, 11)
    assertEquals(forks.trigger(Era.Dijkstra), None)
    assertEquals(setup.mirrorStart.ext.ledgerState.era, Era.Conway)
  }
  test("the node setup reads the Dijkstra genesis the configuration names") {
    val setup =
      NodeSetup.fromConfigFile(dir.resolve("config.json")).fold(e => fail(e.message), identity)
    val dijkstra = setup.globals.dijkstra.fold(fail("no Dijkstra genesis was read"))(identity)
    assertEquals(dijkstra.params.leiosCommitteeSize, BigInt(900))
    assertEquals(dijkstra.params.refScriptCostStride, BigInt(25600))
    assertEquals(dijkstra.plutusV4CostModel.size, 251)
  }

  test("a Dijkstra genesis the reference refuses stops the node setup") {
    // the hash is left out, so the file's content is what refuses
    val unhashed = config.copy(hashes = config.hashes.copy(dijkstra = None))
    def withDijkstra(text: String)(name: String) =
      if name == "dijkstra-genesis.json" then Some(text.getBytes("UTF-8")) else read(name)
    val refused = NodeSetup.assemble(unhashed, withDijkstra("""{"maxRefScriptSizePerBlock": 1}"""))
    assertEquals(
      refused.left.map(_.problem),
      Left(
        xyz.kd5ujc.scarab.ledger.ConfigProblem.GenesisField(
          xyz.kd5ujc.scarab.ledger.GenesisFile.Dijkstra,
          Vector("maxRefScriptSizePerTx"),
          xyz.kd5ujc.scarab.ledger.GenesisFieldError.Missing
        )
      )
    )
  }
end LeiosTestnetConfigSuite
