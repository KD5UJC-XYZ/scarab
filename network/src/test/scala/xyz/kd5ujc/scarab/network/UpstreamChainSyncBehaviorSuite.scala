// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.mtl.Raise
import cats.syntax.all.*
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.io.Source
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncEngine, NetworkError}

import Peers.*

/** Replays exact generic mock-domain chains, points, and serialized block items from the unchanged
  * pinned ChainSync fork properties against the production generic client/server runtime. The
  * upstream Int-hash point domain remains opaque CBOR; this does not claim Cardano block/hash
  * instantiation, node fork choice, or pipelined decision-trace equivalence.
  */
class UpstreamChainSyncBehaviorSuite extends FailsOnUncaught:
  private val casesFixture =
    "/upstream/ouroboros-network-protocol-codecs/chainsync-behavior-cases.tsv"
  private val blocksFixture =
    "/upstream/ouroboros-network-protocol-codecs/chainsync-behavior-blocks.tsv"
  private val pointsFixture =
    "/upstream/ouroboros-network-protocol-codecs/chainsync-behavior-points.tsv"
  private val expectedCasesSha = "a3e77a7ef66233c8d2360788efa828afd946a7513069cc535317e3751d055a18"
  private val expectedBlocksSha = "98d1ee153282f811de0b287bf1c7b3a5e3b5c68482fd8223d7f5536744eda776"
  private val expectedPointsSha = "4093edd68ddf331771f9857899fcd32e14b4108b1249c400d624ba7d35740518"

  private enum Verdict:
    case Pass
    case Failure

  private enum PipelineOutcome:
    case Pass
    case UpstreamFailure
    case PreconditionDiscard

  private enum Side:
    case Producer
    case Client

  private final case class CaseRow(
      seed: Int,
      ordinal: Int,
      size: Int,
      direct: Verdict,
      connect: Verdict,
      channel: Verdict,
      targetPoint: Bytes,
      targetTip: Bytes,
      producerCount: Int,
      forkCount: Int,
      pointCount: Int,
      genesisPoint: Bytes,
      expectedIntersection: Option[Bytes]
  )
  private final case class BlockRow(
      seed: Int,
      caseOrdinal: Int,
      side: Side,
      ordinal: Int,
      point: Bytes,
      payload: Bytes
  )
  private final case class PointRow(seed: Int, caseOrdinal: Int, ordinal: Int, point: Bytes)
  private final case class PipelineRow(
      seed: Int,
      ordinal: Int,
      size: Int,
      depthSeed: Int,
      maxDepth: Int,
      outcome: PipelineOutcome
  )
  private final case class PipelineLowHighRow(
      seed: Int,
      ordinal: Int,
      size: Int,
      firstDepthSeed: Int,
      firstDepth: Int,
      secondDepthSeed: Int,
      secondDepth: Int,
      outcome: PipelineOutcome
  )
  private final case class PipelineDirectConnectRow(
      seed: Int,
      ordinal: Int,
      size: Int,
      depthSeed: Int,
      maxDepth: Int,
      choicesSeed: Int,
      choices: String,
      maxDirect: PipelineOutcome,
      minDirect: PipelineOutcome,
      maxConnect: PipelineOutcome,
      minConnect: PipelineOutcome,
      maxDirectIO: PipelineOutcome,
      minDirectIO: PipelineOutcome,
      maxConnectIO: PipelineOutcome,
      minConnectIO: PipelineOutcome
  )
  private final case class PipelineDemoIORow(
      seed: Int,
      ordinal: Int,
      size: Int,
      firstDepthSeed: Int,
      firstDepth: Int,
      secondDepthSeed: Int,
      secondDepth: Int,
      maxOutcome: PipelineOutcome,
      minOutcome: PipelineOutcome,
      lowHighOutcome: PipelineOutcome
  )
  private final case class BaseIORow(
      seed: Int,
      ordinal: Int,
      size: Int,
      direct: PipelineOutcome,
      connect: PipelineOutcome,
      demo: PipelineOutcome,
      pipe: PipelineOutcome
  )
  private final case class FixtureCase(
      row: CaseRow,
      producer: Vector[BlockRow],
      fork: Vector[BlockRow],
      candidatePoints: Vector[Bytes],
      pointByPayload: Map[Bytes, Bytes]
  )
  private final case class Cursor(index: Int, rollbackPoint: Bytes, rollbackPending: Boolean)

  private def resource(path: String, expectedSha: String): Vector[String] =
    val stream =
      Option(getClass.getResourceAsStream(path)).getOrElse(fail(s"missing fixture $path"))
    val raw = stream.readAllBytes()
    stream.close()
    val sha = MessageDigest.getInstance("SHA-256").digest(raw).map(b => f"${b & 0xff}%02x").mkString
    assertEquals(sha, expectedSha, s"pinned fixture hash: $path")
    val source = Source.fromString(new String(raw, StandardCharsets.UTF_8))
    try source.getLines().toVector
    finally source.close()

  private def fromHex(value: String): Bytes =
    Bytes.fromHex(value).fold(error => fail(s"invalid upstream CBOR hex: $error"), identity)

  private def verdict(value: String): Verdict = value match
    case "pass"             => Verdict.Pass
    case "upstream-failure" => Verdict.Failure
    case other              => fail(s"unknown upstream property verdict: $other")

  private def pipelineOutcome(value: String): PipelineOutcome = value match
    case "pass"                 => PipelineOutcome.Pass
    case "upstream-failure"     => PipelineOutcome.UpstreamFailure
    case "precondition-discard" => PipelineOutcome.PreconditionDiscard
    case other                  => fail(s"unknown ChainSync pipeline property outcome: $other")

  private def readCases(): Vector[CaseRow] =
    resource(casesFixture, expectedCasesSha).map { line =>
      val fields = line.split("\\t", -1).toVector
      assertEquals(fields.size, 14)
      assertEquals(fields(0), "chainsync-behavior-case-v2")
      CaseRow(
        fields(1).toInt,
        fields(2).toInt,
        fields(3).toInt,
        verdict(fields(4)),
        verdict(fields(5)),
        verdict(fields(6)),
        fromHex(fields(7)),
        fromHex(fields(8)),
        fields(9).toInt,
        fields(10).toInt,
        fields(11).toInt,
        fromHex(fields(12)),
        fields(13) match
          case "none" => None
          case point  => Some(fromHex(point))
      )
    }

  private def readBlocks(): Vector[BlockRow] =
    resource(blocksFixture, expectedBlocksSha).map { line =>
      val fields = line.split("\\t", -1).toVector
      assertEquals(fields.size, 7)
      assertEquals(fields(0), "chainsync-behavior-block-v1")
      val side = fields(3) match
        case "producer" => Side.Producer
        case "client"   => Side.Client
        case other      => fail(s"unknown upstream chain side: $other")
      BlockRow(
        fields(1).toInt,
        fields(2).toInt,
        side,
        fields(4).toInt,
        fromHex(fields(5)),
        fromHex(fields(6))
      )
    }

  private def readPoints(): Vector[PointRow] =
    resource(pointsFixture, expectedPointsSha).map { line =>
      val fields = line.split("\\t", -1).toVector
      assertEquals(fields.size, 5)
      assertEquals(fields(0), "chainsync-behavior-point-v1")
      PointRow(fields(1).toInt, fields(2).toInt, fields(3).toInt, fromHex(fields(4)))
    }

  private def readPipelineRows(
      fixture: String,
      expectedSha: String,
      schema: String
  ): Vector[PipelineRow] =
    resource(
      s"/upstream/ouroboros-network-protocol-codecs/$fixture",
      expectedSha
    ).map { line =>
      val fields = line.split("\\t", -1).toVector
      assertEquals(fields.size, 7)
      assertEquals(fields(0), schema)
      PipelineRow(
        fields(1).toInt,
        fields(2).toInt,
        fields(3).toInt,
        fields(4).toInt,
        fields(5).toInt,
        pipelineOutcome(fields(6))
      )
    }

  private def readPipelinePairRows(
      fixture: String,
      expectedSha: String,
      schema: String
  ): Vector[PipelineLowHighRow] =
    resource(
      s"/upstream/ouroboros-network-protocol-codecs/$fixture",
      expectedSha
    ).map { line =>
      val fields = line.split("\\t", -1).toVector
      assertEquals(fields.size, 9)
      assertEquals(fields(0), schema)
      PipelineLowHighRow(
        fields(1).toInt,
        fields(2).toInt,
        fields(3).toInt,
        fields(4).toInt,
        fields(5).toInt,
        fields(6).toInt,
        fields(7).toInt,
        pipelineOutcome(fields(8))
      )
    }

  private def readPipelineDirectConnectRows(expectedSha: String): Vector[PipelineDirectConnectRow] =
    resource(
      "/upstream/ouroboros-network-protocol-codecs/chainsync-pipelined-direct-connect.tsv",
      expectedSha
    ).map { line =>
      val fields = line.split("\\t", -1).toVector
      assertEquals(fields.size, 16)
      assertEquals(fields(0), "chainsync-pipelined-direct-connect-v1")
      PipelineDirectConnectRow(
        fields(1).toInt,
        fields(2).toInt,
        fields(3).toInt,
        fields(4).toInt,
        fields(5).toInt,
        fields(6).toInt,
        fields(7),
        pipelineOutcome(fields(8)),
        pipelineOutcome(fields(9)),
        pipelineOutcome(fields(10)),
        pipelineOutcome(fields(11)),
        pipelineOutcome(fields(12)),
        pipelineOutcome(fields(13)),
        pipelineOutcome(fields(14)),
        pipelineOutcome(fields(15))
      )
    }

  private def readPipelineDemoIORows(expectedSha: String): Vector[PipelineDemoIORow] =
    resource(
      "/upstream/ouroboros-network-protocol-codecs/chainsync-demo-pipelined-io.tsv",
      expectedSha
    ).map { line =>
      val fields = line.split("\\t", -1).toVector
      assertEquals(fields.size, 11)
      assertEquals(fields(0), "chainsync-demo-pipelined-io-v1")
      PipelineDemoIORow(
        fields(1).toInt,
        fields(2).toInt,
        fields(3).toInt,
        fields(4).toInt,
        fields(5).toInt,
        fields(6).toInt,
        fields(7).toInt,
        pipelineOutcome(fields(8)),
        pipelineOutcome(fields(9)),
        pipelineOutcome(fields(10))
      )
    }

  private def readBaseIORows(expectedSha: String): Vector[BaseIORow] =
    resource(
      "/upstream/ouroboros-network-protocol-codecs/chainsync-behavior-io.tsv",
      expectedSha
    ).map { line =>
      val fields = line.split("\\t", -1).toVector
      assertEquals(fields.size, 8)
      assertEquals(fields(0), "chainsync-behavior-io-v1")
      BaseIORow(
        fields(1).toInt,
        fields(2).toInt,
        fields(3).toInt,
        pipelineOutcome(fields(4)),
        pipelineOutcome(fields(5)),
        pipelineOutcome(fields(6)),
        pipelineOutcome(fields(7))
      )
    }

  private def readFixture(): Vector[FixtureCase] =
    val cases = readCases()
    val blocks = readBlocks()
    val points = readPoints()
    cases.map { row =>
      val caseBlocks = blocks.filter(b => b.seed == row.seed && b.caseOrdinal == row.ordinal)
      val producer = caseBlocks.filter(_.side == Side.Producer).sortBy(_.ordinal)
      val fork = caseBlocks.filter(_.side == Side.Client).sortBy(_.ordinal)
      val candidateRows =
        points.filter(p => p.seed == row.seed && p.caseOrdinal == row.ordinal).sortBy(_.ordinal)
      assertEquals(
        producer.map(_.ordinal),
        Vector.range(0, row.producerCount),
        s"seed ${row.seed} producer row identities"
      )
      assertEquals(
        fork.map(_.ordinal),
        Vector.range(0, row.forkCount),
        s"seed ${row.seed} fork row identities"
      )
      assertEquals(
        candidateRows.map(_.ordinal),
        Vector.range(0, row.pointCount),
        s"seed ${row.seed} candidate row identities"
      )
      assertEquals(
        producer.lastOption.map(_.point),
        Some(row.targetPoint),
        s"seed ${row.seed} producer target point"
      )
      val aliases = (producer ++ fork).groupBy(_.payload)
      assert(
        aliases.values.forall(rows => rows.map(_.point).distinct.size == 1),
        s"seed ${row.seed} payload-point alias consistency"
      )
      FixtureCase(
        row,
        producer,
        fork,
        candidateRows.map(_.point),
        aliases.view.mapValues(_.head.point).toMap
      )
    }

  private val pointCodec: ChainSyncEngine.PointCodec[Bytes] =
    ChainSyncEngine.pointCodec(Right(_), Right(_))
  private val tipCodec: ChainSyncEngine.TipCodec[Bytes] =
    ChainSyncEngine.tipCodec(Right(_), Right(_))
  private val payloadCodec: ChainSyncEngine.PayloadCodec[Bytes] =
    ChainSyncEngine.payloadCodec(Right(_), Right(_))

  private def source(testCase: FixtureCase): IO[ChainSyncRuntime.Source[IO, Bytes, Bytes, Bytes]] =
    val producer = testCase.producer
    val row = testCase.row
    Ref.of[IO, Cursor](Cursor(-1, row.genesisPoint, rollbackPending = false)).map { cursor =>
      new ChainSyncRuntime.Source[IO, Bytes, Bytes, Bytes]:
        def intersect(points: Vector[Bytes]): IO[Either[Bytes, (Bytes, Bytes)]] =
          val found = points.collectFirst {
            case point if point == row.genesisPoint => point -> -1
            case point if producer.exists(_.point == point) =>
              point -> producer.indexWhere(_.point == point)
          }
          val (rollbackPoint, index) = found.fold(row.genesisPoint -> -1)(identity)
          cursor
            .set(Cursor(index, rollbackPoint, rollbackPending = true))
            .as(
              found.fold[Either[Bytes, (Bytes, Bytes)]](Left(row.targetTip)) { case (point, _) =>
                Right(point -> row.targetTip)
              }
            )

        def poll: IO[Option[ChainSyncRuntime.Update[Bytes, Bytes, Bytes]]] =
          cursor.modify { current =>
            if current.rollbackPending then
              current.copy(rollbackPending = false) -> Some(
                ChainSyncRuntime.Update.RollBackward(current.rollbackPoint, row.targetTip)
              )
            else if current.index + 1 < producer.size then
              val next = producer(current.index + 1)
              current.copy(index = current.index + 1) -> Some(
                ChainSyncRuntime.Update.RollForward(next.payload, row.targetTip)
              )
            else current -> None
          }

        def await: IO[ChainSyncRuntime.Update[Bytes, Bytes, Bytes]] = IO.never
    }

  private def replayClient(
      client: ChainSyncRuntime.Client[IO, Bytes, Bytes, Bytes],
      testCase: FixtureCase,
      chain: Ref[IO, Vector[Bytes]]
  )(using Raise[IO, NetworkError]): IO[Unit] =
    val row = testCase.row
    cats.Monad[IO].tailRecM(()) { _ =>
      client.requestNext.flatMap {
        case ChainSyncRuntime.Update.RollBackward(point, _) =>
          val retained =
            if point == row.genesisPoint then Vector.empty
            else
              val found = testCase.fork.indexWhere(_.point == point)
              assert(found >= 0, s"seed ${row.seed} rollback point must be on original client fork")
              testCase.fork.take(found + 1).map(_.payload)
          chain.set(retained) *> (if point == row.targetPoint then client.done.as(Right(()))
                                  else IO.pure(Left(())))
        case ChainSyncRuntime.Update.RollForward(payload, _) =>
          val point = testCase.pointByPayload
            .get(payload)
            .getOrElse(fail(s"seed ${row.seed} unknown exact serialized block item"))
          chain.update(_ :+ payload) *> (if point == row.targetPoint then client.done.as(Right(()))
                                         else IO.pure(Left(())))
      }
    }

  private def replayFixedWindow(
      client: ChainSyncRuntime.Client[IO, Bytes, Bytes, Bytes],
      testCase: FixtureCase,
      chain: Ref[IO, Vector[Bytes]],
      maxDepth: Int
  )(using Raise[IO, NetworkError]): IO[Unit] =
    val row = testCase.row
    val targetPayload = testCase.producer.last.payload
    def applyRollback(point: Bytes): IO[Boolean] =
      val retained =
        if point == row.genesisPoint then Vector.empty
        else
          val found = testCase.fork.indexWhere(_.point == point)
          assert(found >= 0, s"seed ${row.seed} rollback point must be on the original fork")
          testCase.fork.take(found + 1).map(_.payload)
      chain.set(retained).as(point == row.targetPoint)

    client.requestNext.flatMap {
      case ChainSyncRuntime.Update.RollBackward(point, _) =>
        applyRollback(point).flatMap {
          case true => client.done
          case false =>
            cats.Monad[IO].tailRecM(()) { _ =>
              chain.get.flatMap { current =>
                val remaining = testCase.producer.size - current.size
                if remaining <= 0 then client.done.as(Right(()))
                else
                  val window = math.min(maxDepth, remaining)
                  client.requestNextBatch(window).flatMap { updates =>
                    assertEquals(updates.size, window, s"seed ${row.seed} batch window replies")
                    val next = updates.foldLeft(current) {
                      case (prefix, ChainSyncRuntime.Update.RollForward(payload, _)) =>
                        val point = testCase.pointByPayload
                          .get(payload)
                          .getOrElse(fail(s"seed ${row.seed} unknown exact serialized block item"))
                        assert(
                          !prefix.lastOption.contains(targetPayload),
                          s"seed ${row.seed} max window continued after the generated target"
                        )
                        assert(point != row.genesisPoint)
                        prefix :+ payload
                      case (_, ChainSyncRuntime.Update.RollBackward(point, _)) =>
                        fail(
                          s"seed ${row.seed} unexpected rollback inside a bounded max window: $point"
                        )
                    }
                    chain.set(
                      next
                    ) *> (if next.size == testCase.producer.size then client.done.as(Right(()))
                          else if next.size > testCase.producer.size then
                            fail(s"seed ${row.seed} max window exceeded producer chain")
                          else IO.pure(Left(())))
                  }
              }
            }
        }
      case other => fail(s"seed ${row.seed} expected initial rollback, got $other")
    }

  private def verifyBaseRuntime(fixture: Vector[FixtureCase]): IO[Unit] =
    assertEquals(fixture.size, 24)
    assertEquals(fixture.map(_.row.seed), Vector.range(504001, 504025))
    assertEquals(fixture.map(_.row.ordinal), Vector.range(0, 24))
    assertEquals(fixture.map(_.row.size), (0 until 24).map(i => 30 + i % 21).toVector)
    assertEquals(fixture.map(_.row.direct).count(_ == Verdict.Pass), 24)
    assertEquals(fixture.map(_.row.connect).count(_ == Verdict.Pass), 24)
    assertEquals(fixture.map(_.row.channel).count(_ == Verdict.Pass), 24)
    assertEquals(fixture.map(_.producer.size).sum, 1_074)
    assertEquals(fixture.map(_.fork.size).sum, 1_036)
    assertEquals(fixture.map(_.candidatePoints.size).sum, 234)
    assertEquals(
      fixture.count(_.row.expectedIntersection.contains(fromHex("80"))),
      3,
      "pinned upstream Chain.findFirstPoint selects Genesis in three generated cases"
    )

    fixture.traverse_ { testCase =>
      val row = testCase.row
      val initialFork = testCase.fork.map(_.payload)
      source(testCase).flatMap { oracle =>
        Ref.of[IO, Vector[Bytes]](initialFork).flatMap { chain =>
          typed {
            linked(protocols = Set(Protocols.ChainSync)).use { (clientMux, serverMux) =>
              for
                clientChannel <- clientMux.channel(Protocols.ChainSync)
                serverChannel <- serverMux.channel(Protocols.ChainSync)
                _ <- Resource
                  .make(
                    Fibers
                      .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                        ChainSyncRuntime
                          .run(
                            serverChannel,
                            pointCodec,
                            tipCodec,
                            payloadCodec,
                            oracle,
                            ChainSyncRuntime.ServerSettings(idleTimeout = 5.seconds)
                          )
                      }
                      .start
                  )(_.cancel)
                  .use { serverFiber =>
                    for
                      client <- ChainSyncRuntime.client(
                        clientChannel,
                        pointCodec,
                        tipCodec,
                        payloadCodec,
                        ChainSyncRuntime.ClientSettings(
                          intersectTimeout = 5.seconds,
                          canAwaitTimeout = 5.seconds,
                          mustReplyTimeout = 5.seconds
                        )
                      )
                      intersection <- client.findIntersect(testCase.candidatePoints)
                      _ <- replayClient(client, testCase, chain).timeout(10.seconds)
                      serverResult <- serverFiber.joinWithNever
                      _ <- Fibers.land[IO, NetworkError, Unit](serverResult)
                      finalChain <- chain.get
                    yield
                      assertEquals(
                        intersection,
                        row.expectedIntersection.map(_ -> row.targetTip)
                      )
                      assertEquals(
                        finalChain,
                        testCase.producer.map(_.payload),
                        s"seed ${row.seed} final chain equals independent producer-chain oracle"
                      )
                  }
              yield ()
            }
          }.map(result => assertEquals(result, Right(()), s"seed ${row.seed}"))
        }
      }
    }

  test(
    "production generic ChainSync runtime replays unchanged pinned upstream fork-property cases"
  ):
    verifyBaseRuntime(readFixture())

  test("production generic ChainSync runtime covers matching direct/connect/demo/pipe IO cases"):
    val fixture = readFixture()
    val ioRows = readBaseIORows(
      "b19554a9c49ea5bec484f0c6a082b1ba1870b9379cd9e5b559e5a80ddd08f121"
    )
    assertEquals(ioRows.size, fixture.size)
    assertEquals(ioRows.map(_.seed), fixture.map(_.row.seed))
    assertEquals(ioRows.map(_.ordinal), fixture.map(_.row.ordinal))
    assertEquals(ioRows.map(_.size), fixture.map(_.row.size))
    assertEquals(ioRows.map(_.direct).count(_ == PipelineOutcome.Pass), 24)
    assertEquals(ioRows.map(_.connect).count(_ == PipelineOutcome.Pass), 24)
    assertEquals(ioRows.map(_.demo).count(_ == PipelineOutcome.Pass), 24)
    assertEquals(ioRows.map(_.pipe).count(_ == PipelineOutcome.Pass), 24)
    verifyBaseRuntime(fixture)

  private def verifyPipelinedFinalChains(
      pipelineRows: Vector[PipelineRow],
      policyName: String
  ): IO[Unit] =
    val fixture = readFixture()
    assertEquals(pipelineRows.size, fixture.size)
    assertEquals(pipelineRows.map(_.seed), fixture.map(_.row.seed))
    assertEquals(pipelineRows.map(_.ordinal), fixture.map(_.row.ordinal))
    assertEquals(pipelineRows.map(_.size), fixture.map(_.row.size))
    assert(pipelineRows.forall(row => row.depthSeed == row.seed + 7000003))
    assert(pipelineRows.forall(row => row.maxDepth >= 0 && row.maxDepth <= 300))
    assertEquals(
      pipelineRows.count(_.outcome == PipelineOutcome.PreconditionDiscard),
      pipelineRows.count(_.maxDepth == 0),
      "zero depths are kept as original upstream implication discards"
    )
    assert(
      pipelineRows.forall(row =>
        (row.maxDepth == 0) == (row.outcome == PipelineOutcome.PreconditionDiscard)
      )
    )
    val runnable = pipelineRows.filter(_.outcome == PipelineOutcome.Pass)
    assert(
      runnable.nonEmpty,
      s"the original generated $policyName sample must contain positive-depth passing rows"
    )
    assertEquals(pipelineRows.count(_.outcome == PipelineOutcome.UpstreamFailure), 0)

    fixture.zip(pipelineRows).filter(_._2.outcome == PipelineOutcome.Pass).traverse_ {
      case (testCase, pipeline) =>
        val row = testCase.row
        source(testCase).flatMap { oracle =>
          Ref.of[IO, Vector[Bytes]](testCase.fork.map(_.payload)).flatMap { chain =>
            typed {
              linked(protocols = Set(Protocols.ChainSync)).use { (clientMux, serverMux) =>
                for
                  clientChannel <- clientMux.channel(Protocols.ChainSync)
                  serverChannel <- serverMux.channel(Protocols.ChainSync)
                  _ <- Resource
                    .make(
                      Fibers
                        .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                          ChainSyncRuntime
                            .run(
                              serverChannel,
                              pointCodec,
                              tipCodec,
                              payloadCodec,
                              oracle,
                              ChainSyncRuntime.ServerSettings(idleTimeout = 5.seconds)
                            )
                        }
                        .start
                    )(_.cancel)
                    .use { serverFiber =>
                      for
                        client <- ChainSyncRuntime.client(
                          clientChannel,
                          pointCodec,
                          tipCodec,
                          payloadCodec,
                          ChainSyncRuntime.ClientSettings(
                            intersectTimeout = 5.seconds,
                            canAwaitTimeout = 5.seconds,
                            mustReplyTimeout = 5.seconds
                          )
                        )
                        intersection <- client.findIntersect(testCase.candidatePoints)
                        _ <- replayFixedWindow(client, testCase, chain, pipeline.maxDepth).timeout(
                          10.seconds
                        )
                        serverResult <- serverFiber.joinWithNever
                        _ <- Fibers.land[IO, NetworkError, Unit](serverResult)
                        finalChain <- chain.get
                      yield
                        assertEquals(
                          intersection,
                          row.expectedIntersection.map(_ -> row.targetTip),
                          s"seed ${row.seed} independent intersection"
                        )
                        assertEquals(
                          finalChain,
                          testCase.producer.map(_.payload),
                          s"seed ${row.seed} fixed-window final chain equals upstream $policyName oracle"
                        )
                    }
                yield ()
              }
            }.map(result => assertEquals(result, Right(()), s"seed ${row.seed}"))
          }
        }
    }

  test("production generic ChainSync runtime matches generated max-pipelined final-chain cases"):
    verifyPipelinedFinalChains(
      readPipelineRows(
        "chainsync-pipelined-max.tsv",
        "a3b1f8a495704991fb90ee701fe1bb31d56371346ce37f7e7bfd38f4e6c0cc49",
        "chainsync-pipelined-max-v1"
      ),
      "max-pipelined"
    )

  test("production generic ChainSync runtime matches generated min-pipelined final-chain cases"):
    verifyPipelinedFinalChains(
      readPipelineRows(
        "chainsync-pipelined-min.tsv",
        "0816948de7bcedcf89ce206214b0cc607d7e58c9a2ac94aa22d539ffd2e1e423",
        "chainsync-pipelined-min-v1"
      ),
      "min-pipelined"
    )

  private def replayRolling(
      client: ChainSyncRuntime.Pipeline[IO, Bytes, Bytes, Bytes],
      testCase: FixtureCase,
      chain: Ref[IO, Vector[Bytes]],
      low: Int,
      high: Int
  )(using Raise[IO, NetworkError]): IO[Unit] =
    val row = testCase.row
    def decoded(bytes: Bytes): Node = Cbor.decode(bytes).fold(e => fail(e.toString), identity)
    def array(node: Node): Vector[Node] = node.value match
      case Value.Arr(items) => items
      case other            => fail(s"seed ${row.seed} expected original array, got $other")
    def blockNo(node: Node): ChainSync.UInt64 = node.value match
      case Value.UInt(value) => ChainSync.UInt64.from(value).fold(e => fail(e.message), identity)
      case other             => fail(s"seed ${row.seed} expected original block number, got $other")
    // The unchanged native ConcreteBlock codec stores headerBlockNo in the fourth header field.
    def height(payload: Bytes): ChainSync.UInt64 = blockNo(array(array(decoded(payload))(0))(3))
    val server = Some(blockNo(array(decoded(row.targetTip))(1)))
    val window =
      ChainSyncPipelineDecision.Window.checked(low, high).fold(e => fail(e.toString), identity)
    def applyUpdate(
        update: ChainSyncRuntime.Update[Bytes, Bytes, Bytes],
        handled: Boolean
    ): IO[Unit] = update match
      case ChainSyncRuntime.Update.RollBackward(point, _) =>
        assert(!handled, s"seed ${row.seed} unexpected rollback after the initial fork alignment")
        val retained =
          if point == row.genesisPoint then Vector.empty
          else
            val found = testCase.fork.indexWhere(_.point == point)
            assert(found >= 0, s"seed ${row.seed} rollback point must remain on original fork")
            testCase.fork.take(found + 1).map(_.payload)
        chain.set(retained)
      case ChainSyncRuntime.Update.RollForward(payload, _) =>
        assert(handled, s"seed ${row.seed} expected initial rollback before rolling headers")
        assert(testCase.pointByPayload.contains(payload), s"seed ${row.seed} original block item")
        chain.update(_ :+ payload)
    for
      occupancy <- Ref.of[IO, Vector[Int]](Vector.empty)
      _ <- cats.Monad[IO].tailRecM((ChainSyncPipelineDecision.Policy(window), false)) {
        (policy, handled) =>
          (chain.get, client.outstanding).tupled.flatMap { (current, count) =>
            occupancy.update(_ :+ count) *> (
              if handled && current.lastOption
                  .flatMap(testCase.pointByPayload.get)
                  .contains(row.targetPoint)
              then
                client.drain(IO.unit)(_ =>
                  IO(fail(s"seed ${row.seed} request beyond generated target"))
                ) *>
                  client.done.as(Right(()))
              else
                val (action, next) = policy
                  .decide(count, current.lastOption.map(height), server)
                  .fold(e => fail(e.toString), identity)
                val step = action match
                  case ChainSyncPipelineDecision.Action.Request =>
                    client.submit *> client
                      .collect(IO.unit)
                      .flatMap(update => applyUpdate(update, handled))
                      .as(true)
                  case ChainSyncPipelineDecision.Action.Pipeline => client.submit.as(false)
                  case ChainSyncPipelineDecision.Action.Collect =>
                    client.collect(IO.unit).flatMap(update => applyUpdate(update, handled)).as(true)
                  case ChainSyncPipelineDecision.Action.CollectOrPipeline =>
                    client.tryCollect(IO.unit).flatMap {
                      case Some(update) => applyUpdate(update, handled).as(true)
                      case None         => client.submit.as(false)
                    }
                step.map(collected => Left((next, handled || collected)))
            )
          }
      }
      observed <- occupancy.get
      remaining <- client.outstanding
    yield
      assert(observed.nonEmpty)
      assertEquals(observed.min, 0, s"seed ${row.seed} initial rolling occupancy")
      assert(
        observed.max > 0 && observed.max <= high,
        s"seed ${row.seed} occupancy ${observed.min}..${observed.max}, low=$low high=$high"
      )
      assertEquals(remaining, 0, s"seed ${row.seed} all ordered replies collected before Done")

  private def verifyRollingFinalChains(rows: Vector[PipelineLowHighRow]): IO[Unit] =
    val fixture = readFixture()
    assertEquals(rows.map(_.seed), fixture.map(_.row.seed))
    fixture.zip(rows).traverse_ { (testCase, generated) =>
      source(testCase).flatMap { oracle =>
        Ref.of[IO, Vector[Bytes]](testCase.fork.map(_.payload)).flatMap { chain =>
          typed {
            linked(protocols = Set(Protocols.ChainSync)).use { (clientMux, serverMux) =>
              for
                clientChannel <- clientMux.channel(Protocols.ChainSync)
                serverChannel <- serverMux.channel(Protocols.ChainSync)
                _ <- Resource
                  .make(
                    Fibers
                      .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                        ChainSyncRuntime.run(
                          serverChannel,
                          pointCodec,
                          tipCodec,
                          payloadCodec,
                          oracle,
                          ChainSyncRuntime.ServerSettings(idleTimeout = 5.seconds)
                        )
                      }
                      .start
                  )(_.cancel)
                  .use { server =>
                    ChainSyncRuntime
                      .resource(
                        clientChannel,
                        pointCodec,
                        tipCodec,
                        payloadCodec,
                        clientMux.abort,
                        ChainSyncRuntime.ClientSettings(
                          intersectTimeout = 5.seconds,
                          canAwaitTimeout = 5.seconds,
                          mustReplyTimeout = 5.seconds
                        ),
                        maxOutstanding = math.max(generated.firstDepth, generated.secondDepth)
                      )
                      .use { client =>
                        for
                          intersection <- client.findIntersect(testCase.candidatePoints)
                          _ <- replayRolling(
                            client,
                            testCase,
                            chain,
                            math.min(generated.firstDepth, generated.secondDepth),
                            math.max(generated.firstDepth, generated.secondDepth)
                          ).timeout(10.seconds)
                          result <- server.joinWithNever
                          _ <- Fibers.land[IO, NetworkError, Unit](result)
                          finalChain <- chain.get
                        yield
                          assertEquals(
                            intersection,
                            testCase.row.expectedIntersection.map(_ -> testCase.row.targetTip)
                          )
                          assertEquals(
                            finalChain,
                            testCase.producer.map(_.payload),
                            s"seed ${generated.seed} rolling final chain equals unchanged native oracle"
                          )
                      }
                  }
              yield ()
            }
          }.map(result => assertEquals(result, Right(()), s"seed ${generated.seed}"))
        }
      }
    }

  test("production generic ChainSync runtime matches generated low/high final-chain cases"):
    val generated = readPipelinePairRows(
      "chainsync-pipelined-low-high.tsv",
      "5b1cddbee3ac695ab8a796aca7701c18ad95b4d85d72c03c4542b68fb782052b",
      "chainsync-pipelined-low-high-v1"
    )
    assertEquals(generated.size, 24)
    assertEquals(generated.map(_.seed), Vector.range(504001, 504025))
    assertEquals(generated.map(_.ordinal), Vector.range(0, 24))
    assert(generated.forall(row => row.size == 30 + row.ordinal % 21))
    assert(generated.forall(row => row.firstDepthSeed == row.seed + 7000003))
    assert(generated.forall(row => row.secondDepthSeed == row.seed + 8000003))
    assert(generated.forall(row => row.firstDepth >= 0 && row.firstDepth <= 300))
    assert(generated.forall(row => row.secondDepth >= 0 && row.secondDepth <= 300))
    assertEquals(generated.count(_.outcome == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.outcome == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.outcome == PipelineOutcome.PreconditionDiscard), 0)
    verifyRollingFinalChains(generated)

  test("production generic ChainSync runtime matches min-buffered final-chain cases"):
    val generated = readPipelinePairRows(
      "chainsync-pipelined-min-buffered.tsv",
      "801615d3e3238aa142ccf61102352d4f2f9dc63e61cf2e044edefd37b9769dd6",
      "chainsync-pipelined-min-buffered-v1"
    )
    assertEquals(generated.size, 24)
    assertEquals(generated.map(_.seed), Vector.range(504001, 504025))
    assertEquals(generated.map(_.ordinal), Vector.range(0, 24))
    assert(generated.forall(row => row.size == 30 + row.ordinal % 21))
    assert(generated.forall(row => row.firstDepthSeed == row.seed + 7000003))
    assert(generated.forall(row => row.secondDepthSeed == row.seed + 8000003))
    assert(generated.forall(row => row.firstDepth > 0 && row.firstDepth <= 300))
    assert(generated.forall(row => row.secondDepth > 0 && row.secondDepth <= 300))
    assertEquals(generated.count(_.outcome == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.outcome == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.outcome == PipelineOutcome.PreconditionDiscard), 0)
    verifyPipelinedFinalChains(
      generated.map(row =>
        PipelineRow(
          row.seed,
          row.ordinal,
          row.size,
          row.firstDepthSeed,
          math.max(row.firstDepth, row.secondDepth),
          row.outcome
        )
      ),
      "min-buffered"
    )

  test("production generic ChainSync runtime replays unchanged direct/connect pipeline cases"):
    val generated = readPipelineDirectConnectRows(
      "1f3ab510123a287f294efd0869c2b33973c175c28a19d144b46869688522668c"
    )
    assertEquals(generated.size, 24)
    assertEquals(generated.map(_.seed), Vector.range(504001, 504025))
    assertEquals(generated.map(_.ordinal), Vector.range(0, 24))
    assert(generated.forall(row => row.size == 30 + row.ordinal % 21))
    assert(generated.forall(row => row.depthSeed == row.seed + 7000003))
    assert(generated.forall(row => row.choicesSeed == row.seed + 9000007))
    assert(generated.forall(row => row.maxDepth > 0 && row.maxDepth <= 300))
    assert(generated.forall(row => row.choices.forall(choice => choice == '0' || choice == '1')))
    assertEquals(generated.map(_.choices).map(_.count(_ == '1')).sum, 282)
    assertEquals(generated.map(_.choices).map(_.count(_ == '0')).sum, 283)
    assertEquals(generated.count(_.maxDirect == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.minDirect == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.maxConnect == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.minConnect == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.maxDirectIO == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.minDirectIO == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.maxConnectIO == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.minConnectIO == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.maxDirect == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.minDirect == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.maxConnect == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.minConnect == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.maxDirectIO == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.minDirectIO == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.maxConnectIO == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.minConnectIO == PipelineOutcome.UpstreamFailure), 0)
    verifyPipelinedFinalChains(
      generated.map(row =>
        PipelineRow(
          row.seed,
          row.ordinal,
          row.size,
          row.depthSeed,
          row.maxDepth,
          PipelineOutcome.Pass
        )
      ),
      "direct/connect max/min-pipelined"
    )

  test("production generic ChainSync runtime replays unchanged demo pipeline IO cases"):
    val generated = readPipelineDemoIORows(
      "e212c604ca458021e456b39599cda7665f799a0e4832d7c999a9aabe843be997"
    )
    assertEquals(generated.size, 24)
    assertEquals(generated.map(_.seed), Vector.range(504001, 504025))
    assertEquals(generated.map(_.ordinal), Vector.range(0, 24))
    assert(generated.forall(row => row.size == 30 + row.ordinal % 21))
    assert(generated.forall(row => row.firstDepthSeed == row.seed + 7000003))
    assert(generated.forall(row => row.secondDepthSeed == row.seed + 8000003))
    assert(generated.forall(row => row.firstDepth >= 0 && row.firstDepth <= 300))
    assert(generated.forall(row => row.secondDepth >= 0 && row.secondDepth <= 300))
    assertEquals(generated.count(_.maxOutcome == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.minOutcome == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.lowHighOutcome == PipelineOutcome.Pass), 24)
    assertEquals(generated.count(_.maxOutcome == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.minOutcome == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.lowHighOutcome == PipelineOutcome.UpstreamFailure), 0)
    assertEquals(generated.count(_.maxOutcome == PipelineOutcome.PreconditionDiscard), 0)
    assertEquals(generated.count(_.minOutcome == PipelineOutcome.PreconditionDiscard), 0)
    assertEquals(generated.count(_.lowHighOutcome == PipelineOutcome.PreconditionDiscard), 0)
    verifyPipelinedFinalChains(
      generated.map(row =>
        PipelineRow(
          row.seed,
          row.ordinal,
          row.size,
          row.firstDepthSeed,
          row.firstDepth,
          row.maxOutcome
        )
      ),
      "max-pipelined IO"
    ) *> verifyPipelinedFinalChains(
      generated.map(row =>
        PipelineRow(
          row.seed,
          row.ordinal,
          row.size,
          row.firstDepthSeed,
          row.firstDepth,
          row.minOutcome
        )
      ),
      "min-pipelined IO"
    ) *> verifyPipelinedFinalChains(
      generated.map(row =>
        PipelineRow(
          row.seed,
          row.ordinal,
          row.size,
          row.firstDepthSeed,
          math.max(row.firstDepth, row.secondDepth),
          row.lowHighOutcome
        )
      ),
      "low/high-pipelined IO"
    )
