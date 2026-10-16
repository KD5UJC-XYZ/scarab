// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.nio.file.{Files, Path}
import munit.FunSuite

/** The documentation lists every kind of event with its namespace and severity, and nothing else
  * (`docs/running.md`, "Log events").
  */
class LogDocsSuite extends FunSuite:
  private def repositoryRoot: Path =
    Iterator
      .iterate(Path.of(sys.props("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("docs/running.md")))
      .getOrElse(fail("docs/running.md not found above the working directory"))

  /** The rows of the table: event, namespace, and the first word of the severity cell. */
  private lazy val documented: List[(String, String, String)] =
    Files
      .readString(repositoryRoot.resolve("docs/running.md"))
      .linesIterator
      .dropWhile(_ != "#### Log events")
      .drop(1)
      .takeWhile(l => !l.startsWith("#"))
      .filter(_.startsWith("| `"))
      .map(_.split("\\|").map(_.trim).toList)
      .collect { case "" :: event :: namespace :: severity :: _ =>
        (unquoted(event), unquoted(namespace), severity.takeWhile(_ != ' '))
      }
      .toList

  private def unquoted(cell: String): String = cell.stripPrefix("`").stripSuffix("`")

  test(
    "every kind of event is documented with its namespace and severity, in the order of the code"
  ) {
    assertEquals(
      documented,
      EventKind.values.toList.map(k => (k.name, k.namespace, k.severity.toString))
    )
  }
