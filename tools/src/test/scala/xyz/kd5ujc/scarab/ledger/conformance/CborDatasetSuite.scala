// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.conformance

import java.util.concurrent.FutureTask
import munit.FunSuite

/** The harness of the cardano-cbor-dataset: its normal form, its verdicts, its rule tables and the
  * report it writes. The samples here are written by hand, not taken from the dataset.
  */
class CborDatasetSuite extends FunSuite:
  import Embedding.{hex, zeros}
  import DatasetCheck.{Category, Kind, Outcome}

  private def normal(in: String): Either[xyz.kd5ujc.scarab.codec.Cbor.Error, String] =
    CborNormalForm.of(hex(in)).map(_.hex)

  test("the normal form makes containers definite, joins chunks and shortens heads") {
    assertEquals(normal("9f0203ff"), Right("820203"))
    assertEquals(normal("bf0304ff"), Right("a10304"))
    assertEquals(normal("9fff"), Right("80"))
    assertEquals(normal("5f41aa42bbccff"), Right("43aabbcc"))
    assertEquals(normal("7f6178617aff"), Right("62787a"))
    assertEquals(normal("190007"), Right("07"))
    assertEquals(normal("d81e9f0306ff"), Right("d81e820306"))
  }

  test("the normal form keeps key order, repeated keys, tags, rationals and float widths") {
    for same <- Vector("a203010401", "a203010302", "d81e820408", "d9010380", "fa3f800000", "f6")
    do assertEquals(normal(same), Right(same))
  }

  test("the normal form folds a bignum that fits a head and keeps one that does not") {
    assertEquals(normal("c2420100"), Right("190100"))
    assertEquals(normal("c34100"), Right("20"))
    assertEquals(normal("c243000005"), Right("05"))
    assertEquals(normal("1bffffffffffffffff"), Right("1bffffffffffffffff"))
    assertEquals(normal("c24a0001" + "00" * 8), Right("c24901" + "00" * 8))
  }

  test("the normal form walks nesting deeper than a small stack") {
    val depth = 20000
    val deep = hex("81" * depth + "00")
    val task = new FutureTask(() => CborNormalForm.of(deep).map(_.size))
    new Thread(null, task, "small", 256 * 1024).start()
    assertEquals(task.get(), Right(depth + 1))
  }

  test("a category gives the expectation class, and an unknown one none") {
    assertEquals(Kind.of("valid"), Some(Kind.Valid))
    assertEquals(Kind.of("manual-valid"), Some(Kind.Valid))
    assertEquals(Kind.of("invalid-zap-0"), Some(Kind.Invalid))
    assertEquals(Kind.of("invalid-zap-12"), Some(Kind.Invalid))
    assertEquals(Kind.of("manual-invalid"), Some(Kind.Invalid))
    assertEquals(Kind.of("invalid-zap-"), None)
    assertEquals(Kind.of("other"), None)
  }

  private val input = "825820" + "11" * 32 + "05"

  private def conway(rule: String, kind: Kind, sample: String, expected: Option[String]) =
    DatasetCheck.check(rule, ConwayRules.rules.get(rule), kind, hex(sample), expected.map(hex))

  test(
    "a valid sample meets its expectation when its re-encoding normalises to the expected bytes"
  ) {
    assertEquals(conway("transaction_input", Kind.Valid, input, Some(input)), Outcome.Met)
    // an indefinite list is written definite by the encoder, as the expected bytes are
    assertEquals(
      conway("transaction_input", Kind.Valid, "9f5820" + "11" * 32 + "05ff", Some(input)),
      Outcome.Met
    )
  }

  test("a rational is written reduced, as the reference writes it") {
    assertEquals(conway("unit_interval", Kind.Valid, "d81e820204", Some("d81e820102")), Outcome.Met)
  }

  test("an invalid sample is met when refused and fails as a decode when accepted") {
    assertEquals(conway("credential", Kind.Invalid, "8202581c" + zeros(28), None), Outcome.Met)
    assertEquals(
      conway("credential", Kind.Invalid, "8200581c" + zeros(28), None),
      Outcome.Failed(Category.Decode, "succeeded when expected to fail")
    )
  }

  test(
    "a decoded value without an encoder, a mismatch and a missing reference are encode failures"
  ) {
    val voter = "8204581c" + zeros(28)
    assert(conway("voter", Kind.Valid, voter, Some(voter)) match
      case Outcome.Failed(Category.Encode, _) => true
      case _                                  => false)
    assert(conway("transaction_input", Kind.Valid, input, Some("8200")) match
      case Outcome.Failed(Category.Encode, _) => true
      case _                                  => false)
    assert(conway("transaction_input", Kind.Valid, input, None) match
      case Outcome.Failed(Category.Encode, _) => true
      case _                                  => false)
  }

  test("a rule Scarab has no decoder of fails every sample, valid or invalid") {
    for kind <- Kind.values do
      assert(conway("language", kind, "00", Some("00")) match
        case Outcome.Failed(Category.Decode, _) => true
        case _                                  => false)
    assert(DatasetCheck.check("no-such-rule", None, Kind.Invalid, hex("00"), None) match
      case Outcome.Failed(Category.Decode, _) => true
      case _                                  => false)
  }

  test("a byte-exact rule compares the kept bytes without normalising them") {
    val body = "a3008001800200"
    assertEquals(conway("transaction_body", Kind.Valid, body, Some(body)), Outcome.Met)
    val indefinite = "bf008001800200ff"
    assert(conway("transaction_body", Kind.Valid, indefinite, Some(body)) match
      case Outcome.Failed(Category.Encode, _) => true
      case _                                  => false)
  }

  test("an item is found in a parent's encoding by its path") {
    assertEquals(
      Step.at(hex("82a10205f6"), List(Step.Index(0), Step.Key(2))).map(_.hex),
      Right("05")
    )
    assertEquals(Step.at(hex("a1410102"), List(Step.FirstKey)).map(_.hex), Right("4101"))
    assert(Step.at(hex("80"), List(Step.Index(0))).isLeft)
  }

  /** The rules of the dataset's `EraSpec.hs` (cardano-cbor-dataset ed6c49a4), the names of the
    * corpus directories: every one has an entry, mapped or with the reason it is not.
    */
  private val shared = Set(
    "block",
    "header",
    "header_body",
    "transaction",
    "transaction_body",
    "transaction_input",
    "transaction_witness_set",
    "transaction_output",
    "value",
    "script",
    "datum_option",
    "certificate",
    "certificates",
    "native_script",
    "plutus_data",
    "redeemers",
    "auxiliary_data",
    "credential",
    "drep",
    "relay",
    "gov_action",
    "voting_procedure",
    "voting_procedures",
    "proposal_procedure",
    "proposal_procedures",
    "protocol_param_update",
    "cost_models",
    "voter",
    "metadatum",
    "drep_voting_thresholds",
    "pool_voting_thresholds",
    "ex_units",
    "anchor",
    "gov_action_id",
    "constitution",
    "operational_cert",
    "bootstrap_witness",
    "vkeywitness",
    "withdrawals",
    "mint",
    "address",
    "reward_account",
    "protocol_version",
    "coin",
    "epoch",
    "slot",
    "block_number",
    "network_id",
    "url",
    "dns_name",
    "unit_interval",
    "nonnegative_interval",
    "transaction_id",
    "script_hash",
    "addr_keyhash",
    "pool_keyhash",
    "vrf_keyhash",
    "auxiliary_data_hash",
    "policy_id",
    "asset_name",
    "stake_credential",
    "drep_credential",
    "committee_cold_credential",
    "committee_hot_credential",
    "vote",
    "ex_unit_prices",
    "pool_metadata",
    "kes_period",
    "vkey",
    "%multiasset<positive_coin>",
    "alonzo_transaction_output",
    "language",
    "auxiliary_data_array",
    "auxiliary_scripts",
    "hash28",
    "hash32",
    "script_data_hash",
    "guardrails_script_hash",
    "major_protocol_version",
    "positive_coin",
    "nonzero_int64",
    "big_int",
    "bounded_bytes",
    "ipv4",
    "ipv6",
    "kes_vkey",
    "vrf_vkey",
    "signature",
    "%constr<plutus_data>",
    "plutus_v1_script",
    "plutus_v2_script",
    "plutus_v3_script",
    "data",
    "metadata",
    "auxiliary_data_map",
    "babbage_transaction_output"
  )

  test("every rule of each era has an entry") {
    assertEquals(ConwayRules.rules.keySet, shared + "redeemer")
    assertEquals(
      DijkstraRules.rules.keySet,
      shared ++ Set(
        "eb_announcement",
        "block_body",
        "sub_transaction_body",
        "account_balance_interval",
        "account_balance_intervals"
      )
    )
  }

  test("the report holds the version 2 fields and one result per sample path") {
    val json = CborDatasetMain.reportJson(
      "11.0",
      "a" * 40,
      "b" * 40,
      1L,
      Vector(
        "coin/valid/00001-x.input.cbor" -> Outcome.Met,
        "coin/manual-invalid/y.input.cbor" -> Outcome.Failed(
          Category.Decode,
          "succeeded when expected to fail"
        )
      )
    )
    val keys = json.asObject.map(_.keys.toSet)
    assertEquals(
      keys,
      Some(
        Set(
          "version",
          "generated_at",
          "protocol_version",
          "dataset_commit",
          "implementation_commit",
          "results"
        )
      )
    )
    assertEquals(json.hcursor.downField("version").as[String], Right("2"))
    assertEquals(
      json.hcursor.downField("results").downField("coin/valid/00001-x.input.cbor").as[Boolean],
      Right(true)
    )
    assertEquals(
      json.hcursor.downField("results").downField("coin/manual-invalid/y.input.cbor").as[String],
      Right("decode: succeeded when expected to fail")
    )
  }
