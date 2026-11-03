// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{ByteStrings, PredicateFailure, TxOut, UtxoFailure}
import xyz.kd5ujc.scarab.protocol.Era

/** A refusal's answer that holds an output writes it as the reference writes it, the output as
  * decoded, not its original bytes (#700): an output whose value was written as a `RecD` record
  * detached from its fields (`9f ff coin multiasset`) is written `[coin, multiasset]`, or the bare
  * coin when it holds no asset (`EncCBOR MaryValue`, `Mary/Value.hs:342-350`, cardano-ledger
  * f649f975), as every encoding of an output is.
  *
  * The expected output is the reference's own, `toCBOR` of a UTxO holding it, from the `detached`
  * cases of `fixtures/reference/utxo-answers/answers.jsonl` (the answer is `{input: output}`).
  */
class DetachedOutputFailureSuite extends FunSuite:
  private final case class Row(
      name: String,
      era: String,
      entries: Vector[(String, String)],
      answer: Option[String]
  )

  private given Decoder[Row] = Decoder.forProduct4("name", "era", "entries", "answer")(Row.apply)

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.message), identity)

  private val eras: Map[String, Era] =
    Map(
      "mary" -> Era.Mary,
      "alonzo" -> Era.Alonzo,
      "babbage" -> Era.Babbage,
      "conway" -> Era.Conway
    )

  private val rows: Vector[Row] =
    Files
      .readString(Path.of("fixtures/reference/utxo-answers/answers.jsonl"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(l => parser.decode[Row](l).fold(e => fail(e.getMessage), identity))
      .filter(r => r.name.contains("detached") && r.answer.isDefined)
      .toVector

  /** The output of a one-entry answer: after the map's head and the input. */
  private def written(row: Row): Bytes =
    val (input, _) = row.entries.headOption.getOrElse(fail(s"${row.name}: no entry"))
    bytes(row.answer.getOrElse("")).drop(1 + input.length / 2)

  private def output(row: Row): (Bytes, TxOut) =
    val (_, raw) = row.entries.headOption.getOrElse(fail(s"${row.name}: no entry"))
    val original = bytes(raw)
    (
      original,
      TxOut.fromBytes(original)(using ByteStrings.Chunked).fold(e => fail(e.message), identity)
    )

  test("the corpus has detached values from Mary to Conway") {
    assertEquals(rows.map(_.era).toSet, eras.keySet)
  }

  for row <- rows do
    test(s"${row.name}: a small output's refusal writes the output as the reference does") {
      val (original, out) = output(row)
      val expected = written(row)
      assertNotEquals(expected, original)
      val failure = PredicateFailure.utxo(UtxoFailure.OutputTooSmall(Vector(out)))
      val era = eras.getOrElse(row.era, fail(row.era))
      val answer =
        if era == Era.Conway then Some(PredicateFailureCbor.applyTxError(Vector(failure)))
        else PreConwayFailureCbor.applyTxError(era, Vector(failure))
      val hex = answer.map(_.hex).getOrElse(fail(s"${row.name}: no encoding"))
      assert(hex.contains(expected.hex), hex)
      assert(!hex.contains(original.hex), hex)
    }
