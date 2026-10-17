// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, FollowerEvent, FollowerEvents}
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{Block, ChainError, NodeError, PeerError}
import cats.effect.{IO, Ref, Resource}
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.NtNHeader
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.relay.ChainServer
import scala.concurrent.duration.*

/** Two upstream peers on in-memory connections with the production wiring (one `Handle` scope for
  * the peer failures of all sessions, one shared [[BlockPool]], `Follower.supervise` per peer): one
  * peer stalls (its ChainSync reply never comes, its BlockFetch hangs), the other keeps serving.
  * The follower must reach the tip, and no fiber may end with an error nobody handles (issue 130: a
  * `Handle$Submarine` that escaped, a claim that stayed open and blocked the other session).
  */
class FollowerStallSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val magic = 42L

  private lazy val forged: ForgedChain =
    ForgedChain.forge(5000 until 5060).unsafeRunSync()(using munitIORuntime)

  private def serving(raws: Vector[Bytes]): IO[ServerHandlers[IO, NtNHeader]] =
    for
      server <- ChainServer.open[IO](
        None,
        None,
        ChainServer.headerOfBlock(ForgedChain.geometry.byron.epochSlots)
      )
      _ <- Handle
        .allow[ChainError] {
          raws.traverse_ { raw =>
            val header = Block.decodeShelley(raw).toOption.get.asInstanceOf[Block.Shelley].header
            server
              .persistence[MirrorState]
              .extended(
                BlockRef(header.slot, header.hash, header.blockNo),
                raw,
                Vector.empty,
                Anchor(None, forged.genesis)
              )
          }
        }
        .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      sink = new TxSubmissionServer.Sink[IO]:
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes]) = IO.unit
    yield ServerHandlers[IO, NtNHeader](NtNHeader.codec, server.source, server.blocks, sink)

  /** The same peer, stalling on every connection: it announces `headers` headers and then does not
    * answer a request for the next one (neither an instruction nor AwaitReply, so the client's wait
    * in NextCanAwait runs out), and it answers the first block range and none after it.
    */
  private def stalling(
      honest: ServerHandlers[IO, NtNHeader],
      headers: Int
  ): (ServerHandlers[IO, NtNHeader], Ref[IO, Int]) =
    val answered = Ref.unsafe[IO, Int](0)
    val handlers = new ServerHandlers[IO, NtNHeader](
      honest.codec,
      honest.chainSync.evalMap { inner =>
        Ref.of[IO, Int](0).map { served =>
          new ChainSyncServer.Source[IO, NtNHeader]:
            def intersect(points: Vector[Point]) = inner.intersect(points)
            def poll = served.getAndUpdate(_ + 1).flatMap { n =>
              if n < headers then inner.poll else IO.never
            }
            def await = inner.await
        }
      },
      new BlockFetchServer.Blocks[IO]:
        // counted across connections: only the very first range is answered
        def range(from: Point, to: Point, max: Int) =
          answered.getAndUpdate(_ + 1).flatMap { n =>
            if n == 0 then honest.blocks.range(from, to, max) else IO.never
          }
      ,
      honest.transactions
    )
    (handlers, answered)

  private val tcp: SocketAddress[Host] = SocketAddress(host"127.0.0.1", port"3001")

  /** A connection to `remote`, opened the way `Peer.nodeToNode` opens one over TCP. */
  private def dial(
      remote: ServerHandlers[IO, NtNHeader],
      pool: BlockPool[IO]
  )(using peerRaise: Raise[IO, PeerError]): Resource[IO, Peer[IO]] =
    given Raise[IO, NetworkError] = new Raise[IO, NetworkError]:
      def functor = cats.Functor[IO]
      def raise[E2 <: NetworkError, A](e: E2): IO[A] = peerRaise.raise(Peer.peerError(e))
    val network = NodeToNode.Settings(
      chainSync = ChainSyncClient.Settings(canAwaitTimeout = 1.second)
    )
    for
      ends <- Resource.eval(Bearer.inMemory[IO](256))
      _ <- Handle
        .allow[NetworkError](NodeToNodeServer.accept(ends._1, magic, remote).void)
        .rescue(_ => IO.unit)
        .background
      connection <- NodeToNode.over[IO, NtNHeader](ends._2, magic, NtNHeader.codec, network)
      peer <- Peer.over[IO](
        connection,
        Peer.Settings(
          tcp,
          magic,
          readAhead = 8,
          byronEpochSlots = ForgedChain.geometry.byron.epochSlots
        ),
        _ => IO.unit,
        None,
        None,
        Some(pool)
      )
    yield peer

  private def eventually[A](what: String)(read: IO[A])(ok: A => Boolean): IO[A] =
    def loop(left: Int): IO[A] =
      read.flatMap { a =>
        if ok(a) then IO.pure(a)
        else if left == 0 then IO.raiseError(new RuntimeException(s"timed out: $what ($a)"))
        else IO.sleep(100.millis) *> loop(left - 1)
      }
    loop(400)

  test("one of two peers stalls in NextCanAwait mid-fetch: the other keeps the follower going") {
    for
      chainRef <- Ref.of[IO, VolatileChain[MirrorState]](
        VolatileChain.genesis(ForgedChain.geometry.securityParam, forged.genesis)
      )
      given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chainRef)
      seen <- Ref.of[IO, Vector[FollowerEvent]](Vector.empty)
      events = new FollowerEvents[IO]:
        def emit(event: FollowerEvent) = seen.update(_ :+ event)
      follower <- Handle
        .allow[NodeError] {
          Follower.create[IO, MirrorState](
            ForgedChain.rules,
            events,
            ForgedChain.geometry
          )
        }
        .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      pool <- BlockPool.create[IO]
      stall <- serving(forged.raws).map(stalling(_, headers = 16))
      (stalled, ranges) = stall
      healthy <- serving(forged.raws)
      retry = Follower.Retry(maxAttempts = 1000, delay = 200.millis)
      last = forged.headers.last
      reconnects <- Handle
        .allow[PeerError] {
          // as in Main: one scope for the failures of every peer's session
          val a = Follower.supervise(follower, dial(stalled, pool), events, retry)
          val b =
            IO.sleep(300.millis) *> Follower.supervise(follower, dial(healthy, pool), events, retry)
          (a, b).parTupled.void
        }
        .rescue(e => IO.raiseError(new RuntimeException(e.message)))
        .background
        .use { _ =>
          eventually("our chain reaches the tip")(chainRef.get.map(_.tip))(_ == last.point) *>
            // the stalling peer failed and came back: a typed, logged reconnect
            eventually("the stalling peer reconnects")(seen.get)(_.exists {
              case FollowerEvent.Reconnecting(_: PeerError.Unavailable, _) => true
              case _                                                       => false
            })
        }
      asked <- ranges.get
    yield
      assert(reconnects.nonEmpty)
      // the stalling peer was asked for a block range it never answered: its ChainSync stall came
      // while blocks of the batch were being fetched, not before any fetch
      assert(asked >= 2, s"block ranges asked of the stalling peer: $asked")
  }

  test("a peer error raised in a fiber of the connection is raised again in the session's scope") {
    Handle
      .allow[PeerError] {
        for
          // how a failed fiber of the connection reaches the session: as a value, not a Submarine
          failed <- Peer.carrying[IO].raise[PeerError, Unit](PeerError.Unavailable("lost")).attempt
          _ <- Peer.landed(IO.fromEither(failed))
        yield Option.empty[PeerError]
      }
      .rescue(error => IO.pure(Some(error)))
      .map(raised => assertEquals(raised, Some(PeerError.Unavailable("lost"))))
  }
