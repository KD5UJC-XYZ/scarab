// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import scala.annotation.unused
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import io.circe.Json
import io.circe.parser
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NtnVersion}
import xyz.kd5ujc.scarab.network.selection.{AfterSlot, ConsensusMode, Targets, UseLedgerPeers}
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

  test("the public configurations offer the latest release, 15; experimental protocols add 16") {
    Vector("preview", "preprod", "mainnet").foreach { network =>
      assertEquals(parsed(network).settings.maxNtnVersion, NtnVersion.V15, network)
    }
    assertEquals(ConfigSettings().maxNtnVersion, NtnVersion.V15)
    assertEquals(ConfigSettings(experimentalProtocolsEnabled = true).maxNtnVersion, NtnVersion.V16)
  }

  test("the public configurations parse as the reference parses them") {
    val preview = parsed("preview")
    assertEquals(preview.files.byron, "byron-genesis.json")
    assertEquals(preview.files.conway, "conway-genesis.json")
    assertEquals(preview.hardForkEpochs.get(Era.Shelley), Some(BigInt(0)))
    assertEquals(preview.hardForkEpochs.get(Era.Alonzo), Some(BigInt(0)))
    assertEquals(preview.requiresNetworkMagic, Some(true))
    assertEquals(preview.settings.lastKnownBlockVersion, Some((3, 1, 0)))
    assertEquals(preview.settings.experimentalHardForksEnabled, false)
    assertEquals(preview.settings.consensusMode, ConsensusMode.GenesisMode)
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
      val result = NodeSetup.verifyHashes(config, read(network))
      assertEquals(result, Right(()), network)
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

  test("the mainnet Byron hash is verified against the complete pinned public file") {
    assertEquals(NodeSetup.verifyHashes(parsed("mainnet"), read("mainnet")), Right(()))
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
            case Left(ConfigError(ConfigProblem.GenesisHashMismatch(r, f, expected, actual))) =>
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
    assertEquals(
      NodeConfig.parse(short).left.map(_.problem),
      Left(ConfigProblem.ByteLength("ShelleyGenesisHash", 32, 2))
    )
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
        NodeConfig.parse(without(key), strict = true).left.exists {
          case ConfigError(ConfigProblem.MissingField("configuration", field)) =>
            field == key || field == s"$key (or GenesisFile)"
          case _ => false
        },
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
    assertEquals(
      NodeConfig.parse(both.noSpaces).left.map(_.problem),
      Left(ConfigProblem.ConflictingFields(Set("ByronGenesisFile", "GenesisFile")))
    )
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
        .exists(_.problem == ConfigProblem.MissingField("configuration", "DijkstraGenesisFile"))
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
    // Global fetching-peer targets; preferred-peer handover follows the reference.
    assertEquals(
      NodeConfig
        .parse(with_("MaxConcurrencyDeadline", Json.fromInt(2)))
        .map(_.settings.maxConcurrencyDeadline),
      Right(Some(2))
    )
    assertEquals(parsed("preview").settings.maxConcurrencyDeadline, None)
    assertEquals(
      NodeConfig
        .parse(with_("MaxConcurrencyDeadline", Json.fromInt(0)))
        .map(_.settings.maxConcurrencyDeadline),
      Right(Some(0))
    )
    assertEquals(
      NodeConfig.parse(with_("MaxConcurrencyDeadline", Json.fromInt(-1))).left.toOption,
      Some(ConfigError(ConfigProblem.Negative("MaxConcurrencyDeadline", BigDecimal(-1))))
    )
    assertEquals(
      NodeConfig
        .parse(with_("MaxConcurrencyBulkSync", Json.fromInt(3)))
        .map(_.settings.maxConcurrencyBulkSync),
      Right(Some(3))
    )
    assertEquals(
      NodeConfig
        .parse(with_("MaxConcurrencyBulkSync", Json.fromInt(0)))
        .map(_.settings.maxConcurrencyBulkSync),
      Right(Some(0))
    )
    assertEquals(
      NodeConfig.parse(with_("MaxConcurrencyBulkSync", Json.fromInt(-1))).left.toOption,
      Some(ConfigError(ConfigProblem.Negative("MaxConcurrencyBulkSync", BigDecimal(-1))))
    )
    assert(NodeConfig.parse("[]").isLeft)
    assert(NodeConfig.parse("{").isLeft)
  }

  test("Genesis sync targets and trusted-state threshold are typed settings") {
    val input = Json.obj(
      "SyncTargetNumberOfRootPeers" -> Json.fromInt(0),
      "SyncTargetNumberOfKnownPeers" -> Json.fromInt(40),
      "SyncTargetNumberOfEstablishedPeers" -> Json.fromInt(8),
      "SyncTargetNumberOfActivePeers" -> Json.fromInt(4),
      "SyncTargetNumberOfKnownBigLedgerPeers" -> Json.fromInt(50),
      "SyncTargetNumberOfEstablishedBigLedgerPeers" -> Json.fromInt(20),
      "SyncTargetNumberOfActiveBigLedgerPeers" -> Json.fromInt(10),
      "MinBigLedgerPeersForTrustedState" -> Json.fromInt(3)
    )
    val settings =
      NodeConfig.parse(input.noSpaces).fold(error => fail(error.message), identity).settings
    val expected = Targets.validated(0, 40, 8, 4, 50, 20, 10).fold(e => fail(e.message), identity)
    assertEquals(
      settings.syncTargets(Targets.sync),
      Right(expected)
    )
    assertEquals(settings.minBigLedgerPeersForTrustedState, 3)
  }

  test(
    "the public topologies: the peers are the bootstrap peers, the ledger peers start at a slot"
  ) {
    val preview =
      Topology.parse(text("preview", "topology.json")).fold(e => fail(e.message), identity)
    assertEquals(preview.peers.map(_.toString), Vector("preview-node.play.dev.cardano.org:3001"))
    assertEquals(preview.useLedgerAfterSlot, UseLedgerPeers.Use(AfterSlot.After(119231973)))
    assertEquals(preview.peerSnapshotFile, Some("peer-snapshot.json"))
    assertEquals(preview.localRoots.map(_.accessPoints), Vector(Vector.empty))
    assertEquals(preview.localRoots.map(_.hotValency), Vector(1))
    val mainnet =
      Topology.parse(text("mainnet", "topology.json")).fold(e => fail(e.message), identity)
    assertEquals(
      mainnet.peers.map(_.toString),
      Vector("backbone.cardano.iog.io:3001", "backbone.mainnet.cardanofoundation.org:3001")
    )
    assertEquals(mainnet.useLedgerAfterSlot, UseLedgerPeers.Use(AfterSlot.After(194140785)))
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
    assertEquals(topology.useLedgerAfterSlot, UseLedgerPeers.Use(AfterSlot.Always))
  }

  test("the public roots are the peers only when nothing else names one") {
    val topology = Topology
      .parse(
        """{"localRoots": [], "publicRoots": [{"accessPoints": [{"address": "public.example", "port": 3004}]}], "bootstrapPeers": null}"""
      )
      .fold(e => fail(e.message), identity)
    assertEquals(topology.peers.map(_.toString), Vector("public.example:3004"))
    assertEquals(topology.bootstrapPeers, None)
    assertEquals(topology.useLedgerAfterSlot, UseLedgerPeers.Disabled)
  }

  test("public and bootstrap topology roots accept SRV access points") {
    val topology = Topology
      .parse(
        """{"localRoots": [], "publicRoots": [{"accessPoints": [{"address": "relay.example"}]}], "bootstrapPeers": [{"address": "boot.example"}]}"""
      )
      .fold(e => fail(e.message), identity)
    assertEquals(
      topology.publicRoots.flatMap(_.srvAccessPoints).map(_.value),
      Vector("relay.example.")
    )
    assertEquals(topology.bootstrapSrvPeers.map(_.value), Vector("boot.example."))
  }

  test("a negative slot disables ledger peers; the legacy Producers format is unsupported") {
    val negative =
      Topology.parse("""{"localRoots": [], "publicRoots": [], "useLedgerAfterSlot": -1}""")
    assertEquals(negative.map(_.useLedgerAfterSlot), Right(UseLedgerPeers.Disabled))
    val legacy =
      Topology.parse("""{"Producers": [{"addr": "127.0.0.1", "port": 3001, "valency": 1}]}""")
    assertEquals(legacy.left.map(_.problem), Left(ConfigProblem.UnsupportedTopology))
  }

  test("topology validates bad ports and addresses, missing fields, and no way to progress") {
    def topology(extra: String) =
      Topology.parse(s"""{"localRoots": [$extra], "publicRoots": []}""")
    def group(point: String) = s"""{"accessPoints": [$point], "valency": 1}"""
    // An address without a port is an SRV name and is accepted for every root class.
    val srv =
      Topology.parse(
        """{"localRoots": [{"accessPoints": [{"address": "local.example"}], "valency": 1}], "publicRoots": []}"""
      )
    val parsed = srv.fold(e => fail(e.message), identity)
    assertEquals(parsed.localRoots.head.srvAccessPoints.map(_.value), Vector("local.example."))
    val roots = PeerNetwork.roots(Nil, Some(parsed))
    assertEquals(roots.localSrv.head.map(_._1.value), List("local.example."))
    assert(roots.hasPeers)
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

  test("an unreadable topology retains the path and original file-system cause") {
    val path = Path.of("/nonexistent/topology.json")
    assert(Topology.read(path).left.exists {
      case ConfigError(ConfigProblem.FileRead(file, cause: java.nio.file.NoSuchFileException)) =>
        file == path.toString && cause.getFile == path.toString
      case _ => false
    })
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
    val praos = Files.createTempDirectory("config-suite-praos")
    List("byron", "shelley", "alonzo", "conway").foreach { n =>
      Files.copy(dir("preview").resolve(s"$n-genesis.json"), praos.resolve(s"$n-genesis.json"))
    }
    Files.copy(dir("preview").resolve("checkpoints.json"), praos.resolve("checkpoints.json"))
    val preview = parser
      .parse(text("preview", "config.json"))
      .fold(error => fail(error.toString), identity)
    Files.writeString(
      praos.resolve("config.json"),
      preview.deepMerge(Json.obj("ConsensusMode" -> Json.fromString("PraosMode"))).noSpaces
    )
    val config = praos.resolve("config.json")
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
    val topologyA = Files.createTempFile("topology-a", ".json")
    val topologyB = Files.createTempFile("topology-b", ".json")
    Files.writeString(
      topologyA,
      """{"localRoots": [], "publicRoots": [{"accessPoints": [{"address": "a.example", "port": 3001}]}]}"""
    )
    Files.writeString(
      topologyB,
      """{"localRoots": [], "publicRoots": [{"accessPoints": [{"address": "b.example", "port": 3002}]}]}"""
    )
    val requestedA = options.copy(topologyFile = Some(topologyA))
    val (_, reloadSetup, _) = Main.configured(requestedA).fold(e => fail(e.message), identity)
    val (_, rootsA) = Main
      .topologyRoots(requestedA, reloadSetup)
      .fold(e => fail(e.message), identity)
    val requestedB = requestedA.copy(topologyFile = Some(topologyB))
    val (_, rootsB) = Main
      .topologyRoots(requestedB, reloadSetup)
      .fold(e => fail(e.message), identity)
    assertEquals(rootsA.public.map(_.toString), Set("a.example:3001"))
    assertEquals(rootsB.public.map(_.toString), Set("b.example:3002"))
    assertEquals(
      rootsB.local.flatMap(_.members.map(_._1.toString)),
      List("127.0.0.1:9")
    )
    // the file's defaults for the flags (here none are set), and a flag wins over the file
    val withSettings = Files.createTempDirectory("config-suite-settings")
    Files.copy(dir("preview").resolve("checkpoints.json"), withSettings.resolve("checkpoints.json"))
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
    assertEquals(Main.configured(nobody).left.map(_.problem), Left(ConfigProblem.NoPeers))
    val bootstrap = Files.createTempFile("genesis-topology", ".json")
    Files.writeString(
      bootstrap,
      """{"localRoots": [], "publicRoots": [], "bootstrapPeers": [{"address":"127.0.0.1","port":3001}]}"""
    )
    val genesisMode = Main
      .parse(
        List(
          "follow",
          "--config",
          dir("preview").resolve("config.json").toString,
          "--topology",
          bootstrap.toString
        )
      )
      .fold(error => fail(error.message), identity)
    assertEquals(
      Main.configured(genesisMode).left.map(_.problem),
      Left(ConfigProblem.BootstrapPeersInGenesisMode)
    )
    // the same settings in a genesis directory's configuration are not applied
    val directoryMode = Main
      .parse(List("follow", "--genesis-dir", withSettings.toString, "--peer", "127.0.0.1:9"))
      .toOption
      .get
    val (plain, _, _) = Main.configured(directoryMode).fold(e => fail(e.message), identity)
    assertEquals((plain.localSocket, plain.db, plain.mempoolBytes), (None, None, None))
  }
