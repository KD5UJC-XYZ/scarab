// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.{Fragment, LatestSlot}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}

class GenesisGovernorSuite extends FailsOnUncaught:
  private def point(slot: Int, salt: Int = 0): Point.Block =
    Point.Block(UInt64.saturating(slot), Bytes(Vector(slot.toByte, salt.toByte)))
  private val empty = Fragment(Point.Origin, Vector.empty)
  private val dense = Fragment(Point.Origin, Vector(point(1), point(2), point(3)))

  test(
    "a newly registered candidate stays at Origin while agreement roots it at the current immutable anchor"
  ) {
    TestControl.executeEmbed {
      val current = Fragment(point(7), Vector(point(8), point(9)))
      for
        observed <- Ref.of[IO, Vector[GenesisGovernor.Observation]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          IO.pure(GenesisGovernor.View(current, 2, _ => Some(8))),
          observe = Some(value => observed.update(_ :+ value))
        )
        _ <- governor.session(_ => IO.unit, IO.unit).use { peer =>
          for
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- governor.run.background.use(_ => IO.sleep(1.millis))
            decisions <- observed.get
          yield
            assertEquals(decisions.size, 1)
            decisions.foreach { decision =>
              assertEquals(decision.peers.map(_.session), Vector(peer.id))
              assertEquals(decision.peers.map(_.state.candidate), Vector(empty))
              assertEquals(decision.shared.prefix, Fragment(current.anchor, Vector.empty))
            }
        }
      yield ()
    }
  }

  test(
    "GDD decision order uses peer keys independently of registration and retains complete observations"
  ) {
    TestControl.executeEmbed {
      for
        killed <- Ref.of[IO, Vector[Int]](Vector.empty)
        observed <- Ref.of[IO, Vector[GenesisGovernor.Observation]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          IO.pure(GenesisGovernor.View(empty, 2, _ => Some(8))),
          observe = Some(value => observed.update(_ :+ value))
        )
        _ <- (
          governor.session(
            _ => IO.unit,
            killed.update(_ :+ 20),
            Some(GenesisGovernor.OrderKey(20))
          ),
          governor.session(
            _ => IO.unit,
            killed.update(_ :+ 10),
            Some(GenesisGovernor.OrderKey(10))
          ),
          governor.session(_ => IO.unit, IO.unit, Some(GenesisGovernor.OrderKey(0)))
        ).tupled.use { (first, second, honest) =>
          for
            _ <- first.publish(
              Some(Fragment(Point.Origin, Vector(point(5, 1)))),
              LatestSlot.At(8),
              false
            )
            _ <- second.publish(
              Some(Fragment(Point.Origin, Vector(point(6, 2)))),
              LatestSlot.At(8),
              false
            )
            _ <- honest.publish(Some(dense), LatestSlot.At(3), false)
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- governor.run.background.use(_ => IO.sleep(1.millis))
            sequence <- killed.get
            decisions <- observed.get
          yield
            assertEquals(sequence, Vector(10, 20))
            assertEquals(decisions.size, 1)
            decisions.foreach { decision =>
              assertEquals(decision.genesisWindow, Some(BigInt(8)))
              assertEquals(decision.current, empty)
              assertEquals(decision.k, 2)
              assertEquals(
                decision.peers.map(_.orderKey),
                Vector(0L, 10L, 20L).map(GenesisGovernor.OrderKey(_))
              )
              assertEquals(decision.result.map(_.losingPeers), Some(Vector(second.id, first.id)))
              assertEquals(decision.result.toVector.flatMap(_.bounds).size, 3)
            }
        }
      yield ()
    }
  }

  test("phase changes reset live controls and change the selection cap without a new block") {
    TestControl.executeEmbed {
      for
        phases <- Ref.of[IO, Vector[Gsm.Phase]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          IO.pure(GenesisGovernor.View(empty, 2, _ => Some(8)))
        )
        _ <- governor.session(p => phases.update(_ :+ p), IO.unit).use { peer =>
          for
            _ <- peer.publish(Some(dense), LatestSlot.At(3), false)
            presync <- governor.limit
            _ <- IO(assertEquals(presync, Some(empty)))
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- governor.run.background.use { _ =>
              IO.sleep(1.millis) *> governor.limit
                .flatMap(limit => IO(assertEquals(limit, Some(dense))))
            }
            revision <- governor.revision
            _ <- governor.setPhase(Gsm.Phase.CaughtUp)
            changed <- governor.changed(revision)
            unlimited <- governor.limit
            seen <- phases.get
            _ <- IO {
              assert(changed > revision)
              assertEquals(unlimited, None)
              assertEquals(
                seen,
                Vector(Gsm.Phase.PreSyncing, Gsm.Phase.Syncing, Gsm.Phase.CaughtUp)
              )
            }
          yield ()
        }
      yield ()
    }
  }

  test("GDD uses the announced slot beyond the validated fragment and defers unknown history") {
    TestControl.executeEmbed {
      for
        window <- Ref.of[IO, Option[BigInt]](None)
        killed <- Ref.of[IO, Vector[String]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          window.get.map(w => GenesisGovernor.View(empty, 2, _ => w))
        )
        _ <- (
          governor.session(_ => IO.unit, killed.update(_ :+ "dense")),
          governor.session(_ => IO.unit, killed.update(_ :+ "sparse"))
        ).tupled.use { (a, b) =>
          for
            _ <- a.publish(Some(dense), LatestSlot.At(3), false)
            sparse = Fragment(Point.Origin, Vector(point(5, 1)))
            _ <- b.publish(Some(sparse), LatestSlot.At(8), false)
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- governor.run.background.use { _ =>
              for
                _ <- IO.sleep(10.millis)
                before <- killed.get
                _ <- IO(assertEquals(before, Vector.empty))
                _ <- window.set(Some(8))
                _ <- b.publish(Some(sparse), LatestSlot.At(9), false)
                _ <- IO.sleep(1.second)
                after <- killed.get
                _ <- IO(assertEquals(after, Vector("sparse")))
              yield ()
            }
          yield ()
        }
      yield ()
    }
  }

  test("a newly joined peer pins agreement and removal releases it") {
    TestControl.executeEmbed {
      for
        governor <- GenesisGovernor.create[IO](
          IO.pure(GenesisGovernor.View(empty, 2, _ => Some(8)))
        )
        _ <- governor.session(_ => IO.unit, IO.unit).use { a =>
          for
            _ <- a.publish(Some(dense), LatestSlot.At(3), false)
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- governor.run.background.use { _ =>
              for
                _ <- IO.sleep(1.millis)
                before <- governor.limit
                _ <- IO(assertEquals(before, Some(dense)))
                _ <- governor.session(_ => IO.unit, IO.unit).use { _ =>
                  IO.sleep(1.second) *> governor.limit
                    .flatMap(limit => IO(assertEquals(limit, Some(empty))))
                }
                _ <- IO.sleep(1.second)
                after <- governor.limit
                _ <- IO(assertEquals(after, Some(dense)))
              yield ()
            }
          yield ()
        }
      yield ()
    }
  }

  test(
    "candidate-only publication does not alter the native watcher fingerprint or selection revision"
  ) {
    TestControl.executeEmbed {
      for
        governor <- GenesisGovernor.create[IO](
          IO.pure(GenesisGovernor.View(empty, 2, _ => Some(8)))
        )
        _ <- governor.session(_ => IO.unit, IO.unit).use { peer =>
          for
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- peer.publish(Some(empty), LatestSlot.At(3), false)
            input <- governor.revision
            selection <- governor.selectionRevision
            _ <- peer.publish(Some(dense), LatestSlot.At(3), false)
            sameInput <- governor.revision
            sameSelection <- governor.selectionRevision
            _ <- peer.publish(Some(dense), LatestSlot.At(3), true)
            idlingInput <- governor.revision
          yield
            assertEquals(sameInput, input)
            assertEquals(sameSelection, selection)
            assert(idlingInput > input)
        }
      yield ()
    }
  }

  test("selection changes wake once for the cap without waking GDD on its own output") {
    TestControl.executeEmbed {
      for
        reads <- Ref.of[IO, Int](0)
        governor <- GenesisGovernor.create[IO](
          reads.update(_ + 1).as(GenesisGovernor.View(empty, 2, _ => Some(8)))
        )
        _ <- governor.session(_ => IO.unit, IO.unit).use { peer =>
          for
            _ <- peer.publish(Some(dense), LatestSlot.At(3), false)
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            input <- governor.revision
            selection <- governor.selectionRevision
            _ <- governor.run.background.use { _ =>
              for
                wake <- governor.selectionChanged(selection)
                _ <- IO(assert(wake > selection))
                _ <- IO.sleep(3.seconds)
                count <- reads.get
                after <- governor.revision
              yield
                assertEquals(count, 1)
                assertEquals(after, input)
            }
          yield ()
        }
      yield ()
    }
  }

  test("a peer change during a ChainDB view read retries the snapshot before publishing a cap") {
    TestControl.executeEmbed {
      for
        began <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        reads <- Ref.of[IO, Int](0)
        governor <- GenesisGovernor.create[IO](reads.getAndUpdate(_ + 1).flatMap { n =>
          (began.complete(()).void *> release.get)
            .whenA(n == 0)
            .as(GenesisGovernor.View(empty, 2, _ => Some(8)))
        })
        _ <- governor.session(_ => IO.unit, IO.unit).use { peer =>
          for
            _ <- peer.publish(Some(dense), LatestSlot.At(3), false)
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- governor.run.background.use { _ =>
              for
                _ <- began.get
                _ <- governor.session(_ => IO.unit, IO.unit).use { _ =>
                  release.complete(()).void *> IO.sleep(1.millis) *> governor.limit.flatMap { cap =>
                    IO(assertEquals(cap, Some(empty)))
                  }
                }
              yield ()
            }
          yield ()
        }
      yield ()
    }
  }

  test("phase publication follows every live bucket reset and survives cancellation") {
    TestControl.executeEmbed {
      for
        began <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        governor <- GenesisGovernor.create[IO](
          IO.pure(GenesisGovernor.View(empty, 2, _ => Some(8)))
        )
        _ <- governor
          .session(
            p => (began.complete(()).void *> release.get).whenA(p == Gsm.Phase.Syncing),
            IO.unit
          )
          .use { _ =>
            for
              setting <- governor.setPhase(Gsm.Phase.Syncing).start
              _ <- began.get
              before <- governor.phase
              cancelling <- setting.cancel.start
              _ <- release.complete(())
              _ <- cancelling.joinWithNever
              after <- governor.phase
            yield
              assertEquals(before, Gsm.Phase.PreSyncing)
              assertEquals(after, Gsm.Phase.Syncing)
          }
      yield ()
    }
  }

  test(
    "duration parsing preserves every integer picosecond without decimal multiplication rounding"
  ) {
    val json = io.circe.parser
      .parse(
        """{"BlockFetchGracePeriod":123456789012345678901234567890123456789.1234567891239,"GDDRateLimit":-0.0000000000001}"""
      )
      .fold(e => fail(e.message), identity)
    val settings = GenesisSettings.parse(Some(json)).fold(e => fail(e.message), identity)
    assertEquals(
      settings.blockFetchGracePicos,
      BigInt("123456789012345678901234567890123456789123456789123")
    )
    assertEquals(settings.gddIntervalPicos, BigInt(-1))
  }

  test("the GDD rate limit includes time spent executing disconnections") {
    TestControl.executeEmbed {
      for
        killed <- Ref.of[IO, Vector[FiniteDuration]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          IO.pure(GenesisGovernor.View(empty, 2, _ => Some(8)))
        )
        _ <- (
          governor.session(_ => IO.unit, IO.unit),
          governor.session(
            _ => IO.unit,
            IO.monotonic.flatMap(t => killed.update(_ :+ t)) *> IO.sleep(200.millis)
          )
        ).tupled.use { (a, b) =>
          val sparse = Fragment(Point.Origin, Vector(point(5, 1)))
          for
            _ <- a.publish(Some(dense), LatestSlot.At(3), false)
            _ <- b.publish(Some(sparse), LatestSlot.At(8), false)
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- governor.run.background.use { _ =>
              for
                _ <- IO.sleep(500.millis)
                _ <- b.publish(Some(sparse), LatestSlot.At(9), false)
                _ <- IO.sleep(600.millis)
                times <- killed.get
              yield assertEquals(times, Vector(Duration.Zero, 1.second))
            }
          yield ()
        }
      yield ()
    }
  }

  test("GSM phase changes retain the stored syncing cap exactly as the reference") {
    TestControl.executeEmbed {
      for
        governor <- GenesisGovernor.create[IO](
          IO.pure(GenesisGovernor.View(empty, 2, _ => Some(8)))
        )
        _ <- governor.session(_ => IO.unit, IO.unit).use { peer =>
          for
            _ <- peer.publish(Some(dense), LatestSlot.At(3), false)
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- governor.run.background.use(_ => IO.sleep(1.millis))
            _ <- governor.setPhase(Gsm.Phase.CaughtUp)
            caughtUp <- governor.limit
            _ <- governor.setPhase(Gsm.Phase.PreSyncing)
            conservative <- governor.limit
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            retained <- governor.limit
          yield
            assertEquals(caughtUp, None)
            assertEquals(conservative, Some(empty))
            assertEquals(retained, Some(dense))
        }
      yield ()
    }
  }

  test("GDD uses concrete remote TCP endpoints rather than connection registration order") {
    import com.comcast.ip4s.*
    import xyz.kd5ujc.scarab.network.ConnectionId
    def tcp(remote: Port): GenesisGovernor.OrderKey =
      GenesisGovernor.OrderKey.Tcp(
        ConnectionId
          .fromSocketAddresses(
            SocketAddress(ip"127.0.0.1", port"4000"),
            SocketAddress(ip"127.0.0.1", remote)
          )
          .fold(error => fail(error.message), identity)
      )
    TestControl.executeEmbed {
      for
        killed <- Ref.of[IO, Vector[Int]](Vector.empty)
        observed <- Ref.of[IO, Vector[GenesisGovernor.Observation]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          IO.pure(GenesisGovernor.View(empty, 2, _ => Some(8))),
          observe = Some(value => observed.update(_ :+ value))
        )
        _ <- (
          governor.session(_ => IO.unit, killed.update(_ :+ 20), Some(tcp(port"3020"))),
          governor.session(_ => IO.unit, killed.update(_ :+ 10), Some(tcp(port"3010"))),
          governor.session(_ => IO.unit, IO.unit, Some(tcp(port"3000")))
        ).tupled.use { (first, second, honest) =>
          for
            _ <- first.publish(
              Some(Fragment(Point.Origin, Vector(point(5, 1)))),
              LatestSlot.At(8),
              false
            )
            _ <- second.publish(
              Some(Fragment(Point.Origin, Vector(point(6, 2)))),
              LatestSlot.At(8),
              false
            )
            _ <- honest.publish(Some(dense), LatestSlot.At(3), false)
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- governor.run.background.use(_ => IO.sleep(1.millis))
            sequence <- killed.get
            decisions <- observed.get
          yield
            assertEquals(sequence, Vector(10, 20))
            assertEquals(decisions.size, 1)
            assertEquals(
              decisions.head.peers.map(_.orderKey),
              Vector(tcp(port"3000"), tcp(port"3010"), tcp(port"3020"))
            )
        }
      yield ()
    }
  }

  test("coalesced fingerprint reads retain the same peer key across hot reactivation") {
    TestControl.executeEmbed {
      for
        governor <- GenesisGovernor.create[IO](
          IO.pure(GenesisGovernor.View(empty, 2, _ => Some(8)))
        )
        _ <- governor.setPhase(Gsm.Phase.Syncing)
        before <- governor.session(_ => IO.unit, IO.unit, Some(GenesisGovernor.OrderKey(20))).use {
          peer =>
            peer.publish(Some(dense), LatestSlot.At(3), false) *> governor.inputFingerprint
        }
        after <- governor.session(_ => IO.unit, IO.unit, Some(GenesisGovernor.OrderKey(20))).use {
          peer =>
            peer.publish(Some(dense), LatestSlot.At(3), false) *> governor.inputFingerprint
        }
      yield assertEquals(after, before)
    }
  }
