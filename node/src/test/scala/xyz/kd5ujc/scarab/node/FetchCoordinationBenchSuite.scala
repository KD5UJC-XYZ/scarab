// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, FetchGsv, FetchMode}
import scala.concurrent.duration.*

/** Controlled virtual transport characterization, not a live-network throughput benchmark. Both
  * peers advertise the identical candidate. Per-peer body writers serialize bytes at 4Mbps while
  * outstanding requests overlap RTT. The baseline is an explicitly modeled idle-worker queue, not
  * an execution of Scarab's historical client. Fixed TestControl seed makes output repeatable.
  * SCARAB_BENCH=1 prints the observations used by docs/performance.md.
  */
class FetchCoordinationBenchSuite extends FailsOnUncaught:
  private val blockBytes = 32768L
  private val blockCount = 24
  private val requestLimit = 3
  private val serialization = (blockBytes * 2000).nanos
  private final case class Peer(id: Int, rtt: FiniteDuration):
    def model: FetchGsv.PeerGsv = FetchGsv.fromSample(0, BigInt(rtt.toNanos) * 1000)
  private val peers = Vector(Peer(0, 400.millis), Peer(1, 20.millis))
  private def header(n: Int): Header = Header.Byron(
    Bytes.empty,
    Bytes(Vector(n.toByte)),
    false,
    0,
    n,
    21600,
    n,
    Option.when(n > 0)(Bytes(Vector((n - 1).toByte)))
  )
  private val anchor = header(0)
  private val headers = (1 to blockCount).toVector.map(header)
  private val batches = headers.grouped(4).map(_.toVector).toVector
  private val candidate = ChainSelection.Candidate(anchor.point, headers)
  private val local = ChainSelection.Local(anchor.point, SelectView.of(anchor), Vector.empty)
  private val hints = headers.map(_.hash -> blockBytes).toMap

  private final case class Observation(
      unique: Set[Bytes] = Set.empty,
      bytes: Long = 0,
      duplicates: Long = 0,
      requests: Map[Int, Int] = Map.empty,
      outstanding: Map[Int, Long] = Map.empty,
      peakRequests: Map[Int, Int] = Map.empty,
      peakBytes: Map[Int, Long] = Map.empty,
      peakActivePeers: Int = 0,
      tipAt: Option[FiniteDuration] = None
  ):
    def admitted(peer: Int, count: Int, size: Long): Observation =
      val n = requests.getOrElse(peer, 0) + count
      val b = outstanding.getOrElse(peer, 0L) + size
      copy(
        requests = requests.updated(peer, n),
        peakActivePeers = peakActivePeers.max(requests.updated(peer, n).values.count(_ > 0)),
        outstanding = outstanding.updated(peer, b),
        peakRequests = peakRequests.updated(peer, peakRequests.getOrElse(peer, 0).max(n)),
        peakBytes = peakBytes.updated(peer, peakBytes.getOrElse(peer, 0L).max(b))
      )
    def arrived(peer: Int, hash: Bytes, now: FiniteDuration): Observation =
      val delivered = unique + hash
      copy(
        unique = delivered,
        bytes = bytes + blockBytes,
        duplicates = duplicates + (if unique(hash) then blockBytes else 0L),
        outstanding = outstanding.updated(peer, outstanding.getOrElse(peer, 0L) - blockBytes),
        tipAt = tipAt.orElse(Option.when(delivered.size == blockCount)(now))
      )
    def completed(peer: Int, count: Int): Observation =
      copy(requests = requests.updated(peer, requests.getOrElse(peer, 0) - count))

  private final case class Result(
      label: String,
      observation: Observation,
      start: FiniteDuration,
      drained: FiniteDuration
  ):
    def line: String =
      val tipMicros = observation.tipAt.fold(-1L)(t => (t - start).toMicros)
      s"fetch-coordination label=$label blocks=${observation.unique.size} " +
        s"tip_us=$tipMicros drain_us=${(drained - start).toMicros} " +
        s"bytes=${observation.bytes} duplicate_bytes=${observation.duplicates} " +
        s"peak_active_peers=${observation.peakActivePeers} " +
        s"peak_requests=${peers.map(p => s"${p.id}:${observation.peakRequests.getOrElse(p.id, 0)}").mkString(",")} " +
        s"peak_bytes=${peers.map(p => s"${p.id}:${observation.peakBytes.getOrElse(p.id, 0L)}").mkString(",")}"

  private def deliver(stats: Ref[IO, Observation], peer: Peer, hash: Bytes): IO[Unit] =
    IO.sleep(serialization) *> IO.monotonic.flatMap(now =>
      stats.update(_.arrived(peer.id, hash, now))
    )

  private def coordinated(mode: FetchMode, target: Int): IO[Result] =
    val config = BlockFetchPolicy.Config(target, target, requestLimit)
    for
      stats <- Ref.of[IO, Observation](Observation())
      fetched <- Ref.of[IO, Set[Bytes]](Set.empty)
      coordinator <- FetchCoordinator.create[IO](config, Some(IO.pure(local)), Some(fetched.get))
      began <- IO.monotonic
      _ <- peers
        .traverse(p =>
          (coordinator.session(p.id.toLong), cats.effect.Resource.eval(Semaphore[IO](1))).tupled
        )
        .use { sessions =>
          // Each peer's fetch client takes the requests the decision loop adds and downloads
          // them while it takes the next, as the session's fetch client does.
          peers.zip(sessions).traverse_ { case (peer, (session, _)) =>
            session.announce(headers.lastOption, peer.model, hints, Some(candidate))
          } *> coordinator.setMode(mode) *> coordinator.watchDecisions(0).background.use { _ =>
            peers.zip(sessions).parTraverse_ { case (peer, (session, writer)) =>
              def download(allocation: FetchCoordinator.Allocation): IO[Unit] =
                stats.update(
                  _.admitted(
                    peer.id,
                    allocation.requests,
                    allocation.headers.size.toLong * blockBytes
                  )
                ) *> IO.sleep(peer.rtt) *> writer.permit.use { _ =>
                  allocation.headers.traverse_ { h =>
                    deliver(stats, peer, h.hash) *> fetched.update(_ + h.hash) *>
                      session.received(allocation, h.hash)
                  } *> stats.update(_.completed(peer.id, allocation.requests)) *>
                    session.completed(allocation).replicateA_(allocation.requests) *>
                    session.finished(allocation)
                }
              val drained = fetched.get.map(_.size == blockCount).flatMap { all =>
                coordinator.inFlight.map(flight => all && flight.values.forall(_.requests == 0))
              }
              cats.effect.std.Supervisor[IO](await = true).use { downloads =>
                cats.Monad[IO].tailRecM(()) { _ =>
                  drained.flatMap {
                    case true => IO.pure(Right(()))
                    case false =>
                      session.acquire(IO.sleep(1.millis)).flatMap {
                        case None => IO.pure(Left(()))
                        case Some(allocation) =>
                          downloads.supervise(download(allocation)).as(Left(()))
                      }
                  }
                }
              }
            }
          }
        }
      ended <- IO.monotonic
      result <- stats.get
    yield Result(s"$mode-target-$target", result, began, ended)

  private def modeledIdleBaseline: IO[Result] =
    for
      stats <- Ref.of[IO, Observation](Observation())
      work <- Ref.of[IO, Vector[Vector[Header]]](batches)
      began <- IO.monotonic
      _ <- peers.parTraverse_ { peer =>
        cats.Monad[IO].tailRecM[Unit, Unit](()) { _ =>
          work.modify(todo => (todo.drop(1), todo.headOption)).flatMap {
            case None => IO.pure(Right(()))
            case Some(batch) =>
              (stats.update(_.admitted(peer.id, 1, batch.size.toLong * blockBytes)) *>
                IO.sleep(peer.rtt) *> batch.traverse_(h => deliver(stats, peer, h.hash)) *>
                stats.update(_.completed(peer.id, 1))).as(Left(()))
          }
        }
      }
      ended <- IO.monotonic
      result <- stats.get
    yield Result("modeled-idle-serial-baseline", result, began, ended)

  test("controlled heterogeneous peers characterize bulk and deadline scheduling") {
    TestControl.executeEmbed(
      for
        baseline <- modeledIdleBaseline
        coordinatedRuns <- Vector(FetchMode.BulkSync, FetchMode.Deadline)
          .flatMap(mode => Vector(1, 2).map(mode -> _))
          .traverse((mode, target) => coordinated(mode, target))
        results = baseline +: coordinatedRuns
        _ <- IO {
          results.foreach { result =>
            assertEquals(result.observation.unique, headers.map(_.hash).toSet, result.line)
            assert(result.observation.tipAt.exists(_ <= result.drained), result.line)
            assert(result.observation.requests.values.forall(_ == 0), result.line)
            assert(result.observation.outstanding.values.forall(_ == 0), result.line)
          }
          coordinatedRuns.foreach { result =>
            assert(result.observation.peakRequests.values.forall(_ <= requestLimit), result.line)
            peers.foreach { peer =>
              assert(
                result.observation.peakBytes.getOrElse(peer.id, 0L) <
                  peer.model.window.highBytes + blockBytes,
                result.line
              )
            }
            if result.label.startsWith("BulkSync") then
              assertEquals(result.observation.duplicates, 0L, result.line)
            else if result.label.endsWith("-2") then
              assert(result.observation.duplicates > 0L, result.line)
          }
          assert(
            coordinatedRuns.exists(_.observation.peakRequests.values.exists(_ > 1)),
            "workload must exercise overlapping requests"
          )
        }
        _ <-
          if sys.env.get("SCARAB_BENCH").contains("1") then
            results.traverse_(r => IO.println(r.line))
          else IO.unit
      yield (),
      seed = Some("MTM=")
    )
  }
