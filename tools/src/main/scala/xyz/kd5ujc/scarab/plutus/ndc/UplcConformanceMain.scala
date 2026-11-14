// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus.ndc

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.{Decoder, Json, parser}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, PlutusVm}
import xyz.kd5ujc.scarab.uplc.{Program, ProgramVersion}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.text.{ProgramError, UplcParser, UplcPrinter}

/** The `uplc-conformance` scenario of pragma-org's node-diversity-conformance site: every vector of
  * its corpus evaluated by one of the node's Plutus evaluators, written as the scenario's report
  * (`scenarios/uplc-conformance/README.md`, `CHANGELOG.md` and `schema.json` at the site's commit
  * 9dc68a75).
  *
  * `tools/runMain xyz.kd5ujc.scarab.plutus.ndc.UplcConformanceMain <scalus|scarab> <corpus-dir>
  * <expectations.json> <report.json>`
  *
  * The scenarios come from the site's `expectations.json`, of which only the names and the versions
  * of each scenario are read (`id`, `title`, `group`, `plutus_version`, `protocol_version`), never
  * its expected outcome; nor are the corpus's `.expected` and `.budget` files read. Each vector is
  * found under `protocol-version-<major>/v<n>/<group>/`, the directory whose name gives the
  * scenario's id. The folders give its language and protocol, and the corpus's
  * `cost-models/protocol-version-<major>/v<n>.json` its cost-model list, which goes unchanged to
  * the backend's evaluation context as the ledger's would.
  *
  * A vector with a `.flat` file (the hex of a Flat program) is admitted as the ledger admits a
  * script: the program in a CBOR byte string through the backend's `deserialiseScript`. One with
  * only a `.uplc` file is read by the `uplc` module's text parser (the reference's grammar), for
  * either backend; a free variable is then refused as the ledger's scope check refuses it, a
  * failure of the evaluation. Then the program's version and scope are checked as
  * `mkTermToEvaluate` checks them, and the machine of the protocol runs within 10,000,000,000 CPU
  * and 14,000,000 memory units, restricting, as the README sets. The result is written in the
  * textual syntax with the names the reference gives a de Bruijn term
  * ([[UplcPrinter.Naming.Fresh]]), and the budget spent with it; a refusal or failure is one of the
  * README's three strings with no budget. A backend that gives no verdict writes `no verdict` and
  * why, under `error`: never a pass.
  */
