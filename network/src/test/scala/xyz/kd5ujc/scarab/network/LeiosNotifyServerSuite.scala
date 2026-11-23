// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO}
import cats.effect.std.Queue
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.bls.{SeatId, Vote}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, rbHash, right}
import xyz.kd5ujc.scarab.protocol.{
  ChainSync,
  EbHash,
  EbPoint,
  HfcHeader,
  IncrementalDecoder,
  LeiosNotify,
  LeiosWireError,
  ProtocolError
}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}

import LeiosNotifyServer.{Replies, Reply, Requested}
import Peers.*

/** The LeiosNotify server's state machine against `leiosNotifyServerPeerLookahead`
  * (`LeiosDemoOnlyTestNotify.hs` 636-722 at ouroboros-consensus 2416493b) and the queue of
  * `hLeiosNotifyServer` (`NodeToNode.hs` 769-936): held requests answered in order with the queue's
  * replies, the quit making each one left a `MsgCanceled` before `MsgDone`, a request past the
  * credits refused, and the pump's failure ending the server.
  */
class LeiosNotifyServerSuite extends FailsOnUncaught:
  private given LeiosNotify.AnnouncementReader = _ => Right(())

  private val point = EbPoint(Word64.fromUnsigned(40L), EbHash.fromBytes(Bytes(Vector.fill(32)(3))))

  /** Replies taken from `queued`, counting requests up to `depth`, with `pump` alongside. */
  private def scripted(
      queued: Queue[IO, Reply],
      depth: Int = LeiosNotifyServer.PipelineDepth,
      pumping: IO[Unit] = IO.never
  ): IO[Replies[IO]] =
    LeiosNotifyServer
      .silent[IO](depth)
      .map(counted =>
        new Replies[IO]:
          def requested: IO[Requested] = counted.requested
          def next: IO[Reply] = queued.take
          def poll: IO[Option[Reply]] = queued.tryTake
          def pump: IO[Unit] = pumping
      )

  private def send(channel: MuxChannel[IO], message: LeiosNotify.Message)(using Net): IO[Unit] =
    LeiosNotify
      .encode(message)
      .fold(e => IO.raiseError(new AssertionError(e.toString)), channel.send)

  private def receive(channel: MuxChannel[IO], state: LeiosNotify.State)(using
      Net
  ): IO[LeiosNotify.Message] =
    channel.recv(LeiosNotify.SmallByteLimit)(new IncrementalDecoder[LeiosNotify.Message]:
      type Cursor = Unit
      def initial: Cursor = ()
      def resume(cursor: Cursor, input: Bytes) =
        (
          (),
          LeiosNotify.decodePrefix(state, input) match
            case Right((m, n))                   => ChainSync.DecodeResult.Decoded(m, n)
            case Left(LeiosWireError.Incomplete) => ChainSync.DecodeResult.NeedMore
            case Left(e) => ChainSync.DecodeResult.Failed(ProtocolError.Leios(e))
        ))

  /** The server over `replies` on one end, and `client` on the other, with how the server ended. */
  private def conversing[A](replies: Replies[IO])(
      client: MuxChannel[IO] => IO[A]
  )(using Net): IO[(Either[Throwable, Unit], A)] =
    linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
      for
        ours <- clientMux.channel(Protocols.LeiosNotify)
        theirs <- serverMux.channel(Protocols.LeiosNotify)
        server <- LeiosNotifyServer.run(theirs, replies).attempt.start
        result <- client(ours)
        ended <- server.joinWithNever
      yield (ended, result)
    }

  /** Replies whose `poll` and `next` are given, which count no request and pump nothing. */
  private def answering(polled: IO[Option[Reply]], nexted: IO[Reply]): Replies[IO] =
    new Replies[IO]:
      def requested: IO[Requested] = IO.pure(Requested.NotExcessive)
      def next: IO[Reply] = nexted
      def poll: IO[Option[Reply]] = polled
      def pump: IO[Unit] = IO.never

  private val untouched: IO[Nothing] = IO.raiseError(new AssertionError("the queue was read"))

  test("once the client quit, the responder answers MsgCanceled without reading the queue") {
    // `orElse` 's first branch (LeiosDemoOnlyTestNotify.hs 647-655 at ouroboros-consensus 2416493b)
    for
      quit <- Deferred[IO, Unit]
      _ <- quit.complete(())
      answered <- LeiosNotifyServer.answer(quit, answering(untouched, untouched))
    yield assertEquals(answered, LeiosNotifyServer.Answer.Canceled)
  }

  test("with no quit, a reply already queued is answered without waiting for the next") {
    for
      quit <- Deferred[IO, Unit]
      answered <- LeiosNotifyServer.answer(
        quit,
        answering(IO.pure(Some(Reply.BlockTxsOffer(point))), untouched)
      )
    yield assertEquals(answered, LeiosNotifyServer.Answer.Replied(Reply.BlockTxsOffer(point)))
  }

  test("a reply the next gives once the client has quit is answered with MsgCanceled") {
    // the quit checked again once `next` has given its reply: `orElse` decides at its commit, the
    // quit first, so a reply that comes with the quit is no answer
    for
      quit <- Deferred[IO, Unit]
      answered <- LeiosNotifyServer.answer(
        quit,
        answering(IO.pure(None), quit.complete(()).as(Reply.BlockTxsOffer(point)))
      )
    yield assertEquals(answered, LeiosNotifyServer.Answer.Canceled)
  }

  test("a reply taken after the client quit is answered with MsgCanceled") {
    // `responder`'s `orElse` tries the quit before `next` in the one transaction that commits the
    // answer (LeiosDemoOnlyTestNotify.hs 647-655 at ouroboros-consensus 2416493b): the sender finds
    // no quit, the quit comes, and only then is a reply there to take
    for
      quitting <- Deferred[IO, Unit]
      ready <- Deferred[IO, Unit]
      program = typed(for
        counted <- LeiosNotifyServer.silent[IO]()
        replies = new Replies[IO]:
          def requested: IO[Requested] = counted.requested
          def next: IO[Reply] = IO.never
          def poll: IO[Option[Reply]] = ready.get.as(Some(Reply.BlockTxsOffer(point)))
          def pump: IO[Unit] = IO.never
        outcome <- conversing(replies)(c =>
          send(c, LeiosNotify.Message.RequestNext) *> quitting.get *>
            send(c, LeiosNotify.Message.Quit) *>
            (receive(c, LeiosNotify.State.Busy), receive(c, LeiosNotify.State.Quit)).tupled
        )
      yield outcome)
      control <- cats.effect.testkit.TestControl.execute(program)
      // the request held and its answer begun, the sender waiting for a reply to take
      _ <- control.tick
      _ <- quitting.complete(())
      // the quit read and recorded
      _ <- control.tick
      _ <- ready.complete(())
      _ <- control.tick
      result <- control.results
    yield result match
      case Some(cats.effect.Outcome.Succeeded(ended)) =>
        assertEquals(
          ended,
          Right((Right(()), (LeiosNotify.Message.Canceled, LeiosNotify.Message.Done)))
        )
      case other => fail(s"the conversation did not end: $other")
  }

  test("held requests are answered in order with the queue's replies; the quit cancels the rest") {
    typed(for
      queued <- Queue.unbounded[IO, Reply]
      replies <- scripted(queued)
      _ <- queued.offer(Reply.BlockOffer(point, Word32.wrap(11))) *>
        queued.offer(Reply.BlockTxsOffer(point))
      outcome <- conversing(replies)(c =>
        send(c, LeiosNotify.Message.RequestNext).replicateA_(3) *>
          receive(c, LeiosNotify.State.Busy)
            .replicateA(2)
            .flatMap(answered =>
              send(c, LeiosNotify.Message.Quit) *>
                (receive(c, LeiosNotify.State.Busy), receive(c, LeiosNotify.State.Quit)).tupled
                  .map((canceled, done) => (answered, canceled, done))
            )
      )
    yield outcome).map(result =>
      assertEquals(
        result,
        Right(
          (
            Right(()),
            (
              List(
                LeiosNotify.Message.BlockOffer(point, Word32.wrap(11)),
                LeiosNotify.Message.BlockTxsOffer(point)
              ),
              LeiosNotify.Message.Canceled,
              LeiosNotify.Message.Done
            )
          )
        )
      )
    )
  }

  test("an announcement goes out as MsgLeiosBlockAnnouncement with its header's envelope") {
    val header = HfcHeader.Dijkstra(Bytes(Vector(0x80.toByte)))
    val envelope = HfcHeader.encode(header).fold(e => fail(e.toString), identity)
    typed(for
      queued <- Queue.unbounded[IO, Reply]
      replies <- scripted(queued)
      _ <- queued.offer(Reply.Announcement(header))
      outcome <- conversing(replies)(c =>
        send(c, LeiosNotify.Message.RequestNext) *> receive(c, LeiosNotify.State.Busy) <*
          send(c, LeiosNotify.Message.Quit) <* receive(c, LeiosNotify.State.Quit)
      )
    yield outcome).map(result =>
      assertEquals(result, Right((Right(()), LeiosNotify.Message.BlockAnnouncement(envelope))))
    )
  }

  test("a vote goes out alone in its MsgLeiosVotes") {
    // a throwaway key made here
    val vote = right(Vote.sign(key(77)._1, right(SeatId.fromInt(3)), rbHash(9)))
    typed(for
      queued <- Queue.unbounded[IO, Reply]
      replies <- scripted(queued)
      _ <- queued.offer(Reply.Votes(Vector(vote)))
      outcome <- conversing(replies)(c =>
        send(c, LeiosNotify.Message.RequestNext) *> receive(c, LeiosNotify.State.Busy) <*
          send(c, LeiosNotify.Message.Quit) <* receive(c, LeiosNotify.State.Quit)
      )
    yield outcome).map {
      case Right((Right(()), LeiosNotify.Message.Votes(votes, raw))) =>
        assertEquals((votes, raw), (Vector(vote), Vector(vote.encode)))
      case other => fail(s"not one vote: $other")
    }
  }

  test("a quit with nothing held is answered with MsgDone at once") {
    typed(for
      queued <- Queue.unbounded[IO, Reply]
      replies <- scripted(queued)
      outcome <- conversing(replies)(c =>
        send(c, LeiosNotify.Message.Quit) *> receive(c, LeiosNotify.State.Quit)
      )
    yield outcome).map(result => assertEquals(result, Right((Right(()), LeiosNotify.Message.Done))))
  }

  test("a request that finds the connection at its credits ends it") {
    typed(
      for
        queued <- Queue.unbounded[IO, Reply]
        replies <- scripted(queued, depth = 2)
        ended <- linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
          for
            ours <- clientMux.channel(Protocols.LeiosNotify)
            theirs <- serverMux.channel(Protocols.LeiosNotify)
            server <- typed(LeiosNotifyServer.run(theirs, replies)).start
            _ <- send(ours, LeiosNotify.Message.RequestNext).replicateA_(3)
            ended <- server.joinWithNever
          yield ended
        }
      yield ended
    ).map(result => assertEquals(result, Right(Left(NetworkError.LeiosNotifyExcessiveRequests(2)))))
  }

  test("the pump's failure ends the server") {
    val boom = new IllegalStateException("pump")
    typed(for
      queued <- Queue.unbounded[IO, Reply]
      replies <- scripted(queued, pumping = IO.raiseError(boom))
      outcome <- conversing(replies)(_ => IO.unit)
    yield outcome._1).map(result => assertEquals(result, Right(Left(boom))))
  }
