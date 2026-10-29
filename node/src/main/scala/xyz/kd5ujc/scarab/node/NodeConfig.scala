// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigNumberField, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.json.FromJson
import cats.syntax.all.*
import io.circe.{Decoder, HCursor, Json, parser}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NtnVersion}
import xyz.kd5ujc.scarab.network.{AcceptedConnectionsLimit, DiffTime, Word32}
import xyz.kd5ujc.scarab.network.selection.{ConsensusMode, Targets}

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
)

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
  *   `MempoolCapacityBytesOverride` as a number of bytes (`NoOverride` is `None`).
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
    mempoolCapacityBytes: Option[Long] = None,
    hardForkVersions: Map[Era, BigInt] = Map.empty,
    peerTargets: PeerTargetOverrides = PeerTargetOverrides(),
    syncPeerTargets: PeerTargetOverrides = PeerTargetOverrides(),
    minBigLedgerPeersForTrustedState: Int = 5,
    maxConcurrencyDeadline: Option[Int] = None,
    maxConcurrencyBulkSync: Option[Int] = None,
    genesis: GenesisSettings = GenesisSettings(),
    acceptedConnectionsLimit: AcceptedConnectionsLimit = AcceptedConnectionsLimit.default
):
  /** The highest node-to-node version the node offers and accepts: 15 unless
    * `ExperimentalProtocolsEnabled` is set, as the reference's `limitToLatestReleasedVersion`.
    */
  def maxNtnVersion: NtnVersion =
    NtnVersion.maximum(experimentalProtocols = experimentalProtocolsEnabled)

  /** POM.hs:899-905: relays default on, producers off; an explicit setting wins. */
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
    ).flatten

/** The node configuration keys that shape the era history: the Byron genesis hash and the epochs at
  * which each Shelley-based era starts (`Test<Era>HardForkAtEpoch`). An absent
  * `TestShelleyHardForkAtEpoch` means the Byron ledger decides when the chain enters Shelley (a
  * public network); `0` means the chain starts in Shelley (no Byron prefix), as on Preview. The
  * Byron rules also read `RequiresNetworkMagic` and `PBftSignatureThreshold`. The genesis files and
  * their hashes, the protocol and the other settings are read as `cardano-node` reads them (see
  * [[NodeConfig.parse]]).
  */
final case class NodeConfig(
    byronGenesisHash: Option[Bytes],
    hardForkEpochs: Map[Era, BigInt],
    requiresNetworkMagic: Option[Boolean] = None,
    pbftSignatureThreshold: Option[Double] = None,
    files: GenesisFiles = GenesisFiles.standard,
    hashes: GenesisHashes = GenesisHashes(),
    settings: ConfigSettings = ConfigSettings()
):
  /** The configured first Shelley epoch, if the configuration fixes it. */
  def firstShelleyEpoch: Option[BigInt] = hardForkEpochs.get(Era.Shelley)

