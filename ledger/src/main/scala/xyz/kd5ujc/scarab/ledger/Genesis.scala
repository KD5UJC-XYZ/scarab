// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
import io.circe.parser
import java.time.Instant
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.byron.ByronParams

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
        .leftMap(e => ConfigError(ConfigProblem.JsonSyntax("shelley genesis", e)))
      c = json.hcursor
      magic <- field[Long](c, "networkMagic")
      start <- field[String](c, "systemStart").flatMap(s =>
        Either
          .catchNonFatal(Instant.parse(s))
          .leftMap(e => ConfigError(ConfigProblem.InvalidTimestamp(s, e)))
      )
      slotLength <- field[BigDecimal](c, "slotLength")
      epochLength <- field[Long](c, "epochLength")
      k <- field[Int](c, "securityParam")
      f <- field[BigDecimal](c, "activeSlotsCoeff")
      millis <- Either.cond(
        (slotLength * 1000).isWhole,
        (slotLength * 1000).toLongExact,
        ConfigError(ConfigProblem.FractionalSlotMilliseconds(slotLength))
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
        .toRight(ConfigError(ConfigProblem.ByronGenesisRequired))
        .map(b =>
          genesis.copy(geometry =
            genesis.geometry.copy(byron =
              firstShelleyEpoch.fold(ByronSpan.derived(b.epochSlots, b.slotLengthMillis))(e =>
                ByronSpan(e, b.epochSlots, b.slotLengthMillis)
              )
            )
          )
        )

  /** The reference node's clock starts at the Byron genesis' `startTime` (whole seconds), which on
    * a network started with `cardano-testnet` or by hand need not equal the Shelley genesis'
    * `systemStart`: the hard-fork combinator takes its system start from the Byron genesis, and the
    * Shelley value is only read for the Shelley parameters. A node that used the Shelley value
    * would run a slot clock that differs from the network's by the difference of the two (found on
    * the devnet, where the two were written a second apart). Without a Byron genesis the Shelley
    * value stands.
    */
  def withSystemStart(genesis: ShelleyGenesis, byron: Option[ByronGenesis]): ShelleyGenesis =
    byron
      .flatMap(_.startTime)
      .fold(genesis)(seconds =>
        genesis.copy(geometry = genesis.geometry.copy(systemStart = Instant.ofEpochSecond(seconds)))
      )

  private[scarab] def field[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name).as[A].leftMap(e => ConfigError(ConfigProblem.JsonField("genesis", name, e)))

/** A Byron heavy-delegation entry of the Byron genesis: the genesis key (extended public key)
  * delegates block signing from `epoch` on to `delegate`; `cert` is the genesis key's signature.
  */
final case class ByronDelegation(epoch: BigInt, issuer: Bytes, delegate: Bytes, cert: Bytes)

/** What the node needs from `byron-genesis.json`. `hash` is the hash the first epoch-boundary block
  * names as its predecessor. `genesisKeys` are the hashes of the genesis keys (the
  * `bootStakeholders`) in ascending order, which is also the PBFT round-robin order. `avvm` are the
  * redeemable balances (32-byte redeem keys), `nonAvvm` the balances at ordinary addresses (the
  * address encoding).
  * @param startTime
  *   `startTime` of the Byron genesis in whole seconds: the reference reports it as the system
  *   start of the hard-fork combinator (it may differ from the Shelley genesis `systemStart` by the
  *   sub-second part).
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
    startTime: Option[Long] = None
):
  /** A Byron epoch has 10k slots. */
  def epochSlots: BigInt = BigInt(securityParam) * 10

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
      .leftMap(e => ConfigError(ConfigProblem.JsonSyntax("byron genesis", e)))
      .flatMap(fromJson(_, configuredHash))

  def fromJson(json: Json, configuredHash: Option[Bytes]): Either[ConfigError, ByronGenesis] =
    val c = json.hcursor
    val computed = Blake2b.hash256.hash(Bytes.fromArray(canonical(json).getBytes("UTF-8")))
    for
      hash <- configuredHash.fold(Right(computed))(h =>
        Either.cond(
          h == computed,
          h,
          ConfigError(
            ConfigProblem.GenesisHashMismatch("Byron", "byron genesis", h.hex, computed.hex)
          )
        )
      )
      magic <- Genesis.field[Long](c.downField("protocolConsts"), "protocolMagic")
      k <- Genesis.field[Int](c.downField("protocolConsts"), "k")
      slot <- Genesis
        .field[String](c.downField("blockVersionData"), "slotDuration")
        .flatMap(s =>
          s.toLongOption.toRight(ConfigError(ConfigProblem.IntegerText("slotDuration", s)))
        )
      params <- ByronParams
        .fromGenesis(c.downField("blockVersionData"))
        .leftMap(e => ConfigError(ConfigProblem.LedgerValue(e)))
      delegations <- c
        .downField("heavyDelegation")
        .as[Option[Map[String, Json]]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("byron genesis", "heavyDelegation", e)))
        .flatMap(_.getOrElse(Map.empty).toVector.sortBy(_._1).traverse((_, j) => delegation(j)))
      stakeholders <- c
        .downField("bootStakeholders")
        .as[Map[String, Json]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("byron genesis", "bootStakeholders", e)))
        .flatMap(
          _.keys.toVector.sorted
            .traverse(h => Bytes.fromHex(h).leftMap(e => ConfigError(ConfigProblem.Hex(e))))
        )
      balances <- genesisBalances(json)
      start <- c
        .downField("startTime")
        .as[Option[Long]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("byron genesis", "startTime", e)))
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
        .leftMap(e => ConfigError(ConfigProblem.JsonField("byron genesis", name, e)))
        .flatMap(
          _.getOrElse(Map.empty).toVector
            .sortBy(_._1)
            .traverse { (k, v) =>
              (
                key(k),
                Either
                  .catchNonFatal(BigInt(v))
                  .leftMap(_ => ConfigError(ConfigProblem.IntegerText("byron balance", v)))
              ).tupled
            }
        )
    (
      balances("avvmDistr")(k =>
        Either
          .catchNonFatal(Bytes.fromArray(java.util.Base64.getUrlDecoder.decode(k)))
          .leftMap(e => ConfigError(ConfigProblem.Base64("avvm key", k, e)))
          .flatMap(b =>
            Either
              .cond(b.size == 32, b, ConfigError(ConfigProblem.ByteLength("avvm key", 32, b.size)))
          )
      ),
      balances("nonAvvmBalances")(k =>
        xyz.kd5ujc.scarab.ledger.Base58
          .decode(k)
          .toRight(ConfigError(ConfigProblem.Base58Address(k)))
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
            .leftMap(e => ConfigError(ConfigProblem.Base64(name, b64, e)))
        )
        .map(Bytes.fromArray)
    for
      omega <- Genesis.field[BigInt](c, "omega")
      issuer <- key("issuerPk")
      delegate <- key("delegatePk")
      cert <- Genesis
        .field[String](c, "cert")
        .flatMap(h => Bytes.fromHex(h).leftMap(e => ConfigError(ConfigProblem.Hex(e))))
    yield ByronDelegation(omega, issuer, delegate, cert)
