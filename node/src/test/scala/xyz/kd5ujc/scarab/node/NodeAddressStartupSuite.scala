// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{ExitCode, IO}
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
