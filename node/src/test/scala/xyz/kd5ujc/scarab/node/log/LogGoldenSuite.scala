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
import xyz.kd5ujc.scarab.node.InboundEvent
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
        _ <- errors.traverse_(e => node(NodeEvent.Failed(e)))
        _ <- node(NodeEvent.Memory(memory))
        _ <- node(NodeEvent.CpuStarvation(250.millis, 0.1, 1.second))
        _ <- node(NodeEvent.Listening("0.0.0.0:3001"))
        _ <- node(NodeEvent.HeadersChecked(50000L, 12L, 3L))
        _ <- node(NodeEvent.Inbound("/tmp/node.socket", InboundEvent.Accepted))
        _ <- node(NodeEvent.Reused("10.0.0.1:3001", "127.0.0.1:3001"))
        _ <- node(NodeEvent.ChainSyncRounds(200L, 25600L, 120L, 900L, 128))
        _ <- node(NodeEvent.SlowFetch(300L))
        _ <- node(NodeEvent.Stalled(10.minutes, BigInt(6809207), BigInt(10200000)))
        _ <- node(NodeEvent.StallExit(30.minutes, BigInt(6809207), BigInt(10200000)))
        _ <- inbound.traverse_(Log.inbound[IO].emit("10.0.0.1:3001", _))
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
  }

  test("the JSON line is the event, the time and the fields, as JsonLog wrote it") {
    val line = summon[Traced[NodeEvent]].line(NodeEvent.SlowFetch(300L))
    assertEquals(
      Render.json(line, at),
      """{"event":"slowFetch","at":"2026-10-16T12:00:00Z","millis":300}"""
    )
    assertEquals(line.kind.namespace, "Chain.SlowFetch")
  }
