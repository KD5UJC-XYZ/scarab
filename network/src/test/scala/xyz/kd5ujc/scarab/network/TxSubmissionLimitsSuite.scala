// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{RelayOffer, TxSubmission2}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import xyz.kd5ujc.scarab.protocol.TxSubmission2.{Message, State}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*

import Peers.*

class TxSubmissionLimitsSuite extends FailsOnUncaught with ScalaCheckSuite:
  private def id(n: Int): Bytes = Bytes(Vector.fill(31)(0.toByte) :+ n.toByte)
  private val all = Vector.tabulate(64)(n => TxSubmissionClient.Held(id(n), 5, n + 1L))
  private val source: TxSubmissionClient.Source[IO] = new TxSubmissionClient.Source[IO]:
    def after(ticket: Long, max: Int) = IO.pure(all.filter(_.ticket > ticket).take(max))
    def awaitAfter(ticket: Long) = IO.unit
    def body(txId: Bytes) = IO.pure(None)

  private def send(channel: MuxChannel[IO], state: State, role: Role, message: Message)(using
      Net
  ): IO[Unit] =
    TxSubmission2.encode(state, role, message).fold(e => fail(e.toString), channel.send)

  private def receive(channel: MuxChannel[IO], state: State, role: Role)(using Net): IO[Message] =
    channel.recv(TxSubmission2.maxMessageBytes(state))(TxSubmission2.decodePrefix(state, role, _))

  test("reference limits cover every active state; the local profile remains stricter"):
    assertEquals(TxSubmission2.maxMessageBytes(State.Init), 65535)
    assertEquals(TxSubmission2.maxMessageBytes(State.Idle), 65535)
    List(State.Txs, State.Ids(true), State.Ids(false)).foreach { state =>
      assertEquals(TxSubmission2.maxMessageBytes(state), 524400)
    }
    assertEquals(TxSubmission2.maxMessageBytes(State.Done), 0)
    assertEquals(TxSubmissionPolicy.timeLimit(State.Txs), Some(10.seconds))
    assertEquals(TxSubmissionPolicy.timeLimit(State.Ids(false)), Some(10.seconds))
    List(State.Init, State.Idle, State.Ids(true), State.Done).foreach { state =>
      assertEquals(TxSubmissionPolicy.timeLimit(state), None)
    }

  property("hostile request sequences preserve finite credit and FIFO acknowledgement"):
    val request = for
      blocking <- Gen.oneOf(true, false)
      ack <- Gen.choose(0, 12)
      count <- Gen.choose(0, 12)
    yield (blocking, ack, count)
    forAll(Gen.listOfN(300, request)) { requests =>
      val (_, valid) = requests.foldLeft((Vector.empty[Bytes], true)) {
        case ((held, valid), (blocking, ack, count)) =>
          val retained = held.size - ack
          val allowed = ack <= held.size && retained + count <= 10 &&
            (if blocking then retained == 0 && count > 0
             else retained > 0 && (ack > 0 || count > 0))
          val checked = TxSubmissionPolicy.acknowledge(held, blocking, ack, count)
          checked match
            case Left(_) => (held, valid && !allowed)
            case Right(rest) =>
              val next = rest ++ Vector.fill(count.min(8))(id(1))
              (next, valid && allowed && rest == held.drop(ack) && next.size <= 10)
      }
      valid
    }

  test("the runtime rejects credit above10, even though each reply is limited to8"):
    TestControl
      .executeEmbed(typed {
        linked(protocols = Set(4)).use { (client, server) =>
          for
            c <- client.channel(4)
            s <- server.channel(4)
            running <- typed(TxSubmissionClient.run(c, source)).start
            _ <- receive(s, State.Init, Role.Client)
            _ <- send(s, State.Idle, Role.Server, Message.RequestIds(true, 0, 8))
            first <- receive(s, State.Ids(true), Role.Client)
            _ <- send(s, State.Idle, Role.Server, Message.RequestIds(false, 0, 2))
            second <- receive(s, State.Ids(false), Role.Client)
            _ <- send(s, State.Idle, Role.Server, Message.RequestIds(false, 0, 1))
            ended <- running.joinWithNever
          yield (first, second, ended)
        }
      })
      .map {
        case Right((Message.ReplyIds(first), Message.ReplyIds(second), ended)) =>
          assertEquals(first.size, 8)
          assertEquals(second.size, 2)
          assertEquals(
            ended,
            Left(NetworkError.TxSubmission(TxSubmissionPolicy.Violation.Outstanding(10, 1)))
          )
        case other => fail(s"unexpected $other")
      }

  for (request, expected) <- List(
      Message.RequestIds(false, 0, 0) -> TxSubmissionPolicy.Violation.EmptyRequest,
      Message.RequestTxs(Vector(id(0), id(0))) -> TxSubmissionPolicy.Violation.DuplicateBody(id(0)),
      Message.RequestTxs(Vector(id(63))) -> TxSubmissionPolicy.Violation.UnadvertisedBody(id(63))
    )
  do
    test(s"runtime refuses ${expected.productPrefix} before looking up a body"):
      TestControl
        .executeEmbed(typed {
          linked(protocols = Set(4)).use { (client, server) =>
            for
              c <- client.channel(4)
              s <- server.channel(4)
              running <- typed(TxSubmissionClient.run(c, source)).start
              _ <- receive(s, State.Init, Role.Client)
              _ <- send(s, State.Idle, Role.Server, Message.RequestIds(true, 0, 1))
              _ <- receive(s, State.Ids(true), Role.Client)
              _ <- send(s, State.Idle, Role.Server, request)
              ended <- running.joinWithNever
            yield ended
          }
        })
        .map(result => assertEquals(result, Right(Left(NetworkError.TxSubmission(expected)))))

  test("credit is checked before the reply batch cap; invalid numeric callers stay typed"):
    assertEquals(
      TxSubmissionPolicy.acknowledge(Vector.empty, true, 0, 65535),
      Left(TxSubmissionPolicy.Violation.Outstanding(0, 65535))
    )
    List(-1, 65536, Int.MaxValue).foreach { count =>
      assertEquals(
        TxSubmissionPolicy.acknowledge(Vector.empty, true, 0, count),
        Left(TxSubmissionPolicy.Violation.Counts(0, count))
      )
    }

  test("the same advertised body may be requested in later messages before acknowledgement"):
    TestControl
      .executeEmbed(typed {
        linked(protocols = Set(4)).use { (client, server) =>
          for
            c <- client.channel(4)
            s <- server.channel(4)
            running <- typed(TxSubmissionClient.run(c, source)).start
            _ <- receive(s, State.Init, Role.Client)
            _ <- send(s, State.Idle, Role.Server, Message.RequestIds(true, 0, 1))
            _ <- receive(s, State.Ids(true), Role.Client)
            _ <- send(s, State.Idle, Role.Server, Message.RequestTxs(Vector(id(0))))
            first <- receive(s, State.Txs, Role.Client)
            _ <- send(s, State.Idle, Role.Server, Message.RequestTxs(Vector(id(0))))
            second <- receive(s, State.Txs, Role.Client)
            _ <- send(s, State.Idle, Role.Server, Message.RequestIds(false, 0, 0))
            _ <- running.joinWithNever
          yield (first, second)
        }
      })
      .map(result =>
        assertEquals(
          result,
          Right((Message.ReplyTxs(Vector.empty), Message.ReplyTxs(Vector.empty)))
        )
      )

  for unexpected <- List(false, true) do
    test(s"uncaught local source ${if unexpected then "bug" else "IOException"} remains a defect"):
      val cause =
        if unexpected then new IllegalStateException("injected backend defect")
        else new java.io.IOException("injected closure")
      val broken = new TxSubmissionClient.Source[IO]:
        def after(ticket: Long, max: Int) = IO.pure(Vector.empty)
        def awaitAfter(ticket: Long) = IO.raiseError[Unit](cause)
        def body(txId: Bytes) = IO.pure(None)
      TestControl
        .executeEmbed(typed {
          linked(protocols = Set(4)).use { (client, server) =>
            TxSubmissionClient.background(client, broken).use { _ =>
              for
                s <- server.channel(4)
                _ <- receive(s, State.Init, Role.Client)
                _ <- send(s, State.Idle, Role.Server, Message.RequestIds(true, 0, 1))
                closed <- client.closed
              yield closed
            }
          }
        })
        .map(result => assertEquals(result, Right(NetworkError.Defect(cause))))

  test("actual bearer IOException remains a typed transport closure through the transaction fiber"):
    val cause = new java.io.IOException("injected transport closure")
    val transport = new Bearer[IO]:
      def reads = fs2.Stream.never[IO]
      def write(value: fs2.Chunk[Byte]) = IO.raiseError[Unit](cause)
      def close = IO.unit
    TestControl
      .executeEmbed(typed {
        Mux.resource(transport, Set(4)).use { mux =>
          TxSubmissionClient.backgroundFinishing(mux, source).use { client =>
            for
              ended <- mux.closed
              finished <- client.finish(1.second)
            yield (ended, finished)
          }
        }
      })
      .map(result =>
        assertEquals(
          result,
          Right(
            (
              NetworkError.BearerClosed(NetworkError.Closure.Transport("bearer failed", cause)),
              false
            )
          )
        )
      )

  for duplicate <- List(false, true) do
    test(
      s"server rejects ${if duplicate then "duplicate" else "excess"} offers before calling sink"
    ):
      TestControl
        .executeEmbed(for
          called <- Ref.of[IO, Boolean](false)
          sink = new TxSubmissionServer.Sink[IO]:
            def known(txId: Bytes) = called.set(true).as(false)
            def submit(originals: Vector[Bytes]) = called.set(true)
          result <- typed {
            linked(protocols = Set(4)).use { (client, server) =>
              for
                c <- client.channel(4)
                s <- server.channel(4)
                running <- typed(TxSubmissionServer.run(s, sink)).start
                _ <- send(c, State.Init, Role.Client, Message.Init)
                _ <- receive(c, State.Idle, Role.Server)
                offers = Vector.tabulate(if duplicate then 2 else 4)(n =>
                  RelayOffer(id(if duplicate then 0 else n), 10)
                )
                _ <- send(c, State.Ids(true), Role.Client, Message.ReplyIds(offers))
                ended <- running.joinWithNever
              yield ended
            }
          }
          touched <- called.get
        yield (result, touched))
        .map { (result, touched) =>
          val failure =
            if duplicate then TxSubmissionPolicy.Violation.DuplicateOffer
            else TxSubmissionPolicy.Violation.ExcessOffers(4, 3)
          assertEquals(result, Right(Left(NetworkError.TxSubmission(failure))))
          assert(!touched)
        }

  private val sink: TxSubmissionServer.Sink[IO] = new TxSubmissionServer.Sink[IO]:
    def known(txId: Bytes) = IO.pure(false)
    def submit(originals: Vector[Bytes]) = IO.unit

  test("requested bodies use the reference shortWait10s instead of the old60s"):
    TestControl
      .executeEmbed(typed {
        linked(protocols = Set(4)).use { (client, server) =>
          for
            c <- client.channel(4)
            s <- server.channel(4)
            running <- typed(TxSubmissionServer.run(s, sink)).start
            _ <- send(c, State.Init, Role.Client, Message.Init)
            _ <- receive(c, State.Idle, Role.Server)
            _ <- send(
              c,
              State.Ids(true),
              Role.Client,
              Message.ReplyIds(Vector(RelayOffer(id(1), 10)))
            )
            _ <- receive(c, State.Idle, Role.Server)
            ended <- running.joinWithNever
            elapsed <- IO.monotonic
          yield (ended, elapsed)
        }
      })
      .map(result =>
        assertEquals(result, Right((Left(NetworkError.Timeout(4, "Txs")), 10.seconds)))
      )

  test("blocking ID wait remains indefinite and resource cancellation owns the server fiber"):
    TestControl
      .executeEmbed(typed {
        linked(protocols = Set(4)).use { (client, server) =>
          for
            c <- client.channel(4)
            s <- server.channel(4)
            ended <- Deferred[IO, Either[NetworkError, Unit]]
            result <- typed(TxSubmissionServer.run(s, sink))
              .flatMap(ended.complete)
              .background
              .use { _ =>
                send(c, State.Init, Role.Client, Message.Init) *>
                  receive(c, State.Idle, Role.Server) *> IO.sleep(120.seconds) *> ended.tryGet
              }
          yield result
        }
      })
      .map(result => assertEquals(result, Right(None)))

  test("the Cardano server retains configured ID counts above the reply-list batch ceiling"):
    typed {
      linked(protocols = Set(4)).use { (client, server) =>
        for
          c <- client.channel(4)
          s <- server.channel(4)
          request <- Resource
            .make(
              Fibers
                .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                  TxSubmissionServer.run(s, sink, TxSubmissionServer.Settings(idsPerRequest = 9))
                }
                .start
            )(_.cancel)
            .use { serving =>
              for
                _ <- send(c, State.Init, Role.Client, Message.Init)
                request <- receive(c, State.Idle, Role.Server)
                _ <- send(c, State.Ids(true), Role.Client, Message.Done)
                _ <- serving.joinWithNever.flatMap(Fibers.land[IO, NetworkError, Unit])
              yield request
            }
        yield request
      }
    }.map(result => assertEquals(result, Right(Message.RequestIds(true, 0, 9))))
