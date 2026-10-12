// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
import io.circe.parser
import java.time.Instant
import lab.Blake2b
import lab.cbor.Bytes
import lab.network.Era
import lab.node.ledger.byron.ByronParams

/** Network configuration read from the Shelley genesis file. */
final case class ShelleyGenesis(
    networkMagic: Long,
    geometry: EpochGeometry,
    slotsPerKesPeriod: BigInt,
    maxKesEvolutions: BigInt,
    maxLovelaceSupply: BigInt,
    raw: Json
)

object Genesis:
  def shelley(text: String): Either[ConfigError, ShelleyGenesis] =
    for
      json <- parser
        .parse(text)
        .leftMap(e => ConfigError(s"shelley genesis is not JSON: ${e.message}"))
      c = json.hcursor
      magic <- field[Long](c, "networkMagic")
      start <- field[String](c, "systemStart").flatMap(s =>
        Either.catchNonFatal(Instant.parse(s)).leftMap(_ => ConfigError(s"invalid systemStart $s"))
      )
      slotLength <- field[BigDecimal](c, "slotLength")
      epochLength <- field[Long](c, "epochLength")
      k <- field[Int](c, "securityParam")
      f <- field[BigDecimal](c, "activeSlotsCoeff")
      millis <- Either.cond(
        (slotLength * 1000).isWhole,
        (slotLength * 1000).toLongExact,
        ConfigError(s"slot length $slotLength is not a whole number of milliseconds")
      )
      geometry <- EpochGeometry.checked(start, millis, epochLength, k, f)
      kesPeriod <- field[BigInt](c, "slotsPerKESPeriod")
      kesEvolutions <- field[BigInt](c, "maxKESEvolutions")
      supply <- field[BigInt](c, "maxLovelaceSupply")
    yield ShelleyGenesis(magic, geometry, kesPeriod, kesEvolutions, supply, json)

  /** The Shelley-based eras start at epoch `firstShelleyEpoch` (before it, the Byron epochs apply),
    * or, with none given, at an epoch the Byron ledger's update state decides.
    */
  def withByronPrefix(
      genesis: ShelleyGenesis,
      byron: Option[ByronGenesis],
      firstShelleyEpoch: Option[BigInt]
  ): Either[ConfigError, ShelleyGenesis] =
    if firstShelleyEpoch.contains(BigInt(0)) then Right(genesis)
    else
      byron
        .toRight(ConfigError("a chain with a Byron prefix needs byron-genesis.json"))
        .map(b =>
          genesis.copy(geometry =
            genesis.geometry.copy(byron =
              firstShelleyEpoch.fold(ByronSpan.derived(b.epochSlots, b.slotLengthMillis))(e =>
                ByronSpan(e, b.epochSlots, b.slotLengthMillis)
              )
            )
          )
        )

  private[node] def field[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name).as[A].leftMap(e => ConfigError(s"genesis field $name: ${e.message}"))

/** A Byron heavy-delegation entry of the Byron genesis: the genesis key (extended public key)
  * delegates block signing from `epoch` on to `delegate`; `cert` is the genesis key's signature.
  */
final case class ByronDelegation(epoch: BigInt, issuer: Bytes, delegate: Bytes, cert: Bytes)

/** What the node needs from `byron-genesis.json`. `hash` is the hash the first epoch-boundary block
  * names as its predecessor. `genesisKeys` are the hashes of the genesis keys (the
  * `bootStakeholders`) in ascending order, which is also the PBFT round-robin order. `avvm` are the
  * redeemable balances (32-byte redeem keys), `nonAvvm` the balances at ordinary addresses (the
  * address encoding).
  */
final case class ByronGenesis(
    hash: Bytes,
    protocolMagic: Long,
    securityParam: Int,
    slotLengthMillis: Long,
    delegations: Vector[ByronDelegation],
    genesisKeys: Vector[Bytes],
    params: ByronParams,
    avvm: Vector[(Bytes, BigInt)],
    nonAvvm: Vector[(Bytes, BigInt)],
    /** `startTime` of the Byron genesis in whole seconds: the reference reports it as the system
      * start of the hard-fork combinator (it may differ from the Shelley genesis `systemStart` by
      * the sub-second part).
      */
    startTime: Option[Long] = None
):
  /** A Byron epoch has 10k slots. */
  def epochSlots: BigInt = BigInt(securityParam) * 10

