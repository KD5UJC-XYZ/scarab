// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.ExitCode
import io.circe.{Decoder, Json, parser}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import munit.FunSuite
import scala.util.Using
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}

/** The start-up refusals of the unchanged reference node (#796).
  *
  * `fixtures/reference/genesis-startup/cases.jsonl` holds one case per line: the preview
  * configuration and genesis files with one change, the hash of each input file, and what
  * `cardano-node run` (11.1.3, `cardano-reference-11.1.3:local`) wrote to standard error and the
  * code it exited with. Each case is rebuilt from the preview files, its inputs checked against the
  * recorded hashes, and read by [[NodeSetup]] under the configuration path the reference was given;
  * the refusal is rendered as `Main` writes it, under the name the reference ran as, and compared
  * whole.
  */
class NativeGenesisStartupSuite extends FunSuite:
  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(e.message), identity)

  private val rows: Vector[Json] =
    Files
      .readString(Path.of("fixtures/reference/genesis-startup/cases.jsonl"))
      .linesIterator
      .map(parser.parse(_).fold(e => fail(e.message), identity))
      .toVector

  private val base = Path.of("fixtures/preview/genesis")
  private val files = Map(
    "byron" -> "byron-genesis.json",
    "shelley" -> "shelley-genesis.json",
    "alonzo" -> "alonzo-genesis.json",
    "conway" -> "conway-genesis.json"
  )
  private val hashKeys = Map(
    "byron" -> "ByronGenesisHash",
    "shelley" -> "ShelleyGenesisHash",
    "alonzo" -> "AlonzoGenesisHash",
    "conway" -> "ConwayGenesisHash"
  )

  /** Cases whose reference refusal [[NodeSetup]] does not yet give: the Byron genesis is read by
    * the node's own canonical JSON reader, which keeps its own refusal for bytes that are not
    * canonical JSON instead of canonical-json's Parsec message. An incomplete gate, not agreement.
    */
  private val incomplete = Set("byron/truncated")

  private def flag(row: Json, name: String): Boolean =
    row.hcursor.get[Boolean](name).getOrElse(false)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def edited(data: Array[Byte], edits: Vector[Vector[Json]]): Array[Byte] =
    edits.foldLeft(data) { (bytes, edit) =>
      def text(i: Int) = edit.lift(i).flatMap(_.asString).getOrElse(fail(s"edit $edit"))
      text(0) match
        case "truncate" =>
          bytes.take(edit.lift(1).flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0))
        case "append" => bytes ++ text(1).getBytes(UTF_8)
        case op =>
          val old = text(1).getBytes(UTF_8)
          val replacement =
            if op == "splice" then
              xyz.kd5ujc.scarab.codec.Bytes
                .fromHex(text(2))
                .fold(e => fail(e.toString), _.value.toArray)
            else text(2).getBytes(UTF_8)
          val at = bytes.indexOfSlice(old)
          assert(at >= 0, s"${text(1)} is not in the file")
          bytes.take(at) ++ replacement ++ bytes.drop(at + old.length)
    }

  /** The case's files, as `scripts/oracle/genesis-startup/run.py` writes them. */
  private def build(row: Json, directory: Path): Option[Path] =
    val era = row.hcursor.get[String]("era").toOption
    // a second file changed as well, its hash kept, for the order of reading
    val also = row.hcursor.downField("also")
    val alsoEra = also.get[String]("era").toOption
    val kept = era.filter(_ => flag(row, "keepHash")).flatMap(hashKeys.get).toSet ++
      alsoEra.flatMap(hashKeys.get)
    val dropped = (hashKeys.values.toVector :+ "CheckpointsFileHash").filterNot(kept)
    val config = Files
      .readString(base.resolve("config.json"))
      .split("\n", -1)
      .filterNot(line => dropped.exists(key => line.contains(s"\"$key\":")))
      .mkString("\n")
    (Vector("topology.json", "checkpoints.json") ++ files.values).foreach(name =>
      Files.copy(base.resolve(name), directory.resolve(name))
    )
    val target = era.map(e => directory.resolve(files(e)))
    val written = (era, target) match
      case (Some(e), Some(_)) if flag(row, "absent") =>
        val named = s"\"${e.capitalize}GenesisFile\": \"${files(e)}\""
        assert(config.contains(named))
        config.replace(named, s"\"${e.capitalize}GenesisFile\": \"absent-genesis.json\"")
      case (_, Some(file)) if flag(row, "directory") =>
        Files.delete(file)
        Files.createDirectory(file)
        config
      case (Some(e), Some(file)) if flag(row, "hidden") =>
        // the file in a directory without read or search permission, withheld after the hashes
        val named = s"\"${e.capitalize}GenesisFile\": \"${files(e)}\""
        assert(config.contains(named))
        val hidden = Files.createDirectory(directory.resolve("hidden"))
        Files.move(file, hidden.resolve(files(e)))
        config.replace(named, s"\"${e.capitalize}GenesisFile\": \"hidden/${files(e)}\"")
      case (_, Some(file)) =>
        val edits = row.hcursor.get[Vector[Vector[Json]]]("edits").getOrElse(Vector.empty)
        Files.write(file, edited(Files.readAllBytes(file), edits))
        config
      case _ => config
    alsoEra.foreach { e =>
      val file = directory.resolve(files(e))
      val edits = also.get[Vector[Vector[Json]]]("edits").getOrElse(Vector.empty)
      Files.write(file, edited(Files.readAllBytes(file), edits))
    }
    Files.writeString(directory.resolve("config.json"), written)
    target
      .filter(_ => flag(row, "unreadable"))
      .orElse(
        Option.when(flag(row, "hidden"))(directory.resolve("hidden"))
      )

  private def inputs(directory: Path): Map[String, String] =
    Using
      .resource(Files.list(directory))(_.toArray.toVector.collect { case p: Path => p })
      .filter(p => Files.isRegularFile(p) && Files.isReadable(p))
      .map(p => p.getFileName.toString -> sha256(Files.readAllBytes(p)))
      .toMap

  /** What `Main` writes for the refusal under the program name of `argv0`. */
  private def stderr(result: Either[ConfigError, NodeSetup], argv0: String): String =
    val program =
      ProgramName.of(s"$argv0\u0000run\u0000".getBytes(UTF_8)).getOrElse(fail("no name"))
    result match
      case Left(ConfigError(ConfigProblem.ProtocolStart(cause))) => cause.rendered(program)
      case Left(other) => s"not the reference's form: ${other.message}"
      case Right(_)    => "accepted"

  test("the fixture holds refusals of every era, of reading, hashing and decoding") {
    assertEquals(rows.size, 43)
    assertEquals(rows.flatMap(_.hcursor.get[String]("era").toOption).toSet, files.keySet)
    assert(rows.forall(r => field[Int](field[Json](r, "reference"), "exit") == 1))
  }

  private val scratch = FunFixture[Path](
    _ => Files.createTempDirectory("genesis-startup-796-"),
    NativeGenesisStartupSuite.remove
  )

  for row <- rows do
    val name = field[String](row, "name")
    scratch.test(s"$name: as the reference") { root =>
      val directory = Files.createDirectory(root.resolve("in"))
      val unreadable = build(row, directory)
      unreadable.foreach { path =>
        assert(path.toFile.setReadable(false, false))
        if Files.isDirectory(path) then assert(path.toFile.setExecutable(false, false))
      }
      assertEquals(inputs(directory), field[Map[String, String]](row, "inputs"))
      unreadable.foreach(path =>
        assume(!Files.isReadable(path), "this user reads files without read permission")
      )
      val argv0 = field[String](row, "argv0")
      val program = if argv0 == "cardano-node" then argv0 else s"/tmp/bin/$argv0"
      val named = if flag(row, "relativeConfig") then "config.json" else "/in/config.json"
      val result = NodeSetup.fromConfigNamed(directory.resolve("config.json"), named, None)
      val reference = field[Json](row, "reference")
      if incomplete(name) then
        assert(
          result.left.exists(e => !e.problem.isInstanceOf[ConfigProblem.ProtocolStart]),
          stderr(result, program)
        )
      else
        assertEquals(stderr(result, program), field[String](reference, "stderr"))
        result.left.foreach(e => assertEquals(Main.startupFailure(e)._2, ExitCode(1)))
    }

  test(
    "the program name is the last segment of argv[0], and there is none without a command line"
  ) {
    assertEquals(ProgramName.of("/usr/bin/java\u0000-jar\u0000".getBytes(UTF_8)), Some("java"))
    assertEquals(ProgramName.of("scarab".getBytes(UTF_8)), Some("scarab"))
    assertEquals(ProgramName.of("dir/\u0000".getBytes(UTF_8)), Some(""))
    assertEquals(ProgramName.of(Array.emptyByteArray), None)
  }

  test("takeDirectory and </> name a configured file as the reference does") {
    assertEquals(HaskellPath.takeDirectory("/in/config.json"), "/in")
    assertEquals(HaskellPath.takeDirectory("config.json"), ".")
    assertEquals(HaskellPath.takeDirectory("/config.json"), "/")
    assertEquals(HaskellPath.takeDirectory("a/b/"), "a/b")
    assertEquals(HaskellPath.takeDirectory("a//b"), "a")
    assertEquals(HaskellPath.combine(".", "shelley-genesis.json"), "./shelley-genesis.json")
    assertEquals(HaskellPath.combine("/in/", "x"), "/in/x")
    assertEquals(HaskellPath.combine("/in", "/abs/x"), "/abs/x")
  }

object NativeGenesisStartupSuite:
  /** Remove a scratch directory this suite made, its withheld file or directory readable again
    * first.
    */
  def remove(root: Path): Unit =
    withheld(root)
    val paths = Using.resource(Files.walk(root))(_.toArray.toVector.collect { case p: Path => p })
    paths.foreach { path =>
      path.toFile.setReadable(true, false)
      path.toFile.setExecutable(true, false)
    }
    paths.reverse.foreach(Files.deleteIfExists)

  /** A directory without search permission cannot be walked: open the case's own first. */
  private def withheld(root: Path): Unit =
    val hidden = root.resolve("in").resolve("hidden").toFile
    hidden.setReadable(true, false)
    hidden.setExecutable(true, false)
    ()
