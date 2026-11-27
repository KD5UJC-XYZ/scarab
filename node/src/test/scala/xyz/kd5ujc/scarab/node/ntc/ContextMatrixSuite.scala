// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.ledger.testByteStrings
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.util.chaining.*
import xyz.kd5ujc.scarab.codec.{Cbor, Value}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTestKit, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxosPredFailure
import xyz.kd5ujc.scarab.ledger.state.{Conway, ShelleyBasedEra}
import xyz.kd5ujc.scarab.plutus.ScarabEvaluator
import xyz.kd5ujc.scarab.protocol.Era

/** Independent Haskell era-specific API evidence. API derivations are not signed admissions. A
  * refused collection's bytes are written by the era's encoder: Alonzo's and Babbage's by
  * [[ContextFailureCbor]] from the flat collection, Conway's by [[ConwayFailureCbor]] from Conway's
  * era collection (`collectPlutusScriptsWithContext`, `Alonzo/Plutus/Evaluate.hs:83-118`,
  * cardano-ledger f649f975), on the same outputs, parameters and clock.
  */
class ContextMatrixSuite extends FunSuite:
  import ContextReferenceCases.{field, hex}
  private val rows = parse(
    Files.readString(Path.of("fixtures/reference/context-matrix/matrix.json"))
  ).fold(error => fail(error.message), identity).asArray.getOrElse(fail("not an array"))

  test("the matrix's rows by era and outcome: every refused one reaches the byte comparison") {
    val counts = rows
      .groupBy(row =>
        (field[String](row, "era"), field[Boolean](field[Json](row, "oracle"), "collected"))
      )
      .view
      .mapValues(_.size)
      .toMap
    assertEquals(
      counts,
      Map(
        ("Alonzo", true) -> 20,
        ("Babbage", true) -> 34,
        ("Babbage", false) -> 20,
        ("Conway", true) -> 120,
        ("Conway", false) -> 115
      )
    )
  }

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
      val witness: ShelleyBasedEra[?] = era match
        case Era.Alonzo  => ShelleyBasedEra.SAlonzo
        case Era.Babbage => ShelleyBasedEra.SBabbage
        case Era.Conway  => ShelleyBasedEra.SConway
        case other       => fail(s"no script context era for $other")
      val actual =
        Phase2.collect(
          tx,
          params,
          scripts,
          spent,
          references,
          slot => Right(start + slot * length)
        )(using witness)
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
          // every refused collection of the matrix retains the reference's CollectError list
          val reference = CborOut.build(w =>
            w.array(2).uint(1).raw(hex(field[String](expected, "collectErrorsCbor")))
          )
          val encoded = era match
            case Era.Alonzo =>
              ContextFailureCbor.utxos(ContextFailureCbor.ContextEra.Alonzo, errors)
            case Era.Babbage =>
              ContextFailureCbor.utxos(ContextFailureCbor.ContextEra.Babbage, errors)
            case Era.Conway =>
              // Conway's era collection on the row's outputs, parameters and clock refuses too,
              // and its era encoder writes the reference's bytes
              val pp = xyz.kd5ujc.scarab.ledger.era.PParams[Conway](
                ConwayPParams.fromStored(params).fold(e => fail(s"$name: $e"), identity)
              )
              val utxo = ConwayUTxO.fromStored(known).fold(e => fail(s"$name: $e"), identity)
              xyz.kd5ujc.scarab.ledger.era.AlonzoPlutusEvaluate
                .collectPlutusScriptsWithContext[Conway](
                  slot => Right(start + slot * length),
                  pp,
                  ConwayTestKit.typed(tx),
                  utxo
                )
                .swap
                .fold(_ => fail(s"$name: Conway's era collection succeeded"), identity)
                .pipe(eraErrors =>
                  ConwayFailureCbor.utxos(ConwayUtxosPredFailure.CollectErrors[Conway](eraErrors))
                )
                .toOption
            case other => fail(s"no context era for $other")
          assertEquals(encoded, Some(reference))
        case Right(requests) =>
          val expectedRequests = field[Vector[Json]](expected, "scripts")
          assertEquals(requests.size, expectedRequests.size)
          requests.zip(expectedRequests).foreach { case ((_, request), reference) =>
            assertEquals(
              PlutusFailureCbor.context(request),
              hex(field[String](reference, "contextCbor"))
            )
            val result = ScarabEvaluator.default.evaluate(request)
            assertEquals(result.isSuccess, field[Boolean](reference, "success"))
            if result.isSuccess then
              val used = field[Json](reference, "used")
              assertEquals(result.spent.mem, field[BigInt](used, "mem"))
              assertEquals(result.spent.steps, field[BigInt](used, "steps"))
            else assertEquals(field[Json](reference, "used"), Json.Null)
          }
    }
  }
