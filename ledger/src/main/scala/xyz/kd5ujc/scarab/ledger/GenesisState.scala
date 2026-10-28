// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json, JsonObject}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}

/** The genesis files and the network constants read from them, and the readers of their injections
  * (`initialFunds`, `stakePools`, `stakeCredentials`, Conway DReps and delegations). The ledger
  * state at the start of a chain is built from them by [[rules.GenesisStart]].
  */
object GenesisState:
  /** The genesis files; `byronNetworkMagic` is the magic Byron addresses carry on a network that
    * requires one (it decides the redeem addresses of the AVVM balances).
    */
  final case class Files(
      shelley: Json,
      alonzo: Json,
      conway: Json,
      byron: Json,
      byronNetworkMagic: Option[Long] = None
  )

  /** Network constants from the genesis files. */
  def globals(
      files: Files,
      genesis: xyz.kd5ujc.scarab.ledger.ShelleyGenesis
  ): Either[ConfigError, LedgerGlobals] =
    val c = files.conway.hcursor
    // `FromJSON ConwayGenesis` requires every field read here: a refusal is the node's, not "no
    // Conway parameters" ([[GenesisSchema.conway]])
    val conway =
      for
        base <- (
          get[BigInt](c, "dRepDeposit"),
          get[BigInt](c, "dRepActivity"),
          get[BigInt](c, "govActionDeposit"),
          get[BigInt](c, "govActionLifetime"),
          GenesisSchema.ratio(GenesisFile.Conway, json.BoundedRatio.NonNegative)(
            c.downField("minFeeRefScriptCostPerByte"),
            Vector("minFeeRefScriptCostPerByte")
          )
        ).mapN(ConwayParams(_, _, _, _, _))
        voting <- ConwayGenesis.votingParams(c)
        governance <- ConwayGenesis.committeeAndConstitution(c)
        v3 <- GenesisCostModels
          .conwayPlutusV3(files.conway)
          .leftMap(e => ConfigError(ConfigProblem.GenesisCostModel(e)))
      yield
        val (pool, drep, minSize, maxTerm) = voting
        val (committee, constitution) = governance
        base.copy(
          plutusV3CostModel = v3.map(BigInt(_)),
          poolVotingThresholds = pool,
          drepVotingThresholds = drep,
          committeeMinSize = minSize,
          committeeMaxTermLength = maxTerm,
          committee = committee,
          constitution = constitution
        )
    (
      conway,
      GenesisSchema.updateQuorum(files.shelley),
      get[BigInt](files.alonzo.hcursor, "lovelacePerUTxOWord")
    ).mapN { (conwayParams, quorum, perWord) =>
      LedgerGlobals(
        genesis.maxLovelaceSupply,
        genesis.geometry.epochLength,
        genesis.geometry.activeSlotsCoeff.rational,
        network = if genesis.networkMagic == 764824073L then 1 else 0,
        updateQuorum = quorum,
        stabilityWindow = genesis.geometry.stabilityWindow.toBigInt,
        startRewards = genesis.geometry.randomnessStabilisationWindow.toBigInt,
        firstEpoch = genesis.geometry.firstShelleyEpoch,
        conway = Some(conwayParams),
        alonzoCoinsPerUTxOWord = perWord
      )
    }

  def output(hex: String, coin: BigInt): Either[ConfigError, (TxIn, TxOut)] =
    Bytes.fromHex(hex).leftMap(e => ConfigError(ConfigProblem.Hex(e))).flatMap(outputBytes(_, coin))

  /** The JSON Addr decoder and its native serialization (`Address.hs:254-259,597-602,657-683` at
    * f649f975). JSON uses strict pointers and reserved header bits; a pseudo transaction hashes the
    * decoded address's serialization, not a noncanonical spelling in the JSON key.
    */
  opaque type FundAddress = Address

  object FundAddress:
    extension (address: FundAddress)
      def canonicalBytes: Bytes = address.raw
      def decoded: Address = address

  def address(hex: String): Either[ConfigError, FundAddress] =
    Bytes.fromHex(hex).leftMap(e => ConfigError(ConfigProblem.Hex(e))).flatMap(addressBytes)

  private def addressBytes(raw: Bytes): Either[ConfigError, FundAddress] =
    val decoded =
      if raw.size > 0 && (raw.value(0) & 0xff) != 0x82 && (raw.value(0) & 0x8e) != 0 then
        Left(LedgerDecodeError.AddressHeader(raw.value(0) & 0xff))
      else Address.decode(raw, Address.Mode.Strict)
    decoded.leftMap(e => ConfigError(ConfigProblem.LedgerValue(e))).flatMap { address =>
      address.kind match
        case Address.Kind.Byron(_) => canonicalByron(raw).map(bytes => address.copy(raw = bytes))
        case _                     => Right(address)
    }

  /** `putAddr` serializes a decoded Byron address (`Address.hs:279-281`); the root and attribute
    * values remain unchanged, while the envelope, payload and attribute map use their encoders.
    */
  private def canonicalByron(raw: Bytes): Either[ConfigError, Bytes] =
    def n(value: CborValue): Node = Node(value, Bytes.empty)
    def encode(value: CborValue): Either[ConfigError, Bytes] =
      Cbor
        .encode(value, byron.ByronCbor.BodyLimits)
        .leftMap(e => ConfigError(ConfigProblem.GenesisAddressEncoding(e)))
    for
      address <- byron.ByronAddress
        .decode(raw)
        .leftMap(e => ConfigError(ConfigProblem.LedgerValue(e)))
      attributes <- Cbor
        .decode(address.attributes, byron.ByronCbor.BodyLimits)
        .leftMap(e => ConfigError(ConfigProblem.LedgerValue(LedgerDecodeError.Syntax(e))))
      payload <- encode(
        CborValue.Arr(
          Vector(
            n(CborValue.ByteString(address.root)),
            attributes,
            n(CborValue.UInt(BigInt(address.addrType)))
          )
        )
      )
      encoded <- encode(
        CborValue.Arr(
          Vector(
            n(CborValue.Tag(BigInt(24), n(CborValue.ByteString(payload)))),
            n(CborValue.UInt(BigInt(byron.ByronCbor.crc32(payload))))
          )
        )
      )
    yield encoded

  /** A convenience output constructor; injection parsing itself retains only address and Coin. */
  def outputBytes(raw: Bytes, coin: BigInt): Either[ConfigError, (TxIn, TxOut)] =
    for
      address <- addressBytes(raw)
      output <- initialOutput(address, state.Coin(coin))
        .leftMap(e => ConfigError(ConfigProblem.GenesisStart(rules.GenesisStartError.Vocab(e))))
    yield output

  /** `mkBasicTxOut` compacts each output value (`Shelley/Transition.hs:650-653`,
    * `Shelley/TxOut.hs:139-143`, `Alonzo/TxOut.hs:326-327`, `Babbage/TxOut.hs:449-450`, f649f975).
    * `initialFundsPseudoTxIn` hashes serialiseAddr and uses minBound
    * (`Shelley/Genesis.hs:660-674`).
    */
  def initialOutput(
      address: FundAddress,
      coin: state.Coin
  ): Either[state.VocabError, (TxIn, TxOut)] =
    state.CompactCoin.fromCoin(coin).map { amount =>
      TxIn(Blake2b.hash256.hash(address.raw), 0) -> TxOut(address, Value.coin(amount.toBigInt))
    }

  def pool(id: String, j: Json): Either[ConfigError, (Bytes, PoolParams)] =
    val c = j.hcursor
    for
      operator <- hex28(id)
      vrf <- get[String](c, "vrf").flatMap(h =>
        Bytes.fromHex(h).leftMap(e => ConfigError(ConfigProblem.Hex(e)))
      )
      pledge <- get[BigInt](c, "pledge")
      cost <- get[BigInt](c, "cost")
      margin <- get[BigDecimal](c, "margin").map(Rational.fromDecimal)
      account <- get[String](c.downField("accountAddress").downField("credential"), "keyHash")
        .flatMap(hex28)
      network <- get[String](c.downField("accountAddress"), "network")
      owners <- get[Vector[String]](c, "owners").flatMap(_.traverse(hex28))
    yield operator -> PoolParams(
      operator,
      vrf,
      pledge,
      cost,
      margin,
      RewardAccount(if network == "Mainnet" then 1 else 0, Credential.Key(account)),
      owners.toSet,
      Bytes.empty,
      None
    )

  def credential(s: String): Either[ConfigError, Credential] =
    if s.startsWith("keyHash-") then hex28(s.drop(8)).map(Credential.Key(_))
    else if s.startsWith("scriptHash-") then hex28(s.drop(11)).map(Credential.Script(_))
    else Left(ConfigError(ConfigProblem.Credential(s)))

  def drep(s: String): Either[ConfigError, DRep] =
    s match
      case "drep-alwaysAbstain"       => Right(DRep.AlwaysAbstain)
      case "drep-alwaysNoConfidence"  => Right(DRep.AlwaysNoConfidence)
      case _ if s.startsWith("drep-") => credential(s.drop(5)).map(DRep.Registered(_))
      case _                          => Left(ConfigError(ConfigProblem.DRep(s)))

  def hex28(h: String): Either[ConfigError, Bytes] =
    Bytes
      .fromHex(h)
      .leftMap(e => ConfigError(ConfigProblem.Hex(e)))
      .flatMap(b =>
        Either
          .cond(b.size == 28, b, ConfigError(ConfigProblem.ByteLength("hash", 28, h.length / 2)))
      )

  /** The parsed native `InjectionData` (`Shelley/Genesis.hs:796-848`, f649f975). Parsing preserves
    * a file source without reading it, and distinguishes no source from embedded empty data.
    */
  enum InjectionData[+A]:
    case NoInjection
    case Embedded(entries: Vector[A])
    case FromFile(segments: Vector[String], hash: state.Hash32)

  /** Legacy and extra sources stay separate until the era's guarded injection operation. */
  final case class InjectionSource[A](legacy: Vector[A], extra: InjectionData[A])

  object InjectionSource:
    def legacy[A](entries: Vector[A]): InjectionSource[A] =
      InjectionSource(entries, InjectionData.NoInjection)

  def optionalObject(cursor: ACursor, name: String): Either[ConfigError, Option[JsonObject]] =
    cursor
      .as[Option[JsonObject]]
      .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis injection", name, e)))

  /** `ListMap` decodes ordered JSON keys, each key before its value (`Data/ListMap.hs:109-125`). */
  def entries[K, V](
      cursor: ACursor,
      name: String,
      key: String => Either[ConfigError, K],
      value: (K, Json) => Either[ConfigError, V],
      optional: Boolean = false
  ): Either[ConfigError, Vector[(K, V)]] =
    val fields =
      if optional then optionalObject(cursor, name).map(_.getOrElse(JsonObject.empty))
      else
        cursor
          .as[JsonObject]
          .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis injection", name, e)))
    fields.flatMap(_.toVector.sortBy(_._1).traverse { (k, v) =>
      key(k).flatMap(parsed => value(parsed, v).map(parsed -> _))
    })

  def injection[K, V](
      extra: Option[JsonObject],
      name: String,
      key: String => Either[ConfigError, K],
      value: (K, Json) => Either[ConfigError, V]
  ): Either[ConfigError, InjectionData[(K, V)]] =
    val cursor = Json.fromJsonObject(extra.getOrElse(JsonObject.empty)).hcursor.downField(name)
    for
      source <- optionalObject(cursor, name)
      parsed <- source match
        case None => Right(InjectionData.NoInjection)
        case Some(obj) =>
          val c = Json.fromJsonObject(obj).hcursor
          for
            file <- c
              .get[Option[Vector[String]]]("file")
              .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis injection", name, e)))
            data <- c
              .downField("data")
              .focus
              .filterNot(_.isNull)
              .traverse(j => entries(j.hcursor, name, key, value))
            result <- (file, data) match
              case (None, None)         => Right(InjectionData.NoInjection)
              case (None, Some(values)) => Right(InjectionData.Embedded(values))
              case (Some(segments), None) =>
                for
                  text <- get[String](c, "hash")
                  bytes <- Bytes.fromHex(text).leftMap(e => ConfigError(ConfigProblem.Hex(e)))
                  hash <- state.Hash32
                    .fromBytes(bytes)
                    .leftMap(e =>
                      ConfigError(ConfigProblem.GenesisStart(rules.GenesisStartError.Vocab(e)))
                    )
                yield InjectionData.FromFile(segments, hash)
              case (Some(_), Some(_)) =>
                Left(ConfigError(ConfigProblem.ConflictingFields(Set("file", "data"))))
          yield result
    yield parsed

  def get[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name).as[A].leftMap(e => ConfigError(ConfigProblem.JsonField("genesis", name, e)))

/** Bitcoin-alphabet base58, as used by Byron addresses. */
object Base58:
  private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
  def decode(s: String): Option[Bytes] =
    s.foldLeft(Option(BigInt(0))) { (acc, ch) =>
      val i = alphabet.indexOf(ch)
      acc.flatMap(a => Option.when(i >= 0)(a * 58 + i))
    }.map { n =>
      val leading = s.takeWhile(_ == '1').length
      val body = if n == 0 then Array.emptyByteArray else n.toByteArray.dropWhile(_ == 0)
      Bytes(Vector.fill(leading)(0.toByte) ++ body.toVector)
    }
