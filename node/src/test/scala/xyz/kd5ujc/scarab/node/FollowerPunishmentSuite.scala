// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.mtl.{Handle, Raise, Stateful}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRules, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Header, NodeError, PeerError, ValidationError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The punishment of a peer whose checked original the node's selection worker refuses: the
  * scripted ChainSync events vary delivery independently of validation order, as ChainDB's
  * add-block queue does, and each announced body reaches the pool as `Peer.over` delivers it.
  */
class FollowerPunishmentSuite extends FailsOnUncaught:
  private enum Action:
    case Add(header: Header, raw: Bytes)
    case Rollback(point: ChainSync.Point)

  private final case class Result(
      outcome: Either[NodeError, Unit],
      selected: ChainSync.Point,
      invalid: Set[Bytes]
  )

  private def refused(header: Header) =
    ValidationError(header.point, BlockFailure.EpochPrecedesLedger(0))

  /** `outcome` is the session's end, or `Right` when it is still running once the worker has
    * decided every block the script delivered. The session's claims report to it, as an outbound
    * session's do: a refusal that punishes the sender ends its session.
    */
  private def run(actions: List[Action], invalid: Set[Bytes]): IO[Result] =
    for
      state <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(5, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      pending <- Ref.of[IO, List[Action]](actions)
      done <- Deferred[IO, Unit]
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, h, _) =>
          if invalid(h.hash) then Raise[IO, ValidationError].raise(refused(h))
          else IO.pure(h.blockNo)
        ),
        _ => IO.unit,
        ForgedChain.geometry
      )
      pool <- WorkerTesting.pool(follower)
      bodies = actions.collect { case Action.Add(header, raw) => header.hash -> raw }.toMap
      script = new Peer[IO]:
        def intersect(@scala.annotation.unused points: Vector[ChainSync.Point])(using
            Raise[IO, PeerError]
        ) =
          IO.pure(Some(ChainSync.Point.Origin))
        def next(using Raise[IO, PeerError]) =
          pending
            .modify {
              case head :: tail => tail -> Some(head)
              case Nil          => Nil -> None
            }
            .flatMap {
              case None => done.complete(()) *> IO.never[SyncEvent]
              case Some(Action.Rollback(point)) =>
                IO.pure(SyncEvent.RollBackward(point, ChainSync.Tip.Origin))
              case Some(Action.Add(header, _)) =>
                IO.pure(SyncEvent.RollForward(header, ChainSync.Tip.Origin))
            }
        def fetch(header: Header)(using Raise[IO, PeerError]) = IO.pure(bodies(header.hash))
      outcome <- Handle
        .allow[NodeError](
          WorkerTesting
            .follow(follower, pool, script, done.get)
            .as(Right(()): Either[NodeError, Unit])
        )
        .rescue(e => IO.pure(Left(e)))
      selected <- state.get
      known <- pool.invalidBlocks.snapshot
    yield Result(outcome, selected.tip, known.entries.keySet)

  test("a forgiven invalid tip keeps the session alive for rollback and a lawful replacement") {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      bad = forks._1.headers.head
      good = forks._2.headers.head
      result <- run(
        List(
          Action.Add(bad, forks._1.raws.head),
          Action.Rollback(ChainSync.Point.Origin),
          Action.Add(good, forks._2.raws.head)
        ),
        Set(bad.hash)
      )
    yield
      assertEquals(result.outcome, Right(()))
      assertEquals(result.selected, good.point)
      assertEquals(result.invalid, Set(bad.hash))
  }

  test("separate invalid additions at the same height retain the BF issuer trap state") {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      left = forks._1.headers.take(2)
      right = forks._2.headers.take(2)
      result <- run(
        left.zip(forks._1.raws).map(Action.Add.apply).toList ++
          List(Action.Rollback(ChainSync.Point.Origin)) ++
          right.zip(forks._2.raws).map(Action.Add.apply).toList,
        Set(left.last.hash, right.last.hash)
      )
    yield
      assertEquals(result.outcome, Left(PeerError.InvalidBlock(refused(right.last))))
      assertEquals(result.invalid, Set(left.last.hash, right.last.hash))
      assertNotEquals(result.selected, left.last.point)
      assertNotEquals(result.selected, right.last.point)
  }
