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

class ValidatorTransitionsV2Suite extends munit.FunSuite:
  import ValidatorTransitions.*
  import CombinedLocalV2 as Combined
  private def get[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def claim(view: View): LocalDerivedCheckpoint.Claim =
    view.confirmation.fullClaimOption.get
  private def temporary =
    Resource.make(
      IO.blocking(Files.createTempDirectory("validator-transitions-v2-").toRealPath())
    ) { directory =>
      IO.blocking {
        require(
          directory.getParent == Path
            .of(System.getProperty("java.io.tmpdir"))
            .toRealPath() && directory.getFileName.toString.startsWith("validator-transitions-v2-")
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

  test("v2 confirmation preserves complete claim without v1 token coercion") {
    val bytes = Bytes.fromArray(Array.fill[Byte](32)(1))
    val token = LocalDerivedCheckpoint.Token(bytes, bytes, bytes, 2L, bytes)
    val full = LocalDerivedCheckpoint.Claim(
      token,
      LocalDerivedCheckpoint.Format,
      CoherentSequence.ProfileId,
      LocalDerivedCheckpoint.Authority,
      bytes,
      bytes,
      BigInt(3),
      BigInt(4)
    )
    Vector(Confirmation.V2LoadedVerified(full), Confirmation.V2Acknowledged(full)).foreach {
      confirmation =>
        assertEquals(confirmation.fullClaimOption, Some(full))
        assertEquals(confirmation.tokenOption, None)
        assert(confirmation.isDurable)
    }
    assert(!Confirmation.Volatile.isDurable)
    assertEquals(Confirmation.Volatile.fullClaimOption, None)
  }
  sys.env.get("COHERENT_WINDOW_EVIDENCE").foreach { location =>
    val path = Path.of(location)
    def context = get(SequenceInput.load(path))
    def originals = get(
      CoherentSequenceCommand.captures(
        Bytes.fromArray(Files.readAllBytes(path.resolve("scala-sequence-capture.md"))),
        12
      )
    )
    def setup(root: Path): (Combined.Config, Combined.Bootstrap) =
      val c = context
      val os = originals
      assert(os.size >= 4)
      val h = get(SequenceInput.block(os.head)).header
      val point = ChainSync.Point.Block(get(ChainSync.UInt64.from(h.slot)), h.hash)
      val binding = ControllerJournalCodec.Binding(
        root.resolve("controller").toString,
        root.resolve("checkpoint").toString,
        ControllerReducer.Store(ControllerReducer.Id("07" * 32), ControllerReducer.Id(c.id.hex))
      )
      (Combined.Config(binding, 3, 10.seconds), Combined.Bootstrap(c, os.take(3), point))
    def fourth = get(SequenceInput.block(originals(3)))
    def oldest(v: View) = v.state.acquisition.candidates.dropRight(1).last

    test("combined create and resume retain full claims and no-op classification") {
      temporary
        .use { directory =>
          val (config, seed) = setup(directory)
          for
            saved <- combinedCreate[IO](config, seed).use { backend =>
              for
                initial <- backend.snapshot
                same <- backend.advanceAnchor(initial, initial.state.acquisition.anchor).map(get(_))
                noRollback <- backend.rollbackTo(same, same.state.acquisition.tip).map(get(_))
              yield
                assert(backend.supportsCompaction); assertEquals(backend.capacity, 3)
                assertEquals(initial.confirmation, Confirmation.V2Acknowledged(claim(initial)))
                assertEquals(claim(initial).token.generation, 0L)
                assert(same eq initial); assert(noRollback eq initial)
                initial
            }
            _ <- combinedResume[IO](config).use { backend =>
              for
                loaded <- backend.snapshot
                noOp <- backend.advanceAnchor(loaded, loaded.state.acquisition.anchor).map(get(_))
                same <- backend.rollbackTo(noOp, noOp.state.acquisition.tip).map(get(_))
              yield
                assertEquals(loaded.confirmation, Confirmation.V2LoadedVerified(claim(saved)))
                assert(noOp eq loaded); assert(same eq loaded)
            }
          yield ()
        }
        .unsafeToFuture()
    }
    test(
      "compaction changes full acknowledgement at unchanged revision and invalidates prepared view"
    ) {
      temporary
        .use { directory =>
          val (config, seed) = setup(directory)
          combinedCreate[IO](config, seed).use { backend =>
            for
              initial <- backend.snapshot
              pending <- backend.prepare(initial, fourth).map(get(_))
              compact <- backend.advanceAnchor(initial, oldest(initial)).map(get(_))
              stale <- backend.publish(pending)
              prepared <- backend.prepare(compact, fourth).map(get(_))
              published <- backend.publish(prepared).map(get(_))
              cached <- backend.lastConfirmed
              rolled <- backend.rollbackTo(published, compact.state.acquisition.tip).map(get(_))
            yield
              assertEquals(compact.state.revision, initial.state.revision)
              assertEquals(claim(compact).token.generation, claim(initial).token.generation + 1)
              assert(claim(compact) != claim(initial))
              assertEquals(compact.confirmation, Confirmation.V2Acknowledged(claim(compact)))
              assertEquals(stale, Left(Rejection.StaleView))
              assertEquals(published.state.depth, BigInt(4))
              assertEquals(cached.confirmation, published.confirmation)
              assertEquals(rolled.state.depth, compact.state.depth)
              assertEquals(claim(rolled).token.generation, claim(published).token.generation + 1)
          }
        }
        .unsafeToFuture()
    }
    test("opaque views and candidates cannot cross combined sessions or native variants") {
      temporary
        .use { directory =>
          val (one, seed) = setup(directory.resolve("one"))
          val (two, _) = setup(directory.resolve("two"))
          IO.blocking {
            Files.createDirectory(directory.resolve("one"));
            Files.createDirectory(directory.resolve("two"))
          } *>
            (
              combinedCreate[IO](one, seed),
              combinedCreate[IO](two, seed),
              inMemory[IO](context)
            ).tupled.use { (a, b, memory) =>
              for
                first <- a.snapshot
                prepared <- a.prepare(first, fourth).map(get(_))
                foreignPrepare <- b.prepare(first, fourth)
                foreignPublish <- b.publish(prepared)
                foreignAnchor <- b.advanceAnchor(first, oldest(first))
                nativePublish <- memory.publish(prepared)
                after <- a.snapshot
              yield
                assertEquals(foreignPrepare, Left(Rejection.ForeignSession))
                assertEquals(foreignPublish, Left(Rejection.ForeignSession))
                assertEquals(foreignAnchor, Left(Rejection.ForeignSession))
                assertEquals(nativePublish, Left(Rejection.ForeignSession))
                assert(after eq first)
            }
        }
        .unsafeToFuture()
    }
    test("native backends reject compaction without changing view or acknowledgement") {
      temporary
        .use { directory =>
          Ref.of[IO, Int](0).flatMap { records =>
            (
              inMemory[IO](context),
              durableCreate[IO](directory, context, 8, 2.seconds, _ => records.update(_ + 1))
            ).tupled.use { (memory, disk) =>
              Vector(memory, disk).traverse_ { backend =>
                for
                  before <- backend.snapshot
                  count <- records.get
                  denied <- backend.advanceAnchor(before, before.state.acquisition.anchor)
                  after <- backend.snapshot
                  finalCount <- records.get
                yield
                  assert(!backend.supportsCompaction)
                  assertEquals(
                    denied,
                    Left(
                      Rejection.Validation(
                        CoherentSequence.Failure
                          .Unsupported("compaction", "combined local v2 backend required")
                      )
                    )
                  )
                  assert(after eq before); assertEquals(finalCount, count)
              }
            }
          }
        }
        .unsafeToFuture()
    }
    test(
      "failed committed compaction keeps cached confirmation and resume selects journal successor"
    ) {
      temporary
        .use { directory =>
          val (config, seed) = setup(directory)
          Ref.of[IO, Boolean](false).flatMap { armed =>
            combinedObserved[IO](
              config,
              Some(seed),
              phase =>
                armed.get.flatMap(on =>
                  if on && phase == Combined.Phase.BeforeMemory then
                    IO.raiseError(new IOException("after commit"))
                  else IO.unit
                )
            ).use { backend =>
              for
                before <- backend.snapshot
                _ <- armed.set(true)
                failure <- backend.advanceAnchor(before, oldest(before)).attempt
                cached <- backend.lastConfirmed
                rejected <- backend.snapshot.attempt
              yield
                val error = failure.swap.toOption.get.asInstanceOf[StorageFailure]
                assert(error.lastConfirmed eq before.confirmed)
                assert(cached eq before.confirmed)
                assert(error.potentiallyOlderThanDisk)
                assert(rejected.swap.toOption.get eq error)
                before
            }.flatMap { before =>
              combinedResume[IO](config).use { backend =>
                backend.snapshot.map { loaded =>
                  assertEquals(claim(loaded).token.generation, claim(before).token.generation + 1)
                  assertEquals(loaded.state.revision, before.state.revision)
                  assertEquals(loaded.state.compactedBlocks, before.state.compactedBlocks + 1)
                  assertEquals(loaded.confirmation, Confirmation.V2LoadedVerified(claim(loaded)))
                }
              }
            }
          }
        }
        .unsafeToFuture()
    }
    test("cancellation after combined commit keeps old adapter confirmation and terminates") {
      temporary
        .use { directory =>
          val (config, seed) = setup(directory)
          (Ref.of[IO, Boolean](false), Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap {
            (armed, entered, proceed) =>
              combinedObserved[IO](
                config,
                Some(seed),
                phase =>
                  armed.get.flatMap(on =>
                    if on && phase == Combined.Phase.BeforeMemory then
                      entered.complete(()).void *> proceed.get
                    else IO.unit
                  )
              ).use { backend =>
                (for
                  before <- backend.snapshot
                  _ <- armed.set(true)
                  mutation <- backend.advanceAnchor(before, oldest(before)).start
                  _ <- entered.get
                  cancelling <- mutation.cancel.start
                  _ <- IO.sleep(20.millis)
                  _ <- proceed.complete(())
                  _ <- cancelling.joinWithNever.timeout(3.seconds)
                  outcome <- mutation.join
                  cached <- backend.lastConfirmed
                  rejected <- backend.snapshot.attempt
                yield
                  assert(outcome.isCanceled)
                  assert(cached eq before.confirmed)
                  val error = rejected.swap.toOption.get.asInstanceOf[StorageFailure]
                  assert(error.potentiallyOlderThanDisk)
                  assert(error.lastConfirmed eq cached)
                ).guarantee(proceed.complete(()).void)
              }
          }
        }
        .unsafeToFuture()
    }
  }
