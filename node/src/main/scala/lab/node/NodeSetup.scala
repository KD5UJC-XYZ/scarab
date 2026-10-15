// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.syntax.all.*
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import lab.Blake2b
import lab.cbor.Bytes
import lab.node.consensus.{ByronRules, ByronRulesConfig, HardForkConfig, Praos, PraosConfig}
import lab.node.ledger.{GenesisState, LedgerGlobals, LiteLedger, Rational}
import lab.node.ledger.byron.ByronLedger
import lab.vrf.PraosLeaderThreshold

/** Everything the follower derives from a genesis directory before it sees a block: the era
  * history, the rule configuration and the initial node state. Pure given the file contents, so the
  * same assembly runs in the binary and in tests over committed fixtures.
  *
  * Directory layout: `shelley-genesis.json`, `alonzo-genesis.json`, `conway-genesis.json`,
  * `byron-genesis.json`, and optionally the node configuration (`configuration.yaml`,
  * `configuration.json` or `config.json`) for the Byron genesis hash and `Test*HardForkAtEpoch`.
  */
final case class NodeSetup(
    genesis: ShelleyGenesis,
    config: NodeConfig,
    byron: Option[ByronGenesis],
    globals: LedgerGlobals,
    praos: PraosConfig,
    start: NodeState,
    /** The Byron genesis parameters even when the chain has no Byron prefix (the era history of a
      * node-to-client client still lists the empty Byron era).
      */
    byronParams: Option[ByronGenesis] = None,
    byronConfig: Option[ByronRules.Config] = None,
    /** Which network this configuration describes (recorded in a database, see D5). */
    identity: NetworkIdentity
):
  def geometry: EpochGeometry = genesis.geometry
  def byronRules: Option[ByronRules.Config] = byronConfig

  /** The hash the chain's first block must name as its predecessor. */
  def origin: Option[Bytes] = byron.map(_.hash)

