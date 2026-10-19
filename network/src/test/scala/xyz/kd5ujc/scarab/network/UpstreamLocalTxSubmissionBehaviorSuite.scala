// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import scala.io.Source
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.Peers.*
import xyz.kd5ujc.scarab.protocol.LocalTxSubmission
import xyz.kd5ujc.scarab.protocol.ChainSync.Role

/** Replays the original generic LocalTxSubmission direct/connect/channel property inputs through
  * Scarab's production server. Its Tx Any and Reject Int are opaque serialized CBOR items, not
  * Cardano-ledger transactions or admission-policy evidence.
  */
class UpstreamLocalTxSubmissionBehaviorSuite extends FailsOnUncaught:
  private val fixture =
    "/upstream/ouroboros-network-protocol-codecs/localtxsubmission-behavior.tsv"
  private val expectedSha256 = "cc74b908cb3b4888bccc015e0447b407757d6c14751735ad0e5bf575e98dc0a9"

  private final case class Row(
      seed: Int,
      ordinal: Int,
      size: Int,
      index: Int,
      tx: Bytes,
      outcome: String,
      rejection: Option[Bytes],
      direct: String,
      connect: String,
      channel: String
  )

  private def fromHex(hex: String): Bytes =
    Bytes
      .fromHex(hex)
      .fold(error => fail(s"invalid fixture CBOR hex: $error"), identity)

  private def readRows(): Vector[Row] =
    val stream =
      Option(getClass.getResourceAsStream(fixture)).getOrElse(fail(s"missing $fixture"))
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
          assertEquals(fields.size, 11)
          assertEquals(fields(0), "localtxsubmission-behavior-v1")
          val outcome = fields(6)
          val rejection = fields(7) match
            case ""    => None
            case value => Some(fromHex(value))
          assertEquals(outcome == "rejected", rejection.nonEmpty)
          Row(
            fields(1).toInt,
            fields(2).toInt,
            fields(3).toInt,
            fields(4).toInt,
            fromHex(fields(5)),
            outcome,
            rejection,
            fields(8),
            fields(9),
            fields(10)
          )
        }
        .toVector
    finally source.close()

  private def encode(message: LocalTxSubmission.Message): IO[Bytes] =
    LocalTxSubmission
      .encode(LocalTxSubmission.State.Idle, Role.Client, message)
      .fold(error => IO.raiseError(new IllegalStateException(error.message)), IO.pure)

  test("replay unchanged LocalTxSubmission properties through the production server"):
    val rows = readRows()
    assertEquals(rows.size, 147)
    rows.foreach { row =>
      assertEquals(row.direct, "pass", s"unchanged direct property at seed ${row.seed}")
      assertEquals(row.connect, "pass", s"unchanged connect property at seed ${row.seed}")
      assertEquals(row.channel, "pass", s"unchanged channel property at seed ${row.seed}")
    }
    assertEquals(rows.count(_.outcome == "accepted"), 25)
    assertEquals(rows.count(_.outcome == "rejected"), 122)
    val cases = rows.groupBy(row => (row.seed, row.ordinal)).toVector.sortBy(_._1)
    assertEquals(cases.size, 24)
    assertEquals(cases.map(_._1._1), Vector.range(499001, 499025))
    assertEquals(cases.count(_._2.isEmpty), 0, "empty input lists are absent in this sample")
    assertEquals(
      cases.count { case (_, caseRows) => caseRows.map(_.tx).distinct.size < caseRows.size },
      4,
      "four generated cases contain repeated requests"
    )
    assertEquals(
      cases.map { case (_, caseRows) => caseRows.head.size },
      Vector(8, 9, 10, 11, 12, 13, 14, 15, 16, 8, 9, 10, 11, 12, 13, 14, 15, 16, 8, 9, 10, 11, 12,
        13)
    )
    cases
      .traverse { case ((seed, ordinal), unsorted) =>
        val txRows = unsorted.sortBy(_.index)
        assertEquals(txRows.map(_.index), txRows.indices.toVector)
        val aliases = txRows.groupBy(_.tx).values
        aliases.foreach(group =>
          assertEquals(group.map(row => row.outcome -> row.rejection).distinct.size, 1)
        )
        val byWire = txRows.map { row =>
          val response = row.outcome match
            case "accepted" => LocalTxSubmissionServer.Response.Accepted
            case "rejected" =>
              LocalTxSubmissionServer.Response.Rejected(row.rejection.get)
            case other => fail(s"unexpected generated SubmitResult $other at seed $seed")
          row.tx -> response
        }.toMap
        assertEquals(byWire.size, txRows.map(_.tx).distinct.size)
        typed {
          linked(protocols = Set(6)).use { (client, server) =>
            for
              c <- client.channel(6)
              s <- server.channel(6, Mux.Role.Responder)
              result <- Resource
                .make(
                  typed(
                    LocalTxSubmissionServer.run(
                      s,
                      tx =>
                        IO.fromOption(byWire.get(tx))(
                          new IllegalStateException(s"unexported transaction at seed $seed")
                        )
                    )
                  ).start
                )(_.cancel)
                .use { serving =>
                  for
                    replies <- txRows.traverse { row =>
                      for
                        request <- encode(LocalTxSubmission.Message.SubmitTx(row.tx))
                        _ <- c.send(request)
                        reply <- c.recv(LocalTxSubmission.MaxMessageBytes)(
                          LocalTxSubmission.decodePrefix(
                            LocalTxSubmission.State.Busy,
                            Role.Server,
                            _
                          )
                        )
                      yield reply
                    }
                    done <- encode(LocalTxSubmission.Message.Done)
                    _ <- c.send(done)
                    serverResult <- serving.joinWithNever
                  yield (replies, serverResult)
                }
            yield result
          }
        }.map { result =>
          val (replies, serverResult) = result.fold(e => fail(s"seed $seed: $e"), identity)
          val expected = txRows.map { row =>
            row.outcome match
              case "accepted" => LocalTxSubmission.Message.AcceptTx
              case "rejected" => LocalTxSubmission.Message.RejectTx(row.rejection.get)
              case other      => fail(s"unexpected generated SubmitResult $other")
          }
          assertEquals(replies, expected, s"ordered exact response trace at seed $seed/$ordinal")
          assertEquals(serverResult, Right(None), s"production server completes on Done")
        }
      }
      .map(_ => assertEquals(rows.map(_.seed).distinct, Vector.range(499001, 499025)))
