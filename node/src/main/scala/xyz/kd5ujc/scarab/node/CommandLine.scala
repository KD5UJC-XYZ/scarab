// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Sync
import cats.syntax.all.*
import java.io.PrintStream
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.InvalidPathException
import xyz.kd5ujc.scarab.codec.DomainError

/** A name the node does not take, under the owner's rule for names of 2026-11-14 (#953): it refuses
  * to start rather than risk naming another file.
  */
enum NameError extends DomainError:
  /** `name` holds a character beyond ASCII, and the JVM's file-name encoding (`encoding`) is not
    * UTF-8. The rule takes such names only under a UTF-8 locale: under another, the JVM may have
    * decoded an argument's bytes into other characters, or may encode the name in bytes other than
    * its UTF-8 ones.
    */
  case NotAscii(name: String, encoding: String)

  /** `name` holds U+FFFD, the character the JVM decodes, and the node decodes its configuration
    * with, in place of bytes that are not valid: the name may stand for other bytes, so the node
    * cannot tell which file it names. One typed as U+FFFD is refused all the same.
    */
  case Replaced(name: String)

  /** The JVM refused to name a file `name`, for `reason` (`InvalidPathException`): a name that
    * holds NUL, or that its file-name encoding cannot encode for another cause.
    */
  case Invalid(name: String, reason: String)

  def message: String = this match
    case NotAscii(name, encoding) =>
      s"the name '$name' holds characters beyond ASCII, which the node takes only under a UTF-8 " +
        s"locale, and the JVM's file-name encoding is $encoding; use ASCII names, or run the node " +
        "under a UTF-8 locale (such as LANG=C.UTF-8)"
    case Replaced(name) =>
      s"the name '$name' holds U+FFFD, which stands where bytes could not be decoded, so the file " +
        "it names is not known; use a name in valid UTF-8 without U+FFFD"
    case Invalid(name, reason) => s"the JVM cannot name the file '$name' ($reason); rename it"

object NameError:
  /** Whether `encoding` names UTF-8; an encoding the JVM does not know is taken for another. */
  def utf8(encoding: String): Boolean =
    Either.catchNonFatal(Charset.forName(encoding)).toOption.contains(StandardCharsets.UTF_8)

  /** The first of `names` the rule refuses under the file-name encoding `encoding`: with `encoding`
    * UTF-8, one that holds U+FFFD; with any other, one beyond ASCII.
    */
  def among(names: List[String], encoding: String): Option[NameError] =
    val unicode = utf8(encoding)
    names.collectFirstSome(name =>
      if !unicode && name.exists(_ > '\u007f') then Some(NotAscii(name, encoding))
      else Option.when(name.contains('�'))(Replaced(name))
    )

  /** The JDK's refusal to name a file, which it raises where it cannot encode the name in its
    * file-name encoding (`encoding`) or the name holds NUL (`sun/nio/fs/UnixPath.java` 98-101 and
    * 125-133, jdk-25+36).
    */
  def of(refusal: InvalidPathException, encoding: String): NameError =
    if !utf8(encoding) && refusal.getInput.exists(_ > '\u007f') then
      NotAscii(refusal.getInput, encoding)
    else Invalid(refusal.getInput, refusal.getReason)

/** The command line taken only where the JVM decoded it exactly, and standard output and error as
  * UTF-8 whatever the locale: the owner's rule for names of 2026-11-14 (#953).
  *
  * The JVM decodes its arguments, and encodes the names of the files it opens, in the locale's
  * encoding (`sun.jnu.encoding`), fixed when it starts. Under a UTF-8 locale any valid UTF-8 comes
  * through exactly, and a byte that is not UTF-8 becomes U+FFFD; under `LANG=C` each byte beyond
  * ASCII becomes U+FFFD (JDK 25, scarab-jdk:local, 2026-11-14). An argument decoded that way names
  * another file, so the node refuses to start before it touches any file. Names are operational and
  * easily changed, so the reference's behaviour at other bytes and locales is not reproduced.
  */
object CommandLine:
  /** `args`, where [[NameError.among]] refuses none of them under `encoding`. */
  def exact(args: List[String], encoding: String): Either[NameError, List[String]] =
    NameError.among(args, encoding).toLeft(args)

  /** The JVM's file-name encoding (`sun.jnu.encoding`), or `unknown` where it names none. */
  def encoding[F[_]](using F: Sync[F]): F[String] =
    F.delay(Option(System.getProperty("sun.jnu.encoding")).getOrElse("unknown"))

  /** Standard output and error written as UTF-8 whatever the locale, where the stream's charset is
    * another: under `LANG=C` the JVM writes each character beyond ASCII as `?`. The reference's own
    * output under `LANG=C` stopped at such a character in a run of cardano-node 11.1.3 (`<stderr>:
    * commitBuffer: invalid argument (cannot encode character ...)`), a bug Scarab does not
    * reproduce (#953). The new stream encodes UTF-8 and writes its bytes to the stream it replaces,
    * which passes bytes through as they are, so wherever that one wrote, this one does.
    */
  def utf8Output[F[_]](using F: Sync[F]): F[Unit] =
    def encoded(stream: PrintStream) = PrintStream(stream, true, StandardCharsets.UTF_8)
    F.delay {
      if System.out.charset != StandardCharsets.UTF_8 then System.setOut(encoded(System.out))
      if System.err.charset != StandardCharsets.UTF_8 then System.setErr(encoded(System.err))
    }
