// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Outcome, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

/** The inbound governor registers the peer of a duplex connection as fresh on its handshake, and
  * the outbound governor's inbound peers hold only mature ones (`freshDuplexPeers`,
  * `InboundGovernor.hs` 385-392; `mkPublicState`, `InboundGovernor/State.hs` 74-83;
  * ouroboros-network `c45735a5`), and tracks accepted connections of either data flow
  * (`unregisterConnection`, `InboundGovernor/State.hs` 208-222). Over in-memory bearers, on a
  * simulated clock.
  */
class InboundPeerMaturitySuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L
  private val remote: SocketAddress[IpAddress] = SocketAddress(ip"10.1.2.3", port"3001")

  private def handlers: ServerHandlers[IO, NtNHeader] =
    ServerHandlers[IO, NtNHeader](
      NtNHeader.codec,
      Resource.pure[IO, ChainSyncServer.Source[IO, NtNHeader]](
        new ChainSyncServer.Source[IO, NtNHeader]:
          def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
          def poll = IO.pure(None)
          def await = IO.never
      ),
      (_, _) => IO.pure(None),
      new TxSubmissionServer.Sink[IO]:
        def reader = RelayedTransactions
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[xyz.kd5ujc.scarab.protocol.TxSubmission2.EraTx])(using
            cats.mtl.Raise[IO, NetworkError]
        ) = IO.unit
    )

  test("a duplex peer that has only completed the handshake is registered, and not offered") {
    Settled { settle =>
      for
        manager <- ConnectionManager.create[IO](ConnectionManager.Limits(timeWait = Duration.Zero))
        sharing <- PeerSharingRuntime.create[IO](1L)
        governor <- InboundGovernor.create[IO](
          manager,
          InboundGovernor.Config(magic, NodeToNodeServer.Settings(offerPeerSharing = true)),
          handlers,
          sharing = Some(sharing)
        )
        start <- IO.monotonic
        pair <- Bearer.inMemory[IO](256)
        (theirs, ours) = pair
        // the inbound governor's own loop runs as it does beside the server
        seen <- governor.run.background.surround(
          for
            served <- governor.serve(ours, remote).start
            seen <- Handle
              .allow[NetworkError] {
                NodeToNode
                  .warmOver[IO, NtNHeader](
                    theirs,
                    magic,
                    NtNHeader.codec,
                    NodeToNode.Settings(offerPeerSharing = true),
                    Some(handlers)
                  )
                  // the registration's change, then the inbound peers the outbound governor
                  // reads once every fiber, the governor's steps among them, has done all it can
                  .use(_ => sharing.changed *> settle *> (sharing.inbound, IO.monotonic).tupled)
                  .guarantee(theirs.close)
              }
              .rescue(e => IO(fail(e.message)))
            _ <- served.joinWithNever
          yield seen
        )
      yield
        val (inbound, at) = seen
        assertEquals(
          at - start,
          Duration.Zero,
          "the clock has not moved: the connection is 0 s old"
        )
        assertEquals(inbound, Map.empty[PeerNetwork.Addr, Boolean], "not offered before 900 s")
    }
  }

  /** `bearer`, known as a connection to `remote` from local port `local`, so that two accepted
    * connections from one remote address are distinct to the manager.
    */
  private def identified(bearer: Bearer[IO], local: Int): Bearer[IO] =
    val id = ConnectionId.fromSocketAddresses(
      SocketAddress(ip"10.9.9.9", Port.fromInt(local).getOrElse(fail("port"))),
      remote
    )
    new Bearer[IO]:
      override def connectionId = Some(IO.pure(id))
      def reads = bearer.reads
      def write(bytes: fs2.Chunk[Byte]) = bearer.write(bytes)
      def close = bearer.close

  test(
    "the end of an accepted unidirectional connection unregisters its address, though a duplex one from it lives"
  ) {
    // the duplex connection is registered at 0 and offered from the governor's step at
    // 911.061883 s; a unidirectional connection from the same remote address, on another local
    // endpoint, then opens and ends, and its end deletes the address from both sets
    // (`unregisterConnection`, `InboundGovernor/State.hs` 208-222 at c45735a5)
    val due = 31415927.microseconds * 29
    val program = (atDue: Deferred[IO, Unit], afterEnd: Deferred[IO, Unit]) =>
      for
        manager <- ConnectionManager.create[IO](ConnectionManager.Limits(timeWait = Duration.Zero))
        sharing <- PeerSharingRuntime.create[IO](1L)
        governor <- InboundGovernor.create[IO](
          manager,
          InboundGovernor.Config(magic, NodeToNodeServer.Settings(offerPeerSharing = true)),
          handlers,
          sharing = Some(sharing)
        )
        duplexPair <- Bearer.inMemory[IO](256)
        unidirectionalPair <- Bearer.inMemory[IO](256)
        seen <- governor.run.background.surround(
          for
            servedDuplex <- governor.serve(identified(duplexPair._2, 3001), remote).start
            seen <- Handle
              .allow[NetworkError] {
                NodeToNode
                  .warmOver[IO, NtNHeader](
                    duplexPair._1,
                    magic,
                    NtNHeader.codec,
                    NodeToNode.Settings(offerPeerSharing = true),
                    Some(handlers)
                  )
                  .use { _ =>
                    for
                      offered <- atDue.get *> sharing.inbound
                      servedOther <- governor
                        .serve(identified(unidirectionalPair._2, 3002), remote)
                        .start
                      _ <- NodeToNode
                        .warmOver[IO, NtNHeader](
                          unidirectionalPair._1,
                          magic,
                          NtNHeader.codec,
                          NodeToNode.Settings(offerPeerSharing = true),
                          None
                        )
                        .use(_ => IO.unit)
                        .guarantee(unidirectionalPair._1.close)
                      _ <- servedOther.joinWithNever
                      gone <- afterEnd.get *> sharing.inbound
                    yield (offered, gone)
                  }
                  .guarantee(duplexPair._1.close)
              }
              .rescue(e => IO(fail(e.message)))
            _ <- servedDuplex.joinWithNever
          yield seen
        )
      yield seen
    for
      atDue <- Deferred[IO, Unit]
      afterEnd <- Deferred[IO, Unit]
      control <- TestControl.execute(program(atDue, afterEnd))
      _ <- control.tick
      _ <- control.tickFor(due) *> atDue.complete(()) *> control.tick
      // the unidirectional connection opens and ends within the next minute
      _ <- control.tickFor(60.seconds) *> afterEnd.complete(()) *> control.tick
      outcome <- control.results
    yield outcome match
      case Some(Outcome.Succeeded((offered, gone))) =>
        val peer = SocketAddress[Host](remote.host, remote.port)
        assertEquals(offered, Map(peer -> true), "the duplex connection's peer is offered")
        assertEquals(
          gone,
          Map.empty[PeerNetwork.Addr, Boolean],
          "the unidirectional end removes it"
        )
      case other => fail(s"the program did not end: $other")
  }
