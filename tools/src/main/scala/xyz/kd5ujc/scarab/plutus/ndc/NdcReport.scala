// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus.ndc

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.{ExecutorService, Executors}
import scala.jdk.CollectionConverters.*
import scala.concurrent.ExecutionContext
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, PlutusVm}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget

/** The parts the reports of pragma-org's node-diversity-conformance scenarios share (schema version
  * 1): the header, the budget object, and the names and versions of a program's context.
  */
object NdcReport:
  /** The `implementation.name` of a report of `vm`: the node's default evaluator is the node. */
  def implementation(vm: PlutusVm): String = vm match
    case PlutusVm.Scalus => "Scarab"
    case PlutusVm.Scarab => "Scarab (--plutus-vm scarab)"

  def header(scenario: String, generated: Instant, implementation: Json): Vector[(String, Json)] =
    Vector(
      "version" -> Json.fromString("1"),
      "scenario" -> Json.fromString(scenario),
      "generated_at" -> Json.fromString(generated.toString),
      "implementation" -> implementation
    )

  def budget(spent: ExBudget): Json =
    Json.obj("cpu" -> Json.fromLong(spent.cpu.toLong), "mem" -> Json.fromLong(spent.memory.toLong))

  /** `V1`, `V2`, `V3`: how the scenarios name a ledger language. */
  def languageName(language: PlutusLanguage): String = s"V${language.id + 1}"

  def language(name: String): Option[PlutusLanguage] =
    PlutusLanguage.values.find(l => languageName(l) == name)

  /** `8.0`: how the scenarios write a protocol version. */
  def protocolName(major: Int): String = s"$major.0"

  /** The major version of a protocol version the scenarios write as `major.minor`. */
  def protocol(text: String): Option[Int] =
    text.split('.').toVector match
      case Vector(major, minor) => (major.toIntOption, minor.toIntOption).mapN((m, _) => m)
      case Vector(major)        => major.toIntOption
      case _                    => None

  /** The entries of `dir` that `keep` keeps, read before the listing is closed. */
  def entries(dir: Path)(keep: Path => Boolean): IO[Either[Throwable, Vector[Path]]] =
    Resource
      .fromAutoCloseable(IO.blocking(Files.list(dir)))
      .use(listing => IO.blocking(listing.iterator.asScala.filter(keep).toVector))
      .attempt

  def write(path: Path, report: Json): IO[Unit] =
    IO.blocking(Files.writeString(path, report.spaces2 + "\n", StandardCharsets.UTF_8)).void

  def now: IO[Instant] = IO.realTimeInstant

  /** Scalus's machine recurses on the term, so `ScalusEvaluator` runs it on threads of this stack
    * size (`ScalusEvaluator.StackSize`); the harness runs every evaluation on one such thread.
    */
  val StackSize: Long = 256L * 1024 * 1024

  /** A thread of [[StackSize]] to evaluate on, for as long as the resource is held. */
  val bigStack: Resource[IO, ExecutionContext] =
    Resource
      .make(IO {
        val executor: ExecutorService = Executors.newSingleThreadExecutor(task =>
          Thread
            .ofPlatform()
            .name("ndc-evaluator")
            .stackSize(StackSize)
            .daemon(true)
            .unstarted(task)
        )
        executor
      })(executor => IO(executor.shutdown()))
      .map(ExecutionContext.fromExecutorService(_))

  /** What the JVM reports of the machine it runs on: inside a container, the container's limits. */
  def machine: IO[Json] =
    IO.blocking {
      val cpu = Either
        .catchNonFatal(Files.readAllLines(Path.of("/proc/cpuinfo")))
        .toOption
        .flatMap(lines =>
          Vector.from(lines.toArray(Array.empty[String])).collectFirst {
            case line if line.startsWith("model name") => line.dropWhile(_ != ':').drop(1).trim
          }
        )
      val memory = ManagementFactory.getOperatingSystemMXBean match
        case os: com.sun.management.OperatingSystemMXBean => Some(os.getTotalMemorySize)
        case _                                            => None
      Json.obj(
        "os" -> Json.fromString(System.getProperty("os.name")),
        "os_version" -> Json.fromString(System.getProperty("os.version")),
        "architecture" -> Json.fromString(System.getProperty("os.arch")),
        "cpu" -> cpu.fold(Json.Null)(Json.fromString),
        "logical_cores" -> Json.fromInt(Runtime.getRuntime.availableProcessors),
        "memory_bytes" -> memory.fold(Json.Null)(Json.fromLong)
      )
    }
