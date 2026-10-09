// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.*
import lab.cbor.Bytes
import ControllerReducer.*
import ControllerJournalCodec.*
import CombinedLocalV2 as Combined

class CombinedLocalV2Suite extends munit.FunSuite:
  def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  def id(n: Int): Id = Id(f"$n%064x")
  def read(p: Path): Bytes =
    val in = Files.newInputStream(p)
    try
      val a = in.readNBytes(LocalDerivedCheckpoint.MaxBytes + 1)
      assert(a.length <= LocalDerivedCheckpoint.MaxBytes); Bytes.fromArray(a)
    finally in.close()
  def directory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("combined-v2-")))(p =>
      IO.blocking {
        val walk = Files.walk(p)
        try walk.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.delete(_))
        finally walk.close()
      }
    )
  def binding(p: Path, context: Id) = Binding(
    p.resolve("controller").toString,
    p.resolve("checkpoint").toString,
    Store(id(7), context)
  )
  def image(b: Binding): Image = get(decode(read(Path.of(b.root).resolve("controller.bin")), b))
  def reseal(p: Array[Byte]): Bytes =
    val payload = Bytes.fromArray(p);
    Bytes(payload.value ++ ClusterHeaderObservation.sha256(payload).value)
  def entered(latch: CountDownLatch): IO[Unit] =
    def loop: IO[Unit] = IO(latch.getCount == 0).flatMap {
      case true  => IO.unit
      case false => IO.sleep(2.millis) *> IO.defer(loop)
    }
    loop.timeout(10.seconds)

  test("versioned launch policy rejects legacy and different-policy journals without retagging") {
    Vector("controller-journal-v1", "in-process-resource-v0")
      .traverse_ { replacement =>
        directory.use { p =>
          val b = binding(p, id(2))
          LocalControllerJournal
            .resource[IO](b, LocalControllerJournal.Mode.Create, id(90))
            .use(_ => IO.unit) *>
            IO.blocking {
              val file = Path.of(b.root).resolve("controller.bin")
              val bytes = read(file).toArray.dropRight(32)
              val needle = (if replacement.startsWith("controller") then "controller-journal-v2"
                            else LaunchPolicy).getBytes("UTF-8")
              val offset =
                bytes.sliding(needle.length).indexWhere(a => java.util.Arrays.equals(a, needle))
              assert(offset >= 0)
              Array.copy(replacement.getBytes("UTF-8"), 0, bytes, offset, needle.length)
              val bad = reseal(bytes); Files.write(file, bad.toArray); bad
            }.flatMap { bad =>
              Combined.resume[IO](Combined.Config(b, 3, 10.seconds)).use(_ => IO.unit).attempt.map {
                r =>
                  assert(r.isLeft);
                  assertEquals(read(Path.of(b.root).resolve("controller.bin")), bad)
              }
            }
        }
      }
      .unsafeToFuture()
  }
  test("missing initial store lock preserves unresolved lease without creating or settling") {
    Vector(false, true)
      .traverse_ { makeDirectory =>
        directory.use { p =>
          val b = binding(p, id(2))
          LocalControllerJournal.resource[IO](b, LocalControllerJournal.Mode.Create, id(90)).use {
            c =>
              c.submit(Message(Input.Request(0, Command.ReserveLaunch(id(10), id(11)))))
                .map(r => assert(r.isRight))
          } *> (if makeDirectory then IO.blocking(Files.createDirectory(Path.of(b.checkpoint))).void
                else IO.unit) *>
            Combined.resume[IO](Combined.Config(b, 3, 10.seconds)).use(_ => IO.unit).attempt.map {
              result =>
                assert(result.isLeft)
                assert(!Files.exists(Path.of(b.checkpoint).resolve("lock")))
                assertEquals(Files.exists(Path.of(b.checkpoint)), makeDirectory)
                val saved = image(b); assertEquals(saved.journal.epoch, 1L)
                assert(
                  saved.journal.lease
                    .exists(l => l.phase == ControllerReducer.Phase.Retired && !l.ended)
                )
            }
        }
      }
      .unsafeToFuture()
  }

  // Capture is supplied explicitly by the private bounded test runner; never fetched here.
  sys.env.get("COHERENT_WINDOW_EVIDENCE").foreach { location =>
    val path = Path.of(location)
    def context = get(SequenceInput.load(path))
    def originals =
      get(CoherentSequenceCommand.captures(read(path.resolve("scala-sequence-capture.md"))))
    def setup(p: Path): (Combined.Config, Combined.Bootstrap) =
      val c = context; val os = originals
      assert(os.size >= 4, "four signed linked originals required")
      val h = get(SequenceInput.block(os.head)).header
      val point = lab.network.ChainSync.Point
        .Block(lab.network.ChainSync.UInt64.from(h.slot).toOption.get, h.hash)
      val config = Combined.Config(binding(p, Id(c.id.hex)), 3, 10.seconds)
      (config, Combined.Bootstrap(c, os.take(3), point))
    def fourth = get(SequenceInput.block(originals(3)))
    def publish(s: Combined.Session[IO]): IO[Combined.View] = for
      v <- s.snapshot
      prepared <- s.prepare(v, fourth).map(get(_))
      next <- s.publish(prepared).map(get(_))
    yield next
    def oldest(v: Combined.View) = v.state.acquisition.candidates.dropRight(1).last
    def observed(
        config: Combined.Config,
        seed: Option[Combined.Bootstrap],
        jf: LocalControllerJournal.Faults = LocalControllerJournal.NoFaults,
        sf: NioLocalDerivedCheckpointStore.Faults = NioLocalDerivedCheckpointStore.NoFaults,
        hook: Combined.Phase => IO[Unit] = _ => IO.unit
    ) = Combined.observed[IO](config, seed, jf, sf, hook)

    test(
      "durable publish compaction and repeated resume preserve identity and fresh undo ownership"
    ) {
      directory
        .use { p =>
          val (config, seed) = setup(p)
          Combined
            .create[IO](config, seed)
            .use { s =>
              for
                initial <- s.snapshot
                _ = assertEquals(initial.claim.token.generation, 0L)
                next <- publish(s)
                compact <- s.advanceAnchor(next, oldest(next)).map(get(_))
                _ = assertEquals(compact.state.revision, next.state.revision)
                _ = assertEquals(compact.claim.token.generation, next.claim.token.generation + 1)
                _ = assert(compact.state.id != next.state.id)
                noOp <- s.advanceAnchor(compact, compact.state.acquisition.anchor).map(get(_))
                _ = assert(noOp eq compact)
              yield compact
            }
            .flatMap { saved =>
              Combined
                .resume[IO](config)
                .use { s =>
                  for
                    current <- s.snapshot
                    _ = assertEquals(current.claim, saved.claim)
                    _ = assertEquals(current.state.id, saved.state.id)
                    foreign <- s.advanceAnchor(saved, saved.state.acquisition.anchor)
                    _ = assert(foreign.isLeft)
                    anchor <- s.rollbackTo(current, current.state.acquisition.anchor).map(get(_))
                    _ = assertEquals(anchor.state.acquisition.size, 0)
                    _ = assertEquals(
                      anchor.claim.token.generation,
                      current.claim.token.generation + 1
                    )
                    nextBlock = get(SequenceInput.block(originals(2)))
                    candidate <- s.prepare(anchor, nextBlock).map(get(_))
                    next <- s.publish(candidate).map(get(_))
                  yield next
                }
                .flatMap { last =>
                  Combined
                    .resume[IO](config)
                    .use(_.snapshot.map(v => assertEquals(v.state.id, last.state.id)))
                }
            }
        }
        .unsafeToFuture()
    }
    test(
      "each combined publication cut returns no success and resumes exact predecessor or successor"
    ) {
      Vector(
        Combined.Phase.Intent,
        Combined.Phase.Checkpoint,
        Combined.Phase.Verified,
        Combined.Phase.Committed,
        Combined.Phase.BeforeMemory,
        Combined.Phase.Memory
      ).traverse_ { cut =>
        directory.use { p =>
          val (config, seed) = setup(p); val armed = new AtomicBoolean(false)
          val hook: Combined.Phase => IO[Unit] =
            phase => IO.raiseWhen(armed.get && phase == cut)(new java.io.IOException("cut"))
          observed(config, Some(seed), hook = hook)
            .use { s =>
              for
                before <- s.snapshot
                _ <- IO(armed.set(true))
                failure <- publish(s).attempt
                _ = assert(failure.isLeft)
                unavailable <- s.snapshot.attempt
                _ = assert(unavailable.isLeft)
                last <- s.lastConfirmed
                _ = assertEquals(last.claim, before.claim)
                _ <- IO(armed.set(false))
              yield before
            }
            .flatMap { before =>
              Combined.resume[IO](config).use { s =>
                s.snapshot.map { after =>
                  val expected = if cut == Combined.Phase.Intent then 0L else 1L
                  assertEquals(after.claim.token.generation, expected)
                  assertEquals(
                    after.state.depth,
                    if expected == 0 then before.state.depth else before.state.depth + 1
                  )
                }
              }
            }
        }
      }.unsafeToFuture()
    }
    test(
      "uncertain checkpoint directory force recovers pending successor without caller acknowledgement"
    ) {
      directory
        .use { p =>
          val (config, seed) = setup(p); val armed = new AtomicBoolean(false)
          val faults = new NioLocalDerivedCheckpointStore.Faults:
            override def at(phase: NioLocalDerivedCheckpointStore.Phase): Unit =
              if armed.get && phase == NioLocalDerivedCheckpointStore.Phase.BeforeDirectoryForce
              then throw new java.io.IOException("uncertain disk")
          observed(config, Some(seed), sf = faults).use { s =>
            for
              _ <- IO(armed.set(true))
              result <- publish(s).attempt
              _ = assert(result.isLeft)
              _ <- IO(armed.set(false))
            yield ()
          } *> Combined
            .resume[IO](config)
            .use(_.snapshot.map(v => assertEquals(v.claim.token.generation, 1L)))
        }
        .unsafeToFuture()
    }
    test("uncertain journal commit never installs memory and reopens exact committed checkpoint") {
      directory
        .use { p =>
          val (config, seed) = setup(p); val armed = new AtomicBoolean(false);
          val writes = new AtomicInteger(0)
          val faults = new LocalControllerJournal.Faults:
            override def at(phase: LocalControllerJournal.Phase): Unit =
              if armed.get && phase == LocalControllerJournal.Phase.DirectoryForced && writes
                  .incrementAndGet() == 2
              then throw new java.io.IOException("uncertain commit acknowledgement")
          observed(config, Some(seed), jf = faults).use { s =>
            for
              before <- s.snapshot
              _ <- IO(armed.set(true))
              result <- publish(s).attempt
              _ = assert(result.isLeft)
              last <- s.lastConfirmed
              _ = assertEquals(last.claim, before.claim)
              _ <- IO(armed.set(false))
            yield ()
          } *> Combined
            .resume[IO](config)
            .use(_.snapshot.map(v => assertEquals(v.claim.token.generation, 1L)))
        }
        .unsafeToFuture()
    }
    test(
      "late resource acquisition cancellation retains journal ownership until writer finalizes"
    ) {
      directory
        .use { p =>
          val (config, seed) = setup(p); val entered = new CountDownLatch(1);
          val release = new CountDownLatch(1)
          val canceled = new AtomicBoolean(false); val exposed = new AtomicBoolean(false)
          val faults = new NioLocalDerivedCheckpointStore.Faults:
            override def at(phase: NioLocalDerivedCheckpointStore.Phase): Unit =
              if phase == NioLocalDerivedCheckpointStore.Phase.Locked then
                entered.countDown(); require(release.await(15, TimeUnit.SECONDS), "latch timeout")
          (for
            fiber <- observed(config, Some(seed), sf = faults).use(_ => IO(exposed.set(true))).start
            _ <- this.entered(entered)
            cancellation <- (fiber.cancel *> IO(canceled.set(true))).start
            _ <- IO.sleep(30.millis)
            _ = assert(!canceled.get && !exposed.get)
            competitor <- LocalControllerJournal
              .resource[IO](config.binding, LocalControllerJournal.Mode.Resume, id(99))
              .use(_ => IO.unit)
              .attempt
            _ = assert(competitor.isLeft)
            _ <- IO(release.countDown())
            _ <- cancellation.joinWithNever
            _ = assert(!exposed.get)
            _ = assert(image(config.binding).journal.lease.exists(_.ended))
            _ <- LocalControllerJournal
              .resource[IO](config.binding, LocalControllerJournal.Mode.Resume, id(98))
              .use(_ => IO.unit)
          yield ()).guarantee(IO(release.countDown()))
        }
        .unsafeToFuture()
    }
    test("writer is closed before journal unlock and close waits for in-flight publication") {
      directory
        .use { p =>
          val (config, seed) = setup(p)
          val armed = new AtomicBoolean(false); val entered = new CountDownLatch(1);
          val release = new CountDownLatch(1)
          val faults = new NioLocalDerivedCheckpointStore.Faults:
            override def at(phase: NioLocalDerivedCheckpointStore.Phase): Unit =
              if armed.get && phase == NioLocalDerivedCheckpointStore.Phase.BeforeFileForce then
                entered.countDown(); require(release.await(15, TimeUnit.SECONDS), "latch timeout")
          val hook: Combined.Phase => IO[Unit] = phase =>
            if phase != Combined.Phase.WriterClosed then IO.unit
            else
              LocalControllerJournal
                .resource[IO](config.binding, LocalControllerJournal.Mode.Resume, id(99))
                .use(_ => IO.unit)
                .attempt
                .flatMap { held =>
                  IO(assert(held.isLeft)) *> NioLocalDerivedCheckpointStore
                    .resource[IO](config.binding, NioLocalDerivedCheckpointStore.Mode.Resume)
                    .use(_ => IO.unit)
                }
          observed(config, Some(seed), sf = faults, hook = hook).allocated.flatMap { (s, close) =>
            val closed = new AtomicBoolean(false)
            val debug = new java.util.concurrent.atomic.AtomicReference[String]("not completed")
            (for
              _ <- IO(armed.set(true))
              publishing <- publish(s).attempt
                .flatTap(r => IO(debug.set(r.toString)))
                .flatMap(IO.fromEither)
                .start
              _ <- this
                .entered(entered)
                .handleErrorWith(e => IO.raiseError(new Exception(debug.get(), e)))
              closing <- (close *> IO(closed.set(true))).start
              _ <- IO.sleep(30.millis)
              _ = assert(!closed.get)
              _ <- IO { armed.set(false); release.countDown() }
              _ <- publishing.joinWithNever
              _ <- closing.joinWithNever
              _ = assert(closed.get)
            yield ()).guarantee(IO(release.countDown()) *> close)
          }
        }
        .unsafeToFuture()
    }
    test("bootstrap rejects zero compaction and empty originals without exposing a session") {
      Vector(false, true)
        .traverse_ { empty =>
          directory.use { p =>
            val (config, seed) = setup(p)
            val invalid =
              if empty then seed.copy(originals = Vector.empty)
              else
                seed.copy(compactThrough = seed.context.certificateSeed.tip match
                  case tip =>
                    lab.network.ChainSync.Point
                      .Block(lab.network.ChainSync.UInt64.from(tip.slot).toOption.get, tip.hash))
            Combined
              .create[IO](config, invalid)
              .use(_ => IO.unit)
              .attempt
              .map(r => assert(r.isLeft))
          }
        }
        .unsafeToFuture()
    }
    test(
      "cancellation during publication waits for commit and snapshots cannot observe a proposal"
    ) {
      directory
        .use { p =>
          val (config, seed) = setup(p)
          val armed = new AtomicBoolean(false); val entered = new CountDownLatch(1);
          val release = new CountDownLatch(1)
          val canceled = new AtomicBoolean(false); val observedView = new AtomicBoolean(false)
          val debug = new java.util.concurrent.atomic.AtomicReference[String]("not completed")
          val hook: Combined.Phase => IO[Unit] = phase =>
            if armed.get && phase == Combined.Phase.Checkpoint then
              IO.blocking {
                entered.countDown(); require(release.await(15, TimeUnit.SECONDS), "latch timeout")
              }
            else IO.unit
          observed(config, Some(seed), hook = hook).use { s =>
            (for
              old <- s.snapshot
              _ <- IO(armed.set(true))
              writer <- publish(s).attempt
                .flatTap(r => IO(debug.set(r.toString)))
                .flatMap(IO.fromEither)
                .start
              _ <- this
                .entered(entered)
                .handleErrorWith(e => IO.raiseError(new Exception(debug.get(), e)))
              cancel <- (writer.cancel *> IO(canceled.set(true))).start
              reader <- s.snapshot.flatTap(_ => IO(observedView.set(true))).start
              _ <- IO.sleep(30.millis)
              _ = assert(!canceled.get && !observedView.get)
              _ <- IO { armed.set(false); release.countDown() }
              _ <- cancel.joinWithNever
              next <- reader.joinWithNever
              _ = assertEquals(next.claim.token.generation, 1L)
              stale <- s.advanceAnchor(old, old.state.acquisition.anchor)
              _ = assert(stale.isLeft)
              malformed <- s.advanceAnchor(null, next.state.acquisition.anchor)
              _ = assert(malformed.isLeft)
            yield ()).guarantee(IO(release.countDown()))
          } *>
            Combined
              .resume[IO](config)
              .use(_.snapshot.map(v => assertEquals(v.claim.token.generation, 1L)))
        }
        .unsafeToFuture()
    }
    test("committed successor cannot resume predecessor bytes or a missing primary") {
      Vector(false, true)
        .traverse_ { remove =>
          directory.use { p =>
            val (config, seed) = setup(p)
            val file = Path.of(config.binding.checkpoint).resolve("local-derived.bin")
            Combined
              .create[IO](config, seed)
              .use { s =>
                for
                  old <- IO.blocking(read(file))
                  _ <- publish(s)
                yield old
              }
              .flatMap { old =>
                IO.blocking {
                  if remove then Files.delete(file) else { Files.write(file, old.toArray); () }
                } *>
                  Combined.resume[IO](config).use(_ => IO.unit).attempt.map { r =>
                    assert(r.isLeft)
                    assertEquals(
                      image(config.binding).journal.selection
                        .asInstanceOf[Selection.Active]
                        .ack
                        .generation,
                      1L
                    )
                  }
              }
          }
        }
        .unsafeToFuture()
    }
  }
