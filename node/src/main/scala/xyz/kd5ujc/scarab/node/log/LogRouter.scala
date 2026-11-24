// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.io.{FileOutputStream, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import cats.Applicative
import cats.data.EitherT
import scala.concurrent.duration.*
import cats.effect.{Async, Clock, Deferred, Fiber, Ref, Resource, Sync}
import cats.effect.std.{Mutex, Queue}
import cats.mtl.Tell
import cats.syntax.all.*

/** The interpreter of [[Log]]: each line goes to every sink whose settings let it through, in the
  * order the lines come, rendered once per format.
  *
  * Per sink and kind of event, what goes through is decided as trace-dispatcher decides it: the
  * least severity of the most specific namespace that sets one (a `Threshold`, or `Silence`), then,
  * if a namespace sets it, the sampling. The sampling keeps the first line of every `n` of the
  * namespace that sets it. It replaces trace-dispatcher's frequency limiter (hermod-tracing
  * `trace-dispatcher/src/Cardano/Logging/FrequencyLimiter.hs` 48-88: at most so many messages per
  * second, with a budget that starts and stops the limiting and messages that say so) by a count:
  * the lines kept do not depend on timing, so two runs of the same chain keep the same lines and a
  * test can say which, and on a sync, where the per-block `extended` lines come as fast as blocks
  * apply, one line in `n` is the rate a person can read whatever the speed.
  *
  * The writes are serialised (one lock for every sink), as `println` was: a line is whole, the
  * order is the same in every sink, and a line is written when `tell` returns, so the last lines of
  * a run (`shutdownSnapshot`, `finished`, `error`) are out before the process exits.
  *
  * A sink that cannot take a line (a full disk, a rotation that cannot move a file) drops it, and
  * `tell` returns as it would have: logging never stops or alters what logged. The failure is
  * reported outside the routes ([[SinkReport]]): the first of a streak with its cause, and the
  * number of lines dropped when the sink takes a line again or the log closes. The reports are
  * written by a fiber of their own, never under the lock: a report that cannot be written (stderr a
  * full pipe nobody reads) holds neither what logged nor the log's release.
  */
