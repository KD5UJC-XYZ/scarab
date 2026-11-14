// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.effect.{Clock, ExitCode, IO, IOApp, Resource, Sync}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.plutus.{PlutusBackend, PlutusVm, UnknownPlutusVm}

/** The ledger-rules report of node-diversity-conformance
  * (https://github.com/pragma-org/node-diversity-conformance, `scenarios/ledger-rules`): every
  * transaction fixture judged by Scarab's production decoder and rules, its result written as the
  * scenario's report schema has it.
  *
  * Arguments: `<scenarios-dir> <documents-root> <report.json> [--sidecar <path>] [--dataset-commit
  * <sha>] [--plutus-vm scalus|scarab] [--select first|expected-in-failures]`. The scenarios are the
  * site's `scenarios/ledger-rules/fixtures/scenarios`; a fixture's `$ref` (`common/...`) is read
  * from the documents root, Amaru's `crates/amaru-ledger/tests/data/transaction`. The sidecar
  * records the run's `--select` (`selection`), which the report's schema has no field for, and each
  * fixture's verdict (`cases`) with every failure, in the reference's order, and its names, and
  * which rule chose the reported name. `--dataset-commit` is the Amaru commit that last changed the
  * corpus, which the site compares for freshness.
  *
  * The report gives one name for a refused transaction, which may fail several predicates at once.
  * `--select` says which ([[Selection]]): with `expected-in-failures`, the default, the report
  * names the expected failure when Scarab raised it among others, and otherwise the first; with
  * `first`, always the first failure the schema names, in the reference's order. The default is the
  * criterion cardano-node's report for the scenario is produced by. Either way the report names
  * only a failure Scarab raised, and an accepted transaction is a pass whatever the fixture
  * expects.
  */
