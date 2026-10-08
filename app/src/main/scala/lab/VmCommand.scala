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
  private val hashes: Map[String, String] = Map(
    "addInteger-01.uplc" -> "b8300e6cb277ff498dd80ade14ae9b29c32f542ef937905c2a62fafad232131e",
    "addInteger-01.uplc.budget.expected" -> "b5a167874a39f3cd8c2d1e7e398b4ede7373ccd93fc579a85361a2480fd6c349",
    "addInteger-01.uplc.expected" -> "7d35a9a740f4e5664f41b3933287e4feab8eb7a7a8f770d761e314a6e1214f94",
    "addInteger-02.uplc" -> "11b68ddf2da072aea052da5acca4c6724db8a52796f26c9e4b4d31ea41635028",
    "addInteger-02.uplc.budget.expected" -> "f1441bb9194ae981f8cf3c72dfbd47caa30a12818970d403ce447a82d57b6901",
    "addInteger-02.uplc.expected" -> "e4e07510de79300ec2cfacc7249ec7db488ac62989c884ac8fdb1a84a335de93",
    "addInteger-uncurried.uplc" -> "81e69608d46a6c63c9a62172969d5a8d0cefba40fa311b19ef33e574cc9d742d",
    "addInteger-uncurried.uplc.budget.expected" -> "b5a167874a39f3cd8c2d1e7e398b4ede7373ccd93fc579a85361a2480fd6c349",
    "addInteger-uncurried.uplc.expected" -> "5266485e73b95c6d69ecb4bf62d187c31a35b149d6ae83dac75343f2d9468063",
    "divideInteger-neg-pos.uplc" -> "6a267026649eecca47aebcc5c00885362918addf02a5995789d954adbfeff934",
    "divideInteger-neg-pos.uplc.budget.expected" -> "93a2a5d7822d522505890ff2ff03a6cc5de72ab25c435fa58343ee8cddf30c8e",
    "divideInteger-neg-pos.uplc.expected" -> "bf7b2f9fcd13bd77003c6bde3ae85fadd5f263ee794e481c7a4dbfca472bc2fc",
    "divideInteger-zero.uplc" -> "aef5150da8bf1291729c23734ac5663cdab82eaea75fe0470d3bfb14d68293bd",
    "divideInteger-zero.uplc.budget.expected" -> "6e65f86795277da87aed702f8927a83314ad4b846fc1f8e833cb563c5879b7b6",
    "divideInteger-zero.uplc.expected" -> "6e65f86795277da87aed702f8927a83314ad4b846fc1f8e833cb563c5879b7b6"
  )
  val names = Vector(
    "addInteger-01",
    "addInteger-02",
    "addInteger-uncurried",
    "divideInteger-neg-pos",
    "divideInteger-zero"
  )
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