object LogRouter:
  /** One sink: its format, where its text goes, and per kind (by ordinal) its threshold and its
    * sampling.
    */
  private final case class Route[F[_]](
      sink: Sink,
      format: Format,
      write: String => F[Either[SinkError, Unit]],
      thresholds: Vector[Threshold],
      sampling: Vector[Option[(Scope, Long)]]
  ):
    def admits(line: Line): Boolean = thresholds(line.kind.ordinal).admits(line.severity)

  private def route[F[_]](
      settings: LogSettings,
      sink: Sink,
      format: Format,
      write: String => F[Either[SinkError, Unit]]
  ): Route[F] =
    Route(
      sink,
      format,
      write,
      EventKind.values.toVector.map(settings.threshold(sink, _)),
      EventKind.values.toVector.map(settings.sampling(sink, _))
    )

  /** The log the settings describe, with its sinks open, and the libraries' SLF4J lines routed to
    * it while it is. A sink's failures are reported on stderr.
    */
  def resource[F[_]: Async](settings: LogSettings): Resource[F, Log[F]] =
    reporting(settings, SinkReport.toStderr[F])

  /** The reports that wait to be written: more are lost, and counted. */
  private val ReportsQueued = 64

  /** How long the log's release waits for its last reports to be written. */
  private val ReportsAtRelease = 1.second

  /** [[resource]], with the reports of failing sinks given to `report`, which the release waits for
    * at most `limit`, and the file sink's file-system calls `calls`.
    */
  private[log] def reporting[F[_]: Async](
      settings: LogSettings,
      report: SinkReport => F[Unit],
      limit: FiniteDuration = ReportsAtRelease,
      calls: FileCalls = FileCalls.real
  ): Resource[F, Log[F]] =
    for
      outputs <- settings.sinks.traverse { (sink, format) =>
        output[F](sink, settings.file, calls).map((sink, format, _))
      }
      log <- routed[F](settings, outputs, report, ReportsQueued, limit)
      _ <- LibraryLogs.install[F](
        log,
        severity =>
          settings.sinks.exists((sink, _) =>
            settings.threshold(sink, EventKind.Slf4j).admits(severity)
          )
      )
    yield log

  /** The log over `outputs`, each a sink, its format and where its text goes; the reports of its
    * failing sinks go to `report` through [[SinkReports]] of `capacity`. When it closes, a sink
    * still failing is reported with the lines it dropped.
    */
  private[log] def routed[F[_]: Async](
      settings: LogSettings,
      outputs: List[(Sink, Format, String => F[Either[SinkError, Unit]])],
      report: SinkReport => F[Unit],
      capacity: Int,
      limit: FiniteDuration
  ): Resource[F, Log[F]] =
    for
      failing <- Resource.eval(Ref.of[F, Map[Sink, Long]](Map.empty))
      reports <- SinkReports.resource[F](
        report,
        capacity,
        limit,
        failing.get.map(_.toList.map(SinkReport.Closed(_, _)))
      )
      mutex <- Resource.eval(Mutex[F])
      counts <- Resource.eval(Ref.of[F, Map[(Sink, Scope), Long]](Map.empty))
    yield of[F](
      outputs.map((sink, format, write) => route(settings, sink, format, write)),
      mutex,
      counts,
      failing,
      reports.offer
    )

  /** The reports of failing sinks on their way to `write`, which one fiber of their own calls, one
    * report at a time. Offering one never waits: when `queue` is full it is lost and counted in
    * `lost`, and the count is written after the next report.
    */
  private final class SinkReports[F[_]: Async] private (
      queue: Queue[F, Option[SinkReport]],
      lost: Ref[F, Long],
      done: Deferred[F, Unit]
  ):
    def offer(report: SinkReport): F[Unit] =
      queue.tryOffer(Some(report)).flatMap(lost.update(_ + 1).unlessA(_))

    /** Each report to `write` until the end (`None`). The writer has no other place to say that it
      * failed, so a report it raises on is not written ([[SinkReport.toStderr]] does not raise: a
      * `PrintStream` keeps an error flag), and the fiber ends with a value.
      */
    private def drain(write: SinkReport => F[Unit]): F[Unit] =
      val lostSince =
        lost.getAndSet(0L).flatMap(n => write(SinkReport.Lost(n)).attempt.void.whenA(n > 0))
      queue.take.flatMap {
        case Some(report) => write(report).attempt.void *> lostSince *> drain(write)
        case None         => lostSince *> done.complete(()).void
      }

    /** The `last` reports and the end, waited for at most `limit`. The writer is then abandoned,
      * not waited for: a write blocked on stderr cannot be cancelled.
      */
    private def end(
        last: List[SinkReport],
        limit: FiniteDuration,
        writer: Fiber[F, Throwable, Unit]
    ): F[Unit] =
      Async[F]
        .race(
          last.traverse_(r => queue.offer(Some(r))) *> queue.offer(None) *> done.get,
          Async[F].sleep(limit)
        )
        .void *> Async[F].start(writer.cancel).void

  private object SinkReports:
    def resource[F[_]: Async](
        write: SinkReport => F[Unit],
        capacity: Int,
        limit: FiniteDuration,
        last: F[List[SinkReport]]
    ): Resource[F, SinkReports[F]] =
      for
        reports <- Resource.eval(
          (Queue.bounded[F, Option[SinkReport]](capacity), Ref.of[F, Long](0L), Deferred[F, Unit])
            .mapN(new SinkReports[F](_, _, _))
        )
        _ <- Resource.make(Async[F].start(reports.drain(write)))(writer =>
          last.flatMap(reports.end(_, limit, writer))
        )
      yield reports

  /** `failing` holds, per sink that failed its last line, the lines it has dropped since it last
    * took one.
    */
  private def of[F[_]: Sync](
      routes: List[Route[F]],
      mutex: Mutex[F],
      counts: Ref[F, Map[(Sink, Scope), Long]],
      failing: Ref[F, Map[Sink, Long]],
      report: SinkReport => F[Unit]
  ): Log[F] = new Tell[F, Line]:
    def functor = Applicative[F]

    /** Whether the sampling of `route` keeps this line of `kind`: the first of every `n`. */
    private def kept(route: Route[F], kind: EventKind): F[Boolean] =
      route.sampling(kind.ordinal) match
        case None => Sync[F].pure(true)
        case Some((scope, n)) =>
          counts.modify { seen =>
            val count = seen.getOrElse(route.sink -> scope, 0L)
            (seen.updated(route.sink -> scope, count + 1), count % n == 0)
          }

    /** What `sink` did with a line: a failure is counted, and reported if it starts a streak; a
      * line taken after a streak ends it, and the lines dropped are reported.
      */
    private def outcome(sink: Sink, written: Either[SinkError, Unit]): F[Unit] =
      written match
        case Right(()) =>
          failing
            .modify(streaks => (streaks - sink, streaks.get(sink)))
            .flatMap(_.traverse_(dropped => report(SinkReport.Recovered(sink, dropped))))
        case Left(error) =>
          failing
            .modify(streaks =>
              (streaks.updated(sink, streaks.getOrElse(sink, 0L) + 1), streaks.contains(sink))
            )
            .flatMap(report(SinkReport.Failed(sink, error)).unlessA(_))

    /** `text` written by `route`; an `IOException` its write raises rather than returns is its
      * failure too.
      */
    private def written(route: Route[F], text: String): F[Either[SinkError, Unit]] =
      route.write(text).recover { case cause: IOException => SinkError.Write(cause).asLeft }

    def tell(line: Line): F[Unit] =
      routes.filter(_.admits(line)) match
        case Nil => Sync[F].unit
        case admitted =>
          mutex.lock.surround(line.time.fold(Clock[F].realTimeInstant)(Sync[F].pure).flatMap { at =>
            val text = Rendered(line, at)
            admitted.traverse_(r =>
              kept(r, line.kind)
                .ifM(written(r, text(r.format)).flatMap(outcome(r.sink, _)), Sync[F].unit)
            )
          })

  /** The line in each format, rendered when first asked for. */
  private final class Rendered(line: Line, at: Instant):
    private lazy val json = Render.json(line, at)
    private lazy val human = Render.human(line, at)
    def apply(format: Format): String = format match
      case Format.Json  => json
      case Format.Human => human

  /** Where a sink writes. `println` does not fail: a `PrintStream` keeps an error flag instead. */
  private def output[F[_]: Sync](
      sink: Sink,
      file: Option[FileSink],
      calls: FileCalls
  ): Resource[F, String => F[Either[SinkError, Unit]]] =
    val taken = ().asRight[SinkError]
    sink match
      case Sink.Stdout =>
        Resource.pure(text => Sync[F].blocking(System.out.println(text)).as(taken))
      case Sink.Stderr =>
        Resource.pure(text => Sync[F].blocking(System.err.println(text)).as(taken))
      case Sink.File =>
        file.fold(Resource.pure[F, String => F[Either[SinkError, Unit]]](_ => Sync[F].pure(taken)))(
          RotatingFile.resource[F](_, calls).map(f => f.write)
        )

