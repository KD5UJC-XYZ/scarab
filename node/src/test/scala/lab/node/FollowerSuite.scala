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

  private def run(
      sessions: List[List[Either[PeerError, SyncEvent]]],
      invalid: Set[Bytes] = Set.empty
  ): IO[(Either[NodeError, Unit], VolatileChain[BigInt], List[Vector[ChainSync.Point]])] =
    for
      ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(geometry.securityParam, -1))
      offered <- Ref.of[IO, List[Vector[ChainSync.Point]]](Nil)
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
      outcome <- Handle
        .allow[NodeError] {
          val follower = Follower[IO, BigInt](rules(invalid), noEvents, geometry)
          Handle
            .allow[PeerError](
              scriptedPeers(sessions, offered).flatMap(peers =>
                Follower.supervise(follower, peers, noEvents, Follower.Retry(3, 1.milli))
              )
            )
            .rescue(e => Handle[IO, NodeError].raise(e))
            .timeoutTo(300.millis, IO.unit)
            .map(_.asRight[NodeError])
        }
        .rescue(e => IO.pure(e.asLeft[Unit]))
      chain <- ref.get
      offers <- offered.get
    yield (outcome, chain, offers)

  private def forward(h: Header) = Right(SyncEvent.RollForward(h, tip))
  private def backward(h: Header) = Right(SyncEvent.RollBackward(h.point, tip))

  test("follows forks: rolls back and adopts the replacement branch") {
    val main = Fixtures.chain(8)
    val fork = Fixtures.chain(2, salt = 1, from = Some(main(5)))
    run(List(main.map(forward).toList ++ List(backward(main(5))) ++ fork.map(forward))).map {
      case (outcome, chain, _) =>
        assertEquals(outcome, Right(()))
        assertEquals(chain.tip, fork.last.point)
        assertEquals(chain.state, fork.last.blockNo)
        assertEquals(chain.anchor.block.map(_.hash), Some(main(2).hash))
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

  test("a header that does not connect is a chain error, not a reconnect") {
    val main = Fixtures.chain(4)
    val stray = Fixtures.chain(2, salt = 2).last
    run(List(main.map(forward).toList :+ forward(stray))).map { case (outcome, chain, _) =>
      assertEquals(outcome, Left(ChainError.NotConnected(stray.point, main.last.point)))
      assertEquals(chain.tip, main.last.point)
    }
  }
