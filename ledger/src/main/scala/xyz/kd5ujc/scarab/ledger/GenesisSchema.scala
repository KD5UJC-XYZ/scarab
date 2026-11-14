// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Json}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.json.{
  AddressDecodeError,
  AddressSite,
  Aeson,
  AesonCause,
  AesonError,
  Base16,
  BoundedRatio,
  FromJson,
  Iso8601,
  JsonKind,
  JsonObject,
  JsonText,
  JsonValue,
  NumberError,
  Parser,
  ParserName
}
import xyz.kd5ujc.scarab.ledger.json.Aeson.{
  Decoder,
  boundedIntegralText,
  boundedRatio,
  either,
  field,
  fieldOpt,
  fixed,
  fromEither,
  hash,
  hashFromText,
  hashText,
  int64,
  integer,
  list,
  listMap,
  natural,
  text,
  textMap,
  traverse,
  value,
  vector,
  withObject,
  withText,
  word16,
  word32,
  word64
}

/** The Shelley, Alonzo and Conway genesis files as the reference's `FromJSON` instances read them
  * (cardano-ledger f649f975: cardano-ledger-shelley 1.19.0.1, -alonzo 1.16.0.0, -conway 1.23.0.0,
  * -core 1.21.0.0), transcribed onto aeson 2.2.5.0's combinators ([[Aeson]]): every field in the
  * order its instance reads it, with the same `withObject` names, `.:` and `.:?`, `<|>` and `<?>`
  * scopes, so that a refusal is the reference's `IError`, path and message. The node checks each
  * file at start-up, so a file the reference node would refuse is refused before any reader of it
  * runs.
  *
  *   - Shelley: `FromJSON ShelleyGenesis` (`Shelley/Genesis.hs:494-518`), `LegacyJSONPParams`
  *     (`403-437`), `ShelleyGenesisStaking` (`526-531`), `ShelleyExtraConfig` (`255-260`) and
  *     `InjectionData` (`833-846`).
  *   - Alonzo: `FromJSON AlonzoGenesis` (`Alonzo/Genesis.hs:222-242`), `AlonzoExtraConfig`
  *     (`110-114`), `Prices` and `ExUnits` (`Plutus/ExUnits.hs:118-128,176-181`) and
  *     `parseCostModels` (`Plutus/CostModels.hs:141-203`).
  *   - Conway: `FromJSON ConwayGenesis` (`Conway/Genesis.hs:156-165`), `UpgradeConwayPParams`
  *     (`Conway/PParams.hs:1082-1095`), `Constitution` and `Committee`
  *     (`Conway/Governance/Procedures.hs:603-609,966-970`).
  *
  * The readers that run after the check read the same instances by [[ratio]], [[unit]] and
  * [[updateQuorum]], so a file the check accepts is read.
  */
