// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

/** Finite in-memory byte peer. All envelopes below are independently assembled from the pinned wire
  * grammar, without calling Handshake.encode, ChainSync.encode or Mux.encode. Opaque payloads and
  * synthetic points do not establish ledger/header/block validity.
  */
object FixturePeer:
  import ChainSync.*
  final case class Counts(
      reads: Int = 0,
      writes: Int = 0,
      readBytes: Long = 0L,
      writtenBytes: Long = 0L
  )
  final case class Report(
      profile: String,
      transitions: Int,
      finalState: State,
      counts: Counts,
      doneObserved: Boolean,
      phaseCoalescingChecked: Boolean
  )
  private enum Step:
    case Read(bytes: Bytes)
    case Expect(bytes: Bytes)
  private final case class ScriptState(steps: Vector[Step], counts: Counts, closed: Boolean = false)
  private def get[A](value: Either[String, A]): A =
    value.fold(s => throw new IllegalArgumentException(s), identity)
  private def hex(value: String): Bytes = get(Bytes.fromHex(value))
  private def join(parts: Bytes*): Bytes = Bytes(parts.toVector.flatMap(_.value))
  private def literal(values: Int*): Bytes = Bytes(values.toVector.map(_.toByte))

  // The fixture grammar uses only small synthetic slot/block numbers and fixed 32-byte hashes.
  private def pointBytes(slot: Int, hash: Int): Bytes =
    join(literal(0x82, slot, 0x58, 0x20), Bytes(Vector.fill(32)(hash.toByte)))
  private def point(slot: Int, hash: Int): Point =
    Point.Block(get(UInt64.from(BigInt(slot))), Bytes(Vector.fill(32)(hash.toByte)))
  private val a = point(1, 1)
  private val b = point(2, 2)
  private val c = point(3, 3)
  private val d = point(2, 4)
  private val e = point(3, 5)
  private val tipC = Tip(c, get(UInt64.from(BigInt(3))))
  private val tipE = Tip(e, get(UInt64.from(BigInt(3))))
  private val cTipBytes = join(literal(0x82), pointBytes(3, 3), literal(3))
  private val eTipBytes = join(literal(0x82), pointBytes(3, 5), literal(3))
  private val find =
    join(literal(0x82, 4, 0x9f), pointBytes(3, 5), pointBytes(2, 2), literal(0x80, 0xff))
  private val found = join(literal(0x83, 5), pointBytes(2, 2), cTipBytes)
  private val rollbackB = join(literal(0x83, 3), pointBytes(2, 2), cTipBytes)
  private val rollbackA = join(literal(0x83, 3), pointBytes(1, 1), eTipBytes)
  private val request = hex("8100")
  private val awaitReply = hex("8101")
  private val done = hex("8107")
  private val proposalNtn = hex("8200a10e84182af500f4")
  private val acceptNtn = hex("83010e84182af500f4")
  private val proposalNtc = hex("8200a119801082182af4")
  private val acceptNtc = hex("830119801082182af4")

  private def frame(protocol: Int, responder: Boolean, payload: Bytes): Bytes =
    require(payload.size > 0 && payload.size <= 65535)
    val number = protocol | (if responder then 0x8000 else 0)
    join(
      literal(0, 0, 0, 0, number >>> 8, number & 255, payload.size >>> 8, payload.size & 255),
      payload
    )

  private def readChunks(bytes: Bytes): Vector[Step] =
    // Deliberately split inside CBOR and mux payloads, including the >5760-byte NtC fixture.
    bytes.value.grouped(97).map(v => Step.Read(Bytes(v))).toVector

  private final class Peer(ref: Ref[IO, ScriptState]) extends ByteTransport[IO]:
    def read: IO[Option[Bytes]] = ref
      .modify { state =>
        if state.closed then (state, Right(None))
        else
          state.steps.headOption match
            case Some(Step.Read(bytes)) =>
              val count = state.counts.copy(
                reads = state.counts.reads + 1,
                readBytes = state.counts.readBytes + bytes.size
              )
              (state.copy(steps = state.steps.tail, counts = count), Right(Some(bytes)))
            case None => (state, Right(None))
            case _ =>
              (state, Left(new IllegalStateException("peer expected a write before this read")))
      }
      .flatMap(IO.fromEither)
    def write(bytes: Bytes): IO[Unit] = ref
      .modify { state =>
        if state.closed then (state, Left(new IllegalStateException("fixture peer closed")))
        else
          state.steps.headOption match
            case Some(Step.Expect(expected))
                if bytes.size > 0 && expected.value.startsWith(bytes.value) =>
              val remaining = expected.value.drop(bytes.size)
              val next =
                if remaining.isEmpty then state.steps.tail
                else Step.Expect(Bytes(remaining)) +: state.steps.tail
              val count = state.counts.copy(
                writes = state.counts.writes + 1,
                writtenBytes = state.counts.writtenBytes + bytes.size
              )
              (state.copy(steps = next, counts = count), Right(()))
            case _ =>
              (
                state,
                Left(new IllegalStateException("fixture peer observed unexpected outbound bytes"))
              )
      }
      .flatMap(IO.fromEither)
    def close: IO[Unit] = ref.update(_.copy(closed = true))
    def isClosed: IO[Boolean] = ref.get.map(_.closed)
    def completed: IO[Counts] = ref.get.flatMap { s =>
      IO.raiseUnless(s.steps.isEmpty)(
        new IllegalStateException("fixture peer has unconsumed script steps")
      ).as(s.counts)
    }

  private def peer(steps: Vector[Step]): Resource[IO, Peer] =
    Resource.make(Ref.of[IO, ScriptState](ScriptState(steps, Counts())).map(new Peer(_)))(_.close)
  private val deadlines =
    SessionDeadlines[IO](10.seconds, 3373.seconds, 10.seconds, IO.pure(Some(601.seconds)))

  /** Handshake accept and partial application frame share one transport read. Application bytes
    * become a legal IntersectFound only after the client sends FindIntersect. AwaitReply and the
    * final rollback share one application SDU.
    */
  def run[P](profile: ConnectionSession.Profile[P], payload: P): IO[Report] =
    val protocol = profile.protocol
    val ntn = profile.suite == Handshake.Suite.NodeToNode
    val proposal = if ntn then proposalNtn else proposalNtc
    val accept = if ntn then acceptNtn else acceptNtc
    def expect(bytes: Bytes): Step = Step.Expect(frame(protocol, false, bytes))
    def inbound(bytes: Bytes): Vector[Step] = readChunks(frame(protocol, true, bytes))
    for
      raw <- IO.fromEither(profile.codec.encode(payload).leftMap(new IllegalArgumentException(_)))
      firstFrame = frame(protocol, true, found)
      steps = Vector(
        Step.Expect(frame(0, false, proposal)),
        Step.Read(join(frame(0, true, accept), Bytes(firstFrame.value.take(5)))),
        expect(find),
        Step.Read(Bytes(firstFrame.value.drop(5))),
        expect(request)
      ) ++ inbound(rollbackB) ++ Vector(expect(request)) ++
        inbound(join(literal(0x83, 2), raw, cTipBytes)) ++ Vector(expect(request)) ++
        inbound(join(awaitReply, rollbackA)) ++ Vector(expect(request)) ++
        inbound(join(literal(0x83, 2), raw, eTipBytes)) ++ Vector(expect(request)) ++
        inbound(join(literal(0x83, 2), raw, eTipBytes)) ++ Vector(expect(done))
      report <- peer(steps).use { transport =>
        ConnectionSession.resource[IO, P](transport, profile, Role.Client, deadlines).use {
          session =>
            for
              model <- Ref.of[IO, FixtureChainModel.Model](
                get(FixtureChainModel.start(Vector(Point.Origin, a, b)))
              )
              result <- session.negotiate(Vector(profile.version -> Handshake.Data(42)))
              _ <- IO.raiseUnless(
                result == Handshake.Result.Negotiated(profile.version, Handshake.Data(42))
              )(new IllegalStateException("unexpected negotiation"))
              send = (message: Message[P]) =>
                session.send(message) *> model.update(m => get(m.step(Role.Client, message)))
              receive = (expected: Message[P], forward: Option[Point]) =>
                session.receive.flatMap { actual =>
                  // Payload wrappers have reference equality; compare opaque bytes explicitly instead.
                  val equal = (actual, expected) match
                    case (Message.RollForward(ap, at), Message.RollForward(ep, et)) =>
                      profile.codec.encode(ap) == profile.codec.encode(ep) && at == et
                    case _ => actual == expected
                  IO.raiseUnless(equal)(
                    new IllegalStateException("fixture response differs from expected script")
                  ) *>
                    model.update(m => get(m.step(Role.Server, actual, forward)))
                }
              _ <- send(Message.FindIntersect(Vector(e, b, Point.Origin)))
              _ <- receive(Message.IntersectFound(b, tipC), None)
              _ <- send(Message.RequestNext)
              _ <- receive(Message.RollBackward(b, tipC), None)
              _ <- send(Message.RequestNext)
              _ <- receive(Message.RollForward(payload, tipC), Some(c))
              _ <- send(Message.RequestNext)
              _ <- receive(Message.AwaitReply, None)
              _ <- receive(Message.RollBackward(a, tipE), None)
              _ <- send(Message.RequestNext)
              _ <- receive(Message.RollForward(payload, tipE), Some(d))
              _ <- send(Message.RequestNext)
              _ <- receive(Message.RollForward(payload, tipE), Some(e))
              _ <- send(Message.Done)
              // This asserts peer consumption of exact wire Done, before either resource closes.
              counts <- transport.completed
              finalModel <- model.get
              status <- session.status
              _ <- IO.raiseUnless(
                finalModel.state == State.Done && status.state == State.Done &&
                  finalModel.history == Vector(Point.Origin, a, d, e) && finalModel.trace.size == 14
              )(new IllegalStateException("synthetic model diverged"))
            yield Report(
              if ntn then "NtN14/protocol2" else "NtC16/protocol5",
              finalModel.trace.size,
              status.state,
              counts,
              doneObserved = true,
              phaseCoalescingChecked = true
            )
        }
      }
    yield report

  /** Responder-side phase boundary: proposal and RequestNext in the same read. Accept must be
    * emitted before the already-buffered application request is exposed. Done is actually read.
    */
  def responderBoundary[P](profile: ConnectionSession.Profile[P]): IO[Counts] =
    val ntn = profile.suite == Handshake.Suite.NodeToNode
    val proposal = if ntn then proposalNtn else proposalNtc
    val accept = if ntn then acceptNtn else acceptNtc
    val rollbackOrigin = hex("830380828000")
    val steps = Vector(
      Step.Read(join(frame(0, false, proposal), frame(profile.protocol, false, request))),
      Step.Expect(frame(0, true, accept)),
      Step.Expect(frame(profile.protocol, true, rollbackOrigin)),
      Step.Read(frame(profile.protocol, false, done))
    )
    peer(steps).use { transport =>
      ConnectionSession.resource[IO, P](transport, profile, Role.Server, deadlines).use { session =>
        for
          _ <- session.negotiate(Vector(profile.version -> Handshake.Data(42)))
          first <- session.receive
          _ <- IO.raiseUnless(first == Message.RequestNext)(
            new IllegalStateException("expected coalesced request")
          )
          _ <- session.send(Message.RollBackward(Point.Origin, Tip.Origin))
          last <- session.receive
          _ <- IO.raiseUnless(last == Message.Done)(
            new IllegalStateException("Done was not consumed")
          )
          count <- transport.completed
        yield count
      }
    }
