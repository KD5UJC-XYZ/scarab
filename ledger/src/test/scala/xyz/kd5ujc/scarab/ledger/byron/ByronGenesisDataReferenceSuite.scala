// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import cats.syntax.all.*
import io.circe.{Decoder, Json, JsonObject}
import io.circe.parser
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  ByronFieldError,
  ByronGenesis,
  ByronGenesisField,
  ByronGenesisFile,
  ConfigProblem
}
import xyz.kd5ujc.scarab.ledger.json.CanonicalJson

/** The Byron genesis as `readGenesisData` reads it with `FromJSON GenesisData`
  * (`Cardano/Chain/Genesis/Data.hs:85-102`, cardano-ledger f649f975), #734:
  * `fixtures/reference/byron-genesis-data/cases.jsonl` holds the genesis files of the repository
  * and edits of the devnet genesis that cover, field by field, each form the field's canonical-JSON
  * instance takes and refuses, and the order in which the fields are read. For each, what the
  * unchanged released reader did: the fields it read (for an edit, those that differ from the
  * unedited genesis), or the field it refused and why.
  */
class ByronGenesisDataReferenceSuite extends FunSuite:
  private given Decoder[CanonicalJson.Kind] =
    Decoder[JsonObject].emap(o =>
      o.keys.headOption
        .flatMap(k => CanonicalJson.Kind.values.find(_.toString == k))
        .toRight(o.toString)
    )
  private given Decoder[ByronGenesisField] =
    Decoder[String].emap(s => ByronGenesisField.values.find(_.toString == s).toRight(s))

  /** The reference's `SchemaError`, as the field's instance raises it. */
  private enum Problem derives Decoder:
    case Missing, NumberText, Hex, Base64
    case Kind(expected: CanonicalJson.Kind, actual: CanonicalJson.Kind)
    case Portion(value: String)
    case HashLength(length: Int)
    case KeyLength(length: Int)
    case SignatureLength(length: Int)
    case DelegationKey(keyPrefix: String, issuerPrefix: String)
    case MultiLayer(delegatePrefix: String)

  private final case class Refusal(field: ByronGenesisField, problem: Problem) derives Decoder

  private final case class Row(
      name: String,
      file: Option[String],
      base: Option[String],
      edits: Option[Vector[(String, String)]],
      hash: Option[String],
      accepted: Boolean,
      result: Option[JsonObject],
      changed: Option[JsonObject],
      failure: Option[String],
      refusal: Option[Refusal]
  ) derives Decoder

  private val rows: Vector[Row] =
    Files
      .readAllLines(Path.of("fixtures", "reference", "byron-genesis-data", "cases.jsonl"))
      .toArray(Array.empty[String])
      .toVector
      .traverse(parser.decode[Row](_))
      .fold(e => fail(e.toString), identity)

  private def text(path: String): String = Files.readString(Path.of(path), UTF_8)

  /** The case's genesis: a file, or the base with each edit applied where its text occurs once. */
  private def bytes(row: Row): Bytes =
    val body = row.file.map(text).getOrElse {
      val base = text(row.base.getOrElse(fail("a case has a file or a base")))
      row.edits.getOrElse(Vector.empty).foldLeft(base) { case (t, (old, replacement)) =>
        assertEquals(t.split(java.util.regex.Pattern.quote(old), -1).length, 2, old)
        t.replace(old, replacement)
      }
    }
    Bytes.fromArray(body.getBytes(UTF_8))

  private def number(n: BigInt): Json = n.toString.asJson

  /** Scarab's genesis in the shape the harness reports `GenesisData` (`Main.hs`, `project`). */
  private def project(g: ByronGenesis): JsonObject =
    val p = g.params
    val multiplier = p.txFeePolicy.multiplierNano
    val divisor = multiplier.gcd(TxFeePolicy.Nano)
    JsonObject(
      "bootStakeholders" -> g.genesisKeys.map(_.hex).asJson,
      "heavyDelegation" -> g.delegations
        .map(d =>
          Json.obj(
            "key" -> ByronCbor.keyHash(d.issuer).hex.asJson,
            "omega" -> number(d.epoch),
            "issuerPk" -> d.issuer.hex.asJson,
            "delegatePk" -> d.delegate.hex.asJson,
            "cert" -> d.cert.hex.asJson
          )
        )
        .asJson,
      "startTime" -> number(g.startTime),
      "nonAvvmBalances" -> g.nonAvvm.size.asJson,
      "blockVersionData" -> Json.obj(
        "scriptVersion" -> number(p.scriptVersion),
        "slotDuration" -> number(p.slotDurationMs),
        "maxBlockSize" -> number(p.maxBlockSize),
        "maxHeaderSize" -> number(p.maxHeaderSize),
        "maxTxSize" -> number(p.maxTxSize),
        "maxProposalSize" -> number(p.maxProposalSize),
        "mpcThd" -> number(p.mpcThd),
        "heavyDelThd" -> number(p.heavyDelThd),
        "updateVoteThd" -> number(p.updateVoteThd),
        "updateProposalThd" -> number(p.updateProposalThd),
        "updateImplicit" -> number(p.updateProposalTtl),
        "softforkRule" -> Json.obj(
          "initThd" -> number(p.softforkRule.initThd),
          "minThd" -> number(p.softforkRule.minThd),
          "thdDecrement" -> number(p.softforkRule.thdDecrement)
        ),
        "txFeePolicy" -> Json.obj(
          "summand" -> number(p.txFeePolicy.summandNano / TxFeePolicy.Nano),
          "multiplierNumerator" -> number(multiplier / divisor),
          "multiplierDenominator" -> number(TxFeePolicy.Nano / divisor)
        ),
        "unlockStakeEpoch" -> number(p.unlockStakeEpoch)
      ),
      "k" -> number(g.securityParam),
      "protocolMagic" -> number(g.protocolMagic),
      "avvmDistr" -> g.avvm.size.asJson
    )

  private def matches(refusal: Refusal, problem: ConfigProblem): Boolean = problem match
    case ConfigProblem.ByronGenesisValue(field, cause) if field == refusal.field =>
      (refusal.problem, cause) match
        case (Problem.Missing, ByronFieldError.Missing)                       => true
        case (Problem.Kind(e, a), ByronFieldError.Kind(f, b))                 => e == f && a == b
        case (Problem.NumberText, ByronFieldError.NumberText(_))              => true
        case (Problem.Portion(v), ByronFieldError.Portion(n))                 => BigInt(v) == n
        case (Problem.Hex, ByronFieldError.Hex(_, _))                         => true
        case (Problem.HashLength(n), ByronFieldError.HashLength(_, m))        => n == m
        case (Problem.Base64, ByronFieldError.Base64(_))                      => true
        case (Problem.KeyLength(n), ByronFieldError.KeyLength(m))             => n == m
        case (Problem.SignatureLength(n), ByronFieldError.SignatureLength(m)) => n == m
        case (Problem.MultiLayer(d), ByronFieldError.MultiLayer(delegate)) =>
          delegate.hex.startsWith(d)
        case (Problem.DelegationKey(k, i), ByronFieldError.DelegationKey(key, issuer)) =>
          key.hex.startsWith(k) && issuer.hex.startsWith(i)
        case _ => false
    case _ => false

  private val base: JsonObject =
    rows
      .find(r => r.file.isDefined && r.file == rows.flatMap(_.base).headOption)
      .flatMap(_.result)
      .getOrElse(fail("the base genesis is a case of its own"))

  test("the corpus covers every field, taken and refused, and the real genesis files") {
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for row <- rows do
      assertEquals(row.accepted, row.result.isDefined || row.changed.isDefined, row.name)
      assertEquals(row.accepted, row.refusal.isEmpty, row.name)
    val refused = rows.flatMap(_.refusal.map(_.field)).toSet
    assertEquals(ByronGenesisField.values.toSet -- refused, Set.empty[ByronGenesisField])
    assert(rows.count(r => r.file.isDefined && r.accepted) >= 7)
  }

  for row <- rows do
    test(row.name) {
      val input = bytes(row)
      assertEquals(
        ByronGenesisFile.read(input).toOption.map(_._2.hex),
        row.hash,
        "the genesis hash"
      )
      (row.accepted, ByronGenesisFile.parse(input, None)) match
        case (true, Right(genesis)) =>
          val expected = row.result.getOrElse(
            row.changed.fold(base)(_.toIterable.foldLeft(base)((o, kv) => o.add(kv._1, kv._2)))
          )
          assertEquals(project(genesis).asJson, expected.asJson)
        case (true, Left(e))   => fail(s"the reference takes this genesis: ${e.problem}")
        case (false, Right(_)) => fail(s"the reference refuses this genesis: ${row.failure}")
        case (false, Left(e)) =>
          assert(
            row.refusal.exists(matches(_, e.problem)),
            s"${e.problem} for ${row.failure}"
          )
    }
