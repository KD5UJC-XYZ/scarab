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
import java.nio.file.{AccessDeniedException, Files, NoSuchFileException, Path}
import java.nio.file.attribute.BasicFileAttributes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ByronRules, ByronRulesConfig, HardForkConfig, PraosConfig}
import xyz.kd5ujc.scarab.ledger.json.{AesonError, JsonValue, NativeJson}
import xyz.kd5ujc.scarab.ledger.{
  ByronFieldError,
  ByronGenesisField,
  GenesisDecodeFailure,
  GenesisEra,
  GenesisFileErrno,
  GenesisFileFailure,
  GenesisFileStep,
  GenesisReadError,
  GenesisSchema,
  GenesisState,
  ProtocolStartError,
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
      )(b => EraHistory.ByronParams(b.epochSlots, b.slotLengthMillis, b.securityParam)),
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
    * when given) and read as [[readGenesis]] reads them. A message names a file as the reference
    * does, `takeDirectory config </> name` (`Configuration/POM.hs` `adjustFilePaths`).
    */
  def fromConfigFile(
      config: Path,
      genesisDir: Option[Path] = None
  ): Either[ConfigError, NodeSetup] = fromConfigNamed(config, config.toString, genesisDir)

  /** [[fromConfigFile]] of the file at `config`, whose path as the command line gave it is `named`:
    * messages name the genesis files under `takeDirectory named`.
    */
  private[node] def fromConfigNamed(
      config: Path,
      named: String,
      genesisDir: Option[Path]
  ): Either[ConfigError, NodeSetup] =
    for
      parsed <- readConfig(config)
      base = genesisDir.getOrElse(Option(config.toAbsolutePath.getParent).getOrElse(Path.of(".")))
      shown = genesisDir.fold(HaskellPath.takeDirectory(named))(_.toString)
      setup <- assembleFrom(parsed, GenesisFiles.on(base, HaskellPath.combine(shown, _)))
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

  /** Why a file the configuration names could not be read: an `IOException` the reference shows
    * ([[GenesisFileFailure]]), or a failure of the JVM's file system that has no such form, kept as
    * the defect it is.
    */
  enum ReadFault:
    case Native(failure: GenesisFileFailure)
    case Defect(file: String, cause: Throwable)

  /** The files a configuration names: the path a message shows for a name, and the bytes. */
  final case class GenesisFiles(
      shown: String => String,
      read: String => Either[ReadFault, Array[Byte]]
  )

  object GenesisFiles:
    /** Files given by name; an absent one as `getFileSize` reports a missing file. */
    def of(read: String => Option[Array[Byte]]): GenesisFiles =
      GenesisFiles(
        identity,
        name =>
          read(name).toRight(
            ReadFault.Native(
              GenesisFileFailure(name, GenesisFileStep.Size, GenesisFileErrno.NoSuchFile)
            )
          )
      )

    /** The files under `base`, read as `readGenesisAny` does: `getFileSize` (a `stat`, which
      * follows links), then `BS.readFile`, whose `openFile` refuses a directory. A file of 10 MiB
      * or more the reference maps into memory instead (`Protocol/Shelley.hs:136-138`), so a failure
      * to read one has no `withBinaryFile` form and is a defect.
      */
    def on(base: Path, shown: String => String): GenesisFiles =
      GenesisFiles(
        shown,
        name =>
          val path = base.resolve(name)
          def native(step: GenesisFileStep, errno: GenesisFileErrno) =
            ReadFault.Native(GenesisFileFailure(shown(name), step, errno))
          def fault(step: GenesisFileStep, mapped: Boolean)(cause: Throwable): ReadFault =
            cause match
              case _: NoSuchFileException if mapped => native(step, GenesisFileErrno.NoSuchFile)
              case _: AccessDeniedException if mapped =>
                native(step, GenesisFileErrno.PermissionDenied)
              case other => ReadFault.Defect(shown(name), other)
          Either
            .catchNonFatal(Files.readAttributes(path, classOf[BasicFileAttributes]))
            .leftMap(fault(GenesisFileStep.Size, mapped = true))
            .flatMap(attributes =>
              if attributes.isDirectory then
                Left(native(GenesisFileStep.Read, GenesisFileErrno.IsDirectory))
              else
                Either
                  .catchNonFatal(Files.readAllBytes(path))
                  .leftMap(fault(GenesisFileStep.Read, mapped = attributes.size < MappedSize))
            )
      )

  /** `readGenesisAny` maps a file of this size or more into memory. */
  private val MappedSize: Long = 10485760L

  /** The bytes of the genesis files, each accepted as the reference accepts it. */
  final case class GenesisBytes(
      byron: Array[Byte],
      shelley: Array[Byte],
      alonzo: Array[Byte],
      conway: Array[Byte]
  )

  /** The genesis files the configuration names, read as the reference's `mkConsensusProtocol` reads
    * them (cardano-node 11.1.3 `Protocol/Cardano.hs:112-152`), one after another: the Byron genesis
    * whole, and its hash over the canonical JSON (`Protocol/Byron.hs:91-113`); then the Shelley,
    * Alonzo and Conway files, each read, hashed (blake2b-256 of its bytes) and decoded
    * (`readGenesisAny`, `Protocol/Shelley.hs:126-153`) before the next is read; then the Dijkstra
    * file, when one is configured and present, hashed. The checkpoints file, required when
    * configured, is read and hashed last.
    */
  def readGenesis(config: NodeConfig, files: GenesisFiles): Either[ConfigError, GenesisBytes] =
    def hashOf(bytes: Array[Byte]) = Blake2b.hash256.hash(Bytes.fromArray(bytes))
    def start(era: GenesisEra, cause: GenesisReadError) =
      ConfigError(ConfigProblem.ProtocolStart(ProtocolStartError.Genesis(era, cause)))
    def bytes(era: GenesisEra, name: String): Either[ConfigError, Array[Byte]] =
      files
        .read(name)
        .leftMap {
          case ReadFault.Native(failure)     => start(era, GenesisReadError.ReadFile(failure))
          case ReadFault.Defect(file, cause) => ConfigError(ConfigProblem.FileRead(file, cause))
        }
    def hashed(era: GenesisEra, data: Array[Byte], expected: Option[Bytes]) =
      expected.traverse_ { want =>
        val got = hashOf(data)
        Either.cond(got == want, (), start(era, GenesisReadError.HashMismatch(got, want)))
      }
    // `eitherDecodeStrict'`: the value, the instance, then nothing but space after the value
    def decoded(era: GenesisEra, name: String, data: Array[Byte])(
        check: JsonValue => Either[AesonError, Unit]
    ): Either[ConfigError, Unit] =
      val input = Bytes.fromArray(data)
      def refused(cause: GenesisDecodeFailure) =
        start(era, GenesisReadError.Decode(files.shown(name), cause))
      for
        parsed <- NativeJson
          .parse(input)
          .leftMap(e => refused(GenesisDecodeFailure.Value(NativeJson.DecodeError.Syntax(e))))
        _ <- check(parsed.value).leftMap(e => refused(GenesisDecodeFailure.Field(e)))
        _ <- Either.cond(
          NativeJson.onlySpace(input, parsed.end),
          (),
          refused(GenesisDecodeFailure.Value(NativeJson.DecodeError.TrailingGarbage))
        )
      yield ()
    def genesis(era: GenesisEra, name: String, expected: Option[Bytes])(
        check: JsonValue => Either[AesonError, Unit]
    ): Either[ConfigError, Array[Byte]] =
      for
        data <- bytes(era, name)
        _ <- hashed(era, data, expected)
        _ <- decoded(era, name, data)(check)
      yield data
    val names = config.files
    val hashes = config.hashes
    for
      // the Byron genesis is read by its own reader; an absent field and the hash take the
      // reference's form, its other refusals keep their own
      byron <- files
        .read(names.byron)
        .leftMap(_ => ConfigError(ConfigProblem.FileMissing("Byron", names.byron)))
      _ <- ByronGenesisFile.parse(Bytes.fromArray(byron), hashes.byron).leftMap {
        case ConfigError(ConfigProblem.ByronGenesisValue(field, ByronFieldError.Missing))
            if field != ByronGenesisField.Top =>
          ConfigError(
            ConfigProblem.ProtocolStart(
              ProtocolStartError.ByronMissingField(files.shown(names.byron), field)
            )
          )
        case other => other
      }
      shelley <- genesis(GenesisEra.Shelley, names.shelley, hashes.shelley)(GenesisSchema.shelley)
      alonzo <- genesis(GenesisEra.Alonzo, names.alonzo, hashes.alonzo)(GenesisSchema.alonzo)
      conway <- genesis(GenesisEra.Conway, names.conway, hashes.conway)(GenesisSchema.conway)
      _ <- names.dijkstra.traverse_(file =>
        files.read(file).toOption.traverse_(hashed(GenesisEra.Dijkstra, _, hashes.dijkstra))
      )
      _ <- names.checkpoints.traverse_(file =>
        files
          .read(file)
          .leftMap(_ => ConfigError(ConfigProblem.FileMissing("Checkpoints", file)))
          .flatMap(data =>
            hashes.checkpoints.traverse_ { want =>
              val got = hashOf(data)
              Either.cond(
                got == want,
                (),
                ConfigError(
                  ConfigProblem.GenesisHashMismatch("Checkpoints", file, want.hex, got.hex)
                )
              )
            }
          )
      )
    yield GenesisBytes(byron, shelley, alonzo, conway)

  /** [[readGenesis]] of files given by name. */
  def readGenesis(
      config: NodeConfig,
      read: String => Option[Array[Byte]]
  ): Either[ConfigError, GenesisBytes] = readGenesis(config, GenesisFiles.of(read))

  /** The setup from a configuration and the genesis files it names (read by name). */
  def assemble(
      config: NodeConfig,
      read: String => Option[Array[Byte]]
  ): Either[ConfigError, NodeSetup] = assembleFrom(config, GenesisFiles.of(read))

  /** The setup from a configuration and its files: [[readGenesis]], then the node's readers. */
  def assembleFrom(config: NodeConfig, sources: GenesisFiles): Either[ConfigError, NodeSetup] =
    val names = config.files
    def required(role: String, name: String): Either[ConfigError, Array[Byte]] =
      sources.read(name).leftMap(_ => ConfigError(ConfigProblem.FileMissing(role, name)))
    def text(bytes: Array[Byte]) = new String(bytes, "UTF-8")
    def json(name: String, bytes: Array[Byte]): Either[ConfigError, Json] =
      parser.parse(text(bytes)).leftMap(e => ConfigError(ConfigProblem.JsonSyntax(name, e)))
    for
      genesisBytes <- readGenesis(config, sources)
      checkpoints <- names.checkpoints
        .traverse(file =>
          required("Checkpoints", file)
            .flatMap(CheckpointsFile.parse(file, _, config.hashes.checkpoints))
        )
        .map(_.getOrElse(HeaderCheckpoints.empty))
      shelleyBytes = genesisBytes.shelley
      shelleyBase <- Genesis.shelley(text(shelleyBytes))
      // The chain store and the volatile chain count blocks in an `Int`: a larger security parameter
      // is refused, which the reference's `NonZero Word64` would accept. An approved deviation,
      // recorded in #474.
      chainStoreK <- ChainStoreK.from(shelleyBase.geometry.k)
      firstShelley = config.firstShelleyEpoch
      byronBytes = genesisBytes.byron
      // The reference reads the whole Byron genesis whichever era the chain starts in
      // (`readGenesisData`, #734); a chain that starts in Shelley takes only its start time from it.
      byronFile <- ByronGenesisFile.read(Bytes.fromArray(byronBytes))
      byronGenesis <- ByronGenesisFile.fromJson(byronFile._1, byronFile._2, config.byronGenesisHash)
      byron = Option.unless(firstShelley.contains(BigInt(0)))(byronGenesis)
      alonzoBytes = genesisBytes.alonzo
      conwayBytes = genesisBytes.conway
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
      injections <- GenesisInjections.read(files)
      globals <- GenesisState.globals(files, genesis)
      forks = HardForkConfig.fromEpochs(config.hardForkEpochs)
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
      env = mirrorEnvOf(genesis, globals, praos, Some(byronGenesis))
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
            startEra,
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
      byronParams = Some(byronGenesis),
      byronConfig = byronRules.map(_._2)
    )