/** The node configuration keys that shape the era history: the Byron genesis hash and the epochs at
  * which each Shelley-based era starts (`Test<Era>HardForkAtEpoch`). An absent
  * `TestShelleyHardForkAtEpoch` means the Byron ledger decides when the chain enters Shelley (a
  * public network); `0` means the chain starts in Shelley (no Byron prefix), as on Preview. The
  * Byron rules also read `RequiresNetworkMagic` and `PBftSignatureThreshold`.
  */
final case class NodeConfig(
    byronGenesisHash: Option[Bytes],
    hardForkEpochs: Map[Era, BigInt],
    requiresNetworkMagic: Option[Boolean] = None,
    pbftSignatureThreshold: Option[Double] = None
):
  /** The configured first Shelley epoch, if the configuration fixes it. */
  def firstShelleyEpoch: Option[BigInt] = hardForkEpochs.get(Era.Shelley)

object NodeConfig:
  val empty: NodeConfig = NodeConfig(None, Map.empty)

  /** The ouroboros-consensus default (`defaultPBftSignatureThreshold`). */
  val DefaultPBftSignatureThreshold: Double = 0.22

  def parse(text: String): Either[ConfigError, NodeConfig] =
    for
      json <- parser
        .parse(text)
        .leftMap(e => ConfigError(s"configuration is not JSON: ${e.message}"))
      c = json.hcursor
      hash <- c
        .downField("ByronGenesisHash")
        .as[Option[String]]
        .leftMap(e => ConfigError(s"ByronGenesisHash: ${e.message}"))
        .flatMap(_.traverse(h => Bytes.fromHex(h).leftMap(ConfigError(_))))
      forks <- Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
        .traverse(era =>
          c.downField(s"Test${era}HardForkAtEpoch")
            .as[Option[BigInt]]
            .leftMap(e => ConfigError(s"Test${era}HardForkAtEpoch: ${e.message}"))
            .map(_.map(era -> _))
        )
      magic <- c
        .downField("RequiresNetworkMagic")
        .as[Option[String]]
        .leftMap(e => ConfigError(s"RequiresNetworkMagic: ${e.message}"))
        .flatMap {
          case None                    => Right(None)
          case Some("RequiresMagic")   => Right(Some(true))
          case Some("RequiresNoMagic") => Right(Some(false))
          case Some(other)             => Left(ConfigError(s"RequiresNetworkMagic: $other"))
        }
      threshold <- c
        .downField("PBftSignatureThreshold")
        .as[Option[Double]]
        .leftMap(e => ConfigError(s"PBftSignatureThreshold: ${e.message}"))
    yield NodeConfig(hash, forks.flatten.toMap, magic, threshold)

