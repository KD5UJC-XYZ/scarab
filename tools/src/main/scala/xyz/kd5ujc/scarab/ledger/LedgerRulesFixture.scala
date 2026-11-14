// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, DecodingFailure, Json}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError, Rational}
import xyz.kd5ujc.scarab.plutus.ExUnits

/** Why a fixture of the ledger-rules scenario (node-diversity-conformance, the transaction fixtures
  * of Amaru's `crates/amaru-ledger/tests/data/transaction`) cannot be read, or cannot be run as it
  * states. Every case leaves the fixture without a verdict.
  */
enum FixtureError extends DomainError:
  /** The file is not JSON. */
  case Syntax(file: String, cause: io.circe.ParsingFailure)

  /** A field is missing or of the wrong type. */
  case Field(file: String, cause: DecodingFailure)

  /** A `$ref` names a document that no documents root holds. */
  case Reference(ref: String)

  /** A `$override` is applied to a document that is not an object. */
  case OverrideOfNonObject(ref: String)

  /** A hex field is not hex. */
  case Hex(field: String, cause: Bytes.HexError)

  /** A CBOR field does not decode as what it holds. */
  case Cbor(field: String, cause: LedgerDecodeError)

  /** A value is outside the vocabulary of the ledger state. */
  case Vocab(field: String, cause: state.VocabError)

  /** The fixture asks for what the Conway rules cannot be given: a constant of the Conway ledger
    * set to another value, or an era history other than one Conway era.
    */
  case Unsupported(what: String)

  /** A seeded proposal chains to neither a root nor an earlier proposal of its lineage. */
  case Unattached(cause: ProposalsError)

  def message: String = this match
    case Syntax(file, cause)      => s"$file: not JSON: ${cause.message}"
    case Field(file, cause)       => s"$file: ${cause.getMessage}"
    case Reference(ref)           => s"no documents root holds $ref"
    case OverrideOfNonObject(ref) => s"$$override of $ref, which is not an object"
    case Hex(field, cause)        => s"$field: ${cause.message}"
    case Cbor(field, cause)       => s"$field: ${cause.message}"
    case Vocab(field, cause)      => s"$field: $cause"
    case Unsupported(what)        => s"unsupported fixture: $what"
    case Unattached(cause)        => s"seeded proposals: $cause"

/** A bound of the era history (`EraBound`): the time in seconds since the system start. */
final case class FixtureBound(time: BigInt, slot: BigInt, epoch: BigInt)

/** The one Conway era of a fixture's era history. */
final case class FixtureEra(
    start: FixtureBound,
    end: Option[FixtureBound],
    epochSize: BigInt,
    slotLengthMillis: BigInt
)

final case class FixtureEraHistory(stabilityWindow: BigInt, era: FixtureEra)

/** `CertificatePointer`s are read and dropped: the Conway state keeps no pointers. */
final case class FixtureAccount(
    credential: Bytes,
    deposit: BigInt,
    rewards: BigInt,
    pool: Option[Bytes],
    drep: Option[Bytes]
)

final case class FixtureDRep(credential: Bytes, deposit: BigInt, validUntil: BigInt)

/** A committee member's status: the hot credential it authorised, or its resignation. */
enum FixtureAuthorization:
  case Hot(credential: Bytes)
  case Resigned

final case class FixtureMember(
    cold: Bytes,
    status: Option[FixtureAuthorization],
    validUntil: Option[BigInt]
)

/** The lineage a seeded proposal chains onto (`ProposalKind`). */
enum FixtureLineage:
  case ParameterChange(securityGroup: Boolean)
  case HardFork(major: BigInt, minor: BigInt)
  case Committee
  case Constitution
  case Information
  case TreasuryWithdrawals

final case class FixtureProposal(id: GovActionId, lineage: FixtureLineage, validUntil: BigInt)

final case class FixtureState(
    utxo: Vector[(Bytes, Bytes)],
    pools: Vector[Bytes],
    accounts: Vector[FixtureAccount],
    dreps: Vector[FixtureDRep],
    committee: Vector[FixtureMember],
    proposals: Vector[FixtureProposal],
    roots: PrevIds,
    dormantEpochs: BigInt,
    treasury: BigInt,
    guardrail: Option[Bytes]
)

/** A fixture with its shared documents resolved: everything the state is built from. Its `expected`
  * is not part of it: the verdict is Scarab's, and the expectation is read apart
  * ([[LedgerRulesFixture.expectation]]), only to choose the name a refusal is reported by among the
  * failures Scarab raised ([[Selection]]).
  */
