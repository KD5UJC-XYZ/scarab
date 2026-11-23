// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{
  ChainSync,
  EbHash,
  EbPoint,
  IncrementalDecoder,
  LeiosNotify,
  LeiosWireError,
  ProtocolError
}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}

import LeiosNotifyServer.{Replies, Reply, Requested}
import Peers.*

/** The pipelined LeiosNotify client against `leiosNotifyClientPeerPipelined`
  * (`LeiosDemoOnlyTestNotify.hs` 529-634 at ouroboros-consensus 2416493b): requests left
  * outstanding up to the depth, none before the gate holds, the replies handed on in order; told to
  * stop, it quits at once, collects what is outstanding without handing it on, and ends with
  * `MsgDone`. It runs against the node's own server ([[LeiosNotifyServer]]) and against a scripted
  * one.
  */
class LeiosNotifyPipelinedSuite extends FailsOnUncaught:
  private given LeiosNotify.AnnouncementReader = _ => Right(())

  private val point = EbPoint(Word64.fromUnsigned(40L), EbHash.fromBytes(Bytes(Vector.fill(32)(3))))

  /** Replies taken from `queued`, the requests counted into `counted` and held as credits up to
    * `depth`, a reply taking one, as `hLeiosNotifyServer` 's `incr` and pump keep them.
    */
  private def scripted(
      queued: Queue[IO, Reply],
      counted: SignallingRef[IO, Int],
      depth: Int
  ): IO[Replies[IO]] =
    Ref
      .of[IO, Int](0)
      .map(credits =>
        new Replies[IO]:
          def requested: IO[Requested] =
            credits.modify(n =>
              if n == depth then (n, Requested.Excessive(depth))
              else (n + 1, Requested.NotExcessive)
            ) <* counted.update(_ + 1)
          def next: IO[Reply] = queued.take <* credits.update(_ - 1)
          def poll: IO[Option[Reply]] =
            queued.tryTake.flatTap(_.traverse_(_ => credits.update(_ - 1)))
          def pump: IO[Unit] = IO.unit
      )

  /** The client over one end, the server over `replies` on the other; how each ended. */
  private def conversing(
      replies: Replies[IO],
      stop: Deferred[IO, Unit],
      gate: Signal[IO, Boolean],
      handle: LeiosNotification => Raise[IO, NetworkError] ?=> IO[Unit],
      depth: Int
  )(using Net): IO[(Either[Throwable, Unit], Either[NetworkError, Unit])] =
    linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
      for
        ours <- clientMux.channel(Protocols.LeiosNotify)
        theirs <- serverMux.channel(Protocols.LeiosNotify)
        server <- LeiosNotifyServer.run(theirs, replies).attempt.start
        client <- typed(
          LeiosNotifyPipelined.run[IO](
            ours,
            stop,
            gate,
            handle,
            LeiosNotifyPipelined.Settings(depth = depth)
          )
        )
        ended <- server.joinWithNever
      yield (ended, client)
    }

  test("requests are left outstanding to the depth, each reply taken lets another go") {
    typed(for
      queued <- Queue.unbounded[IO, Reply]
      counted <- SignallingRef.of[IO, Int](0)
      // the server refuses a request beyond the depth, so a fourth before a reply would end it
      replies <- scripted(queued, counted, depth = 3)
      handled <- Ref.of[IO, Vector[LeiosNotification]](Vector.empty)
      stop <- Deferred[IO, Unit]
      outcome <- (
        conversing(
          replies,
          stop,
          Signal.constant[IO, Boolean](true),
          n => handled.update(_ :+ n),
          depth = 3
        ),
        counted.waitUntil(_ == 3) *> queued.offer(Reply.BlockOffer(point, Word32.wrap(9))) *>
          counted.waitUntil(_ == 4) *> handled.get.flatTap(_ => stop.complete(()))
      ).parTupled
      ((server, client), whenFour) = outcome
      after <- handled.get
    yield (server, client, whenFour, after)).map(result =>
      assertEquals(
        result,
        Right(
          (
            Right(()),
            Right(()),
            Vector(LeiosNotification.BlockOffer(point, Word32.wrap(9))),
            // the three outstanding at the stop are cancelled, and not handed on
            Vector(LeiosNotification.BlockOffer(point, Word32.wrap(9)))
          )
        )
      )
    )
  }

  test("told to stop before the gate holds, the client quits without a request") {
    typed(
      for
        queued <- Queue.unbounded[IO, Reply]
        counted <- SignallingRef.of[IO, Int](0)
        replies <- scripted(queued, counted, depth = 3)
        stop <- Deferred[IO, Unit]
        _ <- stop.complete(())
        outcome <- conversing(
          replies,
          stop,
          Signal.constant[IO, Boolean](false),
          _ => IO.unit,
          depth = 3
        )
        requests <- counted.get
      yield (outcome, requests)
    ).map(result => assertEquals(result, Right(((Right(()), Right(())), 0))))
  }

  test("once the gate opens, the client requests to the depth") {
    typed(for
      queued <- Queue.unbounded[IO, Reply]
      counted <- SignallingRef.of[IO, Int](0)
      replies <- scripted(queued, counted, depth = 2)
      gate <- SignallingRef.of[IO, Boolean](false)
      stop <- Deferred[IO, Unit]
      handled <- Ref.of[IO, Vector[LeiosNotification]](Vector.empty)
      _ <- queued.offer(Reply.BlockTxsOffer(point))
      before <- counted.get
      outcome <- (
        conversing(replies, stop, gate, n => handled.update(_ :+ n), depth = 2),
        gate.set(true) *> counted.waitUntil(_ == 3) *> stop.complete(())
      ).parTupled
      after <- handled.get
    yield (before, outcome._1, after)).map(result =>
      assertEquals(
        result,
        Right((0, (Right(()), Right(())), Vector(LeiosNotification.BlockTxsOffer(point))))
      )
    )
  }

  // --- against a scripted server, for what the node's server never sends ---

  private def send(channel: MuxChannel[IO], message: LeiosNotify.Message)(using Net): IO[Unit] =
    LeiosNotify
      .encode(message)
      .fold(e => IO.raiseError(new AssertionError(e.toString)), channel.send)

  private def receiveIdle(channel: MuxChannel[IO])(using Net): IO[LeiosNotify.Message] =
    channel.recv(LeiosNotify.SmallByteLimit)(new IncrementalDecoder[LeiosNotify.Message]:
      type Cursor = Unit
      def initial: Cursor = ()
      def resume(cursor: Cursor, input: Bytes) =
        (
          (),
          LeiosNotify.decodePrefix(LeiosNotify.State.Idle, input) match
            case Right((m, n))                   => ChainSync.DecodeResult.Decoded(m, n)
            case Left(LeiosWireError.Incomplete) => ChainSync.DecodeResult.NeedMore
            case Left(e) => ChainSync.DecodeResult.Failed(ProtocolError.Leios(e))
        ))

  test("a cancellation before the quit is handed on; a reply after it is not") {
    typed(linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
      for
        ours <- clientMux.channel(Protocols.LeiosNotify)
        theirs <- serverMux.channel(Protocols.LeiosNotify)
        handled <- SignallingRef.of[IO, Vector[LeiosNotification]](Vector.empty)
        stop <- Deferred[IO, Unit]
        // the server: two requests, a cancellation, then the quit, a reply to each request left
        // outstanding, and `MsgDone`
        server = for
          first <- receiveIdle(theirs)
          second <- receiveIdle(theirs)
          _ <- send(theirs, LeiosNotify.Message.Canceled)
          _ <- handled.waitUntil(_.nonEmpty)
          _ <- stop.complete(())
          // the one left, and any the client sent after the cancellation freed a place
          more <- receiveIdle(theirs).flatMap(m =>
            (m :: Nil).iterateUntilM(ms => receiveIdle(theirs).map(_ :: ms))(
              _.headOption.contains(LeiosNotify.Message.Quit)
            )
          )
          _ <- send(theirs, LeiosNotify.Message.BlockTxsOffer(point)).replicateA_(more.size)
          _ <- send(theirs, LeiosNotify.Message.Done)
        yield (first, second, more.head)
        outcome <- (
          LeiosNotifyPipelined.run[IO](
            ours,
            stop,
            Signal.constant[IO, Boolean](true),
            n => handled.update(_ :+ n),
            LeiosNotifyPipelined.Settings(depth = 2)
          ),
          server
        ).parTupled
        after <- handled.get
      yield (outcome._2, after)
    }).map(result =>
      assertEquals(
        result,
        Right(
          (
            (
              LeiosNotify.Message.RequestNext,
              LeiosNotify.Message.RequestNext,
              LeiosNotify.Message.Quit
            ),
            Vector(LeiosNotification.Canceled)
          )
        )
      )
    )
  }

  test("a reply the handler refuses ends the client with the handler's error") {
    val refused = NetworkError.LeiosNotifyExcessiveRequests(1)
    typed(for
      queued <- Queue.unbounded[IO, Reply]
      counted <- SignallingRef.of[IO, Int](0)
      replies <- scripted(queued, counted, depth = 2)
      stop <- Deferred[IO, Unit]
      _ <- queued.offer(Reply.BlockTxsOffer(point))
      outcome <- linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
        for
          ours <- clientMux.channel(Protocols.LeiosNotify)
          theirs <- serverMux.channel(Protocols.LeiosNotify)
          _ <- LeiosNotifyServer.run(theirs, replies).attempt.start
          client <- typed(
            LeiosNotifyPipelined.run[IO](
              ours,
              stop,
              Signal.constant[IO, Boolean](true),
              _ => summon[Raise[IO, NetworkError]].raise(refused),
              LeiosNotifyPipelined.Settings(depth = 2)
            )
          )
        yield client
      }
    yield outcome).map(result => assertEquals(result, Right(Left(refused))))
  }

  test("a reply the Busy decoder does not admit ends the client with the decoder's refusal") {
    typed(linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
      for
        ours <- clientMux.channel(Protocols.LeiosNotify)
        theirs <- serverMux.channel(Protocols.LeiosNotify)
        stop <- Deferred[IO, Unit]
        // `MsgDone` answers a request: a message of Quit, not of Busy
        server = receiveIdle(theirs) *> send(theirs, LeiosNotify.Message.Done)
        outcome <- (
          typed(
            LeiosNotifyPipelined.run[IO](
              ours,
              stop,
              Signal.constant[IO, Boolean](true),
              _ => IO.unit,
              LeiosNotifyPipelined.Settings(depth = 1)
            )
          ),
          server
        ).parTupled
      yield outcome._1
    }).map(result =>
      assertEquals(
        result,
        Right(
          Left(
            NetworkError.Decode(
              Protocols.LeiosNotify,
              ProtocolError.Leios(LeiosWireError.NotAdmitted(0))
            )
          )
        )
      )
    )
  }

  test(
    "at the depth, the request a reply makes room for waits for the gate the room finds closed"
  ) {
    // `checkDone` and the reply's arrival are read in one transaction
    // (leiosNotifyClientPeerPipelined, LeiosDemoOnlyTestNotify.hs 549-582 at ouroboros-consensus
    // 2416493b): with the gate closed when the room comes, no request goes until it opens
    for
      gate <- SignallingRef.of[IO, Boolean](true)
      counted <- SignallingRef.of[IO, Int](0)
      // the gate as each request found it
      seen <- Ref.of[IO, Vector[Boolean]](Vector.empty)
      handled <- SignallingRef.of[IO, Vector[LeiosNotification]](Vector.empty)
      reopen <- Deferred[IO, Unit]
      program = typed(for
        queued <- Queue.unbounded[IO, Reply]
        credits <- scripted(queued, counted, depth = 1)
        replies = new Replies[IO]:
          def requested = gate.get.flatMap(g => seen.update(_ :+ g)) *> credits.requested
          def next = credits.next
          def poll = credits.poll
          def pump = credits.pump
        stop <- Deferred[IO, Unit]
        outcome <- (
          conversing(
            replies,
            stop,
            gate,
            n => handled.update(_ :+ n),
            depth = 1
          ),
          for
            _ <- counted.waitUntil(_ == 1)
            _ <- gate.set(false)
            _ <- queued.offer(Reply.BlockTxsOffer(point))
            _ <- handled.waitUntil(_.nonEmpty)
            _ <- reopen.get
            _ <- gate.set(true)
            _ <- counted.waitUntil(_ == 2)
            _ <- stop.complete(())
          yield ()
        ).parTupled
      yield outcome._1)
      control <- cats.effect.testkit.TestControl.execute(program)
      // every fiber runs to where it waits: the reply is handled with the gate closed
      _ <- control.tick
      whileClosed <- (counted.get, handled.get).tupled
      _ <- reopen.complete(())
      _ <- control.tick
      result <- control.results
      requests <- seen.get
    yield
      assertEquals(whileClosed, (1, Vector(LeiosNotification.BlockTxsOffer(point))))
      assertEquals(requests, Vector(true, true))
      result match
        case Some(cats.effect.Outcome.Succeeded(ended)) =>
          assertEquals(ended, Right((Right(()), Right(()))))
        case other => fail(s"the conversation did not end: $other")
  }
