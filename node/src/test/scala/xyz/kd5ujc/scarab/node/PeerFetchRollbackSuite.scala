// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, BlockRules, SelectView, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{Header, NodeError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

/** Real header decoding, fetch coordination and follower adoption across a queued rollback. The
  * scripted transport controls batching; original forged headers and blocks remain intact.
  */
class PeerFetchRollbackSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  test("retired read-ahead headers and a longer replacement fork both remain fetchable") {
    for
      forks <- ForgedChain.competingForks(5000 until 5020, 5020 until 5100, 31, 47)
      (left, right) = forks
      common = left.headers.zip(right.headers).takeWhile((a, b) => a.hash == b.hash).size
      _ <- IO(
        assert(common > 0 && left.headers.size >= common + 2 && right.headers.size >= common + 3)
      )
      base = left.headers.take(common).last
      a = left.headers.drop(common).take(2)
      b = right.headers.drop(common).take(3)
      leftTip = ChainSync.Tip(
        left.headers.last.point,
        ChainSync.UInt64.saturating(left.headers.last.blockNo)
      )
      rightTip = ChainSync.Tip(b.last.point, ChainSync.UInt64.saturating(b.last.blockNo))
      wire = (h: Header.Shelley) => NtNHeader(h.era, h.raw, None)
      scripts = List(
        Vector(ChainSyncClient.Update.RollForward(wire(a.head), leftTip)),
        Vector(
          ChainSyncClient.Update.RollForward(wire(a.last), leftTip),
          ChainSyncClient.Update.RollBackward(base.point, rightTip),
          ChainSyncClient.Update.RollForward(wire(b.head), rightTip)
        ),
        Vector(ChainSyncClient.Update.RollForward(wire(b(1)), rightTip)),
        Vector(ChainSyncClient.Update.RollForward(wire(b.last), rightTip))
      )
      remaining <- Ref.of[IO, List[Vector[ChainSyncClient.Update[NtNHeader]]]](scripts)
      requests <- Ref.of[IO, Vector[(ChainSync.Point, ChainSync.Point)]](Vector.empty)
      applied <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      closeReason <- Deferred[IO, NetworkError]
      chain <- Ref.of[IO, VolatileChain[BigInt]](
        VolatileChain(
          ForgedChain.geometry.securityParam,
          Anchor(Some(BlockRef(base.slot, base.hash, base.blockNo)), base.blockNo),
          Vector.empty
        )
      )
      result <- {
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(chain)
        val bodies = (left.headers.zip(left.raws) ++ right.headers.zip(right.raws))
          .map((h, raw) => h.hash -> raw)
          .toMap
        val sourceChains = Vector(left.headers.zip(left.raws), right.headers.zip(right.raws))
        val chainSync = new ChainSyncClient[IO, NtNHeader]:
          def findIntersect(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
            IO.pure(Option.when(points.contains(base.point))(base.point -> leftTip))
          def requestNext(using Raise[IO, NetworkError]) =
            IO.raiseError[ChainSyncClient.Update[NtNHeader]](
              new AssertionError("batch interface expected")
            )
          def requestNextBatch(n: Int)(using Raise[IO, NetworkError]) =
            remaining.modify {
              case next :: rest =>
                rest -> IO {
                  assert(next.size <= n, s"scripted batch ${next.size} exceeds requested $n")
                  next
                }
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
              .as(sourceChains.flatMap { rows =>
                val start = rows.indexWhere(_._1.point == from)
                val end = rows.indexWhere(_._1.point == to)
                Option.when(start >= 0 && end >= start)(rows.slice(start, end + 1).map(_._2))
              }.headOption)
          def done(using Raise[IO, NetworkError]) = IO.unit
          def idle = IO.pure(true)
        val mux = new Mux[IO]:
          def channel(protocol: Int)(using Raise[IO, NetworkError]): IO[MuxChannel[IO]] =
            NetworkError
              .IllegalTransition(
                protocol,
                NetworkError.RuntimeViolation.UnexpectedProtocol(protocol)
              )
              .raise[IO, MuxChannel[IO]]
          def channel(protocol: Int, @scala.annotation.unused role: Mux.Role)(using
              Raise[IO, NetworkError]
          ): IO[MuxChannel[IO]] =
            channel(protocol)
          def closed = closeReason.get
          def abort(reason: NetworkError) = closeReason.complete(reason).void
        val rules = BlockRules.from[IO, BigInt] { (_, header, raw) =>
          IO(assertEquals(raw, bodies.getOrElse(header.hash, fail("unannounced block")))) *>
            applied.update(_ :+ header.hash).as(header.blockNo)
        }
        for
          follower <- Follower.create[IO, BigInt](
            rules,
            _ => IO.unit,
            ForgedChain.geometry,
            initialViews = Map(base.hash -> SelectView.of(base))
          )
          pool <- BlockPool.create[IO](BlockPool.FetchPolicy(), follower.fetchSelection)
          connection = NodeToNodeConnection(15, chainSync, blockFetch, mux)
          outcome <- Peer
            .over(
              connection,
              Peer.Settings(
                SocketAddress[Host](ip"10.4.8.1", port"3001"),
                42,
                readAhead = 16,
                byronEpochSlots = ForgedChain.geometry.byron.epochSlots
              ),
              _ => IO.unit,
              pool = Some(pool)
            )
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
                      if current.tip == b.last.point then IO.pure(Right(current))
                      else IO.sleep(5.millis).as(Left(()))
                    }
                  }
                  IO.race(reached, running.joinWithNever).flatMap {
                    case Left(current) => IO.pure(current)
                    case Right(_) =>
                      IO(fail("follower returned before replacement branch was adopted"))
                  }
                }
            }
          observed <- applied.get
          ranges <- requests.get
        yield (outcome, observed, ranges)
      }.timeout(20.seconds)
    yield
      val (finalChain, observed, ranges) = result
      assertEquals(finalChain.tip, b.last.point)
      assert(observed.contains(a.head.hash), "first batch was applied")
      assert(
        observed.contains(a.last.hash),
        "retired header was fetched before its queued rollback"
      )
      assertEquals(finalChain.blocks.map(_._1.hash), b.map(_.hash))
      assert(b.forall(h => observed.contains(h.hash)))
      assert(ranges.exists { (from, to) => from == a.last.point && to == a.last.point })
  }
