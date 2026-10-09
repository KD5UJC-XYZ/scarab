// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.io.IOException
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import lab.cbor.Bytes
import lab.network.ChainSync

class DurableValidatorRunnerSuite extends munit.FunSuite:
  import ValidatorTransitions.*
  import BoundedChainFollower.{Event, Original, Peer}
  import BoundedValidatorRunner.{Policy, Stop as RunnerStop}
  import DurableValidatorRunner.Stop
  private def get[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def peer(
      events: List[Event],
      originals: Vector[Original],
      intersection: Option[ChainSync.Point] = None
  ): Resource[IO, Peer[IO]] =
    Resource.eval(Ref.of[IO, List[Event]](events)).map { queue =>
      new Peer[IO]:
        def intersect(offered: Vector[ChainSync.Point]) =
          IO.pure(intersection.getOrElse(offered.head))
        def next = queue.modify {
          case head :: tail => (tail, head)
          case Nil          => (Nil, Event.Await)
        }
        def fetch(point: ChainSync.Point) = IO {
          originals
            .find(o => {
              val h = get(ReferenceCaptureCommand.header(o.envelope))
              point == ChainSync.Point.Block(get(ChainSync.UInt64.from(h.slot)), h.hash)
            })
            .get
            .block
        }
    }
  private def runBackend(
      context: SequenceInput.Context,
      backend: Resource[IO, Backend[IO]],
      source: Resource[IO, Peer[IO]],
      policy: Policy
  ) =
    DurableValidatorRunner.resource[IO](context, backend, source, policy, _ => IO.unit)
  private def temporary =
    Resource.make(IO.blocking(Files.createTempDirectory("durable-runner-").toRealPath())) {
      directory =>
        IO.blocking {
          require(
            directory.getParent == Path
              .of(System.getProperty("java.io.tmpdir"))
              .toRealPath() && directory.getFileName.toString.startsWith("durable-runner-")
          )
          val paths = Files.walk(directory)
          try
            paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
              require(path.toAbsolutePath.normalize().startsWith(directory))
              Files.delete(path)
            }
          finally paths.close()
        }
    }

  test("sustained V2 requires backend inspection; invalid policies reject before acquisition") {
    Ref
      .of[IO, Int](0)
      .flatMap { touched =>
        val backend = Resource.eval(
          touched.update(_ + 1) *> IO
            .raiseError[Backend[IO]](new AssertionError("backend acquired"))
        )
        val source = Resource.eval(
          touched.update(_ + 1) *> IO.raiseError[Peer[IO]](new AssertionError("peer acquired"))
        )
        for
          a <- runBackend(null, backend, source, Policy(target = 12, advanceWindow = true))
            .use(_.run)
            .attempt
          b <- runBackend(null, backend, source, Policy(target = 0)).use(_.run).attempt
          c <- runBackend(null, backend, source, Policy(target = 2, rollbackCapacity = 1))
            .use(_.run)
            .attempt
          n <- touched.get
        yield
          assert(a.isLeft); assert(b.isLeft); assert(c.isLeft); assertEquals(n, 1)
      }
      .unsafeToFuture()
  }
  sys.env.get("COHERENT_SEQUENCE_EVIDENCE").foreach { location =>
    val root = Path.of(location)
    def context = get(SequenceInput.load(root))
    def originals = get(
      CoherentSequenceCommand
        .captures(Bytes.fromArray(Files.readAllBytes(root.resolve("scala-sequence-capture.md"))))
    )
    def forward = peer(originals.toList.map(o => Event.Forward(o.envelope)), originals)
    def create(directory: Path, capacity: Int = 8) =
      durableCreate[IO](directory, context, capacity, 2.seconds, _ => IO.unit)

    test("durable forward acknowledges only published state and releases peer") {
      temporary
        .use { directory =>
          Ref.of[IO, Int](0).flatMap { released =>
            val source = Resource.make(IO.unit)(_ => released.update(_ + 1)).flatMap(_ => forward)
            runBackend(context, create(directory), source, Policy(target = 1)).use { runner =>
              for
                before <- runner.snapshot
                out <- runner.run
                again <- runner.run
                after <- runner.snapshot
                n <- released.get
              yield
                assertEquals(out.reason, Stop.Completed(RunnerStop.TargetReached))
                assertEquals(out.confirmed.state.depth, BigInt(1))
                assertEquals(out.confirmed.state.revision, BigInt(1))
                assertEquals(
                  out.confirmed.confirmation.tokenOption.get.generation,
                  before.confirmation.tokenOption.get.generation + 1
                )
                assertEquals(after.confirmation, out.confirmed.confirmation)
                assertEquals(again.reason, Stop.Completed(RunnerStop.AlreadyRun))
                assertEquals(n, 1)
            }
          }
        }
        .unsafeToFuture()
    }
    test("resume at cumulative target remains loaded-verified and never opens peer") {
      temporary
        .use { directory =>
          for
            saved <- runBackend(context, create(directory), forward, Policy(target = 1)).use(_.run)
            out <- runBackend(
              context,
              durableResume[IO](
                directory,
                context.id,
                saved.confirmed.confirmation.tokenOption.get,
                20.seconds,
                2.seconds,
                _ => IO.unit
              ),
              Resource.eval(IO.raiseError[Peer[IO]](new AssertionError("peer must not open"))),
              Policy(target = 1)
            ).use(_.run)
          yield
            assertEquals(out.reason, Stop.Completed(RunnerStop.TargetReached))
            assertEquals(out.events, 0)
            assertEquals(
              out.confirmed.confirmation,
              Confirmation.LoadedVerified(saved.confirmed.confirmation.tokenOption.get)
            )
        }
        .unsafeToFuture()
    }
    test("resume exact intersection and awaits do not increment generation or acknowledge") {
      temporary
        .use { directory =>
          for
            saved <- runBackend(context, create(directory), forward, Policy(target = 1)).use(_.run)
            out <- runBackend(
              context,
              durableResume[IO](
                directory,
                context.id,
                saved.confirmed.confirmation.tokenOption.get,
                20.seconds,
                2.seconds,
                _ => IO.unit
              ),
              peer(List(Event.Await, Event.Await), originals),
              Policy(target = 2, maxEvents = 2)
            ).use(_.run)
          yield
            assertEquals(out.reason, Stop.Completed(RunnerStop.EventBudget))
            assertEquals(out.confirmed.state.revision, saved.confirmed.state.revision)
            assertEquals(
              out.confirmed.confirmation,
              Confirmation.LoadedVerified(saved.confirmed.confirmation.tokenOption.get)
            )
        }
        .unsafeToFuture()
    }
    test("resume continues from acknowledged original with cumulative target") {
      temporary
        .use { directory =>
          for
            saved <- runBackend(context, create(directory), forward, Policy(target = 1)).use(_.run)
            out <- runBackend(
              context,
              durableResume[IO](
                directory,
                context.id,
                saved.confirmed.confirmation.tokenOption.get,
                20.seconds,
                2.seconds,
                _ => IO.unit
              ),
              peer(List(Event.Forward(originals(1).envelope)), originals),
              Policy(target = 2)
            ).use(_.run)
          yield
            assertEquals(out.reason, Stop.Completed(RunnerStop.TargetReached))
            assertEquals(out.confirmed.state.depth, BigInt(2))
            assertEquals(
              out.confirmed.confirmation.tokenOption.get.generation,
              saved.confirmed.confirmation.tokenOption.get.generation + 1
            )
            assert(out.confirmed.confirmation.isInstanceOf[Confirmation.Acknowledged])
        }
        .unsafeToFuture()
    }
    test("stored capacity mismatch rejects before peer acquisition") {
      temporary
        .use { directory =>
          for
            saved <- runBackend(
              context,
              create(directory, 2),
              forward,
              Policy(target = 1, rollbackCapacity = 2)
            ).use(_.run)
            touched <- Ref.of[IO, Int](0)
            result <- runBackend(
              context,
              durableResume[IO](
                directory,
                context.id,
                saved.confirmed.confirmation.tokenOption.get,
                20.seconds,
                2.seconds,
                _ => IO.unit
              ),
              Resource.eval(
                touched.update(_ + 1) *> IO
                  .raiseError[Peer[IO]](new AssertionError("peer acquired"))
              ),
              Policy(target = 2, rollbackCapacity = 8)
            ).use(_.run).attempt
            n <- touched.get
          yield
            assert(result.isLeft); assertEquals(n, 0)
        }
        .unsafeToFuture()
    }
    Vector(CoherentSequence.DurablePhase.BeforeDisk, CoherentSequence.DurablePhase.AfterDisk)
      .foreach { phase =>
        test(s"$phase failure preserves cached acknowledgement even when peer cleanup fails") {
          temporary
            .use { directory =>
              Ref.of[IO, Boolean](false).flatMap { armed =>
                val backend = durableCreateObserved[IO](
                  directory,
                  context,
                  2.seconds,
                  _ => IO.unit,
                  NioValidatedCheckpointStore.NoFaults,
                  observed =>
                    armed.get.flatMap(enabled =>
                      if enabled && observed == phase then
                        IO.raiseError(new IOException("storage sentinel"))
                      else IO.unit
                    )
                )
                val source = Resource
                  .make(IO.unit)(_ => IO.raiseError(new IOException("cleanup sentinel")))
                  .flatMap(_ => forward)
                runBackend(context, backend, source, Policy(target = 1)).use { runner =>
                  for
                    before <- runner.snapshot
                    _ <- armed.set(true)
                    out <- runner.run
                    after <- runner.snapshot
                    again <- runner.run
                  yield
                    assertEquals(out.reason, Stop.StorageFailure(true))
                    assert(out.confirmed eq before)
                    assert(after eq before)
                    assertEquals(again.reason, Stop.StorageFailure(true))
                    assertEquals(out.reconnects, 0)
                    assert(out.cleanupFailure.exists {
                      case RunnerStop.CleanupFailed(_) => true
                      case _                           => false
                    })
                }
              }
            }
            .unsafeToFuture()
        }
      }
    test(
      "cancelling recorder mutation reports cached uncertain state without a poisoned snapshot"
    ) {
      temporary
        .use { directory =>
          Deferred[IO, Unit].flatMap { entered =>
            val backend = durableCreate[IO](
              directory,
              context,
              8,
              3.seconds,
              pending =>
                if pending.previous.isEmpty then IO.unit else entered.complete(()).void *> IO.never
            )
            runBackend(context, backend, forward, Policy(target = 1)).use { runner =>
              for
                before <- runner.snapshot
                fiber <- runner.run.start
                _ <- entered.get
                _ <- fiber.cancel.timeout(3.seconds)
                after <- runner.snapshot
                out <- runner.run
              yield
                assert(after eq before)
                assertEquals(out.reason, Stop.StorageFailure(true))
            }
          }
        }
        .unsafeToFuture()
    }
    test("receipt output hook failure preserves returned acknowledgement without retry") {
      temporary
        .use { directory =>
          Ref.of[IO, Int](0).flatMap { released =>
            val source = Resource.make(IO.unit)(_ => released.update(_ + 1)).flatMap(_ => forward)
            DurableValidatorRunner
              .resource[IO](
                context,
                create(directory),
                source,
                Policy(target = 1),
                label =>
                  if label == "after-publish" then
                    IO.raiseError(new IOException("receipt output failed"))
                  else IO.unit
              )
              .use { runner =>
                for
                  before <- runner.snapshot
                  out <- runner.run
                  n <- released.get
                yield
                  out.reason match
                    case Stop.Completed(RunnerStop.Internal(reason)) =>
                      assert(reason.contains("receipt output failed"))
                    case other => fail(other.toString)
                  assertEquals(out.confirmed.state.depth, BigInt(1))
                  assertEquals(
                    out.confirmed.confirmation.tokenOption.get.generation,
                    before.confirmation.tokenOption.get.generation + 1
                  )
                  assert(out.confirmed.confirmation.isInstanceOf[Confirmation.Acknowledged])
                  assertEquals(out.reconnects, 0)
                  assertEquals(out.cleanupFailure, None)
                  assertEquals(n, 1)
              }
          }
        }
        .unsafeToFuture()
    }
    test("malformed header rejection preserves acknowledgement without storage failure") {
      temporary
        .use { directory =>
          runBackend(
            context,
            create(directory),
            peer(List(Event.Forward(Bytes.fromArray(Array(0.toByte)))), originals),
            Policy(target = 1)
          ).use { runner =>
            for
              before <- runner.snapshot
              out <- runner.run
            yield
              out.reason match
                case Stop.Completed(RunnerStop.Rejected("header", _)) => ()
                case other                                            => fail(other.toString)
              assertEquals(out.confirmed.confirmation, before.confirmation)
              assertEquals(out.confirmed.state.id, before.state.id)
          }
        }
        .unsafeToFuture()
    }
  }
