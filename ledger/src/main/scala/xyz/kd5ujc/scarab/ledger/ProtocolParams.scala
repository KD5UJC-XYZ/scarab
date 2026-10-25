// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.ExUnits
import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
import xyz.kd5ujc.scarab.codec.Node
import xyz.kd5ujc.scarab.ledger.ConfigError
import xyz.kd5ujc.scarab.ledger.Nonce

/** Conway pool (SPO) voting thresholds, in the order of the protocol-parameter encoding. */
final case class PoolVotingThresholds(
    motionNoConfidence: Rational,
    committeeNormal: Rational,
    committeeNoConfidence: Rational,
    hardForkInitiation: Rational,
    securityGroup: Rational
):
  def toVector: Vector[Rational] =
    Vector(
      motionNoConfidence,
      committeeNormal,
      committeeNoConfidence,
      hardForkInitiation,
      securityGroup
    )

object PoolVotingThresholds:
  val zero: PoolVotingThresholds =
    PoolVotingThresholds(Rational.zero, Rational.zero, Rational.zero, Rational.zero, Rational.zero)
  def fromVector(xs: Vector[Rational]): Either[LedgerDecodeError, PoolVotingThresholds] =
    xs match
      case Vector(a, b, c, d, e) => Right(PoolVotingThresholds(a, b, c, d, e))
      case _ => Left(LedgerDecodeError.ArrayLength(DecodeField.PoolVotingThresholds, 5, xs.size))

/** Conway DRep voting thresholds, in the order of the protocol-parameter encoding. */
final case class DRepVotingThresholds(
    motionNoConfidence: Rational,
    committeeNormal: Rational,
    committeeNoConfidence: Rational,
    updateToConstitution: Rational,
    hardForkInitiation: Rational,
    ppNetworkGroup: Rational,
    ppEconomicGroup: Rational,
    ppTechnicalGroup: Rational,
    ppGovGroup: Rational,
    treasuryWithdrawal: Rational
):
  def toVector: Vector[Rational] = Vector(
    motionNoConfidence,
    committeeNormal,
    committeeNoConfidence,
    updateToConstitution,
    hardForkInitiation,
    ppNetworkGroup,
    ppEconomicGroup,
    ppTechnicalGroup,
    ppGovGroup,
    treasuryWithdrawal
  )

object DRepVotingThresholds:
  val zero: DRepVotingThresholds =
    DRepVotingThresholds(
      Rational.zero,
      Rational.zero,
      Rational.zero,
      Rational.zero,
      Rational.zero,
      Rational.zero,
      Rational.zero,
      Rational.zero,
      Rational.zero,
      Rational.zero
    )
  def fromVector(xs: Vector[Rational]): Either[LedgerDecodeError, DRepVotingThresholds] =
    xs match
      case Vector(a, b, c, d, e, f, g, h, i, j) =>
        Right(DRepVotingThresholds(a, b, c, d, e, f, g, h, i, j))
      case _ => Left(LedgerDecodeError.ArrayLength(DecodeField.DRepVotingThresholds, 10, xs.size))

/** The protocol parameters the implemented ledger rules consult, for every era. Fields an era does
  * not have keep their genesis or zero value and are not consulted.
  * @param d
  *   Decentralisation parameter; removed in Babbage, where it is effectively 0.
  * @param minUTxOValue
  *   Shelley..Mary minimum lovelace per output.
  * @param coinsPerUTxOWord
  *   Alonzo minimum lovelace per 8-byte word of output.
  * @param costModels
  *   Cost model values by language tag (0 = PlutusV1), in the canonical parameter order.
  */
