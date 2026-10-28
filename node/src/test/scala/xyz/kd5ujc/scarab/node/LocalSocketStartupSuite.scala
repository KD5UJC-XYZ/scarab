// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{ExitCode, IO}
import java.nio.file.{Files, LinkOption, Path}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*

/** Where the start-up removes the stale node-to-client socket: once the configuration is read and
  * before the topology and the database, as the reference's `gatherConfiguredSockets` (cardano-node
  * 11.1.3, `cardano-node/src/Cardano/Node/Run.hs` 317-324), and how a removal that fails ends the
  * start: one `socketConfigError` line and exit code 1.
  */
class LocalSocketStartupSuite extends CatsEffectSuite:
  private val temporary = fs2.io.file.Files.forAsync[IO].tempDirectory.map(_.toNioPath)

  /** Start the node with `socket` and a topology that is not there; the exit code and the events.
    */
  private def start(root: Path, socket: Path): IO[(ExitCode, List[io.circe.Json])] =
    val log = root.resolve("node.log")
    for
      code <- Main.run(
        List(
          "follow",
          "--genesis-dir",
          "fixtures/eras/genesis",
          "--topology",
          root.resolve("absent-topology.json").toString,
          "--db",
          root.resolve("db").toString,
          "--local-socket",
          socket.toString,
          "--log-stdout",
          "off",
          "--log-file",
          log.toString
        )
      )
      lines <- IO.blocking(
        if Files.exists(log) then Files.readAllLines(log).asScala.toList else Nil
      )
    yield (code, lines.flatMap(io.circe.parser.parse(_).toOption))

  private def events(lines: List[io.circe.Json]): List[String] =
    lines.flatMap(_.hcursor.get[String]("event").toOption)

  test("a directory at the socket path refuses the start before the topology and the database") {
    temporary.use { root =>
      val socket = root.resolve("node.socket")
      for
        _ <- IO.blocking(Files.createDirectory(socket))
        outcome <- start(root, socket)
        database <- IO.blocking(Files.exists(root.resolve("db")))
      yield
        val (code, lines) = outcome
        assertEquals(code, ExitCode(1))
        assertEquals(
          events(lines).filter(Set("socketConfigError", "error")),
          List("socketConfigError")
        )
        assertEquals(
          lines
            .find(_.hcursor.get[String]("event").toOption.contains("socketConfigError"))
            .flatMap(_.hcursor.get[String]("error").toOption),
          Some(
            s"LocalSocketError \"$socket\" $socket: removeLink: inappropriate type (Is a directory)"
          )
        )
        assert(!database, "the database is not touched")
    }
  }

  test("a stale socket is removed even when the topology then refuses the start") {
    temporary.use { root =>
      val socket = root.resolve("node.socket")
      for
        _ <- IO.blocking(Files.writeString(socket, "left by an earlier run"))
        outcome <- start(root, socket)
        left <- IO.blocking(Files.exists(socket, LinkOption.NOFOLLOW_LINKS))
      yield
        val (code, lines) = outcome
        assertEquals(code, ExitCode(1))
        assertEquals(events(lines).filter(Set("socketConfigError", "error")), List("error"))
        assert(!left, "the stale socket went before the topology was read")
    }
  }

  test("a bind that fails stops the node with one diffusionErrored line and exit code 1") {
    temporary.use { root =>
      val log = root.resolve("node.log")
      val socket = root.resolve("absent").resolve("node.socket")
      for
        code <- Main.run(
          List(
            "follow",
            "--genesis-dir",
            "fixtures/eras/genesis",
            "--peer",
            "127.0.0.1:1",
            "--query-listen",
            "off",
            "--local-socket",
            socket.toString,
            "--log-stdout",
            "off",
            "--log-file",
            log.toString
          )
        )
        lines <- IO.blocking(Files.readAllLines(log).asScala.toList)
      yield
        val parsed = lines.flatMap(io.circe.parser.parse(_).toOption)
        val seen = events(parsed)
        assertEquals(code, ExitCode(1))
        assertEquals(
          seen.filter(Set("diffusionErrored", "error", "fatal")),
          List("diffusionErrored")
        )
        assertEquals(
          parsed
            .find(_.hcursor.get[String]("event").toOption.contains("diffusionErrored"))
            .flatMap(_.hcursor.get[String]("error").toOption),
          Some("Network.Socket.bind: does not exist (No such file or directory)")
        )
        // the set-up traced up to the bind, the trace before it written once it is known
        assertEquals(
          seen.filter(_.endsWith("LocalSocket")),
          List("createdLocalSocket", "configuringLocalSocket")
        )
    }
  }

  test("the forging credentials are checked and read before the stale socket is removed") {
    temporary.use { root =>
      val socket = root.resolve("node.socket")
      val vrf = root.resolve("vrf.skey")
      val log = root.resolve("node.log")
      def run(kes: Path) = Main.run(
        List(
          "follow",
          "--genesis-dir",
          "fixtures/eras/genesis",
          "--peer",
          "127.0.0.1:1",
          "--local-socket",
          socket.toString,
          "--forge",
          "--vrf-skey",
          vrf.toString,
          "--kes-skey",
          kes.toString,
          "--opcert",
          root.resolve("absent.opcert").toString,
          "--log-stdout",
          "off",
          "--log-file",
          log.toString
        )
      )
      def left = IO.blocking(Files.exists(socket, LinkOption.NOFOLLOW_LINKS))
      for
        _ <- IO.blocking(Files.writeString(socket, "left by an earlier run"))
        _ <- IO.blocking(Files.writeString(vrf, "{}"))
        // others may read the VRF key: the reference refuses before anything else
        _ <- IO.blocking(
          Files.setPosixFilePermissions(
            vrf,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
          )
        )
        refused <- run(root.resolve("absent.kes"))
        afterPermissions <- left
        _ <- IO.blocking(
          Files.setPosixFilePermissions(
            vrf,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
          )
        )
        // a credential that cannot be read refuses before the socket is touched as well
        unreadable <- run(root.resolve("absent.kes"))
        afterCredentials <- left
      yield
        assertEquals((refused, unreadable), (ExitCode(1), ExitCode(1)))
        assert(afterPermissions && afterCredentials, "the stale socket is still there")
    }
  }
