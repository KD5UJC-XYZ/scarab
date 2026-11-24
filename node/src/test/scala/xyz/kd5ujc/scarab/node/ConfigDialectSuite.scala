// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyList
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigNumberField, ConfigProblem}
import xyz.kd5ujc.scarab.network.selection.ConsensusMode
import xyz.kd5ujc.scarab.node.log.{EventKind, NodeEvent, Render, Traced}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The two dialects of a node configuration: the flat one, every key at the top level, which the
  * node reads, and the cardano-config envelope of cardano-node 11.2.1, every setting nested under
  * `Configuration`, which it does not read yet. An envelope is refused wherever the reference's
  * dialect test (`classifyConfigurationFile`) applies, never read as an empty configuration; the
  * keys of a genesis directory's configuration that the node does not read are reported.
  */
class ConfigDialectSuite extends FunSuite:
  private val preview = Path.of("fixtures", "preview", "genesis")
  private def text(file: String) = Files.readString(preview.resolve(file))
  private val flat = parser.parse(text("config.json")).fold(e => fail(e.message), identity)
  private def withKey(key: String, value: Json) = flat.deepMerge(Json.obj(key -> value)).noSpaces

  /** cardano-node 90bc1cd9 (11.2.1) `configuration/cardano/mainnet-config.json`, the file the
    * release ships, with its `HermodTracing.TraceOptions` cut to the root entry.
    */
  private val envelope =
    """{
      |  "$schema": "https://raw.githubusercontent.com/IntersectMBO/cardano-config/v2/schemas/config.schema.json",
      |  "Configuration": {
      |    "Consensus": { "ConsensusMode": "GenesisMode" },
      |    "HermodTracing": {
      |      "TraceOptionForwarder": { "maxReconnectDelay": 30, "queueSize": 128 },
      |      "TraceOptionMetricsPrefix": "cardano.node.metrics.",
      |      "TraceOptionResourceFrequency": 1000,
      |      "TraceOptions": {
      |        "": {
      |          "backends": ["EKGBackend", "Forwarder", "PrometheusSimple suffix 127.0.0.1 12798", "Stdout HumanFormatColoured"],
      |          "detail": "DNormal",
      |          "severity": "Notice"
      |        }
      |      }
      |    },
      |    "Protocol": {
      |      "AlonzoGenesisFile": "mainnet-alonzo-genesis.json",
      |      "AlonzoGenesisHash": "7e94a15f55d1e82d10f09203fa1d40f8eede58fd8066542cf6566008068ed874",
      |      "ByronGenesisFile": "mainnet-byron-genesis.json",
      |      "ByronGenesisHash": "5f20df933584822601f9e3f8c024eb5eb252fe8cefb24d1317dc3d432e940ebb",
      |      "CheckpointsFile": "mainnet-checkpoints.json",
      |      "CheckpointsFileHash": "3e6dee5bae7acc6d870187e72674b37c929be8c66e62a552cf6a876b1af31ade",
      |      "ConwayGenesisFile": "mainnet-conway-genesis.json",
      |      "ConwayGenesisHash": "15a199f895e461ec0ffc6dd4e4028af28a492ab4e806d39cb674c88f7643ef62",
      |      "RequiresNetworkMagic": "RequiresNoMagic",
      |      "ShelleyGenesisFile": "mainnet-shelley-genesis.json",
      |      "ShelleyGenesisHash": "1a3be38bcbb7911969283716ad7aa550250226b76a61fc51cc9a9a35d9276d81"
      |    },
      |    "Storage": {
      |      "LedgerDB": {
      |        "Backend": "V2InMemory",
      |        "QueryBatchSize": 100000,
      |        "Snapshots": { "Interval": 86400, "MaxDelay": 3600, "MinDelay": 300, "NumOfDiskSnapshots": 2, "Offset": 0 }
      |      }
      |    }
      |  },
      |  "MinNodeVersion": "11.2.0",
      |  "Version": 2
      |}""".stripMargin

  private val refused: Either[ConfigProblem, Unit] = Left(ConfigProblem.CardanoConfigEnvelope)
  private def problem[A](result: Either[ConfigError, A]) = result.left.map(_.problem).map(_ => ())

  /** A genesis directory: the preview genesis files with `config` as its `config.json`. */
  private def directory(config: String): Path =
    val dir = Files.createTempDirectory("config-dialect")
    List("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json")
      .foreach(name => Files.copy(preview.resolve(name), dir.resolve(name)))
    Files.writeString(dir.resolve("config.json"), config)
    dir

  test("a genesis directory refuses the 11.2.1 envelope under every build, never reads it empty") {
    val dir = directory(envelope)
    ReferenceBuild.values.foreach { build =>
      assertEquals(problem(NodeSetup.fromDirectory(build)(dir)), refused, build.name)
      assertEquals(
        problem(NodeConfig.parse(build)(envelope)),
        refused,
        s"${build.name}: the genesis directory's reading"
      )
    }
    val options = Main
      .parse(List("follow", "--genesis-dir", dir.toString, "--peer", "127.0.0.1:9"))
      .fold(e => fail(e.message), identity)
    assertEquals(problem(Main.nodeSetup(options)), refused, "--genesis-dir")
    assertEquals(problem(Main.configured(options)), refused, "--genesis-dir, completed")
  }

  test("with --config the envelope is refused as the selected build's node tells it apart") {
    val file = directory(envelope).resolve("config.json")
    // cardano-node 11.1.3 reads every file flat and stops at the first required key it misses
    assertEquals(
      problem(NodeSetup.readConfig(ReferenceBuild.Release11_1)(file)),
      Left(ConfigProblem.MissingField("configuration", "ByronGenesisFile (or GenesisFile)")),
      "11.1"
    )
    // 11.2.1 and the Leios build tell the envelope by its Configuration key; it is not read yet
    List(ReferenceBuild.Release11_2, ReferenceBuild.Leios).foreach { build =>
      assertEquals(problem(NodeSetup.readConfig(build)(file)), refused, build.name)
      assertEquals(
        problem(NodeSetup.fromConfigFile(build)(file)),
        refused,
        s"${build.name}: the setup"
      )
    }
    val options = Main
      .parse(
        List(
          "follow",
          "--config",
          file.toString,
          "--peer",
          "127.0.0.1:9",
          "--reference-build",
          ReferenceBuild.Leios.name
        )
      )
      .fold(e => fail(e.message), identity)
    assertEquals(problem(Main.nodeSetup(options)), refused, "--config, Leios build")
  }

  test(
    "the dialect is told by the Configuration key, whatever its value, as the reference tells it"
  ) {
    List(Json.Null, Json.fromString("flat"), Json.obj(), Json.arr()).foreach { value =>
      val marked = withKey("Configuration", value)
      ReferenceBuild.values.foreach { build =>
        assertEquals(problem(NodeConfig.parse(build)(marked)), refused, s"${build.name}: $value")
      }
      List(ReferenceBuild.Release11_2, ReferenceBuild.Leios).foreach { build =>
        assertEquals(
          problem(NodeConfig.parse(build)(marked, strict = true)),
          refused,
          s"${build.name}, strict: $value"
        )
      }
      // a flat file with the key beside it is what cardano-node 11.1.3 reads: the key is not one of
      // its keys
      val hybrid = NodeConfig
        .parse(ReferenceBuild.Release11_1)(marked, strict = true)
        .fold(e => fail(e.message), identity)
      assertEquals(
        hybrid.copy(ignoredKeys = Nil),
        NodeConfig
          .parse(ReferenceBuild.Release11_1)(flat.noSpaces, strict = true)
          .fold(e => fail(e.message), identity)
          .copy(ignoredKeys = Nil),
        s"11.1, strict: $value"
      )
      assert(hybrid.ignoredKeys.contains("Configuration"), hybrid.ignoredKeys)
    }
    // the key's name is matched exactly
    val lower = withKey("configuration", Json.obj())
    ReferenceBuild.values.foreach { build =>
      assertEquals(problem(NodeConfig.parse(build)(lower)), Right(()), build.name)
    }
  }

  test("the flat dialect still reads: the public files and every fixture configuration") {
    val strict = NodeConfig
      .parse(ReferenceBuild.Default)(text("config.json"), strict = true)
      .fold(e => fail(e.message), identity)
    val lenient = NodeConfig
      .parse(ReferenceBuild.Default)(text("config.json"))
      .fold(e => fail(e.message), identity)
    assertEquals(strict.settings.consensusMode, ConsensusMode.GenesisMode)
    assertEquals(lenient, strict)
    val setup = NodeSetup.fromDirectory(ReferenceBuild.Default)(preview)
    assertEquals(setup.map(_.config.hashes), Right(strict.hashes))
    List(
      "fixtures/mainnet/genesis/config.json",
      "fixtures/preprod/genesis/config.json",
      "fixtures/dijkstra-devnet/genesis/config.json",
      "fixtures/leios/testnet/config/config.json",
      "fixtures/leios/state/genesis/config.json",
      "fixtures/eras/genesis/configuration.yaml",
      "fixtures/chain-dep/genesis/configuration.yaml",
      "fixtures/devnet/lsq/genesis/configuration.yaml"
    ).foreach { file =>
      ReferenceBuild.values.foreach { build =>
        assert(
          NodeConfig.parse(build)(Files.readString(Path.of(file))).isRight,
          s"$file, ${build.name}"
        )
      }
    }
  }

  test("a genesis directory's keys the node does not read are reported, in order; --config's not") {
    val unread = NonEmptyList.of(
      "MinNodeVersion",
      "TraceOptionForwarder",
      "TraceOptionMetricsPrefix",
      "TraceOptionResourceFrequency",
      "TraceOptions"
    )
    List(false, true).foreach { strict =>
      assertEquals(
        NodeConfig.parse(ReferenceBuild.Default)(text("config.json"), strict).map(_.ignoredKeys),
        Right(unread.toList),
        s"strict: $strict"
      )
    }
    def reported(arguments: List[String]) =
      val options = Main
        .parse("follow" :: arguments ++ List("--peer", "127.0.0.1:9"))
        .fold(e => fail(e.message), identity)
      Main.nodeSetup(options).map(Main.ignoredKeysReported(options, _))
    assertEquals(reported(List("--genesis-dir", preview.toString)), Right(Some(unread)))
    assertEquals(reported(List("--config", preview.resolve("config.json").toString)), Right(None))
    assertEquals(
      reported(
        List("--config", preview.resolve("config.json").toString, "--genesis-dir", preview.toString)
      ),
      Right(None),
      "--genesis-dir beside --config is where the genesis files are looked up"
    )
    // a misspelt key is reported and not read
    val misspelt = NodeConfig
      .parse(ReferenceBuild.Default)(withKey("ConsensusMod", Json.fromString("PraosMode")))
      .fold(e => fail(e.message), identity)
    assert(misspelt.ignoredKeys.contains("ConsensusMod"), misspelt.ignoredKeys)
    assertEquals(misspelt.settings.consensusMode, ConsensusMode.GenesisMode)
    // a key the node does not read is not read, whatever its value
    val anyValue =
      NodeConfig.parse(ReferenceBuild.Default)(withKey("ChainSyncIdleTimeout", Json.arr()))
    assertEquals(anyValue.map(_.ignoredKeys.contains("ChainSyncIdleTimeout")), Right(true))
    // in the file's order
    assertEquals(
      NodeConfig
        .parse(ReferenceBuild.Default)("""{"Zeta": 1, "Protocol": "Cardano", "Alpha": {}}""")
        .map(_.ignoredKeys),
      Right(List("Zeta", "Alpha"))
    )
    assertEquals(NodeConfig.parse(ReferenceBuild.Default)("{}").map(_.ignoredKeys), Right(Nil))
    // the start-up warning names them
    val line = summon[Traced[NodeEvent]].line(
      NodeEvent.ConfigKeysIgnored(NonEmptyList.of("MinNodeVersion", "TraceOptions"))
    )
    assertEquals(line.kind, EventKind.ConfigKeysIgnored)
    assertEquals(
      Render.json(line, java.time.Instant.EPOCH),
      """{"event":"configKeysIgnored","at":"1970-01-01T00:00:00Z","keys":["MinNodeVersion","TraceOptions"]}"""
    )
  }

  test("only a declared key can be read: an undeclared one does not compile") {
    assert(
      compileErrors(
        """(f: ConfigFields[NodeConfig.TopLevelKey]) => f.value("MaxOutboundConnections")"""
      ).isEmpty
    )
    assert(
      compileErrors(
        """(f: ConfigFields[NodeConfig.TopLevelKey]) => f.value("TraceOptions")"""
      ).nonEmpty,
      "a key the parser does not declare is not readable"
    )
    assert(
      compileErrors(
        """(f: ConfigFields[NodeConfig.TopLevelKey], key: String) => f.value(key)"""
      ).nonEmpty,
      "nor is a key computed at run time"
    )
  }

  test("every declared key is read: a value no reader takes is refused") {
    val enabled = flat
      .deepMerge(
        Json.obj(
          "ExperimentalHardForksEnabled" -> Json.True,
          "DijkstraGenesisFile" -> Json.fromString("dijkstra-genesis.json")
        )
      )
    assert(NodeConfig.parse(ReferenceBuild.Default)(enabled.noSpaces).isRight)
    NodeConfig.TopLevelKeys.toList.sorted.foreach { key =>
      val wrong = enabled.deepMerge(Json.obj(key -> Json.arr())).noSpaces
      assert(
        NodeConfig.parse(ReferenceBuild.Default)(wrong) match {
          case Left(
                ConfigError(
                  ConfigProblem.Ancillary(
                    xyz.kd5ujc.scarab.node.ConfigFieldError.NotDouble(_, _, _)
                  )
                )
              ) | Left(
                ConfigError(
                  ConfigProblem.Ancillary(xyz.kd5ujc.scarab.node.ConfigFieldError.Number(_, _, _))
                )
              ) | Left(
                ConfigError(
                  ConfigProblem
                    .ConfigNumber(_, xyz.kd5ujc.scarab.ledger.json.NumberError.TypeMismatch(_))
                )
              ) | Left(
                ConfigError(
                  ConfigProblem.ConfigNumber(
                    xyz.kd5ujc.scarab.ledger.ConfigNumberField.DijkstraHardForkAtEpoch,
                    xyz.kd5ujc.scarab.ledger.json.NumberError.TypeMismatch(_)
                  )
                )
              ) | Left(
                ConfigError(
                  ConfigProblem.ConfigNumber(
                    xyz.kd5ujc.scarab.ledger.ConfigNumberField.DijkstraHardForkAtVersion,
                    xyz.kd5ujc.scarab.ledger.json.NumberError.TypeMismatch(_)
                  )
                )
              ) | Left(ConfigError(ConfigProblem.ExpectedObject(_, _))) |
              Left(ConfigError(ConfigProblem.JsonField(_, _, _))) |
              Left(ConfigError(ConfigProblem.MempoolCapacity(_))) =>
            true
          case _ => false
        },
        key
      )
    }
  }

  test("a hard-fork trigger is read by the key its number field names") {
    NodeConfig.HardForks.foreach { (era, epoch, version) =>
      assertEquals[String, String](ConfigNumberField.HardForkAtEpoch(era).key, epoch)
      assertEquals[String, String](ConfigNumberField.HardForkAtVersion(era).key, version)
    }
    assert(NodeConfig.TopLevelKeys(ConfigNumberField.DijkstraHardForkAtEpoch.key))
    assert(NodeConfig.TopLevelKeys(ConfigNumberField.DijkstraHardForkAtVersion.key))
  }

  test("the node's own key MaxOutboundConnections is read in a genesis directory, not ignored") {
    val config = NodeConfig
      .parse(ReferenceBuild.Default)(withKey("MaxOutboundConnections", Json.fromInt(50)))
      .fold(e => fail(e.message), identity)
    assertEquals(config.settings.maxOutboundConnections.map(_.value), Some(50))
    assert(!config.ignoredKeys.contains("MaxOutboundConnections"), config.ignoredKeys)
  }
