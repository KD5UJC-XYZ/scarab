// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, BlockRules, SelectView, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{Header, NodeError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

/** #430 through a real session: after a partition our chain is two blocks past the common prefix,
  * and the peer's fork arrives one header per ChainSync batch. Its first two headers make no chain
  * longer than ours, so the decisions decline them; the third makes the fork preferred, and the
  * whole missing fork suffix is then requested (ouroboros-network 2fb69829
  * BlockFetch/Decision.hs:251–310, selectForkSuffixes 547–558).
  */
class PeerForkPrefixSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  test("a fork prefix declined before the fork was preferred is fetched once it is (#430)") {
    for
      forks <- ForgedChain.competingForks(5000 until 5020, 5020 until 5100, 31, 47)
      (left, right) = forks
      common = left.headers.zip(right.headers).takeWhile((a, b) => a.hash == b.hash).size
      _ <- IO(assert(left.headers.size >= common + 2 && right.headers.size >= common + 3))
      base = left.headers.take(common).last
      ours = left.headers.slice(common, common + 2)
      fork = right.headers.slice(common, common + 3)
      tip = ChainSync.Tip(fork.last.point, ChainSync.UInt64.saturating(fork.last.blockNo))
      wire = (h: Header.Shelley) => NtNHeader(h.era, h.raw, None)
      scripts = fork.toList.map(h => Vector(ChainSyncClient.Update.RollForward(wire(h), tip)))
      remaining <- Ref.of[IO, List[Vector[ChainSyncClient.Update[NtNHeader]]]](scripts)
      requests <- Ref.of[IO, Vector[(ChainSync.Point, ChainSync.Point)]](Vector.empty)
      closeReason <- Deferred[IO, NetworkError]
      chain <- Ref.of[IO, VolatileChain[BigInt]](
        VolatileChain(
          ForgedChain.geometry.securityParam,
          Anchor(Some(BlockRef(base.slot, base.hash, base.blockNo)), base.blockNo),
          ours.map(h => BlockRef(h.slot, h.hash, h.blockNo) -> h.blockNo)
        )
      )
      result <- {
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(chain)
        val bodies = (left.headers.zip(left.raws) ++ right.headers.zip(right.raws))
          .map((h, raw) => h.hash -> raw)
          .toMap
        val chainSync = new ChainSyncClient[IO, NtNHeader]:
          def findIntersect(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
            IO.pure(Option.when(points.contains(base.point))(base.point -> tip))
          def findIntersectResult(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
            findIntersect(points).map(
              _.fold[ChainSyncClient.Intersection](ChainSyncRuntime.Intersection.NotFound(tip)) {
                (point, remoteTip) => ChainSyncRuntime.Intersection.Found(point, remoteTip)
              }
            )
          def requestNext(using Raise[IO, NetworkError]) =
            IO.raiseError[ChainSyncClient.Update[NtNHeader]](
              new AssertionError("batch interface expected")
            )
          // The fork's headers arrive as the peer forges them, apart in time, so that each is
          // decided on before the next exists.
          def requestNextBatch(n: Int)(using Raise[IO, NetworkError]) =
            remaining.modify {
              case next :: rest =>
                rest -> IO
                  .sleep(if rest.size + 1 == fork.size then Duration.Zero else 300.millis)
                  .as(next)
              case Nil => Nil -> IO.never[Vector[ChainSyncClient.Update[NtNHeader]]]
            }.flatten
          def done(using Raise[IO, NetworkError]) = IO.unit
          def idle = IO.pure(true)
          def idling = IO.pure(true)
        val blockFetch = new BlockFetchClient[IO]:
          def requestRange(from: ChainSync.Point, to: ChainSync.Point)(using
              Raise[IO, NetworkError]
          ) =
            requests
              .update(_ :+ (from -> to))
              .as {
                val rows = right.headers.zip(right.raws)
                val start = rows.indexWhere(_._1.point == from)
                val end = rows.indexWhere(_._1.point == to)
                Option.when(start >= 0 && end >= start)(rows.slice(start, end + 1).map(_._2))
              }
          def done(using Raise[IO, NetworkError]) = IO.unit
          def idle = IO.pure(true)
        val mux = new Mux[IO]:
          def installApplication(initiators: Set[Int], responders: Set[Int])(using
              Raise[IO, NetworkError]
          ): IO[Unit] = IO.unit
          def awaitResponderActivity(using Raise[IO, NetworkError]) = IO.never[Unit]
          def channel(protocol: Int)(using Raise[IO, NetworkError]): IO[MuxChannel[IO]] =
            NetworkError
              .IllegalTransition(
                protocol,
                NetworkError.RuntimeViolation.UnexpectedProtocol(protocol)
              )
              .raise[IO, MuxChannel[IO]]
          def channel(protocol: Int, @scala.annotation.unused role: Mux.Role)(using
              Raise[IO, NetworkError]
          ): IO[MuxChannel[IO]] = channel(protocol)
          def closed = closeReason.get
          def abort(reason: NetworkError) = closeReason.complete(reason).void
        val rules = BlockRules.from[IO, BigInt] { (_, header, raw) =>
          IO(assertEquals(raw, bodies.getOrElse(header.hash, fail("unannounced block"))))
            .as(header.blockNo)
        }
        for
          follower <- Follower.create[IO, BigInt](
            rules,
            _ => IO.unit,
            ForgedChain.geometry,
            initialViews = (base +: ours).map(h => h.hash -> SelectView.of(h)).toMap
          )
          pool <- BlockPool.create[IO](BlockPool.FetchPolicy(), follower.fetchSelection)
          _ <- follower.installInvalidBlocks(pool.invalidBlocks)
          work <- SelectionWork.create[IO](IO.unit)
          given BlockSelection.Before[IO] = BlockSelection.Before.noop[IO]
          // As in the node, the add-block runner selects from the fetched bodies alone; the
          // session's follower only tracks the candidate (Main.selectionWorker).
          outcome <- Handle
            .allow[NodeError](
              follower
                .blockSelection(pool, work, cause => IO(fail(s"fatal: $cause")))
                .flatMap(_ => work.session)
                .flatMap { bodyWork =>
                  Peer.over(
                    NodeToNodeConnection(15, chainSync, blockFetch, mux),
                    Peer.Settings(
                      SocketAddress[Host](ip"10.4.8.2", port"3001"),
                      42,
                      readAhead = 16,
                      byronEpochSlots = ForgedChain.geometry.byron.epochSlots
                    ),
                    _ => IO(fail("the add-block runner owns preparation")),
                    ChainSyncHeights
                      .selected(left.headers.take(common) ++ ours, Vector(base.point)),
                    pool = Some(pool),
                    selectionWork = Some(bodyWork)
                  )
                }
                .use { peer =>
                  Resource
                    .make(
                      Handle
                        .allow[NodeError](follower.session(peer))
                        .rescue(e => IO(fail(s"follower stopped: ${e.message}")))
                        .start
                    )(_.cancel)
                    .use { running =>
                      val reached = cats.Monad[IO].tailRecM(()) { _ =>
                        chain.get.flatMap { current =>
                          if current.tip == fork.last.point then IO.pure(Right(current))
                          else IO.sleep(5.millis).as(Left(()))
                        }
                      }
                      IO.race(reached, running.joinWithNever).flatMap {
                        case Left(current) => IO.pure(current)
                        case Right(_) => IO(fail("follower returned before the fork was adopted"))
                      }
                    }
                }
            )
            .rescue(error => IO(fail(error.message)))
          ranges <- requests.get
        yield (outcome, ranges)
      }.timeout(20.seconds)
    yield
      val (adopted, ranges) = result
      assertEquals(adopted.blocks.map(_._1.hash), fork.map(_.hash))
      assert(ranges.exists((from, _) => from == fork.head.point), ranges.toString)
  }
