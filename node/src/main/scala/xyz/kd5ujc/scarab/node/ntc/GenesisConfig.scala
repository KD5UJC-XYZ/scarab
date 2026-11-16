// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import io.circe.{ACursor, Json}
import java.time.Instant
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.NtcVersion
import xyz.kd5ujc.scarab.ledger.{ConfigError, GenesisFile, GenesisSchema, Nonce}
import xyz.kd5ujc.scarab.ledger.json.{BoundedRatio, NumberError}
import xyz.kd5ujc.scarab.ledger.state.PositiveUnitInterval
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.storage.haskell.state.{NodeOrders, Versions, Wire}

/** `GetGenesisConfig`: the compact Shelley genesis (`CompactGenesis`, whose initial funds and
  * staking are dropped) in the 15-field encoding of node-to-client versions 16–23 (the one without
  * the extra configuration): system start, network magic and id, active slot coefficient, security
  * parameter, epoch length, KES period and evolutions, slot length in microseconds, update quorum,
  * maximum supply, the Shelley protocol parameters, the genesis delegates, and two empty maps.
  * Before NTC 21 the Shelley protocol version occupies two contiguous fields instead of one pair
  * (`Query/LegacyShelleyGenesis.hs:34-57`, `LegacyPParams.hs:46-68` at ouroboros-consensus
  * 82ecba329d7d054340bf707d44fe6e9ac27cec40).
  */