object NodeSetup:
  private val MainnetMagic = 764824073L

  val ConfigNames: List[String] = List("configuration.yaml", "configuration.json", "config.json")

  def fromDirectory(dir: Path): Either[ConfigError, NodeSetup] =
    def read(name: String): Option[Array[Byte]] =
      Option.when(Files.exists(dir.resolve(name)))(Files.readAllBytes(dir.resolve(name)))
    fromFiles(read)

  /** The setup `cardano-node run --config` would have: the configuration is read as the reference
    * reads it (`strict`), the genesis files it names are looked up next to it (or in `genesisDir`,
    * when given) and every hash it lists is verified.
    */
  def fromConfigFile(
      config: Path,
      genesisDir: Option[Path] = None
  ): Either[ConfigError, NodeSetup] =
    for
      bytes <- Either
        .catchNonFatal(Files.readAllBytes(config))
        .leftMap(e =>
          ConfigError(
            s"cannot read the configuration $config: ${e.getMessage}",
            ConfigProblem.FileMissing("configuration", config.toString)
          )
        )
      parsed <- NodeConfig.parse(new String(bytes, "UTF-8"), strict = true)
      base = genesisDir.getOrElse(Option(config.toAbsolutePath.getParent).getOrElse(Path.of(".")))
      read = (name: String) =>
        Option.when(Files.exists(base.resolve(name)))(Files.readAllBytes(base.resolve(name)))
      setup <- assemble(parsed, read)
    yield setup

  def fromFiles(read: String => Option[Array[Byte]]): Either[ConfigError, NodeSetup] =
    def text(bytes: Array[Byte]) = new String(bytes, "UTF-8")
    ConfigNames
      .collectFirst(Function.unlift(n => read(n)))
      .fold(Right(NodeConfig.empty))(b => NodeConfig.parse(text(b)))
      .flatMap(assemble(_, read))

  /** Check every hash the configuration lists against the genesis file it names: the Byron one is
    * the hash of the canonical JSON, the Shelley, Alonzo, Conway, Dijkstra and checkpoints ones the
    * blake2b-256 of the file's bytes. A mismatch is a `GenesisHashMismatch`. The four genesis files
    * the node needs must exist; the Dijkstra and checkpoints files are checked when present.
    */
  def verifyHashes(
      config: NodeConfig,
      read: String => Option[Array[Byte]]
  ): Either[ConfigError, Unit] =
    def hashOf(bytes: Array[Byte]) = Blake2b.hash256.hash(Bytes.fromArray(bytes))
    def check(
        role: String,
        file: String,
        expected: Option[Bytes],
        required: Boolean,
        actual: Array[Byte] => Either[ConfigError, Bytes]
    ): Either[ConfigError, Unit] =
      read(file) match
        case None =>
          Either.cond(
            !required,
            (),
            ConfigError(
              s"missing $role genesis file $file",
              ConfigProblem.FileMissing(role, file)
            )
          )
        case Some(bytes) =>
          expected.traverse_ { want =>
            actual(bytes).flatMap { got =>
              Either.cond(
                got == want,
                (),
                ConfigError(
                  s"$role genesis file $file has hash ${got.hex}, the configuration lists ${want.hex}",
                  ConfigProblem.GenesisHashMismatch(role, file, want.hex, got.hex)
                )
              )
            }
          }
    val byron = (bytes: Array[Byte]) =>
      parser
        .parse(new String(bytes, "UTF-8"))
        .leftMap(e => ConfigError(s"byron genesis is not JSON: ${e.message}"))
        .map(j => hashOf(ByronGenesisFile.canonical(j).getBytes("UTF-8")))
    val plain = (bytes: Array[Byte]) => Right(hashOf(bytes))
    val files = config.files
    val hashes = config.hashes
    for
      _ <- check("Byron", files.byron, hashes.byron, required = true, byron)
      _ <- check("Shelley", files.shelley, hashes.shelley, required = true, plain)
      _ <- check("Alonzo", files.alonzo, hashes.alonzo, required = true, plain)
      _ <- check("Conway", files.conway, hashes.conway, required = true, plain)
      _ <- files.dijkstra.traverse_(f =>
        check("Dijkstra", f, hashes.dijkstra, required = false, plain)
      )
      _ <- files.checkpoints.traverse_(f =>
        check("Checkpoints", f, hashes.checkpoints, required = false, plain)
      )
    yield ()

  /** The setup from a configuration and the genesis files it names (read by name). */
  def assemble(
      config: NodeConfig,
      read: String => Option[Array[Byte]]
  ): Either[ConfigError, NodeSetup] =
    val names = config.files
    def required(role: String, name: String): Either[ConfigError, Array[Byte]] =
      read(name).toRight(
        ConfigError(s"missing $name", ConfigProblem.FileMissing(role, name))
      )
    def text(bytes: Array[Byte]) = new String(bytes, "UTF-8")
    def json(name: String, bytes: Array[Byte]): Either[ConfigError, Json] =
      parser.parse(text(bytes)).leftMap(e => ConfigError(s"$name: ${e.message}"))
    for
      _ <- verifyHashes(config, read)
      shelleyBytes <- required("Shelley", names.shelley)
      shelleyBase <- Genesis.shelley(text(shelleyBytes))
      firstShelley = config.firstShelleyEpoch
      byronBytes <- required("Byron", names.byron)
      byron <-
        if firstShelley.contains(BigInt(0)) then Right(None)
        else ByronGenesisFile.parse(text(byronBytes), config.byronGenesisHash).map(Some(_))
      prefixed <- Genesis.withByronPrefix(shelleyBase, byron, firstShelley)
      // the Byron genesis of a chain that starts in Shelley is read only for its parameters
      byronParams = byron.orElse(ByronGenesisFile.parse(text(byronBytes), None).toOption)
      genesis = Genesis.withSystemStart(prefixed, byronParams)
      byronJson <- json(names.byron, byronBytes)
      // The network magic Byron addresses carry: the configuration says whether the network
      // requires one; without it, mainnet does not and the test networks do.
      requiresMagic = config.requiresNetworkMagic.getOrElse(
        byron.map(_.protocolMagic).getOrElse(genesis.networkMagic) != MainnetMagic
      )
      addressMagic = Option.when(requiresMagic)(
        byron.map(_.protocolMagic).getOrElse(genesis.networkMagic)
      )
      files <- (
        json(names.shelley, shelleyBytes),
        required("Alonzo", names.alonzo).flatMap(json(names.alonzo, _)),
        required("Conway", names.conway).flatMap(json(names.conway, _)),
        byronJson.asRight[ConfigError]
      ).mapN(GenesisState.Files(_, _, _, _, addressMagic))
      globals <- GenesisState.globals(files, genesis)
      initial <- GenesisState.initial(files, globals)
      f = genesis.geometry.activeSlotsCoeff
      active <- PraosLeaderThreshold.Fraction
        .checked(BigInt(f.bigDecimal.unscaledValue), BigInt(10).pow(f.bigDecimal.scale.max(0)))
        .leftMap(ConfigError(_))
      praos = PraosConfig(
        genesis.geometry,
        genesis.slotsPerKesPeriod,
        genesis.maxKesEvolutions,
        active,
        HardForkConfig.fromEpochs(config.hardForkEpochs)
      )
      lite <- LiteLedger.fromGenesis(files.shelley, firstShelley.getOrElse(BigInt(0)))
      byronRules <- byron.traverse { b =>
        val ledgerConfig = ByronLedger.Config(b, requiresMagic)
        ByronLedger.initial(ledgerConfig).leftMap(ConfigError(_)).map { ledger =>
          ledger -> ByronRulesConfig(
            ledgerConfig,
            config.pbftSignatureThreshold.getOrElse(NodeConfig.DefaultPBftSignatureThreshold),
            firstShelley,
            (state, g) => GenesisState.initial(files, g, Some(state.utxo)).leftMap(_.message)
          )
        }
      }
    yield NodeSetup(
      genesis,
      config,
      byron,
      globals,
      praos,
      NodeState(
        Praos.initial(Blake2b.hash256.hash(Bytes.fromArray(shelleyBytes))),
        initial,
        BootstrapState.initial(lite),
        byronRules.fold(ByronState.initial)((ledger, _) => ByronState.starting(ledger))
      ),
      identity = NetworkIdentity(
        genesis.networkMagic,
        Blake2b.hash256.hash(Bytes.fromArray(shelleyBytes)),
        byron.map(_.hash)
      ),
      byronParams = byronParams,
      byronConfig = byronRules.map(_._2)
    )
