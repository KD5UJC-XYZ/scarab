// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path, StandardOpenOption}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.fetcher.Digests
import lab.network.ChainSync
import BoundedChainFollower.*
import AcquisitionCheckpoint.*
import NioAcquisitionCheckpointStore.{Open, Phase}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

class AcquisitionCheckpointSuite extends munit.FunSuite:
  private def n(v: Value) = Node(v, Bytes.empty)
  private def arr(v: Value*) = Value.Arr(v.toVector.map(n))
  private def u(v: Int) = Value.UInt(BigInt(v))
  private def bytes(size: Int, salt: Int = 0) =
    Value.ByteString(Bytes(Vector.fill(size)(salt.toByte)))
  private def encodeValue(v: Value) = Cbor.encode(v).toOption.get
  private val anchor =
    ChainSync.Point.Block(ChainSync.UInt64.Zero, Bytes(Vector.fill(32)(0.toByte)))
  private val context = Context.checked("a" * 64, "b" * 64, 1082026, anchor).toOption.get
  private def block(previous: ChainSync.Point, slot: Int, number: Int, salt: Int = 0): Original =
    val parent = previous.asInstanceOf[ChainSync.Point.Block].hash
    val body = Vector(arr(), arr(), Value.Map(Vector.empty), arr())
    val digest =
      Blake2b.hash256.hash(Bytes(body.flatMap(v => Blake2b.hash256.hash(encodeValue(v)).value)))
    val header = arr(
      arr(
        u(number),
        u(slot),
        Value.ByteString(parent),
        bytes(32, salt),
        bytes(32),
        arr(bytes(64), bytes(80)),
        u(4),
        Value.ByteString(digest),
        arr(bytes(32), u(0), u(0), bytes(64)),
        arr(u(11), u(2))
      ),
      bytes(448)
    )
    Original(
      encodeValue(arr(u(6), Value.Tag(24, n(Value.ByteString(encodeValue(header)))))),
      encodeValue(arr(u(7), Value.Arr((header +: body).map(n))))
    )
  private def point(original: Original): ChainSync.Point =
    val h = ReferenceCaptureCommand.header(original.envelope).toOption.get
    ChainSync.Point.Block(ChainSync.UInt64.from(h.slot).toOption.get, h.hash)
  private val a = block(anchor, 1, 1)
  private val b = block(point(a), 2, 2)
  private val c = block(point(b), 3, 3)
  private val fork = block(point(a), 4, 2, 1)
  private val forkEnd = block(point(fork), 5, 3, 1)
  private def cp(originals: Original*) = checked(anchor, originals.toVector).toOption.get

  test("extension and rollback change the existing immutable source identity") {
    assertNotEquals(cp(a).source[IO].identity.digest, cp(a, b).source[IO].identity.digest)
    assertNotEquals(cp(a, b).source[IO].identity.digest, cp(a, fork).source[IO].identity.digest)
    assertEquals(cp(a).source[IO].identity.digest, cp(a).source[IO].identity.digest)
  }

  test("cancel during publication waits for disk commit and memory advancement") {
    directory
      .use { root =>
        val entered = new java.util.concurrent.CountDownLatch(1)
        val release = new java.util.concurrent.CountDownLatch(1)
        val installs = new java.util.concurrent.atomic.AtomicInteger(0)
        val fault: Phase => Unit = p =>
          if p == Phase.BeforeInstall && installs.incrementAndGet() == 2 then
            entered.countDown()
            if !release.await(5, java.util.concurrent.TimeUnit.SECONDS) then
              throw new RuntimeException("test publication release timeout")
        store(root, Open.Create).use(_.snapshot) *>
          store(root, Open.Resume(), fault = fault).use { s =>
            val p = peer(List(Event.Forward(a.envelope)), anchor, Vector(a))
            persistedResource(s, p, Policy(target = 1)).use { f =>
              (for
                fiber <- f.run.start
                reached <- IO.blocking(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                _ <- IO(assert(reached))
                cancelled <- Deferred[IO, Unit]
                cancel <- fiber.cancel.guarantee(cancelled.complete(()).void).start
                _ <- IO.sleep(50.millis)
                pending <- cancelled.tryGet
                _ <- IO(assertEquals(pending, None))
                _ <- IO(release.countDown())
                _ <- cancel.joinWithNever
                memory <- f.checkpoint
                disk <- s.snapshot
                _ <- IO {
                  assertEquals(memory.originals, Vector(a))
                  assertEquals(disk.checkpoint.originals, memory.originals)
                }
              yield ()).guarantee(IO(release.countDown()))
            }
          } *> store(root, Open.Resume())
            .use(_.snapshot)
            .map(saved => assertEquals(saved.checkpoint.originals, Vector(a)))
      }
      .unsafeToFuture()
  }

  test("post-install publication failure is fatal with old memory and complete new disk state") {
    directory
      .use { root =>
        val installs = new java.util.concurrent.atomic.AtomicInteger(0)
        val fault: Phase => Unit = p =>
          if p == Phase.Installed && installs.incrementAndGet() == 2 then
            throw new RuntimeException("installed but acknowledgement lost")
        store(root, Open.Create).use(_.snapshot) *>
          store(root, Open.Resume(), fault = fault).use { s =>
            persistedResource(
              s,
              peer(List(Event.Forward(a.envelope)), anchor, Vector(a)),
              Policy(target = 1, reconnects = 4)
            ).use { f =>
              for
                result <- f.run.attempt
                memory <- f.checkpoint
              yield
                assert(result.swap.toOption.get.isInstanceOf[PublicationFailed])
                assertEquals(memory.size, 0)
            }
          } *> store(root, Open.Resume())
            .use(_.snapshot)
            .map(saved => assertEquals(saved.checkpoint.originals, Vector(a)))
      }
      .unsafeToFuture()
  }
  private def directory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("acquisition-checkpoint-test-")))(root =>
      IO.blocking {
        val paths = Files.walk(root)
        try paths.iterator().asScala.toVector.reverse.foreach(Files.delete)
        finally paths.close()
      }
    )
  private def store(
      root: Path,
      mode: Open,
      expectedContext: Context = context,
      fault: Phase => Unit = _ => ()
  ) =
    NioAcquisitionCheckpointStore.resource[IO](root.resolve("store"), expectedContext, mode, fault)
  private def failure[A](action: IO[A]): IO[Unit] =
    action.attempt.map(result => assert(result.isLeft))

  test("pure codec preserves exact originals and checks lengths, checksum and context") {
    val raw = encode(context, 3, cp(a, b)).toOption.get
    val decoded = decode(raw, context).toOption.get
    assertEquals(decoded.checkpoint.originals, Vector(a, b))
    assertEquals(decoded.revision.generation, 3L)
    assertEquals(decoded.revision.digest, Digests.sha256(raw.dropRight(64)))
    (0 until raw.length by 131).foreach { index =>
      assert(decode(raw.take(index), context).isLeft)
      val corrupt = raw.clone()
      corrupt(index) = (corrupt(index) ^ 1).toByte
      assert(decode(corrupt, context).isLeft)
    }
    assert(decode(raw ++ Array[Byte](0), context).isLeft)
    assert(decode(new Array[Byte](MaxBytes + 1), context).isLeft)
    assert(decode(raw, Context.checked("a" * 64, "c" * 64, 1082026, anchor).toOption.get).isLeft)
    assert(decode(raw, Context.checked("c" * 64, "b" * 64, 1082026, anchor).toOption.get).isLeft)
    assert(decode(raw, Context.checked("a" * 64, "b" * 64, 2, anchor).toOption.get).isLeft)
    assert(decode(raw, Context.checked("a" * 64, "b" * 64, 1082026, point(a)).toOption.get).isLeft)
    assert(Context.checked("a" * 64, "b" * 64, 1082026, anchor, "other").isLeft)
    // Recompute the outer digest after corrupting original bytes: original-byte validation still rejects.
    val payload = raw.dropRight(64)
    payload(payload.length - 1) = 0xff.toByte
    assert(
      decode(
        payload ++ Digests.sha256(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8),
        context
      ).isLeft
    )
  }

  test("save release reopen revalidates bytes; stale caller revisions and disk replays reject") {
    directory
      .use { root =>
        for
          saved <- store(root, Open.Create).use { s =>
            for
              initial <- s.snapshot
              first <- s.save(initial.revision, cp(a))
              _ <- failure(s.save(initial.revision, cp(a, b)))
            yield first
          }
          _ <- store(root, Open.Resume(Some(saved.revision))).use { s =>
            s.snapshot.flatMap { restored =>
              IO(assertEquals(restored.checkpoint.originals, Vector(a))) *>
                s.save(restored.revision, cp(a, b)).void
            }
          }
          _ <- failure(store(root, Open.Resume(Some(saved.revision))).use(_.snapshot))
          _ <- failure(
            store(
              root,
              Open.Resume(),
              Context.checked("a" * 64, "c" * 64, 1082026, anchor).toOption.get
            ).use(_.snapshot)
          )
        yield ()
      }
      .unsafeToFuture()
  }

  test("all publication interruption boundaries reopen only a complete old or new checkpoint") {
    Phase.values.toList
      .traverse_ { phase =>
        directory.use { root =>
          for
            initial <- store(root, Open.Create).use(_.snapshot)
            _ <- store(
              root,
              Open.Resume(),
              fault = p => if p == phase then throw new RuntimeException("injected interruption")
            ).use { s =>
              failure(s.save(initial.revision, cp(a))) *> failure(s.snapshot)
            }
            restored <- store(root, Open.Resume()).use(_.snapshot)
            _ <- IO {
              val installed = phase == Phase.Installed || phase == Phase.DirectoryForced
              assertEquals(
                restored.checkpoint.originals,
                if installed then Vector(a) else Vector.empty
              )
              assert(!Files.exists(root.resolve("store/checkpoint.tmp")))
            }
          yield ()
        }
      }
      .unsafeToFuture()
  }

  test(
    "partial first publication is never promoted; corrupt committed bytes never fall back to staging"
  ) {
    directory
      .use { root =>
        failure(
          store(
            root,
            Open.Create,
            fault = p => if p == Phase.Written then throw new RuntimeException("interrupted")
          ).use(_.snapshot)
        ) *>
          failure(store(root, Open.Resume()).use(_.snapshot))
      }
      .flatMap { _ =>
        directory.use { root =>
          store(root, Open.Create).use(_.snapshot).flatMap { _ =>
            IO.blocking {
              val target = root.resolve("store/checkpoint.bin")
              Files.copy(target, root.resolve("store/checkpoint.tmp"))
              Files.write(target, Array[Byte](1, 2, 3), StandardOpenOption.TRUNCATE_EXISTING)
            } *> failure(store(root, Open.Resume()).use(_.snapshot))
          }
        }
      }
      .unsafeToFuture()
  }

  test("owner lock, unrelated files, symlink and directory byte bounds fail closed") {
    directory
      .use { root =>
        store(root, Open.Create).use { s =>
          failure(store(root, Open.Resume()).use(_.snapshot)) *> s.snapshot.void
        } *> IO.blocking(Files.write(root.resolve("store/unrelated"), Array[Byte](1))) *>
          failure(store(root, Open.Resume()).use(_.snapshot))
      }
      .flatMap { _ =>
        directory.use { root =>
          store(root, Open.Create).use(_.snapshot) *> IO.blocking {
            Files.createSymbolicLink(
              root.resolve("store/checkpoint.tmp"),
              root.resolve("store/checkpoint.bin")
            )
          } *> failure(store(root, Open.Resume()).use(_.snapshot))
        }
      }
      .flatMap { _ =>
        directory.use { root =>
          store(root, Open.Create).use(_.snapshot) *> IO.blocking {
            val channel = java.nio.channels.FileChannel.open(
              root.resolve("store/checkpoint.tmp"),
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE
            )
            try
              channel.position(MaxBytes.toLong)
              channel.write(java.nio.ByteBuffer.wrap(Array[Byte](0)))
            finally channel.close()
          } *> failure(store(root, Open.Resume()).use(_.snapshot))
        }
      }
      .unsafeToFuture()
  }

  private def peer(
      events: List[Event],
      intersection: ChainSync.Point,
      originals: Vector[Original]
  ): Resource[IO, Peer[IO]] =
    Resource.eval(Ref.of[IO, List[Event]](events)).map { queue =>
      new Peer[IO]:
        def intersect(candidates: Vector[ChainSync.Point]) = IO {
          assert(candidates.contains(intersection))
          intersection
        }
        def next = queue
          .modify {
            case head :: tail => (tail, Some(head))
            case Nil          => (Nil, None)
          }
          .flatMap(_.fold(IO.raiseError[Event](new RuntimeException("disconnect")))(IO.pure))
        def fetch(p: ChainSync.Point) = IO.pure(originals.find(point(_) == p).get.block)
    }

  test("new follower resource reloads persisted candidates then rolls back and reapplies a fork") {
    directory
      .use { root =>
        val firstPeer = peer(List(a, b).map(o => Event.Forward(o.envelope)), anchor, Vector(a, b))
        for
          first <- store(root, Open.Create).use { s =>
            persistedResource(s, firstPeer, Policy(target = 2, reconnects = 0)).use(_.run)
          }
          second <- store(root, Open.Resume()).use { s =>
            val next = peer(
              List(
                Event.Backward(point(a)),
                Event.Forward(fork.envelope),
                Event.Forward(forkEnd.envelope)
              ),
              point(b),
              Vector(fork, forkEnd)
            )
            persistedResource(s, next, Policy(target = 3, reconnects = 0)).use(_.run)
          }
          disk <- store(root, Open.Resume()).use(_.snapshot)
        yield
          assertEquals(first.checkpoint.originals, Vector(a, b))
          assertEquals(second.checkpoint.originals, Vector(a, fork, forkEnd))
          assertEquals(disk.checkpoint.originals, second.checkpoint.originals)
          assertNotEquals(
            first.checkpoint.source[IO].identity.digest,
            second.checkpoint.source[IO].identity.digest
          )
      }
      .unsafeToFuture()
  }

  test("publication failure is fatal without reconnect or in-memory advancement") {
    directory
      .use { root =>
        store(root, Open.Create).use(_.snapshot) *>
          store(
            root,
            Open.Resume(),
            fault = p => if p == Phase.BeforeWrite then throw new RuntimeException("disk fault")
          ).use { s =>
            Ref.of[IO, Int](0).flatMap { opens =>
              val p = Resource
                .eval(opens.update(_ + 1))
                .flatMap(_ => peer(List(Event.Forward(a.envelope)), anchor, Vector(a)))
              persistedResource(s, p, Policy(target = 1, reconnects = 4)).use { f =>
                for
                  outcome <- f.run.attempt
                  state <- f.checkpoint
                  count <- opens.get
                yield
                  assert(outcome.swap.toOption.get.isInstanceOf[PublicationFailed])
                  assertEquals(state.size, 0)
                  assertEquals(count, 1)
              }
            }
          }
      }
      .unsafeToFuture()
  }

  test("cancellation after one published block reopens that progress with a fresh owner") {
    directory
      .use { root =>
        for
          waiting <- Deferred[IO, Unit]
          _ <- store(root, Open.Create).use { s =>
            val p =
              peer(List(Event.Forward(a.envelope), Event.Forward(b.envelope)), anchor, Vector(a, b))
                .map { delegate =>
                  new Peer[IO]:
                    def intersect(candidates: Vector[ChainSync.Point]) =
                      delegate.intersect(candidates)
                    def next = delegate.next
                    def fetch(p: ChainSync.Point) =
                      if p == point(b) then waiting.complete(()) *> IO.never else delegate.fetch(p)
                }
            persistedResource(s, p, Policy(target = 2)).use { f =>
              for
                fiber <- f.run.start
                _ <- waiting.get
                _ <- fiber.cancel
              yield ()
            }
          }
          restored <- store(root, Open.Resume()).use(_.snapshot)
        yield assertEquals(restored.checkpoint.originals, Vector(a))
      }
      .unsafeToFuture()
  }
