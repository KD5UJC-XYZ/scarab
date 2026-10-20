// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import scala.io.Source
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.KeepAlive

import Peers.*

/** Replays the exact finite message sequence observed by the pinned KeepAlive direct/connect/
  * channel properties through Scarab's production server and a real in-memory mux pair.
  *
  * The upstream generic client starts its Word16 cookie at n and counts down; Scarab's automatic
  * client counter is a separate policy and is intentionally not inferred from this server replay.
  */
class UpstreamKeepAliveBehaviorSuite extends FailsOnUncaught:
  private val fixture = "/upstream/ouroboros-network-protocol-codecs/keepalive-behavior.tsv"
  private val expectedSha256 = "c2b8176fed36e58e3b128bd33d061275dca0110eca6fc0fed49ddbcf9bd3b36e"

  private final case class Row(
      seed: Int,
      size: Int,
      n: Int,
      trace: Vector[BigInt],
      cookies: Vector[Int],
      direct: String,
      connect: String,
      channel: String
  )

  private def readRows(): Vector[Row] =
    val stream = Option(getClass.getResourceAsStream(fixture)).getOrElse(fail(s"missing $fixture"))
    val bytes = stream.readAllBytes()
    stream.close()
    val actualSha = MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
    assertEquals(actualSha, expectedSha256)
    val source = Source.fromString(new String(bytes, StandardCharsets.UTF_8))
    try
      source
        .getLines()
        .map { line =>
          val fields = line.split("\\t", -1).toVector
          assertEquals(fields.size, 10)
          assertEquals(fields(0), "keepalive-behavior-v1")
          val n = fields(4).toInt
          val trace = fields(5).split(",", -1).toVector.map(BigInt(_))
          val cookies = fields(6) match
            case ""     => Vector.empty
            case values => values.split(",").toVector.map(_.toInt)
          Row(fields(1).toInt, fields(3).toInt, n, trace, cookies, fields(7), fields(8), fields(9))
        }
        .toVector
    finally source.close()

  private def encoded(message: KeepAlive.Message): IO[Bytes] =
    KeepAlive
      .encode(KeepAlive.State.Client, KeepAlive.Role.Client, message)
      .fold(error => IO.raiseError(new IllegalStateException(error.message)), IO.pure)

  test("replay pinned KeepAlive direct/connect/channel message traces through Scarab's server"):
    val rows = readRows()
    assertEquals(rows.size, 24)
    rows.foreach { row =>
      assertEquals(row.direct, "pass", s"unchanged upstream direct property at seed ${row.seed}")
      assertEquals(row.connect, "pass", s"unchanged upstream connect property at seed ${row.seed}")
      assertEquals(
        row.channel,
        "pass",
        s"unchanged upstream channel ST property at seed ${row.seed}"
      )
      assert(row.n >= 0)
      assertEquals(
        row.trace.size,
        row.n + 1,
        s"complete generated function trace at seed ${row.seed}"
      )
      assertEquals(row.trace.head, BigInt(0))
      assertEquals(
        row.cookies,
        (1 to row.n).reverse.toVector,
        s"upstream Word16 cookie order at seed ${row.seed}"
      )
    }

    rows
      .traverse { row =>
        typed {
          linked(protocols = Set(8)).use { (client, server) =>
            for
              c <- client.channel(8)
              s <- server.channel(8, Mux.Role.Responder)
              result <- Resource.make(typed(KeepAliveServer.run(s)).start)(_.cancel).use {
                serving =>
                  for
                    observed <- row.cookies.traverse { value =>
                      for
                        cookie <- KeepAlive.Cookie
                          .from(value)
                          .fold(
                            error => IO.raiseError(new IllegalStateException(error.message)),
                            IO.pure
                          )
                        request <- encoded(KeepAlive.Message.Request(cookie))
                        _ <- c.send(request)
                        response <- c.recv(KeepAlive.UpstreamMessageBytes)(
                          KeepAlive.decodePrefix(KeepAlive.State.Server, KeepAlive.Role.Server, _)
                        )
                        _ <- IO(assertEquals(response, KeepAlive.Message.Response(cookie)))
                      yield response
                    }
                    done <- encoded(KeepAlive.Message.Done)
                    _ <- c.send(done)
                    serverResult <- serving.joinWithNever
                  yield (observed, serverResult)
              }
            yield result
          }
        }.map { outcome =>
          val (responses, serverResult) = outcome.fold(e => fail(s"seed ${row.seed}: $e"), identity)
          assertEquals(
            responses.size,
            row.n,
            s"production server response count at seed ${row.seed}"
          )
          assertEquals(
            serverResult,
            Right(()),
            s"server terminates on upstream Done at seed ${row.seed}"
          )
        }
      }
      .map(_ => assertEquals(rows.map(_.seed), Vector.range(490901, 490925)))