object NodeConfig:
  val empty: NodeConfig = NodeConfig(None, Map.empty)

  /** The ouroboros-consensus default (`defaultPBftSignatureThreshold`). */
  val DefaultPBftSignatureThreshold: Double = 0.22

  private val Eras = Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)

  /** aeson's `FromJSON Word32` (`parseBoundedIntegral`): a JSON number (not a string) with an
    * integral value in `0 .. 2^32 - 1`, in any notation (`512`, `512.0`, `5.12e2`).
    */
  private val word32Decoder: Decoder[Word32] = Decoder.decodeJson.emap(json =>
    json.asNumber
      .flatMap(_.toLong)
      .flatMap(Word32.fromLong)
      .toRight("a Word32: an integral number from 0 to 4294967295")
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

  /** The reference's `FromJSON AcceptedConnectionsLimit` (ouroboros-network 2fb69829,
    * `ouroboros-network/orphan-instances/Ouroboros/Network/OrphanInstances.hs:303-308`): the three
    * keys are required, the limits `Word32` and the delay a `DiffTime`
    * (`Server/RateLimiting.hs:26-42`).
    */
  private val acceptedDecoder: Decoder[AcceptedConnectionsLimit] =
    Decoder.forProduct3[AcceptedConnectionsLimit, Word32, Word32, DiffTime](
      "hardLimit",
      "softLimit",
      "delay"
    )(AcceptedConnectionsLimit.apply)(using word32Decoder, word32Decoder, diffTimeDecoder)

  /** Typed access to the keys of one configuration object; `null` counts as absent. */
  private final class Keys(c: HCursor):
    def opt[A: Decoder](name: String): Either[ConfigError, Option[A]] =
      c.downField(name)
        .focus
        .filterNot(_.isNull)
        .traverse(
          _.as[A].leftMap(e => ConfigError(ConfigProblem.JsonField("configuration", name, e)))
        )

    /** `FromJSON Word64` (`parseBoundedIntegral`): `EpochNo` derives it, and `Word` is 64 bits wide
      * on the platforms the reference is built for.
      */
    def word64(field: ConfigNumberField): Either[ConfigError, Option[BigInt]] =
      c.downField(field.key)
        .focus
        .filterNot(_.isNull)
        .traverse(
          FromJson
            .word64(_)
            .bimap(e => ConfigError(ConfigProblem.ConfigNumber(field, e)), _.toBigInt)
        )

    def hash(name: String): Either[ConfigError, Option[Bytes]] =
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
  private def fileName(k: Keys, role: String): Either[ConfigError, Option[String]] =
    (k.opt[String](s"${role}GenesisFile"), k.opt[String]("GenesisFile")).tupled.flatMap {
      case (Some(_), Some(_)) =>
        Left(
          ConfigError(ConfigProblem.ConflictingFields(Set(s"${role}GenesisFile", "GenesisFile")))
        )
      case (a, b) => Right(a.orElse(b))
    }

  /** The renamed boolean `name`, which must agree with its old name when both are given. */
  private def renamed(
      k: Keys,
      name: String,
      old: String
  ): Either[ConfigError, Option[Boolean]] =
    (k.opt[Boolean](name), k.opt[Boolean](old)).tupled.flatMap { (value, previous) =>
      Either.cond(
        previous.forall(value.contains),
        value,
        ConfigError(ConfigProblem.RenamedField(old, name))
      )
    }

  private def capacity(k: Keys): Either[ConfigError, Option[Long]] =
    k.opt[Json]("MempoolCapacityBytesOverride").flatMap {
      case None => Right(None)
      case Some(j) =>
        j.asNumber.flatMap(_.toLong).filter(_ > 0) match
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
    * protocol must be `Cardano`, `GenesisFile` must not be given next to the specific name, and a
    * listed hash must be 32 bytes of hexadecimal.
    */
  def parse(text: String, strict: Boolean = false): Either[ConfigError, NodeConfig] =
    for
      json <- parser
        .parse(text)
        .leftMap(e => ConfigError(ConfigProblem.JsonSyntax("configuration", e)))
      _ <- Either.cond(
        json.isObject,
        (),
        ConfigError(ConfigProblem.ExpectedObject("configuration", json))
      )
      k = Keys(json.hcursor)
      byronHash <- k.hash("ByronGenesisHash")
      forks <- Eras.traverse(era =>
        k.word64(ConfigNumberField.HardForkAtEpoch(era)).map(_.map(era -> _))
      )
      versions <- Eras.traverse(era =>
        k.word64(ConfigNumberField.HardForkAtVersion(era)).map(_.map(era -> _))
      )
      magic <- k
        .opt[String]("RequiresNetworkMagic")
        .flatMap(
          oneOf("RequiresNetworkMagic", _, Set("RequiresMagic", "RequiresNoMagic"))
        )
        .map(_.map(_ == "RequiresMagic"))
      threshold <- k.opt[Double]("PBftSignatureThreshold")
      byronFile <- fileName(k, "Byron")
      shelleyFile <- fileName(k, "Shelley")
      alonzoFile <- k.opt[String]("AlonzoGenesisFile")
      conwayFile <- k.opt[String]("ConwayGenesisFile")
      dijkstraFile <- k.opt[String]("DijkstraGenesisFile")
      checkpointsFile <- k.opt[String]("CheckpointsFile")
      hashes <- (
        k.hash("ShelleyGenesisHash"),
        k.hash("AlonzoGenesisHash"),
        k.hash("ConwayGenesisHash"),
        k.hash("DijkstraGenesisHash"),
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
      hardForks <- renamed(k, "ExperimentalHardForksEnabled", "TestEnableDevelopmentHardForkEras")
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
      diffusion <- k
        .opt[String]("DiffusionMode")
        .flatMap(oneOf("DiffusionMode", _, Set("InitiatorOnly", "InitiatorAndResponder")))
      socket <- k.opt[String]("SocketPath")
      database <- k.opt[String]("DatabasePath")
      mempool <- capacity(k)
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
          accepted.getOrElse(AcceptedConnectionsLimit.default)
        )
      )
