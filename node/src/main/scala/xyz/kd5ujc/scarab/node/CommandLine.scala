// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Sync
import cats.syntax.all.*
import java.io.{IOException, PrintStream}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.DomainError

/** Why the command line cannot be read as UTF-8. */
enum CommandLineError extends DomainError:
  /** The JVM decoded an argument's bytes beyond ASCII in `encoding`, which is not UTF-8, and the
    * bytes could not be read again: no `/proc/self/cmdline`, an argument file before the
    * application's arguments, or arguments that are not its last ones after the main class.
    */
  case NotUtf8(encoding: String)

  def message: String = this match
    case NotUtf8(encoding) =>
      s"the command line holds bytes beyond ASCII that the JVM decoded as $encoding and that " +
        "cannot be read again as UTF-8; run the node under a UTF-8 locale"

/** The command line, and standard output and error, as UTF-8 whatever the locale (the owner's
  * decision on #953).
  *
  * The JVM decodes its arguments in the locale's encoding (`sun.jnu.encoding`), fixed when it
  * starts: under `LANG=C` each byte beyond ASCII arrives as U+FFFD. So when that encoding is not
  * UTF-8, Scarab reads its arguments' bytes again from `/proc/self/cmdline` (Linux) and decodes
  * them as UTF-8, a sequence that is not UTF-8 as U+FFFD. Where that is needed and cannot be done,
  * it refuses to start rather than use the damaged arguments.
  */
object CommandLine:
  /** `args`, as the JVM decoded them in `jnu`, decoded again as UTF-8 from `raw` (the process's
    * command line, each argument ended by NUL). The launcher's options are read as the launcher
    * reads them, to find its main class, JAR or module. The arguments after it are the
    * application's, used only where they are as many as `args`, decode in `jnu` to `args`, and
    * follow what `javaCommand` (`sun.java.command`) begins with.
    *
    * `None` where the application's arguments may not be those of `raw`: an argument file (`@file`)
    * anywhere before the main target, an option's value included; options from `JDK_JAVA_OPTIONS`
    * (`environment`), which `raw` does not hold; a launch with `--disable-@files`, or of a source
    * file (`--source`), which this does not follow; or anything else that does not match.
    */
  def recovered(
      args: List[String],
      jnu: Charset,
      javaCommand: String,
      environment: Option[String],
      raw: Array[Byte]
  ): Option[List[String]] =
    val entries = arguments(raw)
    val decoded = entries.map(String(_, jnu))
    val plain = environment.forall(_.isBlank) && !decoded.contains("--disable-@files")
    Option.when(plain)(decoded).flatMap(mainTarget(_, 1)).flatMap { (at, target) =>
      val application = entries.drop(at + 1)
      Option.when(
        application.length == args.length &&
          decoded.drop(at + 1) == args &&
          (javaCommand == target || javaCommand.startsWith(target + " "))
      )(application.map(String(_, StandardCharsets.UTF_8)))
    }

  /** The launcher's options that take the next argument as their value (`IsWhiteSpaceOption`,
    * OpenJDK jdk-25+36 `src/java.base/share/native/libjli/java.c` 680-746), but for `-m` and
    * `--module`, whose value is the main target.
    */
  private val withValue = Set(
    "-classpath",
    "-cp",
    "--class-path",
    "--describe-module",
    "-d",
    "--source",
    "--module-path",
    "-p",
    "--upgrade-module-path",
    "--add-modules",
    "--enable-native-access",
    "--limit-modules",
    "--add-exports",
    "--add-opens",
    "--add-reads",
    "--patch-module"
  )

  /** Where the launcher's main target is among the arguments of `launcher` from `at` on, and what
    * it is, as `ParseArguments` finds it (`java.c` 1180-1250): the first argument that is not an
    * option, or the value of `-m`, `--module` or `--module=`. `None` at an argument file, an
    * option's value included.
    */
  @scala.annotation.tailrec
  private def mainTarget(launcher: List[String], at: Int): Option[(Int, String)] =
    launcher.lift(at) match
      case None                                       => None
      case Some(argument) if argument.startsWith("@") => None
      case Some("-m" | "--module")                    => launcher.lift(at + 1).map(at + 1 -> _)
      case Some(argument) if argument.startsWith("--module=") =>
        Some(at -> argument.stripPrefix("--module="))
      case Some(argument) if withValue(argument) =>
        if launcher.lift(at + 1).exists(_.startsWith("@")) then None
        else mainTarget(launcher, at + 2)
      case Some(argument) if argument.startsWith("-") => mainTarget(launcher, at + 1)
      case Some(target)                               => Some(at -> target)

  /** The arguments as UTF-8: as the JVM decoded them where its encoding is UTF-8 or they are all
    * ASCII, otherwise [[recovered]], or [[CommandLineError.NotUtf8]] where that fails. Under an
    * encoding such as ISO-8859-1 a byte beyond ASCII decodes to another character, not U+FFFD, so
    * any character beyond ASCII asks for the bytes.
    */
  def utf8(
      args: List[String],
      jnu: Charset,
      javaCommand: String,
      environment: Option[String],
      raw: Option[Array[Byte]]
  ): Either[CommandLineError, List[String]] =
    if jnu == StandardCharsets.UTF_8 || args.forall(_.forall(_ < '\u0080')) then Right(args)
    else
      raw
        .flatMap(recovered(args, jnu, javaCommand, environment, _))
        .toRight(CommandLineError.NotUtf8(jnu.name))

  /** The arguments of a command line as `/proc/<pid>/cmdline` holds them, empty ones included. */
  private def arguments(raw: Array[Byte]): List[Array[Byte]] =
    val ends = raw.indices.filter(raw(_) == 0).toList
    (0 :: ends.map(_ + 1)).zip(ends).map((start, end) => raw.slice(start, end))

  /** [[utf8]] of this process's arguments. */
  def of[F[_]](args: List[String])(using F: Sync[F]): F[Either[CommandLineError, List[String]]] =
    F.blocking {
      val jnu = Either
        .catchNonFatal(Charset.forName(System.getProperty("sun.jnu.encoding")))
        .getOrElse(Charset.defaultCharset)
      val raw = Either.catchOnly[IOException](Files.readAllBytes(Path.of("/proc/self/cmdline")))
      utf8(
        args,
        jnu,
        Option(System.getProperty("sun.java.command")).getOrElse(""),
        Option(System.getenv("JDK_JAVA_OPTIONS")),
        raw.toOption
      )
    }

  /** Standard output and error written as UTF-8 whatever the locale, where the stream's charset is
    * another: under `LANG=C` the JVM writes each character beyond ASCII as `?`. The reference's own
    * output under `LANG=C` stopped at such a character in a run of cardano-node 11.1.3 (`<stderr>:
    * commitBuffer: invalid argument (cannot encode character ...)`), a bug Scarab does not
    * reproduce (#953). The new stream encodes UTF-8 and writes its bytes to the stream it replaces,
    * which passes bytes through as they are, so wherever that one wrote, this one does.
    */
  def utf8Output[F[_]](using F: Sync[F]): F[Unit] =
    def utf8(stream: PrintStream) = PrintStream(stream, true, StandardCharsets.UTF_8)
    F.delay {
      if System.out.charset != StandardCharsets.UTF_8 then System.setOut(utf8(System.out))
      if System.err.charset != StandardCharsets.UTF_8 then System.setErr(utf8(System.err))
    }