object GenesisSchema:
  def shelley(value: JsonValue): Either[AesonError, Unit] = shelleyGenesis(value).parse
  def alonzo(value: JsonValue): Either[AesonError, Unit] = alonzoGenesis(value).parse
  def conway(value: JsonValue): Either[AesonError, Unit] = conwayGenesis(value).parse

  /** `parseCostModels isLenient languages` on its own, as the reference's measurements call it. */
  private[ledger] def costModelsCheck(lenient: Boolean, languages: Vector[CostModelLanguage])(
      value: JsonValue
  ): Either[AesonError, Unit] = costModels(lenient, languages)(value).void.parse

  // Shared instances

  /** The generic all-nullary `FromJSON Network` (`BaseTypes.hs:878-881`). */
  private val network: Decoder[Unit] = withText(ParserName.Network)(t =>
    if t.asString == "Testnet" || t.asString == "Mainnet" then Aeson.unit
    else Aeson.fail(AesonCause.NetworkTag(t)).context(ParserName.Network)
  )

  /** `FromJSON UTCTime`: `withText "UTCTime" (Time.run Time.parseUTCTime)`. */
  private val utcTime: Decoder[Unit] = withText(ParserName.UtcTime)(t =>
    fromEither(Iso8601.checkUtcTime(t).leftMap(AesonCause.Date(_)))
  )

  /** `FromJSON (NonZero a)` (`BaseTypes/NonZero.hs:108-109`): the number, then `nonZeroM`. */
  private def nonZero(p: Decoder[BigInt]): Decoder[Unit] = v =>
    p(v).flatMap(n => if n == 0 then Aeson.fail(AesonCause.Zero) else Aeson.unit)

  /** `parseCredential` (`Credential.hs:173-190`): `T.splitOn "-"` into exactly a tag and a hash. */
  private def credentialKey(t: JsonText): Parser[Unit] =
    t.asString.split("-", -1).toList match
      case ("scriptHash" | "keyHash") :: hex :: Nil =>
        val h = JsonText.pack(hex)
        hashFromText(28)(h).fold(Aeson.fail(AesonCause.InvalidHash(h)))(_ => Aeson.unit)
      case _ => Aeson.fail(AesonCause.InvalidCredential(t))

  /** `FromJSON (Credential kr)` (`Credential.hs:159-165`): `asum` of the script and key forms. */
  private val credential: Decoder[Unit] = withObject(ParserName.Credential)(o =>
    either(o, "scriptHash", "script hash")(hash(28)).void
      .orElse(either(o, "keyHash", "key hash")(hash(28)).void)
      .orElse(Aeson.fail(AesonCause.Empty))
  )

  /** `FromJSON Anchor` (`BaseTypes.hs:1013-1017`); a `Url` is its `Text`. */
  private val anchor: Decoder[Unit] =
    withObject(ParserName.Anchor)(o => field(o, "url")(text) *> field(o, "dataHash")(hash(32)).void)

  /** `FromJSON (InjectionData k v)` (`Shelley/Genesis.hs:833-846`). */
  private def injection(entries: Decoder[Unit]): Decoder[Unit] =
    withObject(ParserName.InjectionData)(o =>
      for
        file <- fieldOpt(o, "file")(list(text))
        data <- fieldOpt(o, "data")(entries)
        _ <- (file, data) match
          case (Some(_), None) =>
            field(o, "hash")(text).flatMap(h =>
              hashFromText(32)(h).fold(Aeson.fail(AesonCause.InvalidHashFormat(h)))(_ => Aeson.unit)
            )
          case (Some(_), Some(_)) => Aeson.fail(AesonCause.FileAndData)
          case _                  => Aeson.unit
      yield ()
    )

  private def hashKey(t: JsonText): Parser[Unit] = hashText(28)(t).void

  // Shelley

  /** `parseNonce` of `LegacyJSONPParams` (`Shelley/Genesis.hs:427-437`). */
  private val nonce: Decoder[Unit] = withObject(ParserName.Nonce)(o =>
    field(o, "tag")(text).flatMap(tag =>
      tag.asString match
        case "Nonce"        => field(o, "contents")(hash(32)).void
        case "NeutralNonce" => Aeson.unit
        case _              => Aeson.fail(AesonCause.Mismatch(ParserName.Nonce, JsonKind.Object))
    )
  )

  /** `FromJSON ProtVer` (`BaseTypes.hs:211,231-236`): `mkVersion32` of the major, outside its key,
    * and a `Word32` minor.
    */
  private val protVer: Decoder[Unit] = withObject(ParserName.ProtVer)(o =>
    field(o, "major")(word32).flatMap(v =>
      if v <= Aeson.MaxVersion then Aeson.unit else Aeson.fail(AesonCause.UnsupportedVersion(v))
    ) *> field(o, "minor")(word32).void
  )

  /** `FromJSON LegacyJSONPParams` (`Shelley/Genesis.hs:403-425`) of `ShelleyPParams`
    * (`Shelley/PParams.hs:98-134`): the fees, deposits and minimums are `CompactForm Coin`, a
    * `Word64`; `parseNonce =<< (obj .: "extraEntropy")` reads the nonce outside its key.
    */
  private val shelleyParams: Decoder[Unit] = withObject(ParserName.ShelleyPParams)(o =>
    field(o, "minFeeA")(word64) *>
      field(o, "minFeeB")(word64) *>
      field(o, "maxBlockBodySize")(word32) *>
      field(o, "maxTxSize")(word32) *>
      field(o, "maxBlockHeaderSize")(word16) *>
      field(o, "keyDeposit")(word64) *>
      field(o, "poolDeposit")(word64) *>
      field(o, "eMax")(word32) *>
      field(o, "nOpt")(word16) *>
      field(o, "a0")(boundedRatio(BoundedRatio.NonNegative)) *>
      field(o, "rho")(boundedRatio(BoundedRatio.Unit)) *>
      field(o, "tau")(boundedRatio(BoundedRatio.Unit)) *>
      field(o, "decentralisationParam")(boundedRatio(BoundedRatio.Unit)) *>
      field(o, "extraEntropy")(value).flatMap(nonce) *>
      field(o, "protocolVersion")(protVer) *>
      fieldOpt(o, "minUTxOValue")(word64) *>
      fieldOpt(o, "minPoolCost")(word64).void
  )

  /** `FromJSON GenDelegPair` (`Hashes.hs:295-300`). */
  private val genDelegPair: Decoder[Unit] = withObject(ParserName.GenDelegPair)(o =>
    field(o, "delegate")(hash(28)) *> field(o, "vrf")(hash(32)).void
  )

  /** `FromJSONKey Addr`: `parseAddr` (`Address.hs:244-261`). */
  private def addressKey(t: JsonText): Parser[Unit] =
    Base16
      .decode(t)
      .fold(
        e => Aeson.fail(AesonCause.AddressHex(e)),
        bytes => fromEither(decodeAddress(t, bytes).leftMap(AesonCause.Address(_)))
      )

  /** `decodeAddrStateLenientT False False` (`Address.hs:643-675`), for its refusal: the header, the
    * payment hash, the stake reference (a strict pointer, `decodePtr`), then nothing left. A Byron
    * address is read by the node's own reader ([[GenesisState.address]]).
    */
  private def decodeAddress(t: JsonText, raw: Bytes): Either[AddressDecodeError, Unit] =
    val size = raw.size
    def byte(i: Int): Int = raw.value(i) & 0xff
    def hashAt(offset: Int): Either[AddressDecodeError, Int] =
      Either.cond(offset + 28 <= size, offset + 28, AddressDecodeError.ShortHash(size - offset, 28))
    // `decode7BitVarLength` for at most `groups` groups; then the first group's bits are checked
    def varLength(site: AddressSite, groups: Int, mask: Int, bits: Int)(
        start: Int
    ): Either[AddressDecodeError, Int] =
      @tailrec def loop(i: Int): Either[AddressDecodeError, Int] =
        val at = start + i
        if at > size - 1 then Left(AddressDecodeError.NotEnoughBytes(site))
        else if (byte(at) & 0x80) != 0 then
          if i == groups - 1 then Left(AddressDecodeError.TooManyBytes(site)) else loop(i + 1)
        else if i == groups - 1 && (byte(start) & mask) != 0x80 then
          Left(AddressDecodeError.Overlong(site, bits))
        else Right(at + 1)
      loop(0)
    def pointer(start: Int): Either[AddressDecodeError, Int] =
      varLength(AddressSite.SlotNo, 5, 0xf0, 32)(start)
        .flatMap(varLength(AddressSite.TxIx, 3, 0xfc, 16))
        .flatMap(varLength(AddressSite.CertIx, 3, 0xfc, 16))
    if size < 1 then Left(AddressDecodeError.NotEnoughBytes(AddressSite.Header))
    else if byte(0) == 0x82 then
      GenesisState
        .address(t.asString)
        .bimap(e => AddressDecodeError.ByronAddress(e.problem), _ => ())
    else if (byte(0) & 0x8e) != 0 then Left(AddressDecodeError.InvalidHeader(byte(0)))
    else
      val header = byte(0)
      for
        payment <- hashAt(1)
        stake <-
          if (header & 0x40) == 0 then hashAt(payment)
          else if (header & 0x20) != 0 then Right(payment)
          else pointer(payment)
        _ <- Either.cond(stake == size, (), AddressDecodeError.LeftOver(size - stake))
      yield ()

  /** `FromJSON StakePoolRelay` (`State/StakePool.hs:337-357`): `asum` of the three forms. */
  private val relay: Decoder[Unit] =
    val ip4: Decoder[Unit] = withText(ParserName.Ipv4)(t =>
      GenesisPoolFields.ipv4(t.asString).fold(Aeson.fail(AesonCause.NotIpv4(t)))(_ => Aeson.unit)
    )
    val ip6: Decoder[Unit] = withText(ParserName.Ipv6)(t =>
      GenesisPoolFields.ipv6(t.asString).fold(Aeson.fail(AesonCause.NotIpv6(t)))(_ => Aeson.unit)
    )
    val address = withObject(ParserName.SingleHostAddr)(o =>
      fieldOpt(o, "port")(word16) *> fieldOpt(o, "IPv4")(ip4) *> fieldOpt(o, "IPv6")(ip6).void
    )
    val name = withObject(ParserName.SingleHostName)(o =>
      fieldOpt(o, "port")(word16) *> field(o, "dnsName")(text).void
    )
    val multi = withObject(ParserName.MultiHostName)(o => field(o, "dnsName")(text).void)
    withObject(ParserName.StakePoolRelay)(o =>
      field(o, "single host address")(address)
        .orElse(field(o, "single host name")(name))
        .orElse(field(o, "multi host name")(multi))
        .orElse(Aeson.fail(AesonCause.Empty))
    )

  /** `parseJsonBase16` (`State/StakePool.hs:315-321`). */
  private val base16: Decoder[Unit] = v =>
    text(v).flatMap(t =>
      if !t.asString.forall(_ < 0x80) then Aeson.fail(AesonCause.NonAsciiText(t))
      else Base16.decode(t).fold(e => Aeson.fail(AesonCause.HexText(e)), _ => Aeson.unit)
    )

  /** `FromJSON StakePoolParams` (`State/StakePool.hs:467-481`); `owners` (a `Set`) and `relays` (a
    * `StrictSeq`) are lists, `metadata` a `StrictMaybe` of `FromJSON PoolMetadata` (`308-313`).
    */
  private val poolParams: Decoder[Unit] =
    val accountAddress = withObject(ParserName.AccountAddress)(o =>
      field(o, "network")(network) *> field(o, "credential")(credential)
    )
    val metadata: Decoder[Unit] = v =>
      v match
        case JsonValue.Null => Aeson.unit
        case other =>
          withObject(ParserName.PoolMetadata)(o =>
            field(o, "url")(text) *> field(o, "hash")(base16)
          )(other)
    withObject(ParserName.StakePoolParams)(o =>
      either(o, "poolId", "publicKey")(hash(28)) *>
        field(o, "vrf")(hash(32)) *>
        field(o, "pledge")(integer) *>
        field(o, "cost")(integer) *>
        field(o, "margin")(boundedRatio(BoundedRatio.Unit)) *>
        either(o, "accountAddress", "rewardAccount")(accountAddress) *>
        field(o, "owners")(list(hash(28))) *>
        field(o, "relays")(list(relay)) *>
        field(o, "metadata")(metadata)
    )

  private val pools: Decoder[Unit] = v => listMap(hashKey, poolParams)(v).void
  private val stake: Decoder[Unit] = v => listMap(hashKey, hash(28))(v).void
  private val funds: Decoder[Unit] = v => listMap(addressKey, integer)(v).void

  private val staking: Decoder[Unit] = withObject(ParserName.ShelleyGenesisStaking)(s =>
    field(s, "pools")(pools) *> field(s, "stake")(stake)
  )

  private val shelleyExtra: Decoder[Unit] = withObject(ParserName.ShelleyExtraConfig)(e =>
    fieldOpt(e, "initialFunds")(injection(funds)) *>
      fieldOpt(e, "stakePools")(injection(pools)) *>
      fieldOpt(e, "stakeCredentials")(injection(stake)).void
  )

  private val shelleyGenesis: Decoder[Unit] = withObject(ParserName.ShelleyGenesis)(o =>
    field(o, "systemStart")(utcTime) *>
      field(o, "networkMagic")(word32) *>
      field(o, "networkId")(network) *>
      field(o, "activeSlotsCoeff")(boundedRatio(BoundedRatio.PositiveUnit)) *>
      field(o, "securityParam")(nonZero(word64)) *>
      field(o, "epochLength")(word64) *>
      field(o, "slotsPerKESPeriod")(word64) *>
      field(o, "maxKESEvolutions")(word64) *>
      field(o, "slotLength")(fixed) *>
      field(o, "updateQuorum")(word64) *>
      field(o, "maxLovelaceSupply")(word64) *>
      field(o, "protocolParams")(shelleyParams) *>
      field(o, "genDelegs")(Aeson.map(hashKey, genDelegPair)) *>
      field(o, "initialFunds")(funds) *>
      fieldOpt(o, "staking")(staking) *>
      fieldOpt(o, "extraConfig")(shelleyExtra).void
  )

  // Alonzo

  /** `FromJSON ExUnits` (`Plutus/ExUnits.hs:118-128`): `checkWord64Bounds` outside the keys. */
  private val exUnits: Decoder[Unit] = withObject(ParserName.ExUnits) { o =>
    def bounded(n: BigInt) =
      if n <= state.Word64.max.toBigInt then Aeson.unit
      else Aeson.fail(AesonCause.UnitOutOfBounds(n))
    either(o, "memory", "exUnitsMem")(natural).flatMap(bounded) *>
      either(o, "steps", "exUnitsSteps")(natural).flatMap(bounded)
  }

  /** `FromJSON Prices` (`Plutus/ExUnits.hs:176-181`). */
  private val prices: Decoder[Unit] = withObject(ParserName.Prices)(o =>
    either(o, "priceSteps", "prSteps")(boundedRatio(BoundedRatio.NonNegative)) *>
      either(o, "priceMemory", "prMem")(boundedRatio(BoundedRatio.NonNegative)).void
  )

  /** `guardNumberOfParameters` (`Plutus/CostModels.hs:194-203`). */
  private def guardCount(
      lenient: Boolean,
      language: CostModelLanguage,
      supplied: Int
  ): Parser[Unit] =
    if lenient || supplied == language.initialCount then Aeson.unit
    else Aeson.fail(AesonCause.ParameterCount(supplied, language.initialCount))

  /** `parseCostModelAsArray` (`Plutus/CostModels.hs:188-192`): the values as `[Int64]`, which
    * `validateCostModel` accepts whatever their number.
    */
  private def costModelArray(lenient: Boolean, language: CostModelLanguage)(
      items: Vector[JsonValue]
  ): Parser[Unit] =
    list(int64)(JsonValue.Array(items)) *> guardCount(lenient, language, items.size)

  /** `parseCostModelAsMap` (`Plutus/CostModels.hs:182-186`) through `costModelFromMap` (`205-215`),
    * which names every absent parameter.
    */
  private def costModelMap(lenient: Boolean, language: CostModelLanguage)(
      o: JsonObject
  ): Parser[Unit] =
    textMap(int64)(JsonValue.Object(o)).flatMap { entries =>
      val present = entries.map(_._1.asString).toSet
      val missing = language.initialNames.filterNot(present)
      if missing.nonEmpty then Aeson.fail(AesonCause.MissingParameters(language.key, missing))
      else guardCount(lenient, language, o.size)
    }

  /** `parseCostModels isLenient languages` (`Plutus/CostModels.hs:141-180`): the languages a model
    * was given for, of `languages` or, when there are none, of every language
    * (`nonNativeLanguages`). Each model is read outside its key.
    */
  private def costModels(lenient: Boolean, languages: Vector[CostModelLanguage])(
      v: JsonValue
  ): Parser[Set[CostModelLanguage]] =
    val read = if languages.isEmpty then CostModelLanguage.values.toVector else languages
    withObject(ParserName.CostModels) { o =>
      val models = traverse(read) { language =>
        fieldOpt(o, language.key)(value).flatMap {
          case None => Aeson.pure(Option.empty[CostModelLanguage])
          case Some(JsonValue.Object(m)) =>
            costModelMap(lenient, language)(m).map(_ => Some(language))
          case Some(JsonValue.Array(items)) =>
            costModelArray(lenient, language)(items).map(_ => Some(language))
          case Some(other) => Aeson.fail(AesonCause.CostModelShape(other))
        }
      }
      // `o .:? "Unknown" .!= mempty` as a `Map Word8 [Int64]`, before `mkCostModelsLenient`
      val unknown =
        if lenient then
          fieldOpt(o, "Unknown")(
            Aeson.map(boundedIntegralText(ParserName.Word8, BigInt(0), BigInt(255)), list(int64))
          ).void
        else Aeson.unit
      models.flatMap(found => unknown.map(_ => found.flatten.toSet))
    }(v)

  private val alonzoExtra: Decoder[Unit] = withObject(ParserName.AlonzoExtraConfig)(e =>
    fieldOpt(e, "costModels")(value).flatMap(
      _.fold(Aeson.unit)(costModels(lenient = true, Vector.empty)(_).void)
    )
  )

  /** `parseCostModels False [PlutusV1] =<< o .: "costModels"` reads the models outside their key.
    */
  private val alonzoGenesis: Decoder[Unit] = withObject(ParserName.AlonzoGenesis)(o =>
    for
      _ <- field(o, "lovelacePerUTxOWord")(integer)
      models <- field(o, "costModels")(value).flatMap(
        costModels(lenient = false, Vector(CostModelLanguage.PlutusV1))
      )
      _ <- field(o, "executionPrices")(prices)
      _ <- field(o, "maxTxExUnits")(exUnits)
      _ <- field(o, "maxBlockExUnits")(exUnits)
      _ <- field(o, "maxValueSize")(word32)
      _ <- field(o, "collateralPercentage")(word16)
      _ <- field(o, "maxCollateralInputs")(word16)
      _ <- fieldOpt(o, "extraConfig")(alonzoExtra)
      _ <-
        if models.contains(CostModelLanguage.PlutusV1) then Aeson.unit
        else Aeson.fail(AesonCause.MissingPlutusV1)
    yield ()
  )

  // Conway

  private def thresholds(name: ParserName, keys: String*): Decoder[Unit] = withObject(name)(o =>
    traverse(keys.toVector)(k => field(o, k)(boundedRatio(BoundedRatio.Unit))).void
  )

  /** `parseCostModelAsArray False PlutusV3 =<< o .: "plutusV3CostModel"`: the array as a `Vector`
    * at its key, its values outside it.
    */
  private val upgradeConwayParams: Decoder[Unit] = withObject(ParserName.UpgradeConwayPParams)(o =>
    field(o, "poolVotingThresholds")(
      thresholds(
        ParserName.PoolVotingThresholds,
        "motionNoConfidence",
        "committeeNormal",
        "committeeNoConfidence",
        "hardForkInitiation",
        "ppSecurityGroup"
      )
    ) *>
      field(o, "dRepVotingThresholds")(
        thresholds(
          ParserName.DRepVotingThresholds,
          "motionNoConfidence",
          "committeeNormal",
          "committeeNoConfidence",
          "updateToConstitution",
          "hardForkInitiation",
          "ppNetworkGroup",
          "ppEconomicGroup",
          "ppTechnicalGroup",
          "ppGovGroup",
          "treasuryWithdrawal"
        )
      ) *>
      field(o, "committeeMinSize")(word16) *>
      field(o, "committeeMaxTermLength")(word32) *>
      field(o, "govActionLifetime")(word32) *>
      field(o, "govActionDeposit")(integer) *>
      field(o, "dRepDeposit")(integer) *>
      field(o, "dRepActivity")(word32) *>
      field(o, "minFeeRefScriptCostPerByte")(boundedRatio(BoundedRatio.NonNegative)) *>
      field(o, "plutusV3CostModel")(vector(value)).flatMap(
        costModelArray(lenient = false, CostModelLanguage.PlutusV3)
      )
  )

  /** `parseDRep` (`DRep.hs:148-155`). */
  private val drep: Decoder[Unit] = withText(ParserName.DRep) { t =>
    val (prefix, rest) = t.asString.span(_ != '-')
    val dashes = rest.takeWhile(_ == '-')
    if prefix == "drep" && (rest == "-alwaysAbstain" || rest == "-alwaysNoConfidence") then
      Aeson.unit
    else if prefix == "drep" && dashes == "-" then credentialKey(JsonText.pack(rest.drop(1)))
    else Aeson.fail(AesonCause.InvalidDRep(t))
  }

  /** `FromJSON Delegatee` (`Conway/TxCert.hs:398-406`). */
  private val delegatee: Decoder[Unit] = withObject(ParserName.Delegatee)(o =>
    for
      pool <- fieldOpt(o, "poolId")(hash(28))
      vote <- fieldOpt(o, "dRep")(drep)
      _ <- if pool.isEmpty && vote.isEmpty then Aeson.fail(AesonCause.NoDelegatee) else Aeson.unit
    yield ()
  )

  /** `FromJSON DRepState` (`DRep.hs:213-221`); the deposit is a `CompactForm Coin`, a `Word64`. */
  private val drepState: Decoder[Unit] = withObject(ParserName.DRepState)(o =>
    field(o, "expiry")(word64) *> fieldOpt(o, "anchor")(anchor) *> field(o, "deposit")(word64).void
  )

  private val delegs: Decoder[Unit] = v => listMap(credentialKey, delegatee)(v).void
  private val dreps: Decoder[Unit] = v => listMap(credentialKey, drepState)(v).void

  private val constitution: Decoder[Unit] = withObject(ParserName.Constitution)(c =>
    field(c, "anchor")(anchor) *> fieldOpt(c, "script")(hash(28)).void
  )

  private val committee: Decoder[Unit] = withObject(ParserName.Committee)(c =>
    field(c, "members")(Aeson.map(credentialKey, word64)) *>
      field(c, "threshold")(boundedRatio(BoundedRatio.Unit)).void
  )

  private val conwayExtra: Decoder[Unit] = withObject(ParserName.ConwayExtraConfig)(e =>
    fieldOpt(e, "delegs")(injection(delegs)) *> fieldOpt(e, "initialDReps")(injection(dreps)).void
  )

  /** `cgUpgradePParams <- parseJSON (Object obj)`: the parameters from the same object. */
  private val conwayGenesis: Decoder[Unit] = withObject(ParserName.ConwayGenesis)(o =>
    upgradeConwayParams(JsonValue.Object(o)) *>
      field(o, "constitution")(constitution) *>
      field(o, "committee")(committee) *>
      fieldOpt(o, "delegs")(delegs) *>
      fieldOpt(o, "initialDReps")(dreps) *>
      fieldOpt(o, "extraConfig")(conwayExtra).void
  )

  // Readers

  /** A bounded ratio at `path` under `cursor` ([[BoundedRatio.fromJson]]), for the readers of the
    * files: the same instance the check reads it with, so a file the check accepts is read.
    */
  def ratio(file: GenesisFile, interval: BoundedRatio)(
      cursor: ACursor,
      path: Vector[String]
  ): Either[ConfigError, Rational] =
    at(file, cursor, path).flatMap(json =>
      interval.fromJson(json).leftMap(e => fieldError(file, path, GenesisFieldError.Number(e)))
    )

  /** A unit of `FromJSON ExUnits` (`Plutus/ExUnits.hs:118-128`): `o .: first <|> o .: second` as a
    * `Natural`, then within `Word64` (so a first name out of bounds does not fall back).
    */
  def unit(file: GenesisFile)(
      cursor: ACursor,
      path: Vector[String],
      first: String,
      second: String
  ): Either[ConfigError, BigInt] =
    def readNatural(name: String) =
      at(file, cursor.downField(name), path :+ name).flatMap(json =>
        FromJson
          .natural(json)
          .leftMap(e => fieldError(file, path :+ name, GenesisFieldError.Number(e)))
      )
    readNatural(first)
      .orElse(readNatural(second))
      .flatMap(n =>
        Either.cond(
          n <= state.Word64.max.toBigInt,
          n,
          fieldError(
            file,
            path,
            GenesisFieldError.Number(NumberError.NotWord(64, json.Scientific(n, 0)))
          )
        )
      )

  /** `updateQuorum`, a `Word64` (`Shelley/Genesis.hs:507`). A quorum above the number of genesis
    * delegates is never reached, and that number is within `Int`, so a larger quorum is read as
    * `Int.MaxValue`: no vote count reaches either.
    */
  def updateQuorum(shelley: Json): Either[ConfigError, Int] =
    val path = Vector("updateQuorum")
    at(GenesisFile.Shelley, shelley.hcursor.downField("updateQuorum"), path).flatMap(json =>
      FromJson
        .word64(json)
        .bimap(
          e => fieldError(GenesisFile.Shelley, path, GenesisFieldError.Number(e)),
          w => w.toBigInt.min(BigInt(Int.MaxValue)).toInt
        )
    )

  private def at(file: GenesisFile, cursor: ACursor, path: Vector[String]) =
    cursor.focus.toRight(fieldError(file, path, GenesisFieldError.Missing))

  private def fieldError(file: GenesisFile, path: Vector[String], cause: GenesisFieldError) =
    ConfigError(ConfigProblem.GenesisField(file, path, cause))
