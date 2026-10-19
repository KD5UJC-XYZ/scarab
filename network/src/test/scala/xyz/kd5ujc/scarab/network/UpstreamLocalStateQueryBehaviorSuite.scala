// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import cats.mtl.Handle
import munit.CatsEffectSuite
import scala.io.Source
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.Peers.*
import xyz.kd5ujc.scarab.protocol.LocalStateQuery
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.{Message, State, Target}

/** Replays complete, representable maps from the unchanged upstream LSQ behavior properties through
  * Scarab's production server. Generated mock block hashes serialized as Int remain explicit
  * unsupported inputs; no generated map is rewritten to Cardano hashes.
  */
class UpstreamLocalStateQueryBehaviorSuite extends FailsOnUncaught:
  private val casesFixture =
    "/upstream/ouroboros-network-protocol-codecs/localsq-behavior-cases.tsv"
  private val actionsFixture =
    "/upstream/ouroboros-network-protocol-codecs/localsq-behavior-actions.tsv"
  private val expectedCasesSha256 =
    "106efc8fc9785752cf31a622a7a50bd419c56bd1a0e2f2695f0422795db4c1a9"
  private val expectedActionsSha256 =
    "95c47ee1e1b70fd29f514b02d63eaa71efa235978b5eabf8f9b0e627d63a0741"

  private enum Verdict:
    case Pass
    case Failure

  private enum AcquireResult:
    case Acquired(result: Bytes)
    case Rejected(failure: LocalStateQuery.Failure)

  private enum ParsedTarget:
    case Supported(value: Target)
    case Unsupported(error: xyz.kd5ujc.scarab.protocol.ProtocolError)

  private final case class CaseRow(
      seed: Int,
      ordinal: Int,
      size: Int,
      actionCount: Int,
      direct: Verdict,
      connect: Verdict,
      channelST: Verdict,
      channelIO: Verdict,
      pipeIO: Verdict
  )

  private final case class ActionRow(
      seed: Int,
      ordinal: Int,
      size: Int,
      index: Int,
      actionCount: Int,
      targetWire: Bytes,
      target: ParsedTarget,
      result: AcquireResult,
      query: Bytes,
      direct: Verdict,
      connect: Verdict,
      channelST: Verdict,
      channelIO: Verdict,
      pipeIO: Verdict
  )

  private def fromHex(value: String): Bytes =
    Bytes.fromHex(value).fold(error => fail(s"invalid fixture CBOR hex: $error"), identity)

  private def checkHash(path: String, expected: String): Vector[String] =
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse(fail(s"missing $path"))
    val bytes = stream.readAllBytes()
    stream.close()
    val hash = MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
    assertEquals(hash, expected, s"fixture hash for $path")
    val source = Source.fromString(new String(bytes, StandardCharsets.UTF_8))
    try source.getLines().toVector
    finally source.close()

  private def verdict(value: String): Verdict = value match
    case "pass"             => Verdict.Pass
    case "upstream-failure" => Verdict.Failure
    case other              => fail(s"unknown original upstream verdict $other")

  private def parseTarget(targetWire: Bytes, seed: Int): ParsedTarget =
    LocalStateQuery.decodePrefix(State.Idle, Role.Client, targetWire) match
      case DecodeResult.Decoded(Message.Acquire(target), consumed) =>
        assertEquals(consumed, targetWire.size, s"consume exact acquire message at seed $seed")
        ParsedTarget.Supported(target)
      case DecodeResult.Failed(
            error @ xyz.kd5ujc.scarab.protocol.ProtocolError.ExpectedDefiniteByteString
          ) =>
        ParsedTarget.Unsupported(error)
      case DecodeResult.Failed(error) => fail(s"seed $seed unexpected typed target error: $error")
      case DecodeResult.NeedMore =>
        fail(s"seed $seed complete target unexpectedly needs more bytes")
      case DecodeResult.Decoded(other, _) => fail(s"seed $seed decoded non-Acquire target: $other")

  private def parseAcquire(resultWire: Bytes, tag: String, seed: Int): AcquireResult =
    tag match
      case "acquired"           => AcquireResult.Acquired(resultWire)
      case "point-too-old"      => AcquireResult.Rejected(LocalStateQuery.Failure.PointTooOld)
      case "point-not-on-chain" => AcquireResult.Rejected(LocalStateQuery.Failure.PointNotOnChain)
      case other                => fail(s"seed $seed unknown generated acquire result $other")

  private def readCases(): Vector[CaseRow] =
    checkHash(casesFixture, expectedCasesSha256).map { line =>
      val fields = line.split("\\t", -1).toVector
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "localsq-behavior-case-v1")
      CaseRow(
        fields(1).toInt,
        fields(2).toInt,
        fields(3).toInt,
        fields(4).toInt,
        verdict(fields(5)),
        verdict(fields(6)),
        verdict(fields(7)),
        verdict(fields(8)),
        verdict(fields(9))
      )
    }

  private def readActions(): Vector[ActionRow] =
    checkHash(actionsFixture, expectedActionsSha256).map { line =>
      val fields = line.split("\\t", -1).toVector
      assertEquals(fields.size, 15)
      assertEquals(fields(0), "localsq-behavior-v1")
      val seed = fields(1).toInt
      val acquireWire = fromHex(fields(6))
      val target = parseTarget(acquireWire, seed)
      val resultWire = fromHex(fields(9))
      val acquire = parseAcquire(resultWire, fields(7), seed)
      val query = fromHex(fields(8))
      assertEquals(
        query,
        Bytes.fromHex("f6").toOption.get,
        s"original GetTheLedgerState query wire at seed $seed"
      )
      acquire match
        case AcquireResult.Acquired(result) => assert(result.size > 0)
        case AcquireResult.Rejected(_)      => ()
      ActionRow(
        seed,
        fields(2).toInt,
        fields(3).toInt,
        fields(4).toInt,
        fields(5).toInt,
        acquireWire,
        target,
        acquire,
        query,
        verdict(fields(10)),
        verdict(fields(11)),
        verdict(fields(12)),
        verdict(fields(13)),
        verdict(fields(14))
      )
    }

  private def send(channel: MuxChannel[IO], state: State, message: Message)(using
      Handle[IO, NetworkError]
  ): IO[Unit] =
    LocalStateQuery
      .encode(state, Role.Client, message)
      .fold(error => IO.raiseError(new IllegalStateException(error.message)), channel.send)

  private def receive(channel: MuxChannel[IO], state: State)(using Handle[IO, NetworkError]) =
    channel.recv(LocalStateQuery.MaxMessageBytes)(
      LocalStateQuery.decodePrefix(state, Role.Server, _)
    )

  test("replay representable unchanged LocalStateQuery maps through the production server"):
    val cases = readCases()
    val actions = readActions()
    assertEquals(cases.size, 32)
    assertEquals(cases.map(_.seed), Vector.range(513801, 513833))
    assertEquals(cases.map(_.size), Vector.tabulate(32)(i => i % 7))
    assertEquals(actions.size, 40)
    cases.foreach { row =>
      assertEquals(row.direct, Verdict.Pass, s"original direct at seed ${row.seed}")
      assertEquals(row.connect, Verdict.Pass, s"original connect at seed ${row.seed}")
      assertEquals(row.channelST, Verdict.Pass, s"original channel ST at seed ${row.seed}")
      assertEquals(row.channelIO, Verdict.Pass, s"original channel IO at seed ${row.seed}")
      assertEquals(row.pipeIO, Verdict.Pass, s"original pipe IO at seed ${row.seed}")
    }
    val actionGroups = actions.groupBy(row => (row.seed, row.ordinal))
    assertEquals(actionGroups.size, 22)
    cases.foreach { row =>
      val rows = actionGroups.getOrElse((row.seed, row.ordinal), Vector.empty).sortBy(_.index)
      assertEquals(rows.size, row.actionCount)
      assertEquals(rows.map(_.index), rows.indices.toVector)
      rows.foreach { action =>
        assertEquals(action.actionCount, row.actionCount)
        assertEquals(
          (action.direct, action.connect, action.channelST, action.channelIO, action.pipeIO),
          (row.direct, row.connect, row.channelST, row.channelIO, row.pipeIO)
        )
      }
    }
    val unsupported = actions.collect {
      case row @ ActionRow(_, _, _, _, _, _, ParsedTarget.Unsupported(_), _, _, _, _, _, _, _) =>
        row
    }
    assertEquals(unsupported.size, 14)
    assertEquals(unsupported.map(_.seed).distinct.size, 13)
    val unsupportedCaseKeys = unsupported.map(row => (row.seed, row.ordinal)).distinct.toSet
    val supportedCases = cases.filterNot(row =>
      actionGroups
        .getOrElse((row.seed, row.ordinal), Vector.empty)
        .exists(_.target match
          case ParsedTarget.Unsupported(_) => true
          case ParsedTarget.Supported(_)   => false)
    )
    assertEquals(supportedCases.size, 19)
    assertEquals(supportedCases.count(_.actionCount > 0), 9)
    assertEquals(supportedCases.count(_.actionCount == 0), 10)
    assertEquals(supportedCases.map(_.actionCount).sum, 13)
    val unplayedRepresentable = actions.count { action =>
      action.target match
        case ParsedTarget.Supported(_) =>
          unsupportedCaseKeys.contains((action.seed, action.ordinal))
        case ParsedTarget.Unsupported(_) => false
    }
    assertEquals(unplayedRepresentable, 13)
    supportedCases
      .traverse { row =>
        val entries = actionGroups.getOrElse((row.seed, row.ordinal), Vector.empty).sortBy(_.index)
        val sources = entries.collect {
          case ActionRow(
                _,
                _,
                _,
                _,
                _,
                _,
                ParsedTarget.Supported(target),
                outcome,
                _,
                _,
                _,
                _,
                _,
                _
              ) =>
            target -> outcome
        }.toMap
        assertEquals(sources.size, entries.size)
        val source = new LocalStateQueryServer.Source[IO, Nothing]:
          def acquire(target: Target): Resource[IO, Either[
            LocalStateQueryServer.AcquireFailure[Nothing],
            LocalStateQueryServer.Session[IO, Nothing]
          ]] =
            sources.get(target) match
              case None =>
                Resource.eval(
                  IO.raiseError(
                    new IllegalStateException(s"unexported generated target at seed ${row.seed}")
                  )
                )
              case Some(AcquireResult.Rejected(failure)) =>
                Resource.pure(Left(LocalStateQueryServer.AcquireFailure.Rejected(failure)))
              case Some(AcquireResult.Acquired(resultWire)) =>
                val expectedQuery =
                  entries.find(_.target == ParsedTarget.Supported(target)).get.query
                Resource.pure(
                  Right(
                    new LocalStateQueryServer.Session[IO, Nothing]:
                      def answer(
                          query: Bytes
                      ): IO[Either[Nothing, Either[LocalStateQueryServer.Refusal, Bytes]]] =
                        if query != expectedQuery then
                          IO.raiseError(
                            new IllegalStateException(s"query bytes changed at seed ${row.seed}")
                          )
                        else IO.pure(Right(Right(resultWire)))
                  )
                )

        typed {
          linked(protocols = Set(LocalStateQuery.MiniProtocolId)).use { (client, server) =>
            for
              c <- client.channel(LocalStateQuery.MiniProtocolId)
              s <- server.channel(LocalStateQuery.MiniProtocolId, Mux.Role.Responder)
              result <- Resource
                .make(
                  LocalStateQueryServer
                    .run(s, source)
                    .flatMap {
                      case Right(()) => IO.unit
                      case Left(error) =>
                        IO.raiseError(
                          new IllegalStateException(s"impossible LSQ backend error: $error")
                        )
                    }
                    .start
                )(_.cancel)
                .use { serving =>
                  entries
                    .foldLeftM(false) { (wasAcquired, action) =>
                      val target = action.target match
                        case ParsedTarget.Supported(value) => value
                        case ParsedTarget.Unsupported(error) =>
                          throw new IllegalStateException(
                            s"unexpected unsupported target in replay: $error"
                          )
                      val request =
                        if wasAcquired then Message.ReAcquire(target) else Message.Acquire(target)
                      val state = if wasAcquired then State.Acquired else State.Idle
                      for
                        _ <- send(c, state, request)
                        reply <- receive(c, State.Acquiring)
                        next <- action.result match
                          case AcquireResult.Rejected(failure) =>
                            IO(
                              assertEquals(
                                reply,
                                Message.Failed(failure),
                                s"acquire outcome seed ${row.seed}"
                              )
                            )
                              .as(false)
                          case AcquireResult.Acquired(expectedResult) =>
                            for
                              _ <- IO(
                                assertEquals(reply, Message.Acquired, s"acquired seed ${row.seed}")
                              )
                              _ <- send(c, State.Acquired, Message.Query(action.query))
                              result <- receive(c, State.Querying)
                              _ <- IO(
                                assertEquals(
                                  result,
                                  Message.Result(expectedResult),
                                  s"result seed ${row.seed}"
                                )
                              )
                            yield true
                      yield next
                    }
                    .flatMap { acquired =>
                      val release =
                        if acquired then send(c, State.Acquired, Message.Release) else IO.unit
                      release *> send(c, State.Idle, Message.Done) *> serving.joinWithNever
                    }
                }
            yield result
          }
        }.map(result =>
          assertEquals(result, Right(()), s"server completes on Done at seed ${row.seed}")
        )
      }
      .map(_ => ())
