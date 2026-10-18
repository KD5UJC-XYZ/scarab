// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.Assertions
import xyz.kd5ujc.scarab.codec.{Bytes, Value as CborValue}
import xyz.kd5ujc.scarab.protocol.Era

/** Unchanged Haskell collection API results, separate from the complete-submission evidence. */
object ContextReferenceCases extends Assertions:
  def field[A: Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).fold(error => fail(error.message), identity)
  def hex(value: String): Bytes = Bytes.fromHex(value).fold(e => fail(e.toString), identity)

  final case class Recorded(json: Json):
    val name = field[String](json, "name")
    val original = hex(field[String](json, "transaction"))
    val tx = CborRead
      .decode(original)
      .fold(e => fail(e.toString), identity)
      .value match
      case CborValue.Arr(Vector(body, witnesses, flag, auxiliary)) =>
        val valid = flag.value match
          case CborValue.Bool(value) => value
          case _                     => fail("not a validity flag")
        val aux = Option.unless(auxiliary.value == CborValue.Null)(auxiliary.original)
        Tx.fromBlock(Block.Transaction(body.original, witnesses.original, aux, valid), Era.Conway)
          .fold(e => fail(e.toString), identity)
      case _ => fail("not a complete transaction")
    val known = field[Vector[Json]](json, "utxo").map { entry =>
      val input = CborRead
        .decode(hex(field[String](entry, "input")))
        .flatMap(TxIn.decode)
        .fold(e => fail(e.toString), identity)
      input -> TxOut
        .fromBytes(hex(field[String](entry, "output")))
        .fold(e => fail(e.toString), identity)
    }.toMap
    val spent = known.filter { case (in, _) => tx.body.inputs.contains(in) }
    val references = known.filter { case (in, _) => tx.body.referenceInputs.contains(in) }
    val scripts = (spent.values ++ references.values)
      .flatMap(_.referenceScript)
      .map(s => s.hash -> s)
      .toMap ++ tx.witnesses.scriptsByHash
    val models = field[Map[String, Vector[String]]](json, "costModels")
      .map { case (language, values) => language.toInt -> values.map(BigInt(_)) }
    val params = TestLedger.params(field[Int](json, "protocolMajor")).copy(costModels = models)
    val oracle = field[Json](json, "oracle")
    val collected = field[Boolean](oracle, "collected")
    def collect: Phase2.Collected[Vector[(Redeemer, xyz.kd5ujc.scarab.plutus.EvalRequest)]] =
      Phase2.collect(tx, params, scripts, spent, references, slot => Right(slot * 100))

  val all: Vector[Recorded] =
    parse(Files.readString(Path.of("fixtures/reference/context-errors/collection.json")))
      .fold(error => fail(error.message), identity)
      .asArray
      .getOrElse(fail("not a corpus array"))
      .map(Recorded(_))
