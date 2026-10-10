// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import cats.syntax.all.*
import java.nio.file.Path
import lab.submission.AdmissionProfile
import scala.util.control.NonFatal

/** Explicit opt-in to the bounded, externally orchestrated research diagnostic. */
object PlutusResearchCommand:
  val usage =
    "plutus-research --profile isolated-conway-pv9-plutus-v3-spend-v1 --initial DIRECTORY --manifest-sha256 HASH --port PORT --magic PRIVATE_MAGIC --exchange DIRECTORY; loopback only, testnet0, epoch0, 100ms slots, external descriptor/client/endpoint protocol required; volatile, not full ledger validation"
  private[lab] final case class Config(
      initial: Path,
      manifest: String,
      port: Int,
      magic: Long,
      exchange: Path
  ):
    def legacyArgs: List[String] =
      List(initial.toString, manifest, port.toString, magic.toString, exchange.toString)
  private[lab] def options(args: List[String]): Either[String, Config] =
    try
      val names =
        Set("--profile", "--initial", "--manifest-sha256", "--port", "--magic", "--exchange")
      require(args.size == 12, "six explicit options required")
      val pairs = args.grouped(2).map(xs => xs.head -> xs(1)).toVector
      require(
        pairs.map(_._1).toSet == names && pairs.map(_._1).distinct.size == 6,
        "exact options required"
      )
      val values = pairs.toMap
      require(
        values("--profile") == AdmissionProfile.PlutusV3.id,
        "explicit restricted Plutus profile required"
      )
      require(
        values("--manifest-sha256").matches("[0-9a-f]{64}"),
        "canonical independent manifest SHA256 required"
      )
      require(
        values("--port").matches("[1-9][0-9]{0,4}") && values("--magic").matches("[1-9][0-9]{0,9}"),
        "canonical port and private magic required"
      )
      val port = values("--port").toInt
      val magic = values("--magic").toLong
      require(
        port <= 65535 && magic <= 0xffffffffL && !Set(764824073L, 1L, 2L).contains(magic),
        "bounded isolated-network port/magic required"
      )
      def path(key: String): Path =
        val p = Path.of(values(key))
        require(p.isAbsolute && p.normalize == p, "absolute normalized directory required")
        p
      val initial = path("--initial")
      val exchange = path("--exchange")
      require(initial != exchange, "initial packet and exchange directories must differ")
      Right(Config(initial, values("--manifest-sha256"), port, magic, exchange))
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid configuration"))

  def run(args: List[String]): IO[ExitCode] = args match
    case List("--help") => IO.println(usage).as(ExitCode.Success)
    case _ =>
      options(args) match
        case Left(error)   => IO.println(s"PLUTUS_RESEARCH_CONFIG: $error\n$usage").as(ExitCode(2))
        case Right(config) => PlutusResearchRuntime.run(config.legacyArgs)
