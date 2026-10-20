// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.util.chaining.*
import xyz.kd5ujc.scarab.codec.{Cbor, Value}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.plutus.ScalusEvaluator
import xyz.kd5ujc.scarab.protocol.Era

/** Independent Haskell era-specific API evidence. API derivations are not signed admissions. */
class ContextMatrixSuite extends FunSuite:
  import ContextReferenceCases.{field, hex}
  private val rows = parse(
    Files.readString(Path.of("fixtures/reference/context-matrix/matrix.json"))
  ).fold(error => fail(error.message), identity).asArray.getOrElse(fail("not an array"))

  rows.foreach { row =>
    val name = field[String](row, "name")
    test(s"$name: pinned Haskell collection, complete argument bytes, order and outcomes") {
      val era = field[String](row, "era") match
        case "Alonzo"  => Era.Alonzo
        case "Babbage" => Era.Babbage
        case "Conway"  => Era.Conway
        case other     => fail(s"unsupported era: $other")
      val tx = Cbor
        .decode(hex(field[String](row, "transaction")))
        .fold(error => fail(error.toString), identity)
        .value match
        case Value.Arr(Vector(body, witnesses, flag, auxiliary)) =>
          val valid = flag.value match
            case Value.Bool(value) => value
            case _                 => fail("not a validity flag")
          Tx.fromBlock(
            Block.Transaction(
              body.original,
              witnesses.original,
              Option.unless(auxiliary.value == Value.Null)(auxiliary.original),
              valid
            ),
            era
          ).fold(error => fail(error.toString), identity)
        case _ => fail("not a complete transaction")
      val known = field[Vector[Json]](row, "utxo").map { entry =>
        val input = Cbor
          .decode(hex(field[String](entry, "input")))
          .fold(error => fail(error.toString), identity)
          .pipe(TxIn.decode)
          .fold(error => fail(error.toString), identity)
        input -> TxOut
          .fromBytes(hex(field[String](entry, "output")))
          .fold(error => fail(error.toString), identity)
      }.toMap
      val spent = known.filter((input, _) => tx.body.inputs.contains(input))
      val references = known.filter((input, _) => tx.body.referenceInputs.contains(input))
      val scripts = (spent.values ++ references.values)
        .flatMap(_.referenceScript)
        .map(script => script.hash -> script)
        .toMap ++ tx.witnesses.scriptsByHash
      val major = field[Int](row, "protocolMajor")
      val models = field[Map[String, Vector[String]]](row, "costModels")
        .map((language, values) => language.toInt -> values.map(BigInt(_)))
      val params = TestLedger.params(major).copy(costModels = models)
      val start = BigInt(field[Int](row, "systemStartSeconds")) * 1000
      val length = BigInt(field[Int](row, "slotLengthMilliseconds"))
      val actual =
        Phase2.collect(tx, params, scripts, spent, references, slot => Right(start + slot * length))
      val expected = field[Json](row, "oracle")
      assertEquals(actual.isRight, field[Boolean](expected, "collected"))
      actual match
        case Left(errors) =>
          val kinds = errors.toVector.map {
            case CollectError.NoRedeemer(_)     => "NoRedeemer"
            case CollectError.NoWitness(_)      => "NoWitness"
            case CollectError.NoCostModel(_)    => "NoCostModel"
            case CollectError.BadTranslation(_) => "BadTranslation"
          }
          assertEquals(kinds, field[Vector[String]](expected, "failureKinds"))
          if era == Era.Conway then
            val reference = CborOut.build(w =>
              w.array(2).uint(1).raw(hex(field[String](expected, "collectErrorsCbor")))
            )
            assertEquals(ContextFailureCbor.utxos(errors), Some(reference))
        case Right(requests) =>
          val expectedRequests = field[Vector[Json]](expected, "scripts")
          assertEquals(requests.size, expectedRequests.size)
          requests.zip(expectedRequests).foreach { case ((_, request), reference) =>
            assertEquals(
              PlutusFailureCbor.context(request),
              hex(field[String](reference, "contextCbor"))
            )
            val result = ScalusEvaluator.default.evaluate(request)
            assertEquals(result.isSuccess, field[Boolean](reference, "success"))
            if result.isSuccess then
              val used = field[Json](reference, "used")
              assertEquals(result.spent.mem, field[BigInt](used, "mem"))
              assertEquals(result.spent.steps, field[BigInt](used, "steps"))
            else assertEquals(field[Json](reference, "used"), Json.Null)
          }
    }
  }
