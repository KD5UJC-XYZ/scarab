// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.TxSubmission2Fixtures
import xyz.kd5ujc.scarab.protocol.TxSubmission2Fixtures.given
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{RelayOffer, TxSubmission2, TxSubmission2Engine}
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
    def reader = TxSubmission2Fixtures.structural
    def after(ticket: Long, max: Int) = IO.pure(all.filter(_.ticket > ticket).take(max))
    def awaitAfter(ticket: Long) = IO.unit
    def body(txId: Bytes) = IO.pure(None)

  /** A transaction the structural reader frames: `[{}, {}, true, null]`. */
  private val transaction = Bytes(Vector(0x84, 0xa0, 0xa0, 0xf5, 0xf6).map(_.toByte))
  private val holding: TxSubmissionClient.Source[IO] = new TxSubmissionClient.Source[IO]:
    def reader = TxSubmission2Fixtures.structural
    def after(ticket: Long, max: Int) = IO.pure(all.filter(_.ticket > ticket).take(max))
    def awaitAfter(ticket: Long) = IO.unit
    def body(txId: Bytes) = IO.pure(Some(transaction))

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

  /** What a requester does to the window: offer ids of a small alphabet (so that an id is offered
    * again), acknowledge the oldest, or ask for some.
    */
  private enum Step:
    case Offer(ids: List[Int])
    case Acknowledge(count: Int)
    case Ask(picks: List[Int])

  private val step: Gen[Step] = Gen.frequency(
    3 -> Gen.choose(1, 4).flatMap(Gen.listOfN(_, Gen.choose(0, 2))).map(Step.Offer(_)),
    2 -> Gen.choose(0, 4).map(Step.Acknowledge(_)),
    4 -> Gen.choose(1, 3).flatMap(Gen.listOfN(_, Gen.choose(0, 9))).map(Step.Ask(_))
  )

  private type Window = Vector[TxSubmissionPolicy.Advertised[Bytes]]

  property("a requester that asks once per advertisement it holds is never refused"):
    // the requester keeps its own view, the advertisements by position, and asks for any of
    // those it has not asked for, one per id in a request, not necessarily the oldest
    forAll(Gen.listOfN(200, step)) { steps =>
      val (_, _, refusals) =
        steps.foldLeft((Vector.empty[(Int, Boolean)], Vector.empty: Window, 0)) {
          case ((mine, window, refusals), Step.Offer(ns)) =>
            val fresh = ns.take(TxSubmissionPolicy.MaxUnacknowledged - mine.size)
            (
              mine ++ fresh.map(_ -> false),
              window ++ fresh.map(n => TxSubmissionPolicy.Advertised(id(n))),
              refusals
            )
          case ((mine, window, refusals), Step.Acknowledge(n)) =>
            (mine.drop(n), window.drop(n), refusals)
          case ((mine, window, refusals), Step.Ask(picks)) =>
            val open = mine.indices.filterNot(mine(_)._2)
            val chosen = picks
              .flatMap(p => open.lift(p))
              .distinct
              .foldLeft(Vector.empty[Int])((kept, at) =>
                if kept.exists(mine(_)._1 == mine(at)._1) then kept else kept :+ at
              )
            if chosen.isEmpty then (mine, window, refusals)
            else
              TxSubmissionPolicy.bodies(window, chosen.map(at => id(mine(at)._1))) match
                case Right(marked) =>
                  (
                    chosen.foldLeft(mine)((m, at) => m.updated(at, (m(at)._1, true))),
                    marked,
                    refusals
                  )
                case Left(_) => (mine, window, refusals + 1)
        }
      refusals == 0
    }

  property("no request sequence is served an id more often than it was advertised"):
    forAll(Gen.listOfN(200, step)) { steps =>
      val (_, offered, asked, bounded) =
        steps.foldLeft((Vector.empty: Window, Map.empty[Int, Int], Map.empty[Int, Int], true)) {
          case ((window, offered, asked, ok), Step.Offer(ns)) =>
            val fresh = ns.take(TxSubmissionPolicy.MaxUnacknowledged - window.size)
            (
              window ++ fresh.map(n => TxSubmissionPolicy.Advertised(id(n))),
              fresh.foldLeft(offered)((o, n) => o.updated(n, o.getOrElse(n, 0) + 1)),
              asked,
              ok
            )
          case ((window, offered, asked, ok), Step.Acknowledge(n)) =>
            (window.drop(n), offered, asked, ok)
          case ((window, offered, asked, ok), Step.Ask(picks)) =>
            val ns = picks.map(_ % 3)
            TxSubmissionPolicy.bodies(window, ns.toVector.map(id)) match
              case Right(marked) =>
                val now = ns.foldLeft(asked)((a, n) => a.updated(n, a.getOrElse(n, 0) + 1))
                val within = now.forall((n, k) => k <= offered.getOrElse(n, 0)) &&
                  ns.distinct.size == ns.size
                (marked, offered, now, ok && within)
              case Left(_) => (window, offered, asked, ok)
        }
      bounded && asked.values.sum <= offered.values.sum
    }

  test("a request for an id is refused once all its advertisements were asked for"):
    val window: Window =
      Vector(0, 1, 0).map(n => TxSubmissionPolicy.Advertised(id(n)))
    val twice = for
      first <- TxSubmissionPolicy.bodies(window, Vector(id(0)))
      second <- TxSubmissionPolicy.bodies(first, Vector(id(0), id(1)))
    yield second
    assertEquals(twice.map(_.map(_.requested)), Right(Vector(true, true, true)))
    assertEquals(
      twice.flatMap(TxSubmissionPolicy.bodies(_, Vector(id(0)))),
      Left(TxSubmissionPolicy.Violation.AlreadyRequested(id(0)))
    )
    assertEquals(
      TxSubmissionPolicy.bodies(window, Vector(id(2))),
      Left(TxSubmissionPolicy.Violation.UnadvertisedBody(id(2)))
    )
    assertEquals(
      TxSubmissionPolicy.bodies(window, Vector(id(0), id(0))),
      Left(TxSubmissionPolicy.Violation.DuplicateBody(id(0)))
    )
    // acknowledging the oldest drops its mark with it; the later advertisement keeps its own
    assertEquals(
      twice.map(_.drop(1)).flatMap(TxSubmissionPolicy.bodies(_, Vector(id(0)))),
      Left(TxSubmissionPolicy.Violation.AlreadyRequested(id(0)))
    )
    assertEquals(
      TxSubmissionPolicy
        .bodies(window, Vector(id(0)))
        .map(_.drop(1))
        .flatMap(TxSubmissionPolicy.bodies(_, Vector(id(0))))
        .map(_.map(_.requested)),
      Right(Vector(false, true))
    )

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

  test("the bytes of each reply's transactions are reported once it is sent"):
    TestControl
      .executeEmbed(for
        told <- Ref.of[IO, Vector[Long]](Vector.empty)
        result <- typed {
          linked(protocols = Set(4)).use { (client, server) =>
            for
              c <- client.channel(4)
              s <- server.channel(4)
              running <- typed(
                TxSubmissionClient.run(c, holding, served = Some(n => told.update(_ :+ n)))
              ).start
              _ <- receive(s, State.Init, Role.Client)
              _ <- send(s, State.Idle, Role.Server, Message.RequestIds(true, 0, 3))
              _ <- receive(s, State.Ids(true), Role.Client)
              _ <- send(s, State.Idle, Role.Server, Message.RequestTxs(Vector(id(0), id(2))))
              reply <- receive(s, State.Txs, Role.Client)
              _ <- send(s, State.Idle, Role.Server, Message.RequestTxs(Vector(id(1))))
              _ <- receive(s, State.Txs, Role.Client)
              _ <- send(s, State.Idle, Role.Server, Message.RequestIds(false, 0, 0))
              ended <- running.joinWithNever
            yield (reply, ended)
          }
        }
        reported <- told.get
      yield (result, reported))
      .map { (result, reported) =>
        assertEquals(
          result,
          Right(
            (
              Message.ReplyTxs(Vector(transaction, transaction)),
              Left(NetworkError.TxSubmission(TxSubmissionPolicy.Violation.EmptyRequest))
            )
          )
        )
        assertEquals(reported, Vector(2L * transaction.size, transaction.size.toLong))
      }

  /** A profile with the reference's byte limits and no batch cap of its own, so that only the reply
    * bound limits what one reply carries. Ids and bodies are single CBOR byte strings.
    */
  private val wide: TxSubmission2Engine.Codec[Bytes, Bytes] =
    TxSubmission2Engine
      .fromCodecs[Bytes, Bytes](
        maxBatch = 65535,
        maxOriginalBytes = 2500000,
        maxAdvertisedSize = 0xffffffffL,
        maxMessageBytes = {
          case State.Init | State.Idle  => 65535
          case State.Ids(_) | State.Txs => 2500000
          case State.Done               => 0
        },
        encodeId = Right(_),
        decodeId = Right(_),
        encodeBody = Right(_),
        decodeBody = Right(_),
        checkOffer = _ => Right(()),
        advertisedSize = n => Right(n.toLong),
        bodyId = Right(_)
      )
      .fold(error => fail(error.message), identity)

  private val wideRules = new TxSubmission2Runtime.Rules[Bytes]:
    def acknowledge[A](unacked: Vector[A], blocking: Boolean, ack: Int, request: Int) =
      TxSubmissionPolicy.acknowledge(unacked, blocking, ack, request)
    def bodies(unacked: Vector[TxSubmission2Runtime.Advertised[Bytes]], ids: Vector[Bytes]) =
      TxSubmissionPolicy.bodies(unacked, ids)
    def offers(ids: Vector[Bytes], requested: Int) = TxSubmissionPolicy.offers(ids, requested)

  /** A CBOR byte string of `n` bytes, all `fill`. */
  private def byteString(n: Int, fill: Int): Bytes =
    Bytes(
      Vector(0x5a.toByte) ++ java.nio.ByteBuffer.allocate(4).putInt(n).array().toVector ++
        Vector.fill(n)(fill.toByte)
    )

  test("a reply carries the requested bodies only while it stays within the receiver's limit"):
    val large = Vector.tabulate(3)(n => byteString(1000000, n))
    val ids = Vector.tabulate(3)(n => Bytes(Vector(0x41.toByte, n.toByte)))
    val wideSource = new TxSubmission2Runtime.Source[IO, Bytes, Bytes]:
      private val held =
        ids.zip(large).zipWithIndex.map { case ((i, b), n) =>
          TxSubmission2Runtime.Held(i, b.size, n + 1L)
        }
      def after(ticket: Long, max: Int) = IO.pure(held.filter(_.ticket > ticket).take(max))
      def awaitAfter(ticket: Long) = IO.unit
      def body(id: Bytes) = IO.pure(ids.indexOf(id) match
        case -1 => None
        case n  => Some(large(n)))
    def put(
        channel: MuxChannel[IO],
        state: State,
        message: TxSubmission2Engine.Message[Bytes, Bytes]
    )(using
        Net
    ): IO[Unit] = wide.encode(state, Role.Server, message).fold(e => fail(e.toString), channel.send)
    def take(channel: MuxChannel[IO], state: State)(using Net) =
      channel.recv(wide.maxMessageBytes(state))(wide.decodePrefix(state, Role.Client, _))
    TestControl
      .executeEmbed(typed {
        linked(
          protocols = Set(4),
          server = Mux.Config(role = Mux.Role.Responder, ingressLimit = _ => 4000000L)
        ).use { (client, server) =>
          for
            c <- client.channel(4)
            s <- server.channel(4)
            running <- typed(
              TxSubmission2Runtime.client(
                c,
                wideSource,
                wide,
                wideRules,
                None,
                _ => xyz.kd5ujc.scarab.protocol.ProtocolError.UnknownTxSubmission2TagStateArity
              )
            ).start
            _ <- take(s, State.Init)
            _ <- put(s, State.Idle, TxSubmission2Engine.Message.RequestIds(true, 0, 3))
            _ <- take(s, State.Ids(true))
            _ <- put(s, State.Idle, TxSubmission2Engine.Message.RequestTxs(ids))
            reply <- IO.race(take(s, State.Txs), running.joinWithNever)
            _ <- running.cancel
          yield reply
        }
      })
      .map(result =>
        assertEquals(
          result.map(_.left.map {
            case TxSubmission2Engine.Message.ReplyTxs(originals) =>
              (originals.map(_.size), originals == large.take(2))
            case _ => (Vector.empty, false)
          }),
          Right(Left((Vector(1000005, 1000005), true)))
        )
      )

  test("the reply bound counts the reply's framing and keeps the longest prefix within it"):
    assertEquals(TxSubmissionPolicy.ReplyByteLimit, 2500000L)
    assertEquals(TxSubmissionPolicy.replyWithin(Vector.empty), 0)
    assertEquals(TxSubmissionPolicy.replyWithin(Vector(2499996L)), 1)
    assertEquals(TxSubmissionPolicy.replyWithin(Vector(2499997L)), 0)
    assertEquals(TxSubmissionPolicy.replyWithin(Vector(1L, 2499996L, 1L)), 1)
    // the largest reply the local profile builds: eight bodies of 65,536 bytes
    assertEquals(TxSubmissionPolicy.replyWithin(Vector.fill(8)(65545L)), 8)
    assertEquals(TxSubmission2.advertisedSize(TxSubmission2.MaxOriginalBytes), Right(65545L))

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

  /** Offers `id(0)`, `id(1)`, then `id(0)` again under a later ticket, as a mempool that took the
    * transaction back after it left holds it.
    */
  private val readmitting: TxSubmissionClient.Source[IO] = new TxSubmissionClient.Source[IO]:
    private val held = Vector(0, 1, 0).zipWithIndex.map { (n, at) =>
      TxSubmissionClient.Held(id(n), transaction.size, at + 1L)
    }
    def reader = TxSubmission2Fixtures.structural
    def after(ticket: Long, max: Int) = IO.pure(held.filter(_.ticket > ticket).take(max))
    def awaitAfter(ticket: Long) = IO.unit
    def body(txId: Bytes) = IO.pure(Some(transaction))

  /** Sends `requests` to the offering side one at a time, each after the answer to the one before:
    * the answers, and how the side ended if it ended.
    */
  private def converse(
      offering: TxSubmissionClient.Source[IO],
      requests: List[Message]
  ): IO[Either[NetworkError, (Vector[Message], Option[Either[NetworkError, Unit]])]] =
    TestControl.executeEmbed(typed {
      linked(protocols = Set(4)).use { (client, server) =>
        for
          c <- client.channel(4)
          s <- server.channel(4)
          running <- typed(TxSubmissionClient.run(c, offering)).start
          _ <- receive(s, State.Init, Role.Client)
          result <- requests.foldLeftM(
            (Vector.empty[Message], Option.empty[Either[NetworkError, Unit]])
          ) {
            case (done @ (_, Some(_)), _) => IO.pure(done)
            case ((answers, None), request) =>
              val answer = request match
                case Message.RequestIds(blocking, _, _) => State.Ids(blocking)
                case _                                  => State.Txs
              send(s, State.Idle, Role.Server, request) *>
                IO.race(receive(s, answer, Role.Client), running.joinWithNever).map {
                  case Left(reply)  => (answers :+ reply, None)
                  case Right(ended) => (answers, Some(ended))
                }
          }
          _ <- running.cancel
        yield result
      }
    })

  private val served = Message.ReplyTxs(Vector(transaction))
  private def refused(n: Int) =
    Some(Left(NetworkError.TxSubmission(TxSubmissionPolicy.Violation.AlreadyRequested(id(n)))))

  test("a body asked for again under the same advertisement ends the connection"):
    converse(
      holding,
      List(
        Message.RequestIds(true, 0, 1),
        Message.RequestTxs(Vector(id(0))),
        Message.RequestTxs(Vector(id(0)))
      )
    ).map(result =>
      assertEquals(
        result,
        Right((Vector(Message.ReplyIds(Vector(RelayOffer(id(0), 10))), served), refused(0)))
      )
    )

  test("an id advertised twice in the window is served once per advertisement, and no more"):
    // the order of the reference's default inbound side (`Inbound/V1.hs` 291-292, 365-375, 417-447):
    // the omitted or served first advertisement is acknowledged locally, the id offered again is
    // asked for before that acknowledgement is sent
    converse(
      readmitting,
      List(
        Message.RequestIds(true, 0, 2),
        Message.RequestTxs(Vector(id(0))),
        Message.RequestIds(false, 0, 1),
        Message.RequestTxs(Vector(id(0))),
        Message.RequestTxs(Vector(id(1))),
        Message.RequestIds(false, 1, 0),
        Message.RequestTxs(Vector(id(0)))
      )
    ).map(result =>
      assertEquals(
        result,
        Right(
          (
            Vector(
              Message.ReplyIds(Vector(RelayOffer(id(0), 10), RelayOffer(id(1), 10))),
              served,
              Message.ReplyIds(Vector(RelayOffer(id(0), 10))),
              served,
              served,
              Message.ReplyIds(Vector.empty)
            ),
            refused(0)
          )
        )
      )
    )

  test("acknowledgement drops the advertisements with their marks, and only those"):
    converse(
      readmitting,
      List(
        Message.RequestIds(true, 0, 1),
        Message.RequestTxs(Vector(id(0))),
        Message.RequestIds(true, 1, 2),
        Message.RequestTxs(Vector(id(0))),
        Message.RequestIds(false, 1, 0),
        Message.RequestTxs(Vector(id(0)))
      )
    ).map(result =>
      assertEquals(
        result,
        Right(
          (
            Vector(
              Message.ReplyIds(Vector(RelayOffer(id(0), 10))),
              served,
              Message.ReplyIds(Vector(RelayOffer(id(1), 10), RelayOffer(id(0), 10))),
              served,
              Message.ReplyIds(Vector.empty)
            ),
            refused(0)
          )
        )
      )
    )

  for unexpected <- List(false, true) do
    test(s"uncaught local source ${if unexpected then "bug" else "IOException"} remains a defect"):
      val cause =
        if unexpected then new IllegalStateException("injected backend defect")
        else new java.io.IOException("injected closure")
      val broken = new TxSubmissionClient.Source[IO]:
        def reader = TxSubmission2Fixtures.structural
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
            def reader = TxSubmission2Fixtures.structural
            def known(txId: Bytes) = called.set(true).as(false)
            def submit(originals: Vector[Bytes])(using
                cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
            ) = called.set(true)
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
    def reader = TxSubmission2Fixtures.structural
    def known(txId: Bytes) = IO.pure(false)
    def submit(originals: Vector[Bytes])(using
        cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
    ) = IO.unit

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