object ByronGenesisFile:
  /** Canonical JSON as the Byron genesis hash defines it: object keys sorted, no whitespace. */
  def canonical(json: Json): String =
    json.fold(
      "null",
      b => b.toString,
      n => n.toString,
      s => Json.fromString(s).noSpaces,
      xs => xs.map(canonical).mkString("[", ",", "]"),
      o =>
        o.toVector
          .sortBy(_._1)
          .map((k, v) => Json.fromString(k).noSpaces + ":" + canonical(v))
          .mkString("{", ",", "}")
    )

  def parse(text: String, configuredHash: Option[Bytes]): Either[ConfigError, ByronGenesis] =
    parser
      .parse(text)
      .leftMap(e => ConfigError(s"byron genesis is not JSON: ${e.message}"))
      .flatMap(fromJson(_, configuredHash))

  def fromJson(json: Json, configuredHash: Option[Bytes]): Either[ConfigError, ByronGenesis] =
    val c = json.hcursor
    val computed = Blake2b.hash256.hash(Bytes.fromArray(canonical(json).getBytes("UTF-8")))
    for
      hash <- configuredHash.fold(Right(computed))(h =>
        Either.cond(
          h == computed,
          h,
          ConfigError(s"ByronGenesisHash ${h.hex} differs from the genesis file's ${computed.hex}")
        )
      )
      magic <- Genesis.field[Long](c.downField("protocolConsts"), "protocolMagic")
      k <- Genesis.field[Int](c.downField("protocolConsts"), "k")
      slot <- Genesis
        .field[String](c.downField("blockVersionData"), "slotDuration")
        .flatMap(s => s.toLongOption.toRight(ConfigError(s"slotDuration $s")))
      params <- ByronParams.fromGenesis(c.downField("blockVersionData")).leftMap(ConfigError(_))
      delegations <- c
        .downField("heavyDelegation")
        .as[Option[Map[String, Json]]]
        .leftMap(e => ConfigError(s"heavyDelegation: ${e.message}"))
        .flatMap(_.getOrElse(Map.empty).toVector.sortBy(_._1).traverse((_, j) => delegation(j)))
      stakeholders <- c
        .downField("bootStakeholders")
        .as[Map[String, Json]]
        .leftMap(e => ConfigError(s"bootStakeholders: ${e.message}"))
        .flatMap(_.keys.toVector.sorted.traverse(h => Bytes.fromHex(h).leftMap(ConfigError(_))))
      balances <- genesisBalances(json)
      start <- c
        .downField("startTime")
        .as[Option[Long]]
        .leftMap(e => ConfigError(s"startTime: ${e.message}"))
    yield ByronGenesis(
      hash,
      magic,
      k,
      slot,
      delegations,
      stakeholders,
      params,
      balances._1,
      balances._2,
      start
    )

  /** The balances of the genesis file: AVVM (redeem key, lovelace) and non-AVVM (address,
    * lovelace), in key order. All a chain that starts in Shelley needs of its Byron genesis.
    */
  def genesisBalances(
      json: Json
  ): Either[ConfigError, (Vector[(Bytes, BigInt)], Vector[(Bytes, BigInt)])] =
    val c = json.hcursor
    def balances(name: String)(key: String => Either[ConfigError, Bytes]) =
      c.downField(name)
        .as[Option[Map[String, String]]]
        .leftMap(e => ConfigError(s"byron $name: ${e.message}"))
        .flatMap(
          _.getOrElse(Map.empty).toVector
            .sortBy(_._1)
            .traverse { (k, v) =>
              (
                key(k),
                Either.catchNonFatal(BigInt(v)).leftMap(_ => ConfigError(s"byron balance $v"))
              ).tupled
            }
        )
    (
      balances("avvmDistr")(k =>
        Either
          .catchNonFatal(Bytes.fromArray(java.util.Base64.getUrlDecoder.decode(k)))
          .leftMap(_ => ConfigError(s"avvm key $k"))
          .filterOrElse(_.size == 32, ConfigError(s"avvm key $k is not 32 bytes"))
      ),
      balances("nonAvvmBalances")(k =>
        lab.node.ledger.Base58.decode(k).toRight(ConfigError(s"byron address $k"))
      )
    ).tupled

  private def delegation(j: Json): Either[ConfigError, ByronDelegation] =
    val c = j.hcursor
    def key(name: String) =
      Genesis
        .field[String](c, name)
        .flatMap(b64 =>
          Either
            .catchNonFatal(java.util.Base64.getDecoder.decode(b64))
            .leftMap(_ => ConfigError(s"$name is not base64"))
        )
        .map(Bytes.fromArray)
    for
      omega <- Genesis.field[BigInt](c, "omega")
      issuer <- key("issuerPk")
      delegate <- key("delegatePk")
      cert <- Genesis
        .field[String](c, "cert")
        .flatMap(h => Bytes.fromHex(h).leftMap(ConfigError(_)))
    yield ByronDelegation(omega, issuer, delegate, cert)
