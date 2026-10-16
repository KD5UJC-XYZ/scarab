// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import cats.Applicative
import cats.effect.{Async, Clock, Ref, Resource, Sync}
import cats.effect.std.Mutex
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
  */
object LogRouter:
  /** One sink: its format, where its text goes, and per kind (by ordinal) its threshold and its
    * sampling.
    */
  private final case class Route[F[_]](
      sink: Sink,
      format: Format,
      write: String => F[Unit],
      thresholds: Vector[Threshold],
      sampling: Vector[Option[(Scope, Long)]]
  ):
    def admits(line: Line): Boolean = thresholds(line.kind.ordinal).admits(line.severity)

  private def route[F[_]](
      settings: LogSettings,
      sink: Sink,
      format: Format,
      write: String => F[Unit]
  ): Route[F] =
    Route(
      sink,
      format,
      write,
      EventKind.values.toVector.map(settings.threshold(sink, _)),
      EventKind.values.toVector.map(settings.sampling(sink, _))
    )

  /** The log the settings describe, with its sinks open, and the libraries' SLF4J lines routed to
    * it while it is.
    */
  def resource[F[_]: Async](settings: LogSettings): Resource[F, Log[F]] =
    for
      routes <- settings.sinks.traverse { (sink, format) =>
        output[F](sink, settings.file).map(route(settings, sink, format, _))
      }
      mutex <- Resource.eval(Mutex[F])
      counts <- Resource.eval(Ref.of[F, Map[(Sink, Scope), Long]](Map.empty))
      log = of[F](routes, mutex, counts)
      _ <- LibraryLogs.install[F](
        log,
        severity => routes.exists(_.thresholds(EventKind.Slf4j.ordinal).admits(severity))
      )
    yield log

  private def of[F[_]: Sync](
      routes: List[Route[F]],
      mutex: Mutex[F],
      counts: Ref[F, Map[(Sink, Scope), Long]]
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

    def tell(line: Line): F[Unit] =
      routes.filter(_.admits(line)) match
        case Nil => Sync[F].unit
        case admitted =>
          mutex.lock.surround(Clock[F].realTimeInstant.flatMap { at =>
            val text = Rendered(line, at)
            admitted.traverse_(r => kept(r, line.kind).ifM(r.write(text(r.format)), Sync[F].unit))
          })

  /** The line in each format, rendered when first asked for. */
  private final class Rendered(line: Line, at: Instant):
    private lazy val json = Render.json(line, at)
    private lazy val human = Render.human(line, at)
    def apply(format: Format): String = format match
      case Format.Json  => json
      case Format.Human => human

  /** Where a sink writes. */
  private def output[F[_]: Sync](
      sink: Sink,
      file: Option[FileSink]
  ): Resource[F, String => F[Unit]] =
    sink match
      case Sink.Stdout => Resource.pure(text => Sync[F].blocking(System.out.println(text)))
      case Sink.Stderr => Resource.pure(text => Sync[F].blocking(System.err.println(text)))
      case Sink.File =>
        file.fold(Resource.pure[F, String => F[Unit]](_ => Sync[F].unit))(
          RotatingFile.resource[F](_).map(f => f.write)
        )

/** The log file of a [[FileSink]], rotated by size. Used under the router's lock only. */
private[log] final class RotatingFile[F[_]: Sync] private (
    file: FileSink,
    current: Ref[F, RotatingFile.Open]
):
  import RotatingFile.Open

  private def numbered(i: Int): Path = file.path.resolveSibling(s"${file.path.getFileName}.$i")

  /** `path.(keep-1)` to `path.keep`, ..., `path` to `path.1`, and a new `path`. */
  private def rotate(open: Open): F[Open] = Sync[F].blocking {
    open.stream.close()
    (file.keep - 1 to 1 by -1).foreach { i =>
      if Files.exists(numbered(i)) then
        Files.move(numbered(i), numbered(i + 1), StandardCopyOption.REPLACE_EXISTING)
    }
    Files.move(file.path, numbered(1), StandardCopyOption.REPLACE_EXISTING)
    Open(new FileOutputStream(file.path.toFile, false), 0L)
  }

  def write(text: String): F[Unit] =
    val bytes = (text + "\n").getBytes(UTF_8)
    current.get
      .flatMap(open =>
        if open.size > 0 && open.size + bytes.length > file.maxBytes then rotate(open)
        else Sync[F].pure(open)
      )
      .flatMap(open =>
        Sync[F].blocking(open.stream.write(bytes)).as(open.copy(size = open.size + bytes.length))
      )
      .flatMap(current.set)

object RotatingFile:
  private[log] final case class Open(stream: FileOutputStream, size: Long)

  /** The file, appended to; the file open at release is closed. */
  def resource[F[_]: Sync](file: FileSink): Resource[F, RotatingFile[F]] =
    Resource
      .make(
        Sync[F]
          .blocking {
            Option(file.path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
            val size = if Files.exists(file.path) then Files.size(file.path) else 0L
            Open(new FileOutputStream(file.path.toFile, true), size)
          }
          .flatMap(Ref.of[F, Open](_))
      )(_.get.flatMap(open => Sync[F].blocking(open.stream.close())))
      .map(new RotatingFile[F](file, _))
