// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigNumberField, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.json.FromJson
import cats.syntax.all.*
import io.circe.{Decoder, HCursor, Json, JsonObject, parser}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NtnVersion}
import xyz.kd5ujc.scarab.network.{AcceptedConnectionsLimit, DiffTime, OutboundLimit, Word32}
import xyz.kd5ujc.scarab.network.selection.{ConsensusMode, Targets}
import xyz.kd5ujc.scarab.node.mempool.MempoolTimeouts

/** The genesis files a configuration names (`ByronGenesisFile` or the older `GenesisFile`,
  * `ShelleyGenesisFile`, `AlonzoGenesisFile`, `ConwayGenesisFile`, and, with experimental hard
  * forks enabled, `DijkstraGenesisFile`, plus the optional `CheckpointsFile`), as written. The
  * reference resolves a relative name against the directory of the configuration file.
  */
final case class GenesisFiles(
    byron: String,
    shelley: String,
    alonzo: String,
    conway: String,
    dijkstra: Option[String] = None,
    checkpoints: Option[String] = None
):
  /** Every name, as written. */
  def all: List[String] = List(byron, shelley, alonzo, conway) ++ dijkstra ++ checkpoints

object GenesisFiles:
  /** The names a genesis directory (`--genesis-dir`) uses. */
  val standard: GenesisFiles =
    GenesisFiles(
      "byron-genesis.json",
      "shelley-genesis.json",
      "alonzo-genesis.json",
      "conway-genesis.json"
    )

/** The hashes a configuration lists (`ByronGenesisHash`, `ShelleyGenesisHash`, `AlonzoGenesisHash`,
  * `ConwayGenesisHash`, `DijkstraGenesisHash`, `CheckpointsFileHash`). The Byron hash is that of
  * the canonical JSON of the file, the others the blake2b-256 of the file's bytes.
  */
final case class GenesisHashes(
    byron: Option[Bytes] = None,
    shelley: Option[Bytes] = None,
    alonzo: Option[Bytes] = None,
    conway: Option[Bytes] = None,
    dijkstra: Option[Bytes] = None,
    checkpoints: Option[Bytes] = None
)

/** The targets of the peer governor a configuration overrides (`TargetNumberOfRootPeers` and the
  * six that follow, `Cardano.Node.Configuration.POM` 378-391); an absent key leaves the default of
  * the node's role.
  */
final case class PeerTargetOverrides(
    root: Option[Int] = None,
    known: Option[Int] = None,
    established: Option[Int] = None,
    active: Option[Int] = None,
    knownBig: Option[Int] = None,
    establishedBig: Option[Int] = None,
    activeBig: Option[Int] = None
):
  /** The defaults with these replaced, if the result is sane. */
  def apply(
      defaults: xyz.kd5ujc.scarab.network.selection.Targets
  ): Either[ConfigError, xyz.kd5ujc.scarab.network.selection.Targets] =
    xyz.kd5ujc.scarab.network.selection.Targets
      .validated(
        root.getOrElse(defaults.root),
        known.getOrElse(defaults.known),
        established.getOrElse(defaults.established),
        active.getOrElse(defaults.active),
        knownBig.getOrElse(defaults.knownBig),
        establishedBig.getOrElse(defaults.establishedBig),
        activeBig.getOrElse(defaults.activeBig)
      )
      .leftMap(p => ConfigError(ConfigProblem.PeerTargets(p)))

/** The remaining configuration keys the node reads or records. What it does not act on is kept so
  * the log can say what was configured; `unsupported` lists the settings that ask for something the
  * node does not implement.
  *
  * @param lastKnownBlockVersion
  *   `LastKnownBlockVersion-Major`, `-Minor`, `-Alt`: the Byron block version the node supports.
  * @param mempoolCapacityBytes
  *   `MempoolCapacityBytesOverride` as a number of bytes, a `Word32` (`NoOverride` is `None`).
  * @param hardForkVersions
  *   `Test<Era>HardForkAtVersion`: hard forks triggered by a protocol version.
  * @param peerTargets
  *   `TargetNumberOf...Peers`: the governor's targets, when the file sets any.
  * @param syncPeerTargets
  *   `SyncTargetNumberOf...Peers`: Genesis-mode sync targets, when the file sets any.
  * @param maxConcurrencyDeadline
  *   `MaxConcurrencyDeadline` (`POM.hs` 345): the target number of active fetching peers in
  *   deadline mode; preferred-peer handover follows the reference. Absent is the default, 1.
  * @param maxConcurrencyBulkSync
  *   `MaxConcurrencyBulkSync`: the corresponding bulk-sync target (default 1).
  * @param acceptedConnectionsLimit
  *   `AcceptedConnectionsLimit` (`POM.hs` 373-375, cardano-node 11.1.3): an object with
  *   `hardLimit`, `softLimit` and `delay` (seconds), all three required when present
  *   (`Ouroboros/Network/OrphanInstances.hs` `FromJSON AcceptedConnectionsLimit`); absent is the
  *   reference's default, hard 512, soft 384, delay 5 s.
  * @param startAsNonProducingNode
  *   `StartAsNonProducingNode` (`POM.hs` 325, cardano-node 11.1.3): start with forging disabled;
  *   absent is the reference's default, false (`POM.hs` 738), and the command line's flag wins
  *   (`Main.Options.startsNonProducing`).
  * @param maxOutboundConnections
  *   `MaxOutboundConnections`: the opt-in outbound limit, a positive number of connections
  *   ([[OutboundLimit]]); `--max-outbound` wins over it. Not a key of the reference, which has no
  *   outbound limit; absent, the node has none either.
  * @param mempoolTimeouts
  *   `MempoolTimeoutSoft`, `MempoolTimeoutHard` and `MempoolTimeoutCapacity` (`POM.hs` 400-402,
  *   889-897, cardano-node 11.1.3): numbers of seconds, all three or none; none is the reference's
  *   defaults, 1, 1.5 and 5 ([[mempool.MempoolTimeouts]]).
  */
