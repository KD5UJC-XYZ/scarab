// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.vm.*

class VmCommandSuite extends munit.FunSuite:
  private val original =
    val path = Path.of("fixtures/plutus")
    if Files.exists(path) then path else Path.of("../fixtures/plutus")
  private val suffixes = Vector(".uplc", ".uplc.expected", ".uplc.budget.expected")
  private def withCopy(test: Path => Unit): Unit =
    val directory = Files.createTempDirectory("vm-fixtures-")
    try
      VmCommand.names.foreach(name =>
        suffixes.foreach { suffix =>
          Files.copy(original.resolve(name + suffix), directory.resolve(name + suffix))
        }
      )
      test(directory)
    finally
      val files = Files.list(directory)
      try files.forEach(p => { Files.delete(p); () })
      finally files.close()
      Files.delete(directory)

  test("pinned file adapter loads all five vectors") {
    assertEquals(VmCommand.loadFiles(original)._2.size, 5)
  }
  test("swapping complete otherwise valid fixture triples is rejected by filename") {
    withCopy { dir =>
      suffixes.foreach { suffix =>
        val a = dir.resolve("addInteger-01" + suffix)
        val b = dir.resolve("addInteger-02" + suffix)
        val old = Files.readAllBytes(a)
        Files.write(a, Files.readAllBytes(b))
        Files.write(b, old)
      }
      assert(
        intercept[IllegalArgumentException](VmCommand.loadFiles(dir)).getMessage
          .contains("unregistered")
      )
    }
  }
  test("budget-only and expectation-only mutations are rejected") {
    for suffix <- suffixes.tail do
      withCopy { dir =>
        val path = dir.resolve("addInteger-01" + suffix)
        Files.writeString(path, Files.readString(path) + " ")
        assert(
          intercept[IllegalArgumentException](VmCommand.loadFiles(dir)).getMessage
            .contains("unregistered")
        )
      }
  }
  test("all fixture roles have bounded reads, missing and nonregular files are rejected") {
    for suffix <- suffixes do
      withCopy { dir =>
        Files.writeString(dir.resolve("addInteger-01" + suffix), "x" * 65537)
        assert(
          intercept[IllegalArgumentException](VmCommand.loadFiles(dir)).getMessage
            .contains("too large")
        )
      }
    withCopy { dir =>
      val path = dir.resolve("addInteger-01.uplc")
      Files.delete(path)
      intercept[IllegalArgumentException](VmCommand.loadFiles(dir))
      Files.createDirectory(path)
      intercept[IllegalArgumentException](VmCommand.loadFiles(dir))
    }
  }
  test("tagless VM orchestration propagates port failures and never passes empty corpus") {
    type Result[A] = Either[Throwable, A]
    val (params, inputs) = VmCommand.loadFiles(original)
    def source(xs: Vector[VectorInput]): VmFixtureSource[Result] = new VmFixtureSource[Result]:
      def load: Result[(ReferenceParameters, Vector[VectorInput])] = Right((params, xs))
    val sink = new VmReportSink[Result]:
      def write(checks: Vector[ConformanceCheck]): Result[Unit] = Right(())
    assertEquals(VerifyVm.run(source(inputs), sink), Right(true))
    assertEquals(VerifyVm.run(source(Vector.empty), sink), Right(false))
    val error = new IllegalStateException("port failure")
    val broken = new VmFixtureSource[Result]:
      def load: Result[(ReferenceParameters, Vector[VectorInput])] = Left(error)
    assertEquals(VerifyVm.run(broken, sink), Left(error))
    val brokenSink = new VmReportSink[Result]:
      def write(checks: Vector[ConformanceCheck]): Result[Unit] = Left(error)
    assertEquals(VerifyVm.run(source(inputs), brokenSink), Left(error))
  }