object LedgerRulesMain extends IOApp:
  enum Error extends DomainError:
    case Arguments(args: List[String])
    case Vm(cause: UnknownPlutusVm)
    case Select(text: String)
    case Commit(sha: String)
    case NotADirectory(path: Path)
    case NoFixtures(path: Path)

    def message: String = this match
      case Arguments(args) =>
        s"usage: <scenarios-dir> <documents-root> <report.json> [--sidecar <path>] " +
          s"[--dataset-commit <sha>] [--plutus-vm scalus|scarab] " +
          s"[--select first|expected-in-failures] (given: ${args.mkString(" ")})"
      case Vm(cause)           => cause.message
      case Select(text)        => s"unknown selection '$text' (known: first, expected-in-failures)"
      case Commit(sha)         => s"not a full commit id: $sha"
      case NotADirectory(path) => s"not a directory: $path"
      case NoFixtures(path)    => s"no fixture files in $path"

  final case class Config(
      scenarios: Path,
      documents: Path,
      report: Path,
      sidecar: Option[Path],
      commit: Option[String],
      vm: PlutusVm,
      selection: Selection
  )

  private val CommitId = "^[0-9a-f]{40}$".r

  def config(args: List[String]): Either[Error, Config] =
    @annotation.tailrec
    def options(
        rest: List[String],
        acc: Map[String, String]
    ): Either[Error, Map[String, String]] = rest match
      case Nil => Right(acc)
      case flag :: value :: tail if flag.startsWith("--") && !acc.contains(flag) =>
        options(tail, acc.updated(flag, value))
      case _ => Left(Error.Arguments(args))
    args match
      case scenarios :: documents :: report :: rest =>
        for
          opts <- options(rest, Map.empty)
          _ <- Either.cond(
            opts.keySet.subsetOf(Set("--sidecar", "--dataset-commit", "--plutus-vm", "--select")),
            (),
            Error.Arguments(args)
          )
          vm <- opts
            .get("--plutus-vm")
            .fold(Right(PlutusVm.Default))(PlutusVm.parse(_).leftMap(Error.Vm(_)))
          selection <- opts
            .get("--select")
            .fold(Right(Selection.Default))(t => Selection.parse(t).toRight(Error.Select(t)))
          commit <- opts
            .get("--dataset-commit")
            .traverse(c => Either.cond(CommitId.matches(c), c, Error.Commit(c)))
        yield Config(
          Path.of(scenarios),
          Path.of(documents),
          Path.of(report),
          opts.get("--sidecar").map(Path.of(_)),
          commit,
          vm,
          selection
        )
      case _ => Left(Error.Arguments(args))

  /** The fixtures of the corpus, by id, and the shared documents by their path under the root. */
  final case class Corpus(
      fixtures: Vector[(String, String, String)],
      documents: Map[String, String]
  )

  private def text[F[_]: Sync](path: Path): F[String] =
    Sync[F].blocking(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))

  private def listed[F[_]: Sync](open: => java.util.stream.Stream[Path]): F[Vector[Path]] =
    Resource
      .fromAutoCloseable(Sync[F].blocking(open))
      .use(s => Sync[F].blocking(s.iterator.asScala.filter(Files.isRegularFile(_)).toVector.sorted))

  def corpus[F[_]: Sync](config: Config): F[Either[Error, Corpus]] =
    for
      dirs <- Sync[F].blocking(
        Files.isDirectory(config.scenarios) && Files.isDirectory(config.documents)
      )
      result <-
        if !dirs then
          Sync[F].pure(
            Left(
              Error.NotADirectory(
                if Files.isDirectory(config.scenarios) then config.documents else config.scenarios
              )
            )
          )
        else
          for
            scenarioFiles <- listed(Files.list(config.scenarios))
            fixtures <- scenarioFiles
              .flatMap(p => LedgerRulesCase.id(p.getFileName.toString).map(_ -> p))
              .traverse((id, p) => text(p).map(t => (id, p.getFileName.toString, t)))
            documentFiles <- listed(Files.walk(config.documents))
            documents <- documentFiles
              .filter(_.getFileName.toString.endsWith(".json"))
              .traverse(p => text(p).map(config.documents.relativize(p).toString -> _))
          yield Either.cond(
            fixtures.nonEmpty,
            Corpus(fixtures, documents.toMap),
            Error.NoFixtures(config.scenarios)
          )
    yield result

  /** Every fixture of `corpus` judged as `c` says: its verdict by the evaluator of `c.vm`, and the
    * name of a refusal chosen by `c.selection` from the fixture's expectation.
    */
  def judged(c: Config, corpus: Corpus): Vector[LedgerRulesCase] =
    val backend = PlutusBackend.of(c.vm)
    corpus.fixtures.map { (id, file, text) =>
      LedgerRulesCase(
        id,
        file,
        LedgerRulesRun.judge(file, text, corpus.documents.get, backend),
        LedgerRulesFixture.expectation(file, text).toOption.flatMap(_.refusal),
        c.selection
      )
    }

  def report(cases: Vector[LedgerRulesCase], generatedAt: String, commit: Option[String]): Json =
    Json.fromFields(
      Vector(
        "version" -> "1".asJson,
        "scenario" -> "ledger-rules".asJson,
        "generated_at" -> generatedAt.asJson
      ) ++ commit.map(c => "dataset_commit" -> c.asJson) ++ Vector(
        "implementation" -> Json.obj("name" -> "scarab".asJson),
        "scenarios" -> cases.flatMap(LedgerRulesCase.entry).asJson
      )
    )

  /** The sidecar: the selection of the run and what [[LedgerRulesCase.detail]] records of each
    * case.
    */
  def sidecar(selection: Selection, cases: Vector[LedgerRulesCase]): Json =
    Json.obj(
      "selection" -> selection.flag.asJson,
      "cases" -> cases.map(LedgerRulesCase.detail).asJson
    )

  /** What a run of `c` writes, by path: the report, and the sidecar when `c` asks for one. */
  def written(
      c: Config,
      cases: Vector[LedgerRulesCase],
      generatedAt: String
  ): Vector[(Path, Json)] =
    (c.report -> report(cases, generatedAt, c.commit)) +:
      c.sidecar.map(_ -> sidecar(c.selection, cases)).toVector

  private def write[F[_]: Sync](path: Path, json: Json): F[Unit] =
    Sync[F].blocking {
      Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
      Files.write(path, json.spaces2.getBytes(StandardCharsets.UTF_8))
    }.void

  private def millis(d: FiniteDuration): Long = d.toMillis

  def run(args: List[String]): IO[ExitCode] =
    config(args).fold(
      e => IO.consoleForIO.errorln(e.message).as(ExitCode(2)),
      c => main[IO](c)
    )

  private def main[F[_]: Sync](c: Config)(using clock: Clock[F]): F[ExitCode] =
    def say(line: String): F[Unit] = Sync[F].blocking(System.err.println(line))
    clock.timed(corpus[F](c)).flatMap {
      case (_, Left(e)) => say(e.message).as(ExitCode(2))
      case (readTime, Right(corpus)) =>
        for
          _ <- say(
            s"read ${corpus.fixtures.size} fixtures and ${corpus.documents.size} documents " +
              s"in ${millis(readTime)} ms"
          )
          timedJudging <- clock.timed(Sync[F].delay(judged(c, corpus)))
          (runTime, cases) = timedJudging
          now <- clock.realTimeInstant
          timedWriting <- clock.timed(
            written(c, cases, now.toString).traverse_((path, json) => write[F](path, json))
          )
          (writeTime, _) = timedWriting
          _ <- say(
            s"judged ${cases.size} fixtures with the ${c.vm.name} evaluator in ${millis(runTime)} ms: " +
              s"${cases.count(_.result.contains(LedgerRulesCase.Pass))} pass, " +
              s"${cases.count(r => r.result.exists(_ != LedgerRulesCase.Pass))} named failures, " +
              s"${cases.count(_.result.isEmpty)} without a result; wrote in ${millis(writeTime)} ms"
          )
          _ <- say(
            s"names chosen by ${c.selection.flag}: " +
              s"${cases.count(_.chosen.exists(_._2 == Selection.ExpectedInFailures))} " +
              "refusals named by the expected failure among others"
          )
        yield ExitCode.Success
    }