final case class ConfigSettings(
    protocol: String = "Cardano",
    lastKnownBlockVersion: Option[(Int, Int, Int)] = None,
    maxKnownMajorProtocolVersion: Option[BigInt] = None,
    experimentalHardForksEnabled: Boolean = false,
    experimentalProtocolsEnabled: Boolean = false,
    consensusMode: ConsensusMode = ConsensusMode.PraosMode,
    peerSharing: Option[Boolean] = None,
    diffusionMode: Option[String] = None,
    socketPath: Option[String] = None,
    databasePath: Option[String] = None,
    mempoolCapacityBytes: Option[Word32] = None,
    hardForkVersions: Map[Era, BigInt] = Map.empty,
    peerTargets: PeerTargetOverrides = PeerTargetOverrides(),
    syncPeerTargets: PeerTargetOverrides = PeerTargetOverrides(),
    minBigLedgerPeersForTrustedState: Int = 5,
    maxConcurrencyDeadline: Option[Int] = None,
    maxConcurrencyBulkSync: Option[Int] = None,
    genesis: GenesisSettings = GenesisSettings(),
    acceptedConnectionsLimit: AcceptedConnectionsLimit = AcceptedConnectionsLimit.default,
    dijkstraHardForkEpoch: Option[BigInt] = None,
    dijkstraHardForkVersion: Option[BigInt] = None,
    leios: LeiosSettings = LeiosSettings(),
    ledgerDb: LedgerDbSettings = LedgerDbSettings(),
    startAsNonProducingNode: Option[Boolean] = None,
    maxOutboundConnections: Option[OutboundLimit.Connections] = None,
    mempoolTimeouts: MempoolTimeouts = MempoolTimeouts.default
):
  /** The highest node-to-node version the node offers and accepts: 15 unless
    * `ExperimentalProtocolsEnabled` is set, as the reference's `limitToLatestReleasedVersion`.
    */
  def maxNtnVersion: NtnVersion =
    NtnVersion.maximum(experimentalProtocols = experimentalProtocolsEnabled)

  /** The greatest protocol version the node declares it handles under `build`
    * (`cardanoProtocolVersion`, `Cardano.Node.Protocol.Cardano`: lines 182-184 at cardano-node
    * 11.1.3, 192-194 at b004fe0c): 12.0 with `ExperimentalHardForksEnabled` in every build; without
    * it 11.2 in the release builds (cardano-node 11.1.3) and 11.0 in the Leios build (cardano-node
    * b004fe0c). Forged headers carry it in every Shelley-based era: the block configs of every era
    * take it (ouroboros-consensus 82ecba32 `Cardano/Node.hs:703-830`; 2416493b
    * `Cardano/Node.hs:605-737`, Dijkstra's at 732-737) and `forgeShelleyBlock` writes
    * `shelleyProtocolVersion` of the block config (82ecba32 `Shelley/Ledger/Forge.hs:83`; 2416493b
    * `Shelley/Ledger/Forge.hs:144`). A Praos header holds it as `hbProtVer`; a Leios header,
    * Dijkstra's under the Leios build, as its version info, the major as the highest supported one
    * and the minor as the software tag (2416493b `Shelley/Protocol/Praos.hs:184, 275-300`). The
    * 11.2 line keeps the 11.1 line's value, though its release 11.2.1 declares 11.3 without the
    * flag (#1002); the build is refused at start.
    */
  def protocolVersion(build: ReferenceBuild): (BigInt, BigInt) =
    if experimentalHardForksEnabled then (BigInt(12), BigInt(0))
    else
      build match
        case ReferenceBuild.Leios                                    => (BigInt(11), BigInt(0))
        case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 => (BigInt(11), BigInt(2))

  /** POM.hs:899-905 (cardano-node 11.1.3): relays default on, producers off; an explicit setting
    * wins.
    */
  def peerSharingEnabled(producer: Boolean): Boolean = peerSharing.getOrElse(!producer)

  def syncTargets(defaults: Targets): Either[ConfigError, Targets] = syncPeerTargets(defaults)

  /** Settings that ask for something the node does not implement. */
  def unsupported: List[String] =
    List(
      Option.when(experimentalHardForksEnabled)(
        "ExperimentalHardForksEnabled: the Dijkstra era is not implemented"
      ),
      Option.when(hardForkVersions.nonEmpty)(
        "Test<Era>HardForkAtVersion: hard forks triggered by a version are not honoured"
      )
    ).flatten ++ leios.unused ++ ledgerDb.unused