/** Why a sink did not take a line: the file operation that failed, and its cause. */
enum SinkError:
  /** The file could not be opened again after an earlier failure. */
  case Open(cause: IOException)

  /** The file could not be turned over: the close, a move, or the new file failed. */
  case Rotate(cause: IOException)

  /** The line could not be written. */
  case Write(cause: IOException)

  /** The log was closed: a line that comes after it is not written, and the file is not opened
    * again.
    */
  case Released

  def text: String = this match
    case Open(cause)   => s"could not open its file ($cause)"
    case Rotate(cause) => s"could not rotate its file ($cause)"
    case Write(cause)  => s"could not write a line ($cause)"
    case Released      => "was closed"

/** What the router says of a sink that fails, outside the routes. */
enum SinkReport:
  /** The first line `sink` could not take since it last took one, or since it opened. */
  case Failed(sink: Sink, error: SinkError)

  /** `sink` took a line again, after dropping `dropped`. */
  case Recovered(sink: Sink, dropped: Long)

  /** The log closed while `sink` was failing, after it dropped `dropped`. */
  case Closed(sink: Sink, dropped: Long)

  /** `lost` reports could not wait to be written: the ones before were not written yet. */
  case Lost(lost: Long)

  /** The text on stderr, as the node's start-up failures are written (`scarab: ...`). */
  def text: String =
    def name(sink: Sink) = sink.toString.toLowerCase
    this match
      case Failed(sink, error) =>
        s"scarab: the log's ${name(sink)} sink ${error.text}; its lines are dropped until it takes one again"
      case Recovered(sink, dropped) =>
        s"scarab: the log's ${name(sink)} sink takes lines again, after dropping $dropped"
      case Closed(sink, dropped) =>
        s"scarab: the log closed with its ${name(sink)} sink failing, after it dropped $dropped lines"
      case Lost(lost) =>
        s"scarab: $lost reports of the log's failing sinks were lost while earlier ones waited to be written"

object SinkReport:
  /** Each report on stderr, directly: not through the stderr sink, which may be off or the one
    * failing.
    */
  def toStderr[F[_]: Sync]: SinkReport => F[Unit] =
    report => Sync[F].blocking(System.err.println(report.text))

/** The log file of a [[FileSink]], rotated by size, through `calls`. Used under the router's lock
  * only.
  *
  * The file is held while a line is written: `current` is `Shut` until the line is in, so a failure
  * at any step (a write, a rotation that closed the file and could not move it) leaves it `Shut`,
  * never holding a closed stream, and the next line opens the file again; a full file then turns
  * over again. The release makes it `Released` for good: a write still under way when it comes (one
  * abandoned by a bounded last line) closes the stream it holds, and no line opens the file again.
  */
