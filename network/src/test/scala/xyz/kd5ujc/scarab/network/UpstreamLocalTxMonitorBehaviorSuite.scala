// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import scala.io.Source
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.{Held, Snapshot, TxMeasure}
import xyz.kd5ujc.scarab.network.Peers.*
import xyz.kd5ujc.scarab.protocol.LocalTxMonitor
import xyz.kd5ujc.scarab.protocol.ChainSync.Role

/** Replays the unchanged generic LocalTxMonitor direct/connect/channel properties against the
  * production server. The generated Tx/TxId are opaque serialized CBOR; this does not claim Cardano
  * transaction or mempool behavior. The upstream example's byte capacity/size values are not
  * asserted by the properties and differ from Scarab's byte-based metrics.
  */
class UpstreamLocalTxMonitorBehaviorSuite extends FailsOnUncaught:
  private val fixture =
    "/upstream/ouroboros-network-protocol-codecs/localtxmonitor-behavior.tsv"
  private val expectedSha256 = "61c200fe43e7f251b32060879f588cb68da4a6a301038053c26c4d9d52e28b77"

  private enum Input:
    case Empty
    case Transaction(tx: Bytes, txId: Bytes)

  private enum Verdict:
    case Pass
    case Failure

  private final case class Row(
      seed: Int,
      ordinal: Int,
      size: Int,
      slot: BigInt,
      index: Int,
      input: Input,
      direct: Verdict,
      connect: Verdict,
      channel: Verdict
  )

  private def fromHex(hex: String): Bytes =
    Bytes.fromHex(hex).fold(error => fail(s"invalid fixture CBOR hex: $error"), identity)

  private def outcome(value: String): Verdict =
    value match
      case "pass"             => Verdict.Pass
      case "upstream-failure" => Verdict.Failure
      case other              => fail(s"unknown upstream verdict $other")

  private def uintItem(bytes: Bytes): BigInt =
    val values = bytes.value.map(_ & 0xff)
    assert(values.nonEmpty, "empty CBOR SlotNo item")
    val initial = values.head
    assertEquals(initial >> 5, 0, "SlotNo must serialize as a CBOR unsigned integer")
    val additional = initial & 0x1f
    val payloadLength = additional match
      case value if value < 24 => 0
      case 24                  => 1
      case 25                  => 2
      case 26                  => 4
      case 27                  => 8
      case other               => fail(s"invalid CBOR unsigned integer additional info $other")
    if payloadLength == 0 then BigInt(additional)
    else
      assertEquals(values.size, payloadLength + 1)
      values.drop(1).foldLeft(BigInt(0))((acc, value) => (acc << 8) + value)

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
          assertEquals(fields.size, 12)
          assertEquals(fields(0), "localtxmonitor-behavior-v1")
          val input = fields(8) match
            case "empty" =>
              assertEquals(fields(5), "-1")
              assertEquals(fields(6), "")
              assertEquals(fields(7), "")
              Input.Empty
            case "transaction" =>
              assert(fields(6).nonEmpty && fields(7).nonEmpty)
              Input.Transaction(fromHex(fields(6)), fromHex(fields(7)))
            case other => fail(s"unknown generated input kind $other")
          Row(
            fields(1).toInt,
            fields(2).toInt,
            fields(3).toInt,
            uintItem(fromHex(fields(4))),
            fields(5).toInt,
            input,
            outcome(fields(9)),
            outcome(fields(10)),
            outcome(fields(11))
          )
        }
        .toVector
    finally source.close()

  private def clientSend(
      channel: MuxChannel[IO],
      state: LocalTxMonitor.State,
      message: LocalTxMonitor.Message
  )(using HandleError): IO[Unit] =
    LocalTxMonitor
      .encode(state, Role.Client, message)
      .fold(error => IO.raiseError(new IllegalStateException(error.message)), channel.send)

  private type HandleError = cats.mtl.Handle[IO, NetworkError]

  test("replay unchanged LocalTxMonitor properties through the production fixed-snapshot server"):
    val rows = readRows()
    assertEquals(rows.size, 148)
    assertEquals(rows.count(_.input == Input.Empty), 3)
    assertEquals(
      rows.count(_.input match
        case Input.Transaction(_, _) => true
        case Input.Empty             => false),
      145
    )
    rows.foreach { row =>
      assertEquals(row.direct, Verdict.Pass, s"direct property at seed ${row.seed}")
      assertEquals(row.connect, Verdict.Pass, s"connect property at seed ${row.seed}")
      assertEquals(row.channel, Verdict.Pass, s"channel property at seed ${row.seed}")
    }
    val cases = rows.groupBy(row => (row.seed, row.ordinal)).toVector.sortBy(_._1)
    assertEquals(cases.size, 24)
    assertEquals(cases.map(_._1._1), Vector.range(500001, 500025))
    assertEquals(cases.count(_._2.exists(_.input == Input.Empty)), 3)
    cases
      .traverse { case ((seed, ordinal), unsorted) =>
        val ordered = unsorted.sortBy(_.index)
        assertEquals(ordered.map(_.slot).distinct.size, 1)
        val txs = ordered.collect { case Row(_, _, _, _, _, Input.Transaction(tx, _), _, _, _) =>
          tx
        }
        val ids = ordered.collect { case Row(_, _, _, _, _, Input.Transaction(_, id), _, _, _) =>
          id
        }
        val held = txs.map(tx => Held(tx, TxMeasure(tx.size.toLong, 0, 0, 0)))
        val capacity = TxMeasure(held.map(_.measure.bytes).sum, 0, 0, 0)
        val snapshot = Snapshot(
          ordered.head.slot,
          held,
          ids.toSet,
          capacity,
          (ordered.head.slot, txs)
        )
        val source = new LocalTxMonitorServer.Source[IO]:
          def acquire: IO[Snapshot] = IO.pure(snapshot)
          def awaitChange(seen: Snapshot): IO[Snapshot] =
            IO.raiseError(
              new IllegalStateException("upstream behavior does not request AwaitAcquire")
            )
        typed {
          linked(protocols = Set(9)).use { (client, server) =>
            for
              c <- client.channel(9)
              s <- server.channel(9, Mux.Role.Responder)
              result <- Resource
                .make(typed(LocalTxMonitorServer.run(s, source)).start)(_.cancel)
                .use { fiber =>
                  for
                    _ <- clientSend(c, LocalTxMonitor.State.Idle, LocalTxMonitor.Message.Acquire)
                    acquired <- c.recv(LocalTxMonitor.MaxMessageBytes)(
                      LocalTxMonitor.decodePrefix(LocalTxMonitor.State.Acquiring, Role.Server, _)
                    )
                    _ <- IO(
                      assertEquals(acquired, LocalTxMonitor.Message.Acquired(ordered.head.slot))
                    )
                    streamed <- txs.zip(ids).traverse { case (expectedTx, expectedId) =>
                      for
                        _ <- clientSend(
                          c,
                          LocalTxMonitor.State.Acquired,
                          LocalTxMonitor.Message.NextTx
                        )
                        message <- c.recv(LocalTxMonitor.MaxMessageBytes)(
                          LocalTxMonitor.decodePrefix(
                            LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.NextTx),
                            Role.Server,
                            _
                          )
                        )
                        actualTx <- message match
                          case LocalTxMonitor.Message.ReplyNextTx(Some(tx)) => IO.pure(tx)
                          case other =>
                            IO.raiseError(new IllegalStateException(s"unexpected $other"))
                        _ <- IO(assertEquals(actualTx, expectedTx))
                        _ <- clientSend(
                          c,
                          LocalTxMonitor.State.Acquired,
                          LocalTxMonitor.Message.HasTx(expectedId)
                        )
                        has <- c.recv(LocalTxMonitor.MaxMessageBytes)(
                          LocalTxMonitor.decodePrefix(
                            LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.HasTx),
                            Role.Server,
                            _
                          )
                        )
                        _ <- IO(assertEquals(has, LocalTxMonitor.Message.ReplyHasTx(true)))
                      yield actualTx
                    }
                    _ <- clientSend(c, LocalTxMonitor.State.Acquired, LocalTxMonitor.Message.NextTx)
                    end <- c.recv(LocalTxMonitor.MaxMessageBytes)(
                      LocalTxMonitor.decodePrefix(
                        LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.NextTx),
                        Role.Server,
                        _
                      )
                    )
                    _ <- IO(assertEquals(end, LocalTxMonitor.Message.ReplyNextTx(None)))
                    _ <- clientSend(
                      c,
                      LocalTxMonitor.State.Acquired,
                      LocalTxMonitor.Message.GetSizes
                    )
                    sizes <- c.recv(LocalTxMonitor.MaxMessageBytes)(
                      LocalTxMonitor.decodePrefix(
                        LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.GetSizes),
                        Role.Server,
                        _
                      )
                    )
                    _ <- IO {
                      sizes match
                        case LocalTxMonitor.Message.ReplyGetSizes(value) =>
                          assertEquals(value.numberOfTxs, txs.size.toLong)
                        case other => fail(s"unexpected GetSizes reply $other")
                    }
                    _ <- clientSend(
                      c,
                      LocalTxMonitor.State.Acquired,
                      LocalTxMonitor.Message.Release
                    )
                    _ <- clientSend(c, LocalTxMonitor.State.Idle, LocalTxMonitor.Message.Done)
                    serverResult <- fiber.joinWithNever
                  yield (streamed, serverResult)
                }
            yield result
          }
        }.map { result =>
          val (streamed, serverResult) =
            result.fold(e => fail(s"seed $seed/$ordinal: $e"), identity)
          assertEquals(streamed, txs, s"ordered original transaction stream")
          assertEquals(serverResult, Right(()), "server completes after Release and Done")
        }
      }
      .map(_ => ())