/** The node configuration keys that shape the era history: the Byron genesis hash and the epochs at
  * which each Shelley-based era starts (`Test<Era>HardForkAtEpoch`). An absent
  * `TestShelleyHardForkAtEpoch` means the Byron ledger decides when the chain enters Shelley (a
  * public network); `0` means the chain starts in Shelley (no Byron prefix), as on Preview. The
  * Byron rules also read `RequiresNetworkMagic` and `PBftSignatureThreshold`. The genesis files and
  * their hashes, the protocol and the other settings are read as `cardano-node` reads them (see
  * [[NodeConfig.parse]]).
  *
  * @param ignoredKeys
  *   The top-level keys of the configuration that the node does not read, in the file's order. The
  *   node reports them at start for a genesis directory's configuration
  *   ([[Main.ignoredKeysReported]]), not for a `--config` file, which it reads as cardano-node
  *   11.1.3 does, and 11.1.3 reports none.
  */
final case class NodeConfig(
    byronGenesisHash: Option[Bytes],
    hardForkEpochs: Map[Era, BigInt],
    requiresNetworkMagic: Option[Boolean] = None,
    pbftSignatureThreshold: Option[Double] = None,
    files: GenesisFiles = GenesisFiles.standard,
    hashes: GenesisHashes = GenesisHashes(),
    settings: ConfigSettings = ConfigSettings(),
    ignoredKeys: List[String] = Nil
):
  /** The configured first Shelley epoch, if the configuration fixes it. */
  def firstShelleyEpoch: Option[BigInt] = hardForkEpochs.get(Era.Shelley)

  /** Every file name the configuration gives, as written: the genesis files, `SocketPath` and
    * `DatabasePath`.
    */
  def fileNames: List[String] = files.all ++ settings.socketPath ++ settings.databasePath

