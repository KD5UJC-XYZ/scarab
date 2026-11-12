// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{PlutusData, PlutusLanguage, ProtocolMajor}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.ledger.Unavailability

/** The replay of a directory of generated requests and the pinned evaluator's verdicts on them
  * (`models.jsonl`, `requests.jsonl`, `verdicts.jsonl`, as `scripts/oracle/plutus/generated/run.py`
  * writes them) through the production path ([[Verdict.evaluate]]): the retained fixture's in
  * [[GeneratedOracleSuite]], a larger campaign's by hand (`GeneratedOracleReplayMain`).
  */
object GeneratedReplay:
  /** A row of a file that is not what the writer wrote. */
  final case class Malformed(what: String) extends Exception(what)

  def rows(path: Path): Vector[Json] =
    Files.readString(path).linesIterator.map(parser.parse(_).fold(throw _, identity)).toVector

  def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => throw Malformed(s"$key: ${e.message}"), identity)

  def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => throw Malformed(e.message), identity)

  private def languageOf(number: Int): PlutusLanguage =
    PlutusLanguage.values.find(_.id + 1 == number).getOrElse(throw Malformed(s"language $number"))

  final case class Replayed(
      id: String,
      request: Json,
      expected: Json,
      actual: Either[Unavailability, Verdict]
  )

  /** The context of each cost-model list, with every parameter multiplied by `scale`. */
  def contexts(models: Vector[Json], scale: Long = 1): Vector[ProfileContext] =
    models.map(model =>
      Verdict.context(
        languageOf(field[Int](model, "language")),
        ProtocolMajor(field[Int](model, "protocol")),
        field[Vector[Long]](model, "costModel").map(_ * scale)
      )
    )

  def evaluate(
      contexts: Vector[ProfileContext],
      request: Json,
      budget: ExBudget
  ): Either[Unavailability, Verdict] =
    val arguments = field[Vector[String]](request, "arguments")
      .map(a => PlutusData.decode(hex(a)).fold(e => throw Malformed(s"argument: $e"), identity))
    Verdict.evaluate(
      contexts(field[Int](request, "model")),
      hex(field[String](request, "script")),
      arguments,
      budget
    )

  def replay(
      models: Vector[Json],
      requests: Vector[Json],
      verdicts: Vector[Json]
  ): Vector[Replayed] =
    val built = contexts(models)
    requests.zip(verdicts).map { (request, expected) =>
      val budget = ExBudget.of(field[Long](request, "cpu"), field[Long](request, "memory"))
      Replayed(field[String](request, "id"), request, expected, evaluate(built, request, budget))
    }

  def describe(verdict: Verdict): String = verdict match
    case Verdict.Ran(outcome, spent, logs) =>
      s"${verdict.stage} ${verdict.errorClass.getOrElse("-")} ${verdict.constructor.getOrElse("-")} " +
        s"$outcome spent ${spent.cpu.toLong}/${spent.memory.toLong} logs ${logs.mkString("[", " | ", "]")}"
    case other => s"${other.stage} ${other.constructor.getOrElse("-")} $other"

  def expectedText(row: Json): String =
    row.hcursor.downField("logs").delete.focus.fold("")(_.noSpaces) +
      row.hcursor.downField("logs").focus.fold("")(l => s" logs ${l.noSpaces}")

  /** The cases whose admission, stage, error class or constructor, or budget on success differ from
    * the reference's, and those this evaluator gives no verdict on.
    */
  def required(replayed: Vector[Replayed]): Vector[String] =
    replayed.flatMap { case Replayed(id, _, expected, actual) =>
      actual match
        case Left(noVerdict) =>
          Some(s"$id: no verdict ($noVerdict); oracle ${expectedText(expected)}")
        case Right(verdict) =>
          val admitted = verdict match
            case Verdict.Decode(_) => false
            case _                 => true
          val spent = verdict match
            case Verdict.Ran(_, s, _) => Some((s.cpu.toLong, s.memory.toLong))
            case _                    => None
          val stage = field[String](expected, "stage")
          val agrees = admitted == field[Boolean](expected, "admitted") &&
            verdict.stage == stage &&
            verdict.errorClass == field[Option[String]](expected, "class") &&
            verdict.constructor == field[Option[String]](expected, "error") &&
            (stage != "success" ||
              spent == Some((field[Long](expected, "cpu"), field[Long](expected, "memory"))))
          Option.when(!agrees)(s"$id: ${describe(verdict)}; oracle ${expectedText(expected)}")
    }

  /** Among the cases that ran to the reference's stage, those whose budget spent (on failure too)
    * or logs differ.
    */
  def spentAndLogs(replayed: Vector[Replayed]): Vector[String] =
    replayed.flatMap { case Replayed(id, _, expected, actual) =>
      actual match
        case Right(verdict @ Verdict.Ran(_, spent, logs))
            if verdict.stage == field[String](expected, "stage") =>
          val budget =
            field[Option[Long]](expected, "cpu").zip(field[Option[Long]](expected, "memory"))
          val recordedLogs = field[Option[Vector[String]]](expected, "logs")
          val agrees = budget.forall(_ == (spent.cpu.toLong, spent.memory.toLong)) &&
            recordedLogs.forall(_ == logs)
          Option.when(!agrees)(s"$id: ${describe(verdict)}; oracle ${expectedText(expected)}")
        case _ => None
    }

  /** Replays the directory `args(0)` and prints every disagreement with its script. */
  def run(args: Array[String]): Unit =
    val directory = Path.of(args.head)
    val requests = rows(directory.resolve("requests.jsonl"))
    val replayed = replay(
      rows(directory.resolve("models.jsonl")),
      requests,
      rows(directory.resolve("verdicts.jsonl"))
    )
    val byId = requests.map(r => field[String](r, "id") -> r).toMap
    val stages = replayed.groupMapReduce(r => field[String](r.expected, "stage"))(_ => 1)(_ + _)
    val first = required(replayed)
    val second = spentAndLogs(replayed)
    println(
      s"${replayed.size} scripts (${stages.toVector.sorted.mkString(", ")}): ${first.size} " +
        s"disagreements in stage, error or success budget, ${second.size} in failure budget or logs"
    )
    (first ++ second).foreach { line =>
      val id = line.takeWhile(_ != ':')
      println(line)
      byId
        .get(id)
        .foreach(r =>
          println(
            s"  seed ${field[Long](r, "seed")} arguments ${field[Vector[String]](r, "arguments")}"
          )
          println(s"  script ${field[String](r, "script")}")
        )
    }
