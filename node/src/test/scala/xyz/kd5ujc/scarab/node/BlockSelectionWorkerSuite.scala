// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource, Outcome}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import scala.concurrent.duration.*
import scala.math.Ordering.Implicits.seqOrdering
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRules, FollowerEvent, NoVerdictRetry, VolatileChain}
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
      governor: Option[GenesisGovernor[IO]],
      events: Ref[IO, Vector[FollowerEvent]]
  )

  /** What the rules say of a block, when neither `invalid` nor `unavailable` names it. */
  private enum Verdict:
    case Valid, Refused
    case Unavailable(why: xyz.kd5ujc.scarab.plutus.BackendFailure)
  private def error(header: Header) =
    ValidationError(header.point, BlockFailure.EpochPrecedesLedger(0))

  /** A block without a verdict: the evaluator failed with `why` on one of its scripts. */
  private def noVerdict(header: Header, why: xyz.kd5ujc.scarab.plutus.BackendFailure) =
    ValidationError(
      header.point,
      BlockFailure.Tx(
        xyz.kd5ujc.scarab.ledger.LedgerFailure.Unavailable(
          header.hash,
          xyz.kd5ujc.scarab.ledger.ScriptLocation.Phase1(header.hash),
          xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported(why)
        )
      )
    )

  /** One node with its block-selection runner, started and used within `backstop`. The runner
    * signals the end of startup selection itself; `backstop` only bounds a hang and names it.
    */
  private def node(
      invalid: Set[Bytes],
      k: Int = 5,
      pulsed: Boolean = false,
      beforeRule: Header => IO[Unit] = _ => IO.unit,
      onTip: Option[Follower.OnTip[IO, BigInt]] = None,
      preparation: BlockSelection.Before[IO] = BlockSelection.Before.noop[IO],
      fatalAction: Fatal => IO[Unit] = _ => IO.unit,
      initial: Option[VolatileChain[BigInt]] = None,
      recovered: Vector[BlockPool.Delivered] = Vector.empty,
      unavailable: Map[Bytes, xyz.kd5ujc.scarab.plutus.BackendFailure] = Map.empty,
      verdict: Header => IO[Verdict] = _ => IO.pure(Verdict.Valid),
      backstop: FiniteDuration = 5.seconds
  )(use: Node => IO[Unit]): IO[Unit] =
    for
      state <- Ref.of[IO, VolatileChain[BigInt]](initial.getOrElse(VolatileChain.genesis(k, -1)))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      pool <- BlockPool.create[IO]
      _ <- pool.seedRecoveredHeaders(recovered)
      reports <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
      fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      events <- Ref.of[IO, Vector[FollowerEvent]](Vector.empty)
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, h, _) =>
          beforeRule(h) *>
            (if invalid(h.hash) then Raise[IO, ValidationError].raise(error(h))
             else
               unavailable
                 .get(h.hash)
                 .fold(verdict(h).flatMap {
                   case Verdict.Valid   => IO.pure(h.blockNo)
                   case Verdict.Refused => Raise[IO, ValidationError].raise(error(h))
                   case Verdict.Unavailable(why) =>
                     Raise[IO, ValidationError].raise(noVerdict(h, why))
                 })(why => Raise[IO, ValidationError].raise(noVerdict(h, why))))
        ),
        event => events.update(_ :+ event),
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
            .use(_ => use(Node(pool, follower, state, reports, fatals, governor, events)))
            .timeoutTo(
              backstop,
              IO(fail(s"block selection startup and use did not finish within $backstop"))
            )
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

  test("committed header revisions survive late subscription and ignore unchanged reprocessing") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 2)
      _ <- node(Set.empty) { n =>
        n.pool.session.use { sender =>
          for
            initial <- n.follower.headerChanges.revision
            _ <- add(sender, bodies.take(1)) *> n.pool.admissions.reprocess
            first <- n.follower.headerChanges.changed(initial)
            _ <- n.pool.admissions.reprocess
            unchanged <- n.follower.headerChanges.revision
            _ <- add(sender, bodies.drop(1)) *> n.pool.admissions.reprocess
            second <- n.follower.headerChanges.changed(first)
          yield
            assertEquals(first, initial + 1)
            assertEquals(unchanged, first)
            assertEquals(second, first + 1)
        }
      }
    yield ()
  }

  List(
    "a missing implementation" -> xyz.kd5ujc.scarab.plutus.BackendFailure
      .BuiltinUnavailable(scalus.uplc.DefaultFun.Bls12_381_G1_hashToGroup),
    "an evaluator defect a block triggers" -> xyz.kd5ujc.scarab.plutus.BackendFailure
      .Defect(new NullPointerException("Scalus"))
  ).foreach { (name, missing) =>
    // issue 567: no verdict on a block withholds it; selection, and the node, go on
    test(s"$name withholds the block: no stop, no invalid record, no blame") {
      for
        chain <- ForgedChain.forge(5000 until 5060)
        bodies <- checked(chain, 2)
        _ <- node(Set.empty, unavailable = Map(bodies(1).header.hash -> missing)) { n =>
          n.pool.session(Some(e => n.reports.update(_ :+ e))).use { sender =>
            for
              _ <- add(sender, bodies)
              _ <- n.pool.admissions.reprocess
              withheld <- n.pool.noVerdictBlocks.hashes
              fatals <- n.fatals.get
              known <- n.pool.invalidBlocks.snapshot
              reports <- n.reports.get
              tip <- n.state.get.map(_.tip)
              _ <- IO {
                assertEquals(withheld, Set(bodies(1).header.hash))
                assertEquals(fatals, Vector.empty)
                assertEquals(known.entries, Map.empty)
                assertEquals(reports, Vector.empty)
                assertEquals(tip, bodies(0).header.point)
              }
            yield ()
          }
        }
      yield ()
    }
  }

  // issue 567: a withheld block is not judged again when selection runs again
  test("a block without a verdict is judged once: selecting again does not judge it again") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 2)
      judged <- Ref.of[IO, Map[Bytes, Int]](Map.empty)
      _ <- node(
        Set.empty,
        beforeRule = h => judged.update(m => m.updated(h.hash, m.getOrElse(h.hash, 0) + 1)),
        unavailable = Map(
          bodies(1).header.hash -> xyz.kd5ujc.scarab.plutus.BackendFailure
            .BuiltinUnavailable(scalus.uplc.DefaultFun.Bls12_381_G1_hashToGroup)
        )
      ) { n =>
        n.pool.session(Some(e => n.reports.update(_ :+ e))).use { sender =>
          for
            _ <- add(sender, bodies)
            _ <- n.pool.admissions.reprocess
            _ <- n.pool.admissions.reprocess
            _ <- n.pool.admissions.reprocess
            counts <- judged.get
            fatals <- n.fatals.get
            _ <- IO {
              assertEquals(counts.get(bodies(1).header.hash), Some(1))
              assertEquals(fatals, Vector.empty)
            }
          yield ()
        }
      }
    yield ()
  }

  // issue 567: a block without a verdict does not hold the node on its chain: a competing chain
  // that avoids it is adopted
  test("a competing chain that avoids a block without a verdict is adopted") {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      left <- checked(forks._1, 3)
      right <- checked(forks._2, 3)
      _ <- node(
        Set.empty,
        unavailable = Map(
          left(1).header.hash -> xyz.kd5ujc.scarab.plutus.BackendFailure
            .Defect(new NullPointerException("Scalus"))
        )
      ) { n =>
        n.pool.session(Some(e => n.reports.update(_ :+ e))).use { sender =>
          for
            _ <- add(sender, left)
            _ <- n.pool.admissions.reprocess
            stuck <- n.state.get.map(_.tip)
            _ <- add(sender, right)
            _ <- n.pool.admissions.reprocess
            tip <- n.state.get.map(_.tip)
            fatals <- n.fatals.get
            known <- n.pool.invalidBlocks.snapshot
            _ <- IO {
              assertEquals(stuck, left(0).header.point)
              assertEquals(tip, right.last.header.point)
              assertEquals(fatals, Vector.empty)
              assertEquals(known.entries, Map.empty)
            }
          yield ()
        }
      }
    yield ()
  }

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
    "startup preserves the recovered winner without replaying or publishing a rollback"
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
      previous = ranked.head.header
      resumed = VolatileChain
        .genesis[BigInt](5, 42)
        .extend(previous, 99)
        .fold(e => fail(e.message), _._1)
      validations <- Ref.of[IO, Int](0)
      published <- Ref
        .of[IO, Vector[(xyz.kd5ujc.scarab.protocol.ChainSync.Point, BigInt, Int)]](Vector.empty)
      _ <- node(
        Set.empty,
        initial = Some(resumed),
        recovered = originals,
        beforeRule = _ => validations.update(_ + 1),
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
          applied <- validations.get
          fatals <- n.fatals.get
          _ <- IO {
            assertEquals(selected.tip, ranked.head.header.point)
            assertEquals(selected.anchor.state, BigInt(42))
            assertEquals(selected, resumed)
            assertEquals(applied, 0)
            assertEquals(tips, Vector.empty)
            assertEquals(fatals, Vector.empty)
          }
        yield ()
      }
    yield ()
  }

  test("startup still selects the native recovered winner when the resumed branch differs") {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      left <- checked(forks._1, 1)
      right <- checked(forks._2, 1)
      ranked = (left ++ right).sortWith((a, b) =>
        CandidateSelection.compareTips(a.header, b.header) < 0
      )
      resumed = VolatileChain
        .genesis[BigInt](5, 42)
        .extend(ranked.last.header, 99)
        .fold(e => fail(e.message), _._1)
      validations <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      tips <- Ref.of[IO, Vector[xyz.kd5ujc.scarab.protocol.ChainSync.Point]](Vector.empty)
      _ <- node(
        Set.empty,
        initial = Some(resumed),
        recovered = left ++ right,
        beforeRule = h => validations.update(_ :+ h.hash),
        onTip = Some(Follower.OnTip[IO, BigInt]((chain, _) => tips.update(_ :+ chain.tip)))
      ) { n =>
        for
          selected <- n.state.get
          applied <- validations.get
          published <- tips.get
        yield
          assertEquals(selected.tip, ranked.head.header.point)
          assertEquals(applied, Vector(ranked.head.header.hash))
          assertEquals(published, Vector(ranked.head.header.point))
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
      // Startup selection compares every recovered branch: bound a hang, not that linear work.
      _ <- node(Set.empty, recovered = checked, backstop = 15.seconds) { n =>
        for
          processed <- n.pool.processedSnapshot
          selected <- n.state.get
          originals <- Vector(checked.head, checked.last).traverse(body =>
            n.pool.take(body.header.hash)
          )
          flags <- n.pool.session.use(
            _.claim(Vector(checked.head.header.hash, checked.last.header.hash))
          )
          retained <- n.pool.session.use(sender =>
            Vector(checked.head, checked.last).traverse(body =>
              sender.retained(BlockPool.Source.Recovered, body.header.hash)
            )
          )
          fatals <- n.fatals.get
          _ <- IO {
            assertEquals(processed.size, 2 * BlockPool.Retained + 1)
            assertNotEquals(selected.tip, Point.Origin)
            assertEquals(originals, Vector(Some(checked.head.block), Some(checked.last.block)))
            assertEquals(flags, Vector(false, false))
            assertEquals(retained, Vector(true, true))
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

  // issue 693: a block without a verdict for a cause that can pass is judged again, 1 s doubled
  // after each attempt up to 30 s, measured from the end of an attempt, under a simulated clock

  /** A cause that can pass: the evaluator's worker ran out of memory. */
  private val outOfMemory =
    xyz.kd5ujc.scarab.plutus.BackendFailure.Defect(new OutOfMemoryError("Java heap space"))

  /** The attempts on a block whose cause does not clear, the first at 0. */
  private val schedule = Vector(0, 1, 3, 7, 15, 31, 61, 91, 121).map(_.seconds)

  /** The simulated times at which the rules judged each block, and the rule hook that keeps them.
    */
  private final class Judgements(times: Ref[IO, Map[Bytes, Vector[FiniteDuration]]]):
    def record(header: Header): IO[Unit] =
      IO.monotonic.flatMap(at =>
        times.update(m => m.updated(header.hash, m.getOrElse(header.hash, Vector.empty) :+ at))
      )
    def of(header: Header): IO[Vector[FiniteDuration]] =
      times.get.map(_.getOrElse(header.hash, Vector.empty))

  private def judgements: IO[Judgements] =
    Ref.of[IO, Map[Bytes, Vector[FiniteDuration]]](Map.empty).map(Judgements(_))

  /** The no-verdict attempts reported on `header`, with when each said it is judged again. */
  private def reported(n: Node, header: Header): IO[Vector[(Int, NoVerdictRetry)]] =
    n.events.get.map(_.collect {
      case FollowerEvent.NoVerdict(point, _, attempt, retry) if point == header.point =>
        attempt -> retry
    })

  private def retried(n: Node, header: Header): IO[Vector[Int]] =
    n.events.get.map(_.collect {
      case FollowerEvent.NoVerdictRetried(point, attempt) if point == header.point => attempt
    })

  test("a passing cause that clears: judged again on the capped schedule, adopted within the cap") {
    val clears = 100.seconds
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 2)
      target = bodies(1).header
      _ <- TestControl.executeEmbed(judgements.flatMap { judged =>
        node(
          Set.empty,
          beforeRule = judged.record,
          verdict = h =>
            if h.hash != target.hash then IO.pure(Verdict.Valid)
            else
              IO.monotonic.map(at =>
                if at < clears then Verdict.Unavailable(outOfMemory) else Verdict.Valid
              )
          ,
          backstop = 10.minutes
        ) { n =>
          n.pool.session(Some(e => n.reports.update(_ :+ e))).use { sender =>
            for
              _ <- add(sender, bodies) *> n.pool.admissions.reprocess
              _ <- IO.sleep(200.seconds)
              attempts <- judged.of(target)
              noVerdicts <- reported(n, target)
              retries <- retried(n, target)
              tip <- n.state.get.map(_.tip)
              withheld <- n.pool.noVerdictBlocks.hashes
              known <- n.pool.invalidBlocks.snapshot
              reports <- n.reports.get
              fatals <- n.fatals.get
              _ <- IO {
                assertEquals(attempts, schedule)
                assert(attempts.last - clears <= NoVerdictBlocks.MaxDelay, attempts)
                assertEquals(
                  noVerdicts,
                  Vector(1, 2, 4, 8, 16, 30, 30, 30).zipWithIndex
                    .map((wait, i) => (i + 1) -> NoVerdictRetry.After(wait.seconds))
                )
                assertEquals(retries, (2 to 9).toVector)
                assertEquals(tip, target.point)
                assertEquals(withheld, Set.empty[Bytes])
                assertEquals(known.entries, Map.empty)
                assertEquals(reports, Vector.empty)
                assertEquals(fatals, Vector.empty)
              }
            yield ()
          }
        }
      })
    yield ()
  }

  List(
    xyz.kd5ujc.scarab.plutus.BackendFailure
      .BuiltinUnavailable(scalus.uplc.DefaultFun.Bls12_381_G1_hashToGroup),
    xyz.kd5ujc.scarab.plutus.BackendFailure
      .CostingUnmirrored(scalus.uplc.DefaultFun.Bls12_381_G1_hashToGroup),
    xyz.kd5ujc.scarab.plutus.BackendFailure
      .BudgetOutOfRange(xyz.kd5ujc.scarab.plutus.ExUnits(BigInt(2).pow(64), BigInt(0))),
    xyz.kd5ujc.scarab.plutus.BackendFailure.StackExhausted
  ).foreach { why =>
    test(s"a deterministic cause is judged once: ${why.productPrefix}") {
      for
        chain <- ForgedChain.forge(5000 until 5060)
        bodies <- checked(chain, 2)
        target = bodies(1).header
        _ <- TestControl.executeEmbed(judgements.flatMap { judged =>
          node(
            Set.empty,
            beforeRule = judged.record,
            unavailable = Map(target.hash -> why),
            backstop = 30.minutes
          ) { n =>
            n.pool.session.use { sender =>
              for
                _ <- add(sender, bodies) *> n.pool.admissions.reprocess
                _ <- IO.sleep(10.minutes) *> n.pool.admissions.reprocess
                attempts <- judged.of(target)
                noVerdicts <- reported(n, target)
                entry <- n.pool.noVerdictBlocks.lookup(target.hash)
                _ <- IO {
                  assertEquals(attempts, Vector(Duration.Zero))
                  assertEquals(noVerdicts, Vector(1 -> NoVerdictRetry.Never))
                  assertEquals(entry.map(_.schedule), Some(NoVerdictBlocks.Schedule.Once))
                }
              yield ()
            }
          }
        })
      yield ()
    }
  }

  test("re-delivery and a reconnect neither judge the block again nor move its next attempt") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 2)
      target = bodies(1).header
      _ <- TestControl.executeEmbed(judgements.flatMap { judged =>
        node(
          Set.empty,
          beforeRule = judged.record,
          unavailable = Map(target.hash -> outOfMemory),
          backstop = 10.minutes
        ) { n =>
          n.pool.session.use { sender =>
            for
              _ <- add(sender, bodies) *> n.pool.admissions.reprocess
              // the first attempt at 0, the second at 1 s, the third due at 3 s
              _ <- IO.sleep(1500.millis)
              before <- n.pool.noVerdictBlocks.lookup(target.hash)
              // the same session delivers the block again, then another one does
              _ <- add(sender, bodies) *> n.pool.admissions.reprocess
              _ <- n.pool.session.use(other => add(other, bodies)) *> n.pool.admissions.reprocess
              after <- n.pool.noVerdictBlocks.lookup(target.hash)
              _ <- IO.sleep(1400.millis)
              early <- judged.of(target)
              _ <- IO.sleep(200.millis)
              due <- judged.of(target)
              _ <- IO {
                assertEquals(before.map(_.attempts), Some(2))
                assertEquals(before.map(_.schedule), Some(NoVerdictBlocks.Schedule.At(3.seconds)))
                assertEquals(after, before)
                assertEquals(early, Vector(0, 1).map(_.seconds))
                assertEquals(due, Vector(0, 1, 3).map(_.seconds))
              }
            yield ()
          }
        }
      })
    yield ()
  }

  test("a repeating failure does not starve admissions: a competing chain is adopted at once") {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      left <- checked(forks._1, 3)
      right <- checked(forks._2, 3)
      _ <- TestControl.executeEmbed(judgements.flatMap { judged =>
        node(
          Set.empty,
          beforeRule = judged.record,
          unavailable = Map(left(1).header.hash -> outOfMemory),
          backstop = 10.minutes
        ) { n =>
          n.pool.session.use { sender =>
            for
              _ <- add(sender, left) *> n.pool.admissions.reprocess
              _ <- IO.sleep(10.seconds)
              before <- judged.of(left(1).header)
              // no simulated time passes from here to the check: the admission is not delayed
              _ <- add(sender, right) *> n.pool.admissions.reprocess
              adopted <- n.state.get.map(_.tip)
              _ <- IO.sleep(2.minutes)
              tip <- n.state.get.map(_.tip)
              fatals <- n.fatals.get
              known <- n.pool.invalidBlocks.snapshot
              _ <- IO {
                assertEquals(before, Vector(0, 1, 3, 7).map(_.seconds))
                assertEquals(adopted, right.last.header.point)
                assertEquals(tip, right.last.header.point)
                assertEquals(fatals, Vector.empty)
                assertEquals(known.entries, Map.empty)
              }
            yield ()
          }
        }
      })
    yield ()
  }

  test("many blocks without a verdict: each keeps its own schedule, none is judged more often") {
    for
      chains <- (1 to 4).toList.traverse(seed => ForgedChain.forge(5000 until 5060, seed = seed))
      firsts <- chains.traverse(checked(_, 1))
      blocks = firsts.flatten
      _ <- TestControl.executeEmbed(judgements.flatMap { judged =>
        node(
          Set.empty,
          beforeRule = judged.record,
          unavailable = blocks.map(_.header.hash -> outOfMemory).toMap,
          backstop = 10.minutes
        ) { n =>
          n.pool.session.use { sender =>
            for
              _ <- add(sender, blocks.toVector) *> n.pool.admissions.reprocess
              _ <- IO.sleep(70.seconds)
              attempts <- blocks.traverse(b => judged.of(b.header))
              withheld <- n.pool.noVerdictBlocks.hashes
              tip <- n.state.get.map(_.tip)
              _ <- IO {
                assertEquals(blocks.map(_.header.hash).distinct.size, 4)
                attempts.foreach(a => assertEquals(a, schedule.take(7)))
                assertEquals(withheld, blocks.map(_.header.hash).toSet)
                assertEquals(tip, xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin)
              }
            yield ()
          }
        }
      })
    yield ()
  }

  test("a verdict on a retry: a refusal records the block invalid and blames no peer") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 2)
      target = bodies(1).header
      _ <- TestControl.executeEmbed(judgements.flatMap { judged =>
        node(
          Set.empty,
          beforeRule = judged.record,
          verdict = h =>
            if h.hash != target.hash then IO.pure(Verdict.Valid)
            else
              IO.monotonic.map(at =>
                if at < 5.seconds then Verdict.Unavailable(outOfMemory) else Verdict.Refused
              )
          ,
          backstop = 10.minutes
        ) { n =>
          n.pool.session(Some(e => n.reports.update(_ :+ e))).use { sender =>
            for
              _ <- add(sender, bodies) *> n.pool.admissions.reprocess
              _ <- IO.sleep(2.minutes)
              attempts <- judged.of(target)
              known <- n.pool.invalidBlocks.snapshot
              withheld <- n.pool.noVerdictBlocks.hashes
              reports <- n.reports.get
              tip <- n.state.get.map(_.tip)
              noVerdicts <- reported(n, target)
              _ <- IO {
                assertEquals(attempts, Vector(0, 1, 3, 7).map(_.seconds))
                assertEquals(known.entries.keySet, Set(target.hash))
                assertEquals(withheld, Set.empty[Bytes])
                assertEquals(reports, Vector.empty)
                assertEquals(tip, bodies(0).header.point)
                assertEquals(noVerdicts.map(_._1), Vector(1, 2, 3))
              }
            yield ()
          }
        }
      })
    yield ()
  }

  test("pruning at the immutable cutoff ends a block's schedule") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 2)
      target = bodies(1).header
      _ <- TestControl.executeEmbed(judgements.flatMap { judged =>
        node(
          Set.empty,
          beforeRule = judged.record,
          unavailable = Map(target.hash -> outOfMemory),
          backstop = 10.minutes
        ) { n =>
          n.pool.session.use { sender =>
            for
              _ <- add(sender, bodies) *> n.pool.admissions.reprocess
              _ <- IO.sleep(500.millis)
              _ <- n.pool.invalidBlocks.pruneBefore(target.slot + 1)
              withheld <- n.pool.noVerdictBlocks.hashes
              _ <- IO.sleep(2.minutes)
              attempts <- judged.of(target)
              _ <- IO {
                assertEquals(withheld, Set.empty[Bytes])
                assertEquals(attempts, Vector(Duration.Zero))
              }
            yield ()
          }
        }
      })
    yield ()
  }

  test("the stall report names a withheld block, its attempts and its next attempt") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      bodies <- checked(chain, 2)
      target = bodies(1).header
      _ <- TestControl.executeEmbed(
        node(Set.empty, unavailable = Map(target.hash -> outOfMemory), backstop = 10.minutes) { n =>
          n.pool.session.use { sender =>
            for
              _ <- add(sender, bodies) *> n.pool.admissions.reprocess
              _ <- IO.sleep(1500.millis)
              report <- StallDiagnostics.capture(
                StallDiagnostics.Sources(
                  n.pool.fetchCoordinator,
                  n.pool.admissions,
                  n.pool.noVerdictBlocks
                )
              )
              _ <- IO {
                assertEquals(
                  report.withheld.map(w => (w.hash, w.slot, w.attempts, w.retryIn)),
                  Vector((target.hash, target.slot, 2, Some(1500.millis)))
                )
                val line = summon[log.Traced[log.NodeEvent]]
                  .line(log.NodeEvent.StallState(report, StallDiagnostics.Dump.Off))
                  .fields
                  .toMap
                val named =
                  line.get("withheld").flatMap(_.hcursor.downN(0).get[String]("hash").toOption)
                assertEquals(named, Some(target.hash.hex))
              }
            yield ()
          }
        }
      )
    yield ()
  }