private[log] final class RotatingFile[F[_]: Sync] private (
    file: FileSink,
    calls: FileCalls,
    current: Ref[F, RotatingFile.State]
):
  import RotatingFile.{Open, Released, Shut}

  private def numbered(i: Int): Path = file.path.resolveSibling(s"${file.path.getFileName}.$i")

  /** A blocking file operation, its `IOException` as the sink's failure `as`. */
  private def io[A](as: IOException => SinkError)(body: => A): F[Either[SinkError, A]] =
    Sync[F].blocking(body).attemptNarrow[IOException].map(_.leftMap(as))

  /** The rotated files move up, from `path.1` to the first free `path.n` (to `path.keep`, the
    * oldest's place, when none is free), then `path` to `path.1`, and a new `path` starts. Only the
    * files below a free place move, so a rotation that failed after some moves takes up where it
    * stopped: `path.1` is free, and nothing moves again.
    */
  private def rotate(open: Open): F[Either[SinkError, Open]] = io(SinkError.Rotate(_)) {
    open.stream.close()
    val free = (1 to file.keep).find(i => !Files.exists(numbered(i))).getOrElse(file.keep)
    (free - 1 to 1 by -1).foreach(i => calls.move(numbered(i), numbered(i + 1)))
    calls.move(file.path, numbered(1))
    calls.open(file)
  }

  /** `bytes` appended. A stream that failed a write is closed, so the next line opens the file
    * again and reads its size; a failure of that close falls in the streak the write's failure
    * begins, which the router reports by its first failure.
    */
  private def append(open: Open, bytes: Array[Byte]): F[Either[SinkError, Open]] =
    io(SinkError.Write(_))(open.stream.write(bytes)).flatMap {
      case Right(()) =>
        Sync[F].pure(open.copy(size = open.size + bytes.length).asRight[SinkError])
      case Left(error) => io(SinkError.Write(_))(open.stream.close()).as(error.asLeft[Open])
    }

  /** `open` held again, or closed if the file was released while it was written. */
  private[log] def settle(open: Open): F[Either[SinkError, Unit]] =
    current
      .modify {
        case Released => (Released, true)
        case _        => (open, false)
      }
      .ifM(io(SinkError.Write(_))(open.stream.close()), Sync[F].pure(().asRight[SinkError]))

  def write(text: String): F[Either[SinkError, Unit]] =
    val bytes = (text + "\n").getBytes(UTF_8)
    def through(held: F[Either[SinkError, Open]]): F[Either[SinkError, Unit]] =
      (for
        open <- EitherT(held)
        ready <- EitherT(
          if open.size > 0 && open.size + bytes.length > file.maxBytes then rotate(open)
          else Sync[F].pure(open.asRight[SinkError])
        )
        written <- EitherT(append(ready, bytes))
        _ <- EitherT(settle(written))
      yield ()).value
    Sync[F].uncancelable { _ =>
      current.modify(state => (state.taken, state)).flatMap {
        case Released          => Sync[F].pure(SinkError.Released.asLeft[Unit])
        case Shut              => through(io(SinkError.Open(_))(calls.open(file)))
        case open @ Open(_, _) => through(Sync[F].pure(open.asRight[SinkError]))
      }
    }

/** The file-system calls of a [[RotatingFile]]: the real ones, or a test's. Each may throw an
  * `IOException`, which the file makes its failure.
  */
private[log] trait FileCalls:
  /** `from` moved to `to`, replacing it. */
  def move(from: Path, to: Path): Unit

  /** The file, appended to, with its directory made if it is missing. */
  def open(file: FileSink): RotatingFile.Open

private[log] object FileCalls:
  val real: FileCalls = new FileCalls:
    def move(from: Path, to: Path): Unit =
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
      ()

    def open(file: FileSink): RotatingFile.Open =
      Option(file.path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
      val size = if Files.exists(file.path) then Files.size(file.path) else 0L
      RotatingFile.Open(new FileOutputStream(file.path.toFile, true), size)

object RotatingFile:
  private[log] sealed trait State:
    /** What the file holds while a line is written: `Shut`, unless it was released. */
    def taken: State = this match
      case Released => Released
      case _        => Shut
  private[log] final case class Open(stream: FileOutputStream, size: Long) extends State
  private[log] case object Shut extends State
  private[log] case object Released extends State

  /** The file, appended to; the file open at release is closed, and is not opened again. */
  def resource[F[_]: Sync](
      file: FileSink,
      calls: FileCalls = FileCalls.real
  ): Resource[F, RotatingFile[F]] =
    Resource
      .make(Sync[F].blocking(calls.open(file)).flatMap(Ref.of[F, State](_))) {
        _.getAndSet(Released).flatMap {
          case Open(stream, _) => Sync[F].blocking(stream.close())
          case Shut | Released => Sync[F].unit
        }
      }
      .map(new RotatingFile[F](file, calls, _))
