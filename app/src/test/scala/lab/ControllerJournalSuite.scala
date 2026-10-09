// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
import lab.cbor.Bytes
import ControllerReducer.*
import ControllerJournalCodec.*
import LocalControllerJournal.{Controller, Mode, Phase as WritePhase, Faults}

class ControllerJournalSuite extends munit.FunSuite:
  def id(n: Int): Id = Id(f"$n%064x")
  def bytes(n: Int): Bytes = Bytes.fromArray(Array.fill[Byte](32)(n.toByte))
  def get[A](v: Either[String, A]): A = v.fold(fail(_), identity)
  def temp: Resource[IO, Binding] = Resource.make(IO.blocking {
    val root = Files.createTempDirectory("controller-journal-")
    Binding(
      root.resolve("controller").toString,
      root.resolve("checkpoint").toString,
      Store(id(1), id(2))
    )
  })(b =>
    IO.blocking {
      val stream = Files.walk(Path.of(b.root).getParent)
      try stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
      finally stream.close()
    }
  )
  def resource(
      b: Binding,
      mode: Mode = Mode.Create,
      n: Int = 90,
      faults: Faults = LocalControllerJournal.NoFaults
  ) =
    LocalControllerJournal.resource[IO](b, mode, id(n), faults)
  def submit(
      c: Controller[IO],
      command: Command,
      claims: Vector[LocalDerivedCheckpoint.Claim] = Vector.empty
  ) =
    c.snapshot
      .flatMap(i => c.submit(Message(Input.Request(i.journal.revision, command), claims)))
      .map(get)
  def event(c: Controller[IO], e: Evidence) = c.submit(Message(Input.Completed(e))).map(get)
  def probe(c: Controller[IO]): IO[Lease] = for
    r <- submit(c, Command.ReserveLaunch(id(10), id(11)))
    l = r.journal.lease.get
    _ <- event(c, Evidence.ChildBound(l.epoch, l.session, l.launch, id(12)))
    _ <- event(c, Evidence.LockHeld(l.epoch, l.session, id(12)))
    _ <- event(c, Evidence.Inspected(l.epoch, l.session, Disk.Missing))
  yield l
  def full(b: Binding, l: Lease, gen: Long = 0, n: Int = 20): LocalDerivedCheckpoint.Claim =
    def raw(i: Id) = Bytes.fromArray(i.value.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
    LocalDerivedCheckpoint.Claim(
      LocalDerivedCheckpoint
        .Token(raw(b.store.id), raw(b.store.context), raw(l.session), gen, bytes(n)),
      b.format,
      b.profile,
      b.authority,
      bytes(21),
      bytes(22),
      BigInt(1),
      BigInt(1)
    )
  def rejected(r: LocalControllerJournal.Result): Unit = assert(
    r.reply.isInstanceOf[Reply.Rejected]
  )
  def reseal(p: Bytes): Bytes = Bytes(p.value ++ ClusterHeaderObservation.sha256(p).value)

  test("strict bounded journal codec pins paths policy and complete claim fields") {
    temp
      .use { b =>
        IO {
          val i = Image(b, Journal(0, 0, Selection.Dormant(b.store)), Vector.empty)
          val encoded = get(encode(i))
          assertEquals(get(decode(encoded, b)), i)
          assert(decode(encoded, b.copy(checkpoint = b.checkpoint + "-other")).isLeft)
          assert(decode(encoded, b.copy(profile = "other")).isLeft)
          assert(decode(Bytes(encoded.value.dropRight(1)), b).isLeft)
          val payload = Bytes(encoded.value.dropRight(32))
          (0 until payload.size)
            .foreach(n => assert(decode(reseal(Bytes(payload.value.take(n))), b).isLeft))
          assert(decode(reseal(Bytes(encoded.value.dropRight(32) :+ 0.toByte)), b).isLeft)
          assert(decode(Bytes.fromArray(new Array[Byte](MaxBytes + 1)), b).isLeft)
          assert(encode(i.copy(binding = b.copy(checkpoint = b.root + "/child"))).isLeft)
          assert(
            encode(
              i.copy(journal =
                i.journal.copy(selection =
                  Selection.Migrating(
                    id(3),
                    Claim(b.store, id(4), 0, id(5)),
                    Store(id(6), b.store.context),
                    None
                  )
                )
              )
            ).isLeft
          )
          val l = Lease(1, id(10), id(11), b.store, Phase.Probe, Some(id(12)), true)
          val f = full(b, l); val claim = project(f)
          val active = Image(b, Journal(1, 1, Selection.Active(claim)), Vector(f))
          val raw = get(encode(active))
          assertEquals(get(decode(raw, b)).claims, Vector(f))
          assert(encode(active.copy(claims = Vector(f, f))).isLeft)
          assert(encode(active.copy(claims = Vector(f.copy(profile = "wrong")))).isLeft)
          assert(encode(active.copy(claims = Vector(f.copy(anchorId = Bytes.empty)))).isLeft)
          assert(updated(active, active.journal, Vector(f.copy(finalId = bytes(99)))).isLeft)
        }
      }
      .unsafeToFuture()
  }
  test("message codec round trips bounded supported requests and rejects force forgery") {
    temp
      .use { b =>
        IO {
          val l = Lease(1, id(10), id(11), b.store, Phase.Launching)
          val f = full(b, l); val c = project(f); val o = Operation(id(30), 1, l.session, None, c)
          val messages = Vector(
            Message(Input.Request(0, Command.ReserveLaunch(l.session, l.launch))),
            Message(Input.Request(1, Command.Begin(o)), Vector(f)),
            Message(Input.Request(1, Command.Installed(o))),
            Message(Input.Request(1, Command.Activate)),
            Message(Input.Request(1, Command.Retire(id(31)))),
            Message(Input.Completed(Evidence.ChildBound(1, l.session, l.launch, id(12)))),
            Message(Input.Completed(Evidence.NoMatchingChild(1, l.session, l.launch))),
            Message(
              Input.Completed(
                Evidence.OwnershipEnded(1, l.session, l.launch, OwnershipEnd.Absent(id(40)))
              )
            ),
            Message(
              Input.Completed(
                Evidence.OwnershipEnded(
                  1,
                  l.session,
                  l.launch,
                  OwnershipEnd.Exited(id(12), id(40), id(41))
                )
              )
            ),
            Message(Input.Completed(Evidence.LockHeld(1, l.session, id(12)))),
            Message(Input.Completed(Evidence.Inspected(1, l.session, Disk.Missing))),
            Message(Input.Completed(Evidence.Inspected(1, l.session, Disk.Present(c)))),
            Message(Input.Completed(Evidence.Verified(1, l.session, c))),
            Message(Input.Completed(Evidence.Failed(1, l.session, Stage.Verify, Some(c))))
          )
          messages.foreach(m => assertEquals(get(decodeMessage(get(encodeMessage(m)))), m))
          val beginPayload = Bytes(get(encodeMessage(messages(1))).value.dropRight(32))
          (0 until beginPayload.size)
            .foreach(n => assert(decodeMessage(reseal(Bytes(beginPayload.value.take(n)))).isLeft))
          val ticket =
            Ticket(id(90), 1, l.session, Journal(1, 1, Selection.Dormant(b.store), Some(l)))
          assert(encodeMessage(Message(Input.Completed(Evidence.Forced(ticket)))).isLeft)
          assert(encodeMessage(Message(Input.Completed(Evidence.ForceUncertain(ticket)))).isLeft)
          assert(
            encodeMessage(Message(Input.Request(1, Command.BeginMigration(id(33), b.store)))).isLeft
          )
          assert(encodeMessage(Message(Input.Request(1, Command.SelectDestination(id(33))))).isLeft)
          assert(encodeMessage(Message(Input.Request(1, Command.Begin(o)))).isLeft)
          assert(
            decodeMessage(
              reseal(Bytes(get(encodeMessage(messages.head)).value.dropRight(32) :+ 0.toByte))
            ).isLeft
          )
        }
      }
      .unsafeToFuture()
  }
  test("real persisted forces retain complete publication claim and reopen without permissions") {
    temp
      .use { b =>
        resource(b).use { c =>
          for
            l <- probe(c)
            f = full(b, l); op = Operation(id(30), l.epoch, l.session, None, project(f))
            prepared <- submit(c, Command.Begin(op), Vector(f))
            _ = assertEquals(prepared.reply, Reply.Prepared(op))
            duplicateBad <- c.submit(
              Message(
                Input.Request(prepared.journal.revision, Command.Begin(op)),
                Vector(f.copy(anchorId = bytes(99)))
              )
            )
            _ = assert(duplicateBad.isLeft)
            duplicate <- submit(c, Command.Begin(op), Vector(f))
            _ = assertEquals(duplicate.reply, Reply.Prepared(op))
            installed <- submit(c, Command.Installed(op))
            _ = assert(installed.effects.exists(_.isInstanceOf[Effect.Verify]))
            committed <- event(c, Evidence.Verified(l.epoch, l.session, op.after))
            _ = assertEquals(committed.reply, Reply.Committed(op))
            committedBad <- c.submit(
              Message(
                Input.Request(committed.journal.revision, Command.Begin(op)),
                Vector(f.copy(finalId = bytes(99)))
              )
            )
            _ = assert(committedBad.isLeft)
            serving <- submit(c, Command.Activate)
            _ = assert(serving.reply.isInstanceOf[Reply.Serving])
            snapshot <- c.snapshot
            disk <- IO.blocking(
              Bytes.fromArray(Files.readAllBytes(Path.of(b.root).resolve("controller.bin")))
            )
            _ = assertEquals(get(decode(disk, b)), snapshot)
            _ = assertEquals(snapshot.claims, Vector(f))
          yield ()
        } *> resource(b, Mode.Resume, 91).use { c =>
          for
            snapshot <- c.snapshot
            _ = assertEquals(snapshot.claims.size, 1)
            activation <- submit(c, Command.Activate)
            _ = rejected(activation)
            _ <- submit(c, Command.Retire(id(80)))
          yield ()
        }
      }
      .unsafeToFuture()
  }
  test("every publication phase fault poisons and restart selects only strict primary") {
    WritePhase.values.toVector
      .traverse_ { phase =>
        temp.use { b =>
          val armed = new AtomicBoolean(false)
          val faults = new Faults:
            override def at(p: WritePhase): Unit =
              if armed.get && p == phase then throw new java.io.IOException("injected")
          resource(b, faults = faults).use { c =>
            for
              _ <- IO(armed.set(true))
              outcome <- c.submit(Message(Input.Request(0, Command.ReserveLaunch(id(10), id(11)))))
              _ = assert(outcome.isLeft, s"$phase must not grant launch")
              again <- c.submit(Message(Input.Request(0, Command.ReserveLaunch(id(50), id(51)))))
              _ = assert(again.isLeft, s"$phase must poison")
            yield ()
          } *> resource(b, Mode.Resume, 91).use { c =>
            c.snapshot.map { i =>
              assert(Set(0L, 1L)(i.journal.revision))
              assert(!Files.exists(Path.of(b.root).resolve("controller.tmp")))
            }
          }
        }
      }
      .unsafeToFuture()
  }
  test("stale callback concurrency cannot observe pre-force permission or race next state") {
    temp
      .use { b =>
        val armed = new AtomicBoolean(false); val entered = new CountDownLatch(1);
        val release = new CountDownLatch(1)
        val returned = new AtomicBoolean(false)
        val faults = new Faults:
          override def at(p: WritePhase): Unit =
            if armed.get && p == WritePhase.BeforeDirectoryForce then
              entered.countDown(); require(release.await(10, TimeUnit.SECONDS), "test timeout")
        resource(b, faults = faults).use { c =>
          (for
            _ <- IO(armed.set(true))
            first <- c
              .submit(Message(Input.Request(0, Command.ReserveLaunch(id(10), id(11)))))
              .flatTap(_ => IO(returned.set(true)))
              .start
            _ <- IO.blocking(assert(entered.await(5, TimeUnit.SECONDS)))
            _ = assert(!returned.get)
            stale <- c
              .submit(Message(Input.Completed(Evidence.ChildBound(1, id(99), id(11), id(12)))))
              .start
            competing <- c
              .submit(Message(Input.Request(0, Command.ReserveLaunch(id(50), id(51)))))
              .start
            _ <- IO { armed.set(false); release.countDown() }
            accepted <- first.joinWithNever
            _ = assert(get(accepted).effects.exists(_.isInstanceOf[Effect.LaunchExact]))
            old <- stale.joinWithNever
            other <- competing.joinWithNever
            _ = rejected(get(old)); _ = rejected(get(other))
            snapshot <- c.snapshot
            _ = assertEquals(snapshot.journal.lease.get.session, id(10))
            _ = assertEquals(snapshot.journal.revision, 1L)
          yield ()).guarantee(IO(release.countDown()))
        }
      }
      .unsafeToFuture()
  }
  test("cancellation waits for persistence and close keeps ownership until the gate completes") {
    temp
      .use { b =>
        val armed = new AtomicBoolean(false); val entered = new CountDownLatch(1);
        val release = new CountDownLatch(1)
        val closed = new AtomicBoolean(false)
        val faults = new Faults:
          override def at(p: WritePhase): Unit = if armed.get && p == WritePhase.BeforeFileForce
          then
            entered.countDown(); require(release.await(10, TimeUnit.SECONDS), "test timeout")
        resource(b, faults = faults).allocated.flatMap { (c, close) =>
          (for
            _ <- IO(armed.set(true))
            writer <- c
              .submit(Message(Input.Request(0, Command.ReserveLaunch(id(10), id(11)))))
              .start
            _ <- IO.blocking(assert(entered.await(5, TimeUnit.SECONDS)))
            cancel <- writer.cancel.start
            closing <- (close *> IO(closed.set(true))).start
            _ <- IO.sleep(30.millis)
            _ = assert(!closed.get)
            ownership <- resource(b, Mode.Resume, 91).use(_ => IO.unit).attempt
            _ = assert(ownership.isLeft)
            _ <- IO { armed.set(false); release.countDown() }
            _ <- cancel.joinWithNever
            _ <- closing.joinWithNever
            _ <- resource(b, Mode.Resume, 92).use(
              _.snapshot.map(i => assertEquals(i.journal.revision, 1L))
            )
          yield ()).guarantee(IO(release.countDown()) *> close)
        }
      }
      .unsafeToFuture()
  }
  test("exclusive ownership immutable mapping strict resume and exact disk CAS fail closed") {
    temp
      .use { b =>
        resource(b).use { c =>
          for
            competing <- resource(b, Mode.Resume, 91).use(_ => IO.unit).attempt
            _ = assert(competing.isLeft)
            _ <- IO.blocking(
              Files.write(Path.of(b.root).resolve("controller.bin"), Array[Byte](1, 2, 3))
            )
            result <- c.submit(Message(Input.Request(0, Command.ReserveLaunch(id(10), id(11)))))
            _ = assert(result.isLeft)
          yield ()
        } *> resource(b, Mode.Resume, 92).use(_ => IO.unit).attempt.map(r => assert(r.isLeft))
      }
      .unsafeToFuture()
  }
  test("resume cannot promote an orphan staging image or accept wrong pinned store") {
    temp
      .use { b =>
        resource(b).use(_ => IO.unit) *> IO.blocking {
          val root = Path.of(b.root)
          Files.move(root.resolve("controller.bin"), root.resolve("controller.tmp"))
        } *> resource(b, Mode.Resume, 91).use(_ => IO.unit).attempt.map(r => assert(r.isLeft))
      }
      .unsafeToFuture()
  }

  test("partial writes succeed while stalled writes and unsupported atomic replacement poison") {
    (temp.use { b =>
      val partial = new Faults:
        override def write(c: java.nio.channels.FileChannel, buffer: java.nio.ByteBuffer): Int =
          val limit = buffer.limit()
          buffer.limit(math.min(limit, buffer.position() + 7))
          try c.write(buffer)
          finally buffer.limit(limit)
      resource(b, faults = partial).use(c =>
        submit(c, Command.ReserveLaunch(id(10), id(11))).map(r =>
          assert(r.effects.exists(_.isInstanceOf[Effect.LaunchExact]))
        )
      )
    } *> Vector(false, true).traverse_ { atomic =>
      temp.use { b =>
        val armed = new AtomicBoolean(false)
        val fault = new Faults:
          override def write(c: java.nio.channels.FileChannel, buffer: java.nio.ByteBuffer): Int =
            if armed.get && !atomic then 0 else c.write(buffer)
          override def replace(from: Path, to: Path): Unit =
            if armed.get && atomic then
              throw new java.nio.file.AtomicMoveNotSupportedException(
                from.toString,
                to.toString,
                "injected"
              )
            else super.replace(from, to)
        resource(b, faults = fault).use { c =>
          for
            _ <- IO(armed.set(true))
            failed <- c.submit(Message(Input.Request(0, Command.ReserveLaunch(id(10), id(11)))))
            _ = assert(failed.isLeft)
            again <- c.submit(Message(Input.Request(0, Command.ReserveLaunch(id(50), id(51)))))
            _ = assert(again.isLeft)
          yield ()
        }
      }
    }).unsafeToFuture()
  }
  test("pinned mapping and symlink paths reject before returning a controller") {
    temp
      .use { b =>
        for
          _ <- resource(b).use(_ => IO.unit)
          wrong <- resource(b.copy(store = b.store.copy(id = id(99))), Mode.Resume, 91)
            .use(_ => IO.unit)
            .attempt
          _ = assert(wrong.isLeft)
          path <- resource(b.copy(checkpoint = b.checkpoint + "-other"), Mode.Resume, 91)
            .use(_ => IO.unit)
            .attempt
          _ = assert(path.isLeft)
          _ <- IO.blocking(Files.createSymbolicLink(Path.of(b.checkpoint), Path.of(b.root)))
          linked <- resource(b, Mode.Resume, 91).use(_ => IO.unit).attempt
          _ = assert(linked.isLeft)
        yield ()
      }
      .unsafeToFuture()
  }

  test("FIFO lock primary and staging entries are rejected before any stream open") {
    Vector("lock", "controller.bin", "controller.tmp")
      .traverse_ { name =>
        Vector(Mode.Create, Mode.Resume).traverse_ { mode =>
          temp.use { b =>
            for
              _ <- IO.blocking {
                val root = Path.of(b.root); Files.createDirectory(root)
                if name != "lock" then Files.createFile(root.resolve("lock"))
                val process = new ProcessBuilder("mkfifo", root.resolve(name).toString).start()
                assert(process.waitFor(5, TimeUnit.SECONDS), "mkfifo timeout")
                assertEquals(process.exitValue(), 0)
              }
              result <- resource(b, mode).use(_ => IO.unit).attempt.timeout(2.seconds)
              _ = assert(result.isLeft, s"FIFO $name / $mode")
            yield ()
          }
        }
      }
      .unsafeToFuture()
  }
  test("explicit Create retries parent force after prior directory creation failed to force") {
    temp
      .use { b =>
        val calls = new java.util.concurrent.atomic.AtomicInteger(0)
        val faults = new Faults:
          override def forceParent(path: Path): Unit =
            if calls.incrementAndGet() == 1 then
              throw new java.io.IOException("parent force failed")
            else super.forceParent(path)
        for
          first <- resource(b, faults = faults).use(_ => IO.unit).attempt
          _ = assert(first.isLeft)
          _ = assert(Files.isDirectory(Path.of(b.root)))
          _ = assert(!Files.exists(Path.of(b.root).resolve("controller.bin")))
          _ <- resource(b, faults = faults).use(
            _.snapshot.map(i => assertEquals(i.journal.revision, 0L))
          )
          _ = assertEquals(calls.get(), 2)
          _ <- resource(b, Mode.Resume, 91).use(
            _.snapshot.map(i => assertEquals(i.journal.selection, Selection.Dormant(b.store)))
          )
        yield ()
      }
      .unsafeToFuture()
  }