final case class ProtocolParams(
    minFeeA: BigInt,
    minFeeB: BigInt,
    maxTxSize: BigInt,
    maxBlockBodySize: BigInt,
    maxValueSize: BigInt,
    coinsPerUTxOByte: BigInt,
    keyDeposit: BigInt,
    poolDeposit: BigInt,
    eMax: BigInt,
    nOpt: BigInt,
    a0: Rational,
    rho: Rational,
    tau: Rational,
    minPoolCost: BigInt,
    protocolMajor: BigInt,
    drepDeposit: BigInt,
    drepActivity: BigInt,
    govActionDeposit: BigInt,
    govActionLifetime: BigInt,
    minFeeRefScriptCostPerByte: Rational,
    protocolMinor: BigInt = 0,
    maxBlockHeaderSize: BigInt = 1100,
    d: Rational = Rational.zero,
    extraEntropy: Nonce = Nonce.Neutral,
    minUTxOValue: BigInt = 0,
    coinsPerUTxOWord: BigInt = 0,
    priceMem: Rational = Rational.zero,
    priceSteps: Rational = Rational.zero,
    maxTxExUnits: ExUnits = ExUnits.zero,
    maxBlockExUnits: ExUnits = ExUnits.zero,
    collateralPercentage: BigInt = 0,
    maxCollateralInputs: BigInt = 0,
    costModels: Map[Int, Vector[BigInt]] = Map.empty,
    poolVotingThresholds: PoolVotingThresholds = PoolVotingThresholds.zero,
    drepVotingThresholds: DRepVotingThresholds = DRepVotingThresholds.zero,
    committeeMinSize: BigInt = 0,
    committeeMaxTermLength: BigInt = 0
):
  /** The effective decentralisation parameter (Babbage removed `d`). */
  def effectiveD: Rational = if protocolMajor >= 7 then Rational.zero else d

  def version: (BigInt, BigInt) = (protocolMajor, protocolMinor)

  /** `hardforkAlonzoValidatePoolAccountAddressNetID` (`pvMajor pv > 4`, so from Alonzo): only then
    * does a pool registration need the network of its reward account to be the node's. Shelley,
    * Allegra and Mary accept any network (`Cardano.Ledger.Shelley.Era`, `Shelley/Era.hs:246-251`;
    * used in `Shelley/Rules/Pool.hs:231`). Mainnet carries such a registration at slot 4,495,760.
    */
  def validatesPoolRewardAccountNetwork: Boolean = protocolMajor > 4

  /** `Shelley/SoftForks.hs:14` (f649f975): `pv > ProtVer 4 0`, including Mary 4.1. */
  def restrictsPoolMetadataHash: Boolean = protocolMajor > 4 ||
    (protocolMajor == 4 && protocolMinor > 0)

