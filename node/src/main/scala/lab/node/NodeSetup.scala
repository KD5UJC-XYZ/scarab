// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.syntax.all.*
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import lab.Blake2b
import lab.cbor.Bytes
import lab.node.consensus.{ByronRules, Praos, PraosConfig}
import lab.node.ledger.{GenesisState, LedgerGlobals, LiteLedger, Rational}
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
    start: NodeState
):
  def geometry: EpochGeometry = genesis.geometry
  def byronRules: Option[ByronRules.Config] = byron.map(ByronRules.Config(_))

  /** The hash the chain's first block must name as its predecessor. */
  def origin: Option[Bytes] = byron.map(_.hash)

object NodeSetup:
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
        if firstShelley == 0 then Right(None)
        else ByronGenesisFile.parse(text(byronBytes), config.byronGenesisHash).map(Some(_))
      genesis <- Genesis.withByronPrefix(shelleyBase, byron, firstShelley)
      files <- (
        json("shelley-genesis.json", shelleyBytes),
        required("alonzo-genesis.json").flatMap(json("alonzo-genesis.json", _)),
        required("conway-genesis.json").flatMap(json("conway-genesis.json", _)),
        json("byron-genesis.json", byronBytes)
      ).mapN(GenesisState.Files.apply)
      globals = LedgerGlobals(
        genesis.maxLovelaceSupply,
        genesis.geometry.epochLength,
        Rational.fromDecimal(genesis.geometry.activeSlotsCoeff),
        network = if genesis.networkMagic == 764824073L then 1 else 0
      )
      initial <- GenesisState.initial(files, globals)
      f = genesis.geometry.activeSlotsCoeff
      active <- PraosLeaderThreshold.Fraction
        .checked(BigInt(f.bigDecimal.unscaledValue), BigInt(10).pow(f.bigDecimal.scale.max(0)))
        .leftMap(ConfigError(_))
      praos = PraosConfig(
        genesis.geometry,
        genesis.slotsPerKesPeriod,
        genesis.maxKesEvolutions,
        active
      )
      lite <- LiteLedger.fromGenesis(files.shelley, firstShelley)
    yield NodeSetup(
      genesis,
      config,
      byron,
      globals,
      praos,
      NodeState(
        Praos.initial(Blake2b.hash256.hash(Bytes.fromArray(shelleyBytes))),
        initial,
        BootstrapState.initial(lite)
      )
    )
