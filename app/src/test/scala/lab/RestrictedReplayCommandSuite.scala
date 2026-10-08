// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.{Files, Path}
import lab.cbor.Bytes

class RestrictedReplayCommandSuite extends munit.FunSuite:
  private val packet =
    val local = Path.of("fixtures/restricted-replay")
    if Files.exists(local) then local else Path.of("../fixtures/restricted-replay")
  private def bytes(s: String) = Bytes.fromArray(s.getBytes(US_ASCII))
  private def raw(name: String): Bytes =
    Bytes.fromArray(Files.readAllBytes(packet.resolve(s"$name.trace.tsv")))
  private def text(name: String) = new String(raw(name).toArray, US_ASCII)

  for (name, stage) <- Vector("value-conservation" -> "balance", "missing-vkey" -> "coverage") do
    test(s"read-only $name trace derives its own outcome and retains state on rejection") {
      val input = raw(name)
      val parsed = RestrictedReplayCommand.parse(input).fold(fail(_), identity)
      val report = RestrictedReplayCommand.execute(parsed)
      val rendered = RestrictedReplayCommand.render(report).mkString("\n")
      assertEquals(report.observations.size, 2)
      assert(report.observations.head.outcome.isRight)
      assert(report.observations(1).outcome.isLeft)
      assertEquals(report.observations(1).beforeId, report.observations(1).afterId)
      assertEquals(report.state.feesSinceCheckpoint, BigInt(167041))
      assertEquals(report.state.revision.number, BigInt(1))
      assert(rendered.contains(s"outcome=Rejected:$stage"))
      assert(rendered.contains("tick=RecordedButNotExecuted"))
      assert(rendered.contains("fullLedger=false"))
      assert(rendered.contains("persistence=false"))
      assertEquals(RestrictedReplayCommand.exitCode(report), ExitCode.Error)
    }

  test("a syntactically valid changed transaction is not forced to match archive expectations") {
    val lines = text("value-conservation").linesIterator.toVector
    val altered = (Vector(lines.head, lines(2), lines(1)).mkString("\n") + "\n")
    val report =
      RestrictedReplayCommand.execute(RestrictedReplayCommand.parse(bytes(altered)).toOption.get)
    assert(report.observations.head.outcome.isLeft)
    assert(report.observations(1).outcome.isRight)
    assertEquals(report.state.revision.number, BigInt(1))
  }

  test("parser rejects expected-result fields, unsupported events and missing context") {
    val source = text("value-conservation")
    val lines = source.linesIterator.toVector
    Vector(
      "",
      source.trim,
      source + "\n",
      source.replace("3883681", "3883680"),
      source.replace("conway-pv9-transfer-projection-v1", "another-profile"),
      lines.head + "\n" + lines(1) + "\ttrue\n",
      lines.head + "\ntick\t1\n",
      source.replace("restricted-replay-v1", "restricted-replay-v2"),
      source.replace("\n", "\r\n"),
      (lines.head +: Vector.fill(65)(lines(1))).mkString("\n") + "\n"
    )
      .foreach(s => assert(RestrictedReplayCommand.parse(bytes(s)).isLeft, s.take(80)))
    assert(RestrictedReplayCommand.parse(Bytes(Vector(0xff.toByte))).isLeft)
  }

  test("empty trace after checkpoint is an explicit no-op") {
    val line = text("value-conservation").linesIterator.next()
    val report = RestrictedReplayCommand.execute(
      RestrictedReplayCommand.parse(bytes(line + "\n")).toOption.get
    )
    assertEquals(report.observations.size, 0)
    assertEquals(report.state.revision.number, BigInt(0))
    assertEquals(RestrictedReplayCommand.exitCode(report), ExitCode.Success)
  }

  test("bounded file reader observes exact trace bytes and rejects symlinks") {
    val temporary = Files.createTempDirectory("restricted-replay-reader-")
    val link = temporary.resolve("trace.tsv")
    Files.createSymbolicLink(link, packet.resolve("value-conservation.trace.tsv").toAbsolutePath)
    (for
      data <- RestrictedReplayCommand.read(packet.resolve("value-conservation.trace.tsv"))
      _ = assertEquals(data, raw("value-conservation"))
      blocked <- RestrictedReplayCommand.read(link).attempt
      _ = assert(blocked.isLeft)
    yield ())
      .guarantee(cats.effect.IO.blocking {
        Files.deleteIfExists(link)
        Files.deleteIfExists(temporary)
        ()
      })
      .unsafeToFuture()
  }

  test("CLI reports archived rejection as exit 1 and writes no checkpoint") {
    (for
      code <- RestrictedReplayCommand.run(
        List(packet.resolve("value-conservation.trace.tsv").toString)
      )
      _ = assertEquals(code, ExitCode.Error)
      help <- RestrictedReplayCommand.run(List("--help"))
      _ = assertEquals(help, ExitCode.Success)
      invalid <- RestrictedReplayCommand.run(List("--unknown"))
      _ = assertEquals(invalid, ExitCode(2))
    yield ()).unsafeToFuture()
  }
