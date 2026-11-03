// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json, JsonObject}
import io.circe.parser
import java.time.Instant
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.byron.{ByronAddress, ByronParams}
import xyz.kd5ujc.scarab.ledger.json.{FromJson, HaskellRead, NumberError}
import xyz.kd5ujc.scarab.ledger.state.PositiveUnitInterval

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
  /** The fields are read in the order of the reference's `FromJSON ShelleyGenesis` (cardano-ledger
    * f649f975, `eras/shelley/impl/src/Cardano/Ledger/Shelley/Genesis.hs:494-512`), so that of
    * several malformed fields the same one is reported. `activeSlotsCoeff` and `securityParam` are
    * read as that instance reads them: a `PositiveUnitInterval` and a `NonZero Word64`.
    */
  def shelley(text: String): Either[ConfigError, ShelleyGenesis] =
    for
      json <- parser
        .parse(text)
        .leftMap(e => ConfigError(ConfigProblem.JsonSyntax("shelley genesis", e)))
      c = json.hcursor
      start <- field[String](c, "systemStart").flatMap(s =>
        Either
          .catchNonFatal(Instant.parse(s))
          .leftMap(e => ConfigError(ConfigProblem.InvalidTimestamp(s, e)))
      )
      magic <- field[Long](c, "networkMagic")
      f <- number(c, GenesisNumberField.ActiveSlotsCoeff)(PositiveUnitInterval.fromJson)
      k <- number(c, GenesisNumberField.SecurityParam)(FromJson.nonZeroWord64)
      epochLength <- field[Long](c, "epochLength")
      kesPeriod <- field[BigInt](c, "slotsPerKESPeriod")
      kesEvolutions <- field[BigInt](c, "maxKESEvolutions")
      slotLength <- field[BigDecimal](c, "slotLength")
      supply <- field[BigInt](c, "maxLovelaceSupply")
      millis <- Either.cond(
        (slotLength * 1000).isWhole,
        (slotLength * 1000).toLongExact,
        ConfigError(ConfigProblem.FractionalSlotMilliseconds(slotLength))
      )
      geometry <- EpochGeometry.checked(start, millis, epochLength, k, f)
    yield ShelleyGenesis(magic, geometry, kesPeriod, kesEvolutions, supply, json)

  private def number[A](c: ACursor, name: GenesisNumberField)(
      read: Json => Either[NumberError, A]
  ): Either[ConfigError, A] =
    field[Json](c, name.key).flatMap(
      read(_).leftMap(e => ConfigError(ConfigProblem.GenesisNumber(name, e)))
    )

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
    * lovelace), as `readGenesisData` reads them (`Cardano/Chain/Genesis/Data.hs:96, 102`,
    * cardano-ledger f649f975). Each field is canonical JSON's `FromJSON (Map k a)` (canonical-json
    * 0.6.0.1 `Text/JSON/Canonical/Class.hs:162-175`): of two keys that read as the same address or
    * redeem key, the first in the text holds. A non-AVVM key is `decodeAddressBase58`
    * (`Cardano/Chain/Common/Address.hs:188-189, 233-243`), a whole Byron address, kept as the
    * reference encodes it again ([[ByronAddress.reencoded]], #728); an AVVM key is `fromAvvmVK`
    * ([[avvmKey]], #729); an amount is a `Word64` read by `readEither` ([[HaskellRead.word64]],
    * #729). Measured with `scripts/oracle/byron-genesis-balances`.
    */
  def genesisBalances(
      json: Json
  ): Either[ConfigError, (Vector[(Bytes, BigInt)], Vector[(Bytes, BigInt)])] =
    val c = json.hcursor
    def balances(name: String)(key: String => Either[ConfigError, Bytes]) =
      def field(e: io.circe.DecodingFailure) =
        ConfigError(ConfigProblem.JsonField("byron genesis", name, e))
      c.downField(name)
        .as[Option[JsonObject]]
        .leftMap(field)
        .flatMap(
          _.fold(Vector.empty)(_.toVector)
            .traverse { (k, v) =>
              (
                key(k),
                v.as[String]
                  .leftMap(field)
                  .flatMap(text =>
                    HaskellRead
                      .word64(canonicalString(text))
                      .toRight(ConfigError(ConfigProblem.IntegerText("byron balance", text)))
                  )
              ).tupled
            }
        )
        .map(firstOfEach)
    (
      balances("avvmDistr")(k => avvmKey(k).leftMap(e => ConfigError(ConfigProblem.AvvmKey(k, e)))),
      balances("nonAvvmBalances")(k =>
        xyz.kd5ujc.scarab.ledger.Base58
          .decode(k)
          .toRight(ConfigError(ConfigProblem.Base58Address(k)))
          .flatMap(raw =>
            ByronAddress
              .reencoded(raw)
              .leftMap(e => ConfigError(ConfigProblem.ByronGenesisAddress(k, e)))
          )
      )
    ).tupled

  /** The `String` canonical-json gives for a JSON string: its UTF-8 bytes, a `Char` each
    * (`fromJSString`, canonical-json 0.6.0.1 `Text/JSON/Canonical/Types.hs:64, 90-91`).
    * `readEither` reads that, so a character outside ASCII is the bytes of its encoding to it.
    */
  private def canonicalString(text: String): String =
    String(
      text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      java.nio.charset.StandardCharsets.ISO_8859_1
    )

  /** The first balance of each key, in the order of the text (`Map.fromList` of the reversed
    * entries).
    */
  private def firstOfEach(balances: Vector[(Bytes, BigInt)]): Vector[(Bytes, BigInt)] =
    balances
      .foldLeft((Set.empty[Bytes], Vector.empty[(Bytes, BigInt)])) { case ((seen, kept), (k, v)) =>
        if seen.contains(k) then (seen, kept) else (seen + k, kept :+ (k -> v))
      }
      ._2

  private val Base64Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  /** `fromAvvmVK` (`Cardano/Crypto/Signing/Redeem/VerificationKey.hs:120-129`,
    * cardano-crypto-wrapper 1.7.0.0): `-` and `_` become `+` and `/`, the text is
    * base64-bytestring's `decode`, which takes padded, canonical base64 only (its length a multiple
    * of four, at most two `=` at the end and the bits they leave zero), and the key is 32 bytes.
    */
  private[ledger] def avvmKey(text: String): Either[AvvmKeyError, Bytes] =
    val b64 = text.replace('-', '+').replace('_', '/')
    val body = b64.reverse.dropWhile(_ == '=').reverse
    val pads = b64.length - body.length
    val leftover = if pads == 1 then 0x03 else if pads == 2 then 0x0f else 0
    val canonical =
      b64.length % 4 == 0 && pads <= 2 && body.forall(Base64Alphabet.contains(_)) &&
        body.lastOption.forall(last => (Base64Alphabet.indexOf(last.toInt) & leftover) == 0)
    for
      raw <- Either
        .catchNonFatal(java.util.Base64.getDecoder.decode(b64))
        .toOption
        .filter(_ => canonical)
        .toRight(AvvmKeyError.Format)
      key <- Either.cond(raw.length == 32, Bytes.fromArray(raw), AvvmKeyError.Length(raw.length))
    yield key

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
