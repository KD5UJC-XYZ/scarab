// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{ByronState, ConsensusExtras, FollowState, HeaderCheckpoints}
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
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, StateLayout}
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
  ProviderDefect
}
import xyz.kd5ujc.scarab.ledger.byron.ByronLedger
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraGenesis
import xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold
import xyz.kd5ujc.scarab.storage.SnapshotPolicy
import xyz.kd5ujc.scarab.storage.haskell.ChunkSlots

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
    chainStoreK: ChainStoreK,
    ledgerConfig: Either[xyz.kd5ujc.scarab.node.ntc.LedgerConfig.Error, Bytes]
):
  def geometry: EpochGeometry = genesis.geometry
  def byronRules: Option[ByronRules.Config] = byronConfig

  /** Chain-wide defaults use Byron's k even with an empty Byron era (ouroboros-consensus 82ecba32,
    * `Cardano/Node.hs:847-869`, `Storage/LedgerDB/Snapshots.hs:639-642`).
    */
  def defaultSnapshotPolicy: SnapshotPolicy = SnapshotPolicy.reference(BigInt(chainStoreK.blocks))

  /** The HFC takes its chunk size from the first configured era, including when it is skipped
    * (82ecba32, `HardFork/Combinator/Node/InitStorage.hs:21-29`, `Byron/Node.hs:280-286`).
    */
  def immutableChunkSlots: Option[ChunkSlots] =
    ChunkSlots.fromSecurityParam(BigInt(chainStoreK.blocks))

  def retainedHeaders: Int = GenesisRuntime.retainedHeaders(geometry, BigInt(chainStoreK.blocks))

  /** The protocol version the node's forged headers carry: the configured one of the build whose
    * rules the ledger follows ([[ConfigSettings.protocolVersion]]), as `forgeShelleyBlock` takes
    * `shelleyProtocolVersion` of the block config (ouroboros-consensus 2416493b
    * `Shelley/Ledger/Forge.hs:144`).
    */
  def forgedProtocolVersion: (BigInt, BigInt) =
    config.settings.protocolVersion(globals.referenceBuild)

  /** This setup with its ledger following the rules of `build` ([[LedgerGlobals.referenceBuild]]).
    */
  def mirroring(build: ReferenceBuild): NodeSetup =
    copy(globals = globals.copy(referenceBuild = build))

  /** What the ledger-state mirror reads of this configuration (`StateMirror`); `observed` is the
    * epoch in which each era was seen to begin, where the configuration does not say.
    */
  def mirrorEnv(observed: Map[Era, BigInt] = Map.empty): ConsensusMirrorEnv =
    NodeSetup.mirrorEnvOf(
      genesis,
      globals,
      praos,
      byronParams,
      mirrorStart.ext.layout.stateLayout,
      observed
    )

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

  /** The time translation of the node's chain, placed by its era history parameters (the genesis
    * geometry, the Byron era's parameters and the hard-fork triggers): the mempool, the forger and
    * the blockchain time translate time with it as the block rules do.
    */
  def txClock: read.TxClock[MirrorState] =
    import read.ChainRead.given
    read.TxClock.of[MirrorState](geometry, byronHistory, praos.forks)

  /** The hash the chain's first block must name as its predecessor. */
  def origin: Option[Bytes] = byron.map(_.hash)

/** The security parameter as the chain store and the volatile chain count blocks: a positive `Int`,
  * Byron's genesis k. Zero is refused (`SecurityParamZero`), where the reference stops with
  * `error`. A value beyond `Int`, which the reference's `NonZero Word64` holds, is refused too
  * (`SecurityParamBeyondStorage`): an approved deviation, recorded in #474.
  */
opaque type ChainStoreK = Int

object ChainStoreK:
  def from(k: BigInt): Either[ConfigError, ChainStoreK] =
    for
      _ <- Either.cond(k.signum > 0, (), ConfigError(ConfigProblem.SecurityParamZero))
      blocks <- Either.cond(
        k.isValidInt,
        k.toInt,
        ConfigError(ConfigProblem.SecurityParamBeyondStorage(k))
      )
    yield blocks

  extension (k: ChainStoreK) def blocks: Int = k

