// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.relay

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import fs2.Stream
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.{Fatal, NodeScope, StorageBoundary}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.storage.ImmutableStore

class SourceScopeSuite extends FailsOnUncaught:
  import RelayKit.*

  private def store(
      getBody: Point => Raise[IO, StorageError] ?=> IO[Option[Bytes]],
      streamBody: Raise[IO, StorageError] ?=> Stream[IO, ImmutableStore.Stored]
  ): ImmutableStore[IO] = new ImmutableStore[IO]:
    def append(ref: BlockRef, raw: Bytes)(using Raise[IO, StorageError]) = IO.unit
    def sync(using Raise[IO, StorageError]) = IO.unit
    def tip(using Raise[IO, StorageError]) = IO.pure(None)
    def get(point: Point)(using Raise[IO, StorageError]) = getBody(point)
    def byBlockNo(n: BigInt)(using Raise[IO, StorageError]) = IO.pure(None)
    def stream(after: Point)(using Raise[IO, StorageError]) = streamBody

  test("relay cursor owns its storage scope after opening scope ends and closes on cancellation") {
    val cause = new java.io.IOException("read failed")
    val error = StorageError.Io("immutable", cause)
    for
      reported <- Deferred[IO, Fatal]
      server <- NodeScope.resource.use { _ =>
        ChainServer.open[IO](
          Some(store(_ => error.raise[IO, Option[Bytes]], Stream.empty)),
          None,
          headerOf,
          failure => reported.complete(failure).void
        )
      }
      _ <- server.source.use { source =>
        source.intersect(Vector(ref(1).point)).background.use { _ =>
          for
            failure <- reported.get.timeout(3.seconds)
            count <- server.activeFollowers
          yield
            assertEquals(failure, Fatal.Storage(error))
            assertEquals(count, 1)
        }
      }
      remaining <- server.activeFollowers
    yield assertEquals(remaining, 0)
  }

  test("BlockFetch keeps the storage stream scope alive after range creation and releases it") {
    val error = StorageError.Missing("stream tail")
    for
      reported <- Deferred[IO, Fatal]
      released <- Ref.of[IO, Boolean](false)
      seen <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      server <- ChainServer.open[IO](
        Some(
          store(
            point => IO.pure(Some(if point == ref(1).point then raw(1) else raw(3))),
            Stream.resource(Resource.make(IO.unit)(_ => released.set(true))).flatMap { _ =>
              Stream.emit(ImmutableStore.Stored(ref(2), raw(2))) ++
                Stream.eval(error.raise[IO, ImmutableStore.Stored])
            }
          )
        ),
        None,
        headerOf,
        failure => reported.complete(failure).void
      )
      range <- server.blocks.range(ref(1).point, ref(3).point, 10)
      _ <- range.fold(IO(fail("expected range"))) { blocks =>
        blocks.evalMap(value => seen.update(_ :+ value)).compile.drain.background.use { _ =>
          for
            failure <- reported.get.timeout(3.seconds)
            closed <- released.get
            values <- seen.get
          yield
            assertEquals(failure, Fatal.Storage(error))
            assert(closed)
            assertEquals(values, Vector(raw(1), raw(2)))
        }
      }
    yield ()
  }

  test("strict stream construction defects retain their cause and cancellation completes") {
    val cause = new IllegalStateException("stream construction")
    for
      reported <- Deferred[IO, Fatal]
      _ <- StorageBoundary
        .stream[IO, Unit](failure => reported.complete(failure).void) {
          throw cause
        }
        .compile
        .drain
        .background
        .use { _ =>
          reported.get.map(failure => assertEquals(failure, Fatal.Defect(cause)))
        }
    yield ()
  }
