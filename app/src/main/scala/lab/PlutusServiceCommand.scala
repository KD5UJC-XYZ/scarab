// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import cats.syntax.all.*
import scala.util.control.NonFatal
import java.nio.file.Path
import lab.cbor.Bytes

/** Explicit bounded volatile service, separate from the expected-transaction diagnostic. */
object PlutusServiceCommand:
  val usage =
    "plutus-service --profile isolated-conway-pv9-plutus-v3-spend-v1 --initial DIRECTORY --manifest-sha256 HASH --port PORT --magic PRIVATE_MAGIC --output DIRECTORY --duration-seconds N(1..60) --max-blocks N(1..128); loopback only, testnet0, epoch0, 100ms slots, volatile by default; opt-in --checkpoint-after N(1..8) and/or --restore-checkpoint FILE --restore-authority FILE --restore-authority-sha256 HASH require --store-id HASH --session-id HASH --generation N; bounded linear restart only, no crash durability or full ledger validation; separate --epoch-mode repeated-native-checked-jvm-v1 --native-likelihood-executable ABSOLUTE_FILE --native-likelihood-sha256 HASH permits duration<=600/max-blocks<=512 with JVM-computed, native-checked runtime-dependent research epochs; primary --epoch-mode repeated-jvm-v1 has no native executable options or runtime native process"
  private[lab] val RepeatedModeId = "repeated-native-checked-jvm-v1"
  private[lab] val JvmModeId = "repeated-jvm-v1"
  private[lab] val repeatedOptions =
    Set("--epoch-mode", "--native-likelihood-executable", "--native-likelihood-sha256")
  private[lab] enum Repeated:
    case Jvm
    case Native(path: Path, pin: Bytes)
    def id: String = this match
      case Jvm          => JvmModeId
      case Native(_, _) => RepeatedModeId
    def executable: Option[Path] = this match
      case Jvm             => None
      case Native(path, _) => Some(path)
    def executablePin: Option[Bytes] = this match
      case Jvm            => None
      case Native(_, pin) => Some(pin)
  private[lab] final case class Config(
      base: PlutusResearchCommand.Config,
      durationSeconds: Int,
      maxBlocks: Int,
      checkpoint: Option[PlutusServiceCheckpoint.Mode] = None,
      repeated: Option[Repeated] = None
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
      require(
        args.size >= 16 && args.size <= 36 && args.size % 2 == 0,
        "eight base options and complete opt-in groups required"
      )
      val pairs = args.grouped(2).map(xs => xs.head -> xs(1)).toVector
      require(
        names.subsetOf(pairs.map(_._1).toSet) && pairs
          .map(_._1)
          .forall(k =>
            names.contains(k) || PlutusServiceCheckpoint.optionNames.contains(k) || repeatedOptions
              .contains(k)
          ) && pairs.map(_._1).distinct.size == pairs.size,
        "exact options required"
      )
      val values = pairs.toMap
      def number(key: String, maximum: Int): Int =
        val value = values(key)
        require(value.matches("[1-9][0-9]{0,2}"), "canonical positive service bound required")
        val n = value.toInt
        require(n <= maximum, "service bound exceeded")
        n
      val suppliedRepeated = values.keySet.intersect(repeatedOptions)
      val repeated =
        if suppliedRepeated.isEmpty then None
        else
          require(suppliedRepeated.contains("--epoch-mode"), "explicit repeated mode required")
          values("--epoch-mode") match
            case JvmModeId =>
              require(
                suppliedRepeated == Set("--epoch-mode"),
                "pure JVM mode does not accept native executable options"
              )
              Some(Repeated.Jvm)
            case RepeatedModeId =>
              require(
                suppliedRepeated == repeatedOptions,
                "complete native checked mode group required"
              )
              val executable = Path.of(values("--native-likelihood-executable"))
              require(
                executable.isAbsolute && executable.normalize == executable,
                "native executable requires an absolute normalized path"
              )
              val pin = values("--native-likelihood-sha256")
              require(pin.matches("[0-9a-f]{64}"), "canonical native executable pin required")
              Some(Repeated.Native(executable, Bytes.fromHex(pin).toOption.get))
            case _ => throw new IllegalArgumentException("unsupported repeated epoch mode")
      val duration = number("--duration-seconds", if repeated.nonEmpty then 600 else 60)
      val blocks = number("--max-blocks", if repeated.nonEmpty then 512 else 128)
      val baseArgs =
        List("--profile", "--initial", "--manifest-sha256", "--port", "--magic").flatMap(key =>
          List(key, values(key))
        ) ++ List("--exchange", values("--output"))
      PlutusResearchCommand.options(baseArgs).flatMap { base =>
        PlutusServiceCheckpoint
          .options(values, blocks, base.exchange)
          .flatMap { mode =>
            if repeated.nonEmpty && mode.exists(_.checkpointAfter.nonEmpty) then
              Left(
                "repeated mode cannot export a composed checkpoint; use an early same-epoch checkpoint"
              )
            else if repeated.flatMap(_.executable).exists(_.startsWith(base.exchange)) then
              Left("native executable must be outside service output")
            else Right(Config(base, duration, blocks, mode, repeated))
          }
      }
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid configuration"))
  def run(args: List[String]): IO[ExitCode] = args match
    case List("--help") => IO.println(usage).as(ExitCode.Success)
    case _ =>
      options(args) match
        case Left(error)   => IO.println(s"PLUTUS_SERVICE_CONFIG: $error\n$usage").as(ExitCode(2))
        case Right(config) => PlutusServiceRuntime.run(config)
