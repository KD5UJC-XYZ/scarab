// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{
  BlockRules,
  Compute,
  FollowerEvents,
  SchedulingStats,
  VolatileChain
}
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  EpochGeometry,
  Fixtures,
  Header,
  NodeError,
  PeerError,
  ValidationError
}
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import munit.CatsEffectSuite
import scala.concurrent.duration.*

class FollowerSuite extends CatsEffectSuite:
  private val geometry =
    EpochGeometry(
      java.time.Instant.EPOCH,
      1000,
      epochLength = 100,
      securityParam = 5,
      activeSlotsCoeff = 0.5
    )
  private val tip = ChainSync.Tip.Origin

  /** Records the block number of the tip as the state; rejects headers in `invalid`. */
  private def rules(
      invalid: Set[Bytes] = Set.empty
  )(using h: Handle[IO, NodeError]): BlockRules[IO, BigInt] =
    (_, header, _) =>
      if invalid(header.hash) then
        h.raise(ValidationError(header.point, BlockFailure.HeaderDiffers))
      else IO.pure(header.blockNo)

  private val noEvents: FollowerEvents[IO] = _ => IO.unit

  /** One scripted session per element; `Left` fails the session with a peer error. */
  private def scriptedPeers(
      sessions: List[List[Either[PeerError, SyncEvent]]],
      offered: Ref[IO, List[Vector[ChainSync.Point]]]
  )(using
      h: Handle[IO, PeerError]
  ): IO[Resource[IO, Peer[IO]]] =
    Queue
      .unbounded[IO, List[Either[PeerError, SyncEvent]]]
      .flatTap(q => sessions.traverse_(q.offer))
      .map { scripts =>
        Resource.eval(scripts.tryTake.map(_.getOrElse(Nil))).evalMap { script =>
          Ref.of[IO, List[Either[PeerError, SyncEvent]]](script).map { remaining =>
            new Peer[IO]:
              def intersect(candidates: Vector[ChainSync.Point]) =
                offered.update(_ :+ candidates).as(candidates.headOption)
              def next = remaining.modify {
                case Nil                  => Nil -> IO.never
                case Left(e) :: _         => Nil -> h.raise(e)
                case Right(event) :: rest => rest -> IO.pure(event)
              }.flatten
              def fetch(header: Header) = IO.pure(Bytes.empty)
          }
        }
      }

  /** One scripted peer per element of `peers`, each with its own sessions; all follow one chain. */
  private def runPeers(
      peers: List[List[List[Either[PeerError, SyncEvent]]]],
      invalid: Set[Bytes] = Set.empty,
      retries: Int = 3,
      compute: Option[Compute[IO]] = None,
      applied: Option[Ref[IO, List[String]]] = None
  ): IO[(Either[NodeError, Unit], VolatileChain[BigInt], List[Vector[ChainSync.Point]])] =
    for
      ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(geometry.securityParam, -1))
      offered <- Ref.of[IO, List[Vector[ChainSync.Point]]](Nil)
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
      outcome <- Handle
        .allow[NodeError] {
          for
            follower <- Follower.create[IO, BigInt](
              applied.fold(rules(invalid))(log =>
                (state, header, block) =>
                  IO(Thread.currentThread.getName).flatMap(n => log.update(_ :+ n)) *>
                    rules(invalid).apply(state, header, block)
              ),
              noEvents,
              geometry,
              compute = compute
            )
            result <- Handle
              .allow[PeerError](
                peers.parTraverse_ { sessions =>
                  scriptedPeers(sessions, offered).flatMap(connect =>
                    SessionDriver
                      .supervise(
                        follower,
                        connect,
                        noEvents,
                        SessionDriver.Retry(retries, 1.milli),
                        compute
                      )
                  )
                }
              )
              .rescue(e => Handle[IO, NodeError].raise(e))
              .timeoutTo(300.millis, IO.unit)
          yield result.asRight[NodeError]
        }
        .rescue(e => IO.pure(e.asLeft[Unit]))
      chain <- ref.get
      offers <- offered.get
    yield (outcome, chain, offers)

  private def run(
      sessions: List[List[Either[PeerError, SyncEvent]]],
      invalid: Set[Bytes] = Set.empty
  ) = runPeers(List(sessions), invalid)

  private def forward(h: Header) = Right(SyncEvent.RollForward(h, tip))
  private def backward(h: Header) = Right(SyncEvent.RollBackward(h.point, tip))

  test("follows forks: a longer branch replaces ours, rolling back to where it forks") {
    val main = Fixtures.chain(8)
    val fork = Fixtures.chain(3, salt = 1, from = Some(main(5)))
    run(List(main.map(forward).toList ++ List(backward(main(5))) ++ fork.map(forward))).map {
      case (outcome, chain, _) =>
        assertEquals(outcome, Right(()))
        assertEquals(chain.tip, fork.last.point)
        assertEquals(chain.state, fork.last.blockNo)
        assertEquals(chain.anchor.block.map(_.hash), Some(main(3).hash))
    }
  }

  test("with a CPU pool the sessions apply the blocks on it, and the chain is the same") {
    val main = Fixtures.chain(8)
    val fork = Fixtures.chain(3, salt = 1, from = Some(main(5)))
    val script = main.map(forward).toList ++ List(backward(main(5))) ++ fork.map(forward)
    SchedulingStats.create[IO].flatMap { stats =>
      Compute.pool[IO](2, stats).use { pool =>
        for
          log <- Ref.of[IO, List[String]](Nil)
          result <- runPeers(List(List(script)), compute = Some(pool), applied = Some(log))
          names <- log.get
          _ <- IO {
            val (outcome, chain, _) = result
            assertEquals(outcome, Right(()))
            assertEquals(chain.tip, fork.last.point)
            assert(names.nonEmpty && names.forall(_.startsWith("scarab-cpu")), names.toString)
          }
        yield ()
      }
    }
  }

  test("a peer's branch that is not longer than ours changes nothing") {
    val main = Fixtures.chain(8)
    val fork = Fixtures.chain(2, salt = 1, from = Some(main(5)))
    run(List(main.map(forward).toList ++ List(backward(main(5))) ++ fork.map(forward))).map {
      case (outcome, chain, _) =>
        assertEquals(outcome, Right(()))
        assertEquals(chain.tip, main.last.point)
    }
  }

  /** One peer that serves `chain` as a node does: after the intersection it announces a roll back
    * to the intersection point (the protocol's first instruction), then rolls forward over the
    * headers it has beyond it, `pause` apart. It joins `startDelay` after the run began.
    */
  private final case class Serving(
      chain: Vector[Header],
      startDelay: FiniteDuration = Duration.Zero,
      pause: FiniteDuration = Duration.Zero,
      /** False: a scripted peer that ignores the intersection and replays its whole chain. */
      honest: Boolean = true
  )

  private def serving(s: Serving): Resource[IO, Peer[IO]] =
    Resource.eval(IO.sleep(s.startDelay) *> Ref.of[IO, List[SyncEvent]](Nil)).map { pending =>
      new Peer[IO]:
        def intersect(candidates: Vector[ChainSync.Point]) =
          if !s.honest then
            pending.set(s.chain.toList.map(SyncEvent.RollForward(_, tip))).as(candidates.headOption)
          else
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
        def next = pending.modify {
          case Nil          => Nil -> IO.never[SyncEvent]
          case head :: rest => rest -> IO.sleep(s.pause).as(head)
        }.flatten
        def fetch(header: Header) = IO.pure(Bytes.empty)
    }

  /** Peers that serve chains, all followed by one follower, on the test clock (nothing here waits
    * for real time: the run ends when the virtual clock has passed `until`).
    */
  private def runServed(
      servers: List[Serving],
      until: FiniteDuration = 30.seconds
  ): IO[(Either[NodeError, Unit], VolatileChain[BigInt])] =
    for
      ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(geometry.securityParam, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
      outcome <- Handle
        .allow[NodeError] {
          for
            follower <- Follower.create[IO, BigInt](rules(), noEvents, geometry)
            result <- Handle
              .allow[PeerError](
                servers.parTraverse_(s =>
                  SessionDriver.supervise(
                    follower,
                    serving(s),
                    noEvents,
                    SessionDriver.Retry(3, 1.milli),
                    None
                  )
                )
              )
              .rescue(e => Handle[IO, NodeError].raise(e))
              .timeoutTo(until, IO.unit)
          yield result.asRight[NodeError]
        }
        .rescue(e => IO.pure(e.asLeft[Unit]))
      chain <- ref.get
    yield (outcome, chain)

  // main: 6 blocks; both forks leave it after its fourth block (index 3); the longer fork ends
  // with 7 blocks, the shorter with 5.
  private val main = Fixtures.chain(6)
  private val longer = Fixtures.chain(3, salt = 1, from = Some(main(3)))
  private val shorter = Fixtures.chain(1, salt = 2, from = Some(main(3)))
  private val common = main.take(4)

  test("two peers: the chain of the longer one is followed, whichever arrives first") {
    // every order in which the peers join and announce, from simultaneous to far apart: the
    // shorter one first (it has applied the common blocks and its fork when the longer one joins),
    // the longer one first, and both at once under different schedules of the test runtime
    val delays = List(Duration.Zero, 1.milli, 10.millis, 1.second)
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

  test(
    "the old test's flake: a scripted peer that replays its chain from the start, ignoring the " +
      "intersection, is dropped when it joins after the node has the blocks (a test artefact, not a node bug)"
  ) {
    val servers = List(
      Serving(common ++ shorter),
      Serving(common ++ longer, startDelay = 1.second, honest = false)
    )
    TestControl.executeEmbed(runServed(servers)).map { case (outcome, chain) =>
      // the replayed blocks do not extend the candidate that joined at our tip: a protocol error,
      // on every reconnect, until the retries are used up
      assert(
        outcome.left.exists {
          case PeerError.Protocol(r) => r.contains("does not extend"); case _ => false
        },
        outcome.toString
      )
      assertEquals(chain.tip, shorter.last.point)
    }
  }

  test("a fork deeper than k is never adopted, and the peer is dropped, not the node") {
    // k = 5: the peer rolls back to a block that is already immutable here, to a longer branch
    val main = Fixtures.chain(12)
    val deep = Fixtures.chain(9, salt = 1, from = Some(main(4)))
    run(List(main.map(forward).toList ++ List(backward(main(4))) ++ deep.map(forward))).map {
      case (outcome, chain, offers) =>
        assertEquals(outcome, Right(()))
        assertEquals(chain.tip, main.last.point)
        assertEquals(chain.anchor.block.map(_.hash), Some(main(6).hash))
        assertEquals(offers.size, 2) // dropped and reconnected
    }
  }

  test("reconnects after a peer failure and resumes from the local tip") {
    val main = Fixtures.chain(6)
    val first = main.take(4).map(forward).toList :+ Left(PeerError.Unavailable("reset"))
    val second = main.drop(4).map(forward).toList
    run(List(first, second)).map { case (outcome, chain, offers) =>
      assertEquals(outcome, Right(()))
      assertEquals(chain.tip, main.last.point)
      assertEquals(offers.size, 2)
      assertEquals(offers(1).head, main(3).point)
    }
  }

  test("gives up after the configured number of consecutive peer failures") {
    val fail = List(Left(PeerError.Unavailable("down")))
    run(List.fill(4)(fail)).map { case (outcome, chain, _) =>
      assertEquals(outcome, Left(PeerError.Unavailable("down")))
      assertEquals(chain.tip, ChainSync.Point.Origin)
    }
  }

  test("a rule violation is fatal and leaves the chain at the last valid block") {
    val main = Fixtures.chain(5)
    run(List(main.map(forward).toList), invalid = Set(main(3).hash)).map {
      case (outcome, chain, _) =>
        assertEquals(outcome, Left(ValidationError(main(3).point, BlockFailure.HeaderDiffers)))
        assertEquals(chain.tip, main(2).point)
    }
  }

  test("a header that does not connect is a peer error: the peer is dropped and re-synced") {
    val main = Fixtures.chain(4)
    val stray = Fixtures.chain(2, salt = 2).last
    run(List(main.map(forward).toList :+ forward(stray))).map { case (outcome, chain, offers) =>
      assertEquals(outcome, Right(()))
      assertEquals(chain.tip, main.last.point)
      assertEquals(offers.size, 2)
    }
  }

  test("a peer that shares no point with our chain is a peer error, retried and then given up") {
    val strangers: Resource[IO, Peer[IO]] = Resource.pure(new Peer[IO]:
      def intersect(candidates: Vector[ChainSync.Point]) = IO.pure(None)
      def next = IO.never
      def fetch(header: Header) = IO.pure(Bytes.empty))
    for
      ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(geometry.securityParam, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
      outcome <- Handle
        .allow[NodeError] {
          for
            follower <- Follower.create[IO, BigInt](rules(), noEvents, geometry)
            result <- Handle
              .allow[PeerError](
                SessionDriver
                  .supervise(follower, strangers, noEvents, SessionDriver.Retry(2, 1.milli))
              )
              .rescue(e => Handle[IO, NodeError].raise(e))
          yield result.asRight[NodeError]
        }
        .rescue(e => IO.pure(e.asLeft[Unit]))
    yield assertEquals(outcome, Left(PeerError.NoIntersection))
  }
