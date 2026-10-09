// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption as O}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.*
import lab.cbor.Bytes
import lab.network.ChainSync
import ControllerReducer.*
import ControllerJournalCodec.*
import CombinedLocalV2 as Combined

/** Persisted abrupt-stop fixtures are written directly: no controller or combined resource is
  * opened before Resume, hence no retirement/ownership finalizer can rewrite their crash state.
  * This models persisted crash cuts, not a hardware power-loss/OS-kill durability claim.
  */
class CombinedLocalV2AcceptanceSuite extends munit.FunSuite:
  def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  def id(n: Int): Id = Id(f"$n%064x")
  def raw(i: Id): Bytes = get(Bytes.fromHex(i.value))
  def directory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("combined-cuts-")))(p =>
      IO.blocking {
        val stream = Files.walk(p)
        try stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(Files.delete(_))
        finally stream.close()
      }
    )
  def read(p: Path): Bytes =
    val in = Files.newInputStream(p)
    try
      val a = in.readNBytes(LocalDerivedCheckpoint.MaxBytes + 1)
      assert(a.length <= LocalDerivedCheckpoint.MaxBytes); Bytes.fromArray(a)
    finally in.close()
  def journal(b: Binding): Image = get(decode(read(Path.of(b.root).resolve("controller.bin")), b))
  def checkpoint(b: Binding): Bytes = read(Path.of(b.checkpoint).resolve("local-derived.bin"))
  def forceFile(p: Path, bytes: Bytes): Unit =
    val out = FileChannel.open(p, O.CREATE_NEW, O.WRITE)
    try
      val buffer = ByteBuffer.wrap(bytes.toArray)
      while buffer.hasRemaining do assert(out.write(buffer) > 0)
      out.force(true)
    finally out.close()
  def forceDirectory(p: Path): Unit =
    val channel = FileChannel.open(p, O.READ)
    try channel.force(true)
    finally channel.close()
  def persist(image: Image, primary: Option[Bytes], staging: Option[Bytes]): IO[Bytes] =
    IO.blocking {
      val b = image.binding
      val controller = Path.of(b.root); val store = Path.of(b.checkpoint)
      Files.createDirectory(controller); Files.createDirectory(store)
      val bytes = get(encode(image)); assertEquals(get(decode(bytes, b)).journal, image.journal)
      forceFile(controller.resolve("lock"), Bytes.empty)
      forceFile(store.resolve("lock"), Bytes.empty)
      forceFile(controller.resolve("controller.bin"), bytes)
      primary.foreach(forceFile(store.resolve("local-derived.bin"), _))
      staging.foreach(forceFile(store.resolve("local-derived.tmp"), _))
      forceDirectory(controller); forceDirectory(store); forceDirectory(controller.getParent)
      bytes
    }
  def live(b: Binding, claim: Option[Claim]): Lease = Lease(
    1,
    id(10),
    id(11),
    b.store,
    if claim.isDefined then Phase.Serving else Phase.Probe,
    Some(id(12)),
    locked = true,
    verified = claim
  )
  def snapshotMatches(v: Combined.View, expected: LocalDerivedCheckpoint.Claim): Unit =
    assertEquals(v.claim, expected)
    assertEquals(v.state.id, expected.finalId)
    assertEquals(v.state.revision, expected.revision)
    assertEquals(v.state.compactedBlocks, expected.compactedBlocks)

  sys.env.get("COHERENT_WINDOW_EVIDENCE").foreach { location =>
    def originals = get(
      CoherentSequenceCommand.captures(read(Path.of(location).resolve("scala-sequence-capture.md")))
    )
    def config(p: Path): (Combined.Config, Combined.Bootstrap) =
      val c = get(SequenceInput.load(Path.of(location)))
      val os = originals; assert(os.size >= 4)
      val h = get(SequenceInput.block(os.head)).header
      val point = ChainSync.Point.Block(get(ChainSync.UInt64.from(h.slot)), h.hash)
      val b = Binding(
        p.resolve("controller").toString,
        p.resolve("checkpoint").toString,
        Store(id(7), Id(c.id.hex))
      )
      (Combined.Config(b, 3, 10.seconds), Combined.Bootstrap(c, os.take(3), point))
    def nextBlock = get(SequenceInput.block(originals(3)))
    def applyBlock(r: CoherentSequence.Runtime[IO], b: SequenceInput.Block): IO[Unit] =
      r.prepare(b).map(get(_)).flatMap(r.publish).map(get(_)).void
    def seedRuntime(seed: Combined.Bootstrap): IO[CoherentSequence.Runtime[IO]] = for
      r <- CoherentSequence.create[IO](seed.context, 3).map(get(_))
      _ <- seed.originals.traverse_(o => applyBlock(r, get(SequenceInput.block(o))))
      before <- r.snapshot
      _ <- r.advanceAnchor(before.fence, seed.compactThrough).map(get(_))
    yield r
    def exported(r: CoherentSequence.Runtime[IO], b: Binding, session: Id, generation: Long) =
      r.exportLocalCheckpoint(raw(b.store.id), raw(session), generation).map(get(_))
    def publish(s: Combined.Session[IO]): IO[Combined.View] = for
      v <- s.snapshot
      p <- s.prepare(v, nextBlock).map(get(_))
      next <- s.publish(p).map(get(_))
    yield next
    def observed(
        c: Combined.Config,
        seed: Combined.Bootstrap,
        hook: Combined.Phase => IO[Unit],
        recovery: String => IO[Unit] = _ => IO.unit
    ) = Combined.observed[IO](
      c,
      Some(seed),
      LocalControllerJournal.NoFaults,
      NioLocalDerivedCheckpointStore.NoFaults,
      hook,
      recovery
    )

    test(
      "abrupt persisted unresolved pending A-to-B resumes exact primary A or B without graceful rewrite"
    ) {
      Vector(false, true)
        .traverse_ { successor =>
          directory.use { p =>
            val (c, seed) = config(p); val b = c.binding
            for
              runtime <- seedRuntime(seed)
              a <- exported(runtime, b, id(10), 0)
              _ <- applyBlock(runtime, nextBlock)
              next <- exported(runtime, b, id(10), 1)
              op = Operation(id(30), 1, id(10), Some(project(a.claim)), project(next.claim))
              j = Journal(
                8,
                1,
                Selection.Active(project(a.claim)),
                Some(live(b, Some(project(a.claim)))),
                Some(op)
              )
              exactCrash <- persist(
                Image(b, j, Vector(a.claim, next.claim)),
                Some(if successor then next.bytes else a.bytes),
                None
              )
              _ = assertEquals(read(Path.of(b.root).resolve("controller.bin")), exactCrash)
              _ = assert(!journal(b).journal.lease.get.ended)
              _ = assertEquals(journal(b).journal.lease.get.phase, Phase.Serving)
              _ <- Combined.resume[IO](c).use { session =>
                for
                  view <- session.snapshot
                  _ = snapshotMatches(view, if successor then next.claim else a.claim)
                  saved <- IO.blocking(journal(b))
                  _ = assertEquals(saved.journal.pending, None)
                  _ = assertEquals(
                    saved.journal.last,
                    Some(Completion(op, if successor then Outcome.Committed else Outcome.Aborted))
                  )
                  _ = assertEquals(saved.journal.lease.get.epoch, 2L)
                  _ = assertEquals(saved.journal.lease.get.phase, Phase.Serving)
                yield ()
              }
            yield ()
          }
        }
        .unsafeToFuture()
    }

    test(
      "abrupt initial pending absent and staging-only images abort to Dormant without promotion or bootstrap"
    ) {
      Vector(false, true)
        .traverse_ { stageOnly =>
          directory.use { p =>
            val (c, seed) = config(p); val b = c.binding
            for
              runtime <- seedRuntime(seed)
              a <- exported(runtime, b, id(10), 0)
              op = Operation(id(30), 1, id(10), None, project(a.claim))
              j = Journal(5, 1, Selection.Dormant(b.store), Some(live(b, None)), Some(op))
              exactCrash <- persist(
                Image(b, j, Vector(a.claim)),
                None,
                Option.when(stageOnly)(a.bytes)
              )
              _ = assertEquals(read(Path.of(b.root).resolve("controller.bin")), exactCrash)
              _ = assert(!journal(b).journal.lease.get.ended)
              result <- Combined
                .resume[IO](c)
                .use(_ => IO(fail("must not expose an initialized session")))
                .attempt
              _ = assert(result.isLeft)
              _ = assert(result.swap.toOption.get.getMessage.contains("initialization-required"))
              saved <- IO.blocking(journal(b))
              _ = assertEquals(saved.journal.selection, Selection.Dormant(b.store))
              _ = assertEquals(saved.journal.pending, None)
              _ = assertEquals(saved.journal.last, Some(Completion(op, Outcome.Aborted)))
              _ = assert(!Files.exists(Path.of(b.checkpoint).resolve("local-derived.bin")))
              _ = if stageOnly then
                assertEquals(read(Path.of(b.checkpoint).resolve("local-derived.tmp")), a.bytes)
            yield ()
          }
        }
        .unsafeToFuture()
    }

    test(
      "verification timeout settles recovery cancellation before return and strict Resume selects the published successor"
    ) {
      directory
        .use { p =>
          val (base, seed) = config(p); val c = base.copy(recoveryDeadline = 1.second)
          val armed = new AtomicBoolean(false); val active = new AtomicInteger(0);
          val commits = new AtomicInteger(0)
          val returned = new AtomicBoolean(false)
          for
            cleanupEntered <- Deferred[IO, Unit]
            cleanupRelease <- Deferred[IO, Unit]
            timedOut <- observed(
              c,
              seed,
              phase =>
                IO {
                  if armed.get && phase == Combined.Phase.Committed then commits.incrementAndGet();
                  ()
                },
              label =>
                IO.defer {
                  if armed.get && label == "context" then
                    IO(active.incrementAndGet()) *> IO
                      .never[Unit]
                      .guarantee(
                        cleanupEntered.complete(()).void *> cleanupRelease.get *> IO(
                          active.decrementAndGet()
                        ).void
                      )
                  else IO.unit
                }
            ).use { s =>
              (for
                before <- s.snapshot
                _ <- IO(armed.set(true))
                attempt <- publish(s).attempt.flatTap(_ => IO(returned.set(true))).start
                _ <- cleanupEntered.get.timeout(5.seconds)
                _ = assertEquals(active.get, 1)
                _ = assert(!returned.get, "timeout must wait for recovery cleanup")
                competitor <- LocalControllerJournal
                  .resource[IO](c.binding, LocalControllerJournal.Mode.Resume, id(99))
                  .use(_ => IO.unit)
                  .attempt
                _ = assert(competitor.isLeft)
                diskClaim <- IO.blocking(
                  get(LocalDerivedCheckpoint.decode(checkpoint(c.binding))).claim
                )
                _ <- cleanupRelease.complete(())
                result <- attempt.joinWithNever
                _ = assert(result.isLeft)
                _ = assert(result.swap.toOption.get.getMessage.contains("recovery deadline"))
                _ = assertEquals(active.get, 0)
                _ = assertEquals(commits.get, 0)
                last <- s.lastConfirmed
                _ = assertEquals(last.claim, before.claim)
                _ = assertEquals(last.state.id, before.state.id)
                unavailable <- s.snapshot.attempt
                _ = assert(unavailable.isLeft)
                retry <- s.prepare(before, nextBlock).attempt
                _ = assert(retry.isLeft)
                pending <- IO.blocking(journal(c.binding))
                _ = assertEquals(pending.journal.selection, Selection.Active(project(before.claim)))
                _ = assert(pending.journal.pending.exists(_.after == project(diskClaim)))
                _ <- IO(armed.set(false))
              yield diskClaim).guarantee(cleanupRelease.complete(()).void)
            }
            _ <- Combined.resume[IO](base).use(_.snapshot.map(v => snapshotMatches(v, timedOut)))
          yield ()
        }
        .unsafeToFuture()
    }

    test(
      "advanceAnchor publication cuts recover exact full claims and state identities then publish the next block"
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
          val (c, seed) = config(p); val armed = new AtomicBoolean(false)
          val hook: Combined.Phase => IO[Unit] =
            phase => IO.raiseWhen(armed.get && phase == cut)(new java.io.IOException("anchor cut"))
          observed(c, seed, hook)
            .use { s =>
              for
                before <- s.snapshot
                mirror <- seedRuntime(seed)
                start <- mirror.snapshot
                through = before.state.acquisition.candidates.dropRight(1).last
                planned <- mirror.advanceAnchor(start.fence, through).map(get(_))
                fullProposal <- exported(
                  mirror,
                  c.binding,
                  Id(before.claim.token.sessionId.hex),
                  before.claim.token.generation + 1
                )
                _ = assertEquals(planned.state.revision, before.state.revision)
                _ = assert(planned.state.id != before.state.id)
                _ <- IO(armed.set(true))
                result <- s.advanceAnchor(before, through).attempt
                _ = assert(result.isLeft)
                unchanged <- s.lastConfirmed
                _ = assertEquals(unchanged.claim, before.claim)
                _ = assertEquals(unchanged.state.id, before.state.id)
                saved <- IO.blocking(journal(c.binding))
                _ = assert(
                  saved.claims.contains(fullProposal.claim),
                  "intent must retain the complete independently encoded proposal"
                )
                selected = if cut == Combined.Phase.Intent then before.claim else fullProposal.claim
                primary <- IO.blocking(
                  get(LocalDerivedCheckpoint.decode(checkpoint(c.binding))).claim
                )
                _ = assertEquals(primary, selected)
                _ <- IO(armed.set(false))
              yield (selected, through)
            }
            .flatMap { (selected, through) =>
              Combined.resume[IO](c).use { s =>
                for
                  recovered <- s.snapshot
                  _ = snapshotMatches(recovered, selected)
                  mirror <- seedRuntime(seed)
                  initial <- mirror.snapshot
                  _ <-
                    if cut == Combined.Phase.Intent then IO.unit
                    else mirror.advanceAnchor(initial.fence, through).map(get(_)).void
                  next <- publish(s)
                  _ <- applyBlock(mirror, nextBlock)
                  expectedState <- mirror.snapshot
                  expected <- exported(
                    mirror,
                    c.binding,
                    Id(next.claim.token.sessionId.hex),
                    selected.token.generation + 1
                  )
                  _ = assertEquals(next.state.id, expectedState.state.id)
                  _ = snapshotMatches(next, expected.claim)
                  _ = assertEquals(
                    journal(c.binding).journal.selection,
                    Selection.Active(project(next.claim))
                  )
                yield ()
              }
            }
        }
      }.unsafeToFuture()
    }
  }
