// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Queue
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.ChainSync
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
      if invalid(header.hash) then h.raise(ValidationError(header.point, "test", "rejected"))
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
                    Follower
                      .supervise(
                        follower,
                        connect,
                        noEvents,
                        Follower.Retry(retries, 1.milli),
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
            assert(names.nonEmpty && names.forall(_.startsWith("csl-cpu")), names.toString)
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

  test("two peers: the chain of the longer one is followed, whichever arrives first") {
    val main = Fixtures.chain(6)
    val longer = Fixtures.chain(3, salt = 1, from = Some(main(3)))
    val shorter = Fixtures.chain(1, salt = 2, from = Some(main(3)))
    val common = main.take(4).map(forward).toList
    runPeers(
      List(
        List(common ++ shorter.map(forward)),
        List(common ++ longer.map(forward))
      )
    ).map { case (outcome, chain, _) =>
      assertEquals(outcome, Right(()))
      assertEquals(chain.tip, longer.last.point)
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
        assertEquals(outcome, Left(ValidationError(main(3).point, "test", "rejected")))
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
