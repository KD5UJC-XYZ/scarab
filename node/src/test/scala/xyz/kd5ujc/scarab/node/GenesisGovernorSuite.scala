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
          GenesisGovernor.View(current, 2, _ => Some(8)),
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
          GenesisGovernor.View(empty, 2, _ => Some(8)),
          observe = Some(value => observed.update(_ :+ value))
        )
        _ <- (
          governor.session(
            _ => IO.unit,
            killed.update(_ :+ 20),
            Some(PeerOrder(20))
          ),
          governor.session(
            _ => IO.unit,
            killed.update(_ :+ 10),
            Some(PeerOrder(10))
          ),
          governor.session(_ => IO.unit, IO.unit, Some(PeerOrder(0)))
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
                Vector(0L, 10L, 20L).map(PeerOrder(_))
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
          GenesisGovernor.View(empty, 2, _ => Some(8))
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
        killed <- Ref.of[IO, Vector[String]](Vector.empty)
        governor <- GenesisGovernor.create[IO](GenesisGovernor.View(empty, 2, _ => None))
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
                _ <- governor.select(GenesisGovernor.View(empty, 2, _ => Some(8)))
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
          GenesisGovernor.View(empty, 2, _ => Some(8))
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
          GenesisGovernor.View(empty, 2, _ => Some(8))
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
        evaluations <- Ref.of[IO, Int](0)
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => Some(8)),
          observe = Some(_ => evaluations.update(_ + 1))
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
                count <- evaluations.get
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

  // The reference reads one snapshot and commits its evaluation (consensus 82ecba329,
  // Genesis/Governor.hs:129-188): a connection registered after the snapshot is evaluated at the
  // next evaluation, one rate-limit interval later.
  test("a connection registered after an evaluation's snapshot joins the next evaluation") {
    TestControl.executeEmbed {
      for
        during <- Ref.of[IO, IO[Unit]](IO.unit)
        caps <- Ref.of[IO, Vector[(FiniteDuration, Fragment)]](Vector.empty)
        second <- Deferred[IO, Unit]
        joined <- Deferred[IO, IO[Unit]]
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => Some(8)),
          observe = Some(value =>
            during.getAndSet(IO.unit).flatten *> IO.monotonic
              .flatMap(now => caps.updateAndGet(_ :+ (now -> value.shared.prefix)))
              .flatMap(seen => second.complete(()).void.whenA(seen.size == 2))
          )
        )
        result <- governor.session(_ => IO.unit, IO.unit).use { peer =>
          for
            _ <- peer.publish(Some(dense), LatestSlot.At(3), false)
            // a connection registers while the first evaluation runs, after its snapshot
            _ <- during.set(
              governor
                .session(_ => IO.unit, IO.unit)
                .allocated
                .flatMap((_, leave) => joined.complete(leave).void)
            )
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            seen <- governor.run.background.use(_ => second.get *> caps.get)
            _ <- joined.get.flatten
          yield seen
        }
      yield assertEquals(result, Vector(Duration.Zero -> dense, 1.second -> empty))
    }
  }

  test("phase publication follows every live bucket reset and survives cancellation") {
    TestControl.executeEmbed {
      for
        began <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => Some(8))
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
          GenesisGovernor.View(empty, 2, _ => Some(8))
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
          GenesisGovernor.View(empty, 2, _ => Some(8))
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

  test(
    "an evaluation with a known window traces TraceGDDDebug before the kills and TraceGDDDisconnected after"
  ) {
    TestControl.executeEmbed {
      for
        // the traces and the kills, in the order they happen
        happened <- Ref.of[IO, Vector[Either[GenesisTrace, Int]]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => Some(8)),
          events = Some(event => happened.update(_ :+ Left(event)))
        )
        _ <- (
          governor.session(_ => IO.unit, happened.update(_ :+ Right(20)), Some(PeerOrder(20))),
          governor.session(_ => IO.unit, happened.update(_ :+ Right(10)), Some(PeerOrder(10))),
          governor.session(_ => IO.unit, IO.unit, Some(PeerOrder(0)))
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
            sequence <- happened.get
          yield assertEquals(
            sequence,
            Vector(
              // Governor.hs:269-283 (consensus 82ecba329): the losers in the order densityDisconnect
              // gives them, the shared prefix's tip (none: the candidates part at Origin) and the
              // window; then each kill; then the losers again
              Left(GenesisTrace.GddDebug(Vector(PeerOrder(10), PeerOrder(20)), Point.Origin, 8)),
              Right(10),
              Right(20),
              Left(
                GenesisTrace.GddDisconnected(
                  cats.data.NonEmptyVector.of(PeerOrder(10), PeerOrder(20))
                )
              )
            )
          )
        }
      yield ()
    }
  }

  test("an evaluation that disconnects nobody traces only TraceGDDDebug, with the LoE head") {
    TestControl.executeEmbed {
      for
        traced <- Ref.of[IO, Vector[GenesisTrace]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => Some(8)),
          events = Some(event => traced.update(_ :+ event))
        )
        _ <- (
          governor.session(_ => IO.unit, IO.unit, Some(PeerOrder(1))),
          governor.session(_ => IO.unit, IO.unit, Some(PeerOrder(2)))
        ).tupled.use { (a, b) =>
          for
            _ <- a.publish(Some(dense), LatestSlot.At(3), false)
            _ <- b.publish(
              Some(Fragment(Point.Origin, Vector(point(1), point(2)))),
              LatestSlot.At(3),
              false
            )
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            _ <- governor.run.background.use(_ => IO.sleep(1.millis))
            all <- traced.get
          yield assertEquals(all, Vector(GenesisTrace.GddDebug(Vector.empty, point(2), 8)))
        }
      yield ()
    }
  }

  test("an evaluation whose Genesis window is unknown traces nothing") {
    TestControl.executeEmbed {
      for
        traced <- Ref.of[IO, Vector[GenesisTrace]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => None),
          events = Some(event => traced.update(_ :+ event))
        )
        _ <- governor.session(_ => IO.unit, IO.unit, Some(PeerOrder(1))).use { a =>
          a.publish(Some(dense), LatestSlot.At(3), false) *> governor.setPhase(Gsm.Phase.Syncing) *>
            governor.run.background.use(_ => IO.sleep(1.millis))
        }
        all <- traced.get
      yield assertEquals(all, Vector.empty)
    }
  }

  test("GDD uses concrete remote TCP endpoints rather than connection registration order") {
    import com.comcast.ip4s.*
    import xyz.kd5ujc.scarab.network.ConnectionId
    def tcp(remote: Port): PeerOrder =
      PeerOrder.Tcp(
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
          GenesisGovernor.View(empty, 2, _ => Some(8)),
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
          GenesisGovernor.View(empty, 2, _ => Some(8))
        )
        _ <- governor.setPhase(Gsm.Phase.Syncing)
        before <- governor.session(_ => IO.unit, IO.unit, Some(PeerOrder(20))).use { peer =>
          peer.publish(Some(dense), LatestSlot.At(3), false) *> governor.inputFingerprint
        }
        after <- governor.session(_ => IO.unit, IO.unit, Some(PeerOrder(20))).use { peer =>
          peer.publish(Some(dense), LatestSlot.At(3), false) *> governor.inputFingerprint
        }
      yield assertEquals(after, before)
    }
  }

  private val honest = Fragment(Point.Origin, Vector(1, 2, 3, 4, 5, 9).map(point(_)))
  private val sparse = Fragment(Point.Origin, Vector(point(1), point(2), point(7, 1)))
  private val agreed = Fragment(Point.Origin, Vector(point(1), point(2)))

  // A ChainSync client writes its peer's state on nearly every message, also when nothing changes.
  // The reference evaluates one snapshot and commits the result whatever is written afterwards
  // (consensus 82ecba329, Genesis/Governor.hs:129-188): nothing written after the snapshot discards
  // or repeats the evaluation.
  test("an unchanged republication after an evaluation's snapshot does not discard it") {
    TestControl.executeEmbed {
      for
        during <- Ref.of[IO, IO[Unit]](IO.unit)
        evaluations <- Ref.of[IO, Int](0)
        killed <- Ref.of[IO, Vector[String]](Vector.empty)
        disconnected <- Deferred[IO, Unit]
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => Some(8)),
          observe = Some(_ => evaluations.update(_ + 1) *> during.getAndSet(IO.unit).flatten)
        )
        result <- (
          governor.session(
            _ => IO.unit,
            killed.update(_ :+ "sparse") *> disconnected.complete(()).void
          ),
          governor.session(_ => IO.unit, killed.update(_ :+ "honest"))
        ).tupled.use { (thin, full) =>
          val republish = thin.publish(Some(sparse), LatestSlot.At(12), false)
          for
            _ <- republish
            _ <- full.publish(Some(honest), LatestSlot.At(12), false)
            // after the first evaluation's snapshot, the sparse peer republishes its unchanged
            // state 1,000 times
            _ <- during.set(republish.replicateA_(1000))
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            before <- governor.limitAt(empty)
            selection <- governor.selectionRevision
            after <- governor.run.background.use { _ =>
              (
                governor.selectionChanged(selection) *> governor.limitAt(empty),
                disconnected.get *> killed.get,
                evaluations.get
              ).tupled
            }
          yield (before, after)
        }
        (before, (after, losers, count)) = result
      yield
        assertEquals(count, 1, "one evaluation, committed from its snapshot")
        assertEquals(losers, Vector("sparse"))
        assertEquals(before, Some(empty))
        assertEquals(after, Some(agreed))
    }
  }

  // Headers arriving keep the watcher's fingerprint moving. Every evaluation still lands, and the
  // next one waits out the rate limit (consensus 82ecba329, Genesis/Governor.hs:185-188); a sparse
  // peer that joins meanwhile is disconnected at the next evaluation, and the LoE fragment follows
  // the shared prefix.
  test(
    "evaluations land once per interval while a candidate grows during each, and a joining sparse peer is disconnected at the next"
  ) {
    val long = Fragment(Point.Origin, (1 to 20).toVector.map(point(_)))
    def growing(tip: Int): Fragment = Fragment(Point.Origin, (1 to tip).toVector.map(point(_)))
    val evaluations = 7
    TestControl.executeEmbed {
      for
        during <- Ref.of[IO, Int => IO[Unit]](_ => IO.unit)
        seen <- Ref.of[IO, Vector[(FiniteDuration, Point)]](Vector.empty)
        committed <- Ref.of[IO, Vector[Option[Point]]](Vector.empty)
        first <- Deferred[IO, Unit]
        all <- Deferred[IO, Unit]
        killed <- Ref.of[IO, Vector[(String, FiniteDuration)]](Vector.empty)
        dropped <- Deferred[IO, Unit]
        record = (name: String) => IO.monotonic.flatMap(now => killed.update(_ :+ (name -> now)))
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => Some(8)),
          observe = Some(value =>
            IO.monotonic
              .flatMap(now => seen.updateAndGet(_ :+ (now -> value.shared.prefix.tip)))
              .flatMap(so =>
                during.get.flatMap(_(so.size)) *> first.complete(()) *>
                  all.complete(()).void.whenA(so.size == evaluations)
              )
          )
        )
        result <- (
          governor.session(_ => IO.unit, record("long")),
          governor.session(_ => IO.unit, record("growing"))
        ).tupled.use { (full, moving) =>
          for
            _ <- full.publish(Some(long), LatestSlot.At(20), false)
            _ <- moving.publish(Some(growing(2)), LatestSlot.At(2), false)
            // after the snapshot of each of the first six evaluations, the cap it finds is the
            // one the evaluation before committed, and the growing peer's chain gains a header
            _ <- during.set(n =>
              governor.limitAt(empty).flatMap(cap => committed.update(_ :+ cap.map(_.tip))) *>
                moving.publish(Some(growing(2 + n)), LatestSlot.At(2 + n), false).whenA(n <= 6)
            )
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            out <- governor.run.background.use { _ =>
              for
                _ <- first.get
                // a sparse peer joins after the first evaluation; disconnected, its session ends
                joined <- governor
                  .session(_ => IO.unit, record("sparse") *> dropped.complete(()).void)
                  .allocated
                (thin, leave) = joined
                _ <- thin.publish(Some(sparse), LatestSlot.At(30), false)
                _ <- dropped.get *> leave
                _ <- all.get
                evaluated <- seen.get
                caps <- committed.get
                losers <- killed.get
              yield (evaluated, caps, losers)
            }
          yield out
        }
        (evaluated, caps, losers) = result
      yield
        assertEquals(
          evaluated,
          Vector(0 -> 2, 1 -> 2, 2 -> 4, 3 -> 5, 4 -> 6, 5 -> 7, 6 -> 8).map((at, tip) =>
            at.seconds -> point(tip)
          )
        )
        assertEquals(
          caps,
          Some(Point.Origin) +: Vector(2, 2, 4, 5, 6, 7).map(tip => Some(point(tip)))
        )
        assertEquals(losers, Vector("sparse" -> 1.second))
    }
  }

  // The reference kills a loser through the handle in its snapshot, a `throwTo` to a thread that
  // has ended if the session has (consensus 82ecba329, Genesis/Governor.hs:281-283,
  // MiniProtocol/ChainSync/Client.hs:423): a session that ended after the snapshot is not
  // disconnected, and the evaluation is committed as computed.
  test(
    "a losing session that ended after the snapshot is not disconnected; the evaluation still lands"
  ) {
    TestControl.executeEmbed {
      for
        during <- Ref.of[IO, IO[Unit]](IO.unit)
        landed <- Deferred[IO, GenesisGovernor.Observation]
        killed <- Ref.of[IO, Vector[String]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => Some(8)),
          observe = Some(value => during.getAndSet(IO.unit).flatten *> landed.complete(value).void)
        )
        result <- governor.session(_ => IO.unit, killed.update(_ :+ "honest")).use { full =>
          for
            joined <- governor.session(_ => IO.unit, killed.update(_ :+ "sparse")).allocated
            (thin, leave) = joined
            _ <- full.publish(Some(honest), LatestSlot.At(12), false)
            _ <- thin.publish(Some(sparse), LatestSlot.At(12), false)
            // the sparse session ends after the first evaluation's snapshot
            _ <- during.set(leave)
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            selection <- governor.selectionRevision
            out <- governor.run.background.use { _ =>
              for
                observation <- landed.get
                first <- governor.selectionChanged(selection)
                cap <- governor.limitAt(empty)
                at <- IO.monotonic
                _ <- governor.selectionChanged(first)
                next <- governor.limitAt(empty)
                later <- IO.monotonic
                losers <- killed.get
              yield (observation.result.map(_.losingPeers), thin.id, cap, at, next, later, losers)
            }
          yield out
        }
        (losing, gone, cap, at, next, later, losers) = result
      yield
        assertEquals(losing, Some(Vector(gone)))
        assertEquals(losers, Vector.empty)
        assertEquals(cap -> at, Some(agreed) -> Duration.Zero)
        assertEquals(next -> later, Some(honest) -> 1.second)
    }
  }

  // The reference's kill handle is a `throwTo` to the session's own ChainSync thread (consensus
  // 82ecba329, MiniProtocol/ChainSync/Client.hs:423 and 449), which does nothing once that thread
  // has ended, whenever the kill is delivered. A loser whose session ends while an earlier loser
  // of the same evaluation is being disconnected is not told.
  test("a loser whose session ends while an earlier loser is disconnected is not disconnected") {
    val other = Fragment(Point.Origin, Vector(point(1), point(2), point(8, 2)))
    TestControl.executeEmbed {
      for
        killed <- Ref.of[IO, Vector[String]](Vector.empty)
        losing <- Ref.of[IO, Vector[Vector[Long]]](Vector.empty)
        second <- Deferred[IO, Unit]
        leaveLater <- Deferred[IO, IO[Unit]]
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => Some(8)),
          observe = Some(value =>
            losing
              .updateAndGet(_ :+ value.result.toVector.flatMap(_.losingPeers))
              .flatMap(seen => second.complete(()).void.whenA(seen.size == 2))
          )
        )
        // disconnecting the earlier loser ends the later loser's session
        earlier <- governor
          .session(
            _ => IO.unit,
            killed.update(_ :+ "earlier") *> leaveLater.get.flatten,
            Some(PeerOrder(1))
          )
          .allocated
        later <- governor
          .session(_ => IO.unit, killed.update(_ :+ "later"), Some(PeerOrder(2)))
          .allocated
        _ <- leaveLater.complete(later._2)
        result <- governor.session(_ => IO.unit, IO.unit, Some(PeerOrder(3))).use { full =>
          for
            _ <- earlier._1.publish(Some(sparse), LatestSlot.At(12), false)
            _ <- later._1.publish(Some(other), LatestSlot.At(12), false)
            _ <- full.publish(Some(honest), LatestSlot.At(12), false)
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            out <- governor.run.background.use(_ => second.get *> (losing.get, killed.get).tupled)
          yield out
        }
        _ <- earlier._2
        (evaluations, losers) = result
      yield
        assertEquals(
          evaluations,
          Vector(Vector(earlier._1.id, later._1.id), Vector(earlier._1.id)),
          "both lose the first evaluation; the second runs without the ended session"
        )
        assertEquals(losers, Vector("earlier", "earlier"))
    }
  }

  // Codex's case: candidates superseded before the immutable anchor advanced must not be evaluated
  // against that anchor. The reference reads the anchor and the candidates in one transaction
  // (consensus 82ecba329, Genesis/Governor.hs:136-148); here the selection is handed to the
  // governor's own state, so one read holds both.
  test(
    "a selection handed over after an evaluation's snapshot is never paired with that snapshot"
  ) {
    val a = Fragment(
      Point.Origin,
      Vector(point(1), point(2), point(4, 1), point(9, 1), point(10, 1), point(11, 1))
    )
    val b = Fragment(Point.Origin, Vector(point(1), point(2)) ++ (3 to 11).map(point(_, 2)))
    val advanced =
      GenesisGovernor.View(
        Fragment(point(9, 1), Vector(point(10, 1), point(11, 1))),
        2,
        _ => Some(8)
      )
    TestControl.executeEmbed {
      for
        during <- Ref.of[IO, IO[Unit]](IO.unit)
        seen <- Ref.of[IO, Vector[(Point, Vector[Long])]](Vector.empty)
        second <- Deferred[IO, Unit]
        killed <- Ref.of[IO, Vector[String]](Vector.empty)
        governor <- GenesisGovernor.create[IO](
          GenesisGovernor.View(empty, 2, _ => Some(8)),
          observe = Some(value =>
            during.getAndSet(IO.unit).flatten *> seen
              .updateAndGet(
                _ :+ (value.current.anchor -> value.result.toVector.flatMap(_.losingPeers))
              )
              .flatMap(so => second.complete(()).void.whenA(so.size == 2))
          )
        )
        result <- (
          governor.session(_ => IO.unit, killed.update(_ :+ "A"), Some(PeerOrder(1))),
          governor.session(_ => IO.unit, killed.update(_ :+ "B"), Some(PeerOrder(2)))
        ).tupled.use { (pa, pb) =>
          for
            _ <- pa.publish(Some(a), LatestSlot.At(20), false)
            _ <- pb.publish(Some(b), LatestSlot.At(20), false)
            // after the first evaluation's snapshot: B switches to A's chain, then A's blocks make
            // 9a immutable; A then idles, which makes the next evaluation due
            _ <- during.set(
              pb.publish(Some(a), LatestSlot.At(20), false) *> governor.select(advanced) *>
                pa.publish(Some(a), LatestSlot.At(20), true)
            )
            _ <- governor.setPhase(Gsm.Phase.Syncing)
            out <- governor.run.background.use(_ => second.get *> (seen.get, killed.get).tupled)
          yield (out, pa.id)
        }
        ((evaluations, losers), first) = result
      yield
        assertEquals(
          evaluations,
          Vector(Point.Origin -> Vector(first), point(9, 1) -> Vector.empty),
          "the first evaluation is the coherent initial state; the second the coherent final one"
        )
        assertEquals(losers, Vector("A"))
    }
  }

  // `blockUntilChanged` (consensus 82ecba329, Util/STM.hs:47-56) compares the fingerprint with the
  // one last evaluated: a peer whose latest slot moves and comes back while its chain changes does
  // not start an evaluation until the fingerprint really differs.
  test(
    "a fingerprint that changes and comes back to the evaluated one does not start an evaluation"
  ) {
    val long = Fragment(Point.Origin, (1 to 5).toVector.map(point(_)))
    val short = Fragment(Point.Origin, Vector(point(1), point(3, 1)))
    for
      evaluations <- Ref.of[IO, Vector[(FiniteDuration, Vector[Long])]](Vector.empty)
      killed <- Ref.of[IO, Vector[String]](Vector.empty)
      handles <- Deferred[IO, (GenesisGovernor.Session[IO], GenesisGovernor.Session[IO])]
      control <- TestControl.execute(
        GenesisGovernor
          .create[IO](
            GenesisGovernor.View(empty, 2, _ => Some(8)),
            observe = Some(value =>
              IO.monotonic.flatMap(now =>
                evaluations.update(_ :+ (now -> value.result.toVector.flatMap(_.losingPeers)))
              )
            )
          )
          .flatMap { governor =>
            (
              governor.session(_ => IO.unit, killed.update(_ :+ "p")),
              governor.session(_ => IO.unit, killed.update(_ :+ "q"))
            ).tupled.use { (p, q) =>
              p.publish(Some(long), LatestSlot.At(20), false) *>
                q.publish(Some(long), LatestSlot.At(20), false) *>
                governor.setPhase(Gsm.Phase.Syncing) *> handles.complete(p -> q) *> governor.run
            }
          }
      )
      _ <- control.tick
      sessions <- handles.get
      (p, q) = sessions
      first <- evaluations.get
      // during the rate-limit wait, p's chain becomes a sparse one; its latest slot moves and
      // comes back
      _ <- p.publish(Some(short), LatestSlot.At(21), false)
      _ <- p.publish(Some(short), LatestSlot.At(20), false)
      // the rate-limit wait ends at 1 s; nothing is scheduled after it, so the clock stops there
      _ <- control.tickFor(10.seconds)
      quiet <- evaluations.get
      // a real change of the fingerprint
      _ <- q.publish(Some(long), LatestSlot.At(21), false)
      _ <- control.tick
      after <- evaluations.get
      losers <- killed.get
    yield
      assertEquals(first, Vector(Duration.Zero -> Vector.empty))
      assertEquals(quiet, first, "no evaluation while the fingerprint is the one evaluated")
      assertEquals(after, first :+ (1.second -> Vector(p.id)))
      assertEquals(losers, Vector("p"))
  }
