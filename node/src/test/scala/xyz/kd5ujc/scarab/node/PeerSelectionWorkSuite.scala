// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRules, VolatileChain}
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.GenesisBlockFetch.Starvation
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

class PeerSelectionWorkSuite extends FailsOnUncaught:
  test("actual body publication leaves starvation owned solely by the node add-block runner") {
    for
      forged <- ForgedChain.forge(5000 until 5020)
      header = forged.headers.head
      raw = forged.raws.head
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      closeSignal <- Deferred[IO, NetworkError]
      sent <- Ref.of[IO, Boolean](false)
      state <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(5, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      work <- SelectionWork.create[IO](IO.unit)
      pool <- BlockPool.create[IO]
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, h, _) => entered.complete(()).void *> release.get.as(h.blockNo)),
        _ => IO.unit,
        ForgedChain.geometry
      )
      _ <- follower.installInvalidBlocks(pool.invalidBlocks)
      fatal <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      chainSync = new ChainSyncClient[IO, NtNHeader]:
        def findIntersect(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
          IO.pure(
            Option.when(points.contains(ChainSync.Point.Origin))(
              ChainSync.Point.Origin -> ChainSync.Tip.Origin
            )
          )
        def findIntersectResult(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
          findIntersect(points).map(
            _.fold[ChainSyncClient.Intersection](
              ChainSyncRuntime.Intersection.NotFound(ChainSync.Tip.Origin)
            ) { (point, remoteTip) => ChainSyncRuntime.Intersection.Found(point, remoteTip) }
          )
        def requestNext(using Raise[IO, NetworkError]) =
          IO.raiseError[ChainSyncClient.Update[NtNHeader]](new AssertionError("batch expected"))
        def requestNextBatch(@scala.annotation.unused count: Int)(using Raise[IO, NetworkError]) =
          sent.getAndSet(true).flatMap {
            case true => IO.never[Vector[ChainSyncClient.Update[NtNHeader]]]
            case false =>
              IO.pure(
                Vector(
                  ChainSyncClient.Update.RollForward(
                    NtNHeader(header.era, header.raw, None),
                    ChainSync.Tip.Origin
                  )
                )
              )
          }
        def done(using Raise[IO, NetworkError]) = IO.unit
        def idle = IO.pure(true)
        def idling = IO.pure(true)
      blockFetch = new BlockFetchClient[IO]:
        def requestRange(from: ChainSync.Point, to: ChainSync.Point)(using
            Raise[IO, NetworkError]
        ) =
          IO {
            assertEquals(from, header.point)
            assertEquals(to, header.point)
            Some(Vector(raw))
          }
        def done(using Raise[IO, NetworkError]) = IO.unit
        def idle = IO.pure(true)
      mux = new Mux[IO]:
        def installApplication(initiators: Set[Int], responders: Set[Int])(using
            cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
        ): IO[Unit] = IO.unit
        def awaitResponderActivity(using Raise[IO, NetworkError]) = IO.never[Unit]
        def channel(@scala.annotation.unused protocol: Int)(using Raise[IO, NetworkError]) =
          IO.raiseError[MuxChannel[IO]](new AssertionError("client facade supplies transport"))
        def channel(protocol: Int, @scala.annotation.unused role: Mux.Role)(using
            Raise[IO, NetworkError]
        ) = channel(protocol)
        def closed = closeSignal.get
        def failure = closeSignal.tryGet
        def abort(cause: NetworkError) = closeSignal.complete(cause).void
      given BlockSelection.Before[IO] = BlockSelection.Before.noop[IO]
      _ <- Handle
        .allow[NodeError] {
          follower
            .blockSelection(pool, work, cause => fatal.update(_ :+ cause))
            .flatMap { _ =>
              work.session.flatMap { bodyWork =>
                Peer.over(
                  NodeToNodeConnection(15, chainSync, blockFetch, mux),
                  Peer.Settings(
                    SocketAddress[Host](ip"10.4.9.1", port"3001"),
                    42,
                    byronEpochSlots = ForgedChain.geometry.byron.epochSlots
                  ),
                  pool = Some(pool),
                  selectionWork = Some(bodyWork),
                  heightSnapshot = xyz.kd5ujc.scarab.node.ChainSyncHeights.origin
                )
              }
            }
            .flatTap(_ => FetchTesting.decisions(pool))
            .use { peer =>
              for
                _ <- peer.intersect(Vector(ChainSync.Point.Origin))
                event <- peer.next
                _ <- entered.get
                _ <- IO(assertEquals(event, SyncEvent.RollForward(header, ChainSync.Tip.Origin)))
                during <- work.snapshot
                _ <- IO(assert(during != Starvation.Ongoing))
                _ <- release.complete(())
                _ <- pool.admissions.reprocess
                _ <- work.snapshot.assertEquals(Starvation.Ongoing)
                // If publication left a duplicate body-reader token, the actual worker's finalizer
                // above would remain busy after its queue emptied.
                _ <- peer.selectionWork.traverse_(
                  _.processing(header.hash).use(_ => work.snapshot.assertEquals(Starvation.Ongoing))
                )
              yield ()
            }
        }
        .rescue(error => IO(fail(error.message)))
      observed <- fatal.get
      _ <- IO(assertEquals(observed, Vector.empty))
    yield ()
  }
