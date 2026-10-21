// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.mtl.Raise
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.io.Source
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSyncEngine, Handshake, NetworkError}

import Peers.*

/** Replays the unchanged generated inputs from the registered upstream Pipe IO property through
  * Scarab's production linked Mux ChainSync runtime. This checks exact mock-domain wire items and
  * final ordered chain state; the linked Mux profile does not claim OS pipe or scheduler parity.
  */
class UpstreamPipeBehaviorSuite extends FailsOnUncaught:
  private val resourceRoot = "/upstream/ouroboros-network-protocol-codecs/"
  private val casesPath = resourceRoot + "chainsync-pipe-demo-cases.tsv"
  private val blocksPath = resourceRoot + "chainsync-pipe-demo-blocks.tsv"
  private val updatesPath = resourceRoot + "chainsync-pipe-demo-updates.tsv"
  private val socketOutcomesPath = resourceRoot + "chainsync-socket-demo-outcomes.tsv"
  private val casesSha = "3a9f3f099bdd6012b30ed8b9376481d3ee0745aca29ebb0e1c2a49470799b0f1"
  private val blocksSha = "a9ba09ef34443b1922a56e99b9f590d6420d2eacdcc4cee8d591547d9d69f82e"
  private val updatesSha = "d311f1b8ba5656593767564dfa139ba9517167714dcf2f489f47d9a9793b0c10"
  private val socketOutcomesSha = "8c0ef4d4574e16c2663da6ff01d6bd09a016b91eca89fbef8a1cc6dbbb75537e"

  private final case class CaseRow(
      seed: Int,
      ordinal: Int,
      size: Int,
      outcome: String,
      initialCount: Int,
      candidateCount: Int,
      updateCount: Int,
      finalCount: Int,
      initialPoint: Bytes,
      finalPoint: Bytes,
      finalTip: Bytes,
      initialTip: Bytes,
      stopCount: Int
  )
  private enum BlockKind:
    case Initial, Final, Candidate
  private final case class BlockRow(
      caseOrdinal: Int,
      ordinal: Int,
      kind: BlockKind,
      payloadOrPoint: Bytes,
      point: Option[Bytes]
  )
  private enum UpdateKind:
    case Forward, Backward
  private final case class UpdateRow(
      caseOrdinal: Int,
      ordinal: Int,
      kind: UpdateKind,
      payload: Option[Bytes],
      point: Bytes,
      tip: Bytes
  )
  private final case class FixtureCase(
      row: CaseRow,
      initial: Vector[BlockRow],
      candidates: Vector[Bytes],
      updates: Vector[UpdateRow],
      expectedFinal: Vector[BlockRow]
  )

  private def read(path: String, expectedSha: String): Vector[String] =
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

  private def readCases(): Vector[CaseRow] =
    read(casesPath, casesSha).map { line =>
      val f = line.split("\\t", -1).toVector
      assertEquals(f.size, 14)
      assertEquals(f.head, "ouroboros-pipe-demo-v1")
      CaseRow(
        f(1).toInt,
        f(2).toInt,
        f(3).toInt,
        f(4),
        f(5).toInt,
        f(6).toInt,
        f(7).toInt,
        f(8).toInt,
        fromHex(f(9)),
        fromHex(f(10)),
        fromHex(f(11)),
        fromHex(f(12)),
        f(13).toInt
      )
    }

  private def readBlocks(): Vector[BlockRow] =
    read(blocksPath, blocksSha).map { line =>
      val f = line.split("\\t", -1).toVector
      f.head match
        case "candidate" =>
          assertEquals(f.size, 4)
          BlockRow(f(1).toInt, f(2).toInt, BlockKind.Candidate, fromHex(f(3)), None)
        case "initial" | "final" =>
          assertEquals(f.size, 5)
          val kind = if f.head == "initial" then BlockKind.Initial else BlockKind.Final
          BlockRow(f(1).toInt, f(2).toInt, kind, fromHex(f(3)), Some(fromHex(f(4))))
        case other => fail(s"unknown upstream Pipe block row kind: $other")
    }

  private def readUpdates(): Vector[UpdateRow] =
    read(updatesPath, updatesSha).map { line =>
      val f = line.split("\\t", -1).toVector
      assertEquals(f.size, 6)
      val (kind, payload) = f(2) match
        case "roll-forward" => UpdateKind.Forward -> Some(fromHex(f(3)))
        case "roll-backward" =>
          assertEquals(f(3), "")
          UpdateKind.Backward -> None
        case other => fail(s"unknown upstream Pipe update kind: $other")
      UpdateRow(f(0).toInt, f(1).toInt, kind, payload, fromHex(f(4)), fromHex(f(5)))
    }

  private def fixture(): Vector[FixtureCase] =
    val blocks = readBlocks()
    val updates = readUpdates()
    readCases().map { row =>
      val initial = blocks
        .filter(r => r.caseOrdinal == row.ordinal && r.kind == BlockKind.Initial)
        .sortBy(_.ordinal)
      val candidates = blocks
        .filter(r => r.caseOrdinal == row.ordinal && r.kind == BlockKind.Candidate)
        .sortBy(_.ordinal)
      val expectedFinal = blocks
        .filter(r => r.caseOrdinal == row.ordinal && r.kind == BlockKind.Final)
        .sortBy(_.ordinal)
      val caseUpdates = updates.filter(_.caseOrdinal == row.ordinal).sortBy(_.ordinal)
      assertEquals(initial.map(_.ordinal), Vector.range(0, row.initialCount))
      assertEquals(candidates.map(_.ordinal), Vector.range(0, row.candidateCount))
      assertEquals(expectedFinal.map(_.ordinal), Vector.range(0, row.finalCount))
      assertEquals(caseUpdates.map(_.ordinal), Vector.range(0, row.updateCount))
      assertEquals(initial.lastOption.flatMap(_.point).getOrElse(fromHex("80")), row.initialPoint)
      assertEquals(
        expectedFinal.lastOption.flatMap(_.point).getOrElse(fromHex("80")),
        row.finalPoint
      )
      FixtureCase(row, initial, candidates.map(_.payloadOrPoint), caseUpdates, expectedFinal)
    }

  private val pointCodec: ChainSyncEngine.PointCodec[Bytes] =
    ChainSyncEngine.pointCodec(Right(_), Right(_))
  private val tipCodec: ChainSyncEngine.TipCodec[Bytes] =
    ChainSyncEngine.tipCodec(Right(_), Right(_))
  private val payloadCodec: ChainSyncEngine.PayloadCodec[Bytes] =
    ChainSyncEngine.payloadCodec(Right(_), Right(_))

  private def source(testCase: FixtureCase): IO[ChainSyncRuntime.Source[IO, Bytes, Bytes, Bytes]] =
    Ref.of[IO, Int](0).map { cursor =>
      new ChainSyncRuntime.Source[IO, Bytes, Bytes, Bytes]:
        def intersect(points: Vector[Bytes]): IO[Either[Bytes, (Bytes, Bytes)]] =
          val chainPoints = fromHex("80") +: testCase.initial.flatMap(_.point)
          val found = points.find(chainPoints.contains)
          IO.pure(
            found.fold[Either[Bytes, (Bytes, Bytes)]](Left(testCase.row.initialTip))(point =>
              Right(point -> testCase.row.initialTip)
            )
          )

        def poll: IO[Option[ChainSyncRuntime.Update[Bytes, Bytes, Bytes]]] =
          cursor.modify { index =>
            testCase.updates.lift(index) match
              case None => index -> None
              case Some(update) =>
                val message = update.kind match
                  case UpdateKind.Forward =>
                    ChainSyncRuntime.Update.RollForward(
                      update.payload.fold(fail("missing forward payload"))(identity),
                      update.tip
                    )
                  case UpdateKind.Backward =>
                    ChainSyncRuntime.Update.RollBackward(update.point, update.tip)
                (index + 1) -> Some(message)
          }

        def await: IO[ChainSyncRuntime.Update[Bytes, Bytes, Bytes]] = IO.never
    }

  private def replay(
      client: ChainSyncRuntime.Client[IO, Bytes, Bytes, Bytes],
      testCase: FixtureCase
  )(using Raise[IO, NetworkError]): IO[(Vector[Bytes], Int)] =
    val row = testCase.row
    val payloadPoints = (testCase.initial ++ testCase.updates.collect {
      case u if u.kind == UpdateKind.Forward =>
        BlockRow(
          row.ordinal,
          u.ordinal,
          BlockKind.Final,
          u.payload.fold(fail("missing forward payload"))(identity),
          Some(u.point)
        )
    }).flatMap(b => b.point.map(b.payloadOrPoint -> _))
    assert(
      payloadPoints.groupBy(_._1).values.forall(rows => rows.map(_._2).distinct.size == 1),
      s"seed ${row.seed} serialized-block aliases preserve point identity"
    )
    val knownPointByPayload = payloadPoints.toMap
    val initial = testCase.initial.map(_.payloadOrPoint)

    def trim(chain: Vector[Bytes], point: Bytes): Vector[Bytes] =
      if point == fromHex("80") then Vector.empty
      else
        val found = chain.indexWhere(payload => knownPointByPayload.get(payload).contains(point))
        assert(found >= 0, s"seed ${row.seed} upstream rollback point must be on current chain")
        chain.take(found + 1)

    client.findIntersect(testCase.candidates).flatMap { intersection =>
      if testCase.candidates.isEmpty then
        assertEquals(intersection, None, s"seed ${row.seed} empty generated candidate list")
      else
        assert(
          intersection.exists { case (point, tip) =>
            testCase.candidates.contains(point) && tip == row.initialTip
          },
          s"seed ${row.seed} generated candidate should intersect the initial chain"
        )
      if testCase.initial.lastOption.flatMap(_.point).getOrElse(fromHex("80")) == row.finalPoint
      then client.done.as(initial -> 0)
      else
        cats.Monad[IO].tailRecM((initial, 0)) { case (current, updateOrdinal) =>
          val expected = testCase.updates
            .lift(updateOrdinal)
            .fold(
              fail(s"seed ${row.seed} source exhausted before reaching generated final point")
            )(identity)
          client.requestNext.flatMap {
            case ChainSyncRuntime.Update.RollForward(payload, tip) =>
              assertEquals(
                expected.kind,
                UpdateKind.Forward,
                s"seed ${row.seed} update $updateOrdinal constructor"
              )
              assertEquals(
                expected.payload,
                Some(payload),
                s"seed ${row.seed} update $updateOrdinal exact payload item"
              )
              assertEquals(
                expected.tip,
                tip,
                s"seed ${row.seed} update $updateOrdinal exact tip item"
              )
              val point = knownPointByPayload
                .get(payload)
                .fold(fail(s"seed ${row.seed} unknown exact generated block item"))(identity)
              val next = current :+ payload
              if point == row.finalPoint then client.done.as(Right(next -> (updateOrdinal + 1)))
              else IO.pure(Left(next -> (updateOrdinal + 1)))
            case ChainSyncRuntime.Update.RollBackward(point, tip) =>
              assertEquals(
                expected.kind,
                UpdateKind.Backward,
                s"seed ${row.seed} update $updateOrdinal constructor"
              )
              assertEquals(
                expected.point,
                point,
                s"seed ${row.seed} update $updateOrdinal exact rollback point"
              )
              assertEquals(
                expected.tip,
                tip,
                s"seed ${row.seed} update $updateOrdinal exact tip item"
              )
              val next = trim(current, point)
              if point == row.finalPoint then client.done.as(Right(next -> (updateOrdinal + 1)))
              else IO.pure(Left(next -> (updateOrdinal + 1)))
          }
        }
    }

  private def socketReplay(
      testCase: FixtureCase
  ): IO[Either[NetworkError, (Vector[Bytes], Int)]] = typed {
    Network[IO]
      .bind(SocketAddress(ip"127.0.0.1", port"0"))
      .use { listener =>
        val server = Fibers.scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
          listener.accept
            .take(1)
            .evalMap { socket =>
              Mux
                .resource(
                  Bearer.fromSocket(socket),
                  NodeToNodeServer.Protocol,
                  Mux.Config(role = Mux.Role.Responder)
                )
                .use { mux =>
                  for
                    handshake <- mux.channel(Protocols.Handshake)
                    agreement <- HandshakeServer.run(
                      handshake,
                      HandshakeServer.offers(0L, duplex = false)
                    )
                    _ <- IO(assertEquals(agreement.version, 15))
                    channel <- mux.channel(Protocols.ChainSync)
                    oracle <- source(testCase)
                    _ <- ChainSyncRuntime.run(
                      channel,
                      pointCodec,
                      tipCodec,
                      payloadCodec,
                      oracle,
                      ChainSyncRuntime.ServerSettings(idleTimeout = 20.seconds)
                    )
                  yield ()
                }
            }
            .compile
            .drain
        }
        Resource.make(server.start)(_.cancel).use { serverFiber =>
          val address = SocketAddress(host"localhost", listener.address.asIpUnsafe.port)
          Bearer.tcp[IO](address, 5.seconds).use { bearer =>
            Mux.resource(bearer, NodeToNodeServer.Protocol).use { mux =>
              for
                handshake <- mux.channel(Protocols.Handshake)
                agreement <- HandshakeClient.run(
                  handshake,
                  Handshake.defaultNodeToNode(0L),
                  timeout = 5.seconds
                )
                _ <- IO(assertEquals(agreement.version, 15))
                channel <- mux.channel(Protocols.ChainSync)
                client <- ChainSyncRuntime.client(
                  channel,
                  pointCodec,
                  tipCodec,
                  payloadCodec,
                  ChainSyncRuntime.ClientSettings(
                    intersectTimeout = 5.seconds,
                    canAwaitTimeout = 5.seconds,
                    mustReply = ChainSyncTimeouts.Policy.Fixed(ProtocolDeadline.After(5.seconds))
                  )
                )
                result <- replay(client, testCase).timeout(20.seconds)
                serverResult <- serverFiber.joinWithNever.timeout(5.seconds)
                _ <- Fibers.land[IO, NetworkError, Unit](serverResult)
              yield result
            }
          }
        }
      }
  }

  test(
    "unchanged registered upstream Pipe IO property rows replay through production linked Mux ChainSync"
  ):
    val cases = fixture()
    assertEquals(cases.size, 24)
    assertEquals(cases.map(_.row.seed), Vector.range(505001, 505025))
    assertEquals(cases.map(_.row.size), (0 until 24).map(i => 30 + i % 21).toVector)
    assertEquals(cases.count(_.row.outcome == "pass"), 24)
    assertEquals(cases.count(_.row.outcome == "upstream-failure"), 0)
    assertEquals(cases.map(_.initial.size).sum, 364)
    assertEquals(cases.map(_.expectedFinal.size).sum, 587)
    assertEquals(cases.map(_.updates.size).sum, 517)
    assertEquals(cases.map(_.candidates.size).sum, 165)
    assertEquals(cases.count(_.updates.isEmpty), 1)
    assertEquals(cases.count(_.initial.isEmpty), 1)
    assertEquals(cases.count(_.candidates.isEmpty), 1)
    assertEquals(cases.flatMap(_.updates).count(_.kind == UpdateKind.Forward), 416)
    assertEquals(cases.flatMap(_.updates).count(_.kind == UpdateKind.Backward), 101)
    assertEquals(cases.map(_.row.stopCount).sum, 509)
    assertEquals(
      cases.filter(testCase => testCase.row.stopCount < testCase.updates.size).map(_.row.seed),
      Vector(505011, 505020, 505021)
    )
    assertEquals(cases.map(testCase => testCase.updates.size - testCase.row.stopCount).sum, 8)

    cases
      .traverse { testCase =>
        source(testCase).flatMap { oracle =>
          typed {
            linked(protocols = Set(Protocols.ChainSync)).use { (clientMux, serverMux) =>
              for
                clientChannel <- clientMux.channel(Protocols.ChainSync)
                serverChannel <- serverMux.channel(Protocols.ChainSync)
                client <- ChainSyncRuntime.client(
                  clientChannel,
                  pointCodec,
                  tipCodec,
                  payloadCodec,
                  ChainSyncRuntime.ClientSettings(
                    intersectTimeout = 5.seconds,
                    canAwaitTimeout = 5.seconds,
                    mustReply = ChainSyncTimeouts.Policy.Fixed(ProtocolDeadline.After(5.seconds))
                  )
                )
                replayed <- Resource
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
                    for
                      result <- replay(client, testCase).timeout(10.seconds)
                      serverResult <- server.joinWithNever
                      _ <- Fibers.land[IO, NetworkError, Unit](serverResult)
                    yield result
                  }
              yield replayed
            }
          }.map { result =>
            val value = result.fold(
              error => fail(s"seed ${testCase.row.seed} network failure: ${error.message}"),
              identity
            )
            assertEquals(
              value._1,
              testCase.expectedFinal.map(_.payloadOrPoint),
              s"seed ${testCase.row.seed} full final chain equals unchanged upstream oracle"
            )
            assert(
              value._2 <= testCase.updates.size,
              s"seed ${testCase.row.seed} consumed no more than generated updates"
            )
            assertEquals(
              value._2,
              testCase.row.stopCount,
              s"seed ${testCase.row.seed} Haskell consumer stop ordinal"
            )
            value._2
          }
        }
      }
      .map { results =>
        assertEquals(results.sum, 509)
        assertEquals(
          cases.zip(results).collect {
            case (testCase, count) if count < testCase.updates.size => testCase.row.seed
          },
          Vector(505011, 505020, 505021),
          "the original consumer stops at the target point; all unused generated tail events remain in the fixture"
        )
      }

  test("pinned Socket demo runs the same generated rows over TCP and production Handshake"):
    val socketRows = read(socketOutcomesPath, socketOutcomesSha).map(_.split("\\t", -1).toVector)
    val cases = fixture()
    assertEquals(socketRows.size, cases.size)
    assertEquals(socketRows.map(_.head), Vector.fill(24)("ouroboros-socket-demo-v1"))
    assertEquals(socketRows.map(_(4)), Vector.fill(24)("pass"))
    cases.traverse_ { testCase =>
      val row = socketRows(testCase.row.ordinal)
      assertEquals(row.size, 5)
      assertEquals(row(1).toInt, testCase.row.seed)
      assertEquals(row(2).toInt, testCase.row.ordinal)
      assertEquals(row(3).toInt, testCase.row.size)
      socketReplay(testCase).timeout(30.seconds).map { result =>
        val value = result.fold(
          error => fail(s"seed ${testCase.row.seed} TCP network failure: ${error.message}"),
          identity
        )
        assertEquals(
          value._1,
          testCase.expectedFinal.map(_.payloadOrPoint),
          s"seed ${testCase.row.seed} TCP final chain equals original generated Haskell oracle"
        )
        assertEquals(
          value._2,
          testCase.row.stopCount,
          s"seed ${testCase.row.seed} TCP runtime stops at the recorded consumer target"
        )
      }
    }
