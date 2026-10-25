// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import scala.annotation.tailrec
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Value}
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{
  ConwayParamUpdate,
  DRepVotingThresholds,
  Nonce,
  PoolVotingThresholds,
  ProtocolParams
}
import xyz.kd5ujc.scarab.ledger.state.{ShelleyBasedEra, EraTag}
import xyz.kd5ujc.scarab.models.runtime.{
  Arr,
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer,
  at
}
import xyz.kd5ujc.scarab.plutus.ExUnits

import DecodeError.{Arity, Kind}
import Wire.*

/** The protocol parameters as each era's `PParams` encodes them (`EncCBOR (PParams era)`,
  * `Core/PParams.hs:198-203`): an array with one item per parameter of `eraPParams`, in the order
  * of that era's list. The mirror holds one flat [[ProtocolParams]] for every era, so the codec of
  * an era writes the parameters the era has and, reading, leaves the others at the value
  * [[Params.empty]] gives them; decoding what an era wrote therefore gives the parameters projected
  * onto that era (the other fields reset), and the codec is the identity on those.
  *
  * The lists: Shelley, Allegra and Mary 17 parameters (`Shelley/PParams.hs:506-524`), Alonzo 24
  * (`Alonzo/PParams.hs:344-369`), Babbage 22 (`Babbage/PParams.hs:203-226`), Conway 31
  * (`Conway/PParams.hs:862-894`). The codec does not depend on the encoding version: every ratio of
  * a parameter is a bounded one, always tagged.
  */
