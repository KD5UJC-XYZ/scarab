// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.chain.CardanoBlockIndex
import scala.concurrent.duration.*

class FetcherSuite extends munit.FunSuite:
  private val fixtures =
    if Files.exists(Path.of("fixtures/chain-fetch")) then Path.of("fixtures/chain-fetch")
    else Path.of("../fixtures/chain-fetch")
  private def config(era: String = "shelley") = LocalConfig.load(fixtures.resolve(s"$era.tsv"))
  private def bytes(i: Int, era: String = "shelley"): Bytes =
    Bytes.fromArray(Files.readAllBytes(fixtures.resolve(s"$era/$era-$i.cbor")))
  private def block(i: Int) = CardanoBlockIndex.inspect(bytes(i), 1048576).toOption.get
  private def temp: IO[Path] = IO.blocking(Files.createTempDirectory("chain-fetch-test-"))
  private def limits(
      blocks: Int = 64,
      input: Long = 8388608,
      stored: Long = 8388608,
      seconds: Int = 120
  ) =
    Limits.checked(blocks, 1048576, input, stored, 256, seconds).toOption.get
  private def spec(
      count: Option[Int] = Some(4),
      end: Option[Point] = None,
      after: Point = config().spec.after,
      caps: Limits = limits()
  ) = FetchSpec.checked(after, count, end, caps).toOption.get
  private def scripted(events: List[SourceEvent], id: SourceIdentity): IO[BlockSource[IO]] =
    Ref.of[IO, List[SourceEvent]](events).map { ref =>
      new BlockSource[IO]:
        def identity = id
        def inputBytes = IO.pure(0L)
        def open = Resource.pure[IO, BlockCursor[IO]](
          new BlockCursor[IO]:
            def next = ref.modify {
              case head :: tail => tail -> head
              case Nil          => Nil -> SourceEvent.End
            }
        )
    }
  private def checkError(result: Either[Throwable, ?], code: Int): Unit = result match
    case Left(e: FetchError) => assertEquals(e.code, code)
    case other               => fail(s"expected FetchError($code), got $other")

  List("shelley", "allegra", "babbage").foreach { era =>
    test(s"real $era acquisition, verified inspection and byte-identical resume") {
      (for
        root <- temp
        cfg = config(era)
        source <- LocalBlockSource.open[IO](cfg)
        first <- NioSegmentStore
          .resource[IO](root, cfg.spec, source.identity, false)
          .use(s => Fetch.run(cfg.spec, source, s))
        inspected <- NioSegmentStore.inspect[IO](root, cfg.spec.limits)
        next <- LocalBlockSource.open[IO](cfg)
        second <- NioSegmentStore
          .resource[IO](root, cfg.spec, next.identity, true)
          .use(s => Fetch.run(cfg.spec, next, s))
      yield
        assertEquals(first.reason, "countReached")
        assertEquals(first.snapshot, inspected)
        assertEquals(first.snapshot, second.snapshot)
        assertEquals(inspected.records.size, 4)
        inspected.records.zipWithIndex.foreach { (r, i) =>
          assertEquals(
            Bytes.fromArray(Files.readAllBytes(root.resolve(s"objects/${r.rawHash}.cbor"))),
            bytes(i, era)
          )
        }
      ).unsafeToFuture()
    }
  }
  test("inclusive end wins when count and end coincide; count may win sooner") {
    (for
      cfg <- IO(config())
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      outcomes <- List(
        spec(Some(2), Some(Point.of(block(1)))),
        spec(Some(1), Some(Point.of(block(2))))
      ).traverse { selection =>
        for
          root <- temp
          source <- scripted((0 until 4).map(i => SourceEvent.Raw(bytes(i))).toList, id)
          result <- NioSegmentStore
            .resource[IO](root, selection, id, false)
            .use(s => Fetch.run(selection, source, s))
        yield result.reason
      }
    yield assertEquals(outcomes, List("endReached", "countReached"))).unsafeToFuture()
  }
  test("exact interior anchor is found by bounded scan") {
    (for
      root <- temp
      cfg = config()
      source <- LocalBlockSource.open[IO](cfg)
      selection = spec(Some(2), after = Point.of(block(1)))
      result <- NioSegmentStore
        .resource[IO](root, selection, source.identity, false)
        .use(s => Fetch.run(selection, source, s))
    yield assertEquals(
      result.snapshot.records.map(_.point),
      Vector(Point.of(block(2)), Point.of(block(3)))
    )).unsafeToFuture()
  }
  test("missing anchor never falls back; fork and skipped parent are fatal") {
    (for
      id <- LocalBlockSource.open[IO](config()).map(_.identity)
      results <- List(
        (
          spec(after = Point.parse("4492800:" + "a" * 64).toOption.get),
          List(SourceEvent.Raw(bytes(0)), SourceEvent.End)
        ),
        (spec(), List(SourceEvent.Raw(bytes(1)))),
        (spec(), List(SourceEvent.Raw(bytes(0)), SourceEvent.Rollback(id.predecessor)))
      ).traverse { (selection, events) =>
        for
          root <- temp
          source <- scripted(events, id)
          result <- NioSegmentStore
            .resource[IO](root, selection, id, false)
            .use(s => Fetch.run(selection, source, s))
            .attempt
        yield result
      }
    yield results.foreach(checkError(_, 5))).unsafeToFuture()
  }
  test("rollback at current point is harmless; exhaustion is incomplete") {
    (for
      root <- temp
      id <- LocalBlockSource.open[IO](config()).map(_.identity)
      source <- scripted(
        List(SourceEvent.Raw(bytes(0)), SourceEvent.Rollback(Point.of(block(0))), SourceEvent.End),
        id
      )
      selection = spec()
      result <- NioSegmentStore
        .resource[IO](root, selection, id, false)
        .use(s => Fetch.run(selection, source, s))
    yield
      assertEquals(result.reason, "sourceExhausted")
      assertEquals(result.snapshot.records.size, 1)
    ).unsafeToFuture()
  }
  NioSegmentStore.Phase.values.foreach { phase =>
    test(s"crash at $phase recovers exactly the last published pointer") {
      (for
        root <- temp
        cfg = config()
        id <- LocalBlockSource.open[IO](cfg).map(_.identity)
        _ <- NioSegmentStore.resource[IO](root, cfg.spec, id, false).use(_.append(block(0)))
        failed <- NioSegmentStore
          .resource[IO](
            root,
            cfg.spec,
            id,
            true,
            p => if p == phase then throw new RuntimeException("injected crash")
          )
          .use(_.append(block(1)))
          .attempt
        before <- NioSegmentStore.inspect[IO](root, cfg.spec.limits)
        source <- LocalBlockSource.open[IO](cfg)
        complete <- NioSegmentStore
          .resource[IO](root, cfg.spec, id, true)
          .use(s => Fetch.run(cfg.spec, source, s))
      yield
        assert(failed.isLeft)
        assertEquals(
          before.records.size,
          if phase == NioSegmentStore.Phase.CheckpointInstalled then 2 else 1
        )
        assertEquals(complete.snapshot.records.size, 4)
      ).unsafeToFuture()
    }
  }
  test("initialization interrupted before checkpoint resumes as empty") {
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      failure <- NioSegmentStore
        .resource[IO](
          root,
          cfg.spec,
          id,
          false,
          p =>
            if p == NioSegmentStore.Phase.CheckpointForced then
              throw new RuntimeException("init failure")
        )
        .use(_.snapshot)
        .attempt
      recovered <- NioSegmentStore.resource[IO](root, cfg.spec, id, true).use(_.snapshot)
    yield
      assert(failure.isLeft)
      assertEquals(recovered.records.size, 0)
    ).unsafeToFuture()
  }
  test("released handles cannot read or append; concurrent writer is refused") {
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      pair <- NioSegmentStore.resource[IO](root, cfg.spec, id, false).allocated
      (store, release) = pair
      locked <- NioSegmentStore.resource[IO](root, cfg.spec, id, true).use(_.snapshot).attempt
      _ <- release
      closedRead <- store.snapshot.attempt
      closedWrite <- store.append(block(0)).attempt
    yield List(locked, closedRead, closedWrite).foreach(checkError(_, 6))).unsafeToFuture()
  }
  test("corrupt object and incompatible selection reject resume") {
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      snapshot <- NioSegmentStore.resource[IO](root, cfg.spec, id, false).use(_.append(block(0)))
      incompatible <- NioSegmentStore
        .resource[IO](root, spec(Some(3)), id, true)
        .use(_.snapshot)
        .attempt
      _ <- IO.blocking(
        Files.write(root.resolve(s"objects/${snapshot.records.head.rawHash}.cbor"), Array[Byte](0))
      )
      corrupt <- NioSegmentStore.resource[IO](root, cfg.spec, id, true).use(_.snapshot).attempt
    yield List(incompatible, corrupt).foreach(checkError(_, 6))).unsafeToFuture()
  }
  test("unrelated files, source path traversal and symlinks are rejected") {
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      _ <- IO.blocking(Files.writeString(root.resolve("keep.txt"), "unrelated"))
      unrelated <- NioSegmentStore.resource[IO](root, cfg.spec, id, false).use(_.snapshot).attempt
      _ <- IO.blocking(Files.createSymbolicLink(root.resolve("link"), fixtures.toAbsolutePath))
      link <- IO.blocking(LocalFiles.child(root, "link/shelley.tsv")).attempt
      traversal <- IO.blocking(LocalFiles.child(root, "../outside")).attempt
    yield
      checkError(unrelated, 6)
      checkError(link, 2)
      checkError(traversal, 2)
      assertEquals(Files.readString(root.resolve("keep.txt")), "unrelated")
    ).unsafeToFuture()
  }
  test("strict config rejects duplicates, unknown fields, absent selection, overflow and origin") {
    val text = Files.readString(fixtures.resolve("shelley.tsv"))
    val invalid = List(
      text + "count\t4\n",
      text + "extra\tx\n",
      text.replace("count\t4", "count\t-"),
      text.replace("maxBlocks\t64", "maxBlocks\t2147483648"),
      text.replace(config().spec.after.encoded, "origin"),
      text.replace("forkPolicy\tfail", "forkPolicy\trewind"),
      text.replace("shelley/source.tsv", "../source.tsv")
    )
    invalid.foreach(s => intercept[FetchError](LocalConfig.parse(s, fixtures)))
    assert(Point.parse("18446744073709551616:" + "a" * 64).isLeft)
    assert(Point.parse("18446744073709551615:" + "a" * 64).isRight)
  }
  test("object and storage budgets leave inspectable checkpoints") {
    (for
      id <- LocalBlockSource.open[IO](config()).map(_.identity)
      reasons <- List(limits(blocks = 1), limits(stored = 1600)).traverse { cap =>
        for
          root <- temp
          selection = spec(caps = cap)
          source <- scripted((0 until 4).map(i => SourceEvent.Raw(bytes(i))).toList, id)
          result <- NioSegmentStore
            .resource[IO](root, selection, id, false)
            .use(s => Fetch.run(selection, source, s))
          _ <- NioSegmentStore.inspect[IO](root, limits())
        yield result.reason
      }
    yield assertEquals(reasons, List("objectBudget", "storageBudget"))).unsafeToFuture()
  }
  test("timeout of a suspended source uses monotonic time and closes its resource") {
    val program = for
      closed <- Ref.of[IO, Boolean](false)
      id <- IO(SourceIdentity.checked("a" * 64, "synthetic", config().spec.after).toOption.get)
      current <- Ref.of[IO, Snapshot](Snapshot(Vector.empty, "b" * 64))
      store = new SegmentStore[IO]:
        def selectionIdentity = spec(caps = limits(seconds = 1)).identity
        def sourceIdentity = id
        def snapshot = current.get
        def append(b: CardanoBlockIndex.IndexedBlock) =
          IO.raiseError(new RuntimeException("unused"))
      source = new BlockSource[IO]:
        def identity = id
        def inputBytes = IO.pure(0L)
        def open = Resource.make(
          IO.pure(
            new BlockCursor[IO]:
              def next = IO.never[SourceEvent]
          )
        )(_ => closed.set(true))
      result <- Fetch.run(spec(caps = limits(seconds = 1)), source, store)
      released <- closed.get
    yield
      assertEquals(result.reason, "timeBudget")
      assert(released)
    TestControl.executeEmbed(program).unsafeToFuture()
  }
  test("cancellation while source is blocked releases store and resumes") {
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      entered <- Deferred[IO, Unit]
      source = new BlockSource[IO]:
        def identity = id
        def inputBytes = IO.pure(0L)
        def open = Resource.pure[IO, BlockCursor[IO]](
          new BlockCursor[IO]:
            def next = entered.complete(()).void *> IO.never[SourceEvent]
        )
      fiber <- NioSegmentStore
        .resource[IO](root, cfg.spec, id, false)
        .use(s => Fetch.run(cfg.spec, source, s))
        .start
      _ <- entered.get
      _ <- fiber.cancel
      local <- LocalBlockSource.open[IO](cfg)
      result <- NioSegmentStore
        .resource[IO](root, cfg.spec, id, true)
        .use(s => Fetch.run(cfg.spec, local, s))
    yield assertEquals(result.snapshot.records.size, 4)).unsafeToFuture()
  }

  test("inspection never repairs interrupted initialization") {
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      _ <- NioSegmentStore
        .resource[IO](
          root,
          cfg.spec,
          id,
          false,
          p =>
            if p == NioSegmentStore.Phase.CheckpointForced then
              throw new RuntimeException("init failure")
        )
        .use(_.snapshot)
        .attempt
      inspected <- NioSegmentStore.inspect[IO](root, cfg.spec.limits).attempt
    yield
      checkError(inspected, 6)
      assert(!Files.exists(root.resolve("checkpoint")))
    ).unsafeToFuture()
  }
  test("cancel during an admitted commit waits for publication and releases lock") {
    val entered = new java.util.concurrent.CountDownLatch(1)
    val proceed = new java.util.concurrent.CountDownLatch(1)
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      _ <- NioSegmentStore.resource[IO](root, cfg.spec, id, false).use(_.snapshot)
      writer <- NioSegmentStore
        .resource[IO](
          root,
          cfg.spec,
          id,
          true,
          p =>
            if p == NioSegmentStore.Phase.ObjectForced then
              entered.countDown()
              proceed.await()
        )
        .use(_.append(block(0)))
        .start
      _ <- IO.blocking(entered.await())
      cancelStarted <- Deferred[IO, Unit]
      canceler <- (cancelStarted.complete(()).void *> writer.cancel).start
      _ <- cancelStarted.get *> IO.cede
      _ <- IO.blocking(proceed.countDown())
      _ <- canceler.joinWithNever
      inspected <- NioSegmentStore.inspect[IO](root, cfg.spec.limits)
    yield assertEquals(inspected.records.size, 1)).unsafeToFuture()
  }
  test("tiny initialization budget writes no metadata and can recover when raised") {
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      small = spec(caps = limits(stored = 1))
      failed <- NioSegmentStore.resource[IO](root, small, id, false).use(_.snapshot).attempt
      _ <- IO {
        checkError(failed, 6)
        assert(!Files.exists(root.resolve("identity")))
        assertEquals(Files.size(root.resolve("lock")), 0L)
      }
      recovered <- NioSegmentStore.resource[IO](root, cfg.spec, id, true).use(_.snapshot)
    yield assertEquals(recovered.records.size, 0)).unsafeToFuture()
  }
  test(
    "changed pinned source, manifest corruption, orphan quotas and direct append limits fail closed"
  ) {
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      selection = spec(Some(1))
      snapshot <- NioSegmentStore.resource[IO](root, selection, id, false).use { store =>
        store.append(block(0)).flatTap(_ => store.append(block(1)).attempt.map(checkError(_, 5)))
      }
      changed = SourceIdentity.checked("b" * 64, id.label, id.predecessor).toOption.get
      incompatible <- NioSegmentStore
        .resource[IO](root, selection, changed, true)
        .use(_.snapshot)
        .attempt
      _ <- IO.blocking(
        Files.writeString(
          root.resolve("manifests").resolve(snapshot.manifestHash + ".manifest"),
          "corrupt"
        )
      )
      corrupt <- NioSegmentStore.inspect[IO](root, limits()).attempt
      other <- temp
      _ <- NioSegmentStore.resource[IO](other, cfg.spec, id, false).use(_.snapshot)
      _ <- IO.blocking(Files.write(other.resolve("staging/object.tmp"), new Array[Byte](2000)))
      quota <- NioSegmentStore
        .resource[IO](other, spec(caps = limits(stored = 1600)), id, true)
        .use(_.snapshot)
        .attempt
    yield List(incompatible, corrupt, quota).foreach(checkError(_, 6))).unsafeToFuture()
  }
  test("local source verifies file hashes and input admission budget before indexing") {
    (for
      root <- temp
      cfgText = Files.readString(fixtures.resolve("shelley.tsv"))
      manifestBytes = Files.readAllBytes(fixtures.resolve("shelley/source.tsv"))
      _ <- IO.blocking {
        Files.createDirectory(root.resolve("shelley"))
        Files.write(root.resolve("shelley/source.tsv"), manifestBytes)
        (0 until 4).foreach(i =>
          Files.write(root.resolve(s"shelley/shelley-$i.cbor"), bytes(i).toArray)
        )
      }
      small = LocalConfig.parse(
        cfgText.replace("maxInputBytes\t8388608", s"maxInputBytes\t${manifestBytes.length}"),
        root
      )
      budgetSource <- LocalBlockSource.open[IO](small)
      budget <- budgetSource.open.use(_.next).attempt
      cfg = LocalConfig.parse(cfgText, root)
      source <- LocalBlockSource.open[IO](cfg)
      _ <- IO.blocking(Files.write(root.resolve("shelley/shelley-0.cbor"), Array[Byte](0)))
      corrupt <- source.open.use(_.next).attempt
    yield
      checkError(budget, 3)
      checkError(corrupt, 5)
    ).unsafeToFuture()
  }

  test("reusable runner rejects wrong source or selection binding before opening source") {
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      opened <- Ref.of[IO, Boolean](false)
      otherId = SourceIdentity.checked("c" * 64, id.label, id.predecessor).toOption.get
      other = new BlockSource[IO]:
        def identity = otherId
        def inputBytes = IO.pure(0L)
        def open = Resource.eval(
          opened
            .set(true)
            .as(
              new BlockCursor[IO]:
                def next = IO.pure(SourceEvent.End)
            )
        )
      pair <- NioSegmentStore.resource[IO](root, cfg.spec, id, false).use { store =>
        (
          Fetch.run(cfg.spec, other, store).attempt,
          Fetch.run(spec(Some(3)), other, store).attempt
        ).tupled
      }
      wasOpened <- opened.get
      snapshot <- NioSegmentStore.inspect[IO](root, cfg.spec.limits)
    yield
      checkError(pair._1, 6)
      checkError(pair._2, 6)
      assert(!wasOpened)
      assertEquals(snapshot.records.size, 0)
    ).unsafeToFuture()
  }

  test("missing committed object and malformed checkpoint are corrupt output errors") {
    (for
      root <- temp
      cfg = config()
      id <- LocalBlockSource.open[IO](cfg).map(_.identity)
      snapshot <- NioSegmentStore.resource[IO](root, cfg.spec, id, false).use(_.append(block(0)))
      _ <- IO.blocking(Files.delete(root.resolve(s"objects/${snapshot.records.head.rawHash}.cbor")))
      missing <- NioSegmentStore.inspect[IO](root, cfg.spec.limits).attempt
      _ <- IO.blocking(Files.writeString(root.resolve("checkpoint"), "not a checkpoint"))
      malformed <- NioSegmentStore.resource[IO](root, cfg.spec, id, true).use(_.snapshot).attempt
    yield List(missing, malformed).foreach(checkError(_, 6))).unsafeToFuture()
  }

  test("terminal JSON escapes provider diagnostics and metadata") {
    val text = FetchResult("bad\"line\n\t\\", Snapshot(Vector.empty, "a\"b"), 0L).json
    assert(text.contains("\"reason\":\"bad\\\"line\\n\\t\\\\\""))
    assert(text.contains("\"manifestSha256\":\"a\\\"b\""))
    assert(!text.contains('\n'))
    assert(FetchResult("\u0001", Snapshot(Vector.empty, ""), 0L).json.contains("\\u0001"))
  }

  test("v1 persisted records support all six indexed eras and reject Byron or unknown labels") {
    val original = Record.of(block(0))
    CardanoBlockIndex.supportedEraLabels.foreach { era =>
      val record = original.copy(era = era)
      assertEquals(Record.parse(record.encoded), record)
    }
    Vector("byron", "dijkstra", "Mary", "").foreach { era =>
      intercept[FetchError](Record.parse(original.copy(era = era).encoded))
    }
  }
