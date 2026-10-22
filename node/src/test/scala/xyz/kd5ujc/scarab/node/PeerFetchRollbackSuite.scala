// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, BlockRules, SelectView, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{Header, NodeError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.genesis.{LeakyBucket, LimitOnPatience}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

/** Real header decoding, fetch coordination and follower adoption across a queued rollback. The
  * scripted transport controls batching; original forged headers and blocks remain intact.
  */
class PeerFetchRollbackSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  for streaming <- List(false, true) do
    test(
      if streaming then
        "a streamed prefix advances the ledger while a later header in the same batch waits"
      else "retired read-ahead headers and a longer replacement fork both remain fetchable"
    ) {
      for
        forks <- ForgedChain.competingForks(5000 until 5020, 5020 until 5100, 31, 47)
        (left, right) = forks
        common = left.headers.zip(right.headers).takeWhile((a, b) => a.hash == b.hash).size
        _ <- IO(
          assert(common > 0 && left.headers.size >= common + 4 && right.headers.size >= common + 3)
        )
        base = left.headers.take(common).last
        a = left.headers.drop(common).take(if streaming then 4 else 2)
        b = right.headers.drop(common).take(3)
        leftTip = ChainSync.Tip(
          left.headers.last.point,
          ChainSync.UInt64.saturating(left.headers.last.blockNo)
        )
        rightTip = ChainSync.Tip(
          right.headers.last.point,
          ChainSync.UInt64.saturating(right.headers.last.blockNo)
        )
        wire = (h: Header.Shelley) => NtNHeader(h.era, h.raw, None)
        scripts =
          if streaming then
            List(
              Vector(ChainSyncClient.Update.RollForward(wire(a.head), leftTip)),
              a.tail.map(h => ChainSyncClient.Update.RollForward(wire(h), leftTip))
            )
          else
            List(
              Vector(ChainSyncClient.Update.RollForward(wire(a.head), leftTip)),
              Vector(
                ChainSyncClient.Update.RollForward(wire(a.last), leftTip),
                ChainSyncClient.Update.RollBackward(base.point, rightTip),
                ChainSyncClient.Update.RollForward(wire(b.head), rightTip)
              ),
              Vector(ChainSyncClient.Update.RollForward(wire(b(1)), rightTip)),
              Vector(ChainSyncClient.Update.RollForward(wire(b.last), rightTip))
            )
        expected = if streaming then a else b
        remaining <- Ref.of[IO, List[Vector[ChainSyncClient.Update[NtNHeader]]]](scripts)
        requests <- Ref.of[IO, Vector[(ChainSync.Point, ChainSync.Point)]](Vector.empty)
        applied <- Ref.of[IO, Vector[Bytes]](Vector.empty)
        receivedBodies <- Ref.of[IO, Vector[(Bytes, Bytes)]](Vector.empty)
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
            override def requestRangeStreaming(from: ChainSync.Point, to: ChainSync.Point)(
                onBlock: Bytes => IO[Either[NetworkError, Unit]],
                @scala.annotation.unused observe: BlockFetchRuntime.Event => IO[Unit]
            )(using Raise[IO, NetworkError], cats.Monad[IO]): IO[Option[Vector[Bytes]]] =
              requestRange(from, to).flatTap(_.traverse_(_.traverse_ { raw =>
                val hash = bodies
                  .collectFirst { case (hash, bytes) if bytes == raw => hash }
                  .getOrElse(fail("body absent from original forged chain"))
                onBlock(raw).flatMap(_.fold(_.raise[IO, Unit], _ => IO.unit)) *>
                  // Only the streamed-prefix test delays the next wire body until this one
                  // is applied. Legacy batches publish after prefetch completes; making their
                  // transport wait for application would prevent that publication forever.
                  cats
                    .Monad[IO]
                    .tailRecM(()) { _ =>
                      applied.get.flatMap { hashes =>
                        if hashes.contains(hash) then IO.pure(Right(()))
                        else IO.sleep(5.millis).as(Left(()))
                      }
                    }
                    .whenA(streaming)
              }))
            def done(using Raise[IO, NetworkError]) = IO.unit
            def idle = IO.pure(true)
          val mux = new Mux[IO]:
            def installApplication(initiators: Set[Int], responders: Set[Int])(using
                cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
            ): IO[Unit] = IO.unit
            def awaitResponderActivity(using
                cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
            ) =
              IO.never[Unit]
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
            outcome <- LimitOnPatience
              .resource[IO](LeakyBucket.Config.disabled, IO.unit, error => IO(fail(error.message)))
              .flatMap { patience =>
                Peer.over(
                  connection,
                  Peer.Settings(
                    SocketAddress[Host](ip"10.4.8.1", port"3001"),
                    42,
                    readAhead = 16,
                    byronEpochSlots = ForgedChain.geometry.byron.epochSlots
                  ),
                  raws =>
                    receivedBodies.get.flatMap(seen =>
                      IO(
                        assert(
                          raws.forall(raw => seen.exists(_._2 == raw)),
                          "received originals must be persisted before preparation"
                        )
                      )
                    ),
                  pool = Some(pool),
                  receivedBody = Some((header, raw) =>
                    IO(
                      assertEquals(
                        raw,
                        bodies.getOrElse(header.hash, fail("unannounced original body"))
                      )
                    ) *>
                      receivedBodies.update(_ :+ (header.hash -> raw))
                  ),
                  patience = Option.when(streaming)(patience),
                  headers = Option.when(streaming)(
                    new HeaderCheck[IO]:
                      def start(intersection: ChainSync.Point): IO[HeaderGate[IO]] =
                        IO.pure(new HeaderGate[IO]:
                          def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
                          def accept(batch: Vector[SyncEvent])(using
                              Raise[IO, xyz.kd5ujc.scarab.ledger.PeerError]
                          ): IO[Unit] =
                            val needsEarlierBlock = batch.exists {
                              case SyncEvent.RollForward(h, _) => h.hash == a(2).hash
                              case _                           => false
                            }
                            IO(assertEquals(intersection, base.point)) *>
                              cats.Monad[IO].tailRecM(()) { _ =>
                                applied.get.flatMap { hashes =>
                                  if !needsEarlierBlock || hashes.contains(a(1).hash) then
                                    IO.pure(Right(()))
                                  else IO.sleep(5.millis).as(Left(()))
                                }
                              })
                  )
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
                        if current.tip == expected.last.point then IO.pure(Right(current))
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
        assertEquals(finalChain.tip, expected.last.point)
        assert(observed.contains(a.head.hash), "first batch was applied")
        assert(
          observed.contains(a.last.hash),
          "retired header was fetched before its queued rollback"
        )
        assertEquals(finalChain.blocks.map(_._1.hash), expected.map(_.hash))
        assert(expected.forall(h => observed.contains(h.hash)))
        if !streaming then assert(ranges.exists { (_, to) => to == a.last.point })
    }
