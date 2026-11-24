// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import scala.concurrent.duration.*
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
          LeiosNotifyPipelined.run[IO, LeiosNotification](
            ours,
            stop,
            gate,
            LeiosNotifyPipelined.Handling.inOrder[IO](handle),
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

  test("told to stop while the gate is closed, the client quits without a request") {
    // `checkDone` retries until the stop or the forecast (NodeToNode.hs 645-659 at
    // ouroboros-consensus 2416493b): the stop ends the wait at a closed gate
    for
      gate <- SignallingRef.of[IO, Boolean](false)
      counted <- SignallingRef.of[IO, Int](0)
      stop <- Deferred[IO, Unit]
      program = typed(for
        queued <- Queue.unbounded[IO, Reply]
        replies <- scripted(queued, counted, depth = 2)
        outcome <- conversing(replies, stop, gate, _ => IO.unit, depth = 2)
      yield outcome)
      control <- cats.effect.testkit.TestControl.execute(program)
      // every fiber runs to where it waits: the sender at the closed gate
      _ <- control.tick
      _ <- stop.complete(())
      _ <- control.tick
      result <- control.results
      requests <- counted.get
    yield
      assertEquals(requests, 0)
      result match
        case Some(cats.effect.Outcome.Succeeded(ended)) =>
          assertEquals(ended, Right((Right(()), Right(()))))
        case other => fail(s"the conversation did not end: $other")
  }

  test("told to stop while it waits for room at the depth, the client quits") {
    // the depth's wait is read with the stop in one transaction (LeiosDemoOnlyTestNotify.hs 549-582
    // at ouroboros-consensus 2416493b): the stop wakes a sender with no room, and the reply still
    // outstanding is collected and not handed on
    for
      counted <- SignallingRef.of[IO, Int](0)
      handled <- Ref.of[IO, Vector[LeiosNotification]](Vector.empty)
      stop <- Deferred[IO, Unit]
      program = typed(for
        queued <- Queue.unbounded[IO, Reply]
        replies <- scripted(queued, counted, depth = 1)
        outcome <- conversing(
          replies,
          stop,
          Signal.constant[IO, Boolean](true),
          n => handled.update(_ :+ n),
          depth = 1
        )
      yield outcome)
      control <- cats.effect.testkit.TestControl.execute(program)
      // every fiber runs to where it waits: one request outstanding, the sender without room
      _ <- control.tick
      parked <- counted.get
      _ <- stop.complete(())
      _ <- control.tick
      result <- control.results
      after <- handled.get
    yield
      assertEquals(parked, 1)
      assertEquals(after, Vector.empty)
      result match
        case Some(cats.effect.Outcome.Succeeded(ended)) =>
          assertEquals(ended, Right((Right(()), Right(()))))
        case other => fail(s"the conversation did not end: $other")
  }

  test("a failure of the stop's wait ends the client with a defect") {
    // the wait for the stop is a body of the client's own, whose end is the client's: a failure
    // there ends the conversation, where a dropped one would leave the sender waiting at the depth
    val unreadable = new IllegalStateException("the stop cannot be read")
    typed(linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
      for
        ours <- clientMux.channel(Protocols.LeiosNotify)
        theirs <- serverMux.channel(Protocols.LeiosNotify)
        queued <- Queue.unbounded[IO, Reply]
        counted <- SignallingRef.of[IO, Int](0)
        replies <- scripted(queued, counted, depth = 1)
        recorded <- Deferred[IO, Unit]
        stop = new Deferred[IO, Unit]:
          def get: IO[Unit] = IO.raiseError(unreadable)
          def tryGet: IO[Option[Unit]] = recorded.tryGet
          def complete(a: Unit): IO[Boolean] = recorded.complete(a)
        server <- LeiosNotifyServer.run(theirs, replies).attempt.start
        client <- typed(
          LeiosNotifyPipelined.run[IO, LeiosNotification](
            ours,
            stop,
            Signal.constant[IO, Boolean](true),
            LeiosNotifyPipelined.Handling.inOrder[IO](_ => IO.unit),
            LeiosNotifyPipelined.Settings(depth = 1)
          )
        )
        _ <- server.cancel
      yield client
    }).map {
      case Right(Left(NetworkError.Defect(cause))) => assert(cause eq unreadable)
      case other => fail(s"the client did not end with the defect: $other")
    }
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
          LeiosNotifyPipelined.run[IO, LeiosNotification](
            ours,
            stop,
            Signal.constant[IO, Boolean](true),
            LeiosNotifyPipelined.Handling.inOrder[IO](n => handled.update(_ :+ n)),
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

  test(
    "replies are handed on in the order they arrived, each with its reading where it has ended"
  ) {
    // the pipelined driver hands the replies on in order (LeiosDemoOnlyTestNotify.hs 529-634 at
    // ouroboros-consensus 2416493b). The first reply's reading never ends, so its turn takes what was
    // given for that without waiting; while it is handed on, the third reply's reading ends, then the
    // second's, and their turns take them
    def offer(size: Long) = LeiosNotification.BlockOffer(point, Word32.wrap(size))
    for
      gates <- Vector(2L, 3L).traverse(size => Deferred[IO, Unit].map(size -> _)).map(_.toMap)
      held <- Deferred[IO, Unit]
      finished <- Ref.of[IO, Vector[Long]](Vector.empty)
      handled <- Ref.of[IO, Vector[LeiosNotification]](Vector.empty)
      stop <- Deferred[IO, Unit]
      counted <- SignallingRef.of[IO, Int](0)
      program = typed(for
        queued <- Queue.unbounded[IO, Reply]
        replies <- scripted(queued, counted, depth = 3)
        _ <- Vector(1L, 2L, 3L).traverse_(size =>
          queued.offer(Reply.BlockOffer(point, Word32.wrap(size)))
        )
        handling = LeiosNotifyPipelined.Handling[IO, LeiosNotification](
          {
            case LeiosNotification.BlockOffer(_, size) if size.toLong == 1L =>
              LeiosNotifyPipelined.Prepared.Ahead(IO.never, LeiosNotification.Canceled)
            case n @ LeiosNotification.BlockOffer(_, size) =>
              LeiosNotifyPipelined.Prepared.Ahead(
                gates(size.toLong).get *> finished.update(_ :+ size.toLong).as(n),
                LeiosNotification.Canceled
              )
            case n => LeiosNotifyPipelined.Prepared.Ready(n)
          },
          {
            case n @ LeiosNotification.Canceled => held.get *> handled.update(_ :+ n)
            case n                              => handled.update(_ :+ n)
          }
        )
        ended <- linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
          for
            ours <- clientMux.channel(Protocols.LeiosNotify)
            theirs <- serverMux.channel(Protocols.LeiosNotify)
            server <- LeiosNotifyServer.run(theirs, replies).attempt.start
            client <- typed(
              LeiosNotifyPipelined.run[IO, LeiosNotification](
                ours,
                stop,
                Signal.constant[IO, Boolean](true),
                handling,
                LeiosNotifyPipelined.Settings(depth = 3)
              )
            )
            served <- server.joinWithNever
          yield (served, client)
        }
      yield ended)
      control <- cats.effect.testkit.TestControl.execute(program)
      // the three replies received; the first's turn, its reading not ended, handed on and held
      _ <- control.tick
      // the third reply's reading ends, then the second's, each run to its end
      _ <- gates(3L).complete(())
      _ <- control.tick
      _ <- gates(2L).complete(())
      _ <- control.tick
      _ <- held.complete(())
      _ <- control.tick
      after <- handled.get
      order <- finished.get
      _ <- stop.complete(())
      _ <- control.tick
      result <- control.results
    yield
      assertEquals(after, Vector(LeiosNotification.Canceled, offer(2L), offer(3L)))
      assertEquals(order, Vector(3L, 2L))
      result match
        case Some(cats.effect.Outcome.Succeeded(ended)) =>
          assertEquals(ended, Right((Right(()), Right(()))))
        case other => fail(s"the conversation did not end: $other")
  }

  test("a failure to receive ends the client only once the replies before it are handed on") {
    // the reply before it is still being handed on when the failure is received
    for
      released <- Deferred[IO, Unit]
      handled <- Ref.of[IO, Vector[LeiosNotification]](Vector.empty)
      program = typed(linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
        for
          ours <- clientMux.channel(Protocols.LeiosNotify)
          theirs <- serverMux.channel(Protocols.LeiosNotify)
          stop <- Deferred[IO, Unit]
          // a reply, then `MsgDone`, a message of Quit, not of Busy
          server = receiveIdle(theirs) *> receiveIdle(theirs) *>
            send(theirs, LeiosNotify.Message.BlockTxsOffer(point)) *>
            send(theirs, LeiosNotify.Message.Done)
          outcome <- (
            typed(
              LeiosNotifyPipelined.run[IO, LeiosNotification](
                ours,
                stop,
                Signal.constant[IO, Boolean](true),
                LeiosNotifyPipelined.Handling.inOrder[IO](n =>
                  released.get *> handled.update(_ :+ n)
                ),
                LeiosNotifyPipelined.Settings(depth = 2)
              )
            ),
            server
          ).parTupled
        yield outcome._1
      })
      control <- cats.effect.testkit.TestControl.execute(program)
      // every fiber runs to where it waits: the reply and the failure received, the reply's
      // handing on held
      _ <- control.tick
      before <- handled.get
      _ <- released.complete(())
      _ <- control.tick
      result <- control.results
      after <- handled.get
    yield
      assertEquals(before, Vector.empty)
      assertEquals(after, Vector(LeiosNotification.BlockTxsOffer(point)))
      result match
        case Some(cats.effect.Outcome.Succeeded(ended)) =>
          assertEquals(
            ended,
            Right(
              Left(
                NetworkError.Decode(
                  Protocols.LeiosNotify,
                  ProtocolError.Leios(LeiosWireError.NotAdmitted(0))
                )
              )
            )
          )
        case other => fail(s"the client did not end: $other")
  }

  /** A reply read ahead by `reading` and handed on, or `otherwise`, which shows a reading lost. */
  private def readAhead(
      reading: LeiosNotification => IO[LeiosNotification],
      handled: Ref[IO, Vector[LeiosNotification]]
  ): LeiosNotifyPipelined.Handling[IO, LeiosNotification] =
    LeiosNotifyPipelined.Handling[IO, LeiosNotification](
      n => LeiosNotifyPipelined.Prepared.Ahead(reading(n), LeiosNotification.Canceled),
      n => handled.update(_ :+ n)
    )

  test("a reading ahead that fails is lost, and its turn takes what was given for that") {
    val failing = new IllegalStateException("the reading failed")
    typed(for
      queued <- Queue.unbounded[IO, Reply]
      counted <- SignallingRef.of[IO, Int](0)
      replies <- scripted(queued, counted, depth = 1)
      handled <- SignallingRef.of[IO, Vector[LeiosNotification]](Vector.empty)
      stop <- Deferred[IO, Unit]
      _ <- queued.offer(Reply.BlockTxsOffer(point))
      ended <- linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
        for
          ours <- clientMux.channel(Protocols.LeiosNotify)
          theirs <- serverMux.channel(Protocols.LeiosNotify)
          server <- LeiosNotifyServer.run(theirs, replies).attempt.start
          client <- (
            typed(
              LeiosNotifyPipelined.run[IO, LeiosNotification](
                ours,
                stop,
                Signal.constant[IO, Boolean](true),
                readAhead(_ => IO.raiseError(failing), handled),
                LeiosNotifyPipelined.Settings(depth = 1)
              )
            ),
            handled.waitUntil(_.nonEmpty) *> stop.complete(())
          ).parTupled.map(_._1)
          served <- server.joinWithNever
        yield (served, client)
      }
      after <- handled.get
    yield (ended, after)).map(result =>
      assertEquals(result, Right(((Right(()), Right(())), Vector(LeiosNotification.Canceled))))
    )
  }

  test("a reading not ended by its reply's turn is let go of, and the turn does not wait for it") {
    // the reply is handed on with what was given for a lost reading, and the conversation ends
    typed(for
      queued <- Queue.unbounded[IO, Reply]
      counted <- SignallingRef.of[IO, Int](0)
      replies <- scripted(queued, counted, depth = 1)
      handled <- SignallingRef.of[IO, Vector[LeiosNotification]](Vector.empty)
      reading <- Deferred[IO, Unit]
      stop <- Deferred[IO, Unit]
      _ <- queued.offer(Reply.BlockTxsOffer(point))
      ended <- linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
        for
          ours <- clientMux.channel(Protocols.LeiosNotify)
          theirs <- serverMux.channel(Protocols.LeiosNotify)
          server <- LeiosNotifyServer.run(theirs, replies).attempt.start
          client <- (
            typed(
              LeiosNotifyPipelined.run[IO, LeiosNotification](
                ours,
                stop,
                Signal.constant[IO, Boolean](true),
                readAhead(_ => reading.complete(()) *> IO.never, handled),
                LeiosNotifyPipelined.Settings(depth = 1)
              )
            ),
            handled.waitUntil(_.nonEmpty) *> stop.complete(())
          ).parTupled.map(_._1)
          served <- server.joinWithNever
        yield (served, client)
      }
      after <- handled.get
    yield (ended, after)).map(result =>
      assertEquals(result, Right(((Right(()), Right(())), Vector(LeiosNotification.Canceled))))
    )
  }

  test("no more replies are received ahead of their handing on than the lookahead") {
    // a reply is prepared as it is received, so the preparations counted are the replies received
    val received = new java.util.concurrent.atomic.AtomicInteger(0)
    for
      released <- Deferred[IO, Unit]
      handled <- Ref.of[IO, Vector[LeiosNotification]](Vector.empty)
      stop <- Deferred[IO, Unit]
      counted <- SignallingRef.of[IO, Int](0)
      program = typed(for
        queued <- Queue.unbounded[IO, Reply]
        replies <- scripted(queued, counted, depth = 3)
        _ <- Vector(1L, 2L, 3L).traverse_(size =>
          queued.offer(Reply.BlockOffer(point, Word32.wrap(size)))
        )
        ended <- linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
          for
            ours <- clientMux.channel(Protocols.LeiosNotify)
            theirs <- serverMux.channel(Protocols.LeiosNotify)
            server <- LeiosNotifyServer.run(theirs, replies).attempt.start
            client <- typed(
              LeiosNotifyPipelined.run[IO, LeiosNotification](
                ours,
                stop,
                Signal.constant[IO, Boolean](true),
                LeiosNotifyPipelined.Handling[IO, LeiosNotification](
                  n =>
                    received.incrementAndGet()
                    LeiosNotifyPipelined.Prepared.Ready(n)
                  ,
                  n => released.get *> handled.update(_ :+ n)
                ),
                LeiosNotifyPipelined.Settings(depth = 3, lookahead = 1)
              )
            )
            served <- server.joinWithNever
          yield (served, client)
        }
      yield ended)
      control <- cats.effect.testkit.TestControl.execute(program)
      // every fiber runs to where it waits: three replies sent, one received and being handed on,
      // none more received
      _ <- control.tick
      held <- (counted.get, IO(received.get)).tupled
      _ <- released.complete(())
      _ <- control.tick
      all <- handled.get
      _ <- stop.complete(())
      _ <- control.tick
      result <- control.results
    yield
      assertEquals(held, (3, 1))
      assertEquals(all.size, 3)
      result match
        case Some(cats.effect.Outcome.Succeeded(ended)) =>
          assertEquals(ended, Right((Right(()), Right(()))))
        case other => fail(s"the conversation did not end: $other")
  }

  /** A reply handed on for `handing`, during which the client is told to stop and quits, then
    * `MsgDone` sent `after` the reply is handed on: how the client ended.
    */
  private def quitting(handing: FiniteDuration, after: FiniteDuration): IO[Option[Any]] =
    for
      held <- Deferred[IO, Unit]
      done <- Deferred[IO, Unit]
      program = typed(linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
        for
          ours <- clientMux.channel(Protocols.LeiosNotify)
          theirs <- serverMux.channel(Protocols.LeiosNotify)
          stop <- Deferred[IO, Unit]
          received <- Deferred[IO, Unit]
          // one request answered; once the reply is being handed on, the client told to stop, its
          // quit read, and `MsgDone` sent when told
          server = receiveIdle(theirs) *> send(theirs, LeiosNotify.Message.BlockTxsOffer(point)) *>
            received.get *> stop.complete(()) *> receiveIdle(theirs) *> done.get *>
            send(theirs, LeiosNotify.Message.Done)
          outcome <- (
            typed(
              LeiosNotifyPipelined.run[IO, LeiosNotification](
                ours,
                stop,
                Signal.constant[IO, Boolean](true),
                LeiosNotifyPipelined.Handling.inOrder[IO](_ => received.complete(()) *> held.get),
                LeiosNotifyPipelined.Settings(depth = 1)
              )
            ),
            server
          ).parTupled
        yield outcome._1
      })
      control <- cats.effect.testkit.TestControl.execute(program)
      // the reply being handed on, the quit sent and read
      _ <- control.tick
      _ <- control.advanceAndTick(handing)
      _ <- held.complete(())
      _ <- control.tick
      _ <- control.advanceAndTick(after)
      _ <- done.complete(())
      _ <- control.tick
      result <- control.results
    yield result.collect { case cats.effect.Outcome.Succeeded(ended) => ended }

  test("MsgDone is waited for, under its limit, only once the replies before it are handed on") {
    // a client that handles each reply as it receives it waits for `MsgDone` only after it handled
    // the replies before it (LeiosDemoOnlyTestNotify.hs 619-634 at ouroboros-consensus 2416493b):
    // a reply handed on for 30 s puts the limit at 90 s, so `MsgDone` at 61 s is in time, and at 91 s
    // it is not
    (quitting(30.seconds, 31.seconds), quitting(30.seconds, 61.seconds)).tupled.map {
      (inTime, late) =>
        assertEquals(inTime, Some(Right(Right(()))))
        assertEquals(late, Some(Right(Left(NetworkError.Timeout(Protocols.LeiosNotify, "Quit")))))
    }
  }

  test(
    "a reply whose turn comes once the client has quit is not handed on, though received before"
  ) {
    // a client that handles each reply as it receives it receives the second only once it has handled
    // the first, and by then it has quit (`AlreadyDraining`, LeiosDemoOnlyTestNotify.hs 619-626 at
    // ouroboros-consensus 2416493b)
    for
      entered <- Deferred[IO, Unit]
      held <- Deferred[IO, Unit]
      readSecond <- Deferred[IO, Unit]
      stop <- Deferred[IO, Unit]
      handled <- Ref.of[IO, Vector[LeiosNotification]](Vector.empty)
      counted <- SignallingRef.of[IO, Int](0)
      first = LeiosNotification.BlockOffer(point, Word32.wrap(1))
      second = LeiosNotification.BlockOffer(point, Word32.wrap(2))
      program = typed(for
        queued <- Queue.unbounded[IO, Reply]
        replies <- scripted(queued, counted, depth = 2)
        _ <- queued.offer(Reply.BlockOffer(point, Word32.wrap(1))) *>
          queued.offer(Reply.BlockOffer(point, Word32.wrap(2)))
        ended <- linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
          for
            ours <- clientMux.channel(Protocols.LeiosNotify)
            theirs <- serverMux.channel(Protocols.LeiosNotify)
            server <- LeiosNotifyServer.run(theirs, replies).attempt.start
            client <- typed(
              LeiosNotifyPipelined.run[IO, LeiosNotification](
                ours,
                stop,
                Signal.constant[IO, Boolean](true),
                LeiosNotifyPipelined.Handling[IO, LeiosNotification](
                  {
                    case n @ LeiosNotification.BlockOffer(_, size) if size.toLong == 2L =>
                      LeiosNotifyPipelined.Prepared
                        .Ahead(readSecond.complete(()).as(n), LeiosNotification.Canceled)
                    case n => LeiosNotifyPipelined.Prepared.Ready(n)
                  },
                  {
                    case n @ LeiosNotification.BlockOffer(_, size) if size.toLong == 1L =>
                      entered.complete(()) *> held.get *> handled.update(_ :+ n)
                    case n => handled.update(_ :+ n)
                  }
                ),
                LeiosNotifyPipelined.Settings(depth = 2)
              )
            )
            served <- server.joinWithNever
          yield (served, client)
        }
      yield ended)
      control <- cats.effect.testkit.TestControl.execute(program)
      // the first reply being handed on, the second received and read ahead
      _ <- control.tick
      ahead <- (entered.tryGet, readSecond.tryGet).tupled
      // the client quits while the first is still being handed on
      _ <- stop.complete(())
      _ <- control.tick
      _ <- held.complete(())
      _ <- control.tick
      result <- control.results
      after <- handled.get
    yield
      assertEquals(ahead, (Some(()), Some(())))
      assertEquals(after, Vector(first))
      assert(!after.contains(second))
      result match
        case Some(cats.effect.Outcome.Succeeded(ended)) =>
          assertEquals(ended, Right((Right(()), Right(()))))
        case other => fail(s"the conversation did not end: $other")
  }

  test("a defect after a reply ends the client only once that reply is handed on") {
    // a defect, not a typed refusal, while the reply before it is still being handed on
    val broken = new IllegalStateException("the second reply could not be prepared")
    for
      released <- Deferred[IO, Unit]
      handled <- Ref.of[IO, Vector[LeiosNotification]](Vector.empty)
      program = typed(linked(protocols = Set(Protocols.LeiosNotify)).use { (clientMux, serverMux) =>
        for
          ours <- clientMux.channel(Protocols.LeiosNotify)
          theirs <- serverMux.channel(Protocols.LeiosNotify)
          stop <- Deferred[IO, Unit]
          server = receiveIdle(theirs) *> receiveIdle(theirs) *>
            send(theirs, LeiosNotify.Message.BlockTxsOffer(point)) *>
            send(theirs, LeiosNotify.Message.BlockOffer(point, Word32.wrap(2)))
          outcome <- (
            typed(
              LeiosNotifyPipelined.run[IO, LeiosNotification](
                ours,
                stop,
                Signal.constant[IO, Boolean](true),
                LeiosNotifyPipelined.Handling[IO, LeiosNotification](
                  {
                    case n @ LeiosNotification.BlockTxsOffer(_) =>
                      LeiosNotifyPipelined.Prepared.Ready(n)
                    case _ => throw broken
                  },
                  n => released.get *> handled.update(_ :+ n)
                ),
                LeiosNotifyPipelined.Settings(depth = 2)
              )
            ),
            server
          ).parTupled
        yield outcome._1
      })
      control <- cats.effect.testkit.TestControl.execute(program)
      // both replies received, the first being handed on and held, the second's preparation broken
      _ <- control.tick
      before <- handled.get
      _ <- released.complete(())
      _ <- control.tick
      result <- control.results
      after <- handled.get
    yield
      assertEquals(before, Vector.empty)
      assertEquals(after, Vector(LeiosNotification.BlockTxsOffer(point)))
      result match
        case Some(cats.effect.Outcome.Succeeded(Right(Left(NetworkError.Defect(cause))))) =>
          assert(cause eq broken)
        case other => fail(s"the client did not end with the defect: $other")
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
            LeiosNotifyPipelined.run[IO, LeiosNotification](
              ours,
              stop,
              Signal.constant[IO, Boolean](true),
              LeiosNotifyPipelined.Handling.inOrder[IO](_ =>
                summon[Raise[IO, NetworkError]].raise(refused)
              ),
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
            LeiosNotifyPipelined.run[IO, LeiosNotification](
              ours,
              stop,
              Signal.constant[IO, Boolean](true),
              LeiosNotifyPipelined.Handling.inOrder[IO](_ => IO.unit),
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
