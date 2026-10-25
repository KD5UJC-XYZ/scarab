// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.compiletime.constValue
import scala.deriving.Mirror
import cats.Applicative
import cats.effect.{IO, Ref}
import scala.concurrent.duration.*
import cats.mtl.Tell
import cats.syntax.all.*
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.consensus.{FollowerEvent, SchedulingStats, VolatileChain}
import xyz.kd5ujc.scarab.network.{ConnectionEvent, ConnectionEvents}
import com.comcast.ip4s.{host, port, Host, SocketAddress}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.network.selection.{FetchMode, Gsm, Trace}
import xyz.kd5ujc.scarab.ledger.{ChainError, StorageError}
import xyz.kd5ujc.scarab.node.{Fatal, InboundEvent, LedgerPeersTrace, SyncAdvisory}
import xyz.kd5ujc.scarab.node.forge.{ForgeEvent, ForgeEvents}
import xyz.kd5ujc.scarab.node.mempool.MempoolEvent
import xyz.kd5ujc.scarab.storage.{StorageEvent, StorageEvents}

/** The JSON lines of the log, byte for byte: one line per sample (`LogSamples`), through the same
  * adapters as in the node, against `fixtures/log/golden.jsonl`. The golden was first written by
  * the `JsonLog` this log replaced; the scenarios and the run watchers parse these lines.
  */
