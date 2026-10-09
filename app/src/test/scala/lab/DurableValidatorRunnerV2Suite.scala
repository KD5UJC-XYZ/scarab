// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import lab.cbor.Bytes
import lab.network.ChainSync

class DurableValidatorRunnerV2Suite extends munit.FunSuite:
  import ValidatorTransitions.*
  import BoundedChainFollower.{Event, Original, Peer}
  import BoundedValidatorRunner.{Policy, Stop as RunnerStop}
  import DurableValidatorRunner.Stop
  import ControllerReducer.{Id, Store}
  import ControllerJournalCodec.Binding
  private def get[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def point(o: Original): ChainSync.Point =
    val h = get(ReferenceCaptureCommand.header(o.envelope))
    ChainSync.Point.Block(get(ChainSync.UInt64.from(h.slot)), h.hash)
  private def temporary =
    Resource.make(IO.blocking(Files.createTempDirectory("durable-runner-v2-").toRealPath())) { p =>
      IO.blocking {
        require(p.getParent == Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
        require(p.getFileName.toString.startsWith("durable-runner-v2-"))
        val paths = Files.walk(p)
        try
          paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
        finally paths.close()
      }
    }
  private def source(os: Vector[Original]): Resource[IO, Peer[IO]] =
    Resource.eval(Ref.of[IO, List[Original]](os.toList)).map { queue =>
      new Peer[IO]:
        def intersect(offered: Vector[ChainSync.Point]) = IO.pure(offered.head)
        def next = queue.modify {
          case head :: tail => (tail, Event.Forward(head.envelope))
          case Nil          => (Nil, Event.Await)
        }
        def fetch(p: ChainSync.Point) = IO(os.find(o => point(o) == p).get.block)
    }
  private def policy(target: Int = 12, capacity: Int = 3) =
    Policy(
      target = target,
      rollbackCapacity = capacity,
      advanceWindow = true,
      duration = 30.seconds
    )
  private def noPeer = Resource.eval(IO.raiseError[Peer[IO]](new AssertionError("peer acquired")))

  test("sustained targets outside 9..256 and invalid capacities reject before acquisition") {
    Ref
      .of[IO, Int](0)
      .flatMap { touched =>
        val backend = Resource.eval(
          touched.update(_ + 1) *> IO
            .raiseError[Backend[IO]](new AssertionError("backend acquired"))
        )
        Vector(policy(8), policy(257), policy(capacity = 0), policy(capacity = 9)).traverse_ { p =>
          DurableValidatorRunner
            .resource[IO](null, backend, noPeer, p, _ => IO.unit)
            .use(_.run)
            .attempt
            .map(r => assert(r.isLeft))
        } *> touched.get.map(n => assertEquals(n, 0))
      }
      .unsafeToFuture()
  }

  // Existing signed private captures only; no acquisition, keys or live reference process.
  sys.env.get("COHERENT_WINDOW_EVIDENCE").foreach { location =>
    val root = Path.of(location)
    def context = get(SequenceInput.load(root))
    def originals =
      val in = Files.newInputStream(root.resolve("scala-sequence-capture.md"))
      val raw =
        try in.readNBytes(28 * 1024 * 1024 + 1)
        finally in.close()
      assert(raw.length <= 28 * 1024 * 1024)
      val os = get(CoherentSequenceCommand.captures(Bytes.fromArray(raw), 12))
      assert(os.size >= 12, "twelve signed linked originals required")
      os
    def config(directory: Path, c: SequenceInput.Context, capacity: Int = 3) =
      CombinedLocalV2.Config(
        Binding(
          directory.resolve("controller").toString,
          directory.resolve("checkpoint").toString,
          Store(Id("7" * 64), Id(c.id.hex))
        ),
        capacity,
        20.seconds
      )
    def seed(c: SequenceInput.Context, os: Vector[Original]) =
      CombinedLocalV2.Bootstrap(c, os.take(3), point(os.head))

    test("V1 durable backend rejects sustained policy before peer acquisition") {
      temporary
        .use { directory =>
          val c = context
          val backend = durableCreate[IO](directory, c, 3, 2.seconds, _ => IO.unit)
          DurableValidatorRunner
            .resource[IO](c, backend, noPeer, policy(), _ => IO.unit)
            .use(_.run)
            .attempt
            .map(r => assert(r.left.exists(_.isInstanceOf[IllegalArgumentException])))
        }
        .unsafeToFuture()
    }
    test(
      "V2 cumulative target compacts separately, acknowledges full claims and resumes without peer"
    ) {
      temporary
        .use { directory =>
          val c = context; val os = originals; val cfg = config(directory, c)
          Ref.of[IO, Vector[String]](Vector.empty).flatMap { labels =>
            DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](cfg, seed(c, os)),
                source(os.slice(3, 12)),
                policy(),
                label => labels.update(_ :+ label)
              )
              .use(_.run)
              .flatMap { out =>
                for
                  seen <- labels.get
                  resumed <- DurableValidatorRunner
                    .resource[IO](c, combinedResume[IO](cfg), noPeer, policy(), _ => IO.unit)
                    .use(_.run)
                yield
                  assertEquals(out.reason, Stop.Completed(RunnerStop.TargetReached))
                  assertEquals(out.confirmed.state.depth, BigInt(12))
                  assertEquals(out.confirmed.state.revision, BigInt(12))
                  assertEquals(out.confirmed.state.compactedBlocks, BigInt(9))
                  assertEquals(out.confirmed.state.acquisition.size, 3)
                  assertEquals(out.events, 9)
                  assertEquals(
                    out.returnedBytes,
                    os.slice(3, 12).map(o => o.envelope.size.toLong + o.block.size).sum
                  )
                  assertEquals(seen.count(_ == "after-anchor-advance"), 8)
                  assertEquals(seen.count(_ == "after-publish"), 9)
                  assert(out.confirmed.confirmation.tokenOption.isEmpty)
                  assert(out.confirmed.confirmation.isInstanceOf[Confirmation.V2Acknowledged])
                  assertEquals(out.confirmed.confirmation.fullClaimOption.get.token.generation, 17L)
                  assertEquals(resumed.reason, Stop.Completed(RunnerStop.TargetReached))
                  assertEquals(resumed.events, 0)
                  assertEquals(resumed.confirmed.state.id, out.confirmed.state.id)
                  assertEquals(
                    resumed.confirmed.confirmation.fullClaimOption,
                    out.confirmed.confirmation.fullClaimOption
                  )
                  assert(resumed.confirmed.confirmation.isInstanceOf[Confirmation.V2LoadedVerified])
              }
          }
        }
        .unsafeToFuture()
    }
    test("following structural input with bad commitment rejects after acknowledged compaction") {
      temporary
        .use { directory =>
          val c = context; val os = originals; val cfg = config(directory, c)
          val h = get(SequenceInput.block(os(4))).header
          // Synthetic bad commitment: exact original header, fabricated body/witness spans.
          val bad = Original(
            os(4).envelope,
            Bytes(
              Vector(0x82.toByte, 7.toByte, 0x85.toByte) ++
                h.raw.value ++ Vector(
                  0x81.toByte,
                  0.toByte,
                  0x81.toByte,
                  0.toByte,
                  0xa0.toByte,
                  0x80.toByte
                )
            )
          )
          assert(SequenceInput.block(bad).isRight)
          Ref.of[IO, Int](0).flatMap { compacted =>
            DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](cfg, seed(c, os)),
                source(Vector(os(3), bad)),
                policy(),
                label =>
                  if label == "after-anchor-advance" then compacted.update(_ + 1) else IO.unit
              )
              .use(_.run)
              .flatMap { out =>
                combinedResume[IO](cfg).use(_.snapshot).flatMap { resumed =>
                  compacted.get.map { count =>
                    out.reason match
                      case Stop.Completed(RunnerStop.Rejected(_, _)) => ()
                      case other                                     => fail(other.toString)
                    assertEquals(count, 1)
                    assertEquals(out.confirmed.state.depth, BigInt(4))
                    assertEquals(out.confirmed.state.revision, BigInt(4))
                    assertEquals(out.confirmed.state.compactedBlocks, BigInt(2))
                    assertEquals(out.confirmed.state.acquisition.size, 2)
                    assertEquals(
                      out.confirmed.confirmation.fullClaimOption.get.token.generation,
                      2L
                    )
                    assertEquals(resumed.state.id, out.confirmed.state.id)
                    assertEquals(
                      resumed.confirmation.fullClaimOption,
                      out.confirmed.confirmation.fullClaimOption
                    )
                  }
                }
              }
          }
        }
        .unsafeToFuture()
    }
    test("cancellation after acknowledged compaction preserves it and closes peer") {
      temporary
        .use { directory =>
          val c = context; val os = originals; val cfg = config(directory, c)
          Ref.of[IO, Int](0).flatMap { closed =>
            val peer =
              Resource.make(IO.unit)(_ => closed.update(_ + 1)).flatMap(_ => source(os.slice(3, 5)))
            DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](cfg, seed(c, os)),
                peer,
                policy(),
                label => if label == "after-anchor-advance" then IO.canceled else IO.unit
              )
              .use { runner =>
                for
                  fiber <- runner.run.start
                  canceled <- fiber.join
                  saved <- runner.snapshot
                  released <- closed.get
                yield
                  assert(canceled.isCanceled)
                  assertEquals(released, 1)
                  assertEquals(saved.state.depth, BigInt(4))
                  assertEquals(saved.state.compactedBlocks, BigInt(2))
                  assertEquals(saved.confirmation.fullClaimOption.get.token.generation, 2L)
                  assert(saved.confirmation.isInstanceOf[Confirmation.V2Acknowledged])
              }
          }
        }
        .unsafeToFuture()
    }
    test(
      "compaction storage failure reports prior cache while strict resume selects committed successor"
    ) {
      temporary
        .use { directory =>
          val c = context; val os = originals; val cfg = config(directory, c)
          Ref.of[IO, Boolean](false).flatMap { armed =>
            val backend = combinedObserved[IO](
              cfg,
              Some(seed(c, os)),
              phase =>
                armed.get.flatMap(enabled =>
                  if enabled && phase == CombinedLocalV2.Phase.BeforeMemory then
                    IO.raiseError(new java.io.IOException("compaction publication sentinel"))
                  else IO.unit
                )
            )
            DurableValidatorRunner
              .resource[IO](
                c,
                backend,
                source(os.slice(3, 5)),
                policy(),
                label => if label == "after-publish" then armed.set(true) else IO.unit
              )
              .use { runner =>
                for
                  out <- runner.run
                  saved <- runner.snapshot
                  again <- runner.run
                yield
                  assertEquals(out.reason, Stop.StorageFailure(true))
                  assert(saved eq out.confirmed)
                  assertEquals(again.reason, Stop.StorageFailure(true))
                  assertEquals(out.confirmed.state.depth, BigInt(4))
                  assertEquals(out.confirmed.state.compactedBlocks, BigInt(1))
                  assertEquals(out.confirmed.confirmation.fullClaimOption.get.token.generation, 1L)
                  out
              }
              .flatMap { out =>
                combinedResume[IO](cfg).use(_.snapshot).map { recovered =>
                  assertEquals(recovered.state.depth, BigInt(4))
                  assertEquals(recovered.state.revision, out.confirmed.state.revision)
                  assertEquals(recovered.state.compactedBlocks, BigInt(2))
                  assertEquals(recovered.confirmation.fullClaimOption.get.token.generation, 2L)
                  assert(recovered.state.id != out.confirmed.state.id)
                }
              }
          }
        }
        .unsafeToFuture()
    }
    test("stored V2 capacity mismatch rejects before peer acquisition") {
      temporary
        .use { directory =>
          val c = context; val os = originals
          DurableValidatorRunner
            .resource[IO](
              c,
              combinedCreate[IO](config(directory, c), seed(c, os)),
              noPeer,
              policy(capacity = 2),
              _ => IO.unit
            )
            .use(_.run)
            .attempt
            .map(r => assert(r.left.exists(_.isInstanceOf[IllegalArgumentException])))
        }
        .unsafeToFuture()
    }
    def fenceControl(
        c: SequenceInput.Context,
        os: Vector[Original],
        depth: Int,
        signal: cats.effect.Deferred[IO, Unit]
    ): CompletionFence.Control[IO] =
      val h = get(ReferenceCaptureCommand.header(os(depth - 1).envelope))
      val target =
        CompletionFence.Target("a" * 64, "A", c.id.hex, depth, h.blockNo, h.slot, h.hash.hex)
      val a = CompletionFence.Accepted(target, "b" * 64, "test")
      new CompletionFence.Control[IO]:
        val settings = CompletionFence.Settings(Path.of("/tmp/not-used"), "a" * 64, "A", 9)
        def read = signal.tryGet.map(_.map(_ => a))
        def await = signal.get.as(a)
        def verify(value: CompletionFence.Accepted) = IO(assertEquals(value, a))

    test("fence arrival interrupts blocked AwaitNext at exact point and closes peer") {
      temporary
        .use { dir =>
          val c = context; val os = originals
          for
            declared <- cats.effect.Deferred[IO, Unit]
            blocked <- cats.effect.Deferred[IO, Unit]
            closed <- Ref.of[IO, Int](0)
            cursor <- Ref.of[IO, Int](3)
            peer = Resource
              .make(IO.unit)(_ => closed.update(_ + 1))
              .as(new Peer[IO]:
                def intersect(offered: Vector[ChainSync.Point]) = IO.pure(offered.head)
                def next = cursor.getAndUpdate(_ + 1).flatMap { n =>
                  if n < 9 then IO.pure(Event.Forward(os(n).envelope))
                  else blocked.complete(()).void *> IO.never[Event]
                }
                def fetch(p: ChainSync.Point) = IO(os.find(o => point(o) == p).get.block))
            out <- DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](config(dir, c), seed(c, os)),
                peer,
                policy(),
                _ => IO.unit,
                Some(fenceControl(c, os, 9, declared))
              )
              .use { runner =>
                for
                  f <- runner.run.start
                  _ <- blocked.get.timeout(10.seconds)
                  _ <- declared.complete(())
                  result <- f.joinWithNever.timeout(5.seconds)
                yield result
              }
            count <- closed.get
          yield
            assertEquals(out.reason, Stop.Completed(RunnerStop.TargetReached))
            assertEquals(out.confirmed.state.depth, BigInt(9))
            assert(out.fence.nonEmpty)
            assertEquals(count, 1)
        }
        .unsafeToFuture()
    }
    test("fence known before catchup excludes immediately available successor") {
      temporary
        .use { dir =>
          val c = context; val os = originals
          for
            declared <- cats.effect.Deferred[IO, Unit]
            _ <- declared.complete(())
            out <- DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](config(dir, c), seed(c, os)),
                source(os.drop(3)),
                policy(),
                _ => IO.unit,
                Some(fenceControl(c, os, 9, declared))
              )
              .use(_.run)
          yield
            assertEquals(out.reason, Stop.Completed(RunnerStop.TargetReached))
            assertEquals(out.confirmed.state.depth, BigInt(9))
            assertEquals(out.events, 6)
        }
        .unsafeToFuture()
    }
    test("hard numeric maximum without fence never succeeds; cancellation closes peer") {
      temporary
        .use { dir =>
          val c = context; val os = originals
          for
            declared <- cats.effect.Deferred[IO, Unit]
            reached <- cats.effect.Deferred[IO, Unit]
            closed <- Ref.of[IO, Int](0)
            published <- Ref.of[IO, Int](0)
            peer = Resource
              .make(IO.unit)(_ => closed.update(_ + 1))
              .flatMap(_ => source(os.drop(3)))
            out <- DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](config(dir, c), seed(c, os)),
                peer,
                policy(),
                label =>
                  if label == "after-publish" then
                    published
                      .updateAndGet(_ + 1)
                      .flatMap(n => if n == 9 then reached.complete(()).void else IO.unit)
                  else IO.unit,
                Some(fenceControl(c, os, 12, declared))
              )
              .use { runner =>
                for
                  f <- runner.run.start
                  _ <- reached.get
                  _ <- f.cancel
                  result <- f.join
                yield result
              }
            count <- closed.get
          yield
            assert(out.isCanceled)
            assertEquals(count, 1)
        }
        .unsafeToFuture()
    }

    test("matching fence arriving between loop reads never issues another RequestNext") {
      temporary
        .use { dir =>
          val c = context; val os = originals
          for
            declared <- cats.effect.Deferred[IO, Unit]
            _ <- declared.complete(())
            published <- Ref.of[IO, Int](0)
            reads <- Ref.of[IO, Int](0)
            base = fenceControl(c, os, 9, declared)
            control = new CompletionFence.Control[IO]:
              val settings = base.settings
              def read = published.get.flatMap(n =>
                if n < 6 then IO.pure(None)
                else
                  reads
                    .getAndUpdate(_ + 1)
                    .flatMap(i => if i == 0 then IO.pure(None) else base.read)
              )
              def await = IO.never[CompletionFence.Accepted]
              def verify(a: CompletionFence.Accepted) = base.verify(a)
            out <- DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](config(dir, c), seed(c, os)),
                source(os.drop(3)),
                policy(),
                label => if label == "after-publish" then published.update(_ + 1) else IO.unit,
                Some(control)
              )
              .use(_.run)
              .timeout(10.seconds)
          yield
            assertEquals(out.reason, Stop.Completed(RunnerStop.TargetReached))
            assertEquals(out.confirmed.state.depth, BigInt(9))
            assertEquals(
              out.returnedBytes,
              os.slice(3, 9).map(o => o.envelope.size.toLong + o.block.size).sum
            )
        }
        .unsafeToFuture()
    }
    test("declaration before publication rejects successor and preserves prior compaction") {
      temporary
        .use { dir =>
          val c = context; val os = originals
          for
            declared <- cats.effect.Deferred[IO, Unit]
            attempts <- Ref.of[IO, Int](0)
            out <- DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](config(dir, c), seed(c, os)),
                source(os.drop(3)),
                policy(),
                label =>
                  if label == "before-publish" then
                    attempts
                      .updateAndGet(_ + 1)
                      .flatMap(n => if n == 7 then declared.complete(()).void else IO.unit)
                  else IO.unit,
                Some(fenceControl(c, os, 9, declared))
              )
              .use(_.run)
            resumed <- combinedResume[IO](config(dir, c)).use(_.snapshot)
          yield
            assert(out.reason match
              case Stop.Completed(RunnerStop.Rejected("fence", _)) => true
              case _                                               => false)
            assertEquals(out.confirmed.state.depth, BigInt(9))
            assertEquals(out.confirmed.state.compactedBlocks, BigInt(7))
            assertEquals(resumed.state.id, out.confirmed.state.id)
            assertEquals(out.fence, None)
        }
        .unsafeToFuture()
    }
    test("older declaration after publication rejects without rollback or retarget") {
      temporary
        .use { dir =>
          val c = context; val os = originals
          for
            declared <- cats.effect.Deferred[IO, Unit]
            applied <- Ref.of[IO, Int](0)
            out <- DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](config(dir, c), seed(c, os)),
                source(os.drop(3)),
                policy(),
                label =>
                  if label == "after-publish" then
                    applied
                      .updateAndGet(_ + 1)
                      .flatMap(n => if n == 7 then declared.complete(()).void else IO.unit)
                  else IO.unit,
                Some(fenceControl(c, os, 9, declared))
              )
              .use(_.run)
          yield
            assert(out.reason match
              case Stop.Completed(RunnerStop.Rejected("fence", _)) => true
              case _                                               => false)
            assertEquals(out.confirmed.state.depth, BigInt(10))
            assertEquals(out.fence, None)
        }
        .unsafeToFuture()
    }
    test("forked completion point fails before publishing conflicting final block") {
      temporary
        .use { dir =>
          val c = context; val os = originals
          for
            declared <- cats.effect.Deferred[IO, Unit]
            _ <- declared.complete(())
            base = fenceControl(c, os, 9, declared)
            control = new CompletionFence.Control[IO]:
              val settings = base.settings
              def change(a: CompletionFence.Accepted) =
                a.copy(target = a.target.copy(hash = "0" * 64))
              def read = base.read.map(_.map(change))
              def await = base.await.map(change)
              def verify(a: CompletionFence.Accepted) = IO.unit
            out <- DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](config(dir, c), seed(c, os)),
                source(os.drop(3)),
                policy(),
                _ => IO.unit,
                Some(control)
              )
              .use(_.run)
          yield
            assert(out.reason match
              case Stop.Completed(RunnerStop.Rejected("fence", _)) => true
              case _                                               => false)
            assertEquals(out.confirmed.state.depth, BigInt(8))
            assertEquals(out.fence, None)
        }
        .unsafeToFuture()
    }
    test("unreachable fence and event/byte limits cannot produce completion acknowledgment") {
      Vector("time", "events", "bytes")
        .traverse_ { which =>
          temporary.use { dir =>
            val c = context; val os = originals
            for
              declared <- cats.effect.Deferred[IO, Unit]
              _ <- declared.complete(())
              p = which match
                case "time"   => policy().copy(duration = 300.millis)
                case "events" => policy().copy(maxEvents = 12)
                case _        => policy().copy(maxBytes = 1)
              peer =
                if which == "time" then
                  Resource.pure[IO, Peer[IO]](new Peer[IO]:
                    def intersect(offered: Vector[ChainSync.Point]) = IO.pure(offered.head)
                    def next = IO.never[Event]
                    def fetch(p: ChainSync.Point) = IO.never[Bytes])
                else if which == "events" then source(Vector.empty)
                else source(os.drop(3))
              out <- DurableValidatorRunner
                .resource[IO](
                  c,
                  combinedCreate[IO](config(dir, c), seed(c, os)),
                  peer,
                  p,
                  _ => IO.unit,
                  Some(fenceControl(c, os, 12, declared))
                )
                .use(_.run)
            yield
              val expected = which match
                case "time"   => RunnerStop.TimeBudget
                case "events" => RunnerStop.EventBudget
                case _        => RunnerStop.ByteBudget
              assertEquals(out.reason, Stop.Completed(expected))
              assertEquals(out.fence, None)
          }
        }
        .unsafeToFuture()
    }

    test("controller cancellation settles both blocked fence and RequestNext fibers before close") {
      temporary
        .use { dir =>
          val c = context; val os = originals
          for
            reading <- cats.effect.Deferred[IO, Unit]
            watching <- cats.effect.Deferred[IO, Unit]
            canceled <- Ref.of[IO, Set[String]](Set.empty)
            closed <- Ref.of[IO, Boolean](false)
            control = new CompletionFence.Control[IO]:
              val settings = CompletionFence.Settings(Path.of("/tmp/not-used"), "a" * 64, "A", 9)
              def read = IO.pure(None)
              def await = (watching.complete(()).void *> IO.never[CompletionFence.Accepted])
                .onCancel(canceled.update(_ + "watch"))
              def verify(a: CompletionFence.Accepted) = IO.unit
            peer = Resource
              .make(IO.unit)(_ =>
                canceled.get.flatMap(v => IO(assertEquals(v, Set("next", "watch")))) *> closed
                  .set(true)
              )
              .as(new Peer[IO]:
                def intersect(offered: Vector[ChainSync.Point]) = IO.pure(offered.head)
                def next = (reading.complete(()).void *> IO.never[Event])
                  .onCancel(canceled.update(_ + "next"))
                def fetch(p: ChainSync.Point) = IO.never[Bytes])
            result <- DurableValidatorRunner
              .resource[IO](
                c,
                combinedCreate[IO](config(dir, c), seed(c, os)),
                peer,
                policy(),
                _ => IO.unit,
                Some(control)
              )
              .use { runner =>
                for
                  f <- runner.run.start
                  _ <- reading.get *> watching.get
                  _ <- f.cancel.timeout(2.seconds)
                  result <- f.join
                yield result
              }
            done <- closed.get
          yield
            assert(result.isCanceled)
            assert(done)
        }
        .unsafeToFuture()
    }

  }
