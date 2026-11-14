// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.charset.StandardCharsets.{ISO_8859_1, US_ASCII, UTF_8}
import java.nio.file.{Files, InvalidPathException, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The command line taken only where the JVM decoded it exactly, and the JDK's refusals of file
  * names (#953, the owner's decision of 2026-11-14). The decoded forms are those JDK 25 gave `main`
  * in scarab-jdk:local.
  */
class CommandLineSuite extends FunSuite:
  private val path = "/w/café.socket"
  private val typed = List("follow", "--local-socket", path)

  /** What the JVM gives `main` under `LANG=C` or `POSIX` (`ANSI_X3.4-1968`): each byte beyond ASCII
    * as U+FFFD.
    */
  private def underC(argument: String): String = String(argument.getBytes(UTF_8), US_ASCII)

  test("under a UTF-8 encoding valid UTF-8 is kept as the JVM decoded it") {
    assertEquals(CommandLine.exact(typed, "UTF-8"), Right(typed))
  }

  test("under a UTF-8 encoding a byte that is not UTF-8, which the JVM gives as U+FFFD, refuses") {
    val decoded = String(Array[Byte]('/', 'w', '/', 'c', 'a', 'f', 0xe9.toByte), UTF_8)
    assertEquals(decoded, "/w/caf�")
    assertEquals(
      CommandLine.exact(List("follow", "--db", decoded), "UTF-8"),
      Left(NameError.Replaced(decoded))
    )
  }

  test("under ANSI_X3.4-1968 an argument beyond ASCII refuses, naming the encoding") {
    val decoded = typed.map(underC)
    assertEquals(decoded(2), "/w/caf��.socket")
    assertEquals(
      CommandLine.exact(decoded, "ANSI_X3.4-1968"),
      Left(NameError.NotAscii(decoded(2), "ANSI_X3.4-1968"))
    )
  }

  test("under ISO-8859-1 an argument beyond ASCII refuses, though it holds no U+FFFD") {
    val decoded = String(path.getBytes(UTF_8), ISO_8859_1)
    assert(!decoded.contains('�'), decoded)
    assertEquals(
      CommandLine.exact(List("--config", decoded), "ISO-8859-1"),
      Left(NameError.NotAscii(decoded, "ISO-8859-1"))
    )
  }

  test("an ASCII command line, an empty argument included, is kept under any encoding") {
    val ascii = List("follow", "", "--local-socket", "/w/node.socket")
    for encoding <- List("UTF-8", "ANSI_X3.4-1968", "ISO-8859-1", "unknown") do
      assertEquals(CommandLine.exact(ascii, encoding), Right(ascii), encoding)
  }

  test("an encoding the JVM does not name, or does not know, is not taken for UTF-8") {
    assertEquals(
      CommandLine.exact(typed, "unknown"),
      Left(NameError.NotAscii(path, "unknown"))
    )
  }

  test("the first argument refused is the one reported") {
    val second = "/w/naïve.db"
    assertEquals(
      CommandLine.exact(List("--db", second, "--local-socket", path), "ANSI_X3.4-1968"),
      Left(NameError.NotAscii(second, "ANSI_X3.4-1968"))
    )
  }

  test("a file name the JDK cannot encode under an encoding other than UTF-8 is refused alike") {
    val refusal = InvalidPathException(
      "alonzo-é.json",
      "Malformed input or input contains unmappable characters"
    )
    assertEquals(
      NameError.of(refusal, "ANSI_X3.4-1968"),
      NameError.NotAscii("alonzo-é.json", "ANSI_X3.4-1968")
    )
  }

  test("any other refusal of a file name keeps the JDK's reason") {
    val nul = InvalidPathException("alonzo\u0000.json", "Nul character not allowed")
    assertEquals(
      NameError.of(nul, "ANSI_X3.4-1968"),
      NameError.Invalid("alonzo\u0000.json", "Nul character not allowed")
    )
    val surrogate = InvalidPathException(
      "alonzo-\ud800.json",
      "Malformed input or input contains unmappable characters"
    )
    assertEquals(
      NameError.of(surrogate, "UTF-8"),
      NameError.Invalid(
        "alonzo-\ud800.json",
        "Malformed input or input contains unmappable characters"
      )
    )
  }

  test("a configuration's file names: a byte that is not UTF-8 reads as U+FFFD and is refused") {
    val dir = Files.createTempDirectory("names-suite")
    val preview = Files.readAllBytes(Path.of("fixtures", "preview", "genesis", "config.json"))
    val database =
      "{\"DatabasePath\": \"/w/caf".getBytes(UTF_8) ++ Array(0xe9.toByte) ++ "\",".getBytes(UTF_8)
    Files.write(dir.resolve("config.json"), database ++ preview.dropWhile(_ != '{'.toByte).drop(1))
    val config = NodeSetup
      .readConfig(ReferenceBuild.Default)(dir.resolve("config.json"))
      .fold(e => fail(e.message), identity)
    assertEquals(config.settings.databasePath, Some("/w/caf\ufffd"))
    assertEquals(config.fileNames, config.files.all :+ "/w/caf\ufffd")
    assertEquals(
      NameError.among(config.fileNames, "UTF-8"),
      Some(NameError.Replaced("/w/caf\ufffd"))
    )
  }

  test(
    "a configuration's file names: beyond ASCII refused under ISO-8859-1, which can encode them"
  ) {
    val files = GenesisFiles.standard.copy(alonzo = "alonzo-é.json", checkpoints = Some("c.json"))
    assertEquals(files.all.last, "c.json")
    assertEquals(
      NameError.among(files.all, "ISO-8859-1"),
      Some(NameError.NotAscii("alonzo-é.json", "ISO-8859-1"))
    )
    assertEquals(NameError.among(files.all, "UTF-8"), None)
  }

  test("the refusals say what to do") {
    assertEquals(
      NameError.NotAscii("/w/caf\ufffd\ufffd", "ANSI_X3.4-1968").message,
      "the name '/w/caf\ufffd\ufffd' holds characters beyond ASCII, which the node takes only " +
        "under a UTF-8 locale, and the JVM's file-name encoding is ANSI_X3.4-1968; use ASCII " +
        "names, or run the node under a UTF-8 locale (such as LANG=C.UTF-8)"
    )
    assertEquals(
      NameError.Replaced("/w/caf\ufffd").message,
      "the name '/w/caf\ufffd' holds U+FFFD, which stands where bytes could not be decoded, so " +
        "the file it names is not known; use a name in valid UTF-8 without U+FFFD"
    )
    assertEquals(
      NameError.Invalid("a\u0000", "Nul character not allowed").message,
      "the JVM cannot name the file 'a\u0000' (Nul character not allowed); rename it"
    )
  }
