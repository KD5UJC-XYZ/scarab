// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{ExitCode, IO, Resource}
import cats.syntax.all.*
import java.net.{InetSocketAddress, StandardProtocolFamily}
import java.nio.channels.ServerSocketChannel
import java.nio.file.{Files, LinkOption, Path}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*

/** An IPv6 `--listen` address refuses the start before any socket is touched: the JDK cannot make
  * an IPv6 socket IPv6-only, as the reference makes every IPv6 socket of the node, so the node does
  * not listen on one (an approved deviation, issue 854; `IPV6_V6ONLY` through the foreign-function
  * interface is issue 859).
  */
class NodeAddressStartupSuite extends CatsEffectSuite:
  private val temporary = fs2.io.file.Files.forAsync[IO].tempDirectory.map(_.toNioPath)

  private def start(root: Path, listen: String): IO[(ExitCode, List[io.circe.Json])] =
    val log = root.resolve("node.log")
    for
      code <- Main.run(
        List(
          "follow",
          "--genesis-dir",
          "fixtures/eras/genesis",
          "--peer",
          "127.0.0.1:3001",
          "--listen",
          listen,
          "--db",
          root.resolve("db").toString,
          "--local-socket",
          root.resolve("node.socket").toString,
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

  test("an IPv6 listening address is refused before the stale socket, the database or a bind") {
    temporary.use { root =>
      val socket = root.resolve("node.socket")
      for
        _ <- IO.blocking(Files.writeString(socket, "left by an earlier run"))
        outcome <- start(root, "[::1]:0")
        stale <- IO.blocking(Files.exists(socket, LinkOption.NOFOLLOW_LINKS))
        database <- IO.blocking(Files.exists(root.resolve("db")))
      yield
        val (code, lines) = outcome
        assertEquals(code, ExitCode(1))
        val errors = lines.filter(_.hcursor.get[String]("event").toOption.contains("error"))
        assertEquals(
          errors.flatMap(_.hcursor.get[String]("message").toOption),
          List("config: --listen [::1]:0: IPV6_V6ONLY cannot be set on a JDK socket")
        )
        assert(stale, "the stale socket is not removed yet")
        assert(!database, "the database is not touched")
    }
  }

  test("a node-to-node listener that cannot bind stops the node with diffusionErrored and exit 1") {
    // a socket without SO_REUSEPORT holds the port, so the node's listener cannot bind to it
    val held = Resource.make(IO.blocking {
      val channel = ServerSocketChannel.open(StandardProtocolFamily.INET)
      channel.bind(new InetSocketAddress("127.0.0.1", 0))
    })(channel => IO.blocking(channel.close()))
    (temporary, held).tupled.use { (root, channel) =>
      val port = channel.getLocalAddress match
        case a: InetSocketAddress => a.getPort
        case other                => fail(s"not an IP address: $other")
      val log = root.resolve("node.log")
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
            "--listen",
            s"127.0.0.1:$port",
            "--log-stdout",
            "off",
            "--log-file",
            log.toString
          )
        )
        lines <- IO.blocking(Files.readAllLines(log).asScala.toList)
      yield
        val parsed = lines.flatMap(io.circe.parser.parse(_).toOption)
        val seen = parsed.flatMap(_.hcursor.get[String]("event").toOption)
        assertEquals(code, ExitCode(1))
        assertEquals(
          seen.filter(Set("diffusionErrored", "error", "fatal")),
          List("diffusionErrored")
        )
        assertEquals(
          parsed
            .find(_.hcursor.get[String]("event").toOption.contains("diffusionErrored"))
            .flatMap(_.hcursor.get[String]("error").toOption),
          Some("Network.Socket.bind: resource busy (Address in use)")
        )
    }
  }
