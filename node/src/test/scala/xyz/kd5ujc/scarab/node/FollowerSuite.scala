// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.TestGeometry
import xyz.kd5ujc.scarab.consensus.{BlockRules, FollowerEvents, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Fixtures, Header, NodeError, PeerError}
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import scala.concurrent.duration.*

/** Sessions of the follower over scripted peers, whose blocks the node's selection worker adopts
  * (issue 578): reconnection, the intersection offered again, and Genesis reselection.
  */
class FollowerSuite extends FailsOnUncaught:
  private val geometry =
    EpochGeometry(
      java.time.Instant.EPOCH,
      1000,
      epochLength = 100,
      securityParam = TestGeometry.k(5),
      activeSlotsCoeff = TestGeometry.f("0.5")
    )
  private val tip = ChainSync.Tip.Origin

  private lazy val forged: ForgedChain =
    ForgedChain.forge(0 until 30, epoch = 0).unsafeRunSync()(using munitIORuntime)
  private lazy val bodies: Map[Bytes, Bytes] =
    forged.headers.map(_.hash).zip(forged.raws).toMap

  /** Records the block number of the tip as the state. */
  private val rules: BlockRules[IO, BigInt] =
    BlockRules.from((_, header, _) => IO.pure(header.blockNo))

  private val noEvents: FollowerEvents[IO] = _ => IO.unit

  /** One scripted session per element; `Left` fails the session with a peer error. */
  private def scriptedPeers(
      sessions: List[List[Either[PeerError, SyncEvent]]],
      offered: Ref[IO, List[Vector[ChainSync.Point]]]
  ): IO[Resource[IO, Peer[IO]]] =
    Queue
      .unbounded[IO, List[Either[PeerError, SyncEvent]]]
      .flatTap(q => sessions.traverse_(q.offer))
      .map { scripts =>
        Resource.eval(scripts.tryTake.map(_.getOrElse(Nil))).evalMap { script =>
          Ref.of[IO, List[Either[PeerError, SyncEvent]]](script).map { remaining =>
            new Peer[IO]:
              def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
                offered.update(_ :+ candidates).as(candidates.headOption)
              def next(using Raise[IO, PeerError]) = remaining.modify {
                case Nil                  => Nil -> IO.never
                case Left(e) :: _         => Nil -> Raise[IO, PeerError].raise(e)
                case Right(event) :: rest => rest -> IO.pure(event)
              }.flatten
              def fetch(header: Header)(using Raise[IO, PeerError]) = IO.pure(bodies(header.hash))
          }
        }
      }

  /** One scripted peer per element of `peers`, each with its own sessions; all follow one chain,
    * whose blocks the worker adopts. A session starts once the worker has decided what the one
    * before it delivered, as the node connects again later than that.
    */
  private def runPeers(
      peers: List[List[List[Either[PeerError, SyncEvent]]]],
      retries: Int = 3
  ): IO[(Either[NodeError, Unit], VolatileChain[BigInt], List[Vector[ChainSync.Point]])] =
    for
      ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(geometry.k.toInt, -1))
      offered <- Ref.of[IO, List[Vector[ChainSync.Point]]](Nil)
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
      outcome <- Handle
        .allow[NodeError] {
          for
            follower <- Follower.create[IO, BigInt](rules, noEvents, geometry)
            pool <- WorkerTesting.pool(follower)
            result <- (WorkerTesting.selection(follower, pool) >> pool.session).use { claims =>
              Handle
                .allow[PeerError](
                  peers.parTraverse_ { sessions =>
                    scriptedPeers(sessions, offered).flatMap(connect =>
                      SessionDriver
                        .supervise(
                          follower,
                          Resource.eval(WorkerTesting.settled(pool)) >>
                            connect.flatMap(WorkerTesting.delivering(_, claims)).map(_.peer),
                          noEvents,
                          SessionDriver.Retry(retries, 1.milli)
                        )
                    )
                  }
                )
                .rescue(e => Handle[IO, NodeError].raise(e))
                .timeoutTo(300.millis, IO.unit) <* WorkerTesting.settled(pool)
            }
          yield result.asRight[NodeError]
        }
        .rescue(e => IO.pure(e.asLeft[Unit]))
      chain <- ref.get
      offers <- offered.get
    yield (outcome, chain, offers)

  private def run(sessions: List[List[Either[PeerError, SyncEvent]]]) = runPeers(List(sessions))

  private def forward(h: Header) = Right(SyncEvent.RollForward(h, tip))

  test("reconnects after a peer failure and resumes from the local tip") {
    val main = forged.headers.take(6)
    val first = main.take(4).map(forward).toList :+ Left(
      PeerError.Unavailable(PeerError.Unavailability.SessionEnded)
    )
    val second = main.drop(4).map(forward).toList
    run(List(first, second)).map { case (outcome, chain, offers) =>
      assertEquals(outcome, Right(()))
      assertEquals(chain.tip, main.last.point)
      assertEquals(offers.size, 2)
      assertEquals(offers(1).head, main(3).point)
    }
  }

  test("gives up after the configured number of consecutive peer failures") {
    val fail = List(Left(PeerError.Unavailable(PeerError.Unavailability.SessionEnded)))
    run(List.fill(4)(fail)).map { case (outcome, chain, _) =>
      assertEquals(outcome, Left(PeerError.Unavailable(PeerError.Unavailability.SessionEnded)))
      assertEquals(chain.tip, ChainSync.Point.Origin)
    }
  }

  test("a peer that shares no point with our chain is a peer error, retried and then given up") {
    val strangers: Resource[IO, Peer[IO]] = Resource.pure(new Peer[IO]:
      def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) = IO.pure(None)
      def next(using Raise[IO, PeerError]) = IO.never
      def fetch(header: Header)(using Raise[IO, PeerError]) = IO.pure(Bytes.empty))
    for
      ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(geometry.k.toInt, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
      outcome <- Handle
        .allow[NodeError] {
          for
            follower <- Follower.create[IO, BigInt](rules, noEvents, geometry)
            pool <- WorkerTesting.pool(follower)
            result <- WorkerTesting.selection(follower, pool).surround {
              Handle
                .allow[PeerError](
                  SessionDriver
                    .supervise(follower, strangers, noEvents, SessionDriver.Retry(2, 1.milli))
                )
                .rescue(e => Handle[IO, NodeError].raise(e))
            }
          yield result.asRight[NodeError]
        }
        .rescue(e => IO.pure(e.asLeft[Unit]))
    yield assertEquals(outcome, Left(PeerError.NoIntersection))
  }

  test("CaughtUp reselects the stored LoE-postponed candidate without another peer event") {
    TestControl.executeEmbed {
      val headers = forged.headers.take(5)
      for
        ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(2, -1))
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
        incoming <- Queue.unbounded[IO, SyncEvent]
        fetched <- Ref.of[IO, Vector[Bytes]](Vector.empty)
        cancelledReads <- Ref.of[IO, Int](0)
        peer = new Peer[IO]:
          def intersect(points: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
            IO.pure(points.lastOption)
          def next(using Raise[IO, PeerError]) =
            incoming.take.onCancel(cancelledReads.update(_ + 1))
          def fetch(header: Header)(using Raise[IO, PeerError]) =
            fetched.update(_ :+ header.hash).as(bodies(header.hash))
        follower <- Follower.create[IO, BigInt](rules, noEvents, geometry)
        pool <- WorkerTesting.pool(follower)
        governor <- GenesisGovernor.create[IO](follower.genesisView((_, _, _) => Some(30)))
        _ <- follower.installGenesis(governor)
        ended <- Deferred[IO, Either[SessionEnd, Unit]]
        result <- xyz.kd5ujc.scarab.network.Fibers
          .scoped[IO, SessionEnd, Unit](SessionEnd.Defect(_)) {
            val scope = summon[Handle[IO, SessionEnd]]
            given Raise[IO, NodeError] =
              Peer.contramapped[IO, SessionEnd, NodeError](scope)(SessionEnd.of)
            (WorkerTesting.selection(follower, pool) >> pool.session).use(claims =>
              WorkerTesting.delivering(peer, claims).use(fed => follower.session(fed.peer))
            )
          }
          .flatTap(ended.complete)
          .background
          .use { _ =>
            for
              _ <- headers.traverse_(h => incoming.offer(SyncEvent.RollForward(h, tip)))
              _ <- IO.sleep(10.millis)
              before <- ref.get
              _ <- IO(assertEquals(before.tip, headers(1).point))
              _ <- governor.setPhase(xyz.kd5ujc.scarab.network.selection.Gsm.Phase.CaughtUp)
              _ <- IO.sleep(10.millis)
              after <- ref.get
              delivered <- fetched.get
              early <- ended.tryGet
              cancelled <- cancelledReads.get
            yield
              assertEquals(after.tip, headers.last.point)
              assertEquals(delivered, headers.map(_.hash))
              assertEquals(early, None)
              assertEquals(cancelled, 0)
          }
      yield result
    }
  }

  test("Genesis readers capture the last committed chain without waiting for ledger application") {
    TestControl.executeEmbed {
      val header = Fixtures.chain(1).head
      for
        ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(geometry.k.toInt, -1))
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        follower <- Follower.create[IO, BigInt](
          BlockRules.from((_, h, _) => entered.complete(()).void *> release.get.as(h.blockNo)),
          noEvents,
          geometry
        )
        _ <- Resource
          .make(
            Handle
              .allow[NodeError](follower.adoptForged(header, Bytes.empty))
              .rescue(e => IO(fail(e.message)))
              .start
          )(_.cancel)
          .use { applying =>
            for
              _ <- entered.get
              before <- follower.genesisView((state, _, _) => Some(state)).timeout(1.second)
              selected <- follower.fetchSelection
              _ <- IO(assertEquals(before.current.tip, ChainSync.Point.Origin))
              _ <- IO(assertEquals(before.windowAt(0), Some(BigInt(-1))))
              _ <- IO(assertEquals(selected.anchor, ChainSync.Point.Origin))
              _ <- release.complete(()) *> applying.joinWithNever
              after <- follower.genesisView((state, _, _) => Some(state))
              _ <- IO(assertEquals(after.current.tip, header.point))
            yield ()
          }
      yield ()
    }
  }

  test("GSM preference retains the captured prefix when selection forks before its intersection") {
    import xyz.kd5ujc.scarab.consensus.{ChainSelection, HeaderChain, SelectView}
    import xyz.kd5ujc.scarab.network.selection.Gsm
    val old = Fixtures.chain(3)
    val announced = Fixtures.chain(2, from = Some(old.last))
    val selected = old.take(1) ++ Fixtures.chain(2, salt = 1, from = Some(old.head))
    val current = selected.foldLeft(VolatileChain.genesis[BigInt](5, -1))((chain, h) =>
      chain.extend(h, h.blockNo).fold(e => fail(e.message), _._1)
    )
    val history = HeaderChain[NodeHeaderState](
      old.last.point,
      None,
      announced.map(h => HeaderChain.Entry(h, None)),
      (ChainSync.Point.Origin +: old.dropRight(1).map(_.point)).map(p => HeaderChain.Known(p, None))
    )
    for
      ref <- Ref.of[IO, VolatileChain[BigInt]](current)
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
      follower <- Follower.create[IO, BigInt](
        rules,
        noEvents,
        geometry,
        initialViews = selected.map(h => h.hash -> SelectView.of(h)).toMap
      )
      lostPrefix <- follower.versus(ChainSelection.Candidate(history.base, announced))
      full <- follower.versusHeaders(history)
    yield
      assertEquals(lostPrefix, Gsm.Candidate.DoesNotIntersect)
      assertEquals(full, Gsm.Candidate.Better(true))
  }
