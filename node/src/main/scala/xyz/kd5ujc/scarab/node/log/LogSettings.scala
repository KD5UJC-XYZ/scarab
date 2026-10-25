// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.nio.file.Path
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}

/** Where the log goes. */
enum Sink:
  case Stdout, Stderr, File

/** How a sink writes a line: one JSON object (the default, what the scenarios parse), or text for a
  * person (trace-dispatcher's `MachineFormat` and `HumanFormatUncoloured`, hermod-tracing
  * `trace-dispatcher/src/Cardano/Logging/Types.hs` 420-423).
  */
enum Format:
  case Json, Human

/** What a setting applies to: everything, an area (`Chain`), or one kind of event
  * (`Chain.Extended`). A setting for a namespace applies below it unless a longer one overrides it,
  * as trace-dispatcher looks options up (`getOption` tries the namespace and then each of its
  * prefixes, longest first: `trace-dispatcher/src/Cardano/Logging/Configuration.hs` 420-425).
  */
enum Scope:
  case All
  case InArea(area: Area)
  case Of(kind: EventKind)

  def show: String = this match
    case All          => "*"
    case InArea(area) => area.toString
    case Of(kind)     => kind.namespace

object Scope:
  /** The scopes that apply to `kind`, the most specific first. */
  def of(kind: EventKind): List[Scope] = List(Of(kind), InArea(kind.area), All)

  /** `*`, an area or `Area.Kind`; anything else is not a namespace of this node. */
  def parse(text: String): Option[Scope] =
    Option
      .when(text == "*")(All)
      .orElse(Area.values.find(_.toString == text).map(InArea(_)))
      .orElse(EventKind.values.find(_.namespace == text).map(Of(_)))

/** The size-rotated log file: `path` grows to about `maxBytes`, then becomes `path.1` (the older
  * ones move to `path.2`, ...) and a new `path` starts; `keep` rotated files are kept. A restart
  * appends to `path`.
  */
final case class FileSink(path: Path, format: Format, maxBytes: Long, keep: Int)

/** The log configuration: the sinks, and per sink and namespace the least severity let through and
  * the sampling, one line in `n`.
  *
  * The defaults: every line of the node, as JSON, on stdout, and nothing else; the libraries' lines
  * (`Library`) from `Info` up. The error stream (stderr) lets through `Warning` and above unless
  * told otherwise. [[LogSettings.defaultThreshold]] says why.
  */
final case class LogSettings(
    stdout: Option[Format] = Some(Format.Json),
    stderr: Option[Format] = None,
    file: Option[FileSink] = None,
    severity: Map[(Sink, Scope), Threshold] = Map.empty,
    every: Map[(Sink, Scope), Long] = Map.empty
):
  /** The sinks that are on, with their format. */
  def sinks: List[(Sink, Format)] =
    stdout.map(Sink.Stdout -> _).toList ++ stderr.map(Sink.Stderr -> _) ++
      file.map(f => Sink.File -> f.format)

  /** The least severity `sink` lets through for `kind`. */
  def threshold(sink: Sink, kind: EventKind): Threshold =
    Scope
      .of(kind)
      .collectFirstSome(scope => severity.get(sink -> scope))
      .getOrElse(LogSettings.defaultThreshold(sink, kind.area))

  /** The sampling that applies to `kind` on `sink`: the scope that sets it (its lines share one
    * count) and `n`; none when every line goes through.
    */
  def sampling(sink: Sink, kind: EventKind): Option[(Scope, Long)] =
    Scope
      .of(kind)
      .collectFirstSome(scope => every.get(sink -> scope).map(scope -> _))
      .filter(_._2 > 1)

