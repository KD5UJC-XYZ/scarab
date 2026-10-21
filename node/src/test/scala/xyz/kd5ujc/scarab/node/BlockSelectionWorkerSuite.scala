// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.mtl.{Raise, Stateful}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRules, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Header, ValidationError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.forge.ForgedChain

class BlockSelectionWorkerSuite extends FailsOnUncaught:
  private case class Node(
      pool: BlockPool[IO],
      follower: Follower[IO, BigInt],
      state: Ref[IO, VolatileChain[BigInt]],
      reports: Ref[IO, Vector[ValidationError]],
      fatals: Ref[IO, Vector[Fatal]],
      governor: Option[GenesisGovernor[IO]]
  )
  private def error(header: Header) =
    ValidationError(header.point, BlockFailure.EpochPrecedesLedger(0))

  private def node(
      invalid: Set[Bytes],
      k: Int = 5,
      pulsed: Boolean = false,
      beforeRule: Header => IO[Unit] = _ => IO.unit
  )(use: Node => IO[Unit]): IO[Unit] =
    for
      state <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(k, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      pool <- BlockPool.create[IO]
      reports <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
      fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, h, _) =>
          beforeRule(h) *>
            (if invalid(h.hash) then Raise[IO, ValidationError].raise(error(h))
             else IO.pure(h.blockNo))
        ),
        _ => IO.unit,
        ForgedChain.geometry
      )
      _ <- follower.installInvalidBlocks(pool.invalidBlocks)
      governor <-
        (if pulsed then
           GenesisGovernor
             .create[IO](follower.genesisView((_, _, _) => Some(30)))
             .map(Some(_))
         else IO.pure(None))
      _ <- governor.traverse_(follower.installGenesis)
      work <- SelectionWork.create[IO](IO.unit)
      _ <- follower
        .blockSelection(pool, work, e => fatals.update(_ :+ e))
        .use(_ => use(Node(pool, follower, state, reports, fatals, governor)))
        .timeout(5.seconds)
    yield ()

  private def checked(chain: ForgedChain, count: Int): IO[Vector[BlockPool.Delivered]] =
    chain.headers
      .zip(chain.raws)
      .take(count)
      .traverse((h, raw) =>
        BlockPool.Delivered.checked(h, raw).fold(e => IO(fail(e.message)), IO.pure)
      )

  private def add(claims: BlockPool.Claims[IO], bodies: Vector[BlockPool.Delivered]): IO[Unit] =
    claims.claim(bodies.map(_.header.hash)) *> claims.fillDelivered(bodies)

  test("FIFO processing excludes later queued bodies and retains the same-hot issuer trap") {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      left <- checked(forks._1, 3)
      right <- checked(forks._2, 3)
      _ <- node(Set(left.last.header.hash, right.last.header.hash)) { n =>
        n.pool.session(Some(e => n.reports.update(_ :+ e))).use { sender =>
          for
            _ <- add(sender, left)
            _ <- n.pool.admissions.reprocess
            first <- n.reports.get
            _ <- add(sender, right)
            _ <- n.pool.admissions.reprocess
            reports <- n.reports.get
            known <- n.pool.invalidBlocks.snapshot
            fatals <- n.fatals.get
            _ <- IO {
              assertEquals(first, Vector.empty)
              assertEquals(reports, Vector(error(right.last.header)))
              assertEquals(known.entries.keySet, Set(left.last.header.hash, right.last.header.hash))
              assertEquals(fatals, Vector.empty)
            }
          yield ()
        }
      }
    yield ()
  }

  test(
    "a later missing parent owns selection; an older invalid descendant does not pollute its sender's trap"
  ) {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      left <- checked(forks._1, 3)
      right <- checked(forks._2, 3)
      _ <- node(Set(left.last.header.hash, right.last.header.hash)) { n =>
        (
          n.pool.session(Some(e => n.reports.update(_ :+ e))),
          n.pool.session(Some(e => n.reports.update(_ :+ e)))
        ).tupled.use { (descendants, parent) =>
          for
            _ <- add(descendants, left.drop(1).reverse)
            _ <- add(parent, left.take(1))
            _ <- n.pool.admissions.reprocess
            first <- n.reports.get
            _ <- add(descendants, right)
            _ <- n.pool.admissions.reprocess
            reports <- n.reports.get
            known <- n.pool.invalidBlocks.snapshot
            fatals <- n.fatals.get
            _ <- IO {
              assertEquals(first, Vector.empty)
              assertEquals(reports, Vector.empty)
              assertEquals(known.entries.keySet, Set(left.last.header.hash, right.last.header.hash))
              assertEquals(fatals, Vector.empty)
            }
          yield ()
        }
      }
    yield ()
  }

  test("source teardown cannot cancel an admitted block's ledger processing") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 1)
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      _ <- node(
        Set(bodies.head.header.hash),
        beforeRule = _ => entered.complete(()).void *> release.get
      ) { n =>
        for
          _ <- n.pool.session.use(sender => add(sender, bodies) *> entered.get)
          pending <- n.pool.invalidBlocks.snapshot
          _ <- IO(assertEquals(pending.entries, Map.empty))
          _ <- release.complete(()) *> n.pool.admissions.reprocess
          known <- n.pool.invalidBlocks.snapshot
          raw <- n.pool.take(bodies.head.header.hash)
          fatal <- n.fatals.get
          _ <- IO {
            assertEquals(known.entries.keySet, Set(bodies.head.header.hash))
            assertEquals(raw, Some(bodies.head.block))
            assertEquals(fatal, Vector.empty)
          }
        yield ()
      }
    yield ()
  }

  test(
    "LoE postponement consumes the addition; CaughtUp reprocessing cannot replay its punishment"
  ) {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      left <- checked(forks._1, 3)
      right <- checked(forks._2, 3)
      _ <- node(Set(left.last.header.hash, right.last.header.hash), k = 2, pulsed = true) { n =>
        n.pool.session(Some(e => n.reports.update(_ :+ e))).use { sender =>
          for
            _ <- add(sender, left) *> n.pool.admissions.reprocess
            before <- n.pool.invalidBlocks.snapshot
            selectedBefore <- n.state.get
            _ <- IO {
              assertEquals(before.entries, Map.empty)
              assertEquals(selectedBefore.tip, left(1).header.point)
            }
            _ <- n.governor.fold(IO(fail("missing configured governor")))(
              _.setPhase(Gsm.Phase.CaughtUp)
            )
            _ <- n.pool.admissions.reprocess
            after <- n.pool.invalidBlocks.snapshot
            _ <- add(sender, right) *> n.pool.admissions.reprocess
            reports <- n.reports.get
            fatals <- n.fatals.get
            _ <- IO {
              assertEquals(after.entries.keySet, Set(left.last.header.hash))
              assertEquals(reports, Vector.empty)
              assertEquals(fatals, Vector.empty)
            }
          yield ()
        }
      }
    yield ()
  }
