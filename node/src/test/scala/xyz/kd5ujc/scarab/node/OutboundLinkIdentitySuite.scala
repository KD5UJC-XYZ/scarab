// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{NodeError, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** Same logical peer, overlapping connection attempts, and real OutboundPeers resources. The
  * distinct socket keys let the real connection manager admit both attempts while the test controls
  * which bearer reaches handshake first.
  */
class OutboundLinkIdentitySuite extends FailsOnUncaught:
  private val magic = 42L
  private val peer = InMemoryPeers.address(7)

  private def handlers: ServerHandlers[IO, NtNHeader] =
    val cursor = new ChainSyncServer.Source[IO, NtNHeader]:
      def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
      def poll = IO.pure(None)
      def await = IO.never[ChainSyncServer.Update[NtNHeader]]
    val blocks = new BlockFetchServer.Blocks[IO]:
      def range(from: ChainSync.Point, to: ChainSync.Point) = IO.pure(None)
    val transactions = new TxSubmissionServer.Sink[IO]:
      def known(id: xyz.kd5ujc.scarab.codec.Bytes) = IO.pure(false)
      def submit(originals: Vector[xyz.kd5ujc.scarab.codec.Bytes]) = IO.unit
    ServerHandlers(NtNHeader.codec, Resource.pure(cursor), blocks, transactions)

  private final class GatedTransport(
      underlying: InMemoryPeers,
      firstStarted: Deferred[IO, Unit],
      releaseFirst: Deferred[IO, Unit],
      failFirst: Boolean,
      defectFirst: Option[Throwable],
      defectInResolution: Boolean,
      finalizeBearer: IO[Unit],
      afterBearer: IO[Unit]
  ) extends Transport[IO, PeerNetwork.Addr]:
    private val firstPort = port"31001"
    private val secondPort = port"31002"
    private val next = Ref.unsafe[IO, Int](0)

    def resolve(address: PeerNetwork.Addr) =
      val resolving = next.getAndUpdate(_ + 1).map { attempt =>
        val port = if attempt == 0 then firstPort else secondPort
        Right(SocketAddress(ipv4"127.0.0.1", port))
      }
      if defectInResolution then defectFirst.fold(resolving)(IO.raiseError(_)) else resolving

    def bearer(address: PeerNetwork.Addr, remote: SocketAddress[IpAddress])(using
        raise: Raise[IO, NetworkError]
    ): Resource[IO, Bearer[IO]] =
      if remote.port == firstPort then
        Resource.eval(firstStarted.complete(()).void *> releaseFirst.get).flatMap { _ =>
          defectFirst match
            case Some(problem) => Resource.eval(IO.raiseError[Bearer[IO]](problem))
            case None if failFirst =>
              Resource.eval(
                raise.raise[NetworkError, Bearer[IO]](
                  NetworkError
                    .Connect(address.toString, new java.io.IOException("old attempt failed"))
                )
              )
            case None =>
              underlying
                .bearer(address, remote)
                .onFinalize(finalizeBearer)
                .evalTap(_ => afterBearer)
        }
      else underlying.bearer(address, remote).onFinalize(finalizeBearer).evalTap(_ => afterBearer)

  private final case class Fixture(
      outbound: OutboundPeers[IO, PeerNetwork.Addr],
      transport: InMemoryPeers,
      manager: ConnectionManager[IO]
  )

  private def fixture(
      firstStarted: Deferred[IO, Unit],
      releaseFirst: Deferred[IO, Unit],
      failFirst: Boolean,
      follow: OutboundPeers.Follow[IO],
      report: Event[PeerNetwork.Addr] => IO[Unit] = _ => IO.unit,
      transactions: Option[TxSubmissionClient.Source[IO]] = None,
      defectFirst: Option[Throwable] = None,
      fatal: Fatal => IO[Unit] = _ => IO.unit,
      defectInResolution: Boolean = false,
      connected: Option[(PeerNetwork.Addr, SocketAddress[IpAddress]) => IO[Unit]] = None,
      finalizeBearer: IO[Unit] = IO.unit,
      afterBearer: IO[Unit] = IO.unit
  ): Resource[IO, Fixture] =
    for
      node <- NodeScope.resource
      _ <- Resource.eval(IO.unit)
      fixture <- Supervisor[IO](await = false).evalMap { supervisor =>
        given Handle[IO, NodeError] = node
        for
          pool <- BlockPool.create[IO]
          transport <- InMemoryPeers.create(magic)
          _ <- transport.serve(peer, handlers)
          manager <- ConnectionManager.create[IO](ConnectionManager.Limits(maxOutbound = 4))
          metrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
          given Stateful[IO, PeerMetrics[PeerNetwork.Addr]] = RefStateful(metrics)
          hot = OutboundPeers.Hot[IO](
            magic,
            NodeToNode.Settings(),
            Peer
              .Settings(SocketAddress(host"127.0.0.1", port"3001"), magic, byronEpochSlots = 21600),
            follow,
            _ => IO.unit,
            ChainSyncHeights.origin,
            None,
            transactions,
            None,
            pool,
            _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
            _ => IO.unit,
            fatal
          )
          gated = new GatedTransport(
            transport,
            firstStarted,
            releaseFirst,
            failFirst,
            defectFirst,
            defectInResolution,
            finalizeBearer,
            afterBearer
          )
          outbound <- OutboundPeers.create(
            gated,
            manager,
            OutboundPeers.Config(),
            hot,
            supervisor,
            report,
            summon[Stateful[IO, PeerMetrics[PeerNetwork.Addr]]],
            connected
          )
        yield Fixture(outbound, transport, manager)
      }
    yield fixture

  private def overlap(failFirst: Boolean): IO[Unit] =
    (Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { (firstStarted, releaseFirst) =>
      // Identity admission is tested with an idle application; no server reply is held open.
      fixture(firstStarted, releaseFirst, failFirst, follow = (_, _) => IO.unit).use { current =>
        for
          oldAttempt <- current.outbound
            .establish(peer, DiffusionMode.InitiatorAndResponder, Provenance.Outbound)
            .start
          _ <- firstStarted.get.timeout(2.seconds)
          newResult <- current.outbound.establish(
            peer,
            DiffusionMode.InitiatorAndResponder,
            Provenance.Outbound
          )
          _ <- IO(assert(newResult.isRight))
          _ <- releaseFirst.complete(())
          oldResult <- oldAttempt.joinWithNever.timeout(5.seconds)
          _ <- IO {
            if failFirst then
              oldResult match
                case Left(
                      EstablishmentError.Failed(
                        PeerError.Unavailable(
                          PeerError.Unavailability.Network(_: NetworkError.Connect)
                        )
                      )
                    ) =>
                  ()
                case other => fail(s"expected original connect failure, obtained $other")
            else assertEquals(oldResult, Left(EstablishmentError.Superseded))
          }
          stillInstalled <- current.outbound.activate(peer).timeout(5.seconds)
          _ <- IO(assertEquals(stillInstalled, Right(())))
          _ <- current.outbound.close(peer).timeout(5.seconds)
        yield ()
      }
    }

  test("an old failing attempt cannot remove a newer ready link for the same peer") {
    overlap(failFirst = true)
  }

  test("an old late handshake cannot overwrite a newer ready link for the same peer") {
    overlap(failFirst = false)
  }

  test("normal no-intersection keeps the established bearer warm with the native return delay") {
    for
      firstStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      ended <- Deferred[IO, Observation]
      follow: OutboundPeers.Follow[IO] = (over, _) =>
        over.intersect(Vector(ChainSync.Point.Origin)).flatMap {
          case None    => Raise[IO, NodeError].raise[NodeError, Unit](PeerError.NoIntersection)
          case Some(_) => IO.raiseError(new AssertionError("server has no intersection"))
        }
      report = (event: Event[PeerNetwork.Addr]) =>
        event match
          case Event.Observed(_, observation, _, _) => ended.complete(observation).void
          case _                                    => IO.unit
      _ <- releaseFirst.complete(())
      _ <- fixture(firstStarted, releaseFirst, false, follow, report).use { current =>
        for
          established <- current.outbound.establish(
            peer,
            DiffusionMode.InitiatorOnly,
            Provenance.Outbound
          )
          _ <- IO(assert(established.isRight))
          activated <- current.outbound.activate(peer)
          _ <- IO(assertEquals(activated, Right(())))
          observation <- ended.get.timeout(5.seconds)
          closed <- current.transport.closedConnections
          _ <- IO {
            assertEquals(
              observation,
              Observation.HotEnded(
                Some(120.seconds),
                SessionEnd.returnReason(SessionEnd.NoMoreIntersection)
              )
            )
            assertEquals(closed.getOrElse(peer, 0), 0)
          }
          // The same connection can start another hot conversation; the server is restarted on demand.
          again <- current.outbound.activate(peer)
          _ <- IO(assertEquals(again, Right(())))
          opened <- current.transport.connections
          _ <- IO(assertEquals(opened.getOrElse(peer, 0), 1))
        yield ()
      }
    yield ()
  }

  test("a held hot-return notification cannot block a new activation or demote its results") {
    for
      firstStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      reporting <- Deferred[IO, Unit]
      releaseReport <- Deferred[IO, Unit]
      secondRunning <- Deferred[IO, Unit]
      finishSecond <- Deferred[IO, Unit]
      secondReported <- Deferred[IO, Unit]
      activations <- Ref.of[IO, Int](0)
      observations <- Ref.of[IO, Vector[Observation]](Vector.empty)
      follow: OutboundPeers.Follow[IO] = (_, _) =>
        activations.getAndUpdate(_ + 1).flatMap {
          case 0 => IO.unit
          case _ => secondRunning.complete(()).void *> finishSecond.get
        }
      report = (event: Event[PeerNetwork.Addr]) =>
        event match
          case Event.Observed(_, observation, _, _) =>
            observations.updateAndGet(_ :+ observation).flatMap {
              case seen if seen.size == 1 => reporting.complete(()).void *> releaseReport.get
              case _                      => secondReported.complete(()).void
            }
          case _ => IO.unit
      _ <- releaseFirst.complete(())
      _ <- fixture(firstStarted, releaseFirst, false, follow, report).use { current =>
        (for
          established <- current.outbound.establish(
            peer,
            DiffusionMode.InitiatorOnly,
            Provenance.Outbound
          )
          first <- current.outbound.activate(peer)
          _ <- reporting.get.timeout(5.seconds)
          handle <- current.outbound
            .monitorPeerConnection(peer)
            .flatMap(_.liftTo[IO](new AssertionError("missing connection handle")))
          warm <- handle.read
          second <- current.outbound.activate(peer).timeout(5.seconds)
          _ <- secondRunning.get.timeout(5.seconds)
          hot <- handle.read
          _ <- releaseReport.complete(())
          _ <- finishSecond.complete(())
          _ <- secondReported.get.timeout(5.seconds)
          finalState <- handle.read
          seen <- observations.get
          opened <- current.transport.connections
          _ <- IO {
            assert(established.isRight)
            assertEquals(first, Right(()))
            assertEquals(second, Right(()))
            assertEquals(
              warm,
              PeerStateActions.Monitoring(PeerStateActions.Status.Warm, Some(10.seconds))
            )
            assertEquals(hot, PeerStateActions.Monitoring(PeerStateActions.Status.Hot, None))
            assertEquals(finalState.status, PeerStateActions.Status.Warm)
            assertEquals(
              seen,
              Vector.fill(2)(
                Observation.HotEnded(Some(10.seconds), SessionEnd.returnReason(SessionEnd.Returned))
              )
            )
            assertEquals(opened.getOrElse(peer, 0), 1)
          }
        yield ()).guarantee(releaseReport.complete(()).void *> finishSecond.complete(()).void)
      }
    yield ()
  }

  test("a retained handle reaches Cold and keeps its return delay after reconnecting") {
    for
      firstStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      ended <- Deferred[IO, Unit]
      follow: OutboundPeers.Follow[IO] = (over, _) =>
        over.intersect(Vector(ChainSync.Point.Origin)) *>
          Raise[IO, NodeError].raise[NodeError, Unit](PeerError.NoIntersection)
      report = (event: Event[PeerNetwork.Addr]) =>
        event match
          case Event.Observed(_, _: Observation.HotEnded, _, _) => ended.complete(()).void
          case _                                                => IO.unit
      _ <- releaseFirst.complete(())
      _ <- fixture(firstStarted, releaseFirst, false, follow, report).use { current =>
        for
          _ <- current.outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
          old <- current.outbound
            .monitorPeerConnection(peer)
            .flatMap(_.liftTo[IO](new AssertionError("missing old handle")))
          _ <- current.outbound.activate(peer)
          _ <- ended.get.timeout(5.seconds)
          closed <- current.outbound.close(peer).timeout(5.seconds)
          cold <- cats
            .Monad[IO]
            .tailRecM(()) { _ =>
              old.read.flatMap {
                case value if value.status == PeerStateActions.Status.Cold => IO.pure(Right(value))
                case _                                                     => IO.cede.as(Left(()))
              }
            }
            .timeout(5.seconds)
          _ <- current.outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
          fresh <- current.outbound
            .monitorPeerConnection(peer)
            .flatMap(_.liftTo[IO](new AssertionError("missing new handle")))
          previous <- old.read
          // These commands were captured before the address was reused. None may target fresh.
          oldActivation <- old.activate(ChainSyncTimeouts.PeerTrust.Untrustable)
          oldDeactivation <- old.deactivate
          oldClose <- old.close
          currentState <- fresh.read
          _ <- IO {
            assertEquals(
              closed.map(result => result.status -> result.completion),
              Right(PeerStatus.Cooling -> PeerStateActions.CloseCompletion.Clean)
            )
            assertEquals(
              cold,
              PeerStateActions.Monitoring(PeerStateActions.Status.Cold, Some(120.seconds))
            )
            assertEquals(previous, cold)
            assertEquals(
              oldActivation,
              Left(PeerActionError.Inactive(PeerOperation.Activate, PeerStatus.Cold))
            )
            assertEquals(
              oldDeactivation,
              Left(
                PeerStateActions.Failure(
                  PeerActionError.Inactive(PeerOperation.Deactivate, PeerStatus.Cold),
                  PeerStateActions.Release.Released
                )
              )
            )
            assertEquals(
              oldClose,
              Right(
                PeerStateActions.CloseResult(
                  PeerStateActions.Release.Released,
                  status = PeerStatus.Cold
                )
              )
            )
            assert(old.identity != fresh.identity)
            assertEquals(
              currentState,
              PeerStateActions.Monitoring(PeerStateActions.Status.Warm, None)
            )
          }
        yield ()
      }
    yield ()
  }

  test("a close that wins while deactivation drains cannot be reported as warm") {
    cats.effect.testkit.TestControl.executeEmbed {
      for
        firstStarted <- Deferred[IO, Unit]
        releaseFirst <- Deferred[IO, Unit]
        following <- Deferred[IO, Unit]
        releaseReturn <- Deferred[IO, Unit]
        demotionSeen <- Deferred[IO, Unit]
        txWaiting <- Deferred[IO, Unit]
        transactions = new TxSubmissionClient.Source[IO]:
          def after(ticket: Long, max: Int) = IO.pure(Vector.empty[TxSubmissionClient.Held])
          def awaitAfter(ticket: Long) =
            (txWaiting.complete(()) *> IO.never[Unit]).onCancel(demotionSeen.complete(()).void)
          def body(id: xyz.kd5ujc.scarab.codec.Bytes) = IO.pure(None)
        follow: OutboundPeers.Follow[IO] = (_, _) =>
          following.complete(()).void *> releaseReturn.get
        _ <- releaseFirst.complete(())
        _ <- fixture(firstStarted, releaseFirst, false, follow, transactions = Some(transactions))
          .use { current =>
            (for
              _ <- current.outbound.establish(
                peer,
                DiffusionMode.InitiatorOnly,
                Provenance.Outbound
              )
              _ <- current.outbound.activate(peer)
              _ <- following.get *> txWaiting.get
              handle <- current.outbound
                .monitorPeerConnection(peer)
                .flatMap(_.liftTo[IO](new AssertionError("missing handle")))
              _ <- Resource.make(current.outbound.deactivate(peer).start)(_.cancel).use {
                deactivation =>
                  // Shared Terminate has reached Tx; hold the idle follower while close runs.
                  demotionSeen.get *> Resource
                    .make(current.outbound.close(peer).start)(_.cancel)
                    .use { closing =>
                      for
                        _ <- cats.Monad[IO].tailRecM(()) { _ =>
                          handle.read.flatMap {
                            case value if value.status == PeerStateActions.Status.Cooling =>
                              IO.pure(Right(()))
                            case _ => IO.cede.as(Left(()))
                          }
                        }
                        before <- IO.monotonic
                        duplicate <- handle.close
                        after <- IO.monotonic
                        _ <- releaseReturn.complete(())
                        result <- deactivation.joinWithNever
                        released <- closing.joinWithNever
                        _ <- IO {
                          assert(result.exists(_ != PeerStateActions.Deactivation.Warm))
                          assertEquals(
                            duplicate,
                            Right(PeerStateActions.CloseResult(PeerStateActions.Release.Lingering))
                          )
                          assertEquals(after - before, Duration.Zero)
                          assertEquals(
                            released.map(value => value.status -> value.completion),
                            Right(PeerStatus.Cooling -> PeerStateActions.CloseCompletion.Clean)
                          )
                        }
                      yield ()
                    }
              }
            yield ()).guarantee(releaseReturn.complete(()).void)
          }
      yield ()
    }
  }

  test("activation failures retain the actual Cold or Hot status") {
    for
      firstStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      following <- Deferred[IO, Unit]
      releaseFollow <- Deferred[IO, Unit]
      follow: OutboundPeers.Follow[IO] = (_, _) => following.complete(()).void *> releaseFollow.get
      _ <- releaseFirst.complete(())
      _ <- fixture(firstStarted, releaseFirst, false, follow).use { current =>
        (for
          cold <- current.outbound.activate(peer)
          _ <- current.outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
          active <- current.outbound.activate(peer)
          _ <- following.get.timeout(5.seconds)
          hot <- current.outbound.activate(peer)
          _ <- IO {
            assertEquals(
              cold,
              Left(PeerActionError.Inactive(PeerOperation.Activate, PeerStatus.Cold))
            )
            assertEquals(active, Right(()))
            assertEquals(
              hot,
              Left(PeerActionError.Inactive(PeerOperation.Activate, PeerStatus.Hot))
            )
          }
        yield ()).guarantee(releaseFollow.complete(()).void)
      }
    yield ()
  }

  List(false, true).foreach { resolution =>
    test(
      s"a pre-ready defect reaches its caller and fatal sink without success (resolution=$resolution)"
    ) {
      for
        firstStarted <- Deferred[IO, Unit]
        releaseFirst <- Deferred[IO, Unit]
        fatal <- Deferred[IO, Fatal]
        defect = new IllegalStateException("injected acquisition defect")
        _ <- releaseFirst.complete(())
        _ <- fixture(
          firstStarted,
          releaseFirst,
          false,
          follow = (_, _) => IO.unit,
          defectFirst = Some(defect),
          fatal = error => fatal.complete(error).void,
          defectInResolution = resolution
        ).use { current =>
          for
            result <- current.outbound
              .establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
              .timeout(5.seconds)
            stopped <- fatal.get.timeout(5.seconds)
            handle <- current.outbound.monitorPeerConnection(peer)
            _ <- IO {
              assertEquals(result, Left(EstablishmentError.Defect(defect)))
              assertEquals(stopped, Fatal.Defect(defect))
              assertEquals(handle, None)
            }
          yield ()
        }
      yield ()
    }
  }

  List(false, true).foreach { reporting =>
    test(
      s"an installation callback defect cleans up Cold before returning its cause (reporting=$reporting)"
    ) {
      for
        firstStarted <- Deferred[IO, Unit]
        releaseFirst <- Deferred[IO, Unit]
        fatal <- Deferred[IO, Fatal]
        defect = new IllegalStateException("injected connected callback defect")
        _ <- releaseFirst.complete(())
        _ <- fixture(
          firstStarted,
          releaseFirst,
          false,
          follow = (_, _) => IO.unit,
          fatal = error => fatal.complete(error).void,
          report = event =>
            event match
              case _: Event.SharingNegotiated[?] if reporting => IO.raiseError(defect)
              case _                                          => IO.unit,
          connected = if reporting then None else Some((_, _) => IO.raiseError(defect))
        ).use { current =>
          for
            result <- current.outbound
              .establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
              .timeout(5.seconds)
            stopped <- fatal.get.timeout(5.seconds)
            handle <- current.outbound
              .monitorPeerConnection(peer)
              .flatMap(_.liftTo[IO](new AssertionError("missing partially installed handle")))
            status <- handle.read
            live <- current.manager.lookup(SocketAddress(ipv4"127.0.0.1", port"31001"))
            closed <- current.transport.closedConnections
            _ <- IO {
              assertEquals(result, Left(EstablishmentError.Defect(defect)))
              assertEquals(stopped, Fatal.Defect(defect))
              assertEquals(status.status, PeerStatus.Cold)
              assert(live.forall(!_.state.isLive))
              assertEquals(closed.getOrElse(peer, 0), 1)
            }
          yield ()
        }
      yield ()
    }

  }

  test("closing an absent address reports Cold without waiting") {
    cats.effect.testkit.TestControl.executeEmbed {
      for
        firstStarted <- Deferred[IO, Unit]
        releaseFirst <- Deferred[IO, Unit]
        _ <- fixture(firstStarted, releaseFirst, false, follow = (_, _) => IO.unit).use { current =>
          for
            before <- IO.monotonic
            result <- current.outbound.close(peer)
            after <- IO.monotonic
            _ <- IO {
              assertEquals(
                result,
                Right(
                  PeerStateActions.CloseResult(
                    PeerStateActions.Release.Released,
                    status = PeerStatus.Cold
                  )
                )
              )
              assertEquals(after - before, Duration.Zero)
            }
          yield ()
        }
      yield ()
    }
  }

  test("cancelling before ready releases the partial installation and resets Cold") {
    for
      firstStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      callbackEntered <- Deferred[IO, Unit]
      fatal <- Deferred[IO, Fatal]
      _ <- releaseFirst.complete(())
      _ <- fixture(
        firstStarted,
        releaseFirst,
        false,
        follow = (_, _) => IO.unit,
        fatal = error => fatal.complete(error).void,
        connected = Some((_, _) => callbackEntered.complete(()) *> IO.never[Unit])
      ).use { current =>
        Resource
          .make(
            current.outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound).start
          )(_.cancel)
          .use { acquiring =>
            for
              _ <- callbackEntered.get.timeout(5.seconds)
              handle <- current.outbound
                .monitorPeerConnection(peer)
                .flatMap(_.liftTo[IO](new AssertionError("missing partially installed handle")))
              _ <- acquiring.cancel.timeout(5.seconds)
              status <- handle.read
              live <- current.manager.lookup(SocketAddress(ipv4"127.0.0.1", port"31001"))
              closed <- current.transport.closedConnections
              stopped <- fatal.tryGet
              _ <- IO {
                assertEquals(status.status, PeerStatus.Cold)
                assert(live.forall(!_.state.isLive))
                assertEquals(closed.getOrElse(peer, 0), 1)
                assertEquals(stopped, None)
              }
            yield ()
          }
      }
    yield ()
  }

  test("cancelling the caller after ready leaves the returned connection owned by its monitor") {
    for
      firstStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      accepted <- Deferred[IO, Either[EstablishmentError, PeerStateActions.Connection[IO]]]
      _ <- releaseFirst.complete(())
      _ <- fixture(firstStarted, releaseFirst, false, follow = (_, _) => IO.unit).use { current =>
        Resource
          .make(
            (current.outbound
              .establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
              .flatMap(accepted.complete) *> IO.never[Unit]).start
          )(_.cancel)
          .use { caller =>
            for
              result <- accepted.get.timeout(5.seconds)
              handle <- result.fold(
                error => IO.raiseError(new AssertionError(error.message)),
                IO.pure
              )
              _ <- caller.cancel.timeout(5.seconds)
              status <- handle.read
              live <- current.manager.lookup(SocketAddress(ipv4"127.0.0.1", port"31001"))
              _ <- IO {
                assertEquals(status.status, PeerStatus.Warm)
                assert(live.exists(_.state.isLive))
              }
              _ <- handle.close *> handle.awaitCold.timeout(5.seconds)
            yield ()
          }
      }
    yield ()
  }

  List(false, true).foreach { cancelling =>
    test(
      s"a bearer finalizer defect reaches the fatal sink during teardown (cancelling=$cancelling)"
    ) {
      for
        firstStarted <- Deferred[IO, Unit]
        releaseFirst <- Deferred[IO, Unit]
        callbackEntered <- Deferred[IO, Unit]
        finalizerEntered <- Deferred[IO, Unit]
        finishFinalizer <- Deferred[IO, Unit]
        fatal <- Deferred[IO, Fatal]
        fatalCalls <- Ref.of[IO, Vector[Fatal]](Vector.empty)
        defect = new IllegalStateException("injected bearer finalizer defect")
        _ <- releaseFirst.complete(())
        _ <- fixture(
          firstStarted,
          releaseFirst,
          false,
          follow = (_, _) => IO.unit,
          fatal = error => fatalCalls.update(_ :+ error) *> fatal.complete(error).void,
          connected = Some((_, _) =>
            callbackEntered.complete(()) *> (if cancelling then IO.never[Unit] else IO.unit)
          ),
          finalizeBearer =
            finalizerEntered.complete(()) *> finishFinalizer.get *> IO.raiseError(defect)
        ).use { current =>
          Resource
            .make(
              current.outbound
                .establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
                .start
            )(_.cancel)
            .use { acquiring =>
              (for
                _ <- callbackEntered.get.timeout(5.seconds)
                handle <- current.outbound
                  .monitorPeerConnection(peer)
                  .flatMap(_.liftTo[IO](new AssertionError("missing installed handle")))
                teardown =
                  if cancelling then acquiring.cancel
                  else acquiring.joinWithNever *> handle.close.void
                _ <- Resource.make(teardown.start)(_.cancel).use { closing =>
                  for
                    _ <- finalizerEntered.get.timeout(5.seconds)
                    _ <- finishFinalizer.complete(())
                    stopped <- fatal.get.timeout(5.seconds)
                    _ <- closing.joinWithNever.timeout(5.seconds)
                    _ <- handle.awaitCold.timeout(5.seconds)
                    status <- handle.read
                    live <- current.manager.lookup(SocketAddress(ipv4"127.0.0.1", port"31001"))
                    _ <- IO {
                      assertEquals(stopped, Fatal.Defect(defect))
                      assertEquals(status.status, PeerStatus.Cold)
                      assert(live.forall(!_.state.isLive))
                    }
                  yield ()
                }
              yield ()).guarantee(finishFinalizer.complete(()).void)
            }
        }
        calls <- fatalCalls.get
        _ <- IO(assertEquals(calls, Vector(Fatal.Defect(defect))))
      yield ()
    }
  }

  test("a finalizer defect during cancellation of partial acquisition reaches the fatal sink") {
    for
      firstStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      bearerAcquired <- Deferred[IO, Unit]
      fatal <- Deferred[IO, Fatal]
      fatalCalls <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      defect = new IllegalStateException("injected partial-acquisition finalizer defect")
      _ <- releaseFirst.complete(())
      _ <- fixture(
        firstStarted,
        releaseFirst,
        false,
        follow = (_, _) => IO.unit,
        fatal = error => fatalCalls.update(_ :+ error) *> fatal.complete(error).void,
        afterBearer = bearerAcquired.complete(()) *> IO.never[Unit],
        finalizeBearer = IO.raiseError(defect)
      ).use { current =>
        Resource
          .make(
            current.outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound).start
          )(_.cancel)
          .use { acquiring =>
            for
              _ <- bearerAcquired.get.timeout(5.seconds)
              _ <- acquiring.cancel.timeout(5.seconds)
              stopped <- fatal.get.timeout(5.seconds)
              handle <- current.outbound.monitorPeerConnection(peer)
              live <- current.manager.lookup(SocketAddress(ipv4"127.0.0.1", port"31001"))
              _ <- IO {
                assertEquals(stopped, Fatal.Defect(defect))
                assertEquals(handle, None)
                assert(live.forall(!_.state.isLive))
              }
            yield ()
          }
      }
      calls <- fatalCalls.get
      _ <- IO(assertEquals(calls, Vector(Fatal.Defect(defect))))
    yield ()
  }

  test("an acquisition defect survives caller cancellation queued during resource cleanup") {
    for
      firstStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      finalizerEntered <- Deferred[IO, Unit]
      finishFinalizer <- Deferred[IO, Unit]
      fatalCalls <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      defect = new IllegalStateException("original acquisition defect before cleanup")
      _ <- releaseFirst.complete(())
      program = fixture(
        firstStarted,
        releaseFirst,
        false,
        follow = (_, _) => IO.unit,
        fatal = error => fatalCalls.update(_ :+ error),
        afterBearer = IO.raiseError(defect),
        finalizeBearer = finalizerEntered.complete(()) *> finishFinalizer.get
      ).use { current =>
        Resource
          .make(
            current.outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound).start
          )(_.cancel)
          .use { acquiring =>
            finalizerEntered.get *> acquiring.cancel
          }
      }
      control <- cats.effect.testkit.TestControl.execute(program)
      _ <- Resource
        .make(IO.pure(control))(_ => finishFinalizer.complete(()).void *> control.tickAll)
        .use { clock =>
          for
            // Drain every runnable fiber: cancellation is now waiting on the held finalizer.
            _ <- clock.tick
            entered <- finalizerEntered.tryGet
            _ <- IO(assertEquals(entered, Some(())))
            _ <- finishFinalizer.complete(())
            _ <- clock.tick
            result <- clock.results
            calls <- fatalCalls.get
            _ <- IO {
              assertEquals(
                result,
                Some(cats.effect.kernel.Outcome.Succeeded[cats.Id, Throwable, Unit](()))
              )
              assertEquals(calls, Vector(Fatal.Defect(defect)))
            }
          yield ()
        }
    yield ()
  }
