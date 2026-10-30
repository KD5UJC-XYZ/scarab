// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.TestGeometry
import xyz.kd5ujc.scarab.consensus.{
  BlockRules,
  Compute,
  FollowerEvents,
  SchedulingStats,
  VolatileChain
}
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
  ): IO[Resource[IO, WorkerTesting.Scripted[IO]]] =
    Queue
      .unbounded[IO, List[Either[PeerError, SyncEvent]]]
      .flatTap(q => sessions.traverse_(q.offer))
      .map { scripts =>
        Resource.eval(scripts.tryTake.map(_.getOrElse(Nil))).evalMap { script =>
          Ref.of[IO, List[Either[PeerError, SyncEvent]]](script).map { remaining =>
            new WorkerTesting.Scripted[IO]:
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
    * before it delivered, as the node connects again later than that. With `until`, the sessions
    * end once the chain's tip is that point (a 30-second watchdog fails the run otherwise); without
    * it, after 300 ms.
    */
  private def runPeers(
      peers: List[List[List[Either[PeerError, SyncEvent]]]],
      retries: Int = 3,
      compute: Option[Compute[IO]] = None,
      applied: Option[Ref[IO, List[String]]] = None,
      until: Option[ChainSync.Point] = None
  ): IO[(Either[NodeError, Unit], VolatileChain[BigInt], List[Vector[ChainSync.Point]])] =
    for
      ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(geometry.k.toInt, -1))
      offered <- Ref.of[IO, List[Vector[ChainSync.Point]]](Nil)
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
      outcome <- Handle
        .allow[NodeError] {
          for
            follower <- Follower.create[IO, BigInt](
              applied.fold(rules)(log =>
                BlockRules.from { (state, header, block) =>
                  IO(Thread.currentThread.getName).flatMap(n => log.update(_ :+ n)) *>
                    rules(state, header, block)
                }
              ),
              noEvents,
              geometry,
              compute = compute
            )
            pool <- WorkerTesting.pool(follower)
            result <- (WorkerTesting.selection(follower, pool) >> pool.session).use { claims =>
              val sessions = Handle
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
              def adopted(point: ChainSync.Point): IO[Unit] =
                ref.get.flatMap(chain =>
                  if chain.tip == point then IO.unit else IO.sleep(1.milli) *> adopted(point)
                )
              until.fold(sessions.timeoutTo(300.millis, IO.unit))(point =>
                IO.race(sessions, adopted(point).timeout(30.seconds)).void
              ) <* WorkerTesting.settled(pool)
            }
          yield result.asRight[NodeError]
        }
        .rescue(e => IO.pure(e.asLeft[Unit]))
      chain <- ref.get
      offers <- offered.get
    yield (outcome, chain, offers)

  private def run(sessions: List[List[Either[PeerError, SyncEvent]]]) = runPeers(List(sessions))

  test("with a CPU pool the selection worker applies the blocks on it, and the chain is the same") {
    val main = forged.headers.take(8)
    SchedulingStats.create[IO].flatMap { stats =>
      Compute.pool[IO](2, stats).use { pool =>
        for
          log <- Ref.of[IO, List[String]](Nil)
          result <- runPeers(
            List(List(main.map(forward).toList)),
            compute = Some(pool),
            applied = Some(log),
            until = Some(main.last.point)
          )
          names <- log.get
          _ <- IO {
            val (outcome, chain, _) = result
            assertEquals(outcome, Right(()))
            assertEquals(chain.tip, main.last.point)
            assertEquals(names.size, main.size)
            assert(names.forall(_.startsWith("scarab-cpu")), names.toString)
          }
        yield ()
      }
    }
  }

  private def forward(h: Header) = Right(SyncEvent.RollForward(h, tip))

  /** One peer that serves `chain` as a node does: after the intersection it announces a roll back
    * to the intersection point (the protocol's first instruction), then rolls forward over the
    * headers it has beyond it, `pause` apart. It joins `startDelay` after the run began.
    */
  private final case class Serving(
      chain: Vector[Header],
      startDelay: FiniteDuration = Duration.Zero,
      pause: FiniteDuration = Duration.Zero
  )

  private def serving(s: Serving): Resource[IO, WorkerTesting.Scripted[IO]] =
    Resource.eval(IO.sleep(s.startDelay) *> Ref.of[IO, List[SyncEvent]](Nil)).map { pending =>
      new WorkerTesting.Scripted[IO]:
        def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
          val at =
            candidates.find(p => p == ChainSync.Point.Origin || s.chain.exists(_.point == p))
          val after = at.fold(Vector.empty[Header])(p =>
            if p == ChainSync.Point.Origin then s.chain
            else s.chain.dropWhile(_.point != p).drop(1)
          )
          pending
            .set(
              at.toList.map(SyncEvent.RollBackward(_, tip)) ++
                after.map(SyncEvent.RollForward(_, tip))
            )
            .as(at)
        def next(using Raise[IO, PeerError]) = pending.modify {
          case Nil          => Nil -> IO.never[SyncEvent]
          case head :: rest => rest -> IO.sleep(s.pause).as(head)
        }.flatten
        def fetch(header: Header)(using Raise[IO, PeerError]) = IO.pure(forkBodies(header.hash))
    }

  /** Peers that serve chains, all followed by one follower whose blocks the worker adopts, on the
    * test clock (nothing here waits for real time: the run ends when the virtual clock has passed
    * `until`, once the worker has decided what was delivered).
    */
  private def runServed(
      servers: List[Serving],
      until: FiniteDuration = 30.seconds
  ): IO[(Either[NodeError, Unit], VolatileChain[BigInt])] =
    for
      ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(geometry.k.toInt, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
      outcome <- Handle
        .allow[NodeError] {
          for
            follower <- Follower.create[IO, BigInt](rules, noEvents, geometry)
            pool <- WorkerTesting.pool(follower)
            result <- (WorkerTesting.selection(follower, pool) >> pool.session).use { claims =>
              Handle
                .allow[PeerError](
                  servers.parTraverse_(s =>
                    SessionDriver.supervise(
                      follower,
                      serving(s).flatMap(WorkerTesting.delivering(_, claims)).map(_.peer),
                      noEvents,
                      SessionDriver.Retry(3, 1.milli)
                    )
                  )
                )
                .rescue(e => Handle[IO, NodeError].raise(e))
                .timeoutTo(until, IO.unit) <* WorkerTesting.settled(pool)
            }
          yield result.asRight[NodeError]
        }
        .rescue(e => IO.pure(e.asLeft[Unit]))
      chain <- ref.get
    yield (outcome, chain)

  // two forged forks from one prefix: the longer leaves it with three blocks, the shorter with one
  private lazy val forks: (ForgedChain, ForgedChain) =
    ForgedChain
      .competingForks(5000 until 5020, 5020 until 5100, 31, 47)
      .unsafeRunSync()(using
        munitIORuntime
      )
  private lazy val common: Vector[Header] =
    forks._1.headers.zip(forks._2.headers).takeWhile((a, b) => a.hash == b.hash).map(_._1)
  private lazy val longer: Vector[Header] = forks._1.headers.drop(common.size).take(3)
  private lazy val shorter: Vector[Header] = forks._2.headers.drop(common.size).take(1)
  private lazy val forkBodies: Map[Bytes, Bytes] =
    (forks._1.headers.zip(forks._1.raws) ++ forks._2.headers.zip(forks._2.raws))
      .map((h, raw) => h.hash -> raw)
      .toMap

  test("two peers: the chain of the longer one is followed, whichever arrives first") {
    // every order in which the peers join and announce, from simultaneous to far apart: the
    // shorter one first (it has applied the common blocks and its fork when the longer one joins),
    // the longer one first, and both at once under different schedules of the test runtime
    val delays = List(Duration.Zero, 1.milli, 10.millis, 1.second)
    assert(common.nonEmpty && longer.size == 3 && shorter.size == 1)
    val cases = for
      a <- delays
      b <- delays
      pauseA <- List(Duration.Zero, 5.millis)
      pauseB <- List(Duration.Zero, 5.millis)
    yield List(
      Serving(common ++ shorter, a, pauseA),
      Serving(common ++ longer, b, pauseB)
    )
    cases.traverse_ { servers =>
      (0 until 4).toList
        .map(n => java.util.Base64.getEncoder.encodeToString(s"1$n".getBytes))
        .traverse_ { seed =>
          TestControl.executeEmbed(runServed(servers), seed = Some(seed)).map {
            case (outcome, chain) =>
              assertEquals(outcome, Right(()), servers.toString)
              assertEquals(chain.tip, longer.last.point, s"seed $seed, $servers")
          }
        }
    }
  }

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
    val strangers: Resource[IO, WorkerTesting.Scripted[IO]] =
      Resource.pure(new WorkerTesting.Scripted[IO]:
        def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
          IO.pure(None)
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
        peer = new WorkerTesting.Scripted[IO]:
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