object NodeSetup:
  /** The environment of the mirror for a configuration. */
  private def mirrorEnvOf(
      genesis: ShelleyGenesis,
      globals: LedgerGlobals,
      praos: PraosConfig,
      byronParams: Option[ByronGenesis],
      layout: StateLayout,
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
      )(b => EraHistory.ByronParams(b.epochSlots, b.slotLengthMillis, b.securityParam)),
      layout,
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
      byron: ByronState
  ): Either[ConfigError, MirrorState] =
    val protocol = ProtocolState.initial(Era.Byron, shelleyHash)
    ByronMirror
      .of(
        env,
        None,
        protocol.dep,
        ConsensusExtras(FollowState.initial, byron, protocol.extras),
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
      shelleyHash: Bytes
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
          ConsensusExtras(FollowState.initial, ByronState.initial, protocol.extras)
        )
        .leftMap(e => ConfigError(ConfigProblem.StateMirror(e)))
    yield mirror

  val ConfigNames: List[String] = List("configuration.yaml", "configuration.json", "config.json")

  def fromDirectory(build: ReferenceBuild)(dir: Path): Either[ConfigError, NodeSetup] =
    def read(name: String): Option[Array[Byte]] =
      Option.when(Files.exists(dir.resolve(name)))(Files.readAllBytes(dir.resolve(name)))
    fromFiles(build)(read)

  /** The setup `cardano-node run --config` would have: the configuration is read as the reference
    * reads it (`strict`), the genesis files it names are looked up next to it (or in `genesisDir`,
    * when given) and every hash it lists is verified.
    */
  def fromConfigFile(build: ReferenceBuild)(
      config: Path,
      genesisDir: Option[Path] = None
  ): Either[ConfigError, NodeSetup] =
    for
      parsed <- readConfig(build)(config)
      base = genesisDir.getOrElse(Option(config.toAbsolutePath.getParent).getOrElse(Path.of(".")))
      read = (name: String) =>
        Option.when(Files.exists(base.resolve(name)))(Files.readAllBytes(base.resolve(name)))
      setup <- assemble(build)(parsed, read)
    yield setup.mirroring(build)

  /** The configuration file alone, before any genesis file is read. */
  def readConfig(build: ReferenceBuild)(config: Path): Either[ConfigError, NodeConfig] =
    Either
      .catchNonFatal(Files.readAllBytes(config))
      .leftMap(e => ConfigError(ConfigProblem.FileRead(config.toString, e)))
      .flatMap(bytes => NodeConfig.parse(build)(new String(bytes, "UTF-8"), strict = true))

  def fromFiles(build: ReferenceBuild)(
      read: String => Option[Array[Byte]]
  ): Either[ConfigError, NodeSetup] =
    def text(bytes: Array[Byte]) = new String(bytes, "UTF-8")
    ConfigNames
      .collectFirst(Function.unlift(n => read(n)))
      .fold(NodeConfig.parse(build)("{}"))(b => NodeConfig.parse(build)(text(b)))
      .flatMap(assemble(build)(_, read))
      .map(_.mirroring(build))

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
    // `readGenesis` reads the Byron genesis whole before it compares its hash (cardano-node 11.1.3
    // `Cardano/Node/Protocol/Byron.hs:91-95`), so a genesis the schema refuses is refused for that
    val byron = (bytes: Array[Byte]) =>
      ByronGenesisFile.parse(Bytes.fromArray(bytes), None).map(_.hash)
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

  /** The setup from a configuration and the genesis files it names (read by name), its genesis read
    * and its ledger started as `build` reads and starts them.
    */
  def assemble(build: ReferenceBuild)(
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
      firstShelley = config.firstShelleyEpoch
      byronBytes <- required("Byron", names.byron)
      // The reference reads the whole Byron genesis whichever era the chain starts in
      // (cardano-node 11.1.3, `Cardano/Node/Protocol/Cardano.hs:113-124`).
      byronFile <- ByronGenesisFile.read(Bytes.fromArray(byronBytes))
      byronGenesis <- ByronGenesisFile.fromJson(byronFile._1, byronFile._2, config.byronGenesisHash)
      // Mirror the release node: Byron's k configures the HFC consensus and storage even when
      // Byron is skipped (ouroboros-consensus 82ecba32, `Cardano/Node.hs:669-670,847-869,920-929`).
      // Its equality assertion is compiled out unless enabled (`ouroboros-consensus.cabal:34-37,
      // 65-67`; cardano-node 11.1.3, `nix/haskell.nix:415-424`). Shelley geometry keeps its k:
      // TPraos/Praos and ledger Globals use sgSecurityParam (`Cardano/Node.hs:674-697, 795-798`;
      // cardano-ledger f649f975, `Shelley/Genesis.hs:768-789`). The existing Int storage limit
      // remains the approved deviation recorded in #474.
      chainStoreK <- ChainStoreK.from(byronGenesis.securityParam)
      byron = Option.unless(firstShelley.contains(BigInt(0)))(byronGenesis)
      alonzoBytes <- required("Alonzo", names.alonzo)
      conwayBytes <- required("Conway", names.conway)
      prefixed <- Genesis.withByronPrefix(shelleyBase, byron, firstShelley)
      genesis = Genesis.withSystemStart(prefixed, Some(byronGenesis))
      byronJson = byronFile._1
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
      // the Dijkstra genesis, read after the others as the reference reads it (cardano-node
      // b004fe0c `Protocol/Cardano.hs:137-144`); its hash was checked with theirs
      dijkstraBytes <- names.dijkstra.traverse(required("Dijkstra", _))
      dijkstra <- names.dijkstra
        .zip(dijkstraBytes)
        .traverse((name, bytes) => json(name, bytes).flatMap(DijkstraGenesis.read))
      // the genesis state is built in the build's layout: a build whose layout is not implemented
      // yet is refused here, before anything reads or writes a state of it
      layout <- StateLayout
        .of(build)
        .leftMap(e => ConfigError(ConfigProblem.ReferenceBuildNotYet(e)))
      injections <- GenesisInjections.read(layout)(files)
      globals <- GenesisState
        .globals(files, genesis)
        .map(_.copy(dijkstra = dijkstra, referenceBuild = build))
      // Dijkstra's trigger is carried as the reference carries it (`TestDijkstraHardForkAtEpoch`,
      // read with `ExperimentalHardForksEnabled`), and never fires below a maximum major protocol
      // version of 12 (`HardForkConfig.trigger`). The Leios build's maximum is the node's protocol
      // version (`maxMajorProtVer = MaxMajorProtVer $ pvMajor cardanoProtocolVersion`,
      // `Cardano/Node.hs:548-549`, ouroboros-consensus 2416493b): 12 with
      // `ExperimentalHardForksEnabled`, so its chain enters Dijkstra (owner decision on #54,
      // 2026-11-19); the 11.1 line's stays 11, with #788.
      maxMajor = build match
        case ReferenceBuild.LeiosB004fe0c => config.settings.protocolVersion(build)._1.toInt
        case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 =>
          HardForkConfig.MaxMajorProtVer
      forks = HardForkConfig.fromEpochs(
        config.hardForkEpochs ++ config.settings.dijkstraHardForkEpoch.map(Era.Dijkstra -> _),
        maxMajor
      )
      // The era the first slot is in: Byron unless Shelley starts at epoch 0, then the eras whose
      // transition the genesis state decides (`HardForkConfig.initialEra`), never the Shelley
      // genesis's protocol version.
      startEra = byron.fold(forks.initialEra(genesis.geometry.k)(globals.firstEpoch))(_ =>
        Era.Byron
      )
      startsInConway = startEra == Era.Conway
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
        forks,
        checkpoints = checkpoints
      )
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
      genDelegs <- GenesisState.genDelegs(files.shelley)
      fromByron = FromByron(genDelegs, params, globals.maxSupply)
      env = mirrorEnvOf(genesis, globals, praos, Some(byronGenesis), layout)
      shelleyHash = Blake2b.hash256.hash(Bytes.fromArray(shelleyBytes))
      // what `DebugLedgerConfig` answers with, written once from the configuration as the reference
      // reads it, whose `RequiresNetworkMagic` is `RequiresNoMagic` when absent (cardano-node 11.1.3
      // `Configuration/POM.hs:598-599`); only the answer is kept, as the reference keeps no more of
      // the genesis than its ledger configuration needs (`Shelley/Ledger/Config.hs:119-146`,
      // ouroboros-consensus 82ecba32)
      ledgerConfig = xyz.kd5ujc.scarab.node.ntc.LedgerConfig.encode(
        xyz.kd5ujc.scarab.node.ntc.LedgerConfig.Source(
          byronGenesis,
          config.requiresNetworkMagic.getOrElse(false),
          genesis.geometry,
          files.shelley,
          files.alonzo,
          files.conway,
          injections.delegs,
          injections.initialDReps,
          dijkstra,
          config.hardForkEpochs
        )
      )
      start <- byronRules match
        case Some((ledger, _)) => byronStart(env, shelleyHash, ByronState.starting(ledger))
        case None =>
          shelleyStart(
            env,
            Globals(globals, genesis.geometry),
            files,
            injections,
            fromByron,
            startEra,
            shelleyHash
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
        ),
        dijkstraBytes.map(bytes => GenesisFileHash(Blake2b.hash256.hash(Bytes.fromArray(bytes))))
      ),
      chainStoreK = chainStoreK,
      byronParams = Some(byronGenesis),
      byronConfig = byronRules.map(_._2),
      ledgerConfig = ledgerConfig
    )
