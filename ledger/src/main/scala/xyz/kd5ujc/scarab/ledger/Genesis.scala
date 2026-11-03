// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
import io.circe.parser
import java.time.Instant
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.byron.{ByronAddress, ByronParams}
import xyz.kd5ujc.scarab.ledger.json.{
  CanonicalJson,
  FromJson,
  HaskellRead,
  NumberError,
  StrictBase64
}
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
      .map(_.startTime)
      .fold(genesis)(seconds =>
        genesis.copy(geometry = genesis.geometry.copy(systemStart = Instant.ofEpochSecond(seconds)))
      )

  private[scarab] def field[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name).as[A].leftMap(e => ConfigError(ConfigProblem.JsonField("genesis", name, e)))

/** A Byron heavy-delegation entry of the Byron genesis: the genesis key (extended public key)
  * delegates block signing from `epoch` on to `delegate`; `cert` is the genesis key's signature.
  */
final case class ByronDelegation(epoch: BigInt, issuer: Bytes, delegate: Bytes, cert: Bytes)

/** `byron-genesis.json` as `readGenesisData` reads it into `GenesisData`
  * (`Cardano/Chain/Genesis/Data.hs:52-62, 85-102, 169-181`, cardano-ledger f649f975), with `hash`,
  * the hash the first epoch-boundary block names as its predecessor. `genesisKeys` are the hashes
  * of the genesis keys (the `bootStakeholders`) in ascending order, which is also the PBFT
  * round-robin order; `delegations` are in the order of their keys. `avvm` are the redeemable
  * balances (32-byte redeem keys), `nonAvvm` the balances at ordinary addresses (the address
  * encoding).
  * @param startTime
  *   `startTime` in whole seconds (an `Int54`): the reference reports it as the system start of the
  *   hard-fork combinator (it may differ from the Shelley genesis `systemStart` by the sub-second
  *   part).
  * @param securityParam
  *   `k`, a `BlockCount` (`Word64`) taken from an `Int54` by `fromIntegral`.
  * @param protocolMagic
  *   a `Word32` taken from an `Int54` by `fromIntegral`.
  */
final case class ByronGenesis(
    hash: Bytes,
    genesisKeys: Vector[Bytes],
    delegations: Vector[ByronDelegation],
    startTime: Long,
    nonAvvm: Vector[(Bytes, BigInt)],
    params: ByronParams,
    securityParam: BigInt,
    protocolMagic: Long,
    avvm: Vector[(Bytes, BigInt)]
):
  /** `kEpochSlots` (`Cardano/Chain/ProtocolConstants.hs:44-45`): 10k slots, a `Word64`. */
  def epochSlots: BigInt = (securityParam * 10).mod(BigInt(1) << 64)

  /** `ppSlotDuration`, in milliseconds (`fromByronSlotLength`, ouroboros-consensus 82ecba32
    * `Ouroboros/Consensus/Byron/Ledger/Conversions.hs:50-52`).
    */
  def slotLengthMillis: BigInt = params.slotDurationMs

