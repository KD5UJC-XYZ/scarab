// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import cats.syntax.all.*
import io.circe.Decoder
import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  AvvmKeyError,
  Block,
  ByronFieldError,
  ByronGenesisFile,
  ConfigProblem
}
import xyz.kd5ujc.scarab.ledger.json.CanonicalJson.Kind

/** The balances of a Byron genesis as `readGenesisData` reads them
  * (`Cardano/Chain/Genesis/Data.hs`, cardano-ledger f649f975):
  * `fixtures/reference/byron-genesis-balances/cases.jsonl` holds a genesis object per case, its
  * `nonAvvmBalances` and `avvmDistr`, with what the unchanged released reader did with it and,
  * where it took it, the genesis UTxO `fromBalances` made of each field (the id, index, address and
  * amount of each output). The non-AVVM addresses cover each way `decodeAddressBase58` refuses one
  * and the encodings it takes that are not minimal (#728); the AVVM keys each form `fromAvvmVK`
  * takes and refuses, and the amounts each form `readEither` takes as a `Word64` and refuses
  * (#729). An amount outside `0 .. 2^64 - 1`, which the reference takes modulo 2^64, is refused
  * (overflow-improvements.md#byron-genesis-words): there the reference's lovelace is the amount
  * modulo 2^64, and Scarab's refusal names the amount.
  */
class ByronGenesisBalancesReferenceSuite extends FunSuite:
  private final case class Output(txid: String, index: Int, address: String, lovelace: String)
      derives Decoder
  private final case class Utxo(nonAvvm: Vector[Output], avvm: Vector[Output]) derives Decoder

  /** The field a refusal names: the reader's `seExpected`, with a redeem key's wrong length. */
  private enum Refusal derives Decoder:
    case Address, Amount, AvvmKeyFormat, Syntax, MissingField, NotAnObject
    case AvvmKeyLength(length: Int)

  private final case class Row(
      name: String,
      text: Option[String],
      hex: Option[String],
      hash: Option[String],
      accepted: Boolean,
      result: Option[Utxo],
      failure: Option[String],
      refusal: Option[Refusal]
  ) derives Decoder

  private val rows: Vector[Row] =
    Files
      .readAllLines(Path.of("fixtures", "reference", "byron-genesis-balances", "cases.jsonl"))
      .toArray(Array.empty[String])
      .toVector
      .traverse(parser.decode[Row](_))
      .fold(e => fail(e.toString), identity)

  /** The genesis UTxO Scarab builds from balances, as the harness reports one. */
  private def outputs(balances: Vector[(Bytes, BigInt)], avvm: Boolean): Set[Output] =
    val utxo =
      if avvm then ByronLedger.genesisUtxo(balances, Vector.empty, None)
      else ByronLedger.genesisUtxo(Vector.empty, balances, None)
    utxo
      .fold(e => fail(e.toString), identity)
      .map((in, out) => Output(in.id.hex, in.index.toInt, out.address.hex, out.value.toString))
      .toSet

  /** Scarab's problem for the field the reference's refusal names. */
  private def matches(refusal: Refusal, problem: ConfigProblem): Boolean = (refusal, problem) match
    case (Refusal.Address, ConfigProblem.Base58Address(_))                                   => true
    case (Refusal.Address, ConfigProblem.ByronGenesisAddress(_, _))                          => true
    case (Refusal.Amount, ConfigProblem.ByronGenesisValue(_, ByronFieldError.NumberText(_))) => true
    case (Refusal.Amount, ConfigProblem.ByronGenesisValue(_, ByronFieldError.OutOfRange(_, 64))) =>
      true
    case (
          Refusal.Amount,
          ConfigProblem.ByronGenesisValue(_, ByronFieldError.Kind(Kind.String, _))
        ) =>
      true
    case (Refusal.AvvmKeyFormat, ConfigProblem.AvvmKey(_, AvvmKeyError.Format))       => true
    case (Refusal.AvvmKeyLength(n), ConfigProblem.AvvmKey(_, AvvmKeyError.Length(m))) => n == m
    case (Refusal.Syntax, ConfigProblem.CanonicalJsonSyntax(_, _))                    => true
    case (Refusal.MissingField, ConfigProblem.ByronGenesisValue(_, ByronFieldError.Missing)) => true
    case (
          Refusal.NotAnObject,
          ConfigProblem.ByronGenesisValue(_, ByronFieldError.Kind(Kind.Object, _))
        ) =>
      true
    case _ => false

  test("the corpus covers addresses, AVVM keys and amounts, taken and refused") {
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for prefix <- List("address: ", "AVVM key: ", "non-AVVM amount ", "AVVM amount ") do
      val group = rows.filter(_.name.startsWith(prefix))
      assert(group.exists(_.accepted) && group.exists(!_.accepted), prefix)
    for row <- rows do
      assertEquals(row.accepted, row.result.isDefined, row.name)
      assertEquals(row.accepted, row.refusal.isEmpty, row.name)
  }

  for row <- rows do
    test(row.name) {
      val bytes = row.hex
        .map(h => Bytes.fromHex(h).fold(e => fail(e.message), identity))
        .orElse(
          row.text.map(t => Bytes.fromArray(t.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        )
        .getOrElse(fail("a case has its text or its bytes"))
      val file = ByronGenesisFile.read(bytes)
      assertEquals(file.toOption.map(_._2.hex), row.hash, "the genesis hash, or no parse")
      val read = file.flatMap((value, _) => ByronGenesisFile.genesisBalances(value))
      (row.result, read) match
        case (Some(utxo), Right((avvm, nonAvvm))) =>
          assertEquals(outputs(nonAvvm, avvm = false), utxo.nonAvvm.toSet, "non-AVVM outputs")
          assertEquals(outputs(avvm, avvm = true), utxo.avvm.toSet, "AVVM outputs")
        case (Some(utxo), Left(e)) =>
          e.problem match
            case ConfigProblem.ByronGenesisValue(_, ByronFieldError.OutOfRange(amount, 64)) =>
              assert(amount.signum < 0 || amount.bitLength > 64, amount.toString)
              val wrapped = amount.mod(BigInt(1) << 64).toString
              assert(
                (utxo.nonAvvm ++ utxo.avvm).exists(_.lovelace == wrapped),
                s"the reference's lovelace is $amount modulo 2^64"
              )
            case other => fail(s"the reference takes this genesis: $other")
        case (None, Right(_)) => fail(s"the reference refuses this genesis: ${row.failure}")
        case (None, Left(e)) =>
          assert(row.refusal.exists(matches(_, e.problem)), s"${e.problem} for ${row.failure}")
    }

  test(
    "a genesis address longer than a block's limits is read, as the reference bounds it by nothing"
  ) {
    val attribute = ByronCbor.bytes(Bytes.fromArray(Array.fill(1 << 23)(0.toByte)))
    val attributes = ByronCbor.concat(ByronCbor.mapHead(1), ByronCbor.uint(5), attribute)
    val body = ByronCbor.concat(
      ByronCbor.of(0x83),
      ByronCbor.bytes(Bytes.fromArray(Array.tabulate(28)(_.toByte))),
      attributes,
      ByronCbor.uint(0)
    )
    val raw = ByronCbor.concat(
      ByronCbor.of(0x82, 0xd8, 0x18),
      ByronCbor.bytes(body),
      ByronCbor.uint(ByronCbor.crc32(body))
    )
    assert(raw.size > Block.MaxInputBytes)
    assertEquals(ByronAddress.reencoded(raw), Right(raw))
  }
