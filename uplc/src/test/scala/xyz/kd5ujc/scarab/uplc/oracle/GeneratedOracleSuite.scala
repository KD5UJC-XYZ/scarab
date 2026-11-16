// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.cost.ExBudget

/** The generated programs of a retained fixture ([[CorpusSpec]]), judged by the pinned Haskell
  * evaluator of its plutus release (`scripts/oracle/plutus/generated` for 1.70.0.0,
  * `scripts/oracle/plutus/generated-171` for 1.71.0.0), replayed through this evaluator as the node
  * runs a script (`ledger.LedgerEval`, [[Verdict.evaluate]]): the same admission and stage, the
  * same class and constructor of the error, the same budget on success and on failure, and the same
  * logs. No case is excluded; a script this evaluator gives no verdict on is a disagreement too.
  */
abstract class GeneratedOracleGate(spec: CorpusSpec) extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  protected val directory: Path = Path.of(spec.directory)

  private def json(path: Path): Json = parser.parse(Files.readString(path)).fold(throw _, identity)

  protected def rows(name: String): Vector[Json] =
    Files
      .readString(directory.resolve(name))
      .linesIterator
      .map(parser.parse(_).fold(throw _, identity))
      .toVector

  protected def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(s"$key: ${e.message}"), identity)

  private lazy val manifest = json(directory.resolve("manifest.json"))
  private lazy val corpus = json(directory.resolve("corpus.json"))
  private lazy val models = rows("models.jsonl")
  protected lazy val requests: Vector[Json] = rows("requests.jsonl")
  protected lazy val verdicts: Vector[Json] = rows("verdicts.jsonl")

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  test(
    s"${spec.release}: the fixture's files are the ones its manifest binds, with every verdict"
  ) {
    field[Map[String, String]](manifest, "sha256").foreach((name, hash) =>
      assertEquals(sha256(directory.resolve(name)), hash, name)
    )
    assertEquals(field[String](manifest, "plutusCommit"), spec.plutusCommit)
    assertEquals(requests.size, field[Int](manifest, "requests"))
    val perProfile = field[Int](corpus, "modelsPerProfile")
    assertEquals(models.size, spec.profiles.size * perProfile)
    requests.foreach { request =>
      val model = models(field[Int](request, "model"))
      assertEquals(
        (field[Int](model, "language"), field[Int](model, "protocol")),
        (field[Int](request, "language"), field[Int](request, "protocol")),
        field[String](request, "id")
      )
    }
    assertEquals(requests.size, verdicts.size)
    requests
      .zip(verdicts)
      .foreach((request, verdict) =>
        assertEquals(field[String](verdict, "id"), field[String](request, "id"))
      )
  }

  private def profileOf(request: Json): Profile =
    spec
      .profileOf(field[Int](request, "language"), field[Int](request, "protocol"))
      .getOrElse(fail(s"no profile for ${field[String](request, "id")}"))

  test(s"${spec.release}: every cost-model list is its profile's default or one drawn from it") {
    val perProfile = field[Int](corpus, "modelsPerProfile")
    val expected = spec.profiles.zipWithIndex.flatMap((profile, p) =>
      GeneratedCorpus.models(spec, profile, p, field[Long](corpus, "seed"), perProfile)
    )
    assertEquals(models.map(field[Vector[Long]](_, "costModel")), expected)
  }

  test(s"${spec.release}: the generator gives every recorded script, argument and budget") {
    assertEquals(
      field[Int](corpus, "generatorVersion"),
      TermGen.Version,
      "the generator changed: write the fixture again (scripts/oracle/plutus/generated/README.md)"
    )
    val differ = requests.filterNot { request =>
      val profile = profileOf(request)
      val generated = TermGen.generate(profile, field[Long](request, "seed"))
      val row = GeneratedCorpus.request(field[Int](request, "model"), 0, generated)
      Vector("script", "arguments", "cpu", "memory", "language", "protocol").forall(key =>
        row.hcursor.downField(key).focus == request.hcursor.downField(key).focus
      )
    }
    assertEquals(differ.map(field[String](_, "id")).take(10), Vector.empty[String])
  }

  private lazy val replayed = GeneratedReplay.replay(models, requests, verdicts)

  test(
    s"${spec.release}: every generated script: the admission, stage, error and success budget"
  ) {
    val disagreements = GeneratedReplay.required(replayed)
    val stages = replayed.groupMapReduce(r => field[String](r.expected, "stage"))(_ => 1)(_ + _)
    println(
      s"generated-term oracle ${spec.release}: ${replayed.size} scripts " +
        s"(${stages.toVector.sorted.mkString(", ")}), " +
        s"${disagreements.size} disagreements"
    )
    disagreements.take(40).foreach(println)
    assertEquals(disagreements.take(40), Vector.empty[String])
  }

  test(s"${spec.release}: every generated script that ran: the failure budget and the logs") {
    val disagreements = GeneratedReplay.spentAndLogs(replayed)
    println(s"generated-term oracle, spent budget and logs: ${disagreements.size} disagreements")
    disagreements.take(40).foreach(println)
    assertEquals(disagreements.take(40), Vector.empty[String])
  }

  test(s"${spec.release}: negative control: every cost parameter doubled, no success the same") {
    val doubled = GeneratedReplay.contexts(models, scale = 2)
    val unlimited = ExBudget.of(Long.MaxValue / 4, Long.MaxValue / 4)
    val successes = requests.zip(verdicts).filter((_, v) => field[String](v, "stage") == "success")
    val same = successes.filter { (request, expected) =>
      GeneratedReplay.evaluate(doubled, request, unlimited).toOption.exists {
        case Verdict.Ran(_, spent, _) =>
          (spent.cpu.toLong, spent.memory.toLong) ==
            (field[Long](expected, "cpu"), field[Long](expected, "memory"))
        case _ => false
      }
    }
    assert(successes.size > requests.size / 4, s"only ${successes.size} successes")
    assertEquals(same.map((r, _) => field[String](r, "id")), Vector.empty[String])
  }

  test(s"${spec.release}: every builtin of every profile is named by an admitted program") {
    val named = requests.zip(verdicts).collect {
      case (request, verdict) if field[Boolean](verdict, "admitted") =>
        val profile = profileOf(request)
        val program = xyz.kd5ujc.scarab.uplc.flat.SerialisedScript
          .deserialiseScript(
            profile.language,
            profile.protocol,
            hex(field[String](request, "script"))
          )
          .fold(e => fail(s"${field[String](request, "id")}: $e"), identity)
        profile -> Verdict.builtins(program.term)
    }
    val byProfile = named.groupMapReduce(_._1)(_._2)(_ ++ _)
    val missing =
      spec.profiles.map(p => p.name -> p.builtins.filterNot(byProfile.getOrElse(p, Set.empty)))
    missing.foreach((name, absent) =>
      println(
        s"generated-term oracle, $name: ${absent.size} builtins named by no admitted program ${absent.mkString(", ")}"
      )
    )
    val everywhere = spec.profiles.flatMap(_.builtins).toSet
    assertEquals(everywhere.filterNot(byProfile.values.flatten.toSet), Set.empty)
  }

