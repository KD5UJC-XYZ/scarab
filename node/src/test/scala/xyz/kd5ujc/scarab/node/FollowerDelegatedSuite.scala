// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{BlockRules, ChainSelection, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header, NodeError, PeerError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.genesis.PicosClock
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ChainSync

class FollowerDelegatedSuite extends FailsOnUncaught:
  private val headers = Fixtures.chain(4096)
  private val tip = ChainSync.Tip.Origin
  private val candidate = ChainSelection.Candidate(ChainSync.Point.Origin, headers)

  private def run(authoritative: Boolean): IO[Unit] =
    for
      calls <- Ref.of[IO, Int](0)
      clock = new PicosClock[IO]:
        def now = calls.update(_ + 1).as(BigInt(0))
        def sleep(@scala.annotation.unused delayPicos: BigInt) = IO.unit
      work <- SelectionWork.create[IO](IO.unit, Some(clock))
      _ <- work.session.use(session => runWithWork(authoritative, session, calls))
    yield ()

  private def runWithWork(
      authoritative: Boolean,
      work: SelectionWork.Session[IO],
      calls: Ref[IO, Int]
  ): IO[Unit] =
    for
      state <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(5, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      registry <- InvalidBlocks.create[IO]
      source <- Ref.of[IO, ChainSelection.Candidate](candidate)
      tracked <- Ref.of[IO, Option[Ref[IO, ChainSelection.Candidate]]](None)
      seen <- Ref.of[IO, Int](0)
      // The rollback predates both the final body cursor and the local retained chain. It has
      // already been accepted by the authoritative ChainSync header checker.
      events = headers.map(SyncEvent.RollForward(_, tip)) ++ Vector(
        SyncEvent.RollBackward(headers.head.point, tip),
        SyncEvent.RollForward(headers(1), tip)
      )
      remaining <- Ref.of[IO, Vector[SyncEvent]](events)
      peer = new Peer[IO]:
        override def selectionWork = Some(work)
        override def invalidBlocks = Some(registry)
        override def headerCandidate = Option.when(authoritative)(source)
        def intersect(points: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
          IO.pure(points.lastOption)
        def next(using Raise[IO, PeerError]): IO[SyncEvent] =
          tracked.get.flatMap(_.traverse_(_.get.flatMap { observed =>
            if authoritative then IO(assertEquals(observed, candidate))
            else IO(assertEquals(observed.headers.size, 0))
          })) *> remaining
            .modify {
              case values if values.nonEmpty => values.tail -> Some(values.head)
              case values                    => values -> None
            }
            .flatMap {
              case Some(event) => seen.update(_ + 1).as(event)
              case None        => Raise[IO, PeerError].raise(PeerError.NoIntersection)
            }
        def fetch(@scala.annotation.unused header: Header)(using Raise[IO, PeerError]) =
          IO(fail("delegated body consumption must not fetch or apply blocks"))
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, _, _) => IO(fail("selection belongs to the node worker"))),
        _ => IO.unit,
        ForgedChain.geometry
      )
      _ <- follower.installInvalidBlocks(registry)
      outcome <- registry.selectionResource.use { _ =>
        Handle
          .allow[NodeError](
            follower
              .session(peer, ref => tracked.set(Some(ref)))
              .as(
                Option.empty[NodeError]
              )
          )
          .rescue(error => IO.pure(Some(error)))
      }
      count <- seen.get
      processingCalls <- calls.get
      view <- tracked.get.flatMap(_.traverse(_.get))
      preference <- follower.versus(candidate)
    yield
      assertEquals(outcome, Some(PeerError.NoIntersection: NodeError))
      assertEquals(count, events.size)
      // No ready tokens are queued in this fixture: one clock observation per attempted RF
      // consideration therefore exposes repeated traversal even when no token can be acquired.
      assertEquals(processingCalls, headers.size + 1)
      if authoritative then
        assertEquals(view, Some(candidate))
        assertEquals(preference, Gsm.Candidate.Better(true))
      else assertEquals(view.map(_.headers.size), Some(0))

  test(
    "delegated body delivery keeps constant history and accepts an already checked old rollback"
  ) {
    run(authoritative = false)
  }

  test("public tracking observes validated headers ahead of the body cursor") {
    run(authoritative = true)
  }