object LogSettings:
  val default: LogSettings = LogSettings()

  /** The least severity `sink` lets through for `area` when no `--log-severity` names a scope of
    * it: `Warning` on stderr; `Info` for the libraries' lines, whose `Debug` (a DNS resolver's
    * lookups, a native loader's search) is noise to an operator; `Debug` for the node's own. The
    * Haskell node's default configuration likewise sets its root at `Notice` and lowers chosen
    * namespaces only (cardano-node 11.1.3, `Cardano/Node/Tracing/DefaultTraceConfig.hs` 15-56). A
    * fallback, not a setting: any scope that covers the libraries' lines (`*`, `Library`,
    * `Library.Slf4j`, on every sink or on one) replaces it.
    */
  def defaultThreshold(sink: Sink, area: Area): Threshold = (sink, area) match
    case (Sink.Stderr, _)  => Threshold.AtLeast(Severity.Warning)
    case (_, Area.Library) => Threshold.AtLeast(Severity.Info)
    case _                 => Threshold.AtLeast(Severity.Debug)

  val DefaultFileBytes: Long = 100L * 1024 * 1024
  val DefaultFileKeep: Int = 10

  /** The flags that take one value. */
  val ValueFlags: Set[String] =
    Set("log-stdout", "log-stderr", "log-file", "log-file-format", "log-file-size", "log-file-keep")

  /** The flags that may repeat. */
  val RepeatedFlags: Set[String] = Set("log-severity", "log-every")

  val Usage: String =
    "[--log-stdout json|human|off] [--log-stderr json|human|off] [--log-file <path> " +
      "[--log-file-format json|human] [--log-file-size N[K|M|G]] [--log-file-keep N]] " +
      "[--log-severity [<sink>:]<namespace>=<Severity|Silence> ...] [--log-every [<sink>:]<namespace>=N ...]"

  private def sinkOf(text: String): Option[Sink] = Sink.values.find(_.toString.toLowerCase == text)
  private def formatOf(text: String): Option[Format] =
    Format.values.find(_.toString.toLowerCase == text)
  private def switch(text: String): Option[Option[Format]] =
    Option.when(text == "off")(None).orElse(formatOf(text).map(Some(_)))

  /** `N`, `NK`, `NM` or `NG` (powers of 1024), at least one kilobyte. */
  private[log] def bytes(text: String): Option[Long] =
    val unit = text.lastOption.collect {
      case 'K' => 1024L
      case 'M' => 1024L * 1024
      case 'G' => 1024L * 1024 * 1024
    }
    text
      .dropRight(if unit.isDefined then 1 else 0)
      .toLongOption
      .filter(n => n > 0 && n <= Long.MaxValue / unit.getOrElse(1L))
      .map(_ * unit.getOrElse(1L))
      .filter(_ >= 1024)

  /** `[<sink>:]<namespace>=<value>`: the sinks it names (all of them without a prefix), the scope
    * and the value.
    */
  private def rule[A](
      flag: String,
      text: String,
      value: String => Option[A]
  ): Either[ConfigError, List[((Sink, Scope), A)]] =
    def bad(problem: ConfigProblem) = ConfigError(problem)
    val (target, rest) = text.span(_ != '=')
    val (sinkText, namespace) =
      if target.contains(':') then
        val (s, n) = target.span(_ != ':')
        (Some(s), n.drop(1))
      else (None, target)
    for
      sinks <- sinkText.traverse(s => sinkOf(s).toRight(bad(ConfigProblem.LogSink(s))))
      scope <- Scope.parse(namespace).toRight(bad(ConfigProblem.LogNamespace(namespace)))
      a <- value(rest.drop(1)).toRight(bad(ConfigProblem.FlagValue(flag, rest.drop(1))))
    yield sinks.fold(Sink.values.toList)(List(_)).map(s => (s -> scope) -> a)

  /** The settings the log flags give, from the flags of the command line (`one`: the last value of
    * each flag; `many`: every value of a repeated flag).
    */
  def parse(
      one: Map[String, String],
      many: Map[String, List[String]]
  ): Either[ConfigError, LogSettings] =
    def value[A](flag: String, read: String => Option[A]): Either[ConfigError, Option[A]] =
      one.get(flag).traverse(t => read(t).toRight(ConfigError(ConfigProblem.FlagValue(flag, t))))
    for
      stdout <- value("log-stdout", switch)
      stderr <- value("log-stderr", switch)
      path = one.get("log-file").map(Path.of(_))
      format <- value("log-file-format", formatOf)
      size <- value("log-file-size", bytes)
      keep <- value("log-file-keep", _.toIntOption.filter(_ > 0))
      _ <- Either.cond(
        path.isDefined || !(format.isDefined || size.isDefined || keep.isDefined),
        (),
        ConfigError(
          ConfigProblem.FlagDependency(
            Set("log-file-format", "log-file-size", "log-file-keep"),
            "log-file"
          )
        )
      )
      severity <- many
        .getOrElse("log-severity", Nil)
        .flatTraverse(rule("log-severity", _, Threshold.parse))
      every <- many
        .getOrElse("log-every", Nil)
        .flatTraverse(rule("log-every", _, _.toLongOption.filter(_ > 0)))
      settings = LogSettings(
        stdout.getOrElse(Some(Format.Json)),
        stderr.flatten,
        path.map(
          FileSink(
            _,
            format.getOrElse(Format.Json),
            size.getOrElse(DefaultFileBytes),
            keep.getOrElse(DefaultFileKeep)
          )
        ),
        severity.toMap,
        every.toMap
      )
    yield settings
