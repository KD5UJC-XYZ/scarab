// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes

class FixtureSuite extends munit.FunSuite:
  private val fixturePath =
    val local = Path.of("fixtures/cardano-golden.tsv")
    if Files.exists(local) then local else Path.of("../fixtures/cardano-golden.tsv")
  test("all pinned upstream transaction identities match") {
    val fixtures = Fixture.parse(Files.readString(fixturePath)).toOption.get
    assertEquals(fixtures.size, 6)
    fixtures.foreach(f => assertEquals(Fixture.check(f).map(_.matched), Right(true), f.name))
  }
  test("mismatching upstream expectation is reported") {
    val fixture = Fixture.parse(Files.readString(fixturePath)).toOption.get.head
    assertEquals(
      Fixture.check(fixture.copy(expected = Bytes(Vector.fill(32)(0.toByte)))).map(_.matched),
      Right(false)
    )
  }
  test("empty malformed unsupported and bad hash corpus entries fail") {
    assert(Fixture.parse("").isLeft)
    assert(Fixture.parse("x\ty").isLeft)
    assert(Fixture.parse("x\ttransaction-body\ta0\t00").isLeft)
    val valid = Fixture("x", "unknown", Bytes.empty, Bytes(Vector.fill(32)(0.toByte)))
    assert(Fixture.check(valid).isLeft)
  }
  test("tagless orchestration propagates read errors and mismatches") {
    type Result[A] = Either[Throwable, A]
    val fixture = Fixture.parse(Files.readString(fixturePath)).toOption.get.head
    val source = new FixtureSource[Result]:
      def load: Result[Vector[lab.Fixture]] = Right(Vector(fixture))
    val sink = new ReportSink[Result]:
      def write(checks: Vector[Check]): Result[Unit] =
        assertEquals(checks.size, 1)
        Right(())
    assertEquals(Verify.run(source, sink), Right(true))
    val mismatched = new FixtureSource[Result]:
      def load: Result[Vector[lab.Fixture]] =
        Right(Vector(fixture.copy(expected = Bytes(Vector.fill(32)(0.toByte)))))
    assertEquals(Verify.run(mismatched, sink), Right(false))
    val error = new IllegalStateException("read failure")
    val broken = new FixtureSource[Result]:
      def load: Result[Vector[lab.Fixture]] = Left(error)
    assertEquals(Verify.run(broken, sink), Left(error))
    val brokenSink = new ReportSink[Result]:
      def write(checks: Vector[Check]): Result[Unit] = Left(error)
    assertEquals(Verify.run(source, brokenSink), Left(error))
  }
