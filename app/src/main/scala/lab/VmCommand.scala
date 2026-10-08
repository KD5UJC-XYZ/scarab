// SPDX-License-Identifier: Apache-2.0
package lab

import cats.MonadThrow
import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import lab.vm.*

trait VmFixtureSource[F[_]]:
  def load: F[(ReferenceParameters, Vector[VectorInput])]
trait VmReportSink[F[_]]:
  def write(checks: Vector[ConformanceCheck]): F[Unit]
object VerifyVm:
  def run[F[_]: MonadThrow](source: VmFixtureSource[F], sink: VmReportSink[F]): F[Boolean] =
    for
      loaded <- source.load
      (parameters, fixtures) = loaded
      evaluator = new Evaluator(parameters)
      checks <- fixtures.traverse(f =>
        Conformance
          .check(f, evaluator)
          .leftMap(new IllegalArgumentException(_))
          .liftTo[F]
      )
      _ <- sink.write(checks)
    yield checks.nonEmpty && checks.forall(_.matched)

object VmCommand:
  private val hashes = FixtureRegistry.hashes
  val names = FixtureRegistry.names
  def source(directory: Path): VmFixtureSource[IO] = new VmFixtureSource[IO]:
    def load: IO[(ReferenceParameters, Vector[VectorInput])] = IO.blocking(loadFiles(directory))

  private[lab] def loadFiles(directory: Path): (ReferenceParameters, Vector[VectorInput]) = {
    def resource(name: String): String =
      val stream = Option(getClass.getResourceAsStream(s"/plutus-reference-e/$name"))
        .getOrElse(throw new IllegalStateException(s"missing reference resource: $name"))
      try new String(stream.readAllBytes(), UTF_8)
      finally stream.close()
    val parameters = ReferenceParameters
      .parse(resource("cekMachineCostsE.json"), resource("builtinCostModelE.json"))
      .fold(e => throw new IllegalArgumentException(e), identity)
    def file(name: String): String =
      val path = directory.resolve(name)
      if !Files.isRegularFile(path) then
        throw new IllegalArgumentException(s"not a regular fixture file: $name")
      val stream = Files.newInputStream(path)
      val bytes =
        try stream.readNBytes(65537)
        finally stream.close()
      if bytes.length > 65536 then throw new IllegalArgumentException(s"fixture too large: $name")
      val hash = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .map(b => f"${b & 0xff}%02x")
        .mkString
      if !hashes.get(name).contains(hash) then
        throw new IllegalArgumentException(s"unregistered fixture bytes: $name")
      new String(bytes, UTF_8)
    val fixtures = names.map(name =>
      VectorInput(
        name,
        file(s"$name.uplc"),
        file(s"$name.uplc.expected"),
        file(s"$name.uplc.budget.expected")
      )
    )
    (parameters, fixtures)
  }

  def run(directory: Path): IO[ExitCode] =
    val sink = new VmReportSink[IO]:
      def write(checks: Vector[ConformanceCheck]): IO[Unit] =
        checks.traverse_(c =>
          IO.println(s"${if c.matched then "PASS" else "FAIL"}\t${c.name}\t${c.detail}")
        ) *>
          IO.println(
            s"${checks.count(_.matched)}/${checks.size} pinned evaluator vectors matched. Reference E costs, Plutus 1.63 corpus; no ledger validation or live oracle."
          )
    VerifyVm
      .run(source(directory), sink)
      .map(ok => if ok then ExitCode.Success else ExitCode.Error)
      .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
