// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{BlockFailure, NodeError, ValidationError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** Late blame goes through the real outbound reporter and connection manager, after reconnecting
  * the source. Its innocent consumer's bearer stays open.
  */
class CachedBlockSourceSuite extends FailsOnUncaught:
  private val magic = 42L
  private val hash = Bytes.wrap(Array[Byte](1))
  private val block = Bytes.wrap(Array[Byte](2))
  private val refusal = ValidationError(ChainSync.Point.Origin, BlockFailure.HeaderDiffers)

  private def handlers: ServerHandlers[IO, NtNHeader] =
    val cursor = new ChainSyncServer.Source[IO, NtNHeader]:
      def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
      def poll = IO.pure(None)
      def await = IO.never[ChainSyncServer.Update[NtNHeader]]
    val blocks = new BlockFetchServer.Blocks[IO]:
      def range(from: ChainSync.Point, to: ChainSync.Point, maxBlocks: Int) = IO.pure(None)
    val transactions = new TxSubmissionServer.Sink[IO]:
      def known(id: Bytes) = IO.pure(false)
      def submit(originals: Vector[Bytes]) = IO.unit
    ServerHandlers(NtNHeader.codec, Resource.pure(cursor), blocks, transactions)

  List((true, false), (false, false), (false, true)).foreach { (originalLive, replacement) =>
    test(
      s"cached attribution targets only its adding bearer (live=$originalLive replacement=$replacement)"
    ) {
      NodeScope.resource.use { node =>
        given Handle[IO, NodeError] = node
        Supervisor[IO](await = false).use { supervisor =>
          for
            pool <- BlockPool.create[IO]
            transport <- InMemoryPeers.create(magic)
            source = InMemoryPeers.address(1)
            consumer = InMemoryPeers.address(2)
            _ <- List(source, consumer).traverse_(transport.serve(_, handlers))
            manager <- ConnectionManager.create[IO]()
            events <- Ref.of[IO, Vector[Event[PeerNetwork.Addr]]](Vector.empty)
            entered <- cats.effect.std.Queue.unbounded[IO, Unit]
            finalized <- Ref.of[IO, Int](0)
            metrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
            given Stateful[IO, PeerMetrics[PeerNetwork.Addr]] = RefStateful(metrics)
            hot = OutboundPeers.Hot[IO](
              magic,
              NodeToNode.Settings(),
              Peer
                .Settings(
                  SocketAddress(host"127.0.0.1", port"3001"),
                  magic,
                  byronEpochSlots = 21600
                ),
              (_, _) => (entered.offer(()) *> IO.never[Unit]).guarantee(finalized.update(_ + 1)),
              _ => IO.unit,
              ChainSyncHeights.origin,
              None,
              None,
              None,
              pool,
              _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
              _ => IO.unit,
              _ => IO.unit
            )
            outbound <- OutboundPeers.create(
              transport,
              manager,
              OutboundPeers.Config(),
              hot,
              supervisor,
              e => events.update(_ :+ e),
              summon[Stateful[IO, PeerMetrics[PeerNetwork.Addr]]]
            )
            first <- outbound.establish(
              source,
              DiffusionMode.InitiatorAndResponder,
              Provenance.Outbound
            )
            _ <- outbound.activate(source)
            _ <- entered.take.timeout(2.seconds)
            sourceKey <- outbound
              .cachedSource(source)
              .flatMap(IO.fromOption(_)(new AssertionError("missing source identity")))
            _ <- pool.session(Some(outbound.rejectedCachedBlock(sourceKey, _))).use { claims =>
              claims.claim(Vector(hash)) *> claims.fill(Vector(hash -> block))
            }
            _ <- outbound.close(source).unlessA(originalLive)
            second <- outbound
              .establish(source, DiffusionMode.InitiatorAndResponder, Provenance.Outbound)
              .whenA(replacement)
            _ <- outbound.activate(source).whenA(replacement)
            _ <- entered.take.timeout(2.seconds).whenA(replacement)
            other <- outbound.establish(
              consumer,
              DiffusionMode.InitiatorAndResponder,
              Provenance.Outbound
            )
            remote <- transport
              .resolve(source)
              .flatMap(e => IO.fromOption(e.toOption)(new AssertionError("source address")))
            current <- manager.lookup(remote)
            _ <- pool.session.use { innocent =>
              for
                taken <- innocent.take(hash)
                cached <- IO.fromOption(taken)(
                  new AssertionError("cached block lost on disconnect")
                )
                _ <- innocent.distrust(cached.origin, refusal)
                _ <- innocent.distrust(cached.origin, refusal)
              yield ()
            }
            _ <- if originalLive then current.traverse_(_.closed.timeout(2.seconds)) else IO.unit
            replacementState <-
              if replacement then manager.lookup(remote).map(_.exists(_.state.isLive))
              else IO.pure(false)
            consumerRemote <- transport
              .resolve(consumer)
              .flatMap(e => IO.fromOption(e.toOption)(new AssertionError("consumer address")))
            innocent <- manager.lookup(consumerRemote)
            observed <- events.get
            sourceFailures = observed.collect {
              case Event.Observed(peer, Observation.Lost(failure, _, false, _), _)
                  if peer == source =>
                failure
            }
            rejected = observed.collect {
              case Event.Observed(
                    peer,
                    Observation.CachedBlockRejected(cause, generation, live),
                    _
                  ) =>
                (peer, cause.cause, generation, live)
            }
            _ <- outbound.close(consumer)
            _ <- outbound.close(source)
            finished <- finalized.get
          yield
            assertEquals(finished, if replacement then 2 else 1)
            assertEquals(first, None)
            assertEquals(second, ())
            assertEquals(other, None)
            assert(innocent.isDefined)
            assertEquals(replacementState, replacement)
            assertEquals(
              sourceFailures,
              if originalLive then Vector(FailureClass.Misbehaved) else Vector.empty
            )
            assertEquals(
              rejected,
              Vector((source, refusal, sourceKey.activationGeneration, originalLive))
            )
        }
      }
    }

  }

  test("a real fetched original cannot punish the next hot client on the same bearer") {
    import cats.effect.Deferred
    import cats.mtl.Raise
    import xyz.kd5ujc.scarab.node.forge.ForgedChain
    import xyz.kd5ujc.scarab.protocol.ChainSync.Point
    NodeScope.resource.use { node =>
      given Handle[IO, NodeError] = node
      Supervisor[IO](await = false).use { supervisor =>
        for
          chain <- ForgedChain.forge(5000 until 5008)
          header = chain.headers.head
          tip = ChainSync.Tip(
            chain.headers(Peer.Depth).point,
            ChainSync.UInt64.saturating(chain.headers(Peer.Depth).blockNo)
          )
          delivered <- Deferred[IO, Unit]
          filled <- Deferred[IO, Unit]
          finishFirst <- Deferred[IO, Unit]
          firstEnded <- Deferred[IO, Unit]
          secondStarted <- Deferred[IO, Unit]
          activations <- Ref.of[IO, Int](0)
          pool <- BlockPool.create[IO]
          events <- Ref.of[IO, Vector[Event[PeerNetwork.Addr]]](Vector.empty)
          cursor = Resource.eval(Ref.of[IO, Int](0)).map { position =>
            new ChainSyncServer.Source[IO, NtNHeader]:
              def intersect(points: Vector[Point]) =
                if points.contains(Point.Origin) then position.set(0).as(Right(Point.Origin -> tip))
                else IO.pure(Left(tip))
              def poll = position.getAndUpdate(_ + 1).map { i =>
                chain.headers
                  .take(Peer.Depth + 1)
                  .lift(i)
                  .map(h => ChainSyncServer.Update.RollForward(NtNHeader(h.era, h.raw, None), tip))
              }
              def await = IO.never[ChainSyncServer.Update[NtNHeader]]
          }
          blocks = new BlockFetchServer.Blocks[IO]:
            def range(from: Point, to: Point, max: Int) =
              val at = chain.headers.indexWhere(_.point == from)
              val raw = Option.when(at >= 0 && from == to)(
                fs2.Stream.emit(chain.raws(at.max(0))).covary[IO]
              )
              filled.complete(()).void.whenA(to == chain.headers(Peer.Depth).point).as(raw)
          servers = ServerHandlers(NtNHeader.codec, cursor, blocks, handlers.transactions)
          transport <- InMemoryPeers.create(magic)
          peer = InMemoryPeers.address(1)
          _ <- transport.serve(peer, servers)
          manager <- ConnectionManager.create[IO]()
          metrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
          follow: OutboundPeers.Follow[IO] = (over, _) =>
            (caller: Raise[IO, NodeError]) ?=>
              Handle
                .allow[xyz.kd5ujc.scarab.ledger.PeerError] {
                  for
                    activation <- activations.getAndUpdate(_ + 1)
                    intersection <- over.intersect(Vector(Point.Origin))
                    _ <- IO(assertEquals(intersection, Some(Point.Origin)))
                    _ <-
                      if activation == 0 then
                        for
                          next <- over.next
                          h <- next match
                            case SyncEvent.RollForward(h, _) => IO.pure(h)
                            case other => IO(fail(s"expected actual header, got $other"))
                          raw <- over.fetch(h)
                          _ <- IO(assertEquals(raw, chain.raws.head))
                          _ <- delivered.complete(()) *> finishFirst.get
                          missing <- over.intersect(Vector(chain.headers.last.point))
                          _ <- IO(assertEquals(missing, None))
                          _ <- Raise[IO, xyz.kd5ujc.scarab.ledger.PeerError]
                            .raise[xyz.kd5ujc.scarab.ledger.PeerError, Unit](
                              xyz.kd5ujc.scarab.ledger.PeerError.NoIntersection
                            )
                        yield ()
                      else secondStarted.complete(()).void *> IO.never[Unit]
                  yield ()
                }
                .rescue(e => caller.raise[NodeError, Unit](e))
          hot = OutboundPeers.Hot[IO](
            magic,
            NodeToNode.Settings(),
            Peer.Settings(
              SocketAddress(host"127.0.0.1", port"3001"),
              magic,
              readAhead = 1,
              byronEpochSlots = 21600
            ),
            follow,
            _ => IO.unit,
            ChainSyncHeights.origin,
            None,
            None,
            None,
            pool,
            _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
            _ => IO.unit,
            _ => IO.unit
          )
          outbound <- OutboundPeers.create(
            transport,
            manager,
            OutboundPeers.Config(),
            hot,
            supervisor,
            e =>
              events.update(_ :+ e) *> (e match
                case Event.Observed(_, Observation.HotEnded(_), _) => firstEnded.complete(()).void
                case _                                             => IO.unit),
            RefStateful(metrics)
          )
          _ <- outbound.establish(peer, DiffusionMode.InitiatorAndResponder, Provenance.Outbound)
          _ <- outbound.activate(peer)
          _ <- (delivered.get *> filled.get).timeout(5.seconds)
          old <- outbound
            .cachedSource(peer)
            .flatMap(IO.fromOption(_)(new AssertionError("no delivering source")))
          taken <- pool.session
            .use(_.take(header.hash))
            .flatMap(IO.fromOption(_)(new AssertionError("no actual cached original")))
          _ <- finishFirst.complete(())
          _ <- firstEnded.get.timeout(5.seconds)
          warmSource <- outbound.cachedSource(peer)
          _ <- IO(assertEquals(warmSource, None))
          _ <- outbound.activate(peer)
          _ <- secondStarted.get.timeout(5.seconds)
          successor <- outbound
            .cachedSource(peer)
            .flatMap(IO.fromOption(_)(new AssertionError("no successor source")))
          refusal = ValidationError(header.point, BlockFailure.HeaderDiffers)
          _ <- pool.session.use(_.distrust(taken.origin, refusal))
          opened <- transport.connections
          observed <- events.get
          remote <- transport
            .resolve(peer)
            .flatMap(e => IO.fromOption(e.toOption)(new AssertionError("source address")))
          connected <- manager.lookup(remote)
          active <- outbound.cachedSource(peer)
          _ <- outbound.close(peer)
        yield
          assert(successor.activationGeneration > old.activationGeneration)
          assertEquals(opened.getOrElse(peer, 0), 1)
          assert(connected.exists(_.state.isLive))
          assertEquals(active, Some(successor))
          assert(observed.exists {
            case Event.Observed(p, Observation.CachedBlockRejected(cause, generation, false), _) =>
              p == peer && cause.cause == refusal && generation == old.activationGeneration
            case _ => false
          })
      }
    }
  }
