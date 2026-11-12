// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import cats.parse.{Numbers, Parser as P}
import cats.syntax.all.*
import io.circe.parser
import java.nio.file.{FileSystems, Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import xyz.kd5ujc.scarab.uplc.cost.ExBudget

/** The vendored plutus reference data (`plutus-reference`, a test resource of `plutus` that this
  * module reads without depending on it) as the view of one release: the base release's files, less
  * those the release lists as `absent`, with its `overlays/<tag>/` files in their place. This is
  * the rule of `PlutusReference` in the `plutus` tests, whose class path carries Scalus.
  */
object ReferenceCorpus:
  /** The resource directory, also when the resources come in a jar (a `tools` program's class
    * path).
    */
  lazy val root: Path =
    val uri = getClass.getResource("/plutus-reference").toURI
    if uri.getScheme == "jar" then
      FileSystems
        .newFileSystem(uri, java.util.Collections.emptyMap[String, Any]())
        .getPath("/plutus-reference")
    else Paths.get(uri)

  private def filesBelow(directory: Path): Map[String, Path] =
    if !Files.isDirectory(directory) then Map.empty
    else
      Using.resource(Files.walk(directory)) { stream =>
        stream
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .map(file => directory.relativize(file).toString.replace('\\', '/') -> file)
          .toMap
      }

  /** The files of release `tag` by their path in the layout (`conformance/...`). */
  def view(tag: String): Map[String, Path] =
    val absent = parser
      .parse(Files.readString(root.resolve("releases.json")))
      .flatMap(
        _.hcursor
          .downField("releases")
          .as[Vector[io.circe.Json]]
          .map(_.find(_.hcursor.get[String]("tag").contains(tag)))
      )
      .toOption
      .flatten
      .map(_.hcursor.get[Vector[String]]("absent").getOrElse(Vector.empty))
    assert(absent.isDefined, s"plutus reference: no release $tag")
    val base = filesBelow(root).filterNot((name, _) => name.startsWith("overlays/"))
    base -- absent.getOrElse(Vector.empty) ++ filesBelow(root.resolve("overlays").resolve(tag))

  val Releases: Vector[String] = Vector("1.70.0.0", "1.71.0.0")

  /** The release the own evaluator implements (plutus 1.70.0.0, `507cab8c`, ADR 0027). */
  val Pinned: String = "1.70.0.0"

  /** The directory of the conformance cases in a view: upstream's
    * `plutus-conformance/test-cases/uplc/evaluation`.
    */
  val Conformance: String = "conformance"

  /** What the reference's conformance runner recorded for a case, from its `.uplc.expected` and
    * `.budget.expected` files read together. A failure is recorded as a fixed text in both files
    * (`shownParseError` and `shownEvaluationFailure`, `PlutusConformance/Common.hs:74-80`, plutus
    * 1.70.0.0 `507cab8c`), compared exactly (`compareAlphaEq`, `Common.hs:431-437`); a success as
    * the textual result program and the pretty-printed budget (`Pretty ExBudget`,
    * `PlutusCore/Evaluation/Machine/ExBudget.hs:209-216`).
    */
  enum Expected:
    /** The program evaluates to `result`, a textual program, spending `budget`. */
    case Evaluates(result: String, budget: ExBudget)

    /** `evaluation failure`: the machine fails; the budget file says the same. */
    case EvaluationFailure

    /** `parse/decode error`: the program does not parse, or names a free variable
      * (`PlutusConformance/CekEvaluator.hs:54-57`).
      */
    case ParseError

  /** A case of the conformance corpus.
    *
    * @param name
    *   the case's directory below `conformance/`, as upstream names its test
    * @param program
    *   the text of its `.uplc` file
    */
  final case class ConformanceCase(name: String, program: String, expected: Expected)

  /** Why the conformance files of a view do not make a case. */
  enum CorpusProblem:
    case Missing(name: String, file: String)
    case UnreadableBudget(name: String, text: String)

    /** The result file and the budget file disagree on whether the case succeeds. */
    case Inconsistent(name: String, result: String, budget: String)

  val ParseErrorText: String = "parse/decode error"
  val EvaluationFailureText: String = "evaluation failure"

  // `({cpu: N\n| mem: M})`, the layout of `vsep` within parentheses and braces.
  private val budgetText: P[ExBudget] =
    val gap = P.charsWhile0(_.isWhitespace)
    val count = Numbers.digits.mapFilter(_.toLongOption)
    ((P.string("({cpu:") *> gap *> count <* gap) ~ (P.string("| mem:") *> gap *> count <* gap <*
      P.string("})")))
      .map((cpu, mem) => ExBudget.of(cpu, mem))

  private def expectation(
      name: String,
      result: String,
      budget: String
  ): Either[CorpusProblem, Expected] =
    (result, budget) match
      case (ParseErrorText, ParseErrorText)               => Right(Expected.ParseError)
      case (EvaluationFailureText, EvaluationFailureText) => Right(Expected.EvaluationFailure)
      case (ParseErrorText | EvaluationFailureText, _) |
          (_, ParseErrorText | EvaluationFailureText) =>
        Left(CorpusProblem.Inconsistent(name, result, budget))
      case _ =>
        budgetText
          .parseAll(budget)
          .leftMap(_ => CorpusProblem.UnreadableBudget(name, budget))
          .map(Expected.Evaluates(result, _))

  /** Every conformance case of release `tag`, in the order of their names: a directory with a
    * `<name>.uplc` program, its `.uplc.expected` result and its `.budget.expected` budget.
    */
  def conformance(tag: String): Either[CorpusProblem, Vector[ConformanceCase]] =
    val files = view(tag)
    val prefix = s"$Conformance/"
    def read(name: String, suffix: String): Either[CorpusProblem, String] =
      files
        .get(s"$prefix$name$suffix")
        .toRight(CorpusProblem.Missing(name, s"$name$suffix"))
        .map(Files.readString)
    files.keys.toVector
      .collect {
        case p if p.startsWith(prefix) && p.endsWith(".uplc") =>
          p.stripPrefix(prefix).stripSuffix(".uplc")
      }
      .sorted
      .traverse { stem =>
        val name = stem.split('/').dropRight(1).mkString("/")
        for
          program <- read(stem, ".uplc")
          result <- read(stem, ".uplc.expected")
          budget <- read(stem, ".budget.expected")
          expected <- expectation(name, result, budget)
        yield ConformanceCase(name, program, expected)
      }