class LogGoldenSuite extends CatsEffectSuite:
  import LogSamples.*

  private val golden = Path.of("fixtures/log/golden.jsonl")
  private val at = Instant.parse("2026-10-16T12:00:00Z")

  private def point(slot: Long, hash: Int): ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.saturating(slot), Bytes.wrap(Array(hash.toByte)))

  /** A `Log` that keeps every line. */
  private def collecting(lines: Ref[IO, Vector[Line]]): Log[IO] = new Tell[IO, Line]:
    def functor = Applicative[IO]
    def tell(line: Line) = lines.update(_ :+ line)

  private val build = NodeEvent.Build("0.1.0", "3.3.8", "1.10.7", "0123abcd")

  private val logged: IO[Vector[Line]] =
    Ref.of[IO, Vector[Line]](Vector.empty).flatMap { lines =>
      given log: Log[IO] = collecting(lines)
      def node(e: NodeEvent) = log.emit(e)
      for
        sched <- SchedulingStats.create[IO]
        _ <- sched.timer(70L) *> sched.step("rules", 40000000L)
        _ <- node(NodeEvent.Started(build, peers, setup.genesis, setup.config, Some(topology)))
        _ <- node(
          NodeEvent
            .Started(build, peers, setup.genesis, xyz.kd5ujc.scarab.node.NodeConfig.empty, None)
        )
        _ <- blstReports.traverse_(r => node(NodeEvent.Blst(r)))
        _ <- plutusCryptoReports.traverse_(r => node(NodeEvent.PlutusCrypto(r)))
        _ <- errors.traverse_(e => node(NodeEvent.Failed(e)))
        _ <- node(
          NodeEvent.ReceivedBlockDiscarded(
            hash,
            StorageError.Corrupt("received block", xyz.kd5ujc.scarab.codec.Cbor.Error.UnexpectedEnd)
          )
        )
        _ <- node(NodeEvent.Memory(memory))
        _ <- node(NodeEvent.CpuStarvation(250.millis, 0.1, 1.second))
        _ <- node(NodeEvent.PraosSyncUntrusted(SyncAdvisory.Position.FromOrigin))
        _ <- node(NodeEvent.PraosSyncUntrusted(SyncAdvisory.Position.Behind(BigInt(432001))))
        _ <- node(NodeEvent.Listening("0.0.0.0:3001"))
        _ <- node(NodeEvent.HeadersChecked(50000L, 12L, 3L))
        _ <- node(NodeEvent.Inbound("/tmp/node.socket", InboundEvent.Accepted))
        _ <- node(NodeEvent.ChainSyncRounds(200L, 25600L, 120L, 900L, 128))
        _ <- node(NodeEvent.SlowFetch(300L))
        _ <- node(NodeEvent.Stalled(10.minutes, BigInt(6809207), BigInt(10200000)))
        _ <- node(NodeEvent.StalledBehind(LogSamples.point, held))
        _ <- node(
          NodeEvent.StallState(
            xyz.kd5ujc.scarab.node.StallDiagnostics.Report(
              Vector(
                xyz.kd5ujc.scarab.node.FetchCoordinator.SessionDiagnostic(
                  session = 3L,
                  busy = true,
                  pendingOffers = 1,
                  pendingHeaders = 20,
                  active = Vector(xyz.kd5ujc.scarab.node.FetchCoordinator.ActiveFetch(5L, 2, 40)),
                  candidateHeaders = Some(100),
                  reserving = Some(
                    xyz.kd5ujc.scarab.node.FetchCoordinator
                      .Reserving(
                        xyz.kd5ujc.scarab.node.FetchCoordinator.Phase.LockWait,
                        570.seconds
                      )
                  ),
                  pipeline = Some(
                    xyz.kd5ujc.scarab.node.Peer.PipelineState(
                      peer = SocketAddress(host"10.0.0.1", port"3001"),
                      permitsFree = 0,
                      permits = 3,
                      queued = 3,
                      candidateHeaders = 100,
                      unpublishedHeaders = Some(60),
                      capacityRoom = false,
                      candidateBase = point(800, 0xaa),
                      candidateTip = point(900, 0xbb),
                      publishedBase = point(800, 0xaa),
                      publishedTip = point(840, 0xcc),
                      candidateTipSlot = Some(900L),
                      publishedTipSlot = Some(840L),
                      lastReplyCollectedAgo = Some(601.seconds),
                      lastReplyAcceptedAgo = Some(600.seconds),
                      lastReplyHandledAgo = None
                    )
                  )
                )
              ),
              xyz.kd5ujc.scarab.node.BlockAdmissions.Occupancy(
                10,
                10,
                4,
                Some(
                  xyz.kd5ujc.scarab.node.BlockAdmissions
                    .Worker(
                      xyz.kd5ujc.scarab.node.BlockAdmissions.WorkKind.Add,
                      xyz.kd5ujc.scarab.node.BlockAdmissions.AdmissionStage.Applying,
                      12.seconds,
                      590.seconds
                    )
                ),
                2,
                Some(30.seconds)
              ),
              Some(
                xyz.kd5ujc.scarab.node.FetchCoordinator.LockHolder(
                  xyz.kd5ujc.scarab.node.FetchCoordinator.DecisionHolder.Reserve,
                  580.seconds
                )
              ),
              3L
            ),
            xyz.kd5ujc.scarab.node.StallDiagnostics.Dump.Written(
              Path.of("/stall/fiber-dump-1.txt")
            )
          )
        )
        _ <- node(
          NodeEvent.StallDumped(
            xyz.kd5ujc.scarab.node.StallDiagnostics.Dump.Written(
              Path.of("/stall/stall-state-1.json")
            ),
            xyz.kd5ujc.scarab.node.StallDiagnostics.Dump.Failed(
              xyz.kd5ujc.scarab.node.StallDiagnostics.DumpError.NoMonitor
            )
          )
        )
        _ <- node(
          NodeEvent.StallDumped(
            xyz.kd5ujc.scarab.node.StallDiagnostics.Dump.Skipped,
            xyz.kd5ujc.scarab.node.StallDiagnostics.Dump.Skipped
          )
        )
        _ <- node(NodeEvent.StallExit(30.minutes, BigInt(6809207), BigInt(10200000)))
        _ <- node(
          NodeEvent.Faulted(
            Fatal.Storage(StorageError.Io("volatile", new java.io.IOException("disk full")))
          )
        )
        _ <- node(NodeEvent.Faulted(Fatal.Internal(ChainError.NoIntersection)))
        _ <- node(NodeEvent.Faulted(Fatal.Defect(new IllegalStateException("a bug in the rules"))))
        _ <- inbound.traverse_(Log.inbound[IO].emit("10.0.0.1:3001", _))
        _ <- peerSelection.traverse_(t => node(NodeEvent.PeerSelection(t)))
        _ <- peerCounters.traverse_(c => node(NodeEvent.PeerCounters(c)))
        _ <- churnTraces.traverse_(t => node(NodeEvent.PeerChurn(t)))
        _ <- ledgerPeers.traverse_(t => node(NodeEvent.LedgerPeers(t)))
        _ <- outboundStates.traverse_(c => node(NodeEvent.OutboundConnections(c)))
        _ <- gsmEvents.traverse_((phase, e) => node(NodeEvent.GsmTransition(phase, e)))
        _ <- FetchMode.values.toList.traverse_(m => node(NodeEvent.FetchModeChanged(m)))
        _ <- connection.traverse_(Log.sink[ConnectionEvents, ConnectionEvent, IO].emit)
        _ <- mempool.traverse_(Log.mempool[IO].emit)
        _ <- forge.traverse_(Log.sink[ForgeEvents, ForgeEvent, IO].emit)
        _ <- follower.traverse_(Log.follower[IO](Some(sched)).emit)
        _ <- follower.traverse_(Log.follower[IO](None).emit)
        _ <- storage.traverse_(Log.sink[StorageEvents, StorageEvent, IO].emit)
        _ <- node(NodeEvent.finished(VolatileChain.genesis(2160, setup.mirrorStart, setup.origin)))
        // the lines JsonLog did not write
        _ <- node(NodeEvent.StateDumped(BigInt(4200), hash, BigInt(3), 120L))
        _ <- node(
          NodeEvent.StateDumpRefused(BigInt(4200), hash, BigInt(3), "Vocab(CoinOutOfRange)")
        )
        _ <- node(NodeEvent.VerificationCache("witness=1/2 opCert=3/4 vrf=5/6"))
        _ <- node(
          NodeEvent.Library("org.http4s.ember", Severity.Warning, "connection reset", None)
        )
        _ <- node(
          NodeEvent.Library("fs2.io", Severity.Error, "failed", Some("java.io.IOException: x"))
        )
        all <- lines.get
      yield all
    }

  test("every event renders as the golden says") {
    logged.map { lines =>
      val rendered = lines.map(Render.json(_, at)).toList
      val expected = Files.readString(golden, UTF_8).linesIterator.toList
      if rendered != expected then
        Files.createDirectories(Path.of("target"))
        Files.writeString(
          Path.of("target/log-golden.actual.jsonl"),
          rendered.mkString("", "\n", "\n")
        )
      assertEquals(rendered, expected)
    }
  }

  test("the golden has a line of every kind of event") {
    logged.map(lines => assertEquals(lines.map(_.kind).toSet, EventKind.values.toSet))
  }

  private inline def cases[T](using m: Mirror.SumOf[T]): Int =
    constValue[Tuple.Size[m.MirroredElemLabels]]

  test("the samples hold every case of every event type") {
    def covered[T](samples: List[T], count: Int)(using m: Mirror.SumOf[T]) =
      assertEquals(samples.map(m.ordinal).toSet, (0 until count).toSet, samples)
    covered(follower, cases[FollowerEvent])
    covered(storage, cases[StorageEvent])
    covered(connection, cases[ConnectionEvent])
    covered(mempool, cases[MempoolEvent])
    covered(forge, cases[ForgeEvent])
    covered(inbound, cases[InboundEvent])
    covered(peerSelection, cases[Trace[SocketAddress[Host]]])
    covered(gsmEvents.map(_._2), cases[Gsm.Event])
    covered(ledgerPeers, cases[LedgerPeersTrace])
  }

  test("the JSON line is the event, the time and the fields, as JsonLog wrote it") {
    val line = summon[Traced[NodeEvent]].line(NodeEvent.SlowFetch(300L))
    assertEquals(
      Render.json(line, at),
      """{"event":"slowFetch","at":"2026-10-16T12:00:00Z","millis":300}"""
    )
    assertEquals(line.kind.namespace, "Chain.SlowFetch")
  }