object ByronGenesisFile:
  /** The Byron genesis file as `readGenesisData` reads it (`Cardano/Chain/Genesis/Data.hs:169-181`,
    * cardano-ledger f649f975): its bytes parsed by `parseCanonicalJSON` ([[CanonicalJson.parse]]),
    * and its hash, `blake2b-256` of `renderCanonicalJSON` of what was parsed (`:179`), #730.
    */
  def read(bytes: Bytes): Either[ConfigError, (CanonicalJson, Bytes)] =
    CanonicalJson
      .parse(bytes)
      .leftMap(e => ConfigError(ConfigProblem.CanonicalJsonSyntax("byron genesis", e)))
      .map(v => (v, Blake2b.hash256.hash(CanonicalJson.render(v))))

  /** The genesis of `bytes`, every field read as `FromJSON GenesisData` reads it (#734), and its
    * hash checked against the configured one.
    */
  def parse(bytes: Bytes, configuredHash: Option[Bytes]): Either[ConfigError, ByronGenesis] =
    read(bytes).flatMap((value, computed) => fromJson(value, computed, configuredHash))

  /** The genesis `value` whose hash is `computed`: `readGenesisData` reads the fields before the
    * node compares the hash with the configured one.
    */
  def fromJson(
      value: CanonicalJson,
      computed: Bytes,
      configuredHash: Option[Bytes]
  ): Either[ConfigError, ByronGenesis] =
    for
      f <- ByronGenesisRead.fields(value)
      hash <- configuredHash.fold(Right(computed))(h =>
        Either.cond(
          h == computed,
          h,
          ConfigError(
            ConfigProblem.GenesisHashMismatch("Byron", "byron genesis", h.hex, computed.hex)
          )
        )
      )
    yield ByronGenesis(
      hash,
      f.genesisKeys,
      f.delegations,
      f.startTime,
      f.nonAvvm,
      f.params,
      f.securityParam,
      f.protocolMagic,
      f.avvm
    )

  /** The balances of the genesis file: AVVM (redeem key, lovelace) and non-AVVM (address,
    * lovelace), read as `FromJSON GenesisData` reads them, the non-AVVM balances first
    * (`Cardano/Chain/Genesis/Data.hs:96, 102`, cardano-ledger f649f975).
    */
  def genesisBalances(
      value: CanonicalJson
  ): Either[ConfigError, (Vector[(Bytes, BigInt)], Vector[(Bytes, BigInt)])] =
    for
      nonAvvm <- nonAvvmBalances(value)
      avvm <- avvmBalances(value)
    yield (avvm, nonAvvm)

  /** `nonAvvmBalances`: canonical JSON's `FromJSON (Map k a)` (canonical-json 0.6.0.1
    * `Text/JSON/Canonical/Class.hs:162-175`), so of two keys that read as the same address the
    * first in the text holds. A key is `decodeAddressBase58`
    * (`Cardano/Chain/Common/Address.hs:188-189, 233-243`), a whole Byron address, kept as the
    * reference encodes it again ([[ByronAddress.reencoded]], #728); an amount is a `Word64` read by
    * `readEither` ([[HaskellRead.word64]], #729). Measured with
    * `scripts/oracle/byron-genesis-balances`.
    */
  def nonAvvmBalances(value: CanonicalJson): Either[ConfigError, Vector[(Bytes, BigInt)]] =
    balances(value, ByronGenesisField.NonAvvmBalances)(k =>
      xyz.kd5ujc.scarab.ledger.Base58
        .decode(k)
        .toRight(ConfigError(ConfigProblem.Base58Address(k)))
        .flatMap(raw =>
          ByronAddress
            .reencoded(raw)
            .leftMap(e => ConfigError(ConfigProblem.ByronGenesisAddress(k, e)))
        )
    )

  /** `avvmDistr`, as [[nonAvvmBalances]] but each key `fromAvvmVK` ([[avvmKey]], #729). */
  def avvmBalances(value: CanonicalJson): Either[ConfigError, Vector[(Bytes, BigInt)]] =
    balances(value, ByronGenesisField.AvvmDistr)(k =>
      avvmKey(k).leftMap(e => ConfigError(ConfigProblem.AvvmKey(k, e)))
    )

  // `fromJSField`: the field is required, and is an object whose every entry is read, its key and
  // then its amount (`FromJSON Word64`: a string, read by `readEither` as `fromJSString` gives it)
  private def balances(value: CanonicalJson, field: ByronGenesisField)(
      key: String => Either[ConfigError, Bytes]
  ): Either[ConfigError, Vector[(Bytes, BigInt)]] =
    def refuse[A](error: ByronFieldError): Either[ConfigError, A] =
      Left(ConfigError(ConfigProblem.ByronGenesisValue(field, error)))
    def amount(v: CanonicalJson): Either[ConfigError, BigInt] = v match
      case CanonicalJson.Str(b) =>
        val text = CanonicalJson.string(b)
        HaskellRead.word64(text).fold(refuse(ByronFieldError.NumberText(text)))(Right(_))
      case other => refuse(ByronFieldError.Kind(CanonicalJson.Kind.String, other.kind))
    for
      _ <- ByronGenesisRead.entries(ByronGenesisField.Top, value)
      entry <- value.field(field.key).fold(refuse(ByronFieldError.Missing))(Right(_))
      entries <- ByronGenesisRead.entries(field, entry)
      read <- entries.traverse((k, v) => (key(CanonicalJson.string(k)), amount(v)).tupled)
    yield firstOfEach(read)

  /** The first balance of each key, in the order of the text (`Map.fromList` of the reversed
    * entries).
    */
  private def firstOfEach(balances: Vector[(Bytes, BigInt)]): Vector[(Bytes, BigInt)] =
    balances
      .foldLeft((Set.empty[Bytes], Vector.empty[(Bytes, BigInt)])) { case ((seen, kept), (k, v)) =>
        if seen.contains(k) then (seen, kept) else (seen + k, kept :+ (k -> v))
      }
      ._2

  /** `fromAvvmVK` (`Cardano/Crypto/Signing/Redeem/VerificationKey.hs:120-129`,
    * cardano-crypto-wrapper 1.7.0.0): `-` and `_` become `+` and `/`, the text is
    * base64-bytestring's `decode` ([[StrictBase64]]), and the key is 32 bytes.
    */
  private[ledger] def avvmKey(text: String): Either[AvvmKeyError, Bytes] =
    for
      raw <- StrictBase64
        .decode(text.replace('-', '+').replace('_', '/'))
        .toRight(AvvmKeyError.Format)
      key <- Either.cond(raw.size == 32, raw, AvvmKeyError.Length(raw.size))
    yield key
