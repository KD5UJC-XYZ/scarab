// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{ChainError, PeerError, StorageError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.FailureClass
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.log.NodeEvent
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader, NtnVersion}

/** A decoder that throws on a peer's input is the connection's failure, not the node's: the policy
  * turns on where the exception came from (network `RethrowPolicy.hs`, `ShutdownPeer` for an
  * exception in a mini-protocol thread; consensus `Node/RethrowPolicy.hs`, `ShutdownNode` only for
  * storage). A defect of the node itself, and storage, still stop it.
  */
class PeerDefectPolicySuite extends FailsOnUncaught:
  private val cause = new IllegalStateException("injected decoder defect")

  /** A decoder's own typed refusal, which the defect boundary passes through unchanged. */
  private enum Refused:
    case Malformed

  private val sites = Vector(
    PeerError.InputSite.Wire(Protocols.ChainSync),
    PeerError.InputSite.Wire(Protocols.KeepAlive),
    PeerError.InputSite.Bearer,
    PeerError.InputSite.ChainSyncHeader,
    PeerError.InputSite.FetchedBlock(ChainSync.Point.Origin)
  )

  test("the decoder's defect reaches the node as a failure of the peer with its original cause") {
    Peer.networkFault(NetworkError.DecoderDefect(Protocols.ChainSync, cause)) match
      case Fault.Failed(PeerError.InputDefect(PeerError.InputSite.Wire(protocol), thrown)) =>
        assertEquals(protocol, Protocols.ChainSync)
        assert(thrown eq cause)
      case other => fail(s"unexpected $other")
  }

  test(
    "the bearer reader's defect reaches the node as a failure of the peer with its cause (issue 463)"
  ) {
    Peer.networkFault(NetworkError.BearerDefect(cause)) match
      case Fault.Failed(PeerError.InputDefect(PeerError.InputSite.Bearer, thrown)) =>
        assert(thrown eq cause)
      case other => fail(s"unexpected $other")
  }

  test("control: a defect of unknown origin is still the node's") {
    assertEquals(Peer.networkFault(NetworkError.Defect(cause)), Fault.Defect(cause))
  }

  test("an input defect closes the connection and backs off: transient, never forgotten") {
    sites.foreach { site =>
      val error = PeerError.InputDefect(site, cause)
      assertEquals(FailurePolicy.classify(error), FailureClass.Transient)
      assertEquals(FailurePolicy.disposition(SessionEnd.Failed(error)), Disposition.Backoff)
      assertEquals(SessionEnd.peerError(SessionEnd.Failed(error)), Some(error))
    }
  }

  test("control: an exception of the node, a storage failure and an internal error stop the node") {
    FailurePolicy.disposition(SessionEnd.Defect(cause)) match
      case Disposition.StopNode(Fatal.Defect(thrown)) => assert(thrown eq cause)
      case other                                      => fail(s"unexpected $other")
    val storage = StorageError.Io("volatile", new java.io.IOException("disk"))
    assertEquals(
      FailurePolicy.disposition(SessionEnd.Storage(storage)),
      Disposition.StopNode(Fatal.Storage(storage))
    )
    assertEquals(
      FailurePolicy.disposition(SessionEnd.Internal(ChainError.NoIntersection)),
      Disposition.StopNode(Fatal.Internal(ChainError.NoIntersection))
    )
  }

  test("a decoder that throws at a node decode site is an input defect with the site and cause") {
    def attempt(site: PeerError.InputSite): IO[Either[PeerError, Int]] =
      Handle
        .allow[PeerError](Peer.decodingInput[IO, Int](site)(throw cause).map(_.asRight[PeerError]))
        .rescue(e => IO.pure(e.asLeft[Int]))
    sites
      .traverse(site => attempt(site).map(site -> _))
      .map(_.foreach { (site, result) =>
        result match
          case Left(PeerError.InputDefect(seen, thrown)) =>
            assertEquals(seen, site)
            assert(thrown eq cause)
          case other => fail(s"unexpected $other")
      })
  }

  test("a decoder that answers is not touched") {
    Handle
      .allow[PeerError](
        Peer
          .decodingInput[IO, Either[Refused, Int]](PeerError.InputSite.ChainSyncHeader)(
            Left(Refused.Malformed)
          )
          .map(_.asRight[PeerError])
      )
      .rescue(e => IO.pure(e.asLeft))
      .map(result => assertEquals(result, Right(Left(Refused.Malformed))))
  }

  private def reported(failure: HandlerFailure[NetworkError]) =
    for
      events <- Ref.of[IO, Vector[NodeEvent]](Vector.empty)
      fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      _ <- ListenerFailures.report[IO](
        "listener-test",
        failure,
        event => events.update(_ :+ event),
        fatal => fatals.update(_ :+ fatal)
      )
      observed <- events.get
      stopped <- fatals.get
    yield (observed, stopped)

  test("a listener connection that ends with a decoder defect is reported without stopping") {
    val error = NetworkError.DecoderDefect(Protocols.LocalTxSubmission, cause)
    reported(HandlerFailure.Domain(error)).map { (events, fatals) =>
      assertEquals(fatals, Vector.empty[Fatal])
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Connection(ConnectionEnd.fromNetwork(error)))
          )
        )
      )
    }
  }

  /** What the connection's owner does with the end of a session, as the governor does: it closes
    * the connection with the peer's failure and reports it.
    */
  private def owner(
      mux: Mux[IO],
      result: Either[NetworkError, Either[Fault, Unit]]
  ): IO[Option[PeerError]] = result match
    case Right(Left(Fault.Failed(error))) =>
      mux
        .abort(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(error)))
        .as(Some(error))
    case Left(error) => IO.pure(faulted(Peer.networkFault(error)))
    case other       => IO.raiseError(new AssertionError(s"unexpected $other"))

  private def faulted(fault: Fault): Option[PeerError] = fault match
    case Fault.Failed(error) => Some(error)
    case Fault.Defect(_)     => None

  test("an outbound ChainSync decoder that throws ends the session with an input defect") {
    val handlers = ServerHandlers[IO, NtNHeader](
      NtNHeader.codec,
      Resource.pure[IO, ChainSyncServer.Source[IO, NtNHeader]](
        new ChainSyncServer.Source[IO, NtNHeader]:
          def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
          def poll = IO.pure(None)
          def await = IO.never
      ),
      (_, _) => IO.pure(None),
      new TxSubmissionServer.Sink[IO]:
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes])(using
            cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
        ) = IO.unit
    )
    val settings = Peer.Settings(
      SocketAddress(host"127.0.0.1", port"3001"),
      42L,
      byronEpochSlots = ForgedChain.geometry.byron.epochSlots
    )
    Peers
      .typed {
        Peers
          .linked(
            protocols = NodeToNodeServer.Protocol,
            client = Mux.Config(duplex = true),
            server = Mux.Config(role = Mux.Role.Responder, duplex = true)
          )
          .use { (client, server) =>
            NodeToNodeServer.start(server, handlers, initiators = true).use { _ =>
              val poisoned = PoisonedMux(client, Protocols.ChainSync, cause)
              Peers
                .typed {
                  NodeToNode
                    .clients[IO, NtNHeader](
                      poisoned,
                      Mux.Role.Initiator,
                      NtnVersion.LatestReleased.number,
                      NtNHeader.codec
                    )
                    .flatMap(
                      Peer.over(_, settings, _ => IO.unit, ChainSyncHeights.origin)
                    )
                    .use(peer => Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)).void))
                }
                .flatMap(result => owner(poisoned, result))
            }
          }
      }
      .map {
        case Right(Some(PeerError.InputDefect(site, thrown))) =>
          assertEquals(site, PeerError.InputSite.Wire(Protocols.ChainSync))
          assert(thrown eq cause)
        case other => fail(s"unexpected $other")
      }
      .timeout(20.seconds)
  }