/** A fixture's `expected` (the fixture schema's `Expected`): a pass, a transaction that must not
  * decode, or the predicate it must fail.
  */
enum FixtureExpectation derives CanEqual:
  case Pass
  case DecodingFailure
  case Failure(predicate: String)

  /** The report name of the predicate a refusal is expected for, if the report schema has it. */
  def refusal: Option[ReportName] = this match
    case Failure(predicate) => ReportName.parse(predicate)
    case _                  => None

final case class LedgerRulesFixture(
    network: Int,
    eraHistory: FixtureEraHistory,
    params: ProtocolParams,
    state: FixtureState,
    slot: BigInt,
    txIndex: Int,
    transaction: Bytes
)

object LedgerRulesFixture:
  /** The constants the Conway ledger fixes that the fixture schema carries as parameters: the
    * reference-script size limit (`maxRefScriptSizePerTx`, `Conway/Rules/Ledger.hs`) and the tier
    * of the reference-script fee (`Conway/Tx.hs` `tierRefScriptFee`, 25 KiB tiers, a factor of
    * 1.2). A fixture with another value asks for rules the Conway ledger does not have.
    */
  private val RefScriptsLimit = BigInt(RefScripts.MaxRefScriptSizePerTx)
  private val RefScriptsRange = BigInt(25600)
  private val RefScriptsMultiplier = Rational(12, 10)

  /** The fixture in `text`, its `$ref` documents read through `document` (the path the reference
    * names, relative to a fixture data root).
    */
  def read(
      file: String,
      text: String,
      document: String => Option[String]
  ): Either[FixtureError, LedgerRulesFixture] =
    for
      json <- io.circe.parser.parse(text).leftMap(FixtureError.Syntax(file, _))
      c = json.hcursor
      network <- field[String](file, c, "network").map(n => if n == "mainnet" then 1 else 0)
      eraJson <- field[Json](file, c, "era_history").flatMap(resolve(_, document))
      eras <- eraHistory(file, eraJson.hcursor)
      ppJson <- field[Json](file, c, "protocol_parameters").flatMap(resolve(_, document))
      pp <- params(file, ppJson.hcursor)
      st <- initialState(file, c.downField("initial_state"))
      slot <- field[BigInt](file, c.downField("point"), "slot")
      ix <- field[Int](file, c.downField("point"), "transaction_index")
      txHex <- field[String](file, c, "transaction")
      tx <- hex("transaction", txHex)
    yield LedgerRulesFixture(network, eras, pp, st, slot, ix, tx)

  /** The `expected` of the fixture in `text`: `"Pass"`, `{ "decoding_failure": true }` or
    * `{ "predicate": <name>, ... }` (`Expected.hs` of the reference harness).
    */
  def expectation(file: String, text: String): Either[FixtureError, FixtureExpectation] =
    for
      json <- io.circe.parser.parse(text).leftMap(FixtureError.Syntax(file, _))
      c = json.hcursor.downField("expected")
      expected <-
        c.as[String].toOption match
          case Some("Pass") => Right(FixtureExpectation.Pass)
          case _ if c.downField("decoding_failure").succeeded =>
            Right(FixtureExpectation.DecodingFailure)
          case _ => field[String](file, c, "predicate").map(FixtureExpectation.Failure(_))
    yield expected

  /** A shared document (`SharedDocument.hs`): inline, or `$ref` to a document with an optional
    * `$override` whose top-level keys replace the referenced ones.
    */
  private def resolve(
      json: Json,
      document: String => Option[String]
  ): Either[FixtureError, Json] =
    json.asObject.flatMap(_("$ref")).flatMap(_.asString) match
      case None => Right(json)
      case Some(ref) =>
        for
          text <- document(ref).toRight(FixtureError.Reference(ref))
          referenced <- io.circe.parser.parse(text).leftMap(FixtureError.Syntax(ref, _))
          overrides = json.asObject.flatMap(_("$override")).flatMap(_.asObject)
          merged <- overrides.fold(Right(referenced)) { o =>
            referenced.asObject
              .map(r =>
                Json.fromJsonObject(o.toIterable.foldLeft(r)((acc, kv) => acc.add(kv._1, kv._2)))
              )
              .toRight(FixtureError.OverrideOfNonObject(ref))
          }
        yield merged

  private def field[A: Decoder](file: String, c: ACursor, name: String): Either[FixtureError, A] =
    c.downField(name).as[A].leftMap(FixtureError.Field(file, _))

  private def optional[A: Decoder](
      file: String,
      c: ACursor,
      name: String
  ): Either[FixtureError, Option[A]] =
    c.downField(name).as[Option[A]].leftMap(FixtureError.Field(file, _))

  private def list[A](file: String, c: ACursor, name: String)(
      item: ACursor => Either[FixtureError, A]
  ): Either[FixtureError, Vector[A]] =
    optional[Vector[Json]](file, c, name).flatMap(
      _.getOrElse(Vector.empty).traverse(j => item(j.hcursor))
    )

  private def hex(what: String, text: String): Either[FixtureError, Bytes] =
    Bytes.fromHex(text).leftMap(FixtureError.Hex(what, _))

  private def hexField(file: String, c: ACursor, name: String): Either[FixtureError, Bytes] =
    field[String](file, c, name).flatMap(hex(name, _))

  private def ratio(file: String, c: ACursor): Either[FixtureError, Rational] =
    (field[BigInt](file, c, "numerator"), field[BigInt](file, c, "denominator"))
      .mapN(Rational(_, _))

  private def exUnits(file: String, c: ACursor): Either[FixtureError, ExUnits] =
    (field[BigInt](file, c, "mem"), field[BigInt](file, c, "steps")).mapN(ExUnits(_, _))

  private def bound(file: String, c: ACursor): Either[FixtureError, FixtureBound] =
    (
      field[BigInt](file, c, "time"),
      field[BigInt](file, c, "slot"),
      field[BigInt](file, c, "epoch")
    )
      .mapN(FixtureBound.apply)

  /** `EraHistory.hs`: one Conway era, as the reference harness refuses any other history. */
  private def eraHistory(file: String, c: ACursor): Either[FixtureError, FixtureEraHistory] =
    for
      window <- field[BigInt](file, c, "stability_window")
      eras <- field[Vector[Json]](file, c, "eras")
      one <- eras match
        case Vector(e) => Right(e.hcursor)
        case _         => Left(FixtureError.Unsupported(s"an era history of ${eras.size} eras"))
      name <- field[String](file, one.downField("params"), "era_name")
      _ <- Either.cond(name == "Conway", (), FixtureError.Unsupported(s"an era history in $name"))
      start <- bound(file, one.downField("start"))
      end <- optional[Json](file, one, "end").flatMap(_.traverse(j => bound(file, j.hcursor)))
      size <- field[BigInt](file, one.downField("params"), "epoch_size_slots")
      length <- field[BigInt](file, one.downField("params"), "slot_length")
    yield FixtureEraHistory(window, FixtureEra(start, end, size, length))

  /** `ProtocolParameters.hs`, field for field; the cost models by language tag in their order. */
  private def params(file: String, c: ACursor): Either[FixtureError, ProtocolParams] =
    def int(name: String) = field[BigInt](file, c, name)
    def rat(name: String) = ratio(file, c.downField(name))
    val refFee = c.downField("min_fee_reference_scripts")
    val pools = c.downField("stake_pool_voting_thresholds")
    val dreps = c.downField("delegate_representative_voting_thresholds")
    def poolRatio(name: String) = ratio(file, pools.downField(name))
    def drepRatio(name: String) = ratio(file, dreps.downField(name))
    for
      refLimit <- int("max_reference_scripts_size")
      _ <- Either.cond(
        refLimit == RefScriptsLimit,
        (),
        FixtureError.Unsupported(
          s"max_reference_scripts_size $refLimit (Conway fixes $RefScriptsLimit)"
        )
      )
      range <- field[BigInt](file, refFee, "range")
      multiplier <- ratio(file, refFee.downField("multiplier"))
      _ <- Either.cond(
        range == RefScriptsRange && multiplier == RefScriptsMultiplier,
        (),
        FixtureError.Unsupported(s"a reference-script fee tier of $range bytes and $multiplier")
      )
      utxoConstant <- optional[BigInt](file, c, "min_utxo_deposit_constant")
      _ <- Either.cond(
        utxoConstant.forall(_ == 0),
        (),
        FixtureError.Unsupported(s"min_utxo_deposit_constant $utxoConstant")
      )
      refBase <- ratio(file, refFee.downField("base"))
      models <- costModels(file, c.downField("plutus_cost_models"))
      poolThresholds <- (
        poolRatio("motion_no_confidence"),
        poolRatio("committee_normal"),
        poolRatio("committee_no_confidence"),
        poolRatio("hard_fork_initiation"),
        poolRatio("security_voting_threshold")
      ).mapN(PoolVotingThresholds.apply)
      drepThresholds <- (
        drepRatio("motion_no_confidence"),
        drepRatio("committee_normal"),
        drepRatio("committee_no_confidence"),
        drepRatio("update_constitution"),
        drepRatio("hard_fork_initiation"),
        drepRatio("pp_network_group"),
        drepRatio("pp_economic_group"),
        drepRatio("pp_technical_group"),
        drepRatio("pp_governance_group"),
        drepRatio("treasury_withdrawal")
      ).mapN(DRepVotingThresholds.apply)
      prices = c.downField("script_execution_prices")
      memPrice <- ratio(file, prices.downField("mem_price"))
      stepPrice <- ratio(file, prices.downField("step_price"))
      txUnits <- exUnits(file, c.downField("max_execution_units_per_transaction"))
      blockUnits <- exUnits(file, c.downField("max_execution_units_per_block"))
      major <- field[BigInt](file, c.downField("version"), "major")
      minor <- field[BigInt](file, c.downField("version"), "minor")
      minFeeA <- int("min_fee_coefficient")
      minFeeB <- int("min_fee_constant")
      maxTx <- int("max_transaction_size")
      maxBody <- int("max_block_body_size")
      maxHeader <- int("max_block_header_size")
      maxValue <- int("max_value_size")
      perByte <- int("min_utxo_deposit_coefficient")
      keyDeposit <- int("stake_credential_deposit")
      poolDeposit <- int("stake_pool_deposit")
      eMax <- int("stake_pool_retirement_epoch_bound")
      nOpt <- int("desired_number_of_stake_pools")
      a0 <- rat("stake_pool_pledge_influence")
      rho <- rat("monetary_expansion")
      tau <- rat("treasury_expansion")
      minPoolCost <- int("min_stake_pool_cost")
      drepDeposit <- int("delegate_representative_deposit")
      drepActivity <- int("delegate_representative_max_idle_time")
      govDeposit <- int("governance_action_deposit")
      govLifetime <- int("governance_action_lifetime")
      collateral <- int("collateral_percentage")
      maxCollateral <- int("max_collateral_inputs")
      committeeMin <- int("constitutional_committee_min_size")
      committeeTerm <- int("constitutional_committee_max_term_length")
    yield ProtocolParams(
      minFeeA = minFeeA,
      minFeeB = minFeeB,
      maxTxSize = maxTx,
      maxBlockBodySize = maxBody,
      maxValueSize = maxValue,
      coinsPerUTxOByte = perByte,
      keyDeposit = keyDeposit,
      poolDeposit = poolDeposit,
      eMax = eMax,
      nOpt = nOpt,
      a0 = a0,
      rho = rho,
      tau = tau,
      minPoolCost = minPoolCost,
      protocolMajor = major,
      drepDeposit = drepDeposit,
      drepActivity = drepActivity,
      govActionDeposit = govDeposit,
      govActionLifetime = govLifetime,
      minFeeRefScriptCostPerByte = refBase,
      protocolMinor = minor,
      maxBlockHeaderSize = maxHeader,
      priceMem = memPrice,
      priceSteps = stepPrice,
      maxTxExUnits = txUnits,
      maxBlockExUnits = blockUnits,
      collateralPercentage = collateral,
      maxCollateralInputs = maxCollateral,
      costModels = models,
      poolVotingThresholds = poolThresholds,
      drepVotingThresholds = drepThresholds,
      committeeMinSize = committeeMin,
      committeeMaxTermLength = committeeTerm
    )

  /** `plutusCostModelsFromJson`: the languages present, by tag (0 for Plutus V1). */
  private def costModels(file: String, c: ACursor): Either[FixtureError, Map[Int, Vector[BigInt]]] =
    Vector("plutus_v1" -> 0, "plutus_v2" -> 1, "plutus_v3" -> 2)
      .traverse((name, tag) => optional[Vector[BigInt]](file, c, name).map(_.map(tag -> _)))
      .map(_.flatten.toMap)

  /** `InitialState.hs`: absent collections are empty, and a `voting_state` takes precedence over
    * the legacy DRep, committee and dormant-epoch fields.
    */
  private def initialState(file: String, c: ACursor): Either[FixtureError, FixtureState] =
    val voting = c.downField("voting_state")
    for
      utxo <- list(file, c, "utxo")(e =>
        (hexField(file, e, "input"), hexField(file, e, "output")).tupled
      )
      pools <- list(file, c, "pools")(p =>
        p.as[String].leftMap(FixtureError.Field(file, _)).flatMap(hex("pools", _))
      )
      accounts <- list(file, c, "accounts")(account(file, _))
      hasVoting = voting.succeeded
      dreps <- list(file, if hasVoting then voting else c, "dreps")(drep(file, _))
      members <- list(file, if hasVoting then voting else c, "committee")(member(file, _))
      proposals <- list(file, c, "proposals")(proposal(file, _))
      roots <- proposalRoots(file, c.downField("proposals_roots"))
      dormant <-
        if hasVoting then optional[BigInt](file, voting, "consecutive_dormant_epochs")
        else
          optional[BigInt](file, c.downField("governance_activity"), "consecutive_dormant_epochs")
      treasury <- optional[BigInt](file, c.downField("pots"), "treasury")
      guardrail <- optional[String](file, c, "guardrail_script").flatMap(
        _.traverse(hex("guardrail_script", _))
      )
    yield FixtureState(
      utxo,
      pools,
      accounts,
      dreps,
      members,
      proposals,
      roots,
      dormant.getOrElse(0),
      treasury.getOrElse(0),
      guardrail
    )

  private def account(file: String, c: ACursor): Either[FixtureError, FixtureAccount] =
    (
      hexField(file, c, "credential"),
      field[BigInt](file, c, "deposit"),
      optional[BigInt](file, c, "rewards").map(_.getOrElse(BigInt(0))),
      optional[String](file, c.downField("pool"), "id").flatMap(_.traverse(hex("pool", _))),
      optional[String](file, c.downField("drep"), "id").flatMap(_.traverse(hex("drep", _)))
    ).mapN(FixtureAccount.apply)

  private def drep(file: String, c: ACursor): Either[FixtureError, FixtureDRep] =
    (
      hexField(file, c, "credential"),
      field[BigInt](file, c, "deposit"),
      field[BigInt](file, c, "valid_until")
    )
      .mapN(FixtureDRep.apply)

  /** `CommitteeMember`: a status is `"resigned"` or the CBOR of the hot credential. */
  private def member(file: String, c: ACursor): Either[FixtureError, FixtureMember] =
    (
      hexField(file, c, "cold_credential"),
      optional[String](file, c, "status").flatMap(_.traverse {
        case "resigned" => Right(FixtureAuthorization.Resigned)
        case other      => hex("status", other).map(FixtureAuthorization.Hot(_))
      }),
      optional[BigInt](file, c, "valid_until")
    ).mapN(FixtureMember.apply)

  private def actionId(file: String, c: ACursor): Either[FixtureError, GovActionId] =
    (hexField(file, c, "transaction_id"), field[BigInt](file, c, "proposal_index"))
      .mapN(GovActionId(_, _))

  /** `[id, kind, valid_until]`, the slim form every fixture of the corpus uses. */
  private def proposal(file: String, c: ACursor): Either[FixtureError, FixtureProposal] =
    for
      id <- actionId(file, c.downN(0))
      kind <- c.downN(1).as[String].leftMap(FixtureError.Field(file, _))
      lineage <- FixtureLineage
        .parse(kind)
        .toRight(FixtureError.Unsupported(s"proposal kind $kind"))
      until <- c.downN(2).as[BigInt].leftMap(FixtureError.Field(file, _))
    yield FixtureProposal(id, lineage, until)

  private def proposalRoots(file: String, c: ACursor): Either[FixtureError, PrevIds] =
    def root(name: String) =
      if c.downField(name).succeeded then actionId(file, c.downField(name)).map(Some(_))
      else Right(None)
    (
      root("protocol_parameters"),
      root("hard_fork"),
      root("constitutional_committee"),
      root("constitution")
    )
      .mapN(PrevIds(_, _, _, _))

object FixtureLineage:
  private val HardForkKind = """HardFork\(([0-9]+)\.([0-9]+)\)""".r

  /** `ProposalSlim`'s `FromJSON`: `Orphan` names an information action. */
  def parse(kind: String): Option[FixtureLineage] = kind match
    case "ProtocolParameters"                 => Some(FixtureLineage.ParameterChange(false))
    case "ProtocolParameters(security-group)" => Some(FixtureLineage.ParameterChange(true))
    case "ConstitutionalCommittee"            => Some(FixtureLineage.Committee)
    case "Constitution"                       => Some(FixtureLineage.Constitution)
    case "Orphan" | "Information"             => Some(FixtureLineage.Information)
    case "TreasuryWithdrawals"                => Some(FixtureLineage.TreasuryWithdrawals)
    case HardForkKind(major, minor) => Some(FixtureLineage.HardFork(BigInt(major), BigInt(minor)))
    case _                          => None
