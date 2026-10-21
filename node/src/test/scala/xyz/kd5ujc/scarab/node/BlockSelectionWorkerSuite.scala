// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource, Outcome}
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import scala.concurrent.duration.*
import scala.math.Ordering.Implicits.seqOrdering
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRules, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Header, ValidationError, NodeError, StorageError}
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
      beforeRule: Header => IO[Unit] = _ => IO.unit,
      onTip: Option[Follower.OnTip[IO, BigInt]] = None,
      preparation: BlockSelection.Before[IO] = BlockSelection.Before.noop[IO],
      fatalAction: Fatal => IO[Unit] = _ => IO.unit,
      initial: Option[VolatileChain[BigInt]] = None,
      recovered: Vector[BlockPool.Delivered] = Vector.empty
  )(use: Node => IO[Unit]): IO[Unit] =
    for
      state <- Ref.of[IO, VolatileChain[BigInt]](initial.getOrElse(VolatileChain.genesis(k, -1)))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      pool <- BlockPool.create[IO]
      _ <- pool.seedRecoveredHeaders(recovered)
      reports <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
      fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, h, _) =>
          beforeRule(h) *>
            (if invalid(h.hash) then Raise[IO, ValidationError].raise(error(h))
             else IO.pure(h.blockNo))
        ),
        _ => IO.unit,
        ForgedChain.geometry,
        onTip = onTip
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
      given BlockSelection.Before[IO] = preparation
      _ <- Handle
        .allow[NodeError] {
          follower
            .blockSelection(pool, work, e => fatals.update(_ :+ e) *> fatalAction(e))
            .use(_ => use(Node(pool, follower, state, reports, fatals, governor)))
            .timeout(5.seconds)
        }
        .rescue(error => IO(fail(error.message)))
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

  test(
    "onTip storage errors use the applying worker scope and close admissions before a stalled fatal sink"
  ) {
    val failure = StorageError.Io("worker-on-tip", new java.io.IOException("onTip test fault"))
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 1)
      reported <- Deferred[IO, Fatal]
      release <- Deferred[IO, Unit]
      _ <- node(
        Set.empty,
        onTip = Some(Follower.OnTip[IO, BigInt]((_, _) => Raise[IO, StorageError].raise(failure))),
        fatalAction = fatal => reported.complete(fatal).void *> release.get
      ) { n =>
        n.pool.session(Some(e => n.reports.update(_ :+ e))).use { sender =>
          for
            _ <- add(sender, bodies)
            actual <- reported.get
            _ <- IO(assertEquals(actual, Fatal.Storage(failure)))
            _ <- Resource.make(n.pool.admissions.reprocess.start)(_.cancel).use { waiting =>
              waiting.join.flatMap {
                case Outcome.Canceled() => IO.unit
                case other =>
                  IO(fail(s"closed runner did not cancel its blocked submitter: $other"))
              }
            }
            reports <- n.reports.get
            _ <- IO(assertEquals(reports, Vector.empty))
            _ <- release.complete(())
          yield ()
        }
      }
    yield ()
  }

  test("node-owned preparation resolves its own typed storage capability") {
    val failure = StorageError.Io("worker-receipt", new java.io.IOException("receipt test fault"))
    val preparation = new BlockSelection.Before[IO]:
      def prepare(@scala.annotation.unused header: Header, @scala.annotation.unused raw: Bytes)(
          using Raise[IO, NodeError]
      ): IO[Unit] = Raise[IO, StorageError].raise(failure)
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 1)
      reported <- Deferred[IO, Fatal]
      _ <- node(
        Set.empty,
        preparation = preparation,
        fatalAction = e => reported.complete(e).void
      ) { n =>
        n.pool.session(Some(e => n.reports.update(_ :+ e))).use { sender =>
          for
            _ <- add(sender, bodies)
            actual <- reported.get
            reports <- n.reports.get
            invalid <- n.pool.invalidBlocks.snapshot
            _ <- IO {
              assertEquals(actual, Fatal.Storage(failure))
              assertEquals(reports, Vector.empty)
              assertEquals(invalid.entries, Map.empty)
            }
          yield ()
        }
      }
    yield ()
  }

  test(
    "startup selects the complete recovered forest from the immutable state, not the resumed volatile winner"
  ) {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      left <- checked(forks._1, 1)
      right <- checked(forks._2, 1)
      originals = left ++ right
      // Native initialChainSelection ranks equal-height Praos candidates by unrestricted VRF.
      ranked = originals.sortWith((a, b) =>
        summon[Ordering[Vector[Int]]].lt(
          xyz.kd5ujc.scarab.consensus.SelectView
            .of(a.header)
            .tieBreak
            .fold(fail("missing VRF"))(_.value.map(_ & 255)),
          xyz.kd5ujc.scarab.consensus.SelectView
            .of(b.header)
            .tieBreak
            .fold(fail("missing VRF"))(_.value.map(_ & 255))
        )
      )
      previous = ranked.last.header
      resumed = VolatileChain
        .genesis[BigInt](5, 42)
        .extend(previous, 99)
        .fold(e => fail(e.message), _._1)
      published <- Ref
        .of[IO, Vector[(xyz.kd5ujc.scarab.protocol.ChainSync.Point, BigInt, Int)]](Vector.empty)
      _ <- node(
        Set.empty,
        initial = Some(resumed),
        recovered = originals,
        onTip = Some(
          Follower.OnTip[IO, BigInt]((chain, raws) =>
            published.update(_ :+ ((chain.tip, chain.state, raws.size)))
          )
        )
      ) { n =>
        for
          _ <- n.pool.admissions.reprocess
          selected <- n.state.get
          tips <- published.get
          fatals <- n.fatals.get
          _ <- IO {
            assertEquals(selected.tip, ranked.head.header.point)
            assertEquals(selected.anchor.state, BigInt(42))
            assertEquals(
              tips.headOption,
              Some((xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin, BigInt(42), 0))
            )
            assertEquals(tips.lastOption.map(_._1), Some(ranked.head.header.point))
            assertEquals(fatals, Vector.empty)
          }
        yield ()
      }
    yield ()
  }

  test("startup retains every checked recovered branch beyond the ordinary cache threshold") {
    import xyz.kd5ujc.scarab.node.genesis.UpstreamGenesisBlocks
    import xyz.kd5ujc.scarab.protocol.ChainSync.Point
    val points = Vector.tabulate[Point.Block](2 * BlockPool.Retained + 1) { i =>
      val value = (i + 1).toLong
      Point.Block(
        xyz.kd5ujc.scarab.protocol.ChainSync.UInt64.saturating(i + 1),
        Bytes(Vector.tabulate(8)(b => (value >>> (8 * b)).toByte))
      )
    }
    for
      universe <- UpstreamGenesisBlocks.buildPoints(points).fold(e => IO(fail(e.toString)), IO.pure)
      checked <- universe.blocks.traverse(block =>
        BlockPool.Delivered
          .checked(block.header, block.body)
          .fold(e => IO(fail(e.message)), IO.pure)
      )
      _ <- node(Set.empty, recovered = checked) { n =>
        for
          processed <- n.pool.processedSnapshot
          selected <- n.state.get
          originals <- Vector(checked.head, checked.last).traverse(body =>
            n.pool.take(body.header.hash)
          )
          flags <- n.pool.session.use(
            _.claim(Vector(checked.head.header.hash, checked.last.header.hash))
          )
          fatals <- n.fatals.get
          _ <- IO {
            assertEquals(processed.size, 2 * BlockPool.Retained + 1)
            assertNotEquals(selected.tip, Point.Origin)
            assertEquals(originals, Vector(Some(checked.head.block), Some(checked.last.block)))
            assertEquals(flags, Vector(false, false))
            assertEquals(fatals, Vector.empty)
          }
        yield ()
      }
    yield ()
  }

  test(
    "startup failure reaches the acquiring caller as its typed storage error before resource use"
  ) {
    val failure =
      StorageError.Io("initial-selection", new java.io.IOException("startup test fault"))
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 1)
      state <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(5, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      pool <- BlockPool.create[IO]
      _ <- pool.seedRecoveredHeaders(bodies)
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, _, _) => Raise[IO, StorageError].raise(failure)),
        _ => IO.unit,
        ForgedChain.geometry
      )
      _ <- follower.installInvalidBlocks(pool.invalidBlocks)
      used <- Ref.of[IO, Boolean](false)
      work <- SelectionWork.create[IO](IO.unit)
      given BlockSelection.Before[IO] = BlockSelection.Before.noop[IO]
      result <- Handle
        .allow[NodeError] {
          follower
            .blockSelection(pool, work, _ => IO.unit)
            .use(_ => used.set(true))
            .as(Right(()): Either[NodeError, Unit])
        }
        .rescue(error => IO.pure(Left(error)))
        .timeout(2.seconds)
      entered <- used.get
      invalid <- pool.invalidBlocks.snapshot
      _ <- IO {
        assertEquals(result, Left(failure))
        assert(!entered)
        assertEquals(invalid.entries, Map.empty)
      }
    yield ()
  }