object UplcConformanceMain extends IOApp:

  /** The budget of the README. */
  val Limit: ExBudget = ExBudget.of(10_000_000_000L, 14_000_000L)

  /** A scenario of `expectations.json`: its names and versions, nothing of its expected outcome. */
  final case class Scenario(
      id: String,
      title: String,
      group: String,
      plutusVersion: String,
      protocolVersion: String
  )

  object Scenario:
    given Decoder[Scenario] =
      Decoder.forProduct5("id", "title", "group", "plutus_version", "protocol_version")(
        Scenario.apply
      )

  /** Why the corpus or the scenarios cannot be read. */
  enum CorpusError:
    case Unreadable(path: Path, cause: Throwable)
    case Malformed(path: Path, detail: String)
    case UnknownLanguage(scenario: String, text: String)
    case UnknownProtocol(scenario: String, text: String)
    case NoVector(scenario: String, directory: Path)
    case Ambiguous(scenario: String, names: Vector[String])

    def message: String = this match
      case Unreadable(path, cause)  => s"cannot read $path: $cause"
      case Malformed(path, detail)  => s"$path: $detail"
      case UnknownLanguage(s, text) => s"$s: no Plutus language $text"
      case UnknownProtocol(s, text) => s"$s: no protocol version $text"
      case NoVector(s, directory)   => s"$s: no vector under $directory"
      case Ambiguous(s, names)      => s"$s: several vectors match: ${names.mkString(", ")}"

  /** A vector's program: the bytes of a script, or the text of a program. */
  enum Input:
    case Script(bytes: Bytes)
    case Text(text: String)

  /** A scenario with its context and its program, ready for a backend. */
  final case class Located(
      scenario: Scenario,
      language: PlutusLanguage,
      protocol: Int,
      input: Input
  )

  /** The scenario's id from a vector's directory: its group and name, lower case, every run of
    * other characters than letters and digits one `_`, then the language (`builtin/constant/array`
    * and `emptyArray` at V3: `builtin_constant_array_emptyarray_v3`).
    */
  def scenarioId(group: String, name: String, language: PlutusLanguage): String =
    val slug =
      s"$group/$name".toLowerCase.replaceAll("[^a-z0-9]+", "_").stripPrefix("_").stripSuffix("_")
    s"${slug}_v${language.id + 1}"

  private def read(path: Path): Either[CorpusError, String] =
    Either
      .catchNonFatal(Files.readString(path, StandardCharsets.UTF_8))
      .leftMap(CorpusError.Unreadable(path, _))

  private def json(path: Path): Either[CorpusError, Json] =
    read(path).flatMap(parser.parse(_).leftMap(e => CorpusError.Malformed(path, e.message)))

  def scenarios(path: Path): Either[CorpusError, Vector[Scenario]] =
    json(path).flatMap(
      _.hcursor
        .downField("scenarios")
        .as[Vector[Scenario]]
        .leftMap(e => CorpusError.Malformed(path, e.message))
    )

  def costModel(
      corpus: Path,
      language: PlutusLanguage,
      protocol: Int
  ): Either[CorpusError, Vector[Long]] =
    val path = corpus.resolve(s"cost-models/protocol-version-$protocol/v${language.id + 1}.json")
    json(path).flatMap(_.as[Vector[Long]].leftMap(e => CorpusError.Malformed(path, e.message)))

  /** The vector of a scenario: its directory found by the scenario's id, its `.flat` if it has one,
    * else its `.uplc`.
    */
  def locate(corpus: Path, scenario: Scenario): Either[CorpusError, Located] =
    for
      language <- NdcReport
        .language(scenario.plutusVersion)
        .toRight(CorpusError.UnknownLanguage(scenario.id, scenario.plutusVersion))
      protocol <- NdcReport
        .protocol(scenario.protocolVersion)
        .toRight(CorpusError.UnknownProtocol(scenario.id, scenario.protocolVersion))
      directory = corpus.resolve(
        s"protocol-version-$protocol/v${language.id + 1}/${scenario.group}"
      )
      names <- Either
        .catchNonFatal(Files.list(directory).iterator.asScala.toVector)
        .leftMap(CorpusError.Unreadable(directory, _))
        .map(_.filter(Files.isDirectory(_)).map(_.getFileName.toString))
      matching = names.filter(scenarioId(scenario.group, _, language) == scenario.id).sorted
      name <- matching match
        case Vector(one) => Right(one)
        case Vector()    => Left(CorpusError.NoVector(scenario.id, directory))
        case several     => Left(CorpusError.Ambiguous(scenario.id, several))
      flat = directory.resolve(s"$name/$name.flat")
      input <-
        if Files.exists(flat) then
          read(flat).flatMap(hex =>
            Bytes
              .fromHex(hex.trim)
              .leftMap(e => CorpusError.Malformed(flat, e.toString))
              .flatMap(script =>
                Cbor
                  .encode(CborValue.ByteString(script))
                  .leftMap(e => CorpusError.Malformed(flat, e.toString))
              )
              .map(Input.Script(_))
          )
        else read(directory.resolve(s"$name/$name.uplc")).map(Input.Text(_))
    yield Located(scenario, language, protocol, input)

  /** One vector on `backend` in `context`: the version of its program once it is read, and the run
    * or why it did not get to one.
    */
  def evaluate(backend: UplcBackend)(
      context: backend.Context,
      input: Input
  ): (Option[ProgramVersion], Run) =
    val prepared = input match
      case Input.Script(bytes) => backend.script(context, bytes)
      case Input.Text(text) =>
        UplcParser.program(text) match
          case Left(ProgramError.Unparsable(e)) => Left(Run.Failed(Verdict.ParseDecode, e.toString))
          case Left(ProgramError.Open(free)) =>
            Left(Run.Failed(Verdict.EvaluationFailure, free.toString))
          case Right(program) => backend.text(context, program)
    prepared.fold(run => (None, run), p => (Some(p.version), p.evaluate(Limit)))

  /** The report's item of a scenario. */
  def item(scenario: Scenario, version: Option[ProgramVersion], run: Run): Json =
    val (evaluation, budget, error) = run match
      case Run.Evaluated(spent, result) =>
        (version, result()) match
          case (Some(v), Right(term)) =>
            (
              UplcPrinter.program(Program(v, term), UplcPrinter.Naming.Fresh),
              NdcReport.budget(spent),
              None
            )
          case (_, Left(cause)) => ("no verdict", Json.Null, Some(cause.message))
          case (None, Right(_)) => ("no verdict", Json.Null, Some("no program version"))
      case Run.Failed(verdict, _) => (verdict.text, Json.Null, None)
      case Run.NoVerdict(cause)   => ("no verdict", Json.Null, Some(cause.message))
    Json.fromFields(
      Vector(
        "id" -> Json.fromString(scenario.id),
        "title" -> Json.fromString(scenario.title),
        "group" -> Json.fromString(scenario.group),
        "plutus_version" -> Json.fromString(scenario.plutusVersion),
        "protocol_version" -> Json.fromString(scenario.protocolVersion),
        "evaluation" -> Json.fromString(evaluation),
        "budget" -> budget
      ) ++ error.map("error" -> Json.fromString(_))
    )

  /** Every vector on `backend`, in their order, the context made once for each language and
    * protocol.
    */
  def runAll(
      corpus: Path,
      backend: UplcBackend,
      vectors: Vector[Located]
  ): Either[CorpusError, Vector[Json]] =
    vectors.zipWithIndex
      .groupBy((v, _) => (v.language, v.protocol))
      .toVector
      .traverse { case ((language, protocol), group) =>
        costModel(corpus, language, protocol).map { model =>
          val context = backend.context(language, protocol, model)
          group.map { (v, index) =>
            val (version, run) =
              context.fold(cause => (None, Run.NoVerdict(cause)), evaluate(backend)(_, v.input))
            index -> item(v.scenario, version, run)
          }
        }
      }
      .map(_.flatten.sortBy(_._1).map(_._2))

  def run(args: List[String]): IO[ExitCode] = args match
    case List(vmName, corpusDir, expectationsFile, reportFile) =>
      PlutusVm.parse(vmName) match
        case Left(unknown) => IO.println(unknown.message).as(ExitCode(2))
        case Right(vm) =>
          conform(vm, Path.of(corpusDir), Path.of(expectationsFile), Path.of(reportFile))
    case _ =>
      IO.println(
        "usage: UplcConformanceMain <scalus|scarab> <corpus-dir> <expectations.json> <report.json>"
      ).as(ExitCode(2))

  private def conform(vm: PlutusVm, corpus: Path, expectations: Path, out: Path): IO[ExitCode] =
    val backend = UplcBackend.of(vm)
    NdcReport.bigStack.use { stack =>
      for
        read <- IO.blocking(scenarios(expectations).flatMap(_.traverse(locate(corpus, _)))).timed
        (readTime, vectors) = read
        ran <- IO(vectors.traverse(runAll(corpus, backend, _))).evalOn(stack).timed
        (runTime, items) = ran
        code <- items.flatten match
          case Left(error) => IO.println(error.message).as(ExitCode(1))
          case Right(results) =>
            for
              generated <- NdcReport.now
              report = Json.fromFields(
                NdcReport.header(
                  "uplc-conformance",
                  generated,
                  Json.obj("name" -> Json.fromString(NdcReport.implementation(vm)))
                ) :+ ("scenarios" -> Json.fromValues(results))
              )
              written <- NdcReport.write(out, report).timed
              counts = results
                .flatMap(_.hcursor.get[String]("evaluation").toOption)
                .map(e => if e.startsWith("(program") then "evaluated" else e)
                .groupBy(identity)
                .view
                .mapValues(_.size)
                .toMap
              _ <- IO.println(
                s"${vm.name}: ${results.size} scenarios $counts; read ${readTime.toMillis} ms, " +
                  s"run ${runTime.toMillis} ms, write ${written._1.toMillis} ms"
              )
            yield ExitCode.Success
      yield code
    }
