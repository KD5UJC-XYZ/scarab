// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.util.chaining.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{AvvmKeyError, ByronGenesisFile, ConfigProblem}

/** The balances of a Byron genesis as `readGenesisData` reads them
  * (`Cardano/Chain/Genesis/Data.hs`, cardano-ledger f649f975):
  * `fixtures/reference/byron-genesis-balances/cases.jsonl` holds a genesis object per case, its
  * `nonAvvmBalances` and `avvmDistr`, with what the unchanged released reader did with it and,
  * where it took it, the genesis UTxO `fromBalances` made of each field (the id, index, address and
  * amount of each output). The non-AVVM addresses cover each way `decodeAddressBase58` refuses one
  * and the encodings it takes that are not minimal (#728); the AVVM keys each form `fromAvvmVK`
  * takes and refuses, and the amounts each form `readEither` takes as a `Word64` and refuses
  * (#729).
  */
class ByronGenesisBalancesReferenceSuite extends FunSuite:
  private final case class Output(txid: String, index: Int, address: String, lovelace: String)
      derives Decoder
  private final case class Utxo(nonAvvm: Vector[Output], avvm: Vector[Output]) derives Decoder
  private final case class Row(
      name: String,
      text: String,
      accepted: Boolean,
      result: Option[Utxo],
      failure: Option[String]
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

  /** The kind of field the reference's schema error names, which Scarab's problem must match. */
  private def matches(failure: String, problem: ConfigProblem): Boolean =
    val length = """Address length is (\d+)""".r.findFirstMatchIn(failure).map(_.group(1).toInt)
    problem match
      case ConfigProblem.Base58Address(_) | ConfigProblem.ByronGenesisAddress(_, _) =>
        failure.contains("seExpected = \"Address\"")
      case ConfigProblem.IntegerText(_, _) | ConfigProblem.JsonField(_, _, _) =>
        failure.contains("seExpected = \"Word64\"")
      case ConfigProblem.AvvmKey(_, AvvmKeyError.Length(n)) => length.contains(n)
      case ConfigProblem.AvvmKey(_, AvvmKeyError.Format) =>
        failure.contains("is not base64(url) format")
      case _ => false

  test("the corpus covers addresses, AVVM keys and amounts, taken and refused") {
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for prefix <- List("address: ", "AVVM key: ", "non-AVVM amount ", "AVVM amount ") do
      val group = rows.filter(_.name.startsWith(prefix))
      assert(group.exists(_.accepted) && group.exists(!_.accepted), prefix)
    for row <- rows do assertEquals(row.accepted, row.result.isDefined, row.name)
  }

  for row <- rows do
    test(row.name) {
      val read = parser
        .parse(row.text)
        .fold(e => fail(e.toString), identity[Json])
        .pipe(ByronGenesisFile.genesisBalances)
      (row.result, read) match
        case (Some(utxo), Right((avvm, nonAvvm))) =>
          assertEquals(outputs(nonAvvm, avvm = false), utxo.nonAvvm.toSet, "non-AVVM outputs")
          assertEquals(outputs(avvm, avvm = true), utxo.avvm.toSet, "AVVM outputs")
        case (Some(_), Left(e)) => fail(s"the reference takes this genesis: ${e.problem}")
        case (None, Right(_))   => fail(s"the reference refuses this genesis: ${row.failure}")
        case (None, Left(e)) =>
          assert(matches(row.failure.getOrElse(""), e.problem), s"${e.problem} for ${row.failure}")
    }