object GenesisConfig:
  enum Error extends xyz.kd5ujc.scarab.codec.DomainError:
    case InvalidField(field: String, cause: io.circe.DecodingFailure)
    case InvalidHex(field: String, cause: Bytes.HexError)
    case InvalidActiveSlotsCoeff(cause: NumberError)
    case InvalidSystemStart(value: String, cause: Throwable)

    /** A bounded ratio the reference's instance refuses (`FromJSON (BoundedRatio b Word64)`). */
    case InvalidRatio(cause: ConfigError)

    /** An unsigned integer outside the range of the Haskell type it is read and written as. */
    case OutOfRange(field: String, cause: WordOutOfRange)

    /** A ratio outside the range of the bounded ratio it is read and written as. */
    case RatioOutOfRange(field: String, cause: xyz.kd5ujc.scarab.node.ntc.RatioOutOfRange)

    /** A slot length whose microseconds are not an integer this node writes: beyond 2^64 seconds.
      */
    case SlotLengthOutOfRange(value: BigDecimal)

    def message: String = this match
      case InvalidField(field, detail)  => s"genesis $field: $detail"
      case InvalidHex(field, detail)    => s"genesis $field: $detail"
      case InvalidActiveSlotsCoeff(e)   => s"genesis activeSlotsCoeff: ${e.message}"
      case InvalidSystemStart(value, _) => s"systemStart $value"
      case InvalidRatio(e)              => e.message
      case OutOfRange(field, e)         => s"genesis $field: ${e.message}"
      case RatioOutOfRange(field, e)    => s"genesis $field: ${e.message}"
      case SlotLengthOutOfRange(value)  => s"genesis slotLength: $value"

  private type R[A] = Either[Error, A]

  /** The version the answer is written at: the lowest of the Conway era (`Versions.eraLow`). */
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)
  import NodeOrders.given

  /** The genesis delegates: genesis key hash to the delegate's key hash and VRF key hash, a `Map`
    * written by `encodeMap` at `shelleyProtVer`: definite up to 23 pairs, indefinite beyond
    * (`Binary/Encoding/Encoder.hs:391-408`, cardano-ledger-binary 048b78d2).
    */
  private val delegateCodec = Wire.sortedMapOf(
    xyz.kd5ujc.scarab.models.runtime.Codec.bytes,
    Wire.writeOnly[(Bytes, Bytes)]("a genesis delegate") { (d, out) =>
      out.arrayHead(2)
      out.bytes(d._1)
      out.bytes(d._2)
    }
  )

  /** `networkId` as the compact genesis writes it: `Mainnet` is 1, and any other name 0. The parser
    * of the genesis text, where the name becomes the word written (STR-2).
    */
  @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.StringControlFlow"))
  private def networkWord(name: String): Int = name match
    case "Mainnet" => 1
    case _         => 0

  private def get[A: io.circe.Decoder](c: ACursor, name: String): R[A] =
    c.downField(name).as[A].leftMap(e => Error.InvalidField(name, e))

  private def hex(c: ACursor, name: String): R[Bytes] =
    get[String](c, name).flatMap(s => Bytes.fromHex(s).leftMap(Error.InvalidHex(name, _)))

  /** `value`, if it is a word of `width`. */
  private def bounded(field: String, value: BigInt, width: WordWidth): R[BoundedWord] =
    BoundedWord.of(value, width).leftMap(Error.OutOfRange(field, _))

  /** An unsigned integer field read as a word of `width`, as the reference's `FromJSON` reads the
    * `Word` types of the genesis.
    */
  private def word(c: ACursor, name: String, width: WordWidth): R[BoundedWord] =
    get[BigInt](c, name).flatMap(bounded(name, _, width))

  /** A field the reference reads with `.:? name .!= mempty`: absent or null is zero
    * (`Shelley/Genesis.hs:423-424`, cardano-ledger f649f975).
    */
  private def optionalWord(c: ACursor, name: String, width: WordWidth): R[BoundedWord] =
    get[Option[BigInt]](c, name).flatMap(n => bounded(name, n.getOrElse(BigInt(0)), width))

  /** A bounded ratio as the reference's instance reads it: a decimal or a rational object
    * (`FromJSON (BoundedRatio b Word64)`, `BaseTypes.hs:437-442`, cardano-ledger f649f975), two
    * `Word64` s.
    */
  private def ratio(p: ACursor, name: String, interval: BoundedRatio): R[WordRatio] =
    GenesisSchema
      .ratio(GenesisFile.Shelley, interval)(p.downField(name), Vector("protocolParams", name))
      .leftMap(Error.InvalidRatio(_))
      .flatMap(WordRatio.of(_).leftMap(Error.RatioOutOfRange(name, _)))

  /** The fields of the Shelley genesis these encodings write, read from its JSON as the reference's
    * types hold them (`ShelleyGenesis`, `Shelley/Genesis.hs:313-330`, cardano-ledger f649f975).
    */
  private final case class Fields(
      start: Instant,
      magic: BoundedWord,
      network: Int,
      active: WordRatio,
      k: BoundedWord,
      epochLength: BoundedWord,
      kesPeriod: BoundedWord,
      kesEvolutions: BoundedWord,
      slotMicros: BigInt,
      quorum: BoundedWord,
      supply: BoundedWord,
      delegates: Map[Bytes, (Bytes, Bytes)],
      params: CborOut => Unit
  )

  /** The microseconds of a slot length in seconds, truncated (`NominalDiffTimeMicro`, a `Micro`),
    * refused beyond 2^64 seconds; a length below a microsecond is zero, computed without expanding
    * its exponent.
    */
  private def micros(slotLength: BigDecimal): R[BigInt] =
    val micro = BigDecimal("0.000001")
    if slotLength.abs > BigDecimal(BigInt(1) << 64) then
      Left(Error.SlotLengthOutOfRange(slotLength))
    else if slotLength.abs < micro then Right(BigInt(0))
    else Right((slotLength * 1000000).toBigInt)

  private def fields(shelley: Json, version: NtcVersion): R[Fields] =
    val c = shelley.hcursor
    val p = c.downField("protocolParams")
    for
      start <- get[String](c, "systemStart").flatMap(s =>
        Either.catchNonFatal(Instant.parse(s)).leftMap(e => Error.InvalidSystemStart(s, e))
      )
      magic <- word(c, "networkMagic", WordWidth.W32)
      network <- get[String](c, "networkId").map(networkWord)
      active <- get[Json](c, "activeSlotsCoeff").flatMap(
        PositiveUnitInterval.fromJson(_).leftMap(Error.InvalidActiveSlotsCoeff(_))
      )
      activeRatio <- WordRatio
        .of(active.rational)
        .leftMap(Error.RatioOutOfRange("activeSlotsCoeff", _))
      k <- word(c, "securityParam", WordWidth.W64)
      epochLength <- word(c, "epochLength", WordWidth.W64)
      kesPeriod <- word(c, "slotsPerKESPeriod", WordWidth.W64)
      kesEvolutions <- word(c, "maxKESEvolutions", WordWidth.W64)
      slotMicros <- get[BigDecimal](c, "slotLength").flatMap(micros)
      quorum <- word(c, "updateQuorum", WordWidth.W64)
      supply <- word(c, "maxLovelaceSupply", WordWidth.W64)
      delegates <- c
        .downField("genDelegs")
        .as[Option[Map[String, Json]]]
        .leftMap(e => Error.InvalidField("genDelegs", e))
        .flatMap(
          _.getOrElse(Map.empty).toVector.traverse { case (g, j) =>
            for
              genesis <- Bytes.fromHex(g).leftMap(Error.InvalidHex("genesis key", _))
              delegate <- hex(j.hcursor, "delegate")
              vrf <- hex(j.hcursor, "vrf")
            yield genesis -> (delegate, vrf)
          }
        )
      params <- shelleyParams(p, version)
    yield Fields(
      start,
      magic,
      network,
      activeRatio,
      k,
      epochLength,
      kesPeriod,
      kesEvolutions,
      slotMicros,
      quorum,
      supply,
      delegates.toMap,
      params
    )

  /** `UTCTime` as the ledger writes it (`encodeUTCTime`, `Binary/Encoding/Encoder.hs:549-557`,
    * cardano-ledger-binary 048b78d2): `[year, day of year, picoseconds of the day]`, the year and
    * the picoseconds `Integer` s, the date `toOrdinalDate` of the proleptic Gregorian calendar.
    */
  private[ntc] def utcTime(w: CborOut, at: Instant): CborOut =
    utcTime(w, BigInt(at.getEpochSecond), at.getNano)

  /** [[utcTime]] of `seconds` since 1970 and `nanos` past them, by arithmetic alone, so for any
    * year: the civil date of the day (Howard Hinnant's `civil_from_days`, in the March-based year)
    * and the day of the year from the first of January.
    */
  private[ntc] def utcTime(w: CborOut, seconds: BigInt, nanos: Int): CborOut =
    def floorDiv(a: BigInt, b: BigInt): BigInt = (a - a.mod(b)) / b
    val day = floorDiv(seconds, 86400)
    val ofDay = seconds.mod(86400)
    // civil_from_days: the year of the day, its era of 400 years starting on 1 March 0000
    val z = day + 719468
    val era = floorDiv(z, 146097)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val year = yoe + era * 400 + (if mp >= 10 then 1 else 0)
    // the day the year starts on, by days_from_civil of 1 January
    val before = year - 1
    val startEra = floorDiv(before, 400)
    val startYoe = before - startEra * 400
    val januaryFirst =
      startEra * 146097 + startYoe * 365 + startYoe / 4 - startYoe / 100 + 306 - 719468
    val picos = ofDay * BigInt(10).pow(12) + BigInt(nanos) * 1000
    w.array(3).integer(year).integer(day - januaryFirst + 1).integer(picos)

  /** The first fifteen fields, as both encodings write them; the slot length is a `Micro`, an
    * `Integer` of microseconds.
    */
  private def writeFields(w: CborOut, f: Fields): Unit =
    utcTime(w, f.start)
    w.word(f.magic).uint(f.network)
    w.untaggedRatio(f.active)
    w.word(f.k).word(f.epochLength).word(f.kesPeriod).word(f.kesEvolutions)
    w.integer(f.slotMicros)
    w.word(f.quorum).word(f.supply)
    f.params(w)
    w.raw(delegateCodec.encodeUnchecked(f.delegates))
    w.map(0)
    w.array(2).map(0).map(0)

  /** The result item, from the Shelley genesis JSON. */
  def encode(shelley: Json, version: NtcVersion = NtcVersion.LatestReleased): R[Bytes] =
    fields(shelley, version).map(f =>
      CborOut.build { w =>
        w.array(15)
        writeFields(w, f)
      }
    )

  /** `toCBOR` of the compact genesis (`ToCBOR ShelleyGenesis`, `Shelley/Genesis.hs:575-612` at
    * cardano-ledger f649f975, at `shelleyProtVer`): the fifteen fields of [[encode]] at version 21
    * and its sixteenth, the extra configuration, which the compact genesis erases (`SNothing`,
    * `Shelley/Ledger/Config.hs:140-146` at ouroboros-consensus 82ecba32), written `[]`.
    */
  private[ntc] def compact(shelley: Json): R[Bytes] =
    fields(shelley, NtcVersion.V21).map(f =>
      CborOut.build { w =>
        w.array(16)
        writeFields(w, f)
        w.array(0)
      }
    )

  /** `toCBOR` of `FromByronTranslationContext` (`Shelley/Translation.hs:54-62` at cardano-ledger
    * f649f975), the Shelley era's translation context, which `toFromByronTranslationContext`
    * (`:102-107`) takes from the genesis: its delegates, its parameters and its maximum supply, at
    * `shelleyProtVer`, as the genesis writes them.
    */
  private[ntc] def fromByronContext(shelley: Json): R[Bytes] =
    fields(shelley, NtcVersion.V21).map(f =>
      CborOut.build { w =>
        w.array(3)
        w.raw(delegateCodec.encodeUnchecked(f.delegates))
        f.params(w)
        w.word(f.supply)
      }
    )

  /** The fields of `Globals` that `mkShelleyGlobals` takes from the genesis
    * (`Shelley/Genesis.hs:768-789` at cardano-ledger f649f975), as the partial ledger configuration
    * writes them (`Shelley/Node/Serialisation.hs:318-329` at ouroboros-consensus 82ecba32): the KES
    * period, the two stability windows (`Shelley/StabilityWindow.hs:14-37`: `Word64` s, given here
    * as the caller computed them), the security parameter, the KES evolutions, the update quorum,
    * the maximum supply, the active slot coefficient (a tagged rational), the network and the
    * system start, the Shelley genesis' own.
    */
  private[ntc] def globals(
      shelley: Json,
      stabilityWindow: BoundedWord,
      randomnessWindow: BoundedWord
  ): R[Bytes] =
    fields(shelley, NtcVersion.V21).map(f =>
      CborOut.build { w =>
        w.word(f.kesPeriod).word(stabilityWindow).word(randomnessWindow).word(f.k)
        w.word(f.kesEvolutions).word(f.quorum).word(f.supply)
        w.ratio(f.active)
        w.uint(f.network)
        utcTime(w, f.start)
      }
    )

  /** The 17 Shelley protocol parameters of the genesis file, read as the reference reads them
    * (`FromJSON LegacyJSONPParams`, `Shelley/Genesis.hs:403-424`, and the field types,
    * `Shelley/PParams.hs:99-131`, cardano-ledger f649f975): the fees, deposits and minima are
    * compact coins (`Word64`), the sizes `Word32` and `Word16`, `eMax` an `EpochInterval`
    * (`Word32`), the ratios bounded ratios as a decimal or a rational object, the protocol version
    * a major version and a `Word32` minor (`BaseTypes.hs:211, 231-236`), and the minimum output and
    * pool cost zero when absent.
    */
  private def shelleyParams(p: ACursor, version: NtcVersion): R[CborOut => Unit] =
    val legacy = version.number < NtcVersion.V21.number
    for
      minFeeA <- word(p, "minFeeA", WordWidth.W64)
      minFeeB <- word(p, "minFeeB", WordWidth.W64)
      maxBody <- word(p, "maxBlockBodySize", WordWidth.W32)
      maxTx <- word(p, "maxTxSize", WordWidth.W32)
      maxHeader <- word(p, "maxBlockHeaderSize", WordWidth.W16)
      keyDeposit <- word(p, "keyDeposit", WordWidth.W64)
      poolDeposit <- word(p, "poolDeposit", WordWidth.W64)
      eMax <- word(p, "eMax", WordWidth.W32)
      nOpt <- word(p, "nOpt", WordWidth.W16)
      a0 <- ratio(p, "a0", BoundedRatio.NonNegative)
      rho <- ratio(p, "rho", BoundedRatio.Unit)
      tau <- ratio(p, "tau", BoundedRatio.Unit)
      d <- ratio(p, "decentralisationParam", BoundedRatio.Unit)
      entropy <- p
        .downField("extraEntropy")
        .downField("tag")
        .as[Option[String]]
        .leftMap(e => Error.InvalidField("extraEntropy", e))
        .flatMap {
          case None | Some("NeutralNonce") => Right(None)
          case Some(_) => hex(p.downField("extraEntropy"), "contents").map(Some(_))
        }
      major <- word(p.downField("protocolVersion"), "major", WordWidth.W64)
      minor <- word(p.downField("protocolVersion"), "minor", WordWidth.W32)
      minUtxo <- optionalWord(p, "minUTxOValue", WordWidth.W64)
      minPool <- optionalWord(p, "minPoolCost", WordWidth.W64)
    yield (w: CborOut) =>
      w.array(if legacy then 18 else 17)
      w.word(minFeeA).word(minFeeB).word(maxBody).word(maxTx).word(maxHeader)
      w.word(keyDeposit).word(poolDeposit).word(eMax).word(nOpt)
      List(a0, rho, tau, d).foreach(r => w.ratio(r))
      w.raw(Wire.nonce.encodeUnchecked(entropy.fold[Nonce](Nonce.Neutral)(Nonce.Hash(_))))
      if !legacy then w.array(2)
      w.word(major).word(minor)
      w.word(minUtxo).word(minPool)
