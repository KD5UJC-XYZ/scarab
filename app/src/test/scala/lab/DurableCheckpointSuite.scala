// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.ChainSync

class DurableCheckpointSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def manifest(files: Map[String, Bytes]): Bytes = raw(
    "format\tcoherent-sequence-context-v1\n" + SequenceInput.sources.toVector
      .sortBy(_._1)
      .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(files(name)).hex)
      .mkString("\n") + "\n"
  )
  private def bind(files: Map[String, Bytes]): SequenceInput.Context = get(
    SequenceInput.bind(manifest(files), files)
  )
  private def read(path: Path): Bytes =
    val in = Files.newInputStream(path)
    val bytes =
      try in.readNBytes(4194305)
      finally in.close()
    assert(bytes.nonEmpty && bytes.length <= 4194304)
    Bytes.fromArray(bytes)
  private def sameContent(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
    assertEquals(a.id, b.id)
    assertEquals(a.contextId, b.contextId)
    assertEquals(a.acquisition.anchor, b.acquisition.anchor)
    assertEquals(a.acquisition.originals, b.acquisition.originals)
    assertEquals(a.certificates.state.id, b.certificates.state.id)
    assertEquals(a.certificates.state.counters, b.certificates.state.counters)
    assertEquals(a.nonces.id, b.nonces.id)
    assertEquals(a.nonces.fields, b.nonces.fields)
    assertEquals(
      a.eligibility.map(e =>
        (
          e.contextId,
          e.headers.map(h => (h.headerHash, h.leaderValue, h.stake.numerator, h.stake.denominator))
        )
      ),
      b.eligibility.map(e =>
        (
          e.contextId,
          e.headers.map(h => (h.headerHash, h.leaderValue, h.stake.numerator, h.stake.denominator))
        )
      )
    )
    assertEquals(a.ledger.id, b.ledger.id)
    assertEquals(a.ledger.outputMap, b.ledger.outputMap)
    assertEquals(a.ledger.fees, b.ledger.fees)
    assertEquals(a.ledger.slot, b.ledger.slot)
    assertEquals(a.scopedAppliedTip, b.scopedAppliedTip)
  private def unchanged(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
    sameContent(a, b)
    assertEquals(a.revision, b.revision)

  // Public context is synthetic and used only for anchor/fence behavior; no header is invented.
  private def syntheticContext: SequenceInput.Context =
    val tip = s"""{"era":"Conway","hash":"${"12" * 32}","slot":20,"block":3,"epoch":0}"""
    bind(
      Map(
        "transfer-genesis.md" -> raw(
          """{"networkId":"Testnet","networkMagic":1082026,"epochLength":500,"securityParam":5,"activeSlotsCoeff":0.05,"slotsPerKESPeriod":129600,"maxKESEvolutions":60}"""
        ),
        "pre-tips.md" -> raw(s"[$tip,$tip]"),
        "pre-protocol-state.md" -> raw(
          s"""{"lastSlot":20,"oCertCounters":{"${"34" * 28}":0},"epochNonce":null,"candidateNonce":null,"evolvingNonce":null,"labNonce":null,"lastEpochBlockNonce":null}"""
        ),
        "pre-ledger-state.md" -> raw(
          s"""{"lastEpoch":0,"stakeDistrib":{"pdTotalActiveStake":100,"unPoolDistr":{"${"34" * 28}":{"individualPoolStakeVrf":"${"56" * 32}","individualPoolStake":{"numerator":1,"denominator":1},"individualTotalPoolStake":100}}},"stateBefore":{"esLState":{"utxoState":{"fees":0}}}}"""
        ),
        "pre-parameters.md" -> raw(
          """{"protocolVersion":{"major":9,"minor":0},"txFeePerByte":44,"txFeeFixed":155381,"maxTxSize":16384,"utxoCostPerByte":4310}"""
        ),
        "pre-utxo.md" -> raw("{}"),
        "pre-utxo-cbor.md" -> raw("a0")
      )
    )
  import CoherentSequence.*
  import NioValidatedCheckpointStore.{Mode, Phase, Faults, NoFaults}
  import java.util.concurrent.{CountDownLatch, TimeUnit}
  import java.util.concurrent.atomic.AtomicBoolean
  private def temp[A](body: Path => IO[A]): IO[A] =
    cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("validated-store-test")))(root =>
        IO.blocking {
          val paths = Files.walk(root)
          try
            paths
              .sorted(java.util.Comparator.reverseOrder[Path]())
              .forEach(p => { Files.delete(p); () })
          finally paths.close()
        }
      )
      .use(body)
  private def create(
      root: Path,
      c: SequenceInput.Context = syntheticContext,
      faults: Faults = NoFaults,
      observe: DurablePhase => IO[Unit] = _ => IO.unit,
      record: PendingTokens => IO[Unit] = _ => IO.unit,
      timeout: FiniteDuration = 1.second
  ) =
    durableResource[IO](root, Some(c), c.id, None, 8, 20.seconds, timeout, record, faults, observe)
  private def resume(root: Path, token: ValidatedCheckpoint.Token) =
    durableResume[IO](root, token.contextId, token, 20.seconds, 1.second, _ => IO.unit)
  private def bytes(root: Path) =
    IO.blocking(Bytes.fromArray(Files.readAllBytes(root.resolve("validated.bin"))))
  private def unlocked(root: Path): IO[Boolean] = IO.blocking {
    val channel = java.nio.channels.FileChannel
      .open(root.resolve("lock"), java.nio.file.StandardOpenOption.WRITE)
    try
      val lock =
        try channel.tryLock()
        catch case _: java.nio.channels.OverlappingFileLockException => null
      if lock == null then false else { lock.release(); true }
    finally channel.close()
  }
  test(
    "strict replay reopen rejects wrong tokens, staging never rescues corruption, and failures release locks"
  ) {
    temp { parent =>
      val root = parent.resolve("store")
      for
        first <- create(root).use(_.snapshot)
        raw <- bytes(root)
        _ <- IO.blocking(Files.write(root.resolve("validated.tmp"), raw.toArray))
        restored <- resume(root, first.token).use(_.snapshot)
        _ = unchanged(first.snapshot.state, restored.snapshot.state)
        _ = assert(!Files.exists(root.resolve("validated.tmp")))
        bad <- resume(root, first.token.copy(generation = 9)).use(_.snapshot).attempt
        released <- unlocked(root)
        _ = assert(bad.isLeft && released)
        _ <- IO.blocking(Files.write(root.resolve("validated.tmp"), raw.toArray))
        _ <- IO.blocking(Files.write(root.resolve("validated.bin"), Array[Byte](1)))
        corrupt <- resume(root, first.token).use(_.snapshot).attempt
        released2 <- unlocked(root)
        _ = assert(corrupt.isLeft && released2 && Files.exists(root.resolve("validated.tmp")))
        _ <- IO.blocking(Files.delete(root.resolve("validated.bin")))
        missing <- resume(root, first.token).use(_.snapshot).attempt
        released3 <- unlocked(root)
      yield assert(missing.isLeft && released3)
    }.unsafeToFuture()
  }
  test("delayed competing creator rechecks under lock and cannot overwrite initialized bytes") {
    temp { parent =>
      val root = parent.resolve("store")
      val entered = new CountDownLatch(1); val release = new CountDownLatch(1)
      val faults = new Faults:
        override def at(p: Phase): Unit = if p == Phase.BeforeLock then
          entered.countDown()
          require(release.await(10, TimeUnit.SECONDS), "test barrier timeout")
      for
        delayed <- create(root, faults = faults).use(_.snapshot).attempt.start
        _ <- IO.blocking(require(entered.await(10, TimeUnit.SECONDS)))
        winner <- create(root).use(_.snapshot)
        before <- bytes(root)
        _ <- IO(release.countDown())
        loser <- delayed.joinWithNever
        after <- bytes(root)
        available <- unlocked(root)
      yield
        assert(loser.isLeft && available)
        assertEquals(after, before)
        assert(ValidatedCheckpoint.decode(after, winner.token.contextId, winner.token).isRight)
    }.unsafeToFuture()
  }
  test("lock acquisition faults release ownership and an owned directory rejects a second opener") {
    temp { parent =>
      val root = parent.resolve("store")
      val fault = new Faults:
        override def at(p: Phase): Unit =
          if p == Phase.Locked then throw new RuntimeException("locked fault")
      for
        failed <- create(root, faults = fault).use(_.snapshot).attempt
        released <- unlocked(root)
        _ <- create(root).use { runtime =>
          for
            state <- runtime.snapshot
            blocked <- resume(root, state.token).use(_.snapshot).attempt
          yield assert(blocked.isLeft)
        }
      yield assert(failed.isLeft && released)
    }.unsafeToFuture()
  }
  test("initial recorder failure timeout and cancellation release lock without file mutation") {
    temp { parent =>
      val root = parent.resolve("timeout")
      for
        entered <- Deferred[IO, Unit]; finish <- Deferred[IO, Unit]; done <- Deferred[IO, Unit]
        // Even an internally masked recorder cannot hold this store lock beyond its deadline.
        recorder = (_: PendingTokens) =>
          IO.uncancelable(_ => entered.complete(()).void *> finish.get)
            .guarantee(done.complete(()).void)
        failed <- create(root, record = recorder, timeout = 50.millis).use(_.snapshot).attempt
        available <- unlocked(root)
        _ = assert(failed.isLeft && available && !Files.exists(root.resolve("validated.bin")))
        _ <- finish.complete(()); _ <- done.get
        cancelRoot = parent.resolve("cancel")
        ready <- Deferred[IO, Unit]
        fiber <- create(cancelRoot, record = _ => ready.complete(()).void *> IO.never)
          .use(_.snapshot)
          .start
        _ <- ready.get; _ <- fiber.cancel
        canceled <- fiber.join
        cancelReleased <- unlocked(cancelRoot)
        errorRoot = parent.resolve("error")
        error <- create(errorRoot, record = _ => IO.raiseError(new RuntimeException("recorder")))
          .use(_.snapshot)
          .attempt
        errorReleased <- unlocked(errorRoot)
      yield assert(canceled.isCanceled && cancelReleased && error.isLeft && errorReleased)
    }.unsafeToFuture()
  }
  test("byte store loops short writes and rejects disk-token CAS divergence") {
    temp { parent =>
      val root = parent.resolve("store"); val c = syntheticContext
      val faults = new Faults:
        override def write(
            channel: java.nio.channels.FileChannel,
            buffer: java.nio.ByteBuffer
        ): Int =
          val limit = buffer.limit()
          buffer.limit(math.min(limit, buffer.position() + 11))
          try channel.write(buffer)
          finally buffer.limit(limit)
      for
        initial <- create(root, c, faults).use(_.snapshot)
        plain <- CoherentSequence.create[IO](c).map(get(_)).flatMap(_.snapshot)
        next = get(ValidatedCheckpoint.encode(c, plain, initial.token.storeId, 1, 8))
        _ <- NioValidatedCheckpointStore.resource[IO](root, Mode.Resume).use { disk =>
          for
            _ <- IO.blocking(Files.write(root.resolve("validated.bin"), next._1.toArray))
            denied <- IO.blocking(disk.install(Some(initial.token), next._1, next._2)).attempt
            poisoned <- IO.blocking(disk.read(c.id, next._2)).attempt
          yield assert(denied.isLeft && poisoned.isLeft)
        }
      yield ()
    }.unsafeToFuture()
  }
  test("store rejects symlinks unrelated entries and sparse oversize files without leaking locks") {
    Vector("symlink", "unrelated", "oversize", "lock-content")
      .traverse_ { kind =>
        temp { parent =>
          val root = parent.resolve("store")
          for
            initial <- create(root).use(_.snapshot)
            _ <- IO.blocking {
              kind match
                case "symlink" =>
                  Files.createSymbolicLink(
                    root.resolve("validated.tmp"),
                    root.resolve("validated.bin")
                  ); ()
                case "unrelated"    => Files.write(root.resolve("other"), Array[Byte](1)); ()
                case "lock-content" => Files.write(root.resolve("lock"), Array[Byte](1)); ()
                case _ =>
                  val channel = java.nio.channels.FileChannel.open(
                    root.resolve("validated.tmp"),
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE
                  )
                  try
                    channel.position(ValidatedCheckpoint.MaxBytes.toLong)
                    channel.write(java.nio.ByteBuffer.wrap(Array[Byte](1)))
                  finally channel.close()
            }
            failed <- resume(root, initial.token).use(_.snapshot).attempt
            available <- unlocked(root)
          yield assert(failed.isLeft && available, kind)
        }
      }
      .unsafeToFuture()
  }
  test("zero-progress writes and atomic-move errors poison with old-or-new exact file evidence") {
    Vector("zero", "unsupported", "moved-then-error")
      .traverse_ { kind =>
        temp { parent =>
          val root = parent.resolve("store"); val c = syntheticContext
          val fault = new Faults:
            override def write(channel: java.nio.channels.FileChannel, buffer: java.nio.ByteBuffer)
                : Int =
              if kind == "zero" then 0 else super.write(channel, buffer)
            override def replace(from: Path, to: Path): Unit =
              if kind == "unsupported" then
                throw new java.nio.file.AtomicMoveNotSupportedException(
                  from.toString,
                  to.toString,
                  "injected"
                )
              super.replace(from, to)
              if kind == "moved-then-error" then throw new RuntimeException("ambiguous move error")
          for
            initial <- create(root, c).use(_.snapshot)
            plain <- CoherentSequence.create[IO](c).map(get(_)).flatMap(_.snapshot)
            next = get(ValidatedCheckpoint.encode(c, plain, initial.token.storeId, 1, 8))
            _ <- NioValidatedCheckpointStore.resource[IO](root, Mode.Resume, fault).use { disk =>
              for
                failed <- IO.blocking(disk.install(Some(initial.token), next._1, next._2)).attempt
                poisoned <- IO.blocking(disk.read(c.id, initial.token)).attempt
              yield assert(failed.isLeft && poisoned.isLeft)
            }
            expected = if kind == "moved-then-error" then next._2 else initial.token
            restored <- resume(root, expected).use(_.snapshot)
          yield assertEquals(restored.token, expected)
        }
      }
      .unsafeToFuture()
  }
  test("full recovery failure releases ownership and preserves staging") {
    temp { parent =>
      val root = parent.resolve("store")
      for
        initial <- create(root).use(_.snapshot)
        saved <- bytes(root)
        changed <- IO {
          val payload = saved.toArray.dropRight(32)
          val in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))
          (0 until 4).foreach(_ => in.skipNBytes(in.readInt().toLong))
          in.readLong(); in.readInt(); in.skipNBytes(in.readInt().toLong)
          in.readInt()
          val position = payload.length - in.available()
          payload(position) = (payload(position) ^ 1).toByte
          val digest = ClusterHeaderObservation.sha256(Bytes.fromArray(payload))
          (Bytes(payload.toVector ++ digest.value), initial.token.copy(digest = digest))
        }
        _ <- IO.blocking(Files.write(root.resolve("validated.bin"), changed._1.toArray))
        _ <- IO.blocking(Files.write(root.resolve("validated.tmp"), saved.toArray))
        _ = assert(
          ValidatedCheckpoint.decode(changed._1, initial.token.contextId, changed._2).isRight
        )
        failed <- resume(root, changed._2).use(_.snapshot).attempt
        available <- unlocked(root)
      yield assert(failed.isLeft && available && Files.exists(root.resolve("validated.tmp")))
    }.unsafeToFuture()
  }
  sys.env.get("COHERENT_SEQUENCE_EVIDENCE").foreach { location =>
    def context = get(SequenceInput.load(Path.of(location)))
    def originals = get(
      CoherentSequenceCommand.captures(
        Bytes.fromArray(Files.readAllBytes(Path.of(location).resolve("scala-sequence-capture.md")))
      )
    )
    def firstBlock = get(SequenceInput.block(originals.head))
    def allDenied(
        runtime: DurableRuntime[IO],
        old: DurableSnapshot,
        candidate: Candidate
    ): IO[Unit] =
      for
        a <- runtime.snapshot.attempt
        b <- runtime.prepare(firstBlock).attempt
        c <- runtime.publish(candidate, old.token).attempt
        d <- runtime
          .rollbackTo(old.snapshot.fence, old.snapshot.state.acquisition.anchor, old.token)
          .attempt
      yield assert(Vector(a, b, c, d).forall(_.isLeft))
    test(
      "mutation recorder failure timeout and cancellation poison without filesystem publication"
    ) {
      Vector("error", "timeout", "cancel")
        .traverse_ { mode =>
          temp { parent =>
            val root = parent.resolve("store"); val armed = new AtomicBoolean(false)
            for
              ready <- Deferred[IO, Unit]
              old <- create(
                root,
                context,
                record = _ =>
                  if !armed.get() then IO.unit
                  else
                    ready.complete(()).void *> (if mode == "error" then
                                                  IO.raiseError(new RuntimeException("recorder"))
                                                else IO.never)
                ,
                timeout = 100.millis
              ).use { runtime =>
                for
                  before <- runtime.snapshot
                  original <- bytes(root)
                  candidate <- runtime.prepare(firstBlock).map(get(_))
                  _ <- IO(armed.set(true))
                  _ <-
                    if mode == "cancel" then
                      for
                        fiber <- runtime.publish(candidate, before.token).start
                        _ <- ready.get; _ <- fiber.cancel
                        outcome <- fiber.join
                      yield assert(outcome.isCanceled)
                    else
                      runtime
                        .publish(candidate, before.token)
                        .attempt
                        .map(value => assert(value.isLeft))
                  unchangedBytes <- bytes(root)
                  _ = assertEquals(unchangedBytes, original)
                  _ <- allDenied(runtime, before, candidate)
                yield before
              }
              free <- unlocked(root)
              restored <- resume(root, old.token).use(_.snapshot)
            yield assert(free && restored.snapshot.state.acquisition.size == 0)
          }
        }
        .unsafeToFuture()
    }
    test("all facade operations reject after explicit close") {
      temp { parent =>
        create(parent.resolve("store"), context).use { runtime =>
          for
            before <- runtime.snapshot
            candidate <- runtime.prepare(firstBlock).map(get(_))
            _ <- runtime.close
            _ <- allDenied(runtime, before, candidate)
            _ <- runtime.close
          yield ()
        }
      }.unsafeToFuture()
    }
    test(
      "every filesystem publication phase poisons and exact old/new reopen reconstructs the tuple"
    ) {
      val phases = Phase.values.toVector.filterNot(p => p == Phase.BeforeLock || p == Phase.Locked)
      phases
        .traverse_ { phase =>
          temp { parent =>
            val root = parent.resolve("store"); val armed = new AtomicBoolean(false)
            val fault = new Faults:
              override def at(p: Phase): Unit =
                if armed.get() && p == phase then throw new RuntimeException(phase.toString)
            for
              pending <- cats.effect.Ref.of[IO, Option[PendingTokens]](None)
              old <- create(root, context, fault, record = p => pending.set(Some(p))).use {
                runtime =>
                  for
                    before <- runtime.snapshot
                    candidate <- runtime.prepare(firstBlock).map(get(_))
                    _ <- IO(armed.set(true))
                    failed <- runtime.publish(candidate, before.token).attempt
                    _ = assert(failed.isLeft, phase.toString)
                    _ <- allDenied(runtime, before, candidate)
                  yield before
              }
              pair <- pending.get.map(_.get)
              raw <- bytes(root)
              installed = Set(Phase.Replaced, Phase.BeforeDirectoryForce, Phase.DirectoryForced)(
                phase
              )
              selected = if installed then pair.next else old.token
              wrong = if installed then old.token else pair.next
              rejected <- resume(root, wrong).use(_.snapshot).attempt
              fresh <- resume(root, selected).use(_.snapshot)
            yield
              assert(rejected.isLeft, phase.toString)
              assertEquals(fresh.snapshot.state.acquisition.size, if installed then 1 else 0)
              assert(ValidatedCheckpoint.decode(raw, selected.contextId, selected).isRight)
          }
        }
        .unsafeToFuture()
    }
    test("every facade phase including memory install and acknowledgement failure poisons") {
      DurablePhase.values.toVector
        .traverse_ { phase =>
          temp { parent =>
            val root = parent.resolve("store"); val armed = new AtomicBoolean(false)
            for
              pair <- cats.effect.Ref.of[IO, Option[PendingTokens]](None)
              old <- create(
                root,
                context,
                observe = p =>
                  if armed.get() && p == phase then IO.raiseError(new RuntimeException(p.toString))
                  else IO.unit,
                record = p => pair.set(Some(p))
              ).use { runtime =>
                for
                  before <- runtime.snapshot
                  candidate <- runtime.prepare(firstBlock).map(get(_))
                  _ <- IO(armed.set(true))
                  failed <- runtime.publish(candidate, before.token).attempt
                  _ = assert(failed.isLeft, phase.toString)
                  _ <- allDenied(runtime, before, candidate)
                yield before
              }
              pending <- pair.get.map(_.get)
              installed = Set(
                DurablePhase.AfterDisk,
                DurablePhase.BeforeMemory,
                DurablePhase.AfterMemory,
                DurablePhase.BeforeAcknowledgement,
                DurablePhase.AcknowledgementPrepared
              )(phase)
              token = if installed then pending.next else old.token
              restored <- resume(root, token).use(_.snapshot)
            yield assertEquals(restored.snapshot.state.acquisition.size, if installed then 1 else 0)
          }
        }
        .unsafeToFuture()
    }
    test("concurrent close waits for publication before releasing the lock") {
      temp { parent =>
        val root = parent.resolve("store"); val armed = new AtomicBoolean(false)
        for
          entered <- Deferred[IO, Unit]; release <- Deferred[IO, Unit]; closed <- Deferred[IO, Unit]
          _ <- create(
            root,
            context,
            observe = p =>
              if armed.get() && p == DurablePhase.AfterDisk then
                entered.complete(()).void *> release.get
              else IO.unit
          ).use { runtime =>
            for
              before <- runtime.snapshot
              candidate <- runtime.prepare(firstBlock).map(get(_))
              _ <- IO(armed.set(true))
              publishing <- runtime.publish(candidate, before.token).start
              _ <- entered.get
              closing <- (runtime.close *> closed.complete(())).start
              _ <- IO.cede
              lockFree <- unlocked(root)
              closeDone <- closed.tryGet
              _ = assert(!lockFree && closeDone.isEmpty)
              _ <- release.complete(())
              published <- publishing.joinWithNever.map(get(_))
              _ <- closing.joinWithNever
              denied <- runtime.snapshot.attempt
              free <- unlocked(root)
              reopened <- resume(root, published.token).use(_.snapshot)
            yield assert(denied.isLeft && free && reopened.snapshot.state.acquisition.size == 1)
          }
        yield ()
      }.unsafeToFuture()
    }
    test("cancellation at each masked facade boundary completes publication then releases close") {
      DurablePhase.values.toVector
        .filterNot(_ == DurablePhase.Publishing)
        .traverse_ { phase =>
          temp { parent =>
            val root = parent.resolve("store"); val armed = new AtomicBoolean(false)
            for
              entered <- Deferred[IO, Unit]; release <- Deferred[IO, Unit]
              token <- create(
                root,
                context,
                observe = p =>
                  if armed.get() && p == phase then entered.complete(()).void *> release.get
                  else IO.unit
              ).use { runtime =>
                for
                  before <- runtime.snapshot
                  candidate <- runtime.prepare(firstBlock).map(get(_))
                  _ <- IO(armed.set(true))
                  publishing <- runtime.publish(candidate, before.token).start
                  _ <- entered.get
                  cancel <- publishing.cancel.start
                  _ <- IO.cede
                  free <- unlocked(root)
                  _ = assert(!free)
                  _ <- release.complete(())
                  _ <- cancel.joinWithNever
                  _ <- publishing.join
                  current <- runtime.snapshot
                yield current.token
              }
              reopened <- resume(root, token).use(_.snapshot)
            yield assertEquals(reopened.snapshot.state.acquisition.size, 1)
          }
        }
        .unsafeToFuture()
    }
    test("concurrent candidates CAS once, no-op rollback is stable, rollback/reopen keeps undo") {
      temp { parent =>
        val root = parent.resolve("store")
        for
          saved <- create(root, context).use { runtime =>
            for
              before <- runtime.snapshot
              a <- runtime.prepare(firstBlock).map(get(_))
              b <- runtime.prepare(firstBlock).map(get(_))
              results <- (
                runtime.publish(a, before.token),
                runtime.publish(b, before.token)
              ).parTupled
              _ = assertEquals(Vector(results._1, results._2).count(_.isRight), 1)
              current <- runtime.snapshot
              noop <- runtime
                .rollbackTo(
                  current.snapshot.fence,
                  current.snapshot.state.acquisition.tip,
                  current.token
                )
                .map(get(_))
              _ = assertEquals(noop.token, current.token)
            yield current
          }
          rolled <- resume(root, saved.token).use { runtime =>
            for
              before <- runtime.snapshot
              oldFence <- runtime.rollbackTo(
                saved.snapshot.fence,
                before.snapshot.state.acquisition.anchor,
                before.token
              )
              _ = assertEquals(oldFence, Left(Failure.ForeignFence))
              result <- runtime
                .rollbackTo(
                  before.snapshot.fence,
                  before.snapshot.state.acquisition.anchor,
                  before.token
                )
                .map(get(_))
            yield result
          }
          finalState <- resume(root, rolled.token).use(_.snapshot)
        yield
          assertEquals(finalState.snapshot.state.revision, BigInt(2))
          assertEquals(finalState.token.generation, 2L)
          assertEquals(finalState.snapshot.state.acquisition.size, 0)
      }.unsafeToFuture()
    }
  }
