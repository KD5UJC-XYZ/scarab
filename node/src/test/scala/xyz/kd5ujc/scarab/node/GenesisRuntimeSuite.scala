// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Applicative
import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import cats.syntax.all.*
import java.time.Instant
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, HeaderChain, SelectView}
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.{Fragment, LatestSlot}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Header, HeaderVrf, PeerError, VrfCert}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.{ChainSyncJumping, Gsm}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}

class GenesisRuntimeSuite extends FailsOnUncaught:
  private val geometry = EpochGeometry(Instant.EPOCH, 1000, 100, 2, BigDecimal(1))
  private val empty = Fragment(ChainSync.Point.Origin, Vector.empty)
  private val local =
    ChainSelection.Local(ChainSync.Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
  private def header(i: Int): Header = Header.Shelley(
    Era.Babbage,
    Bytes.empty,
    Bytes.empty,
    Bytes(Vector(i.toByte)),
    i,
    i,
    None,
    Bytes.empty,
    Bytes.empty,
    HeaderVrf.Praos(VrfCert(Bytes.empty, Bytes.empty)),
    0,
    Bytes.empty,
    Header.OpCert(Bytes.empty, 0, 0, Bytes.empty),
    (8, 0),
    Bytes.empty
  )
  private def gate(history: HeaderChain[NodeHeaderState]): HeaderGate[IO] = new HeaderGate[IO]:
    def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]): IO[Unit] = IO.unit
    def counts: IO[HeaderGate.Counts] = IO.pure(HeaderGate.Counts(0, 0, 0))
    override def snapshot(using Applicative[IO]): IO[Option[HeaderChain[NodeHeaderState]]] =
      IO.pure(Some(history))
  private def setup(
      settings: GenesisSettings = GenesisSettings(),
      changed: IO[Unit] = IO.unit,
      selected: ChainSelection.Local = local
  ) =
    for
      governor <- GenesisGovernor.create[IO](IO.pure(GenesisGovernor.View(empty, 2, _ => Some(8))))
      runtime <- GenesisRuntime.create[IO](
        governor,
        settings,
        IO.pure(selected),
        IO.pure(geometry),
        IO.pure(BigInt(0)),
        changed
      )
    yield runtime

  test("LoP expiry returns to its watcher, permitting a later GSM refill to expire again") {
    TestControl.executeEmbed {
      for
        errors <- Ref.of[IO, Vector[PeerError]](Vector.empty)
        runtime <- setup(
          GenesisSettings(
            enableCSJ = false,
            bucketCapacity = 1,
            bucketRate = xyz.kd5ujc.scarab.codec.Rational.one
          )
        )
        _ <- runtime
          .session(1, ChainSync.Point.Origin, e => errors.update(_ :+ e), _ => IO.unit)
          .use { _ =>
            for
              _ <- runtime.governor.setPhase(Gsm.Phase.Syncing)
              _ <- IO.sleep(2.seconds)
              _ <- runtime.governor.setPhase(Gsm.Phase.CaughtUp)
              _ <- runtime.governor.setPhase(Gsm.Phase.Syncing)
              _ <- IO.sleep(2.seconds)
              seen <- errors.get
              _ <- IO(
                assertEquals(
                  seen,
                  Vector.fill(2)(PeerError.Genesis(PeerError.GenesisProblem.LoPExpired))
                )
              )
            yield ()
          }
      yield ()
    }
  }

  test("CSJ role changes do not pause an in-flight batch or wake the fetch scheduler") {
    TestControl.executeEmbed {
      for
        wakes <- Ref.of[IO, Int](0)
        runtime <- setup(changed = wakes.update(_ + 1))
        _ <- runtime.session(1, ChainSync.Point.Origin, _ => IO.unit, _ => IO.unit).use { first =>
          for
            instruction <- first.control.next
            _ <- IO(assertEquals(instruction, ChainSyncJumping.Instruction.RunNormally))
            before <- wakes.get
            _ <- runtime.session(2, ChainSync.Point.Origin, _ => IO.unit, _ => IO.unit).use {
              second =>
                for
                  state <- second.patience.snapshot
                  _ <- IO(assert(!state.paused))
                  _ <- runtime.demoteDynamo(1)
                  after <- wakes.get
                  _ <- IO(assertEquals(after, before))
                yield ()
            }
          yield ()
        }
      yield ()
    }
  }

  test("CSJ pauses while waiting for an instruction, before any intersection is offered") {
    TestControl.executeEmbed {
      setup().flatMap { runtime =>
        (
          runtime.session(1, ChainSync.Point.Origin, _ => IO.unit, _ => IO.unit),
          runtime.session(2, ChainSync.Point.Origin, _ => IO.unit, _ => IO.unit)
        ).tupled.use { (_, second) =>
          second.control.next.background.use { _ =>
            IO.sleep(1.millis) *> second.patience.snapshot.flatMap(s => IO(assert(s.paused)))
          }
        }
      }
    }
  }

  test(
    "rollback announcements do not publish the rolled-back latest slot until validation succeeds"
  ) {
    TestControl.executeEmbed {
      for
        runtime <- setup(GenesisSettings(enableCSJ = false))
        _ <- runtime.session(1, ChainSync.Point.Origin, _ => IO.unit, _ => IO.unit).use { bound =>
          val h = header(3)
          val tip = ChainSync.Tip(h.point, ChainSync.UInt64.saturating(3))
          val history = HeaderChain[NodeHeaderState](
            ChainSync.Point.Origin,
            None,
            Vector(HeaderChain.Entry(h, None))
          )
          val rollback = SyncEvent.RollBackward(ChainSync.Point.Origin, tip)
          for
            _ <- runtime.governor.setPhase(Gsm.Phase.Syncing)
            _ <- bound.control.observed(gate(history), SyncEvent.RollForward(h, tip))
            _ <- bound.received(SyncEvent.RollForward(h, tip))
            before <- runtime.governor.revision
            _ <- bound.received(rollback)
            unvalidated <- runtime.governor.revision
            _ <- IO(assertEquals(unvalidated, before))
            _ <- bound.control.observed(
              gate(HeaderChain(ChainSync.Point.Origin, None, Vector.empty)),
              rollback
            )
            after <- runtime.governor.revision
            _ <- IO(assert(after > before))
          yield ()
        }
      yield ()
    }
  }

  test("GDD disconnect callbacks return, so all losing connections are removed in one evaluation") {
    TestControl.executeEmbed {
      for
        disconnected <- Ref.of[IO, Vector[Long]](Vector.empty)
        runtime <- setup(GenesisSettings(enableCSJ = false))
        _ <- (
          runtime.session(
            1,
            ChainSync.Point.Origin,
            _ => disconnected.update(_ :+ 1L),
            _ => IO.unit
          ),
          runtime.session(
            2,
            ChainSync.Point.Origin,
            _ => disconnected.update(_ :+ 2L),
            _ => IO.unit
          ),
          runtime.governor.session(_ => IO.unit, IO.unit)
        ).tupled.use { (a, b, dense) =>
          val sparse = header(5)
          val tip = ChainSync.Tip(sparse.point, ChainSync.UInt64.saturating(5))
          val history = HeaderChain[NodeHeaderState](
            ChainSync.Point.Origin,
            None,
            Vector(HeaderChain.Entry(sparse, None))
          )
          for
            _ <- a.control.observed(gate(history), SyncEvent.RollForward(sparse, tip))
            _ <- b.control.observed(gate(history), SyncEvent.RollForward(sparse, tip))
            _ <- a.received(SyncEvent.RollForward(header(9), tip))
            _ <- b.received(SyncEvent.RollForward(header(9), tip))
            _ <- dense.publish(
              Some(
                Fragment(
                  ChainSync.Point.Origin,
                  Vector(1, 2, 3).map(i =>
                    ChainSync.Point.Block(ChainSync.UInt64.saturating(i), header(i).hash)
                  )
                )
              ),
              LatestSlot.At(3),
              false
            )
            _ <- runtime.governor.setPhase(Gsm.Phase.Syncing)
            _ <- runtime.governor.run.background.use { _ =>
              IO.sleep(10.millis) *> disconnected.get.flatMap(ids =>
                IO(assertEquals(ids, Vector(1L, 2L)))
              )
            }
          yield ()
        }
      yield ()
    }
  }

  test("a Genesis listener without its shared pool reports a typed local invariant") {
    TestControl.executeEmbed {
      for
        failures <- Ref
          .of[IO, Vector[xyz.kd5ujc.scarab.ledger.GenesisInternalProblem]](Vector.empty)
        acquired <- Ref.of[IO, Boolean](false)
        runtime <- setup()
        _ <- GenesisRuntime
          .controls(Some(runtime), None, 1, _ => IO.unit, problem => failures.update(_ :+ problem))
          .use(_ => acquired.set(true))
          .background
          .use { _ =>
            for
              _ <- IO.sleep(1.millis)
              seen <- failures.get
              opened <- acquired.get
              _ <- IO {
                assertEquals(
                  seen,
                  Vector(xyz.kd5ujc.scarab.ledger.GenesisInternalProblem.MissingSharedBlockPool)
                )
                assert(!opened)
              }
            yield ()
          }
      yield ()
    }
  }

  test("an intersection different from the offered jump is rejected before mutating the gate") {
    TestControl.executeEmbed {
      for
        failures <- Ref.of[IO, Vector[PeerError]](Vector.empty)
        runtime <- setup()
        _ <- runtime
          .session(1, ChainSync.Point.Origin, error => failures.update(_ :+ error), _ => IO.unit)
          .use { bound =>
            val history = HeaderChain[NodeHeaderState](ChainSync.Point.Origin, None, Vector.empty)
            val info = ChainSyncJumping.JumpInfo(
              ChainSync.Point.Origin,
              Vector.empty,
              GenesisPeer.Checkpoint(history, local, ChainSync.Point.Origin)
            )
            bound.control
              .processJump(info, false, Some(header(1).point), gate(history))
              .background
              .use { _ =>
                IO.sleep(1.millis) *> failures.get.flatMap { seen =>
                  IO(
                    assertEquals(
                      seen,
                      Vector(
                        PeerError.Genesis(
                          PeerError.GenesisProblem
                            .InvalidJumpResponse(ChainSync.Point.Origin, Some(header(1).point))
                        )
                      )
                    )
                  )
                }
              }
          }
      yield ()
    }
  }

  test("offered checkpoints preserve the most recent intersection rather than the history anchor") {
    TestControl.executeEmbed {
      val one = header(1)
      val two = header(2)
      val selected =
        local.copy(blocks = Vector(ChainSelection.LocalBlock(one.point, SelectView.of(one))))
      val history = HeaderChain[NodeHeaderState](
        ChainSync.Point.Origin,
        None,
        Vector(HeaderChain.Entry(one, None), HeaderChain.Entry(two, None))
      )
      val tip = ChainSync.Tip(two.point, ChainSync.UInt64.saturating(2))
      setup(GenesisSettings(jumpSlots = 0), selected = selected).flatMap { runtime =>
        (
          runtime.session(1, ChainSync.Point.Origin, _ => IO.unit, _ => IO.unit),
          runtime.session(2, ChainSync.Point.Origin, _ => IO.unit, _ => IO.unit)
        ).tupled.use { (first, second) =>
          for
            _ <- first.control.observed(gate(history), SyncEvent.RollForward(two, tip))
            _ <- first.received(SyncEvent.RollForward(header(3), tip))
            instruction <- second.control.next
            _ <- IO(instruction match
              case ChainSyncJumping.Instruction.Jump(info, false) =>
                assertEquals(info.payload.intersection, one.point)
                assertEquals(info.payload.history, history)
                assertEquals(info.payload.selection, selected)
              case other => fail(s"expected an offered checkpoint, got $other"))
          yield ()
        }
      }
    }
  }

  test("ordinary accepted jumps publish the graft but do not advance the active validator record") {
    TestControl.executeEmbed {
      val one = header(1)
      val history = HeaderChain[NodeHeaderState](
        ChainSync.Point.Origin,
        None,
        Vector(HeaderChain.Entry(one, None))
      )
      val tip = ChainSync.Tip(one.point, ChainSync.UInt64.saturating(1))
      for
        installed <- Ref.of[IO, HeaderChain[NodeHeaderState]](
          HeaderChain(ChainSync.Point.Origin, None, Vector.empty)
        )
        target = new HeaderGate[IO]:
          def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]): IO[Unit] = IO.unit
          def counts: IO[HeaderGate.Counts] = IO.pure(HeaderGate.Counts(0, 0, 0))
          override def snapshot(using Applicative[IO]): IO[Option[HeaderChain[NodeHeaderState]]] =
            installed.get.map(Some(_))
          override def graft(source: HeaderChain[NodeHeaderState], point: ChainSync.Point)(using
              Applicative[IO]
          ): IO[Either[HeaderCheck.GraftError, Unit]] =
            if source.tipPoint == point then installed.set(source).as(Right(()))
            else IO.pure(Left(HeaderCheck.GraftError.MissingPoint(point)))
        runtime <- setup(
          GenesisSettings(
            jumpSlots = 0,
            bucketCapacity = 10,
            bucketRate = xyz.kd5ujc.scarab.codec.Rational.one
          )
        )
        _ <- (
          runtime.session(1, ChainSync.Point.Origin, _ => IO.unit, _ => IO.unit),
          runtime.session(2, ChainSync.Point.Origin, _ => IO.unit, _ => IO.unit)
        ).tupled.use { (first, second) =>
          for
            _ <- runtime.governor.setPhase(Gsm.Phase.Syncing)
            _ <- first.control.observed(gate(history), SyncEvent.RollForward(one, tip))
            _ <- first.received(SyncEvent.RollForward(header(2), tip))
            _ <- IO.sleep(1.second)
            instruction <- second.control.next
            _ <- instruction match
              case ChainSyncJumping.Instruction.Jump(info, false) =>
                second.control.processJump(info, false, Some(info.tip), target).flatMap { result =>
                  IO(
                    assertEquals(
                      result,
                      GenesisPeer.Applied
                        .Grafted(ChainSelection.Candidate(history.base, Vector(one)))
                    )
                  )
                }
              case other => IO(fail(s"expected ordinary jump, got $other"))
            after <- second.patience.snapshot
            _ <- second.patience.validated(1)
            credited <- second.patience.snapshot
            _ <- IO {
              assert(after.paused)
              assertEquals(after.level, xyz.kd5ujc.scarab.codec.Rational(9))
              assertEquals(credited.level, xyz.kd5ujc.scarab.codec.Rational(10))
            }
          yield ()
        }
      yield ()
    }
  }
