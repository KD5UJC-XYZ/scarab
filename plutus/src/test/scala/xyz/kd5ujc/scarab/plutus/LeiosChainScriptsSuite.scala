// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.uplc.oracle.GeneratedReplay
import xyz.kd5ujc.scarab.uplc.oracle.GeneratedReplay.{field, hex}

/** The Plutus scripts of transactions recorded on the public Leios testnet's chain, at protocol 12,
  * judged by the pinned plutus 1.71.0.0 evaluator (`scripts/oracle/plutus/generated-171`, the
  * unchanged `deserialiseScript` and `evaluateScriptRestricting`) with the arguments the testnet's
  * build collected for them (`fixtures/reference/leios-testnet-chain-contexts`), the chain's cost
  * model of their language and the redeemer's execution units as the budget.
  *
  * Each is replayed through the own evaluator as the generated-program oracle's scripts are
  * ([[GeneratedReplay]], `LedgerEval.evaluateSerialised`): the same admission and stage, the same
  * class and constructor of the error, the same budget spent and the same logs; and through
  * [[ScarabEvaluator]], the node's interpreter of that path, which must succeed or fail with the
  * reference and spend the same budget with the same logs. No case is excluded.
  */
class LeiosChainScriptsSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val directory = Path.of("fixtures/reference/leios-testnet-chain-scripts")

  private def rows(name: String): Vector[Json] = GeneratedReplay.rows(directory.resolve(name))

  private lazy val manifest =
    io.circe.parser
      .parse(Files.readString(directory.resolve("manifest.json")))
      .fold(throw _, identity)
  private lazy val models = rows("models.jsonl")
  private lazy val requests = rows("requests.jsonl")
  private lazy val verdicts = rows("verdicts.jsonl")

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  test("the fixture's files are the ones its manifest binds, with a verdict for every script") {
    field[Map[String, String]](manifest, "sha256").foreach((name, hash) =>
      assertEquals(sha256(directory.resolve(name)), hash, name)
    )
    assertEquals(requests.size, field[Int](manifest, "requests"))
    assert(requests.size >= 200, s"only ${requests.size} scripts")
    assertEquals(verdicts.map(field[String](_, "id")), requests.map(field[String](_, "id")))
    assertEquals(requests.map(field[Int](_, "language")).distinct.sorted, Vector(1, 2, 3, 4))
    val purposes =
      requests.groupMapReduce(r => (field[Int](r, "language"), field[String](r, "purpose")))(_ =>
        1
      )(_ + _)
    println(
      s"Leios testnet chain scripts by language and purpose: ${purposes.toVector.sorted.mkString(", ")}"
    )
    requests.foreach { request =>
      val model = models(field[Int](request, "model"))
      assertEquals(field[Int](request, "protocol"), 12, field[String](request, "id"))
      assertEquals(
        (field[Int](model, "language"), field[Int](model, "protocol")),
        (field[Int](request, "language"), field[Int](request, "protocol")),
        field[String](request, "id")
      )
    }
  }

  private lazy val replayed = GeneratedReplay.replay(models, requests, verdicts)

  test("every script: the admission, stage, error and success budget are the reference's") {
    val disagreements = GeneratedReplay.required(replayed)
    val stages = replayed.groupMapReduce(r => field[String](r.expected, "stage"))(_ => 1)(_ + _)
    println(
      s"Leios testnet chain scripts: ${replayed.size} " +
        s"(${stages.toVector.sorted.mkString(", ")}), ${disagreements.size} disagreements"
    )
    disagreements.take(40).foreach(println)
    assertEquals(disagreements.take(40), Vector.empty[String])
  }

  test("every script that ran: the budget spent and the logs are the reference's") {
    val disagreements = GeneratedReplay.spentAndLogs(replayed)
    disagreements.take(40).foreach(println)
    assertEquals(disagreements.take(40), Vector.empty[String])
  }

  test(
    "every script through ScarabEvaluator: the outcome, the budget spent and the logs are the reference's"
  ) {
    val evaluator = ScarabEvaluator.pure
    val differ = requests.zip(verdicts).flatMap { (request, expected) =>
      val language = PlutusLanguage.values
        .find(_.id + 1 == field[Int](request, "language"))
        .getOrElse(fail(s"language of ${field[String](request, "id")}"))
      val result = evaluator.evaluate(
        EvalRequest(
          language,
          field[Int](request, "protocol"),
          hex(field[String](request, "script")),
          field[Vector[String]](request, "arguments")
            .map(a => PlutusData.decode(hex(a)).fold(e => fail(e.toString), identity)),
          field[Vector[Long]](models(field[Int](request, "model")), "costModel"),
          ExUnits(field[BigInt](request, "memory"), field[BigInt](request, "cpu"))
        )
      )
      val spent = field[Option[Long]](expected, "cpu").zip(field[Option[Long]](expected, "memory"))
      val logs = result match
        case EvalResult.Success(_, l)    => l
        case EvalResult.Failure(_, _, l) => l
      val agrees = result.isSuccess == (field[String](expected, "stage") == "success") &&
        spent.forall((cpu, memory) => result.spent == ExUnits(memory, cpu)) &&
        field[Option[Vector[String]]](expected, "logs").forall(_ == logs)
      Option.when(!agrees)(s"${field[String](request, "id")}: $result; oracle ${expected.noSpaces}")
    }
    differ.take(40).foreach(println)
    assertEquals(differ.take(40), Vector.empty[String])
  }

  test("each transaction's recorded validity flag is the reference's verdict on its scripts") {
    val byTransaction = requests.zip(verdicts).groupBy((r, _) => field[String](r, "tx"))
    assert(byTransaction.size >= 100, s"only ${byTransaction.size} transactions")
    val differ = byTransaction.toVector.sortBy(_._1).flatMap { (tx, scripts) =>
      val valid = scripts.map((r, _) => field[Boolean](r, "valid")).distinct
      val passes = scripts.forall((_, v) => field[String](v, "stage") == "success")
      Option.when(valid != Vector(passes))(
        s"$tx: recorded $valid, the reference's scripts pass: $passes"
      )
    }
    assertEquals(differ, Vector.empty[String])
  }
