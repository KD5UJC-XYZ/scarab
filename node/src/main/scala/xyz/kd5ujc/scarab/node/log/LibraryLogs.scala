// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.util.concurrent.atomic.AtomicReference
import cats.effect.{Async, Resource, Sync}
import cats.effect.std.Dispatcher
import org.slf4j.{ILoggerFactory, IMarkerFactory, Logger, Marker}
import org.slf4j.event.Level
import org.slf4j.helpers.{BasicMarkerFactory, LegacyAbstractLogger, MessageFormatter, NOPMDCAdapter}
import org.slf4j.spi.{MDCAdapter, SLF4JServiceProvider}

/** The libraries' logs (http4s, fs2, through SLF4J) as [[NodeEvent.Library]] lines in the node's
  * log, namespace `Library.Slf4j`, with the SLF4J level as the severity.
  *
  * SLF4J finds its backend through a static `LoggerFactory`, so the route from the libraries to the
  * [[Log]] has to be reachable from static code: it is this one reference, which
  * [[LogRouter.resource]] sets while its log is open and clears after. It is the only global state
  * of the log; the node itself is given its `Log` through `using`. Without a route (before the log
  * is open, after it is closed, in a tool or a test that has none) a library's warnings and errors
  * go to stderr as text and the rest is dropped.
  */
object LibraryLogs:
  private final case class Route(enabled: Severity => Boolean, emit: NodeEvent => Unit)

  private val route = new AtomicReference[Option[Route]](None)

  /** Route the libraries' lines to `log` while the resource is open; `enabled` says which
    * severities any sink lets through, so that SLF4J does not format a line nobody keeps.
    */
  def install[F[_]: Async](log: Log[F], enabled: Severity => Boolean): Resource[F, Unit] =
    Dispatcher.sequential[F](await = true).flatMap { dispatcher =>
      Resource.make(
        Sync[F].delay(
          route.set(Some(Route(enabled, event => dispatcher.unsafeRunAndForget(log.emit(event)))))
        )
      )(_ => Sync[F].delay(route.set(None)))
    }

  private def fallback(severity: Severity): Boolean = severity.ordinal >= Severity.Warning.ordinal

  private[log] def enabled(severity: Severity): Boolean =
    route.get.fold(fallback(severity))(_.enabled(severity))

  private[log] def log(event: NodeEvent.Library): Unit =
    route.get match
      case Some(r) => r.emit(event)
      case None =>
        if fallback(event.severity) then
          System.err.println(s"[${event.logger}](${event.severity}) ${event.message}")

  def severity(level: Level): Severity = level match
    case Level.ERROR => Severity.Error
    case Level.WARN  => Severity.Warning
    case Level.INFO  => Severity.Info
    case Level.DEBUG => Severity.Debug
    case Level.TRACE => Severity.Debug

/** One SLF4J logger: a line per call, through [[LibraryLogs]]. */
private[log] final class LibraryLogger(loggerName: String) extends LegacyAbstractLogger:
  name = loggerName

  private def on(level: Level) = LibraryLogs.enabled(LibraryLogs.severity(level))
  def isTraceEnabled: Boolean = on(Level.TRACE)
  def isDebugEnabled: Boolean = on(Level.DEBUG)
  def isInfoEnabled: Boolean = on(Level.INFO)
  def isWarnEnabled: Boolean = on(Level.WARN)
  def isErrorEnabled: Boolean = on(Level.ERROR)

  protected def getFullyQualifiedCallerName: String = classOf[LibraryLogger].getName

  protected def handleNormalizedLoggingCall(
      level: Level,
      marker: Marker,
      pattern: String,
      arguments: Array[Object],
      thrown: Throwable
  ): Unit =
    LibraryLogs.log(
      NodeEvent.Library(
        loggerName,
        LibraryLogs.severity(level),
        MessageFormatter.basicArrayFormat(pattern, arguments),
        Option(thrown).map(_.toString)
      )
    )

/** The SLF4J 2 backend of the node, found by `ServiceLoader`
  * (`META-INF/services/org.slf4j.spi.SLF4JServiceProvider`).
  */
final class LibraryLogsProvider extends SLF4JServiceProvider:
  private val markers = new BasicMarkerFactory
  private val mdc = new NOPMDCAdapter
  private val loggers: ILoggerFactory = (name: String) => (new LibraryLogger(name): Logger)

  def getLoggerFactory: ILoggerFactory = loggers
  def getMarkerFactory: IMarkerFactory = markers
  def getMDCAdapter: MDCAdapter = mdc
  def getRequestedApiVersion: String = "2.0.99"
  def initialize(): Unit = ()
