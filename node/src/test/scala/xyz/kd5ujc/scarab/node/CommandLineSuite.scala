// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.charset.StandardCharsets.{ISO_8859_1, US_ASCII, UTF_8}
import munit.FunSuite

/** The command line decoded as UTF-8 whatever the locale (#953). */
class CommandLineSuite extends FunSuite:
  private def bytes(arguments: Seq[Array[Byte]]): Array[Byte] =
    arguments.flatMap(_.toList :+ 0.toByte).toArray

  private def raw(arguments: String*): Array[Byte] = bytes(arguments.map(_.getBytes(UTF_8)))

  /** What the JVM gives `main` under `LANG=C`: each byte beyond ASCII as U+FFFD, as seen in
    * scarab-jdk:local.
    */
  private def underC(argument: String): String = String(argument.getBytes(UTF_8), US_ASCII)

  private val main = "xyz.kd5ujc.scarab.node.Main"
  private val path = "/w/café.socket"
  private val jvm = List("java", "-cp", "scarab.jar", main)
  private val typed = List("follow", "--local-socket", path)
  private def command(args: List[String]) = (main :: args).mkString(" ")

  test("under a UTF-8 locale the JVM's arguments are kept as they are") {
    assertEquals(CommandLine.utf8(typed, UTF_8, command(typed), None, None), Right(typed))
  }

  test("under LANG=C an argument beyond ASCII is decoded again from its bytes, as UTF-8") {
    val asDecoded = typed.map(underC)
    assertEquals(asDecoded(2), "/w/caf��.socket")
    assertEquals(
      CommandLine.utf8(asDecoded, US_ASCII, command(asDecoded), None, Some(raw((jvm ++ typed)*))),
      Right(typed)
    )
  }

  test("an empty argument stays one, and the JVM's own options are not taken for arguments") {
    val args = List("follow", "", path)
    val asDecoded = args.map(underC)
    assertEquals(
      CommandLine.utf8(asDecoded, US_ASCII, command(asDecoded), None, Some(raw((jvm ++ args)*))),
      Right(args)
    )
  }

  // the reference keeps such a byte as a character standing for it (cardano-node 11.1.3,
  // `caf\56553`), a difference recorded on #953
  test("a byte that is not UTF-8 is decoded as U+FFFD, and the valid ones around it as UTF-8") {
    val argument = Array[Byte]('/', 'c', 'a', 'f', 0xe9.toByte) ++ "é".getBytes(UTF_8)
    val asDecoded = List("follow", String(argument, US_ASCII))
    assertEquals(asDecoded(1), "/caf���")
    val line = bytes(jvm.map(_.getBytes(UTF_8)) ++ List("follow".getBytes(UTF_8), argument))
    assertEquals(
      CommandLine.utf8(asDecoded, US_ASCII, command(asDecoded), None, Some(line)),
      Right(List("follow", "/caf�é"))
    )
  }

  // the arguments came from an argument file, so their bytes are not in the command line, though
  // the command line's last argument decodes to the same text (a case Codex's review gave)
  test("arguments that do not follow the main class are not taken from the command line") {
    val asDecoded = List(underC("@à"))
    val fromFile = raw("java", "@é")
    assertEquals(String("@é".getBytes(UTF_8), US_ASCII), asDecoded.head)
    assertEquals(
      CommandLine.utf8(asDecoded, US_ASCII, command(asDecoded), None, Some(fromFile)),
      Left(CommandLineError.NotUtf8("US-ASCII"))
    )
  }

  test("where the bytes cannot be read again, a damaged command line refuses to start") {
    val asDecoded = typed.map(underC)
    assertEquals(
      CommandLine.utf8(asDecoded, US_ASCII, command(asDecoded), None, None),
      Left(CommandLineError.NotUtf8("US-ASCII"))
    )
  }

  test("an ASCII command line is used as the JVM decoded it under any locale") {
    val ascii = List("follow", "--local-socket", "/w/node.socket")
    assertEquals(CommandLine.utf8(ascii, US_ASCII, command(ascii), None, None), Right(ascii))
  }

  // the classpath names the main class, the real main class and arguments come from an argument
  // file, and the command line's last argument decodes to the same text (a case Codex's review gave)
  test("an argument file before the main target refuses, though a classpath names the main class") {
    val asDecoded = List(underC("@\u00e0"))
    val line = raw("java", "-cp", main, "@\u00e9")
    assertEquals(
      CommandLine.utf8(asDecoded, US_ASCII, command(asDecoded), None, Some(line)),
      Left(CommandLineError.NotUtf8("US-ASCII"))
    )
  }

  // under ISO-8859-1 the JVM decodes the bytes of `é` as `Ã©`, with no U+FFFD
  test("under ISO-8859-1 an argument beyond ASCII is decoded again, or refused without its bytes") {
    val asDecoded = typed.map(argument => String(argument.getBytes(UTF_8), ISO_8859_1))
    assertEquals(asDecoded(2), "/w/caf\u00c3\u00a9.socket")
    assertEquals(
      CommandLine.utf8(asDecoded, ISO_8859_1, command(asDecoded), None, Some(raw((jvm ++ typed)*))),
      Right(typed)
    )
    assertEquals(
      CommandLine.utf8(asDecoded, ISO_8859_1, command(asDecoded), None, None),
      Left(CommandLineError.NotUtf8("ISO-8859-1"))
    )
  }

  test("the main target is found after options with values, -jar, and each form of --module") {
    val asDecoded = typed.map(underC)
    def check(launcher: List[String], target: String) = assertEquals(
      CommandLine.utf8(
        asDecoded,
        US_ASCII,
        (target :: asDecoded).mkString(" "),
        None,
        Some(raw((launcher ++ typed)*))
      ),
      Right(typed),
      launcher
    )
    check(
      List("java", "-Xmx3g", "-cp", "a.jar:b.jar", "--add-opens", "x/y=ALL-UNNAMED", main),
      main
    )
    check(List("java", "--enable-native-access=ALL-UNNAMED", "-jar", "scarab.jar"), "scarab.jar")
    check(List("java", "-m", "scarab/" + main), "scarab/" + main)
    check(List("java", "--module", "scarab/" + main), "scarab/" + main)
    check(List("java", "--module=scarab/" + main), "scarab/" + main)
  }

  // an empty argument file as an option's value moves where the launcher finds the main class, and
  // JDK_JAVA_OPTIONS can hold an option whose value is the command line's first argument (cases
  // Codex's review gave)
  test("an argument file as an option's value, or JDK_JAVA_OPTIONS, refuses the recovery") {
    val asDecoded = List(underC("@\u00e0"))
    assertEquals(
      CommandLine.utf8(
        asDecoded,
        US_ASCII,
        command(asDecoded),
        None,
        Some(raw("java", "-cp", "@empty", main, "@\u00e9"))
      ),
      Left(CommandLineError.NotUtf8("US-ASCII"))
    )
    val decoded = typed.map(underC)
    assertEquals(
      CommandLine
        .utf8(decoded, US_ASCII, command(decoded), Some("-cp"), Some(raw((jvm ++ typed)*))),
      Left(CommandLineError.NotUtf8("US-ASCII"))
    )
    assertEquals(
      CommandLine.utf8(decoded, US_ASCII, command(decoded), Some(" "), Some(raw((jvm ++ typed)*))),
      Right(typed)
    )
  }
