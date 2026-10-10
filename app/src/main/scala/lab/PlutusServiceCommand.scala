// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import cats.syntax.all.*
import scala.util.control.NonFatal

/** Explicit bounded volatile service, separate from the expected-transaction diagnostic. */
object PlutusServiceCommand:
  val usage =
    "plutus-service --profile isolated-conway-pv9-plutus-v3-spend-v1 --initial DIRECTORY --manifest-sha256 HASH --port PORT --magic PRIVATE_MAGIC --output DIRECTORY --duration-seconds N(1..60) --max-blocks N(1..128); loopback only, testnet0, epoch0, 100ms slots, volatile, no restart or full ledger validation"
  private[lab] final case class Config(
      base: PlutusResearchCommand.Config,
      durationSeconds: Int,
      maxBlocks: Int
  )
  private[lab] def options(args: List[String]): Either[String, Config] =
    try
      val names = Set(
        "--profile",
        "--initial",
        "--manifest-sha256",
        "--port",
        "--magic",
        "--output",
        "--duration-seconds",
        "--max-blocks"
      )
      require(args.size == 16, "eight explicit options required")
      val pairs = args.grouped(2).map(xs => xs.head -> xs(1)).toVector
      require(
        pairs.map(_._1).toSet == names && pairs.map(_._1).distinct.size == 8,
        "exact options required"
      )
      val values = pairs.toMap
      def number(key: String, maximum: Int): Int =
        val value = values(key)
        require(value.matches("[1-9][0-9]{0,2}"), "canonical positive service bound required")
        val n = value.toInt
        require(n <= maximum, "service bound exceeded")
        n
      val duration = number("--duration-seconds", 60)
      val blocks = number("--max-blocks", 128)
      val baseArgs =
        List("--profile", "--initial", "--manifest-sha256", "--port", "--magic").flatMap(key =>
          List(key, values(key))
        ) ++ List("--exchange", values("--output"))
      PlutusResearchCommand.options(baseArgs).map(Config(_, duration, blocks))
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid configuration"))
  def run(args: List[String]): IO[ExitCode] = args match
    case List("--help") => IO.println(usage).as(ExitCode.Success)
    case _ =>
      options(args) match
        case Left(error)   => IO.println(s"PLUTUS_SERVICE_CONFIG: $error\n$usage").as(ExitCode(2))
        case Right(config) => PlutusServiceRuntime.run(config)
