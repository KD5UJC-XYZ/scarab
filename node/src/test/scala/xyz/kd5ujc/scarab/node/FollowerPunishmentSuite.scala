// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.mtl.{Handle, Raise, Stateful}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRules, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Header, NodeError, PeerError, ValidationError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ChainSync

/** Exercises the actual Follower selection boundary with checked original admissions. The scripted
  * ChainSync events vary delivery independently of validation order, as ChainDB's add-block queue
  * does.
  */
class FollowerPunishmentSuite extends FailsOnUncaught:
  private enum Action:
    case Add(header: Header, raw: Bytes)
    case Announce(header: Header)
    case Rollback(point: ChainSync.Point)

  private final case class Result(
      outcome: Either[NodeError, Unit],
      selected: ChainSync.Point,
      fetched: Vector[Bytes],
      reports: Vector[ValidationError],
      invalid: Set[Bytes]
  )

  private def refused(header: Header) =
    ValidationError(header.point, BlockFailure.EpochPrecedesLedger(0))

  private def run(actions: List[Action], invalid: Set[Bytes]): IO[Result] =
    for
      state <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(5, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      pool <- BlockPool.create[IO]
      reports <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
      fetched <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      pending <- Ref.of[IO, List[Action]](actions)
      last <- Ref.of[IO, Option[BlockPool.Taken]](None)
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, h, _) =>
          if invalid(h.hash) then Raise[IO, ValidationError].raise(refused(h))
          else IO.pure(h.blockNo)
        ),
        _ => IO.unit,
        ForgedChain.geometry
      )
      _ <- follower.installInvalidBlocks(pool.invalidBlocks)
      outcome <- pool.session(Some(e => reports.update(_ :+ e))).use { claims =>
        val peer = new Peer[IO]:
          override def invalidBlocks = Some(pool.invalidBlocks)
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
                case None => Raise[IO, PeerError].raise(PeerError.NoIntersection)
                case Some(Action.Rollback(point)) =>
                  IO.pure(SyncEvent.RollBackward(point, ChainSync.Tip.Origin))
                case Some(Action.Announce(header)) =>
                  IO.pure(SyncEvent.RollForward(header, ChainSync.Tip.Origin))
                case Some(Action.Add(header, raw)) =>
                  BlockPool.Delivered
                    .checked(header, raw)
                    .fold(e => IO(fail(e.message)), IO.pure)
                    .flatMap { checked =>
                      claims.claim(Vector(header.hash)) *> claims.fillDelivered(Vector(checked))
                    }
                    .as(SyncEvent.RollForward(header, ChainSync.Tip.Origin))
              }
          def fetch(header: Header)(using Raise[IO, PeerError]) =
            claims.take(header.hash).flatMap {
              case Some(taken) =>
                last.set(Some(taken)) *> fetched.update(_ :+ header.hash).as(taken.block)
              case None => IO(fail("selection fetched a header with no admitted body"))
            }
          override def validation(
              header: Header
          )(using cats.Applicative[IO], Raise[IO, PeerError]) =
            claims
              .takeAddition(header)
              .map(_.map {
                case Some(taken) => Peer.Validation.added(claims, header, taken, _ => IO.unit)
                case None        => Peer.Validation.none[IO]
              })
          override def invalidated(header: Header, error: ValidationError)(using
              cats.Applicative[IO]
          ) =
            last.get.flatMap(
              _.fold(IO(fail("missing fetched original")))(claims.invalidated(_, header, error))
            )
        Handle
          .allow[NodeError](follower.session(peer).as(Right(()): Either[NodeError, Unit]))
          .rescue(e => IO.pure(Left(e)))
      }
      selected <- state.get
      reads <- fetched.get
      punished <- reports.get
      known <- pool.invalidBlocks.snapshot
    yield Result(outcome, selected.tip, reads, punished, known.entries.keySet)

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
      assertEquals(result.outcome, Left(PeerError.NoIntersection))
      assertEquals(result.selected, good.point)
      assertEquals(result.reports, Vector.empty)
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
      assertEquals(result.outcome, Left(refused(right.last)))
      assertEquals(result.reports, Vector(refused(right.last)))
      assertEquals(result.invalid, Set(left.last.hash, right.last.hash))
      assertNotEquals(result.selected, left.last.point)
      assertNotEquals(result.selected, right.last.point)
  }

  test("a nonpreferred announced fork does not create a body fetch") {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      selected = forks._1.headers.take(2)
      result <- run(
        selected.zip(forks._1.raws).map(Action.Add.apply).toList ++ List(
          Action.Rollback(ChainSync.Point.Origin),
          Action.Announce(forks._2.headers.head)
        ),
        Set.empty
      )
    yield
      assertEquals(result.outcome, Left(PeerError.NoIntersection))
      assertEquals(result.selected, selected.last.point)
      assertEquals(result.fetched, selected.map(_.hash))
  }
