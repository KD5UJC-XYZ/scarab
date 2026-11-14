// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.conformance

import cats.effect.{Clock, ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}

/** Runs one era of the cardano-cbor-dataset (r2rationality/cardano-cbor-dataset) through Scarab's
  * production decoders and encoders ([[ConwayRules]], [[DijkstraRules]]) and writes the version 2
  * report of node-diversity-conformance's `cbor-decoding` scenario.
  *
  * Arguments: the era's corpus directory (`dataset/<era>`), the era (`conway` or `dijkstra`), the
  * dataset's commit, Scarab's commit, and the report to write. Before the corpus, the normaliser is
  * checked against the dataset's `dataset/normalization` vectors beside the era's directory.
  */
object CborDatasetMain extends IOApp:
  enum Failure extends DomainError:
    case Arguments
    case UnknownEra(name: String)
    case Commit(value: String)
    case UnknownCategory(sample: String)
    case Normaliser(cases: Vector[String])

    def message: String = this match
      case Arguments =>
        "usage: CborDatasetMain <corpus-era-dir> <conway|dijkstra> <dataset-commit> <scarab-commit> <report.json>"
      case UnknownEra(name)        => s"unknown era $name"
      case Commit(value)           => s"$value is not a full lowercase commit hash"
      case UnknownCategory(sample) => s"$sample is not under a known sample category"
      case Normaliser(cases) =>
        s"the normaliser fails the dataset's vectors ${cases.mkString(", ")}"

  /** An era of the dataset, the protocol version its samples are generated at (`eraProtVerHigh` in
    * the dataset's `EraSpec.hs`), and Scarab's rules for it.
    */
  enum DatasetEra(val protocolVersion: String, val rules: Map[String, DatasetRule]):
    case Conway extends DatasetEra("11.0", ConwayRules.rules)
    case Dijkstra extends DatasetEra("12.0", DijkstraRules.rules)

  private final case class Config(
      corpus: Path,
      era: DatasetEra,
      dataset: String,
      scarab: String,
      out: Path
  )

  private final case class Sample(path: String, rule: String, kind: DatasetCheck.Kind, file: Path)

  private val commit = "[0-9a-f]{40}".r

  private def config(args: List[String]): Either[Failure, Config] = args match
    case List(corpus, era, dataset, scarab, out) =>
      for
        e <- DatasetEra.values.find(_.toString.toLowerCase == era).toRight(Failure.UnknownEra(era))
        d <- Either.cond(commit.matches(dataset), dataset, Failure.Commit(dataset))
        s <- Either.cond(commit.matches(scarab), scarab, Failure.Commit(scarab))
      yield Config(Path.of(corpus), e, d, s, Path.of(out))
    case _ => Left(Failure.Arguments)

  private def read(file: Path): IO[Bytes] = IO.blocking(Bytes.wrap(Files.readAllBytes(file)))

  private def inputs(root: Path): IO[Vector[Path]] =
    IO.blocking(
      Files.walk(root).iterator.asScala.filter(_.toString.endsWith(".input.cbor")).toVector.sorted
    )

  private def relative(root: Path, file: Path): String =
    root.relativize(file).iterator.asScala.map(_.toString).mkString("/")

  private def sample(root: Path)(file: Path): Either[Failure, Sample] =
    val path = relative(root, file)
    path.split('/').toList match
      case rule :: category :: _ :: _ =>
        DatasetCheck.Kind
          .of(category)
          .map(Sample(path, rule, _, file))
          .toRight(Failure.UnknownCategory(path))
      case _ => Left(Failure.UnknownCategory(path))

  private def expectedOf(file: Path): Path =
    file.resolveSibling(file.getFileName.toString.stripSuffix(".input.cbor") + ".expected.cbor")

  /** The normalisation vectors the normaliser fails, by name. */
  private def normaliserFailures(corpus: Path): IO[Vector[String]] =
    val vectors = Option(corpus.toAbsolutePath.getParent).map(_.resolve("normalization"))
    vectors.filter(Files.isDirectory(_)).fold(IO.pure(Vector.empty[String])) { dir =>
      inputs(dir).flatMap(_.flatTraverse { file =>
        (read(file), read(expectedOf(file))).mapN { (input, expected) =>
          Vector(file.getFileName.toString).filter(_ => CborNormalForm.of(input) != Right(expected))
        }
      })
    }

  private def outcome(rules: Map[String, DatasetRule])(s: Sample): IO[DatasetCheck.Outcome] =
    for
      input <- read(s.file)
      expectedFile = expectedOf(s.file)
      expected <- IO
        .blocking(Files.exists(expectedFile))
        .ifM(read(expectedFile).map(Some(_)), IO.pure(None))
    yield DatasetCheck.check(s.rule, rules.get(s.rule), s.kind, input, expected)

  /** The version 2 report of the dataset's `schema/report.schema.json`: the outcome of each sample
    * by its path relative to the era's corpus.
    */
  private[conformance] def reportJson(
      protocolVersion: String,
      dataset: String,
      scarab: String,
      generatedAt: Long,
      results: Vector[(String, DatasetCheck.Outcome)]
  ): Json =
    Json.obj(
      "version" -> "2".asJson,
      "generated_at" -> generatedAt.asJson,
      "protocol_version" -> protocolVersion.asJson,
      "dataset_commit" -> dataset.asJson,
      "implementation_commit" -> scarab.asJson,
      "results" -> Json.fromFields(results.map((path, o) => path -> o.json))
    )

  /** One line per rule: its decoder, how it is reached and written, and the met and total samples
    * of each expectation class.
    */
  private def table(
      rules: Map[String, DatasetRule],
      results: Vector[(Sample, DatasetCheck.Outcome)]
  ): Vector[String] =
    results.groupBy(_._1.rule).toVector.sortBy(_._1).map { (rule, rs) =>
      def counts(kind: DatasetCheck.Kind) =
        val of = rs.filter(_._1.kind == kind)
        s"${of.count(_._2 == DatasetCheck.Outcome.Met)}/${of.size}"
      val mapping = rules.get(rule) match
        case Some(DatasetRule.Mapped(decoder, reach, writing, _)) =>
          s"$decoder\t${reach.render}\t${writing.render}"
        case Some(DatasetRule.Unmapped(why)) => s"-\t${why.message}\t-"
        case None                            => s"-\t${Unmapped.NoDecoder.message}\t-"
      s"$rule\t$mapping\tvalid ${counts(DatasetCheck.Kind.Valid)}\tinvalid ${counts(DatasetCheck.Kind.Invalid)}"
    }

  private def seconds(d: FiniteDuration): String = f"${d.toMillis / 1000.0}%.1f s"

  def run(args: List[String]): IO[ExitCode] =
    config(args).fold(f => IO.println(f.message).as(ExitCode.Error), runWith)

  private def runWith(c: Config): IO[ExitCode] =
    normaliserFailures(c.corpus).flatMap {
      case failed if failed.nonEmpty =>
        IO.println(Failure.Normaliser(failed).message).as(ExitCode.Error)
      case _ =>
        for
          listed <- Clock[IO].timed(inputs(c.corpus))
          samples <- listed._2
            .traverse(sample(c.corpus))
            .fold(f => IO.println(f.message).as(None), s => IO.pure(Some(s)))
          code <- samples.fold(IO.pure(ExitCode.Error)) { all =>
            for
              ran <- Clock[IO].timed(all.traverse(s => outcome(c.era.rules)(s).tupleLeft(s)))
              now <- Clock[IO].realTime
              json = reportJson(
                c.era.protocolVersion,
                c.dataset,
                c.scarab,
                now.toMillis,
                ran._2.map((s, o) => s.path -> o)
              )
              wrote <- Clock[IO].timed(
                IO.blocking(Files.writeString(c.out, json.noSpaces, StandardCharsets.UTF_8))
              )
              met = ran._2.count(_._2 == DatasetCheck.Outcome.Met)
              _ <- table(c.era.rules, ran._2).traverse_(IO.println)
              _ <- IO.println(
                s"samples ${ran._2.size}, met $met, failed ${ran._2.size - met}; " +
                  s"list ${seconds(listed._1)}, run ${seconds(ran._1)}, write ${seconds(wrote._1)}"
              )
            yield ExitCode.Success
          }
        yield code
    }
