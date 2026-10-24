// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import java.nio.file.{Files, Path}
import munit.FunSuite

/** The documentation names every metric of the catalogue, with its type and its labels, and no
  * metric that is not there (`docs/running/metrics.md`).
  */
class DocsSuite extends FunSuite:
  private def repositoryRoot: Path =
    Iterator
      .iterate(Path.of(sys.props("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("docs/running/metrics.md")))
      .getOrElse(fail("docs/running/metrics.md not found above the working directory"))

  private def kind(d: MetricDef): String = d match
    case _: CounterDef[?]   => "counter"
    case _: GaugeDef[?]     => "gauge"
    case _: HistogramDef[?] => "histogram"

  /** The rows of the metrics tables: the first three cells of each row that starts with a metric
    * name in backticks.
    */
  private lazy val documented: Map[String, (String, String)] =
    val text = Files.readString(repositoryRoot.resolve("docs/running/metrics.md"))
    val section = text.linesIterator
      .dropWhile(_ != "## Metrics")
      .drop(1)
      .toList
    section
      .filter(_.startsWith("| `"))
      .map(_.split("\\|").map(_.trim).toList)
      .collect { case "" :: name :: type_ :: labels :: _ =>
        name.stripPrefix("`").stripSuffix("`") -> (type_ -> labels)
      }
      .toMap

  test("every metric of the catalogue is documented with its type and labels") {
    Catalogue.all.foreach { d =>
      val labels =
        if d.labelNames.isEmpty then "" else d.labelNames.map(l => s"`${l.value}`").mkString(", ")
      assertEquals(documented.get(d.name.value), Some(kind(d) -> labels), d.name.value)
    }
  }

  test("the documentation names no metric the node does not export") {
    assertEquals(documented.keySet.diff(Catalogue.all.map(_.name.value).toSet), Set.empty[String])
  }

/** Writes the rows of the metrics tables for `docs/running/metrics.md`:
  * `sbt "node/Test/runMain xyz.kd5ujc.scarab.node.metrics.CatalogueTable"`.
  */
object CatalogueTable:
  def main(args: Array[String]): Unit =
    Catalogue.all.foreach { d =>
      val kind = d match
        case _: CounterDef[?]   => "counter"
        case _: GaugeDef[?]     => "gauge"
        case _: HistogramDef[?] => "histogram"
      val labels = d.labelNames.map(l => s"`${l.value}`").mkString(", ")
      println(s"| `${d.name.value}` | $kind | $labels | ${d.help} |")
    }
