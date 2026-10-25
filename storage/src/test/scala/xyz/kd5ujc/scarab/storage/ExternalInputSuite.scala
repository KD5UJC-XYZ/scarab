// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.storage.ExternalInput.Problem

/** A set but wrong external input path fails instead of skipping (#337). */
class ExternalInputSuite extends FunSuite:
  private val variable = "SCARAB_TEST_INPUT"

  private val root = FunFixture[Path](
    _ => Files.createTempDirectory("external-input"),
    dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
  )

  test("an unset variable is an environmental skip") {
    assertEquals(
      ExternalInput.directory(variable, Map.empty, "immutable"),
      ExternalInput.Unset(variable)
    )
  }

  root.test("a directory with every required subdirectory is present") { dir =>
    Files.createDirectory(dir.resolve("immutable"))
    assertEquals(
      ExternalInput.directory(variable, Map(variable -> dir.toString), "immutable"),
      ExternalInput.Present(variable, dir)
    )
  }

  root.test("a missing path, a file, a missing subdirectory or a blank value is invalid") { dir =>
    val absent = dir.resolve("absent")
    val file = Files.writeString(dir.resolve("file"), "")
    def problem(value: String) =
      ExternalInput.directory(variable, Map(variable -> value), "immutable") match
        case ExternalInput.Invalid(_, _, p) => Some(p)
        case _                              => None
    assertEquals(problem(absent.toString), Some(Problem.NotADirectory(absent)))
    assertEquals(problem(file.toString), Some(Problem.NotADirectory(file)))
    assertEquals(problem(dir.toString), Some(Problem.MissingDirectory(dir.resolve("immutable"))))
    assertEquals(problem(" "), Some(Problem.Blank))
  }

  root.test("a suite fails on an invalid input and skips an unset one") { dir =>
    val suite = new FunSuite with ExternalInputs
    val invalid = ExternalInput.directory(variable, Map(variable -> dir.resolve("absent").toString))
    val failed = intercept[munit.FailException](suite.withInput(invalid)(_ => ()))
    assert(failed.getMessage.contains(variable), failed.getMessage)
    intercept[org.junit.AssumptionViolatedException](
      suite.withInput(ExternalInput.Unset(variable))(_ => fail("the body ran on an unset input"))
    )
    var seen = Option.empty[Path]
    suite.withInput(ExternalInput.Present(variable, dir))(p => seen = Some(p))
    assertEquals(seen, Some(dir))
  }
