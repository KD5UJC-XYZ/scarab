// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import cats.effect.testkit.TestControl
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.io.Source
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.Peers.*
import xyz.kd5ujc.scarab.protocol.{NetworkError, ProtocolError, TxSubmission2Engine}
import xyz.kd5ujc.scarab.protocol.TxSubmission2Engine.State
import munit.CatsEffectSuite
import scala.concurrent.duration.*

/** Replays unchanged generic ouroboros-network TxSubmission2 direct/connect/channel cases through
  * the same typed engine and client/server loops used by Scarab's strict Conway facade. The opaque
  * test profile carries the exact generated CBOR items; it does not claim Cardano transaction
  * admission or Conway wire compatibility.
  */
class UpstreamTxSubmission2BehaviorSuite extends FailsOnUncaught:
  private val fixture = "/upstream/ouroboros-network-protocol-codecs/txsubmission2-behavior.tsv"
  private val expectedSha256 = "2cdc3825d0a45a28962101177e1db29fa3769d9584ae897f386a4360d674e8b3"

  private enum Verdict:
    case Pass, Failure

  private final case class Pair(id: Bytes, body: Bytes)
  private final case class Row(
      seed: Int,
      ordinal: Int,
      size: Int,
      maxUnacked: Int,
      maxIdsToRequest: Int,
      maxTxToRequest: Int,
      direct: Verdict,
      connect: Verdict,
      pipelined: Verdict,
      channel: Verdict,
      pairs: Vector[Pair],
      connect2Choices: Vector[Boolean]
  )

  private def fromHex(value: String): Bytes =
    Bytes.fromHex(value).fold(error => fail(s"invalid upstream CBOR hex: $error"), identity)

  private def int(value: String): Int =
    value.toIntOption.getOrElse(fail(s"invalid fixture integer: $value"))

  private def verdict(value: String): Verdict = value match
    case "pass"             => Verdict.Pass
    case "upstream-failure" => Verdict.Failure
    case other              => fail(s"unknown upstream property result: $other")

  private def readRows(): Vector[Row] =
    val stream = Option(getClass.getResourceAsStream(fixture)).getOrElse(fail(s"missing $fixture"))
    val raw = stream.readAllBytes()
    stream.close()
    val actualSha =
      MessageDigest.getInstance("SHA-256").digest(raw).map(b => f"${b & 0xff}%02x").mkString
    assertEquals(actualSha, expectedSha256)
    Source
      .fromString(new String(raw, StandardCharsets.UTF_8))
      .getLines()
      .map { line =>
        val fields = line.split("\t", -1)
        assertEquals(fields.length, 14)
        assertEquals(fields(0), "txsubmission2-behavior-v2")
        val count = int(fields(11))
        val pairs =
          if count == 0 then Vector.empty
          else
            fields(12).split(";", -1).toVector.map { encoded =>
              encoded.split(":", -1).toList match
                case idHex :: bodyHex :: Nil => Pair(fromHex(idHex), fromHex(bodyHex))
                case _                       => fail("invalid TxSubmission2 generated ID/body pair")
            }
        assertEquals(pairs.size, count)
        val connect2Choices = fields(13).toVector.map {
          case '1'   => true
          case '0'   => false
          case other => fail(s"invalid generated connect2 choice: $other")
        }
        assert(connect2Choices.nonEmpty, "original connect2 choice vector must remain nonempty")
        Row(
          int(fields(1)),
          int(fields(2)),
          int(fields(3)),
          int(fields(4)),
          int(fields(5)),
          int(fields(6)),
          verdict(fields(7)),
          verdict(fields(8)),
          verdict(fields(9)),
          verdict(fields(10)),
          pairs,
          connect2Choices
        )
      }
      .toVector

  private val codec: TxSubmission2Engine.Codec[Bytes, Bytes] =
    TxSubmission2Engine
      .fromCodecs[Bytes, Bytes](
        maxBatch = 65535,
        maxOriginalBytes = 2500000,
        maxAdvertisedSize = 0xffffffffL,
        maxMessageBytes = {
          case State.Init | State.Idle  => 65535
          case State.Ids(_) | State.Txs => 2500000
          case State.Done               => 0
        },
        encodeId = Right(_),
        decodeId = Right(_),
        encodeBody = Right(_),
        decodeBody = Right(_),
        checkOffer = offer =>
          if offer.advertisedSize > 0 && offer.advertisedSize <= 0xffffffffL then Right(())
          else Left(ProtocolError.SizeOutsideUint32),
        advertisedSize = _ => Right(500L),
        bodyId = Right(_)
      )
      .fold(error => fail(error.message), identity)

  private def rules(maxUnacked: Int): TxSubmission2Runtime.Rules[Bytes] =
    new TxSubmission2Runtime.Rules[Bytes]:
      def acknowledge(unacked: Vector[Bytes], blocking: Boolean, ack: Int, request: Int) =
        val retained = unacked.drop(ack)
        if ack < 0 || ack > 65535 then Left(ProtocolError.CountOutsideUint16(BigInt(ack)))
        else if request < 0 || request > 65535 then
          Left(ProtocolError.CountOutsideUint16(BigInt(request)))
        else if ack > unacked.size then
          Left(ProtocolError.AckExceedsOutstandingInventory(ack, unacked.size))
        else if retained.size + request > maxUnacked then Left(ProtocolError.InvalidRequestLimits)
        else if blocking && (request == 0 || retained.nonEmpty) then
          Left(ProtocolError.InvalidBlockingInventoryRequest)
        else if !blocking && retained.isEmpty then
          Left(ProtocolError.InvalidNonblockingInventoryRequest)
        else Right(retained)

      def bodies(unacked: Vector[Bytes], ids: Vector[Bytes]) =
        if ids.distinct.size != ids.size then Left(ProtocolError.DuplicateBodyRequest)
        else if ids.exists(id => !unacked.contains(id)) then
          Left(ProtocolError.UnadvertisedAcknowledgedOrRepeatedBodyRequest)
        else Right(())

      def offers(ids: Vector[Bytes], requested: Int) =
        if ids.size > requested then Left(ProtocolError.BatchLimitExceeded(ids.size, requested))
        else if ids.distinct.size != ids.size then Left(ProtocolError.DuplicateOfferID)
        else Right(())

  private def replay(row: Row)(using Handle[IO, NetworkError]): IO[Vector[Bytes]] =
    assertEquals(row.direct, Verdict.Pass, s"upstream prop_direct failed for seed ${row.seed}")
    assertEquals(row.connect, Verdict.Pass, s"upstream prop_connect1 failed for seed ${row.seed}")
    assertEquals(row.pipelined, Verdict.Pass, s"upstream prop_connect2 failed for seed ${row.seed}")
    assertEquals(row.channel, Verdict.Pass, s"upstream prop_channel_ST failed for seed ${row.seed}")
    row.pairs.foreach(pair =>
      assertEquals(pair.id, pair.body, s"newtype wire identity for seed ${row.seed}")
    )
    val expected = row.pairs.map(_.body)
    val byId: Map[Bytes, Bytes] = row.pairs.map(pair => pair.id -> pair.body).toMap
    for
      finished <- Deferred[IO, Unit]
      _ <- Option.when(row.pairs.isEmpty)(finished.complete(()).void).getOrElse(IO.unit)
      received <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      source = new TxSubmission2Runtime.Source[IO, Bytes, Bytes]:
        private val all = row.pairs.zipWithIndex.map { case (pair, index) =>
          TxSubmission2Runtime.Held(pair.id, pair.body.size, index.toLong + 1L)
        }
        def after(ticket: Long, max: Int): IO[Vector[TxSubmission2Runtime.Held[Bytes]]] =
          val batch = all.filter(_.ticket > ticket).take(max)
          batch.lastOption
            .filter(_.ticket == all.lastOption.fold(0L)(_.ticket))
            .fold(IO.unit)(_ => finished.complete(()).void)
            .as(batch)
        def awaitAfter(ticket: Long): IO[Unit] =
          if all.exists(_.ticket > ticket) then IO.unit else IO.never
        def body(id: Bytes): IO[Option[Bytes]] = IO.pure(byId.get(id))
      sink = new TxSubmission2Runtime.Sink[IO, Bytes, Bytes]:
        def known(id: Bytes): IO[Boolean] = IO.pure(false)
        def submit(originals: Vector[Bytes]): IO[Unit] = received.update(_ ++ originals)
      clientChannelAndServerChannel <- linked(protocols = Set(4)).use { (clientPeer, serverPeer) =>
        for
          clientChannel <- clientPeer.channel(4)
          serverChannel <- serverPeer.channel(4)
          result <- Resource
            .make(
              Fibers
                .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                  TxSubmission2Runtime
                    .client(
                      clientChannel,
                      source,
                      codec,
                      rules(row.maxUnacked),
                      Some(finished.get),
                      _ => ProtocolError.UnknownTxSubmission2TagStateArity
                    )
                }
                .start
            )(_.cancel)
            .use { client =>
              for
                serverResult <- TxSubmission2Runtime.server(
                  serverChannel,
                  sink,
                  codec,
                  rules(row.maxUnacked),
                  TxSubmission2Runtime.ServerSettings(
                    idsPerRequest = row.maxIdsToRequest,
                    maxTxToRequest = row.maxTxToRequest,
                    maxUnacked = row.maxUnacked
                  ),
                  _ => ProtocolError.UnknownTxSubmission2TagStateArity
                )
                clientResult <- client.joinWithNever
                _ <- Fibers.land[IO, NetworkError, Unit](clientResult)
                actual <- received.get
              yield (serverResult, actual)
            }
        yield result
      }
    yield
      assertEquals(clientChannelAndServerChannel._1, ())
      assertEquals(
        clientChannelAndServerChannel._2,
        expected,
        s"final transaction order for seed ${row.seed}"
      )
      clientChannelAndServerChannel._2

  test("shared production TxSubmission2 engine replays exact pinned generic runtime cases"):
    assertEquals(
      TxSubmission2Runtime.validateServerSettings(
        TxSubmission2Runtime.ServerSettings(maxTxToRequest = 0),
        codec
      ),
      Left(ProtocolError.InvalidTxSubmission2Limits)
    )
    val rows = readRows()
    assertEquals(rows.size, 24)
    assertEquals(rows.count(_.pairs.isEmpty), 1)
    assertEquals(rows.map(_.pairs.size).sum, 94)
    assertEquals(rows.count(_.direct == Verdict.Pass), 24)
    assertEquals(rows.count(_.connect == Verdict.Pass), 24)
    assertEquals(rows.count(_.pipelined == Verdict.Pass), 24)
    assertEquals(rows.count(_.channel == Verdict.Pass), 24)
    assertEquals(
      rows.map(_.connect2Choices.size).groupMapReduce(identity)(_ => 1)(_ + _),
      Map(1 -> 6, 2 -> 3, 3 -> 2, 4 -> 5, 5 -> 2, 6 -> 3, 7 -> 1, 8 -> 1, 9 -> 1)
    )
    assertEquals(rows.map(_.connect2Choices.count(identity)).sum, 52)
    assertEquals(rows.map(_.connect2Choices.count(choice => !choice)).sum, 38)
    val originalChoices = rows
      .map(row =>
        s"${row.seed}:${row.connect2Choices.map(choice => if choice then '1' else '0').mkString}"
      )
      .mkString("\n")
    val choicesSha =
      MessageDigest
        .getInstance("SHA-256")
        .digest(originalChoices.getBytes(StandardCharsets.UTF_8))
        .map(b => f"${b & 0xff}%02x")
        .mkString
    assertEquals(choicesSha, "da96c2e73a5e428ab3d94f24d64fbcfcb4e14357588d85a1f63d32a5cc531848")
    TestControl
      .executeEmbed(
        typed(rows.traverse(replay)).timeoutTo(
          30.seconds,
          IO.raiseError(
            new RuntimeException("TxSubmission2 upstream behavior replay exceeded 30 seconds")
          )
        )
      )
      .map { result =>
        assertEquals(result.map(_.map(_.size)), Right(rows.map(_.pairs.size)))
      }
