// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, AtomicMoveNotSupportedException}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import lab.cbor.Bytes
import scala.concurrent.duration.*

class NioLocalDerivedCheckpointStoreSuite extends munit.FunSuite:
  import NioLocalDerivedCheckpointStore.*
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def hash(n: Int) = Bytes(Vector.fill(32)(n.toByte))
  private def temp: Resource[IO, ControllerJournalCodec.Binding] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("local-derived-store-"))) { root =>
        IO.blocking {
          val stream = Files.walk(root)
          try stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
          finally stream.close()
        }
      }
      .map { root =>
        ControllerJournalCodec.Binding(
          root.resolve("controller").toString,
          root.resolve("checkpoint").toString,
          ControllerReducer
            .Store(ControllerReducer.Id(hash(1).hex), ControllerReducer.Id(hash(2).hex))
        )
      }
  private def store(
      b: ControllerJournalCodec.Binding,
      mode: Mode = Mode.Create,
      faults: Faults = NoFaults
  ) = resource[IO](b, mode, faults)

  test("Resume never creates missing root or lock; absent primary is inspection only") {
    temp
      .use { b =>
        val root = Path.of(b.checkpoint)
        for
          missing <- store(b, Mode.Resume).use(_ => IO.unit).attempt
          _ = assert(missing.isLeft)
          _ = assert(!Files.exists(root))
          _ <- IO.blocking(Files.createDirectory(root))
          noLock <- store(b, Mode.Resume).use(_ => IO.unit).attempt
          _ = assert(noLock.isLeft)
          _ = assert(!Files.exists(root.resolve("lock")))
          _ <- store(b).use { disk =>
            IO.blocking {
              assertEquals(disk.readOption(), None)
              intercept[Invalid](disk.read())
            }
          }
          _ <- store(b, Mode.Resume).use(d => IO.blocking(assertEquals(d.readOption(), None)))
        yield ()
      }
      .unsafeToFuture()
  }

  test("exclusive ownership and idempotent close preserve lock until explicit release") {
    temp
      .use { b =>
        store(b).use { disk =>
          for
            conflict <- store(b, Mode.Resume).use(_ => IO.unit).attempt
            _ = assert(conflict.isLeft)
            _ <- IO.blocking { disk.close(); disk.close() }
            _ <- store(b, Mode.Resume).use(_ => IO.unit)
            _ <- IO.blocking(intercept[Invalid](disk.readOption()))
          yield ()
        }
      }
      .unsafeToFuture()
  }

  test("failed parent force retries despite existing newly created root") {
    temp
      .use { b =>
        val count = new java.util.concurrent.atomic.AtomicInteger(0)
        val faults = new Faults:
          override def forceParent(p: Path): Unit =
            if count.incrementAndGet() == 1 then throw new java.io.IOException("parent force")
            else super.forceParent(p)
        for
          failed <- store(b, faults = faults).use(_ => IO.unit).attempt
          _ = assert(failed.isLeft && Files.isDirectory(Path.of(b.checkpoint)))
          _ <- store(b, faults = faults).use(_ => IO.unit)
          _ = assertEquals(count.get(), 2)
        yield ()
      }
      .unsafeToFuture()
  }

  test("FIFO lock primary and staging fail before stream opens in both modes") {
    Vector("lock", "local-derived.bin", "local-derived.tmp")
      .traverse_ { name =>
        Vector(Mode.Create, Mode.Resume).traverse_ { mode =>
          temp.use { b =>
            for
              _ <- IO.blocking {
                val root = Path.of(b.checkpoint); Files.createDirectory(root)
                if name != "lock" then Files.createFile(root.resolve("lock"))
                val process = new ProcessBuilder("mkfifo", root.resolve(name).toString).start()
                assert(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(process.exitValue(), 0)
              }
              result <- store(b, mode).use(_ => IO.unit).attempt.timeout(2.seconds)
              _ = assert(result.isLeft)
            yield ()
          }
        }
      }
      .unsafeToFuture()
  }

  test("symlink paths reject and orphan staging is never promoted") {
    temp
      .use { b =>
        val root = Path.of(b.checkpoint)
        for
          _ <- store(b).use(_ => IO.unit)
          _ <- IO.blocking(Files.write(root.resolve("local-derived.tmp"), Array[Byte](1, 2)))
          rejected <- store(b).use(_ => IO.unit).attempt
          _ = assert(rejected.isLeft)
          _ <- store(b, Mode.Resume).use { d =>
            IO.blocking {
              assertEquals(d.readOption(), None)
              assert(Files.exists(root.resolve("local-derived.tmp")))
              d.discardStagingAfterRecovery()
              assert(!Files.exists(root.resolve("local-derived.tmp")))
              assertEquals(d.readOption(), None)
            }
          }
          _ <- IO.blocking {
            Files.delete(root.resolve("lock"))
            Files.createSymbolicLink(root.resolve("lock"), root.resolve("missing"))
          }
          symlink <- store(b, Mode.Resume).use(_ => IO.unit).attempt
          _ = assert(symlink.isLeft)
        yield ()
      }
      .unsafeToFuture()
  }

  test("allocation and close observer failures release acquired ownership") {
    Vector(Phase.Locked, Phase.BeforeClose, Phase.Closed)
      .traverse_ { phase =>
        temp.use { b =>
          val faults = new Faults:
            override def at(p: Phase): Unit =
              if p == phase then throw new java.io.IOException("observer")
          for
            failed <- store(b, faults = faults).use(_ => IO.unit).attempt
            _ = assert(failed.isLeft)
            _ <- store(b, Mode.Resume).use(_ => IO.unit)
          yield ()
        }
      }
      .unsafeToFuture()
  }

  sys.env.get("COHERENT_WINDOW_EVIDENCE").foreach { location =>
    def publications: IO[
      (
          SequenceInput.Context,
          LocalDerivedCheckpoint.Publication,
          LocalDerivedCheckpoint.Publication
      )
    ] =
      val directory = Path.of(location)
      val context = get(SequenceInput.load(directory))
      val input = Files.newInputStream(directory.resolve("scala-sequence-capture.md"))
      val capture =
        try input.readNBytes(4194305)
        finally input.close()
      require(capture.nonEmpty && capture.length <= 4194304, "bounded capture required")
      val raw = Bytes.fromArray(capture)
      val originals = get(CoherentSequenceCommand.captures(raw))
      assert(originals.size >= 2)
      for
        runtime <- CoherentSequence.create[IO](context, 2).map(get(_))
        first <- runtime
          .prepare(get(SequenceInput.block(originals.head)))
          .map(get(_))
          .flatMap(runtime.publish)
          .map(get(_))
        _ <- runtime
          .prepare(get(SequenceInput.block(originals(1))))
          .map(get(_))
          .flatMap(runtime.publish)
          .map(get(_))
        tip <- runtime.snapshot
        _ <- runtime.advanceAnchor(tip.fence, first.state.acquisition.tip).map(get(_))
        zero <- runtime.exportLocalCheckpoint(hash(1), hash(3), 0).map(get(_))
        one <- runtime.exportLocalCheckpoint(hash(1), hash(4), 1).map(get(_))
      yield (context, zero, one)
    def bound(b: ControllerJournalCodec.Binding, c: SequenceInput.Context) =
      b.copy(store = b.store.copy(context = ControllerReducer.Id(c.id.hex)))

    test("full claim CAS supports new issuer and rejects changed predecessor metadata") {
      publications
        .flatMap { (c, zero, one) =>
          temp.use { base =>
            val b = bound(base, c)
            for
              _ <- store(b).use(d =>
                IO.blocking {
                  d.install(None, zero)
                  assertEquals(d.read(), zero.bytes)
                  d.install(Some(zero.claim), one)
                  assertEquals(d.read(), one.bytes)
                }
              )
              _ <- store(b, Mode.Resume).use(d => IO.blocking(assertEquals(d.read(), one.bytes)))
            yield ()
          } *> temp.use { base =>
            store(bound(base, c)).use(d =>
              IO.blocking {
                d.install(None, zero)
                intercept[Invalid](d.install(Some(zero.claim.copy(anchorId = hash(9))), one))
                intercept[Invalid](d.read())
              }
            )
          }
        }
        .unsafeToFuture()
    }

    test("every write cut poisons and reopen sees only complete old or new primary") {
      val phases = Vector(
        Phase.BeforeOpen,
        Phase.Opened,
        Phase.WriteChunk,
        Phase.Written,
        Phase.BeforeFileForce,
        Phase.FileForced,
        Phase.BeforeFileClose,
        Phase.FileClosed,
        Phase.BeforeReplace,
        Phase.Replaced,
        Phase.BeforeDirectoryForce,
        Phase.DirectoryForced
      )
      publications
        .flatMap { (c, zero, one) =>
          phases.traverse_ { phase =>
            temp.use { base =>
              val b = bound(base, c); val armed = new AtomicBoolean(false)
              val faults = new Faults:
                override def at(p: Phase): Unit =
                  if armed.get && p == phase then throw new java.io.IOException("cut")
              for
                _ <- store(b, faults = faults).use(d =>
                  IO.blocking {
                    d.install(None, zero); armed.set(true)
                    intercept[java.io.IOException](d.install(Some(zero.claim), one))
                    intercept[Invalid](d.readOption())
                  }
                )
                _ <- store(b, Mode.Resume).use(d =>
                  IO.blocking {
                    val bytes = d.read()
                    assert(bytes == zero.bytes || bytes == one.bytes)
                    d.discardStagingAfterRecovery()
                    assertEquals(d.read(), bytes)
                  }
                )
              yield ()
            }
          }
        }
        .unsafeToFuture()
    }

    test("short writes succeed; stalled writes and unsupported atomic replacement poison") {
      publications
        .flatMap { (c, zero, _) =>
          val short = new Faults:
            override def write(ch: FileChannel, b: ByteBuffer): Int =
              val end = b.limit(); b.limit(math.min(end, b.position() + 7))
              try ch.write(b)
              finally b.limit(end)
          temp.use(b =>
            store(bound(b, c), faults = short).use(d =>
              IO.blocking {
                d.install(None, zero); assertEquals(d.read(), zero.bytes)
              }
            )
          ) *> Vector(
            new Faults {
              override def write(ch: FileChannel, b: ByteBuffer): Int = 0
            },
            new Faults {
              override def replace(from: Path, to: Path): Unit =
                throw new AtomicMoveNotSupportedException(from.toString, to.toString, "test")
            }
          ).traverse_ { faults =>
            temp.use(b =>
              store(bound(b, c), faults = faults).use(d =>
                IO.blocking {
                  intercept[Exception](d.install(None, zero))
                  intercept[Invalid](d.readOption())
                }
              )
            )
          }
        }
        .unsafeToFuture()
    }

    test("wrong pinned store rejects before publication and primary corruption is never accepted") {
      publications
        .flatMap { (c, zero, _) =>
          temp.use { b =>
            store(b).use(d =>
              IO.blocking {
                intercept[Invalid](d.install(None, zero))
                assert(!Files.exists(d.root.resolve("local-derived.bin")))
              }
            )
          } *> temp.use { base =>
            val b = bound(base, c)
            for
              _ <- store(b).use(d => IO.blocking(d.install(None, zero)))
              _ <- IO.blocking(
                Files.write(Path.of(b.checkpoint).resolve("local-derived.bin"), Array[Byte](1, 2))
              )
              bad <- store(b, Mode.Resume).use(d => IO.blocking(d.read())).attempt
              _ = assert(bad.isLeft)
            yield ()
          }
        }
        .unsafeToFuture()
    }
  }