object NodeConfig:
  val empty: NodeConfig = NodeConfig(None, Map.empty)

  /** The ouroboros-consensus default (`defaultPBftSignatureThreshold`), taken when the key is
    * absent or null (82ecba32 `Ouroboros/Consensus/Byron/Node.hs:176-177, 239`).
    */
  val DefaultPBftSignatureThreshold: Double = 0.22

  /** Every top-level key the parser reads, declared once. [[Keys]] and the configuration's
    * [[ConfigFields]] read only these ([[TopLevelKey]]), so a read of a key not declared here does
    * not compile: a new key is added here, beside its read. The keys of a configuration outside the
    * declaration are the ones it lists as not read ([[NodeConfig.ignoredKeys]]). A test
    * (`ConfigDialectSuite`) checks that each declared key is read.
    */
  private[node] type TopLevel = (
      "ByronGenesisFile",
      "ShelleyGenesisFile",
      "GenesisFile",
      "AlonzoGenesisFile",
      "ConwayGenesisFile",
      "DijkstraGenesisFile",
      "CheckpointsFile",
      "ByronGenesisHash",
      "ShelleyGenesisHash",
      "AlonzoGenesisHash",
      "ConwayGenesisHash",
      "DijkstraGenesisHash",
      "CheckpointsFileHash",
      "RequiresNetworkMagic",
      "PBftSignatureThreshold",
      "Protocol",
      "LastKnownBlockVersion-Major",
      "LastKnownBlockVersion-Minor",
      "LastKnownBlockVersion-Alt",
      "MaxKnownMajorProtocolVersion",
      "ExperimentalHardForksEnabled",
      "TestEnableDevelopmentHardForkEras",
      "ExperimentalProtocolsEnabled",
      "TestEnableDevelopmentNetworkProtocols",
      "TestShelleyHardForkAtEpoch",
      "TestShelleyHardForkAtVersion",
      "TestAllegraHardForkAtEpoch",
      "TestAllegraHardForkAtVersion",
      "TestMaryHardForkAtEpoch",
      "TestMaryHardForkAtVersion",
      "TestAlonzoHardForkAtEpoch",
      "TestAlonzoHardForkAtVersion",
      "TestBabbageHardForkAtEpoch",
      "TestBabbageHardForkAtVersion",
      "TestConwayHardForkAtEpoch",
      "TestConwayHardForkAtVersion",
      "TestDijkstraHardForkAtEpoch",
      "TestDijkstraHardForkAtVersion",
      "ConsensusMode",
      "PeerSharing",
      "StartAsNonProducingNode",
      "DiffusionMode",
      "SocketPath",
      "DatabasePath",
      "MempoolCapacityBytesOverride",
      "MempoolTimeoutSoft",
      "MempoolTimeoutHard",
      "MempoolTimeoutCapacity",
      "TargetNumberOfRootPeers",
      "TargetNumberOfKnownPeers",
      "TargetNumberOfEstablishedPeers",
      "TargetNumberOfActivePeers",
      "TargetNumberOfKnownBigLedgerPeers",
      "TargetNumberOfEstablishedBigLedgerPeers",
      "TargetNumberOfActiveBigLedgerPeers",
      "SyncTargetNumberOfRootPeers",
      "SyncTargetNumberOfKnownPeers",
      "SyncTargetNumberOfEstablishedPeers",
      "SyncTargetNumberOfActivePeers",
      "SyncTargetNumberOfKnownBigLedgerPeers",
      "SyncTargetNumberOfEstablishedBigLedgerPeers",
      "SyncTargetNumberOfActiveBigLedgerPeers",
      "LowLevelGenesisOptions",
      "AcceptedConnectionsLimit",
      "MaxOutboundConnections",
      "MaxConcurrencyDeadline",
      "MaxConcurrencyBulkSync",
      "MinBigLedgerPeersForTrustedState",
      "EgressBudgetMbps",
      "LeiosDbConfig",
      "LeiosMinOfferLead",
      "LeiosAdaptiveEndorsements",
      "LedgerDB",
      "SnapshotInterval",
      "NumOfDiskSnapshots"
  )

  /** A key of [[TopLevel]]. */
  private[node] type TopLevelKey = Tuple.Union[TopLevel]

  /** The keys of [[TopLevel]], as values. */
  private[node] val TopLevelKeys: Set[String] =
    scala.compiletime.constValueTuple[TopLevel].toList.toSet[String]

  /** The eras whose start a test network may fix, with the keys of their triggers
    * (`Test<Era>HardForkAtEpoch`, `Test<Era>HardForkAtVersion`, [[ConfigNumberField]]).
    */
  private[node] val HardForks: Vector[(Era, TopLevelKey, TopLevelKey)] = Vector(
    (Era.Shelley, "TestShelleyHardForkAtEpoch", "TestShelleyHardForkAtVersion"),
    (Era.Allegra, "TestAllegraHardForkAtEpoch", "TestAllegraHardForkAtVersion"),
    (Era.Mary, "TestMaryHardForkAtEpoch", "TestMaryHardForkAtVersion"),
    (Era.Alonzo, "TestAlonzoHardForkAtEpoch", "TestAlonzoHardForkAtVersion"),
    (Era.Babbage, "TestBabbageHardForkAtEpoch", "TestBabbageHardForkAtVersion"),
    (Era.Conway, "TestConwayHardForkAtEpoch", "TestConwayHardForkAtVersion")
  )

  /** How a reader tells a configuration's two dialects apart: the flat one, every key at the top
    * level, and the cardano-config envelope, every setting nested under `Configuration`.
    */
  private enum Dialects:
    /** cardano-node 11.1.3 reads every file flat (`POM.hs` 322-324, `parseNodeConfigurationFP`
      * 1014-1017), and refuses an envelope for its absent genesis files (`POM.hs` 585-593).
      */
    case FlatOnly

    /** cardano-node 11.2.1 and b004fe0c tell an envelope by its top-level `Configuration` key,
      * whatever its value (`classifyConfigurationFile`, `CardanoConfigResolve.hs` 143-147 and
      * 141-145), and read it with cardano-config alone (`buildNodeConfiguration`, 119-122 and
      * 117-120).
      */
    case ByConfigurationKey

  private object Dialects:
    /** A `--config` file (`strict`) is told apart as the selected build's node tells it. A genesis
      * directory, which the reference does not have, tells the envelope apart under every build, so
      * that its configuration never reads as empty.
      */
    def of(build: ReferenceBuild, strict: Boolean): Dialects =
      if !strict then ByConfigurationKey
      else
        build match
          case ReferenceBuild.Release11_1                        => FlatOnly
          case ReferenceBuild.Release11_2 | ReferenceBuild.Leios => ByConfigurationKey

  /** The flat dialect, the only one the node reads: an envelope, where `dialects` tells it apart,
    * is refused ([[ConfigProblem.CardanoConfigEnvelope]]).
    */
  private def flat(dialects: Dialects, top: JsonObject): Either[ConfigError, Unit] =
    dialects match
      case Dialects.FlatOnly => Right(())
      case Dialects.ByConfigurationKey =>
        Either.cond(
          !top.contains("Configuration"),
          (),
          ConfigError(ConfigProblem.CardanoConfigEnvelope)
        )

  /** aeson's `FromJSON Word32` (`parseBoundedIntegral`): a JSON number (not a string) with an
    * integral value in `0 .. 2^32 - 1`, in any notation (`512`, `512.0`, `5.12e2`).
    */
  private val word32Decoder: Decoder[Word32] = Decoder.decodeJson.emap(json =>
    json.asNumber
      .flatMap(_.toLong)
      .flatMap(Word32.fromLong)
      .toRight("a Word32: an integral number from 0 to 4294967295")
  )

  /** A JSON number (not a string) with a whole value that an `Int` holds, in any notation (`50`,
    * `50.0`, `5e1`): the values of the node's own keys, which no reference parser defines.
    */
  private val wholeDecoder: Decoder[Int] = Decoder.decodeJson.emap(json =>
    json.asNumber.flatMap(_.toInt).toRight("a whole number from -2147483648 to 2147483647")
  )

  /** A JSON number as aeson's `Scientific` holds it: the coefficient of its digits and the base-10
    * exponent as written (`1.50e3` is `150` and `1`).
    */
  private val Number = """(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?""".r

  /** aeson's `FromJSON DiffTime` (aeson 2.2 `Data/Aeson/Types/FromJSON.hs`, `withBoundedScientific`
    * and `realToFrac`): a JSON number of seconds whose exponent is at most 1024, of any sign,
    * floored to a picosecond (`Fixed` `fromRational`).
    */
  private val diffTimeDecoder: Decoder[DiffTime] = Decoder.decodeJson.emap(json =>
    json.asNumber
      .map(_.toString)
      .collect { case Number(sign, whole, fraction, exponent) =>
        val digits = Option(fraction).getOrElse("")
        val coefficient = BigInt(sign + whole + digits)
        (coefficient, Option(exponent).fold(BigInt(0))(BigInt(_)) - digits.length)
      }
      .toRight("a number of seconds")
      .filterOrElse(_._2 <= 1024, "a number of seconds with an exponent of at most 1024")
      .map { (coefficient, exponent) =>
        val shift = exponent + 12
        if shift >= 0 then DiffTime.picoseconds(coefficient * BigInt(10).pow(shift.toInt))
        else if -shift > coefficient.abs.toString.length then
          DiffTime.picoseconds(if coefficient.signum < 0 then BigInt(-1) else BigInt(0))
        else
          val (quotient, remainder) = coefficient /% BigInt(10).pow((-shift).toInt)
          DiffTime.picoseconds(if remainder.signum < 0 then quotient - 1 else quotient)
      }
  )

  /** The reference's `FromJSON AcceptedConnectionsLimit` (ouroboros-network c45735a5,
    * `ouroboros-network/orphan-instances/Ouroboros/Network/OrphanInstances.hs:302-307`): the three
    * keys are required, the limits `Word32` and the delay a `DiffTime`
    * (`Server/RateLimiting.hs:26-42`).
    */
  private val acceptedDecoder: Decoder[AcceptedConnectionsLimit] =
    Decoder.forProduct3[AcceptedConnectionsLimit, Word32, Word32, DiffTime](
      "hardLimit",
      "softLimit",
      "delay"
    )(AcceptedConnectionsLimit.apply)(using word32Decoder, word32Decoder, diffTimeDecoder)

  /** Typed access to the configuration's keys, the declared ones only ([[TopLevel]]); `null` counts
    * as absent.
    */
  private final class Keys(c: HCursor):
    def opt[A: Decoder](name: TopLevelKey): Either[ConfigError, Option[A]] =
      c.downField(name)
        .focus
        .filterNot(_.isNull)
        .traverse(
          _.as[A].leftMap(e => ConfigError(ConfigProblem.JsonField("configuration", name, e)))
        )

    /** `FromJSON Word64` (`parseBoundedIntegral`): `EpochNo` derives it, and `Word` is 64 bits wide
      * on the platforms the reference is built for.
      */
    def word64(key: TopLevelKey, field: ConfigNumberField): Either[ConfigError, Option[BigInt]] =
      c.downField(key)
        .focus
        .filterNot(_.isNull)
        .traverse(
          FromJson
            .word64(_)
            .bimap(e => ConfigError(ConfigProblem.ConfigNumber(field, e)), _.toBigInt)
        )

    def hash(name: TopLevelKey): Either[ConfigError, Option[Bytes]] =
      opt[String](name).flatMap(
        _.traverse(h =>
          Bytes
            .fromHex(h)
            .leftMap(e => ConfigError(ConfigProblem.Hex(e)))
            .filterOrElse(
              _.size == 32,
              ConfigError(ConfigProblem.ByteLength(name, 32, h.length / 2))
            )
        )
      )

  private def oneOf(
      name: String,
      value: Option[String],
      allowed: Set[String]
  ): Either[ConfigError, Option[String]] =
    value.traverse(v =>
      Either.cond(
        allowed(v),
        v,
        ConfigError(ConfigProblem.Selection(name, v, allowed))
      )
    )

  /** `name` and the older `GenesisFile` for the Byron and Shelley files: one of them, never both.
    */
  private def fileName(k: Keys, name: TopLevelKey): Either[ConfigError, Option[String]] =
    (k.opt[String](name), k.opt[String]("GenesisFile")).tupled.flatMap {
      case (Some(_), Some(_)) =>
        Left(
          ConfigError(ConfigProblem.ConflictingFields(Set(name, "GenesisFile")))
        )
      case (a, b) => Right(a.orElse(b))
    }

  /** The renamed boolean `name`, which must agree with its old name when both are given. */
  private def renamed(
      k: Keys,
      name: TopLevelKey,
      old: TopLevelKey
  ): Either[ConfigError, Option[Boolean]] =
    (k.opt[Boolean](name), k.opt[Boolean](old)).tupled.flatMap { (value, previous) =>
      Either.cond(
        previous.forall(value.contains),
        value,
        ConfigError(ConfigProblem.RenamedField(old, name))
      )
    }

  /** The mempool's time limits as cardano-node reads them (cardano-node 11.1.3
    * `cardano-node/src/Cardano/Node/Configuration/POM.hs:400-402`, `889-897`; cardano-node 11.2.1
    * `402-404`, `939-947`): each key a `DiffTime` ([[diffTimeDecoder]]), `null` or absent as unset
    * (`.:?`); all three set, or none, which is the defaults; some and not all refuse the
    * configuration.
    */
  private def timeouts(k: Keys): Either[ConfigError, MempoolTimeouts] =
    val names: Vector[TopLevelKey] =
      Vector[TopLevelKey]("MempoolTimeoutSoft", "MempoolTimeoutHard", "MempoolTimeoutCapacity")
    names.traverse((name: TopLevelKey) => k.opt[DiffTime](name)(using diffTimeDecoder)).flatMap {
      case Vector(Some(soft), Some(hard), Some(capacity)) =>
        Right(MempoolTimeouts(soft, hard, capacity))
      case values if values.forall(_.isEmpty) => Right(MempoolTimeouts.default)
      case values =>
        Left(
          ConfigError(
            ConfigProblem
              .MempoolTimeouts(names.zip(values).collect { case (n, Some(_)) => n }.toSet[String])
          )
        )
    }

  /** `MempoolCapacityBytesOverride` as cardano-node 11.1.3 reads it
    * (`cardano-node/src/Cardano/Node/Configuration/POM.hs:475-487`): a number is a `Word32`
    * ([[word32Decoder]]), 0 included, the string `NoOverride` is no override, and a `null` or
    * absent key is no override.
    */
  private def capacity(k: Keys): Either[ConfigError, Option[Word32]] =
    k.opt[Json]("MempoolCapacityBytesOverride").flatMap {
      case None => Right(None)
      case Some(j) =>
        word32Decoder.decodeJson(j).toOption match
          case Some(n)                                   => Right(Some(n))
          case None if j.asString.contains("NoOverride") => Right(None)
          case None =>
            Left(
              ConfigError(ConfigProblem.MempoolCapacity(j))
            )
    }

  /** Read a configuration (`config.json`). With `strict`, as `cardano-node run` does: the Byron,
    * Shelley, Alonzo and Conway genesis files and `LastKnownBlockVersion-Major` and `-Minor` must
    * be named, and a Dijkstra genesis file once experimental hard forks are enabled. Without it (a
    * genesis directory) a missing genesis file takes the directory's standard name. In both the
    * protocol must be `Cardano`, `GenesisFile` must not be given next to the specific name, a
    * listed hash must be 32 bytes of hexadecimal, and the keys the parser does not read are listed
    * ([[NodeConfig.ignoredKeys]]). A cardano-config envelope is refused where [[Dialects]] tells it
    * apart: in a genesis directory, and with `strict` where the selected build's node tells it.
    */
  def parse(
      build: ReferenceBuild
  )(text: String, strict: Boolean = false): Either[ConfigError, NodeConfig] =
    for
      json <- parser
        .parse(text)
        .leftMap(e => ConfigError(ConfigProblem.JsonSyntax("configuration", e)))
      top <- json.asObject.toRight(ConfigError(ConfigProblem.ExpectedObject("configuration", json)))
      _ <- flat(Dialects.of(build, strict), top)
      config <- declared(build)(json, strict)
    yield config.copy(ignoredKeys = top.keys.filterNot(TopLevelKeys.contains).toList)

  /** The configuration from its declared keys ([[TopLevel]]), the only ones the parser can read. */
  private def declared(
      build: ReferenceBuild
  )(json: Json, strict: Boolean): Either[ConfigError, NodeConfig] =
    val k = Keys(json.hcursor)
    for
      hardForks <- renamed(k, "ExperimentalHardForksEnabled", "TestEnableDevelopmentHardForkEras")
      fields <- ConfigFields.keyed[TopLevelKey]("configuration", json)
      leios <- LeiosSettings.parse(fields, build)
      ledgerDb <- LedgerDbSettings.parse(fields, build)
      dijkstraEpoch <-
        if hardForks.contains(true) then
          k.word64("TestDijkstraHardForkAtEpoch", ConfigNumberField.DijkstraHardForkAtEpoch)
        else Right(None)
      dijkstraVersion <-
        if hardForks.contains(true) then
          k.word64("TestDijkstraHardForkAtVersion", ConfigNumberField.DijkstraHardForkAtVersion)
        else Right(None)
      byronHash <- k.hash("ByronGenesisHash")
      forks <- HardForks.traverse((era, epoch, _) =>
        k.word64(epoch, ConfigNumberField.HardForkAtEpoch(era)).map(_.map(era -> _))
      )
      versions <- HardForks.traverse((era, _, version) =>
        k.word64(version, ConfigNumberField.HardForkAtVersion(era)).map(_.map(era -> _))
      )
      magic <- k
        .opt[String]("RequiresNetworkMagic")
        .flatMap(
          oneOf("RequiresNetworkMagic", _, Set("RequiresMagic", "RequiresNoMagic"))
        )
        .map(_.map(_ == "RequiresMagic"))
      // `npcByronPbftSignatureThresh <- v .:? "PBftSignatureThreshold"`, a `Maybe Double`
      // (cardano-node 11.1.3 `POM.hs:600`)
      threshold <- fields.realFloat("PBftSignatureThreshold")
      byronFile <- fileName(k, "ByronGenesisFile")
      shelleyFile <- fileName(k, "ShelleyGenesisFile")
      alonzoFile <- k.opt[String]("AlonzoGenesisFile")
      conwayFile <- k.opt[String]("ConwayGenesisFile")
      dijkstraFile <-
        if hardForks.contains(true) then k.opt[String]("DijkstraGenesisFile") else Right(None)
      checkpointsFile <- k.opt[String]("CheckpointsFile")
      hashes <- (
        k.hash("ShelleyGenesisHash"),
        k.hash("AlonzoGenesisHash"),
        k.hash("ConwayGenesisHash"),
        if hardForks.contains(true) then k.hash("DijkstraGenesisHash") else Right(None),
        k.hash("CheckpointsFileHash")
      ).tupled
      protocol <- k.opt[String]("Protocol")
      _ <- Either.cond(
        protocol.forall(_ == "Cardano"),
        (),
        ConfigError(ConfigProblem.Selection("Protocol", protocol.getOrElse(""), Set("Cardano")))
      )
      major <- k.opt[Int]("LastKnownBlockVersion-Major")
      minor <- k.opt[Int]("LastKnownBlockVersion-Minor")
      alt <- k.opt[Int]("LastKnownBlockVersion-Alt")
      maxKnown <- k.opt[BigInt]("MaxKnownMajorProtocolVersion")
      protocols <- renamed(
        k,
        "ExperimentalProtocolsEnabled",
        "TestEnableDevelopmentNetworkProtocols"
      )
      modeName <- k
        .opt[String]("ConsensusMode")
        .flatMap(oneOf("ConsensusMode", _, Set("PraosMode", "GenesisMode")))
      mode <- modeName match
        case None                => Right(ConsensusMode.PraosMode)
        case Some("PraosMode")   => Right(ConsensusMode.PraosMode)
        case Some("GenesisMode") => Right(ConsensusMode.GenesisMode)
        case Some(other) =>
          Left(
            ConfigError(
              ConfigProblem.Selection("ConsensusMode", other, Set("PraosMode", "GenesisMode"))
            )
          )
      sharing <- k.opt[Boolean]("PeerSharing")
      nonProducing <- k.opt[Boolean]("StartAsNonProducingNode")
      diffusion <- k
        .opt[String]("DiffusionMode")
        .flatMap(oneOf("DiffusionMode", _, Set("InitiatorOnly", "InitiatorAndResponder")))
      socket <- k.opt[String]("SocketPath")
      database <- k.opt[String]("DatabasePath")
      mempool <- capacity(k)
      mempoolTimeouts <- timeouts(k)
      targets <- (
        k.opt[Int]("TargetNumberOfRootPeers"),
        k.opt[Int]("TargetNumberOfKnownPeers"),
        k.opt[Int]("TargetNumberOfEstablishedPeers"),
        k.opt[Int]("TargetNumberOfActivePeers"),
        k.opt[Int]("TargetNumberOfKnownBigLedgerPeers"),
        k.opt[Int]("TargetNumberOfEstablishedBigLedgerPeers"),
        k.opt[Int]("TargetNumberOfActiveBigLedgerPeers")
      ).mapN(PeerTargetOverrides.apply)
      syncTargets <- (
        k.opt[Int]("SyncTargetNumberOfRootPeers"),
        k.opt[Int]("SyncTargetNumberOfKnownPeers"),
        k.opt[Int]("SyncTargetNumberOfEstablishedPeers"),
        k.opt[Int]("SyncTargetNumberOfActivePeers"),
        k.opt[Int]("SyncTargetNumberOfKnownBigLedgerPeers"),
        k.opt[Int]("SyncTargetNumberOfEstablishedBigLedgerPeers"),
        k.opt[Int]("SyncTargetNumberOfActiveBigLedgerPeers")
      ).mapN(PeerTargetOverrides.apply)
      genesisOptions <- k.opt[Json]("LowLevelGenesisOptions").flatMap(GenesisSettings.parse)
      accepted <- k.opt[AcceptedConnectionsLimit]("AcceptedConnectionsLimit")(using acceptedDecoder)
      maxOutbound <- k
        .opt[Int]("MaxOutboundConnections")(using wholeDecoder)
        .flatMap(
          _.traverse(n =>
            OutboundLimit.Connections
              .from(n)
              .leftMap(_ =>
                ConfigError(ConfigProblem.NonPositive("MaxOutboundConnections", BigDecimal(n)))
              )
          )
        )
      deadlineConcurrency <- k
        .opt[Int]("MaxConcurrencyDeadline")
        .flatMap(n =>
          n.traverse(c =>
            Either.cond(
              c >= 0,
              c,
              ConfigError(ConfigProblem.Negative("MaxConcurrencyDeadline", BigDecimal(c)))
            )
          )
        )
      bulkConcurrency <- k
        .opt[Int]("MaxConcurrencyBulkSync")
        .flatMap(n =>
          n.traverse(c =>
            Either.cond(
              c >= 0,
              c,
              ConfigError(ConfigProblem.Negative("MaxConcurrencyBulkSync", BigDecimal(c)))
            )
          )
        )
      minBigLedgerPeers <- k.opt[Int]("MinBigLedgerPeersForTrustedState").flatMap {
        case None                     => Right(5)
        case Some(value) if value > 0 => Right(value)
        case Some(value) =>
          Left(
            ConfigError(
              ConfigProblem.NonPositive("MinBigLedgerPeersForTrustedState", BigDecimal(value))
            )
          )
      }
      required = (name: String, present: Boolean) =>
        Either.cond(
          present || !strict,
          (),
          ConfigError(ConfigProblem.MissingField("configuration", name))
        )
      _ <- required("ByronGenesisFile (or GenesisFile)", byronFile.isDefined)
      _ <- required("ShelleyGenesisFile (or GenesisFile)", shelleyFile.isDefined)
      _ <- required("AlonzoGenesisFile", alonzoFile.isDefined)
      _ <- required("ConwayGenesisFile", conwayFile.isDefined)
      _ <- required("LastKnownBlockVersion-Major", major.isDefined)
      _ <- required("LastKnownBlockVersion-Minor", minor.isDefined)
      _ <- required("DijkstraGenesisFile", dijkstraFile.isDefined || !hardForks.contains(true))
    yield
      val standard = GenesisFiles.standard
      val (shelleyHash, alonzoHash, conwayHash, dijkstraHash, checkpointsHash) = hashes
      NodeConfig(
        byronHash,
        forks.flatten.toMap,
        magic,
        threshold,
        GenesisFiles(
          byronFile.getOrElse(standard.byron),
          shelleyFile.getOrElse(standard.shelley),
          alonzoFile.getOrElse(standard.alonzo),
          conwayFile.getOrElse(standard.conway),
          dijkstraFile,
          checkpointsFile
        ),
        GenesisHashes(
          byronHash,
          shelleyHash,
          alonzoHash,
          conwayHash,
          dijkstraHash,
          checkpointsHash
        ),
        ConfigSettings(
          protocol.getOrElse("Cardano"),
          (major, minor).mapN((a, b) => (a, b, alt.getOrElse(0))),
          maxKnown,
          hardForks.getOrElse(false),
          protocols.getOrElse(false),
          mode,
          sharing,
          diffusion,
          socket,
          database,
          mempool,
          versions.flatten.toMap,
          targets,
          syncTargets,
          minBigLedgerPeers,
          deadlineConcurrency,
          bulkConcurrency,
          genesisOptions,
          accepted.getOrElse(AcceptedConnectionsLimit.default),
          dijkstraEpoch,
          dijkstraVersion,
          leios,
          ledgerDb,
          nonProducing,
          maxOutbound,
          mempoolTimeouts
        )
      )
