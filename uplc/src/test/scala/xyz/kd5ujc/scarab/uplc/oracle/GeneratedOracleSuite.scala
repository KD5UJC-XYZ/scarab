// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{PlutusData, PlutusLanguage, ProtocolMajor}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget

/** The generated programs of `fixtures/reference/uplc-generated`, judged by the pinned Haskell
  * evaluator (plutus 1.70.0.0, `scripts/oracle/plutus/generated`), replayed through this evaluator
  * as the ledger runs a script ([[LedgerEvaluation]]): the same admission and stage, the same class
  * and constructor of the error, the same budget on success and on failure, and the same logs. No
  * case is excluded; a script this evaluator gives no verdict on is a disagreement too.
  */
final class GeneratedOracleSuite extends munit.FunSuite:
  private val directory = Path.of("fixtures/reference/uplc-generated")

  private def json(path: Path): Json = parser.parse(Files.readString(path)).fold(throw _, identity)

  private def rows(name: String): Vector[Json] =
    Files
      .readString(directory.resolve(name))
      .linesIterator
      .map(parser.parse(_).fold(throw _, identity))
      .toVector

  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(s"$key: ${e.message}"), identity)

  private lazy val manifest = json(directory.resolve("manifest.json"))
  private lazy val corpus = json(directory.resolve("corpus.json"))
  private lazy val models = rows("models.jsonl")
  private lazy val requests = rows("requests.jsonl")
  private lazy val verdicts = rows("verdicts.jsonl")

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def languageOf(number: Int): PlutusLanguage =
    PlutusLanguage.values.find(_.id + 1 == number).getOrElse(fail(s"language $number"))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  test("the fixture's files are the ones its manifest binds, and every request has its verdict") {
    field[Map[String, String]](manifest, "sha256").foreach((name, hash) =>
      assertEquals(sha256(directory.resolve(name)), hash, name)
    )
    assertEquals(
      field[String](manifest, "plutusCommit"),
      "507cab8cf6192e5143f12be5b2d3005a2928dc6d"
    )
    assertEquals(requests.size, field[Int](manifest, "requests"))
    assertEquals(models.size, Profile.all.size)
    assertEquals(requests.size, verdicts.size)
    requests
      .zip(verdicts)
      .foreach((request, verdict) =>
        assertEquals(field[String](verdict, "id"), field[String](request, "id"))
      )
  }

  test("the generator gives every recorded script, argument and budget from its seed") {
    assertEquals(
      field[Int](corpus, "generatorVersion"),
      TermGen.Version,
      "the generator changed: write the fixture again (scripts/oracle/plutus/generated/README.md)"
    )
    val differ = requests.filterNot { request =>
      val profile = Profile.all(field[Int](request, "model"))
      val generated = TermGen.generate(profile, field[Long](request, "seed"))
      val row = GeneratedCorpus.request(field[Int](request, "model"), 0, generated)
      Vector("script", "arguments", "cpu", "memory", "language", "protocol").forall(key =>
        row.hcursor.downField(key).focus == request.hcursor.downField(key).focus
      )
    }
    assertEquals(differ.map(field[String](_, "id")).take(10), Vector.empty[String])
  }

  /** The machine of each cost-model list, built once. */
  private lazy val machines = models.map { model =>
    LedgerEvaluation.machine(
      languageOf(field[Int](model, "language")),
      ProtocolMajor(field[Int](model, "protocol")),
      field[Vector[Long]](model, "costModel")
    )
  }

  private final case class Replayed(id: String, expected: Json, actual: Either[NoVerdict, Verdict])

  private lazy val replayed: Vector[Replayed] = requests.zip(verdicts).map { (request, expected) =>
    val machine = machines(field[Int](request, "model"))
    val arguments = field[Vector[String]](request, "arguments")
      .map(a => PlutusData.decode(hex(a)).fold(e => fail(s"argument: $e"), identity))
    val budget = ExBudget.of(field[Long](request, "cpu"), field[Long](request, "memory"))
    Replayed(
      field[String](request, "id"),
      expected,
      machine.flatMap(
        LedgerEvaluation.evaluate(_, hex(field[String](request, "script")), arguments, budget)
      )
    )
  }

  private def describe(verdict: Verdict): String = verdict match
    case Verdict.Ran(outcome, spent, logs) =>
      s"${verdict.stage} ${verdict.errorClass.getOrElse("-")} ${verdict.constructor.getOrElse("-")} " +
        s"$outcome spent ${spent.cpu.toLong}/${spent.memory.toLong} logs ${logs.mkString("[", " | ", "]")}"
    case other => s"${other.stage} ${other.constructor.getOrElse("-")} $other"

  private def expectedText(row: Json): String =
    row.hcursor
      .downField("logs")
      .delete
      .focus
      .fold("")(_.noSpaces) + row.hcursor
      .downField("logs")
      .focus
      .fold("")(l => s" logs ${l.noSpaces}")

  test(
    "every generated script: the admission, stage, error and, on success, budget of the reference"
  ) {
    val disagreements = replayed.flatMap { case Replayed(id, expected, actual) =>
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
            (stage != "success" || spent == Some(
              (field[Long](expected, "cpu"), field[Long](expected, "memory"))
            ))
          Option.when(!agrees)(s"$id: ${describe(verdict)}; oracle ${expectedText(expected)}")
    }
    val stages = replayed.groupMapReduce(r => field[String](r.expected, "stage"))(_ => 1)(_ + _)
    println(
      s"generated-term oracle: ${replayed.size} scripts (${stages.toVector.sorted.mkString(", ")}), " +
        s"${disagreements.size} disagreements"
    )
    disagreements.take(40).foreach(println)
    assertEquals(disagreements.take(40), Vector.empty[String])
  }

  test("every generated script that ran: the budget spent when it failed, and the logs") {
    val disagreements = replayed.flatMap { case Replayed(id, expected, actual) =>
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
    println(s"generated-term oracle, spent budget and logs: ${disagreements.size} disagreements")
    disagreements.take(40).foreach(println)
    assertEquals(disagreements.take(40), Vector.empty[String])
  }

  test("negative control: with every cost parameter doubled, no successful run spends the same") {
    val doubled = models.map { model =>
      LedgerEvaluation.machine(
        languageOf(field[Int](model, "language")),
        ProtocolMajor(field[Int](model, "protocol")),
        field[Vector[Long]](model, "costModel").map(_ * 2)
      )
    }
    val successes = requests.zip(verdicts).filter((_, v) => field[String](v, "stage") == "success")
    val same = successes.filter { (request, expected) =>
      val arguments = field[Vector[String]](request, "arguments").map(a =>
        PlutusData.decode(hex(a)).fold(e => fail(s"argument: $e"), identity)
      )
      val budget = ExBudget.of(Long.MaxValue / 4, Long.MaxValue / 4)
      doubled(field[Int](request, "model"))
        .flatMap(
          LedgerEvaluation.evaluate(_, hex(field[String](request, "script")), arguments, budget)
        )
        .toOption
        .exists {
          case Verdict.Ran(_, spent, _) =>
            (spent.cpu.toLong, spent.memory.toLong) ==
              (field[Long](expected, "cpu"), field[Long](expected, "memory"))
          case _ => false
        }
    }
    assert(successes.size > requests.size / 4, s"only ${successes.size} successes")
    assertEquals(same.map((r, _) => field[String](r, "id")), Vector.empty[String])
  }

  test("the admitted programs name every builtin of every profile") {
    val named = requests.zip(verdicts).collect {
      case (request, verdict) if field[Boolean](verdict, "admitted") =>
        val profile = Profile.all(field[Int](request, "model"))
        val program = xyz.kd5ujc.scarab.uplc.flat.SerialisedScript
          .deserialiseScript(
            profile.language,
            profile.protocol,
            hex(field[String](request, "script"))
          )
          .fold(e => fail(s"${field[String](request, "id")}: $e"), identity)
        profile -> LedgerEvaluation.builtins(program.term)
    }
    val byProfile = named.groupMapReduce(_._1)(_._2)(_ ++ _)
    val missing =
      Profile.all.map(p => p.name -> p.builtins.filterNot(byProfile.getOrElse(p, Set.empty)))
    missing.foreach((name, absent) =>
      println(
        s"generated-term oracle, $name: ${absent.size} builtins named by no admitted program ${absent.mkString(", ")}"
      )
    )
    val everywhere = Profile.all.flatMap(_.builtins).toSet
    assertEquals(everywhere.filterNot(byProfile.values.flatten.toSet), Set.empty)
  }
