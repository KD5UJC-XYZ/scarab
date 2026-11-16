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
import xyz.kd5ujc.scarab.ledger.DecoderVersion
import xyz.kd5ujc.scarab.storage.FileNumber

/** Runs one era of the cardano-cbor-dataset (r2rationality/cardano-cbor-dataset) through Scarab's
  * production decoders and encoders ([[ConwayRules]], [[DijkstraRules]]) and writes the version 2
  * report of node-diversity-conformance's `cbor-decoding` scenario.
  *
  * Arguments: the era's corpus directory (`dataset/<era>`), the era (`conway` or `dijkstra`), the
  * dataset's commit, Scarab's commit, and the report to write. Before the corpus, the normaliser is
  * checked against the dataset's `dataset/normalization` vectors beside the era's directory, every
  * one its README's table lists; and the corpus is checked against the era's `coverage.json`, its
  * count of samples and its rules. A missing vector, a vector the normaliser fails, an empty corpus
  * or one that differs from its inventory is an incomplete run: no report is written, never a pass.
  */
object CborDatasetMain extends IOApp:
  enum Failure extends DomainError:
    case Arguments
    case UnknownEra(name: String)
    case Commit(value: String)
    case UnknownCategory(sample: String)
    case Normaliser(cases: Vector[String])
    case NoVectors(directory: Path)
    case NoVectorTable(file: Path)
    case MissingVectors(names: Vector[String])
    case NoInventory(file: Path)
    case BadInventory(file: Path, cause: io.circe.Error)
    case EmptyCorpus(directory: Path)
    case InventoryMismatch(
        expected: Int,
        found: Int,
        missingRules: Vector[String],
        unexpectedRules: Vector[String]
    )

    def message: String = this match
      case Arguments =>
        "usage: CborDatasetMain <corpus-era-dir> <conway|dijkstra> <dataset-commit> <scarab-commit> <report.json>"
      case UnknownEra(name)        => s"unknown era $name"
      case Commit(value)           => s"$value is not a full lowercase commit hash"
      case UnknownCategory(sample) => s"$sample is not under a known sample category"
      case Normaliser(cases) =>
        s"the normaliser fails the dataset's vectors ${cases.mkString(", ")}"
      case NoVectors(directory) =>
        s"incomplete: no normalisation vectors in $directory: the dataset's normalization directory is missing or empty"
      case NoVectorTable(file) =>
        s"incomplete: no table of normalisation vectors in $file"
      case MissingVectors(names) =>
        s"incomplete: the normalisation vectors ${names.mkString(", ")} are listed and missing"
      case NoInventory(file) => s"incomplete: no corpus inventory at $file"
      case BadInventory(file, cause) =>
        s"incomplete: the corpus inventory $file does not read: ${cause.getMessage}"
      case EmptyCorpus(directory) => s"incomplete: no samples in $directory"
      case InventoryMismatch(expected, found, missing, unexpected) =>
        s"incomplete: the inventory lists $expected samples, $found are present" +
          (if missing.isEmpty then "" else s"; rules without samples: ${missing.mkString(", ")}") +
          (if unexpected.isEmpty then ""
           else s"; samples of rules it does not list: ${unexpected.mkString(", ")}")

  /** An era of the dataset, the decoder version Scarab reads it at and Scarab's rules for it. The
    * report names that decoder version as its protocol version: it is the version the samples were
    * decoded and written again at, the era's `eraProtVerHigh` ([[DecoderVersion.high]]), Conway 11
    * and Dijkstra 12, as the dataset decodes them (its `EraSpec.hs`).
    */
  enum DatasetEra(val version: DecoderVersion, val rules: Map[String, DatasetRule]):
    case Conway extends DatasetEra(ConwayRules.version, ConwayRules.rules)
    case Dijkstra extends DatasetEra(DijkstraRules.version, DijkstraRules.rules)

    def protocolVersion: String = s"${version.value}.0"

  /** A normalisation vector of the dataset: its name, its input, and its normal form when the file
    * of it is there.
    */
  final case class NormalVector(name: String, input: Bytes, expected: Option[Bytes])

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

  /** The dataset's normalisation vectors, `dataset/normalization` beside the era's directory, that
    * directory, and the names its README's table lists; no vectors when it is missing.
    */
  private def vectorsOf(corpus: Path): IO[(Path, Vector[String], Vector[NormalVector])] =
    val dir = corpus.toAbsolutePath.normalize.resolveSibling("normalization")
    val readme = dir.resolve("README.md")
    val table = IO
      .blocking(Files.isRegularFile(readme))
      .ifM(IO.blocking(Files.readString(readme)).map(vectorTable), IO.pure(Vector.empty))
    val vectors = IO
      .blocking(Files.isDirectory(dir))
      .ifM(
        inputs(dir).flatMap(_.traverse { file =>
          val expected = expectedOf(file)
          (
            read(file),
            IO.blocking(Files.exists(expected)).ifM(read(expected).map(Some(_)), IO.pure(None))
          ).mapN(NormalVector(file.getFileName.toString.stripSuffix(".input.cbor"), _, _))
        }),
        IO.pure(Vector.empty)
      )
    (table, vectors).mapN((t, v) => (dir, t, v))

  private val tableRow = raw"\|\s*(\d+)\s*\|\s*([^|\s]+)\s*\|.*".r

  /** The vectors a normalization README's table lists, by their files' name:
    * `0001-indefinite-array` for the row `| 1 | indefinite-array | ...`.
    */
  def vectorTable(readme: String): Vector[String] =
    readme.linesIterator.toVector.collect { case tableRow(n, name) =>
      s"${FileNumber.padded(n.toInt, 4)}-$name"
    }

  /** How many vectors the normaliser meets, or why the run stops before it reads the corpus: the
    * table of them is missing, one it lists is missing, there are none, the normaliser fails one or
    * one has no normal form to meet.
    */
  def checkVectors(
      dir: Path,
      table: Vector[String],
      vectors: Vector[NormalVector]
  ): Either[Failure, Int] =
    val missing = table.filterNot(vectors.map(_.name).toSet)
    if table.isEmpty then Left(Failure.NoVectorTable(dir.resolve("README.md")))
    else if missing.nonEmpty then Left(Failure.MissingVectors(missing))
    else if vectors.isEmpty then Left(Failure.NoVectors(dir))
    else
      val failed = vectors.collect {
        case v if !v.expected.exists(e => CborNormalForm.of(v.input) == Right(e)) => v.name
      }
      Either.cond(failed.isEmpty, vectors.size, Failure.Normaliser(failed))

  /** What an era's `coverage.json` says the corpus holds: its samples and its rules. */
  final case class Inventory(samples: Int, rules: Set[String])

  def inventoryOf(json: String): Either[io.circe.Error, Inventory] =
    io.circe.parser.parse(json).flatMap { doc =>
      val c = doc.hcursor
      (
        c.get[Int]("samples"),
        c.downField("rules").as[Vector[Json]].flatMap(_.traverse(_.hcursor.get[String]("name")))
      ).mapN((samples, rules) => Inventory(samples, rules.toSet))
    }

  /** The corpus found against its inventory: not empty, its count of samples, and its rules
    * exactly, none missing and none it does not list.
    */
  def checkInventory(
      corpus: Path,
      inventory: Inventory,
      found: Vector[(String, String)]
  ): Either[Failure, Unit] =
    val rules = found.map(_._2).toSet
    val missing = (inventory.rules -- rules).toVector.sorted
    val unexpected = (rules -- inventory.rules).toVector.sorted
    if found.isEmpty then Left(Failure.EmptyCorpus(corpus))
    else
      Either.cond(
        found.size == inventory.samples && missing.isEmpty && unexpected.isEmpty,
        (),
        Failure.InventoryMismatch(inventory.samples, found.size, missing, unexpected)
      )

  private def inventory(corpus: Path): IO[Either[Failure, Inventory]] =
    val file = corpus.resolve("coverage.json")
    IO.blocking(Files.isRegularFile(file))
      .ifM(
        IO.blocking(Files.readString(file))
          .map(inventoryOf(_).leftMap(Failure.BadInventory(file, _))),
        IO.pure(Left(Failure.NoInventory(file)))
      )

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
    vectorsOf(c.corpus).map((dir, table, vectors) => checkVectors(dir, table, vectors)).flatMap {
      case Left(failure) => IO.println(failure.message).as(ExitCode.Error)
      case Right(count) =>
        for
          _ <- IO.println(s"normaliser: the dataset's $count normalisation vectors pass")
          listed <- Clock[IO].timed(inputs(c.corpus))
          listing <- inventory(c.corpus)
          samples <- listing
            .flatMap(inv =>
              listed._2
                .traverse(sample(c.corpus))
                .flatTap(all => checkInventory(c.corpus, inv, all.map(s => s.path -> s.rule)))
            )
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
