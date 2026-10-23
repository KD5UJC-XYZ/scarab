// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import cats.effect.{Async, Concurrent, Deferred, Ref, Sync, Temporal}
import cats.effect.std.Supervisor
import javax.management.ObjectName
import cats.syntax.all.*
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import java.time.Instant

import FetchCoordinator.SessionDiagnostic
import log.{NodeEvent, Render, Traced}

/** What the node records when [[StallWatchdog]] reports a stall, so that a stall that cannot be
  * reproduced on demand leaves its evidence (issue 371). Reading it is a handful of `Ref` reads and
  * queue sizes behind probes that never block, done once when the stall begins. What the block path
  * pays for it is constant per item and takes no lock: a ChainSync reply writes three timestamps
  * (one `Ref` write each), a BlockFetch reservation three phase marks and the decision lock two
  * holder marks, an admission three marks (its publication wait, its work and its stage).
  */
object StallDiagnostics:
  /** Every session's pipeline as the coordinator and the session see it, and the shared block
    * admissions.
    */
  private[node] final case class Report(
      sessions: Vector[SessionDiagnostic],
      admissions: BlockAdmissions.Occupancy,
      decisionLock: Option[FetchCoordinator.LockHolder] = None
  )

  /** Where the report reads from. */
  private[node] final case class Sources[F[_]](
      coordinator: FetchCoordinator[F],
      admissions: BlockAdmissions[F]
  )

  /** The report of `sources` now. */
  private[node] def capture[F[_]: cats.Monad](sources: Sources[F]): F[Report] =
    (
      sources.coordinator.diagnostics,
      sources.admissions.occupancy,
      sources.coordinator.lockHolder
    ).mapN(Report(_, _, _))

  /** What became of the fiber dump a stall asked for. */
  enum Dump:
    /** `--stall-dump` was not given. */
    case Off

    /** The dump is under way in a fiber of its own; the `stallDumped` event tells its end. */
    case Requested

    /** A dump of an earlier stall is still under way, so this stall asked for none: dumps do not
      * pile up behind a hung operation.
      */
    case Skipped

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

  /** `background` for a [[Reporter]] under `supervisor`: the body runs in a fiber of its own that
    * the supervisor's finalization does not wait for. A dump blocked in JMX or a file operation
    * cannot be cancelled, so waiting for it would keep the node from ending; the supervised fiber
    * only joins it, which cancellation ends at once, and the abandoned fiber ends the process's
    * way, with the process.
    */
  def abandoning[F[_]: Concurrent](supervisor: Supervisor[F]): F[Unit] => F[Unit] =
    body => supervisor.supervise(Concurrent[F].start(body).flatMap(_.join).void).void

  /** The reporter's log: at most one job runs at a time and at most one waits, the newest, so a
    * wedged log holds one fiber however many stalls come. `dropped` counts the jobs replaced before
    * they ran.
    */
  private final case class LogSlot[F[_]](
      running: Boolean,
      pending: Option[F[Unit]],
      dropped: Long
  )

  /** What the node does when a stall begins: read the report from the sources (installed once the
    * block pool exists), then, in fibers that `background` starts so that nothing waits for a log
    * lock or a hung file or JMX operation: log the `first` line and the `stallState` event, and,
    * when `dump` names a directory, write the report and the fiber dump there and log the
    * `stallDumped` event. The report is read once per stall, before anything that can block. At
    * most one dump is under way, released when its files are written whatever becomes of its log
    * line: a stall that begins while an earlier dump is still running asks for none and says so.
    * Log jobs go through a single-slot queue that never waits ([[LogSlot]]).
    *
    * @param first
    *   The line to log before `stallState`, in the same fiber, so that the two keep their order.
    * @param background
    *   Starts its argument in a fiber and returns at once.
    * @param snapshot
    *   The live-fiber snapshot source.
    */
  private[node] final class Reporter[F[_]: Sync] private (
      sources: Ref[F, Option[Sources[F]]],
      dump: Option[Path],
      count: Ref[F, Int],
      dumping: Ref[F, Boolean],
      slot: Ref[F, LogSlot[F]],
      log: NodeEvent => F[Unit],
      background: F[Unit] => F[Unit],
      snapshot: () => Option[Array[String]]
  ):
    /** Where the reports read from. */
    def install(from: Sources[F]): F[Unit] = sources.set(Some(from))

    /** The log jobs replaced by a newer one before they ran, because the log was busy. */
    def dropped: F[Long] = slot.get.map(_.dropped)

    /** `body` in the background, ending with a value whatever it does: a failing diagnostic must
      * not end the watchdog or the node.
      */
    private def owned(body: F[Unit]): F[Unit] = background(body.attempt.void)

    /** Run `job` when the log is free, or keep it as the one waiting job, replacing (and counting)
      * the job that waited. Never waits.
      */
    private def logged(job: F[Unit]): F[Unit] =
      slot
        .modify { s =>
          if s.running then
            (s.copy(pending = Some(job), dropped = s.dropped + s.pending.size), false)
          else (s.copy(running = true), true)
        }
        .flatMap(start => owned(drain(job)).whenA(start))

    private def drain(first: F[Unit]): F[Unit] =
      Sync[F].tailRecM(first)(job =>
        job.attempt *>
          slot.modify { s =>
            s.pending match
              case Some(next) => (s.copy(pending = None), Left(next))
              case None       => (s.copy(running = false), Right(()))
          }
      )

    /** The report and, when asked for, the files of a stall that has just begun. */
    def onStall(first: NodeEvent): F[Unit] =
      sources.get.flatMap {
        case None => logged(log(first))
        case Some(s) =>
          capture(s).flatMap { report =>
            def state(shown: Dump): F[Unit] = log(first) *> log(NodeEvent.StallState(report, shown))
            dump match
              case None => logged(state(Dump.Off))
              case Some(dir) =>
                dumping.getAndSet(true).flatMap {
                  case true =>
                    logged(
                      state(Dump.Skipped) *> log(NodeEvent.StallDumped(Dump.Skipped, Dump.Skipped))
                    )
                  case false =>
                    logged(state(Dump.Requested)) *>
                      count.updateAndGet(_ + 1).flatMap { number =>
                        owned(
                          Sync[F]
                            .guarantee(
                              (
                                stateFile(dir, number, report),
                                fiberDump(dir, number, snapshot)
                              ).tupled,
                              dumping.set(false)
                            )
                            .flatMap((file, fibers) =>
                              logged(log(NodeEvent.StallDumped(file, fibers)))
                            )
                        )
                      }
                }
          }
      }

  private[node] object Reporter:
    def create[F[_]: Sync](
        dump: Option[Path],
        log: NodeEvent => F[Unit],
        background: F[Unit] => F[Unit],
        snapshot: () => Option[Array[String]] = () => liveSnapshot
    ): F[Reporter[F]] =
      (
        Ref.of[F, Option[Sources[F]]](None),
        Ref.of[F, Int](0),
        Ref.of[F, Boolean](false),
        Ref.of[F, LogSlot[F]](LogSlot(running = false, pending = None, dropped = 0L))
      ).mapN(new Reporter(_, dump, _, _, _, log, background, snapshot))

  /** Run `fa` in a fiber of its own and wait for it for at most `limit`; an `fa` that does not end
    * is abandoned, not cancelled (a blocked write cannot be). For a last log line on a path that
    * must end.
    */
  def bounded[F[_]: Temporal](limit: FiniteDuration)(fa: F[Unit]): F[Unit] =
    Temporal[F]
      .start(fa.attempt.void)
      .flatMap(fiber => Temporal[F].race(fiber.join, Temporal[F].sleep(limit)).void)

  /** What the stall watchdog's reports need: `exit`, completed when a stall has lasted
    * `--stall-exit-after`, the [[Reporter]] of the stall's state, and the `log` and `background`
    * with which [[react]] writes the watchdog's own lines.
    */
  private[node] final case class Watch[F[_]: Sync](
      exit: Deferred[F, Unit],
      reporter: Reporter[F],
      log: NodeEvent => F[Unit],
      background: F[Unit] => F[Unit]
  ):
    /** The watchdog's callback. It awaits no log call: the lines go to `background`, and `exit` is
      * completed before anything else, so that neither a wedged log nor a slow metric keeps a later
      * check, or the exit, from happening. `measured` is the non-blocking metrics hook.
      */
    def react(measured: StallWatchdog.Change => F[Unit])(change: StallWatchdog.Change): F[Unit] =
      change match
        case StallWatchdog.Change.Began(idle, ours, peers) =>
          measured(change) *> reporter.onStall(NodeEvent.Stalled(idle, ours, peers))
        case StallWatchdog.Change.Ended => measured(change)
        case StallWatchdog.Change.ExitDue(idle, ours, peers) =>
          exit.complete(()) *> measured(change) *>
            background(log(NodeEvent.StallExit(idle, ours, peers)).attempt.void)

  private[node] object Watch:
    def create[F[_]: Async](
        dump: Option[Path],
        log: NodeEvent => F[Unit],
        background: F[Unit] => F[Unit]
    ): F[Watch[F]] =
      (Deferred[F, Unit], Reporter.create[F](dump, log, background))
        .mapN(Watch(_, _, log, background))
