// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
import io.circe.parser
import java.time.Instant
import lab.Blake2b
import lab.cbor.Bytes
import lab.network.Era

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

  /** The Shelley-based eras start at epoch `firstShelleyEpoch`; before it, `byron` epochs apply. */
  def withByronPrefix(
      genesis: ShelleyGenesis,
      byron: Option[ByronGenesis],
      firstShelleyEpoch: BigInt
  ): Either[ConfigError, ShelleyGenesis] =
    if firstShelleyEpoch == 0 then Right(genesis)
    else
      byron
        .toRight(ConfigError("a chain with a Byron prefix needs byron-genesis.json"))
        .map(b =>
          genesis.copy(geometry =
            genesis.geometry
              .copy(byron = ByronSpan(firstShelleyEpoch, b.epochSlots, b.slotLengthMillis))
          )
        )

  private[node] def field[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name).as[A].leftMap(e => ConfigError(s"genesis field $name: ${e.message}"))

/** A Byron heavy-delegation entry of the Byron genesis: the genesis key (extended public key)
  * delegates block signing from `epoch` on to `delegate`; `cert` is the genesis key's signature.
  */
final case class ByronDelegation(epoch: BigInt, issuer: Bytes, delegate: Bytes, cert: Bytes)

/** What the node needs from `byron-genesis.json`. `hash` is the hash the first epoch-boundary block
  * names as its predecessor.
  */
final case class ByronGenesis(
    hash: Bytes,
    protocolMagic: Long,
    securityParam: Int,
    slotLengthMillis: Long,
    delegations: Vector[ByronDelegation],
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
  * `TestShelleyHardForkAtEpoch` means the chain starts in Shelley (no Byron prefix), as on Preview.
  */
final case class NodeConfig(byronGenesisHash: Option[Bytes], hardForkEpochs: Map[Era, BigInt]):
  def firstShelleyEpoch: BigInt = hardForkEpochs.getOrElse(Era.Shelley, BigInt(0))

object NodeConfig:
  val empty: NodeConfig = NodeConfig(None, Map.empty)

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
    yield NodeConfig(hash, forks.flatten.toMap)

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
    for
      json <- parser
        .parse(text)
        .leftMap(e => ConfigError(s"byron genesis is not JSON: ${e.message}"))
      c = json.hcursor
      computed = Blake2b.hash256.hash(Bytes.fromArray(canonical(json).getBytes("UTF-8")))
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
      delegations <- c
        .downField("heavyDelegation")
        .as[Option[Map[String, Json]]]
        .leftMap(e => ConfigError(s"heavyDelegation: ${e.message}"))
        .flatMap(_.getOrElse(Map.empty).toVector.sortBy(_._1).traverse((_, j) => delegation(j)))
      start <- c
        .downField("startTime")
        .as[Option[Long]]
        .leftMap(e => ConfigError(s"startTime: ${e.message}"))
    yield ByronGenesis(hash, magic, k, slot, delegations, start)

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
