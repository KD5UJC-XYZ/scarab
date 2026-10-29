// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{BootstrapState, ByronState, ConsensusExtras, HeaderCheckpoints}
import xyz.kd5ujc.scarab.consensus.state.{
  ByronMirror,
  ConsensusMirrorEnv,
  GenesisMirror,
  EraHistory,
  MirrorProvenance,
  MirrorState,
  ProtocolState
}
import xyz.kd5ujc.scarab.ledger.rules.{FromByron, GenesisInjections, GenesisStart, Globals}
import xyz.kd5ujc.scarab.ledger.state.MirrorEnv
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{
  ByronGenesis,
  ByronGenesisFile,
  ConfigError,
  ConfigProblem,
  EpochGeometry,
  GenesisFileHash,
  Genesis,
  NetworkIdentity,
  ProtocolParams,
  ShelleyGenesis
}
import cats.{Applicative, Monad}
import cats.mtl.Ask
import cats.syntax.all.*
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ByronRules, ByronRulesConfig, HardForkConfig, PraosConfig}
import xyz.kd5ujc.scarab.ledger.{
  GenesisSchema,
  GenesisState,
  LedgerDecodeError,
  LedgerGlobals,
  LiteLedger,
  ProviderDefect
}
import xyz.kd5ujc.scarab.ledger.byron.ByronLedger
import xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold

/** Everything the follower derives from a genesis directory before it sees a block: the era
  * history, the rule configuration and the initial node state. Pure given the file contents, so the
  * same assembly runs in the binary and in tests over committed fixtures.
  *
  * Directory layout: `shelley-genesis.json`, `alonzo-genesis.json`, `conway-genesis.json`,
  * `byron-genesis.json`, and optionally the node configuration (`configuration.yaml`,
  * `configuration.json` or `config.json`) for the Byron genesis hash and `Test*HardForkAtEpoch`.
  *
  * @param mirrorStart
  *   The state the chain starts from, as the mirror.
  * @param handOver
  *   What the Shelley genesis gives the ledger state a chain that begins in Byron enters Shelley
  *   with (`FromByronTranslationContext`, `Shelley/Translation.hs:46`): the genesis delegations,
  *   the protocol parameters and the supply. None for a chain that starts in Shelley.
  * @param byronParams
  *   The Byron genesis parameters even when the chain has no Byron prefix (the era history of a
  *   node-to-client client still lists the empty Byron era).
  * @param identity
  *   Which network this configuration describes (recorded in a database, see D5).
  */
final case class NodeSetup(
    genesis: ShelleyGenesis,
    config: NodeConfig,
    byron: Option[ByronGenesis],
    globals: LedgerGlobals,
    praos: PraosConfig,
    mirrorStart: MirrorState,
    handOver: Option[FromByron],
    byronParams: Option[ByronGenesis] = None,
    byronConfig: Option[ByronRules.Config] = None,
    identity: NetworkIdentity,
    chainStoreK: ChainStoreK
):
  def geometry: EpochGeometry = genesis.geometry
  def byronRules: Option[ByronRules.Config] = byronConfig

  /** What the ledger-state mirror reads of this configuration (`StateMirror`); `observed` is the
    * epoch in which each era was seen to begin, where the configuration does not say.
    */
  def mirrorEnv(observed: Map[Era, BigInt] = Map.empty): ConsensusMirrorEnv =
    NodeSetup.mirrorEnvOf(genesis, globals, praos, byronParams, observed)

  /** The mirror's environment as a capability: the configuration, with the eras seen to begin so
    * far read from `observed` at each use.
    */
  def mirrorAsk[F[_]](
      observed: F[Map[Era, BigInt]]
  )(using monad: Monad[F]): Ask[F, ConsensusMirrorEnv] =
    new Ask[F, ConsensusMirrorEnv]:
      val applicative: Applicative[F] = monad
      def ask[E2 >: ConsensusMirrorEnv]: F[E2] = monad.map(observed)(mirrorEnv(_))

  /** The Byron parameters of the era history, where the mirror's telescopes are placed. */
  def byronHistory: EraHistory.ByronParams = mirrorEnv().byron

  /** The hash the chain's first block must name as its predecessor. */
  def origin: Option[Bytes] = byron.map(_.hash)

