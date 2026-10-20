// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import cats.syntax.all.*
import io.circe.{Decoder, HCursor, Json, parser}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NtnVersion}
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
  *   `MaxConcurrencyDeadline` (`POM.hs` 345): how many peers may be asked for the same block in the
  *   fetch mode deadline; absent is the library's default, 1.
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
    maxConcurrencyDeadline: Option[Int] = None
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
      Option.when(consensusMode == ConsensusMode.GenesisMode)(
        "ConsensusMode GenesisMode: chain-density and ledger-dependent fork choice are not " +
          "implemented; peer selection provides sync targets and trusted-state hooks only"
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

  /** Typed access to the keys of one configuration object; `null` counts as absent. */
  private final class Keys(c: HCursor):
    def opt[A: Decoder](name: String): Either[ConfigError, Option[A]] =
      c.downField(name)
        .focus
        .filterNot(_.isNull)
        .traverse(
          _.as[A].leftMap(e => ConfigError(ConfigProblem.JsonField("configuration", name, e)))
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
        k.opt[BigInt](s"Test${era}HardForkAtEpoch").map(_.map(era -> _))
      )
      versions <- Eras.traverse(era =>
        k.opt[BigInt](s"Test${era}HardForkAtVersion").map(_.map(era -> _))
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
      deadlineConcurrency <- k
        .opt[Int]("MaxConcurrencyDeadline")
        .flatMap(n =>
          n.traverse(c =>
            Either.cond(
              c >= 1,
              c,
              ConfigError(ConfigProblem.NonPositive("MaxConcurrencyDeadline", BigDecimal(c)))
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
          deadlineConcurrency
        )
      )