object Params:
  /** One parameter: its name in the ledger (`ppName`), the codec of its value, and where the flat
    * record keeps it.
    */
  final class Param[A](
      val name: String,
      codec: Codec[A],
      get: ProtocolParams => A,
      set: (ProtocolParams, A) => ProtocolParams,
      canonicalCodec: Codec[A]
  ):
    def write(p: ProtocolParams, out: Writer): Unit = codec.encode(get(p), out)
    def read(p: ProtocolParams, n: Node): Decoded[ProtocolParams] =
      codec.decode(n).map(set(p, _)).at(name)
    def refuses(p: ProtocolParams): Option[ConstraintViolation] =
      codec.check(get(p)).map(_.at(name))
    def copyValue(from: ProtocolParams, into: ProtocolParams): ProtocolParams = set(into, get(from))
    def canonical(raw: Bytes): Decoded[Bytes] = codec.decodeBytes(raw).map(codec.encodeUnchecked)
    private[Params] def capture(n: Node): Decoded[Captured[A]] =
      canonicalCodec.decode(n).map(value => new Captured(this, value))
    private[Params] def captureFrom(p: ProtocolParams): Either[ConstraintViolation, Captured[A]] =
      val value = get(p)
      canonicalCodec.check(value).map(_.at(name)).toLeft(new Captured(this, value))
    private[Params] def install(value: A, base: ProtocolParams): ProtocolParams = set(base, value)
    private[Params] def writeCaptured(value: A, out: Writer): Unit =
      canonicalCodec.encode(value, out)

  /** A decoded value paired with the descriptor that fixes its field type and encoding. */
  final case class Captured[A] private[Params] (
      private val descriptor: Param[A],
      private val value: A
  ):
    private[Params] def write(out: Writer): Unit = descriptor.writeCaptured(value, out)
    private[Params] def overlay(base: ProtocolParams): ProtocolParams =
      descriptor.install(value, base)

  /** An exact, fully populated Conway PParams projection. Its private constructor and opaque
    * captured fields prevent absent-era parameters from being filled with `Params.empty` values.
    */
  final class ConwayPParams private[Params] (
      private[Params] val fields: Vector[(Int, Captured[?])]
  ):
    override def equals(other: Any): Boolean = other match
      case that: ConwayPParams => fields == that.fields
      case _                   => false
    override def hashCode(): Int = fields.hashCode()

  private def param[A](name: String, codec: Codec[A], canonicalCodec: Option[Codec[A]] = None)(
      get: ProtocolParams => A
  )(set: (ProtocolParams, A) => ProtocolParams): Param[A] =
    new Param(name, codec, get, set, canonicalCodec.getOrElse(codec))

  /** What a field an era does not have reads as: zero, the neutral nonce, no cost models. */
  val empty: ProtocolParams = ProtocolParams(
    minFeeA = 0,
    minFeeB = 0,
    maxTxSize = 0,
    maxBlockBodySize = 0,
    maxValueSize = 0,
    coinsPerUTxOByte = 0,
    keyDeposit = 0,
    poolDeposit = 0,
    eMax = 0,
    nOpt = 0,
    a0 = Rational.zero,
    rho = Rational.zero,
    tau = Rational.zero,
    minPoolCost = 0,
    protocolMajor = 0,
    drepDeposit = 0,
    drepActivity = 0,
    govActionDeposit = 0,
    govActionLifetime = 0,
    minFeeRefScriptCostPerByte = Rational.zero
  )

  // ---- the codecs of the values

  private val coin: Codec[BigInt] = word(64)
  private val w16: Codec[BigInt] = word(16)
  private val w32: Codec[BigInt] = word(32)

  /** `ProtVer` (`BaseTypes.hs:238-244`): `[major, minor]`, the major in 0..13, the minor a
    * `Word32`.
    */
  private val protocolVersion: Codec[(BigInt, BigInt)] =
    Record.of[(BigInt, BigInt)]((Codec.uintBig(13), w32))

  /** `Prices` (`ExUnits.hs:219-229`): `[memory price, steps price]`. */
  private val prices: Codec[(Rational, Rational)] =
    Record.of[(Rational, Rational)]((nonNegativeInterval, nonNegativeInterval))

  /** `ExUnits` (`ExUnits.hs:192-215`): `[memory, steps]`, each at most `maxBound :: Int64`. */
  val exUnits: Codec[ExUnits] =
    val natural = Codec.uintBig(BigInt(Long.MaxValue))
    Record.of[ExUnits]((natural, natural))

  /** Canonical `Natural` fields are unbounded; the node snapshot's ExUnits codec above remains
    * Int64-bounded as required by the state format (`BasicTypes.hs:323-335`).
    */
  private val canonicalExUnits: Codec[ExUnits] =
    val natural = new Codec[BigInt]:
      def decode(n: Node): Decoded[BigInt] = Wire.integer.decode(n).flatMap { value =>
        if value >= 0 then Right(value)
        else Left(DecodeError.at(n, Kind.OutOfRange("Natural", value)))
      }
      def encode(value: BigInt, out: Writer): Unit = out.integer(value)
      override def check(value: BigInt): Option[ConstraintViolation] =
        Codec.refuse(value < 0, "Natural", "0 <= x", value)
    Record.of[ExUnits]((natural, natural))

  /** `CostModels` (`CostModels.hs:446-471`): a map from the language (a `Word8`) to its parameters,
    * each an `Int64`; a list of more than 23 is indefinite. Keys are in order, and a repeated one
    * is refused.
    */
  private val costModels: Codec[Map[Int, Vector[BigInt]]] = new Codec[Map[Int, Vector[BigInt]]]:
    private val language = Codec.uintBig(255)
    private val values = seqOf(Codec.bigRange(BigInt(Long.MinValue), BigInt(Long.MaxValue)))
    def decode(n: Node): Decoded[Map[Int, Vector[BigInt]]] =
      Codec.pairs(language, values).decode(n).flatMap { pairs =>
        val map = pairs.map((k, v) => k.toInt -> v).toMap
        if map.size == pairs.size then Right(map)
        else Left(DecodeError.at(n, Kind.DuplicateKey("a language")))
      }
    def encode(a: Map[Int, Vector[BigInt]], out: Writer): Unit =
      out.variableMap(a.size)(a.toVector.sortBy(_._1).foreach { (k, vs) =>
        out.uint(k.toLong)
        values.encode(vs, out)
      })
    override def check(a: Map[Int, Vector[BigInt]]): Option[ConstraintViolation] =
      Codec.refuse(
        !(a.keys.forall(k => k >= 0 && k <= 255) &&
          a.values.forall(_.forall(x => x >= Long.MinValue && x <= Long.MaxValue))),
        "cost models",
        "language 0..255 and Int64 parameters",
        s"${a.size} languages"
      )

  /** The SCLS codec uses definite maps and lists, independently of the state snapshot's
    * variable-length policy above. Reference: cardano-ledger@f649f975,
    * CanonicalState/Namespace/GovPParams/V0.hs:85-91 (`flattenCostModels`).
    */
  private val canonicalCostModels: Codec[Map[Int, Vector[BigInt]]] =
    new Codec[Map[Int, Vector[BigInt]]]:
      def decode(n: Node): Decoded[Map[Int, Vector[BigInt]]] = costModels.decode(n)
      def encode(value: Map[Int, Vector[BigInt]], out: Writer): Unit =
        out.mapHead(value.size)
        value.toVector.sortBy(_._1).foreach { (language, parameters) =>
          out.uint(language.toLong)
          out.arrayHead(parameters.size)
          parameters.foreach(out.int)
        }
      override def check(value: Map[Int, Vector[BigInt]]): Option[ConstraintViolation] =
        costModels.check(value)

  /** `PoolVotingThresholds`: five unit intervals. */
  private val poolThresholds: Codec[PoolVotingThresholds] =
    Record.of[PoolVotingThresholds](
      (unitInterval, unitInterval, unitInterval, unitInterval, unitInterval)
    )

  /** `DRepVotingThresholds`: ten unit intervals. */
  private val drepThresholds: Codec[DRepVotingThresholds] =
    Record.of[DRepVotingThresholds](
      (
        unitInterval,
        unitInterval,
        unitInterval,
        unitInterval,
        unitInterval,
        unitInterval,
        unitInterval,
        unitInterval,
        unitInterval,
        unitInterval
      )
    )

  // ---- the parameters, by name

  private val txFeePerByte = param("txFeePerByte", coin)(_.minFeeA)((p, x) => p.copy(minFeeA = x))
  private val txFeeFixed = param("txFeeFixed", coin)(_.minFeeB)((p, x) => p.copy(minFeeB = x))
  private val maxBBSize =
    param("maxBBSize", w32)(_.maxBlockBodySize)((p, x) => p.copy(maxBlockBodySize = x))
  private val maxTxSize = param("maxTxSize", w32)(_.maxTxSize)((p, x) => p.copy(maxTxSize = x))
  private val maxBHSize =
    param("maxBHSize", w16)(_.maxBlockHeaderSize)((p, x) => p.copy(maxBlockHeaderSize = x))
  private val keyDeposit = param("keyDeposit", coin)(_.keyDeposit)((p, x) => p.copy(keyDeposit = x))
  private val poolDeposit =
    param("poolDeposit", coin)(_.poolDeposit)((p, x) => p.copy(poolDeposit = x))
  private val eMax = param("eMax", w32)(_.eMax)((p, x) => p.copy(eMax = x))
  private val nOpt = param("nOpt", w16)(_.nOpt)((p, x) => p.copy(nOpt = x))
  private val a0 = param("a0", nonNegativeInterval)(_.a0)((p, x) => p.copy(a0 = x))
  private val rho = param("rho", unitInterval)(_.rho)((p, x) => p.copy(rho = x))
  private val tau = param("tau", unitInterval)(_.tau)((p, x) => p.copy(tau = x))
  private val d = param("d", unitInterval)(_.d)((p, x) => p.copy(d = x))

  private val extraEntropy: Param[Nonce] =
    param("extraEntropy", Wire.nonce)(_.extraEntropy)((p, x) => p.copy(extraEntropy = x))

  private val protocolVersionParam =
    param("protocolVersion", protocolVersion)(p => (p.protocolMajor, p.protocolMinor))((p, x) =>
      p.copy(protocolMajor = x._1, protocolMinor = x._2)
    )
  private val minUTxOValue =
    param("minUTxOValue", coin)(_.minUTxOValue)((p, x) => p.copy(minUTxOValue = x))
  private val minPoolCost =
    param("minPoolCost", coin)(_.minPoolCost)((p, x) => p.copy(minPoolCost = x))
  private val coinsPerUTxOWord =
    param("coinsPerUTxOWord", coin)(_.coinsPerUTxOWord)((p, x) => p.copy(coinsPerUTxOWord = x))
  private val coinsPerUTxOByte =
    param("coinsPerUTxOByte", coin)(_.coinsPerUTxOByte)((p, x) => p.copy(coinsPerUTxOByte = x))
  private val costModelsParam =
    param("costModels", costModels, Some(canonicalCostModels))(_.costModels)((p, x) =>
      p.copy(costModels = x)
    )
  private val pricesParam =
    param("prices", prices)(p => (p.priceMem, p.priceSteps))((p, x) =>
      p.copy(priceMem = x._1, priceSteps = x._2)
    )
  private val maxTxExUnits =
    param("maxTxExUnits", exUnits, Some(canonicalExUnits))(_.maxTxExUnits)((p, x) =>
      p.copy(maxTxExUnits = x)
    )
  private val maxBlockExUnits =
    param("maxBlockExUnits", exUnits, Some(canonicalExUnits))(_.maxBlockExUnits)((p, x) =>
      p.copy(maxBlockExUnits = x)
    )
  private val maxValSize =
    param("maxValSize", w32)(_.maxValueSize)((p, x) => p.copy(maxValueSize = x))
  private val collateralPercentage =
    param("collateralPercentage", w16)(_.collateralPercentage)((p, x) =>
      p.copy(collateralPercentage = x)
    )
  private val maxCollateralInputs =
    param("maxCollateralInputs", w16)(_.maxCollateralInputs)((p, x) =>
      p.copy(maxCollateralInputs = x)
    )
  private val poolVotingThresholds =
    param("poolVotingThresholds", poolThresholds)(_.poolVotingThresholds)((p, x) =>
      p.copy(poolVotingThresholds = x)
    )
  private val drepVotingThresholds =
    param("drepVotingThresholds", drepThresholds)(_.drepVotingThresholds)((p, x) =>
      p.copy(drepVotingThresholds = x)
    )
  private val committeeMinSize =
    param("committeeMinSize", w16)(_.committeeMinSize)((p, x) => p.copy(committeeMinSize = x))
  private val committeeMaxTermLength =
    param("committeeMaxTermLength", w32)(_.committeeMaxTermLength)((p, x) =>
      p.copy(committeeMaxTermLength = x)
    )
  private val govActionLifetime =
    param("govActionLifetime", w32)(_.govActionLifetime)((p, x) => p.copy(govActionLifetime = x))
  private val govActionDeposit =
    param("govActionDeposit", coin)(_.govActionDeposit)((p, x) => p.copy(govActionDeposit = x))
  private val drepDeposit =
    param("drepDeposit", coin)(_.drepDeposit)((p, x) => p.copy(drepDeposit = x))
  private val drepActivity =
    param("drepActivity", w32)(_.drepActivity)((p, x) => p.copy(drepActivity = x))
  private val minFeeRefScriptCostPerByte =
    param("minFeeRefScriptCostPerByte", nonNegativeInterval)(_.minFeeRefScriptCostPerByte)((p, x) =>
      p.copy(minFeeRefScriptCostPerByte = x)
    )

  // ---- the eras (`eraPParams`)

  private val shelleyList: Vector[Param[?]] = Vector(
    txFeePerByte,
    txFeeFixed,
    maxBBSize,
    maxTxSize,
    maxBHSize,
    keyDeposit,
    poolDeposit,
    eMax,
    nOpt,
    a0,
    rho,
    tau,
    d,
    extraEntropy,
    protocolVersionParam,
    minUTxOValue,
    minPoolCost
  )

  private val alonzoList: Vector[Param[?]] = Vector(
    txFeePerByte,
    txFeeFixed,
    maxBBSize,
    maxTxSize,
    maxBHSize,
    keyDeposit,
    poolDeposit,
    eMax,
    nOpt,
    a0,
    rho,
    tau,
    d,
    extraEntropy,
    protocolVersionParam,
    minPoolCost,
    coinsPerUTxOWord,
    costModelsParam,
    pricesParam,
    maxTxExUnits,
    maxBlockExUnits,
    maxValSize,
    collateralPercentage,
    maxCollateralInputs
  )

  private val babbageList: Vector[Param[?]] = Vector(
    txFeePerByte,
    txFeeFixed,
    maxBBSize,
    maxTxSize,
    maxBHSize,
    keyDeposit,
    poolDeposit,
    eMax,
    nOpt,
    a0,
    rho,
    tau,
    protocolVersionParam,
    minPoolCost,
    coinsPerUTxOByte,
    costModelsParam,
    pricesParam,
    maxTxExUnits,
    maxBlockExUnits,
    maxValSize,
    collateralPercentage,
    maxCollateralInputs
  )

  private val conwayList: Vector[Param[?]] = babbageList ++ Vector(
    poolVotingThresholds,
    drepVotingThresholds,
    committeeMinSize,
    committeeMaxTermLength,
    govActionLifetime,
    govActionDeposit,
    drepDeposit,
    drepActivity,
    minFeeRefScriptCostPerByte
  )

  private val conwayMapFields: Vector[(Int, Param[?])] = Vector(
    0 -> txFeePerByte,
    1 -> txFeeFixed,
    2 -> maxBBSize,
    3 -> maxTxSize,
    4 -> maxBHSize,
    5 -> keyDeposit,
    6 -> poolDeposit,
    7 -> eMax,
    8 -> nOpt,
    9 -> a0,
    10 -> rho,
    11 -> tau,
    14 -> protocolVersionParam,
    16 -> minPoolCost,
    17 -> coinsPerUTxOByte,
    18 -> costModelsParam,
    19 -> pricesParam,
    20 -> maxTxExUnits,
    21 -> maxBlockExUnits,
    22 -> maxValSize,
    23 -> collateralPercentage,
    24 -> maxCollateralInputs,
    25 -> poolVotingThresholds,
    26 -> drepVotingThresholds,
    27 -> committeeMinSize,
    28 -> committeeMaxTermLength,
    29 -> govActionLifetime,
    30 -> govActionDeposit,
    31 -> drepDeposit,
    32 -> drepActivity,
    33 -> minFeeRefScriptCostPerByte
  )

  /** Conway `PParams` in canonical-state's integer-key map form (`Conway.hs:107-160`), distinct
    * from the positional-array codec used by the node state snapshot. The map must contain exactly
    * the 31 parameters in the Conway schema; values decode through the same descriptors used by the
    * node-state codec, without defaulting fields from other eras.
    */
  val conwayPParamsCodec: Codec[ConwayPParams] = new Codec[ConwayPParams]:
    private val expectedKeys = conwayMapFields.map(_._1).toSet

    def decode(n: Node): Decoded[ConwayPParams] = n.value match
      case Value.Map(pairs) =>
        val parsedKeys = pairs.traverse { case (key, _) => Codec.uint.decode(key) }
        parsedKeys.flatMap { keys =>
          if pairs.size != conwayMapFields.size then
            Left(DecodeError.at(n, Kind.BadArity(Arity.Exactly(conwayMapFields.size), pairs.size)))
          else if keys.distinct.size != keys.size then
            Left(DecodeError.at(n, Kind.DuplicateKey("a Conway PParams field")))
          else
            keys.find(key => !key.isValidInt || !expectedKeys(key.toInt)) match
              case Some(key) => Left(DecodeError(Kind.UnexpectedKey(key.toString), Nil, n.offset))
              case None =>
                val values = keys.zip(pairs.map(_._2)).toMap
                conwayMapFields
                  .traverse { case (key, descriptor) =>
                    descriptor.capture(values(key)).map(key -> _)
                  }
                  .map(fields => new ConwayPParams(fields))
        }
      case _ => Left(Codec.mismatch("31-field Conway PParams map", n))

    def encode(value: ConwayPParams, out: Writer): Unit =
      out.mapHead(value.fields.size)
      value.fields.foreach { case (key, captured) =>
        out.uint(key.toLong)
        captured.write(out)
      }

  /** Capture exactly the canonical Conway fields, using their canonical constraints (including
    * unbounded execution-unit Naturals), without manufacturing absent-era values.
    */
  def projectConway(value: ProtocolParams): Either[ConstraintViolation, ConwayPParams] =
    conwayMapFields
      .traverse { case (key, descriptor) => descriptor.captureFrom(value).map(key -> _) }
      .map(fields => new ConwayPParams(fields))

  /** Apply the complete canonical field set to an explicit base. Parameters absent from Conway
    * retain the base's values. Reference: cardano-ledger@f649f975,
    * CanonicalState/Conway/Import.hs:207-217; the caller owns same-point base admission.
    */
  def overlayConway(value: ConwayPParams, base: ProtocolParams): ProtocolParams =
    value.fields.foldLeft(base) { case (current, (_, captured)) => captured.overlay(current) }

  /** The typed projection used only when reporting a Conway context error. Transaction and state
    * values keep their original parameter bytes; the reference's diagnostic `PParamsUpdate`
    * re-encodes its typed fields (`Conway/PParams.hs`, `conwayPParamsHKDPairs`).
    */
  def canonicalConwayUpdate(update: ConwayParamUpdate): Decoded[ConwayParamUpdate] =
    val fields = conwayMapFields.filterNot(_._1 == 14).toMap
    update.encodings
      .traverse { case (key, raw) =>
        fields
          .get(key)
          .toRight(DecodeError(Kind.UnexpectedKey(key.toString), Nil, 0))
          .flatMap(_.canonical(raw))
          .map(BigInt(key) -> _)
      }
      .flatMap(values =>
        ConwayParamUpdate
          .fromEncoded(values)
          .left
          .map(why => DecodeError(Kind.Domain(why), Nil, 0))
      )

  private def listCodec(params: Vector[Param[?]]): Codec[ProtocolParams] =
    new Codec[ProtocolParams]:
      def decode(n: Node): Decoded[ProtocolParams] =
        Arr.exact(n, params.size).flatMap { items =>
          @tailrec def go(i: Int, acc: ProtocolParams): Decoded[ProtocolParams] =
            if i == params.size then Right(acc)
            else
              params(i).read(acc, items(i)) match
                case Right(next) => go(i + 1, next)
                case left        => left
          go(0, empty)
        }
      def encode(a: ProtocolParams, out: Writer): Unit =
        out.arrayHead(params.size)
        params.foreach(_.write(a, out))
      override def check(a: ProtocolParams): Option[ConstraintViolation] =
        params.iterator.flatMap(_.refuses(a)).nextOption()

  private val shelley = listCodec(shelleyList)
  private val alonzo = listCodec(alonzoList)
  private val babbage = listCodec(babbageList)
  private val conway = listCodec(conwayList)

  /** The `PParams` codec of an era. */
  def codec[E <: EraTag](era: ShelleyBasedEra[E]): Codec[ProtocolParams] = era match
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary => shelley
    case ShelleyBasedEra.SAlonzo                                                     => alonzo
    case ShelleyBasedEra.SBabbage                                                    => babbage
    case ShelleyBasedEra.SConway                                                     => conway

  /** Preserve every field encoded by this era, including zero; only fields the era lacks come from
    * genesis. Comparing values with zero would lose legitimate zero-valued parameters.
    */
  def complete[E <: EraTag](
      era: ShelleyBasedEra[E],
      read: ProtocolParams,
      genesis: ProtocolParams
  ): ProtocolParams =
    val fields = era match
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary =>
        shelleyList
      case ShelleyBasedEra.SAlonzo  => alonzoList
      case ShelleyBasedEra.SBabbage => babbageList
      case ShelleyBasedEra.SConway  => conwayList
    fields.foldLeft(genesis)((full, field) => field.copyValue(read, full))

  /** The Conway `PParams` codec: the parameters of the governance state and of its enact state. */
  val conwayCodec: Codec[ProtocolParams] = conway