/** `fixtures/reference/uplc-generated`: protocols 5 to 11 under plutus 1.70.0.0. */
final class GeneratedOracleSuite extends GeneratedOracleGate(GeneratedCorpus.Plutus170)

/** `fixtures/reference/uplc-generated-171`: protocols 5 to 12 under plutus 1.71.0.0. */
final class GeneratedOracle171Suite extends GeneratedOracleGate(GeneratedCorpus.Plutus171):
  private val older = Path.of(GeneratedCorpus.Plutus170.directory)

  private def olderRows(name: String): Vector[Json] =
    Files
      .readString(older.resolve(name))
      .linesIterator
      .map(parser.parse(_).fold(throw _, identity))
      .toVector

  test(
    "protocols 5 to 11: the requests of the 1.70.0.0 fixture, and 1.71.0.0's verdicts are 1.70.0.0's"
  ) {
    val before = olderRows("requests.jsonl")
    val judged = olderRows("verdicts.jsonl")
    val lists = olderRows("models.jsonl")
    assertEquals(rows("models.jsonl").take(lists.size), lists, "the cost-model lists")
    val same = Vector("id", "seed", "language", "protocol", "script", "arguments", "cpu", "memory")
    val shared = requests.take(before.size)
    assertEquals(shared.size, before.size)
    val differ = shared.zip(before).filterNot { (now, earlier) =>
      same.forall(key => now.hcursor.downField(key).focus == earlier.hcursor.downField(key).focus)
    }
    assertEquals(differ.map((r, _) => field[String](r, "id")).take(10), Vector.empty[String])
    val disagree = verdicts.take(judged.size).zip(judged).filterNot(_ == _)
    println(
      s"generated-term oracle: ${judged.size} scripts of protocols 5 to 11, " +
        s"${disagree.size} verdicts of plutus 1.71.0.0 unlike 1.70.0.0's"
    )
    assertEquals(disagree.map((v, _) => field[String](v, "id")).take(10), Vector.empty[String])
  }

  test("protocol 12 is in the fixture: every language, program version 1.2.0 and case on Data") {
    val dijkstra = requests.zip(verdicts).filter((r, _) => field[Int](r, "protocol") == 12)
    assertEquals(
      dijkstra.map((r, _) => field[Int](r, "language")).distinct.sorted,
      Vector(1, 2, 3, 4)
    )
    val admitted = dijkstra.filter((_, v) => field[Boolean](v, "admitted"))
    assert(admitted.size > dijkstra.size / 2, s"only ${admitted.size} of ${dijkstra.size} admitted")
    val succeeded = dijkstra.filter((_, v) => field[String](v, "stage") == "success")
    assert(succeeded.size > dijkstra.size / 4, s"only ${succeeded.size} succeed")
  }