object ProtocolParams:
  /** The parameters `changed` has over `base`, laid over `full`: field by field, the value of
    * `changed` where it differs from `base`, else the one of `full`. The Haskell `PParams` of an
    * era has only the fields of that era, and the parameters read from a snapshot have the others
    * at zero; `full` is the node's whole set (the later eras' fields come from their genesis
    * files), `base` the same parameters as the snapshot has them, and `changed` an update of `base`
    * (the future parameters), which is thereby an update of `full`.
    */
  def overlay(full: ProtocolParams, base: ProtocolParams, changed: ProtocolParams): ProtocolParams =
    val m = summon[scala.deriving.Mirror.ProductOf[ProtocolParams]]
    val (f, b, c) = (
      Tuple.fromProduct(full).toArray,
      Tuple.fromProduct(base).toArray,
      Tuple.fromProduct(changed).toArray
    )
    m.fromProduct(
      Tuple.fromArray(Array.tabulate[Object](f.length)(i => if c(i) != b(i) then c(i) else f(i)))
    )

  /** Initial parameters from the Shelley, Alonzo and (when the chain starts in Conway) Conway
    * genesis files.
    */
  def fromGenesis(
      shelley: Json,
      alonzo: Json,
      conway: Option[Json]
  ): Either[ConfigError, ProtocolParams] =
    val s = shelley.hcursor.downField("protocolParams")
    val a = alonzo.hcursor
    val c = conway.getOrElse(Json.obj()).hcursor
    def opt[A: Decoder](cur: ACursor, name: String): Either[ConfigError, Option[A]] =
      cur
        .downField(name)
        .as[Option[A]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("protocol parameters", name, e)))
    // Alonzo genesis files differ in key names; absent values are zero.
    def anyInt(cur: ACursor, names: String*): Either[ConfigError, BigInt] =
      Right(
        names.iterator
          .map(n => cur.downField(n).as[Option[BigInt]].toOption.flatten)
          .collectFirst { case Some(v) => v }
          .getOrElse(BigInt(0))
      )
    def rational(cur: ACursor, names: String*): Either[ConfigError, Rational] =
      Right(
        names.iterator
          .map { name =>
            val q = cur.downField(name)
            q.downField("numerator")
              .as[BigInt]
              .toOption
              .zip(q.downField("denominator").as[BigInt].toOption)
              .map { case (n, d) => Rational(n, d) }
              .orElse(q.as[BigDecimal].toOption.map(Rational.fromDecimal))
          }
          .collectFirst { case Some(r) => r }
          .getOrElse(Rational.zero)
      )
    for
      minFeeA <- get[BigInt](s, "minFeeA")
      minFeeB <- get[BigInt](s, "minFeeB")
      maxTxSize <- get[BigInt](s, "maxTxSize")
      maxBlockBodySize <- get[BigInt](s, "maxBlockBodySize")
      maxBlockHeaderSize <- opt[BigInt](s, "maxBlockHeaderSize").map(_.getOrElse(BigInt(1100)))
      maxValueSize <- get[BigInt](a, "maxValueSize")
      perWord <- get[BigInt](a, "lovelacePerUTxOWord")
      keyDeposit <- get[BigInt](s, "keyDeposit")
      poolDeposit <- get[BigInt](s, "poolDeposit")
      eMax <- get[BigInt](s, "eMax")
      nOpt <- get[BigInt](s, "nOpt")
      a0 <- get[BigDecimal](s, "a0").map(Rational.fromDecimal)
      rho <- get[BigDecimal](s, "rho").map(Rational.fromDecimal)
      tau <- get[BigDecimal](s, "tau").map(Rational.fromDecimal)
      d <- opt[BigDecimal](s, "decentralisationParam").map(
        _.fold(Rational.zero)(Rational.fromDecimal)
      )
      minPoolCost <- get[BigInt](s, "minPoolCost")
      minUTxO <- opt[BigInt](s, "minUTxOValue").map(_.getOrElse(BigInt(0)))
      major <- get[BigInt](s.downField("protocolVersion"), "major")
      minor <- get[BigInt](s.downField("protocolVersion"), "minor")
      priceMem <- rational(a.downField("executionPrices"), "prMem", "priceMemory")
      priceSteps <- rational(a.downField("executionPrices"), "prSteps", "priceSteps")
      txMem <- anyInt(a.downField("maxTxExUnits"), "exUnitsMem", "memory")
      txSteps <- anyInt(a.downField("maxTxExUnits"), "exUnitsSteps", "steps")
      blockMem <- anyInt(a.downField("maxBlockExUnits"), "exUnitsMem", "memory")
      blockSteps <- anyInt(a.downField("maxBlockExUnits"), "exUnitsSteps", "steps")
      collateralPct <- anyInt(a, "collateralPercentage")
      maxCollateral <- anyInt(a, "maxCollateralInputs")
      entropy <- extraEntropy(s)
      baseModels <- costModelsOf(a.downField("costModels"))
      // Alonzo genesis files of Babbage-era clusters carry the later languages (PlutusV2) in
      // `extraConfig.costModels`; the ledger merges them into the genesis parameters.
      extraModels <- costModelsOf(a.downField("extraConfig").downField("costModels"))
      alonzoModels = baseModels ++ extraModels
      v3Model = c.downField("plutusV3CostModel").as[Vector[BigInt]].toOption
      costModels = v3Model.fold(alonzoModels)(m => alonzoModels.updated(2, m))
      drepDeposit <- opt[BigInt](c, "dRepDeposit").map(_.getOrElse(BigInt(0)))
      drepActivity <- opt[BigInt](c, "dRepActivity").map(_.getOrElse(BigInt(0)))
      govDeposit <- opt[BigInt](c, "govActionDeposit").map(_.getOrElse(BigInt(0)))
      govLifetime <- opt[BigInt](c, "govActionLifetime").map(_.getOrElse(BigInt(0)))
      refCost <- opt[BigDecimal](c, "minFeeRefScriptCostPerByte").map(
        _.fold(Rational.zero)(Rational.fromDecimal)
      )
      voting <- ConwayGenesis.votingParams(c)
    // Babbage translation: coinsPerUTxOByte = lovelacePerUTxOWord / 8 (8-byte words), floored.
    yield ProtocolParams(
      minFeeA,
      minFeeB,
      maxTxSize,
      maxBlockBodySize,
      maxValueSize,
      perWord / 8,
      keyDeposit,
      poolDeposit,
      eMax,
      nOpt,
      a0,
      rho,
      tau,
      minPoolCost,
      major,
      drepDeposit,
      drepActivity,
      govDeposit,
      govLifetime,
      refCost,
      protocolMinor = minor,
      maxBlockHeaderSize = maxBlockHeaderSize,
      d = d,
      extraEntropy = entropy,
      minUTxOValue = minUTxO,
      coinsPerUTxOWord = perWord,
      priceMem = priceMem,
      priceSteps = priceSteps,
      maxTxExUnits = ExUnits(txMem, txSteps),
      maxBlockExUnits = ExUnits(blockMem, blockSteps),
      collateralPercentage = collateralPct,
      maxCollateralInputs = maxCollateral,
      costModels = costModels,
      poolVotingThresholds = voting._1,
      drepVotingThresholds = voting._2,
      committeeMinSize = voting._3,
      committeeMaxTermLength = voting._4
    )

  private def extraEntropy(s: ACursor): Either[ConfigError, Nonce] =
    s.downField("extraEntropy").downField("tag").as[Option[String]] match
      case Right(None) | Right(Some("NeutralNonce")) => Right(Nonce.Neutral)
      case Right(Some(_)) =>
        get[String](s.downField("extraEntropy"), "contents")
          .flatMap(h =>
            xyz.kd5ujc.scarab.codec.Bytes.fromHex(h).leftMap(e => ConfigError(ConfigProblem.Hex(e)))
          )
          .map(Nonce.Hash(_))
      case Left(e) =>
        Left(ConfigError(ConfigProblem.JsonField("protocol parameters", "extraEntropy", e)))

  /** Genesis cost models are objects of named integers, canonically ordered by parameter name. */
  private def costModelsOf(c: ACursor): Either[ConfigError, Map[Int, Vector[BigInt]]] =
    c.focus.flatMap(_.asObject) match
      case None => Right(Map.empty)
      case Some(obj) =>
        obj.toVector
          .traverse { case (lang, model) =>
            val tag = lang match
              case "PlutusV1" => Right(0)
              case "PlutusV2" => Right(1)
              case "PlutusV3" => Right(2)
              case other      => Left(ConfigError(ConfigProblem.CostModelLanguage(other)))
            // The ledger reads named parameters into a map ordered by name; that order is the
            // canonical parameter order, whatever the order of the genesis file.
            val values = model.asObject match
              case Some(o) =>
                o.toVector
                  .sortBy(_._1)
                  .map(_._2)
                  .traverse(
                    _.as[BigInt].leftMap(e =>
                      ConfigError(ConfigProblem.JsonField("protocol parameters", "cost model", e))
                    )
                  )
              case None =>
                model
                  .as[Vector[BigInt]]
                  .leftMap(e =>
                    ConfigError(ConfigProblem.JsonField("protocol parameters", "cost model", e))
                  )
            (tag, values).tupled
          }
          .map(_.toMap)

  /** Apply a protocol-parameter update (field index -> original CBOR). Key 17 is the lovelace per
    * UTxO word in the eras before Babbage (`Alonzo/PParams.hs:664`) and per byte from Babbage
    * (`Babbage/PParams.hs`); which one is decided by the version of the parameters the update is
    * applied to, not by the version it sets, because the update is applied by the rules of the era
    * it was proposed in and the translation follows. Crossing into Babbage then derives
    * `coinsPerUTxOByte` from the per-word value, rounded down (`upgradeBabbagePParams True`,
    * `Babbage/PParams.hs:389-391`), also when the same update names key 17.
    */
  def update(p: ProtocolParams, u: ParamUpdate): Either[LedgerDecodeError, ProtocolParams] =
    // The fields are the original bytes of an update that was read before, from a body or from a
    // stored state: they are applied in any form that read took.
    given ByteStrings = ByteStrings.Chunked
    def field(key: Int)(f: (ProtocolParams, Node) => Either[LedgerDecodeError, ProtocolParams])(
        p: ProtocolParams
    ): Either[LedgerDecodeError, ProtocolParams] =
      u.fields.get(key).fold(Right(p))(raw => CborRead.decode(raw).flatMap(f(p, _)))
    def uint(name: DecodeSite)(set: (ProtocolParams, BigInt) => ProtocolParams) =
      (p: ProtocolParams, n: Node) => CborRead.uint(n, name).map(set(p, _))
    def rat(name: DecodeSite, bound: CborRead.Bound)(
        set: (ProtocolParams, Rational) => ProtocolParams
    ) =
      (p: ProtocolParams, n: Node) => CborRead.rational(n, name, bound).map(set(p, _))
    def exUnits(name: DecodeSite)(set: (ProtocolParams, ExUnits) => ProtocolParams) =
      (p: ProtocolParams, n: Node) =>
        CborRead
          .arrayN(n, 2, name)
          .flatMap(xs =>
            (CborRead.uint(xs(0), name), CborRead.uint(xs(1), name)).mapN(ExUnits(_, _))
          )
          .map(set(p, _))
    val steps: Vector[ProtocolParams => Either[LedgerDecodeError, ProtocolParams]] = Vector(
      field(0)(uint(DecodeField.MinFeeA)((p, v) => p.copy(minFeeA = v))),
      field(1)(uint(DecodeField.MinFeeB)((p, v) => p.copy(minFeeB = v))),
      field(2)(uint(DecodeField.MaxBlockBodySize)((p, v) => p.copy(maxBlockBodySize = v))),
      field(3)(uint(DecodeField.MaxTxSize)((p, v) => p.copy(maxTxSize = v))),
      field(4)(uint(DecodeField.MaxBlockHeaderSize)((p, v) => p.copy(maxBlockHeaderSize = v))),
      field(5)(uint(DecodeField.KeyDeposit)((p, v) => p.copy(keyDeposit = v))),
      field(6)(uint(DecodeField.PoolDeposit)((p, v) => p.copy(poolDeposit = v))),
      field(7)(uint(DecodeField.EMax)((p, v) => p.copy(eMax = v))),
      field(8)(uint(DecodeField.NOpt)((p, v) => p.copy(nOpt = v))),
      field(9)(rat(DecodeField.A0, CborRead.Bound.NonNegative)((p, v) => p.copy(a0 = v))),
      field(10)(rat(DecodeField.Rho, CborRead.Bound.Unit)((p, v) => p.copy(rho = v))),
      field(11)(rat(DecodeField.Tau, CborRead.Bound.Unit)((p, v) => p.copy(tau = v))),
      field(12)(rat(DecodeField.D, CborRead.Bound.Unit)((p, v) => p.copy(d = v))),
      field(13)((p, n) =>
        Nonce.decode(n, Nonce.NeutralFields.Stored).map(v => p.copy(extraEntropy = v))
      ),
      field(14)((p, n) =>
        CborRead
          .arrayN(n, 2, DecodeField.ProtocolVersion)
          .flatMap(xs =>
            (CborRead.uint(xs(0), DecodeField.Major), CborRead.uint(xs(1), DecodeField.Minor))
              .mapN((a, b) => p.copy(protocolMajor = a, protocolMinor = b))
          )
      ),
      field(15)(uint(DecodeField.MinUTxOValue)((p, v) => p.copy(minUTxOValue = v))),
      field(16)(uint(DecodeField.MinPoolCost)((p, v) => p.copy(minPoolCost = v))),
      field(17)(
        uint(DecodeField.CoinsPerUTxO)((acc, v) =>
          if p.protocolMajor >= 7 then acc.copy(coinsPerUTxOByte = v)
          else acc.copy(coinsPerUTxOWord = v)
        )
      ),
      field(18)((p, n) => costModels(n).map(m => p.copy(costModels = p.costModels ++ m))),
      field(19)((p, n) =>
        CborRead
          .arrayN(n, 2, DecodeField.Prices)
          .flatMap(xs =>
            (
              CborRead.rational(xs(0), DecodeField.PriceMem, CborRead.Bound.NonNegative),
              CborRead.rational(xs(1), DecodeField.PriceSteps, CborRead.Bound.NonNegative)
            )
              .mapN((m, s) => p.copy(priceMem = m, priceSteps = s))
          )
      ),
      field(20)(exUnits(DecodeField.MaxTxExUnits)((p, v) => p.copy(maxTxExUnits = v))),
      field(21)(exUnits(DecodeField.MaxBlockExUnits)((p, v) => p.copy(maxBlockExUnits = v))),
      field(22)(uint(DecodeField.MaxValueSize)((p, v) => p.copy(maxValueSize = v))),
      field(23)(uint(DecodeField.CollateralPercentage)((p, v) => p.copy(collateralPercentage = v))),
      field(24)(uint(DecodeField.MaxCollateralInputs)((p, v) => p.copy(maxCollateralInputs = v))),
      field(25)((p, n) =>
        rationals(n, DecodeField.PoolVotingThresholds)
          .flatMap(PoolVotingThresholds.fromVector)
          .map(v => p.copy(poolVotingThresholds = v))
      ),
      field(26)((p, n) =>
        rationals(n, DecodeField.DRepVotingThresholds)
          .flatMap(DRepVotingThresholds.fromVector)
          .map(v => p.copy(drepVotingThresholds = v))
      ),
      field(27)(uint(DecodeField.CommitteeMinSize)((p, v) => p.copy(committeeMinSize = v))),
      field(28)(
        uint(DecodeField.CommitteeMaxTermLength)((p, v) => p.copy(committeeMaxTermLength = v))
      ),
      field(29)(uint(DecodeField.GovActionLifetime)((p, v) => p.copy(govActionLifetime = v))),
      field(30)(uint(DecodeField.GovActionDeposit)((p, v) => p.copy(govActionDeposit = v))),
      field(31)(uint(DecodeField.DrepDeposit)((p, v) => p.copy(drepDeposit = v))),
      field(32)(uint(DecodeField.DrepActivity)((p, v) => p.copy(drepActivity = v))),
      field(33)(
        rat(DecodeField.MinFeeRefScriptCostPerByte, CborRead.Bound.NonNegative)((p, v) =>
          p.copy(minFeeRefScriptCostPerByte = v)
        )
      )
    )
    for updated <- steps.foldLeftM(p)((acc, step) => step(acc))
    yield
      if p.protocolMajor < 7 && updated.protocolMajor >= 7 then
        updated.copy(coinsPerUTxOByte = updated.coinsPerUTxOWord / 8)
      else updated

  private def rationals(n: Node, what: DecodeSite): Either[LedgerDecodeError, Vector[Rational]] =
    CborRead.array(n, what).flatMap(_.traverse(CborRead.rational(_, what, CborRead.Bound.Unit)))

  private def costModels(n: Node): Either[LedgerDecodeError, Map[Int, Vector[BigInt]]] =
    CborRead
      .map(n, DecodeField.CostModels)
      .flatMap(_.traverse { case (k, v) =>
        (
          CborRead.uint(k, DecodeField.Language).map(_.toInt),
          CborRead
            .array(v, DecodeField.CostModel)
            .flatMap(_.traverse(CborRead.int(_, DecodeField.Cost)))
        ).tupled
      }.map(_.toMap))

  private def get[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name)
      .as[A]
      .leftMap(e => ConfigError(ConfigProblem.JsonField("protocol parameters", name, e)))
