// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import scala.annotation.unused
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import io.circe.Json
import io.circe.parser
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import munit.FunSuite

/** The Haskell node's `config.json` and `topology.json`, read as `cardano-node run` reads them,
  * against the public Preview, preprod and mainnet files (`fixtures/<network>/genesis`, unchanged).
  */
class ConfigSuite extends FunSuite:
  private val networks = List("preview", "preprod", "mainnet")
  private def dir(network: String) = Path.of("fixtures", network, "genesis")
  private def text(network: String, file: String) = Files.readString(dir(network).resolve(file))
  private def read(network: String): String => Option[Array[Byte]] = name =>
    Option.when(Files.exists(dir(network).resolve(name)))(
      Files.readAllBytes(dir(network).resolve(name))
    )
  @unused private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private def blake(bytes: Array[Byte]) = Blake2b.hash256.hash(Bytes.fromArray(bytes))

  private def parsed(network: String) =
    NodeConfig
      .parse(text(network, "config.json"), strict = true)
      .fold(e => fail(e.message), identity)

  test("the public configurations parse as the reference parses them") {
    val preview = parsed("preview")
    assertEquals(preview.files.byron, "byron-genesis.json")
    assertEquals(preview.files.conway, "conway-genesis.json")
    assertEquals(preview.hardForkEpochs.get(Era.Shelley), Some(BigInt(0)))
    assertEquals(preview.hardForkEpochs.get(Era.Alonzo), Some(BigInt(0)))
    assertEquals(preview.requiresNetworkMagic, Some(true))
    assertEquals(preview.settings.lastKnownBlockVersion, Some((3, 1, 0)))
    assertEquals(preview.settings.experimentalHardForksEnabled, false)
    assertEquals(preview.settings.consensusMode, Some("GenesisMode"))
    assertEquals(preview.files.checkpoints, Some("checkpoints.json"))
    val preprod = parsed("preprod")
    assertEquals(preprod.settings.lastKnownBlockVersion, Some((2, 0, 0)))
    assertEquals(preprod.hardForkEpochs, Map.empty)
    assertEquals(preprod.requiresNetworkMagic, Some(true))
    val mainnet = parsed("mainnet")
    assertEquals(mainnet.requiresNetworkMagic, Some(false))
    assertEquals(mainnet.settings.maxKnownMajorProtocolVersion, Some(BigInt(2)))
    assertEquals(mainnet.hashes.conway.map(_.hex).map(_.take(8)), Some("15a199f8"))
  }

  test("every hash of every public configuration equals the file it names") {
    networks.foreach { network =>
      val config = parsed(network)
      // mainnet's 1 MB Byron genesis file is not kept; the other three are
      val result = NodeSetup.verifyHashes(config, read(network))
      if network == "mainnet" then
        assertEquals(
          result.left.map(_.problem.getClass.getSimpleName).left.toOption,
          Some("FileMissing")
        )
      else assertEquals(result, Right(()), network)
      List(
        config.files.shelley -> config.hashes.shelley,
        config.files.alonzo -> config.hashes.alonzo,
        config.files.conway -> config.hashes.conway
      ).foreach { (file, expected) =>
        val bytes = read(network)(file).getOrElse(fail(s"$network/$file is missing"))
        assertEquals(Some(blake(bytes)), expected, s"$network/$file")
      }
    }
  }

  test("the mainnet Byron hash is verified against the full file when it is at hand") {
    val full =
      Path.of(".workspace/data/2026-10-14-configs/mainnet")
    assume(
      Files.exists(full.resolve("byron-genesis.json")),
      "the 1 MB mainnet Byron genesis is not kept"
    )
    val readFull: String => Option[Array[Byte]] = name =>
      read("mainnet")(name).orElse(
        Option.when(Files.exists(full.resolve(name)))(Files.readAllBytes(full.resolve(name)))
      )
    assertEquals(NodeSetup.verifyHashes(parsed("mainnet"), readFull), Right(()))
  }

  test("a genesis file that differs from its listed hash is a typed GenesisHashMismatch") {
    networks.filter(_ != "mainnet").foreach { network =>
      val config = parsed(network)
      List(
        "Shelley" -> config.files.shelley,
        "Alonzo" -> config.files.alonzo,
        "Conway" -> config.files.conway,
        "Byron" -> config.files.byron
      )
        .foreach { (role, file) =>
          val altered: String => Option[Array[Byte]] = name =>
            read(network)(name).map(b =>
              // one more space at the end changes the bytes (and the Shelley/Alonzo/Conway hash); for
              // Byron, which is hashed in canonical form, a changed value is needed
              if name != file then b
              else if role == "Byron" then
                new String(b, "UTF-8")
                  .replaceFirst("\"k\": ?[0-9]+", "\"k\": 999999")
                  .getBytes("UTF-8")
              else b :+ ' '.toByte
            )
          NodeSetup.verifyHashes(config, altered) match
            case Left(ConfigError(_, ConfigProblem.GenesisHashMismatch(r, f, expected, actual))) =>
              assertEquals((r, f), (role, file))
              assertNotEquals(expected, actual)
            case other => fail(s"$network $role: $other")
        }
    }
  }

  test("whitespace does not change the Byron hash, which is over the canonical JSON") {
    val config = parsed("preview")
    val reformatted: String => Option[Array[Byte]] = name =>
      read("preview")(name).map(b =>
        if name == config.files.byron then
          parser.parse(new String(b, "UTF-8")).toOption.get.spaces4.getBytes("UTF-8")
        else b
      )
    assertEquals(NodeSetup.verifyHashes(config, reformatted), Right(()))
  }

  test("a missing genesis file is a typed FileMissing, and a hash of the wrong shape is invalid") {
    val config = parsed("preview")
    val without: String => Option[Array[Byte]] =
      name => if name == config.files.alonzo then None else read("preview")(name)
    assertEquals(
      NodeSetup.verifyHashes(config, without).left.map(_.problem),
      Left(ConfigProblem.FileMissing("Alonzo", "alonzo-genesis.json"))
    )
    val short = text("preview", "config.json").replace(config.hashes.shelley.get.hex, "abcd")
    assert(NodeConfig.parse(short).left.exists(_.message.contains("ShelleyGenesisHash")))
  }

  test("the setup from the preview configuration is the setup from its directory") {
    val viaConfig = NodeSetup
      .fromConfigFile(dir("preview").resolve("config.json"))
      .fold(e => fail(e.message), identity)
    val viaDir = NodeSetup.fromDirectory(dir("preview")).fold(e => fail(e.message), identity)
    assertEquals(viaConfig.identity, viaDir.identity)
    assertEquals(viaConfig.genesis.geometry, viaDir.genesis.geometry)
    assertEquals(viaConfig.config, viaDir.config)
    assertEquals(viaConfig.identity.magic, 2L)
  }

  test("the preprod configuration starts a chain with a Byron prefix, and its hashes verify") {
    val setup = NodeSetup
      .fromConfigFile(dir("preprod").resolve("config.json"))
      .fold(e => fail(e.message), identity)
    assertEquals(setup.identity.magic, 1L)
    assert(setup.byron.isDefined)
    assertEquals(setup.byron.map(_.hash), parsed("preprod").hashes.byron)
  }

  test("--genesis-dir is where the genesis files a configuration names are looked up") {
    val elsewhere = Files.createTempDirectory("config-suite")
    Files.copy(dir("preview").resolve("config.json"), elsewhere.resolve("config.json"))
    // no genesis files next to the configuration: they are missing...
    assertEquals(
      NodeSetup
        .fromConfigFile(elsewhere.resolve("config.json"))
        .left
        .map(_.problem.getClass.getSimpleName),
      Left("FileMissing")
    )
    // ...unless the directory is given
    assert(NodeSetup.fromConfigFile(elsewhere.resolve("config.json"), Some(dir("preview"))).isRight)
  }

  test("strict reading insists on what cardano-node insists on") {
    def without(key: String) =
      parser
        .parse(text("preview", "config.json"))
        .toOption
        .get
        .hcursor
        .downField(key)
        .delete
        .top
        .get
        .noSpaces
    List(
      "ConwayGenesisFile",
      "AlonzoGenesisFile",
      "ShelleyGenesisFile",
      "ByronGenesisFile",
      "LastKnownBlockVersion-Major",
      "LastKnownBlockVersion-Minor"
    ).foreach { key =>
      assert(
        NodeConfig.parse(without(key), strict = true).left.exists(_.message.contains(key.take(12))),
        key
      )
      // the directory mode takes the standard name instead
      if key.endsWith("File") || key.startsWith("Last") then
        assert(NodeConfig.parse(without(key)).isRight, key)
    }
    // GenesisFile is the older name of the Byron and Shelley files, and not allowed next to them
    val both = parser
      .parse(text("preview", "config.json"))
      .toOption
      .get
      .deepMerge(Json.obj("GenesisFile" -> Json.fromString("g.json")))
    assert(NodeConfig.parse(both.noSpaces).left.exists(_.message.contains("not both")))
    val old = Json.obj(
      "GenesisFile" -> Json.fromString("shelley-genesis.json"),
      "AlonzoGenesisFile" -> Json.fromString("a"),
      "ConwayGenesisFile" -> Json.fromString("c"),
      "LastKnownBlockVersion-Major" -> Json.fromInt(3),
      "LastKnownBlockVersion-Minor" -> Json.fromInt(0)
    )
    val config = NodeConfig.parse(old.noSpaces, strict = true).fold(e => fail(e.message), identity)
    assertEquals(
      (config.files.byron, config.files.shelley),
      ("shelley-genesis.json", "shelley-genesis.json")
    )
    assertEquals(config.settings.lastKnownBlockVersion, Some((3, 0, 0)))
  }

  test("other protocols, unknown modes, a renamed key and experimental forks are handled") {
    val base = parser.parse(text("preview", "config.json")).toOption.get
    def with_(key: String, value: Json) = base.deepMerge(Json.obj(key -> value)).noSpaces
    assert(NodeConfig.parse(with_("Protocol", Json.fromString("Byron"))).isLeft)
    assert(NodeConfig.parse(with_("ConsensusMode", Json.fromString("Other"))).isLeft)
    assert(NodeConfig.parse(with_("DiffusionMode", Json.fromString("Other"))).isLeft)
    assert(NodeConfig.parse(with_("TestEnableDevelopmentHardForkEras", Json.True)).isLeft)
    // Dijkstra: required once enabled, and recorded as unsupported
    val enabled = with_("ExperimentalHardForksEnabled", Json.True)
    assert(
      NodeConfig
        .parse(enabled, strict = true)
        .left
        .exists(_.message.contains("DijkstraGenesisFile"))
    )
    val dijkstra = base
      .deepMerge(
        Json.obj(
          "ExperimentalHardForksEnabled" -> Json.True,
          "DijkstraGenesisFile" -> Json.fromString("d.json")
        )
      )
      .noSpaces
    val config = NodeConfig.parse(dijkstra, strict = true).fold(e => fail(e.message), identity)
    assert(config.settings.unsupported.exists(_.contains("Dijkstra")))
    assert(parsed("preview").settings.unsupported.exists(_.contains("GenesisMode")))
    // settings the node acts on when started from a configuration file
    val settings = NodeConfig
      .parse(
        base
          .deepMerge(
            Json.obj(
              "SocketPath" -> Json.fromString("/s/node.socket"),
              "DatabasePath" -> Json.fromString("db"),
              "MempoolCapacityBytesOverride" -> Json.fromLong(2000000)
            )
          )
          .noSpaces
      )
      .fold(e => fail(e.message), _.settings)
    assertEquals(
      (settings.socketPath, settings.databasePath, settings.mempoolCapacityBytes),
      (Some("/s/node.socket"), Some("db"), Some(2000000L))
    )
    assertEquals(
      NodeConfig
        .parse(with_("MempoolCapacityBytesOverride", Json.fromString("NoOverride")))
        .map(_.settings.mempoolCapacityBytes),
      Right(None)
    )
    assert(NodeConfig.parse(with_("MempoolCapacityBytesOverride", Json.fromString("lots"))).isLeft)
    assert(NodeConfig.parse("[]").isLeft)
    assert(NodeConfig.parse("{").isLeft)
  }

  test(
    "the public topologies: the peers are the bootstrap peers, the ledger peers start at a slot"
  ) {
    val preview =
      Topology.parse(text("preview", "topology.json")).fold(e => fail(e.message), identity)
    assertEquals(preview.peers.map(_.toString), Vector("preview-node.play.dev.cardano.org:3001"))
    assertEquals(preview.useLedgerAfterSlot, LedgerPeers.After(119231973))
    assertEquals(preview.peerSnapshotFile, Some("peer-snapshot.json"))
    assertEquals(preview.localRoots.map(_.accessPoints), Vector(Vector.empty))
    assertEquals(preview.localRoots.map(_.hotValency), Vector(1))
    val mainnet =
      Topology.parse(text("mainnet", "topology.json")).fold(e => fail(e.message), identity)
    assertEquals(
      mainnet.peers.map(_.toString),
      Vector("backbone.cardano.iog.io:3001", "backbone.mainnet.cardanofoundation.org:3001")
    )
    assertEquals(mainnet.useLedgerAfterSlot, LedgerPeers.After(194140785))
    val preprod =
      Topology.parse(text("preprod", "topology.json")).fold(e => fail(e.message), identity)
    assertEquals(preprod.peers.size, 1)
  }

  test(
    "local roots come first, then the bootstrap peers, without repeats; both spellings of valency"
  ) {
    val topology = Topology
      .parse(
        """{"localRoots": [{"accessPoints": [{"address": "127.0.0.1", "port": 3001}, {"address": "relay.example", "port": 3002}],
        |  "advertise": true, "valency": 2, "trustable": true},
        |  {"accessPoints": [{"address": "10.0.0.5", "port": 3003}], "hotValency": 1, "warmValency": 2, "behindFirewall": true, "diffusionMode": "InitiatorOnly"}],
        | "publicRoots": [{"accessPoints": [{"address": "public.example", "port": 3004}], "advertise": false}],
        | "bootstrapPeers": [{"address": "relay.example", "port": 3002}, {"address": "boot.example.", "port": 3005}],
        | "useLedgerAfterSlot": 0}""".stripMargin
      )
      .fold(e => fail(e.message), identity)
    assertEquals(
      topology.peers.map(_.toString),
      Vector("127.0.0.1:3001", "relay.example:3002", "10.0.0.5:3003", "boot.example:3005")
    )
    assertEquals(topology.localRoots.map(_.hotValency), Vector(2, 1))
    assertEquals(topology.localRoots.map(_.warmValency), Vector(2, 2))
    assertEquals(topology.localRoots.map(_.trustable), Vector(true, false))
    assertEquals(topology.localRoots.map(_.behindFirewall), Vector(false, true))
    assertEquals(
      topology.localRoots.map(_.diffusionMode),
      Vector("InitiatorAndResponder", "InitiatorOnly")
    )
    assertEquals(topology.useLedgerAfterSlot, LedgerPeers.Always)
  }

  test("the public roots are the peers only when nothing else names one") {
    val topology = Topology
      .parse(
        """{"localRoots": [], "publicRoots": [{"accessPoints": [{"address": "public.example", "port": 3004}]}], "bootstrapPeers": null}"""
      )
      .fold(e => fail(e.message), identity)
    assertEquals(topology.peers.map(_.toString), Vector("public.example:3004"))
    assertEquals(topology.bootstrapPeers, None)
    assertEquals(topology.useLedgerAfterSlot, LedgerPeers.Disabled)
  }

  test("a negative slot disables ledger peers; the legacy Producers format is unsupported") {
    val negative =
      Topology.parse("""{"localRoots": [], "publicRoots": [], "useLedgerAfterSlot": -1}""")
    assertEquals(negative.map(_.useLedgerAfterSlot), Right(LedgerPeers.Disabled))
    val legacy =
      Topology.parse("""{"Producers": [{"addr": "127.0.0.1", "port": 3001, "valency": 1}]}""")
    assertEquals(legacy.left.map(_.problem), Left(ConfigProblem.UnsupportedTopology))
  }

  test(
    "topology refusals: SRV names, bad ports and addresses, missing fields, no way to progress"
  ) {
    def topology(extra: String) =
      Topology.parse(s"""{"localRoots": [$extra], "publicRoots": []}""")
    def group(point: String) = s"""{"accessPoints": [$point], "valency": 1}"""
    // an address without a port is an SRV name
    assertEquals(
      topology(group("""{"address": "_cardano._tcp.example"}""")).left.map(_.problem),
      Left(ConfigProblem.UnsupportedTopology)
    )
    assert(topology(group("""{"address": "relay.example", "port": 70000}""")).isLeft)
    assert(topology(group("""{"address": "relay.example", "port": "3001"}""")).isLeft)
    assert(topology(group("""{"address": "0.0.0.0", "port": 3001}""")).isLeft)
    assert(topology(group("""{"address": "", "port": 3001}""")).isLeft)
    assert(topology("""{"accessPoints": []}""").isLeft, "valency is required")
    assert(Topology.parse("""{"publicRoots": []}""").isLeft, "localRoots is required")
    assert(Topology.parse("""{"localRoots": []}""").isLeft, "publicRoots is required")
    assert(
      Topology.parse("""{"localRoots": [], "publicRoots": [], "useLedgerAfterSlot": "x"}""").isLeft
    )
    assert(Topology.parse("[]").isLeft)
    // an empty bootstrap list and no trustable local root: nothing to make progress from
    assert(Topology.parse("""{"localRoots": [], "publicRoots": [], "bootstrapPeers": []}""").isLeft)
    assert(
      Topology
        .parse(
          """{"localRoots": [{"accessPoints": [], "valency": 1, "trustable": true}], "publicRoots": [], "bootstrapPeers": []}"""
        )
        .isRight
    )
  }

  test("an unreadable topology file is a typed FileMissing") {
    assertEquals(
      Topology
        .read(Path.of("/nonexistent/topology.json"))
        .left
        .map(_.problem.getClass.getSimpleName),
      Left("FileMissing")
    )
  }

  test(
    "the flags: --config replaces --genesis-dir, --topology replaces --peer, and one of each is needed"
  ) {
    val viaFiles =
      Main.parse(List("follow", "--config", "/c/config.json", "--topology", "/c/topology.json"))
    assert(viaFiles.isRight, viaFiles.toString)
    assertEquals(viaFiles.toOption.get.genesisDir, None)
    assertEquals(viaFiles.toOption.get.configFile, Some(Path.of("/c/config.json")))
    assert(
      Main.parse(List("follow", "--config", "/c/config.json", "--peer", "127.0.0.1:3001")).isRight
    )
    assert(
      Main.parse(List("follow", "--genesis-dir", "/g", "--topology", "/c/topology.json")).isRight
    )
    assert(Main.parse(List("follow", "--topology", "/c/topology.json")).isLeft, "no genesis")
    assert(Main.parse(List("follow", "--config", "/c/config.json")).isLeft, "no peers")
    assert(Main.parse(List("follow", "--config", "a", "--config", "b", "--peer", "x:1")).isLeft)
  }

  test(
    "the options are completed from the files: peers after the flags' ones, socket and database only with --config"
  ) {
    val config = dir("preview").resolve("config.json")
    val topology = dir("preview").resolve("topology.json")
    val options = Main
      .parse(
        List(
          "follow",
          "--config",
          config.toString,
          "--topology",
          topology.toString,
          "--peer",
          "127.0.0.1:9"
        )
      )
      .toOption
      .get
    val (completed, setup, loaded) = Main.configured(options).fold(e => fail(e.message), identity)
    assertEquals(
      completed.peers.map(_.toString),
      List("127.0.0.1:9", "preview-node.play.dev.cardano.org:3001")
    )
    assertEquals(setup.identity.magic, 2L)
    assert(loaded.isDefined)
    // the file's defaults for the flags (here none are set), and a flag wins over the file
    val withSettings = Files.createTempDirectory("config-suite-settings")
    List("byron", "shelley", "alonzo", "conway").foreach { n =>
      Files.copy(
        dir("preview").resolve(s"$n-genesis.json"),
        withSettings.resolve(s"$n-genesis.json")
      )
    }
    val edited = parser
      .parse(text("preview", "config.json"))
      .toOption
      .get
      .deepMerge(
        Json.obj(
          "SocketPath" -> Json.fromString("/s/node.socket"),
          "DatabasePath" -> Json.fromString("/d"),
          "MempoolCapacityBytesOverride" -> Json.fromLong(3000000)
        )
      )
    Files.writeString(withSettings.resolve("config.json"), edited.noSpaces)
    val bare = Main
      .parse(
        List(
          "follow",
          "--config",
          withSettings.resolve("config.json").toString,
          "--peer",
          "127.0.0.1:9"
        )
      )
      .toOption
      .get
    val (fromFile, _, _) = Main.configured(bare).fold(e => fail(e.message), identity)
    assertEquals(fromFile.localSocket, Some(Path.of("/s/node.socket")))
    assertEquals(fromFile.db, Some(Path.of("/d")))
    assertEquals(fromFile.mempoolBytes, Some(3000000L))
    val flagged = bare.copy(db = Some(Path.of("/mine")), mempoolBytes = Some(5L))
    val (overridden, _, _) = Main.configured(flagged).fold(e => fail(e.message), identity)
    assertEquals((overridden.db, overridden.mempoolBytes), (Some(Path.of("/mine")), Some(5L)))
    // a topology that names no peer, and no --peer: nothing to follow
    val empty = Files.createTempFile("topology", ".json")
    Files.writeString(
      empty,
      """{"localRoots": [], "publicRoots": [], "bootstrapPeers": null}"""
    )
    val nobody = Main
      .parse(List("follow", "--config", config.toString, "--topology", empty.toString))
      .toOption
      .get
    assert(Main.configured(nobody).left.exists(_.message.contains("no peer to follow")))
    // the same settings in a genesis directory's configuration are not applied
    val directoryMode = Main
      .parse(List("follow", "--genesis-dir", withSettings.toString, "--peer", "127.0.0.1:9"))
      .toOption
      .get
    val (plain, _, _) = Main.configured(directoryMode).fold(e => fail(e.message), identity)
    assertEquals((plain.localSocket, plain.db, plain.mempoolBytes), (None, None, None))
  }
