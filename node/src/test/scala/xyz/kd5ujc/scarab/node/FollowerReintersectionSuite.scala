// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.TestGeometry
import cats.effect.{Deferred, IO, Ref}
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRules, ChainSelection, FollowerEvents, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Fixtures, Header, NodeError, PeerError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.ChainSync

class FollowerReintersectionSuite extends FailsOnUncaught:
  private val geometry =
    EpochGeometry(java.time.Instant.EPOCH, 1000, 100, TestGeometry.k(2), TestGeometry.f("0.5"))
  private val headers = Fixtures.chain(10)
  private val tip = ChainSync.Tip.Origin

  private def applied(headers: Vector[Header]): VolatileChain[BigInt] =
    headers.foldLeft(VolatileChain.genesis(2, BigInt(-1))) { (chain, h) =>
      chain.extend(h, h.blockNo).fold(e => fail(e.message), _._1)
    }

  private def run(advanced: VolatileChain[BigInt], suffix: Header) =
    for
      chain <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(2, BigInt(-1)))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(chain)
      offers <- Ref.of[IO, Vector[Vector[ChainSync.Point]]](Vector.empty)
      steps <- Ref.of[IO, Int](0)
      tracked <- Ref.of[IO, Vector[Ref[IO, ChainSelection.Candidate]]](Vector.empty)
      finished <- Deferred[IO, Unit]
      peer = new Peer[IO]:
        def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
          offers
            .update(_ :+ candidates)
            .as(
              candidates.find(p => p == ChainSync.Point.Origin || headers.exists(_.point == p))
            )
        def next(using Raise[IO, PeerError]) = steps.getAndUpdate(_ + 1).flatMap {
          // Another session advances past this candidate's origin before its first header lands.
          case 0 => chain.set(advanced).as(SyncEvent.RollForward(headers.head, tip))
          case 1 => IO.pure(SyncEvent.RollBackward(advanced.tip, tip))
          case 2 => IO.pure(SyncEvent.RollForward(suffix, tip))
          case _ => finished.complete(()).void *> IO.never[SyncEvent]
        }
        def fetch(header: Header)(using Raise[IO, PeerError]) = IO.pure(Bytes.empty)
      events: FollowerEvents[IO] = _ => IO.unit
      outcome <- Handle
        .allow[NodeError] {
          for
            follower <- Follower.create[IO, BigInt](
              BlockRules.from((_, h, _) => IO.pure(h.blockNo)),
              events,
              geometry
            )
            _ <- IO.race(follower.session(peer, c => tracked.update(_ :+ c)), finished.get)
          yield Right(())
        }
        .rescue(e => IO.pure(Left(e)))
      selected <- chain.get
      offered <- offers.get
      candidates <- tracked.get.flatMap(_.traverse(_.get))
    yield (outcome, selected, offered, candidates)

  test("a lagging candidate re-intersects on the same peer and tracked reference, then advances") {
    val advanced = applied(headers.take(8))
    run(advanced, headers(8)).map { case (outcome, selected, offers, candidates) =>
      assertEquals(outcome, Right(()))
      assertEquals(offers, Vector(Vector(ChainSync.Point.Origin), advanced.candidates))
      assertEquals(selected.tip, headers(8).point)
      assertEquals(selected.state, headers(8).blockNo)
      assertEquals(candidates.size, 1)
      assertEquals(candidates.head.tip, headers(8).point)
    }
  }

  test("a genuinely disjoint fork cannot replace the selected chain after reintersection fails") {
    val advanced = applied(Fixtures.chain(8, salt = 7))
    run(advanced, headers(8)).map { case (outcome, selected, offers, _) =>
      assertEquals(outcome, Left(PeerError.NoIntersection))
      assertEquals(selected, advanced)
      assertEquals(offers.size, 2)
      assertEquals(offers.last, advanced.candidates)
    }
  }

  test("a header that fails to extend the new intersection remains a protocol refusal") {
    val advanced = applied(headers.take(8))
    run(advanced, headers.head).map { case (outcome, selected, offers, _) =>
      assertEquals(
        outcome,
        Left(
          PeerError.Protocol(
            PeerError.ProtocolProblem.HeaderDoesNotExtend(headers.head.point, advanced.tip)
          )
        )
      )
      assertEquals(selected, advanced)
      assertEquals(offers.size, 2)
    }
  }
