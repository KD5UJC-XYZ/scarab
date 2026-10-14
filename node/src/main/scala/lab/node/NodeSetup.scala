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

  def fromFiles(read: String => Option[Array[Byte]]): Either[ConfigError, NodeSetup] =
    def required(name: String): Either[ConfigError, Array[Byte]] =
      read(name).toRight(ConfigError(s"missing $name"))
    def text(bytes: Array[Byte]) = new String(bytes, "UTF-8")
    def json(name: String, bytes: Array[Byte]): Either[ConfigError, Json] =
      parser.parse(text(bytes)).leftMap(e => ConfigError(s"$name: ${e.message}"))
    for
      shelleyBytes <- required("shelley-genesis.json")
      shelleyBase <- Genesis.shelley(text(shelleyBytes))
      config <- ConfigNames
        .collectFirst(Function.unlift(n => read(n)))
        .fold(Right(NodeConfig.empty))(b => NodeConfig.parse(text(b)))
      firstShelley = config.firstShelleyEpoch
      byronBytes <- required("byron-genesis.json")
      byron <-
        if firstShelley.contains(BigInt(0)) then Right(None)
        else ByronGenesisFile.parse(text(byronBytes), config.byronGenesisHash).map(Some(_))
      genesis <- Genesis.withByronPrefix(shelleyBase, byron, firstShelley)
      byronJson <- json("byron-genesis.json", byronBytes)
      // The network magic Byron addresses carry: the configuration says whether the network
      // requires one; without it, mainnet does not and the test networks do.
      requiresMagic = config.requiresNetworkMagic.getOrElse(
        byron.map(_.protocolMagic).getOrElse(genesis.networkMagic) != MainnetMagic
      )
      addressMagic = Option.when(requiresMagic)(
        byron.map(_.protocolMagic).getOrElse(genesis.networkMagic)
      )
      files <- (
        json("shelley-genesis.json", shelleyBytes),
        required("alonzo-genesis.json").flatMap(json("alonzo-genesis.json", _)),
        required("conway-genesis.json").flatMap(json("conway-genesis.json", _)),
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
      byronParams = byron.orElse(ByronGenesisFile.parse(text(byronBytes), None).toOption)
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
