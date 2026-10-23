// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import cats.effect.{Async, Deferred, Ref, Sync}
import javax.management.ObjectName
import cats.syntax.all.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import java.time.Instant

import FetchCoordinator.SessionDiagnostic
import log.{NodeEvent, Render, Traced}

/** What the node records when [[StallWatchdog]] reports a stall, so that a stall that cannot be
  * reproduced on demand leaves its evidence (issue 371). Reading it is a handful of `Ref` reads and
  * queue sizes behind probes that never block, done once when the stall begins; nothing runs on the
  * block path for it beyond the two timestamps a session keeps per ChainSync reply batch.
  */
object StallDiagnostics:
  /** Every session's pipeline as the coordinator and the session see it, and the shared block
    * admissions.
    */
  private[node] final case class Report(
      sessions: Vector[SessionDiagnostic],
      admissions: BlockAdmissions.Occupancy
  )

  /** Where the report reads from. */
  private[node] final case class Sources[F[_]](
      coordinator: FetchCoordinator[F],
      admissions: BlockAdmissions[F]
  )

  /** The report of `sources` now. */
  private[node] def capture[F[_]: cats.Monad](sources: Sources[F]): F[Report] =
    (sources.coordinator.diagnostics, sources.admissions.occupancy).mapN(Report(_, _))

  /** What became of the fiber dump a stall asked for. */
  enum Dump:
    /** `--stall-dump` was not given. */
    case Off

    /** The dump is under way in a fiber of its own; the `stallDumped` event tells its end. */
    case Requested

    /** The dump is in this file. */
    case Written(path: Path)
    case Failed(error: DumpError)

  /** Why a fiber dump was not written. */
  enum DumpError:
    case Io(cause: IOException)

    /** The runtime registered no live-fiber snapshot trigger (JMX), so it has none to read. */
    case NoMonitor

    /** The runtime's fiber monitor failed; the watchdog must outlive it. */
    case Defect(cause: Throwable)

  /** The JMX name pattern `IORuntime` registers its live-fiber snapshot trigger under
    * (`cats.effect.unsafe.metrics.LiveFiberSnapshotTrigger`, `type=LiveFiberSnapshotTrigger-<n>`).
    */
  private val TriggerPattern = new ObjectName(
    "cats.effect.unsafe.metrics:type=LiveFiberSnapshotTrigger-*"
  )

  /** Write the runtime's live-fiber snapshot to `dir/fiber-dump-<epoch millis>-<number>.txt`: the
    * time keeps a restarted node (`--stall-exit-after`), whose count starts again, from overwriting
    * the dump of the run before it. It is the report `IOApp` prints on SIGUSR1, taken through the
    * supported public route: the `liveFiberSnapshot` operation of the runtime's
    * `LiveFiberSnapshotTriggerMBean` (cats-effect 3.7.1 keeps `IORuntime.fiberMonitor` private to
    * its package). The snapshot lists the fibers the runtime traces (`cats.effect.tracing.mode`,
    * `cached` by default). The write is blocking and happens on the blocking pool, once per stall.
    */
  def fiberDump[F[_]: Sync](dir: Path, number: Int): F[Dump] =
    fiberDump(dir, number, () => liveSnapshot)

  /** The lines of the runtime's live-fiber snapshot, or none when no trigger is registered. */
  private def liveSnapshot: Option[Array[String]] =
    val server = ManagementFactory.getPlatformMBeanServer
    server
      .queryNames(TriggerPattern, null)
      .asScala
      .headOption
      .map(trigger =>
        server
          .invoke(trigger, "liveFiberSnapshot", Array.empty[AnyRef], Array.empty[String])
          .asInstanceOf[Array[String]]
      )

  /** [[fiberDump]] over `snapshot`, which runs on the blocking pool: none stands for a runtime that
    * registered no trigger. The seam lets a test reach the failure cases a live runtime does not
    * produce on demand.
    */
  private[node] def fiberDump[F[_]: Sync](
      dir: Path,
      number: Int,
      snapshot: () => Option[Array[String]]
  ): F[Dump] =
    Sync[F].realTime.flatMap(now =>
      written(dir, s"fiber-dump-${now.toMillis}-$number.txt", snapshot)
    )

  /** Write the lines to `dir/name` (creating `dir`) on the blocking pool; none from `lines` is a
    * runtime with no snapshot source.
    */
  private def written[F[_]: Sync](
      dir: Path,
      name: String,
      lines: () => Option[Array[String]]
  ): F[Dump] =
    Sync[F]
      .blocking {
        lines() match
          case None => Dump.Failed(DumpError.NoMonitor)
          case Some(text) =>
            Files.createDirectories(dir)
            val file = dir.resolve(name)
            Files.write(file, text.toList.asJava, UTF_8)
            Dump.Written(file)
      }
      .recover {
        case io: IOException => Dump.Failed(DumpError.Io(io))
        case NonFatal(cause) => Dump.Failed(DumpError.Defect(cause))
      }

  /** Write the `stallState` line of `report` to `dir/stall-state-<epoch millis>-<number>.json`
    * directly, without the log: the evidence must not depend on the log's lock, which a wedged
    * writer may hold.
    */
  private[node] def stateFile[F[_]: Sync](dir: Path, number: Int, report: Report): F[Dump] =
    Sync[F].realTime.flatMap { now =>
      val line = Traced[NodeEvent].line(NodeEvent.StallState(report, Dump.Requested))
      val text = Render.json(line, Instant.ofEpochMilli(now.toMillis))
      written(dir, s"stall-state-${now.toMillis}-$number.json", () => Some(Array(text)))
    }

  /** What the node does when a stall begins, after the `stalled` event: read the report from the
    * sources (installed once the block pool exists; without them nothing is reported), then, each
    * in a fiber of its own that `background` owns so that nothing waits for a log lock or a hung
    * file or JMX operation: log the `stallState` event, and, when `dump` names a directory, write
    * the report and the fiber dump there and log the `stallDumped` event. The report is read once
    * per stall, before anything that can block.
    *
    * @param background
    *   Starts its argument in a fiber the caller owns and returns at once.
    * @param snapshot
    *   The live-fiber snapshot source.
    */
  private[node] final class Reporter[F[_]: Sync] private (
      sources: Ref[F, Option[Sources[F]]],
      dump: Option[Path],
      count: Ref[F, Int],
      log: NodeEvent => F[Unit],
      background: F[Unit] => F[Unit],
      snapshot: () => Option[Array[String]]
  ):
    /** Where the reports read from. */
    def install(from: Sources[F]): F[Unit] = sources.set(Some(from))

    /** `body` in the background, ending with a value whatever it does: a failing diagnostic must
      * not end the watchdog or the node.
      */
    private def owned(body: F[Unit]): F[Unit] = background(body.attempt.void)

    /** The report and, when asked for, the files of a stall that has just begun. */
    def onStall: F[Unit] =
      sources.get.flatMap(_.traverse_ { s =>
        capture(s).flatMap { report =>
          owned(log(NodeEvent.StallState(report, dump.fold(Dump.Off)(_ => Dump.Requested)))) *>
            dump.traverse_ { dir =>
              count.updateAndGet(_ + 1).flatMap { number =>
                owned(
                  for
                    state <- stateFile(dir, number, report)
                    fibers <- fiberDump(dir, number, snapshot)
                    _ <- log(NodeEvent.StallDumped(state, fibers))
                  yield ()
                )
              }
            }
        }
      })

  private[node] object Reporter:
    def create[F[_]: Sync](
        dump: Option[Path],
        log: NodeEvent => F[Unit],
        background: F[Unit] => F[Unit],
        snapshot: () => Option[Array[String]] = () => liveSnapshot
    ): F[Reporter[F]] =
      (Ref.of[F, Option[Sources[F]]](None), Ref.of[F, Int](0))
        .mapN(new Reporter(_, dump, _, log, background, snapshot))

  /** What the stall watchdog's reports need: `exit`, completed when a stall has lasted
    * `--stall-exit-after`, and the [[Reporter]] of the stall's state.
    */
  private[node] final case class Watch[F[_]](exit: Deferred[F, Unit], reporter: Reporter[F])

  private[node] object Watch:
    def create[F[_]: Async](
        dump: Option[Path],
        log: NodeEvent => F[Unit],
        background: F[Unit] => F[Unit]
    ): F[Watch[F]] =
      (Deferred[F, Unit], Reporter.create[F](dump, log, background)).mapN(Watch(_, _))