/** The security parameter as the chain store and the volatile chain count blocks: an `Int`. A
  * larger one, which the reference's `NonZero Word64` holds, is refused at setup
  * (`SecurityParamBeyondStorage`): an approved deviation, recorded in #474.
  */
opaque type ChainStoreK = Int

object ChainStoreK:
  def from(k: BigInt): Either[ConfigError, ChainStoreK] =
    Either.cond(k.isValidInt, k.toInt, ConfigError(ConfigProblem.SecurityParamBeyondStorage(k)))

  extension (k: ChainStoreK) def blocks: Int = k

object NodeSetup:
  /** The environment of the mirror for a configuration. */
  private def mirrorEnvOf(
      genesis: ShelleyGenesis,
      globals: LedgerGlobals,
      praos: PraosConfig,
      byronParams: Option[ByronGenesis],
      observed: Map[Era, BigInt] = Map.empty
  ): ConsensusMirrorEnv =
    val geometry = genesis.geometry
    ConsensusMirrorEnv(
      MirrorEnv(globals.network, geometry, globals.updateQuorum),
      praos.forks,
      byronParams.fold(
        EraHistory.ByronParams(
          geometry.k * 10,
          BigInt(20000),
          geometry.k
        )
      )(b =>
        EraHistory.ByronParams(b.epochSlots, BigInt(b.slotLengthMillis), BigInt(b.securityParam))
      ),
      observed
    )

  private val MainnetMagic = 764824073L

  /** The start of a chain that begins in Byron: the Byron ledger of the genesis, with the
    * chain-dependent state Shelley starts from held beside it ([[ByronMirror]]). The reference
    * injects nothing into a Byron state (`perEraInjections`, ouroboros-consensus 82ecba32
    * `Cardano/Node.hs:957-959`); the Shelley state is made when the chain crosses into Shelley.
    */
  private def byronStart(
      env: ConsensusMirrorEnv,
      shelleyHash: Bytes,
      lite: LiteLedger,
      byron: ByronState
  ): Either[ConfigError, MirrorState] =
    val protocol = ProtocolState.initial(Era.Byron, shelleyHash)
    ByronMirror
      .of(
        env,
        None,
        protocol.dep,
        ConsensusExtras(BootstrapState.initial(lite), byron, protocol.extras),
        MirrorProvenance.complete
      )
      .leftMap(e => ConfigError(ConfigProblem.StateMirror(e)))

  /** The start of a chain whose first era `era` is Shelley-based: the Byron genesis UTxO carried
    * through the translations up to `era` and the genesis injections of that era
    * ([[GenesisStart.ledger]]), at the origin of its telescopes ([[GenesisMirror]]).
    */
  private def shelleyStart(
      env: ConsensusMirrorEnv,
      globals: Globals,
      files: GenesisState.Files,
      injections: GenesisInjections,
      fromByron: FromByron,
      era: Era,
      shelleyHash: Bytes,
      lite: LiteLedger
  ): Either[ConfigError, MirrorState] =
    val protocol = ProtocolState.initial(era, shelleyHash)
    for
      balances <- ByronGenesisFile.genesisBalances(files.byron)
      byronUtxo <- ByronLedger
        .genesisUtxo(balances._1, balances._2, files.byronNetworkMagic)
        .leftMap(e => ConfigError(ConfigProblem.LedgerValue(e)))
      ledger <- GenesisStart
        .ledger(globals, fromByron, byronUtxo, era, injections)
        .leftMap(e => ConfigError(ConfigProblem.GenesisStart(e)))
      mirror <- GenesisMirror
        .of(
          env,
          ledger,
          protocol.dep,
          ConsensusExtras(BootstrapState.initial(lite), ByronState.initial, protocol.extras)
        )
        .leftMap(e => ConfigError(ConfigProblem.StateMirror(e)))
    yield mirror

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
      parsed <- readConfig(config)
      base = genesisDir.getOrElse(Option(config.toAbsolutePath.getParent).getOrElse(Path.of(".")))
      read = (name: String) =>
        Option.when(Files.exists(base.resolve(name)))(Files.readAllBytes(base.resolve(name)))
      setup <- assemble(parsed, read)
    yield setup

  /** The configuration file alone, before any genesis file is read. */
  def readConfig(config: Path): Either[ConfigError, NodeConfig] =
    Either
      .catchNonFatal(Files.readAllBytes(config))
      .leftMap(e => ConfigError(ConfigProblem.FileRead(config.toString, e)))
      .flatMap(bytes => NodeConfig.parse(new String(bytes, "UTF-8"), strict = true))

  def fromFiles(read: String => Option[Array[Byte]]): Either[ConfigError, NodeSetup] =
    def text(bytes: Array[Byte]) = new String(bytes, "UTF-8")
    ConfigNames
      .collectFirst(Function.unlift(n => read(n)))
      .fold(Right(NodeConfig.empty))(b => NodeConfig.parse(text(b)))
      .flatMap(assemble(_, read))

  /** Check every hash the configuration lists against the genesis file it names: the Byron one is
    * the hash of the canonical JSON, the Shelley, Alonzo, Conway, Dijkstra and checkpoints ones the
    * blake2b-256 of the file's bytes. A mismatch is a `GenesisHashMismatch`. The four genesis files
    * the node needs must exist. A configured checkpoints file is also required; an optional
    * Dijkstra file is hash-checked when present.
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
            ConfigError(ConfigProblem.FileMissing(role, file))
          )
        case Some(bytes) =>
          expected.traverse_ { want =>
            actual(bytes).flatMap { got =>
              Either.cond(
                got == want,
                (),
                ConfigError(ConfigProblem.GenesisHashMismatch(role, file, want.hex, got.hex))
              )
            }
          }
    val byron = (bytes: Array[Byte]) =>
      parser
        .parse(new String(bytes, "UTF-8"))
        .leftMap(e => ConfigError(ConfigProblem.JsonSyntax("byron genesis", e)))
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
        check("Checkpoints", f, hashes.checkpoints, required = true, plain)
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
        ConfigError(ConfigProblem.FileMissing(role, name))
      )
    def text(bytes: Array[Byte]) = new String(bytes, "UTF-8")
    def json(name: String, bytes: Array[Byte]): Either[ConfigError, Json] =
      parser.parse(text(bytes)).leftMap(e => ConfigError(ConfigProblem.JsonSyntax(name, e)))
    for
      _ <- verifyHashes(config, read)
      checkpoints <- names.checkpoints
        .traverse(file =>
          required("Checkpoints", file)
            .flatMap(CheckpointsFile.parse(file, _, config.hashes.checkpoints))
        )
        .map(_.getOrElse(HeaderCheckpoints.empty))
      shelleyBytes <- required("Shelley", names.shelley)
      shelleyBase <- Genesis.shelley(text(shelleyBytes))
      // The chain store and the volatile chain count blocks in an `Int`: a larger security parameter
      // is refused, which the reference's `NonZero Word64` would accept. An approved deviation,
      // recorded in #474.
      chainStoreK <- ChainStoreK.from(shelleyBase.geometry.k)
      firstShelley = config.firstShelleyEpoch
      byronBytes <- required("Byron", names.byron)
      byron <-
        if firstShelley.contains(BigInt(0)) then Right(None)
        else ByronGenesisFile.parse(text(byronBytes), config.byronGenesisHash).map(Some(_))
      alonzoBytes <- required("Alonzo", names.alonzo)
      conwayBytes <- required("Conway", names.conway)
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
        json(names.alonzo, alonzoBytes),
        json(names.conway, conwayBytes),
        byronJson.asRight[ConfigError]
      ).mapN(GenesisState.Files(_, _, _, _, addressMagic))
      // each file as the reference node reads it at start-up, before any reader of ours
      _ <- GenesisSchema.shelley(files.shelley)
      _ <- GenesisSchema.alonzo(files.alonzo)
      _ <- GenesisSchema.conway(files.conway)
      injections <- GenesisInjections.read(files)
      globals <- GenesisState.globals(files, genesis)
      startsInConway = files.shelley.hcursor
        .downField("protocolParams")
        .downField("protocolVersion")
        .downField("major")
        .as[Int]
        .exists(_ >= 9)
      // Parse the supplied injection even when a Byron start will not apply it: the reference
      // reads AlonzoGenesis.extraConfig before perEraInjections chooses Byron's identity
      // (cardano-ledger f649f975 Alonzo/Genesis.hs:222-242; consensus 82ecba32 Cardano/Node.hs:957-959).
      // The hand-over uses translated parameters; only a Shelley-based start injects cost models
      // into its current parameters (GenesisStart.alonzoInjectCostModels).
      conwayFile = Option.when(startsInConway)(files.conway)
      _ <- ProtocolParams.fromGenesis(files.shelley, files.alonzo, conwayFile)
      params <- ProtocolParams.translatedFromGenesis(files.shelley, files.alonzo, conwayFile)
      f = genesis.geometry.activeSlotsCoeff
      active <- PraosLeaderThreshold.Fraction
        .checked(f.numerator, f.denominator)
        .leftMap(e => ConfigError(ConfigProblem.ActiveSlotsCoefficient(e)))
      praos = PraosConfig(
        genesis.geometry,
        genesis.slotsPerKesPeriod,
        genesis.maxKesEvolutions,
        active,
        HardForkConfig.fromEpochs(config.hardForkEpochs),
        checkpoints = checkpoints
      )
      lite <- LiteLedger.fromGenesis(files.shelley, firstShelley.getOrElse(BigInt(0)))
      byronRules <- byron.traverse { b =>
        val ledgerConfig = ByronLedger.Config(b, requiresMagic)
        ByronLedger
          .initial(ledgerConfig)
          .leftMap {
            case e: LedgerDecodeError => ConfigError(ConfigProblem.LedgerValue(e))
            case d: ProviderDefect    => ConfigError(ConfigProblem.GenesisUnverifiable(d))
          }
          .map { ledger =>
            ledger -> ByronRulesConfig(
              ledgerConfig,
              config.pbftSignatureThreshold.getOrElse(NodeConfig.DefaultPBftSignatureThreshold),
              firstShelley
            )
          }
      }
      genDelegs <- LiteLedger.fromGenesis(files.shelley).map(_.genDelegs)
      fromByron = FromByron(genDelegs, params, globals.maxSupply)
      env = mirrorEnvOf(genesis, globals, praos, byronParams)
      shelleyHash = Blake2b.hash256.hash(Bytes.fromArray(shelleyBytes))
      start <- byronRules match
        case Some((ledger, _)) => byronStart(env, shelleyHash, lite, ByronState.starting(ledger))
        case None =>
          shelleyStart(
            env,
            Globals(globals, genesis.geometry),
            files,
            injections,
            fromByron,
            praos.forks.eraAt(globals.firstEpoch, params.protocolMajor),
            shelleyHash,
            lite
          )
    yield NodeSetup(
      genesis,
      config,
      byron,
      globals,
      praos,
      start,
      byronRules.map(_ => fromByron),
      identity = NetworkIdentity(
        genesis.networkMagic,
        Blake2b.hash256.hash(Bytes.fromArray(shelleyBytes)),
        byron.map(_.hash),
        GenesisFileHash(
          Blake2b.hash256.hash(Bytes.fromArray(alonzoBytes))
        ),
        GenesisFileHash(
          Blake2b.hash256.hash(Bytes.fromArray(conwayBytes))
        )
      ),
      chainStoreK = chainStoreK,
      byronParams = byronParams,
      byronConfig = byronRules.map(_._2)
    )
