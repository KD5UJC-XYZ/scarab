// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.{Queue, Semaphore}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

/** One exclusively owned connection, handshake followed by one non-pipelined fixture profile. No
  * callbacks, background reader, socket addressing, or payload validity claims.
  */
final class SingleProtocolConnection[F[_]: Async, S, M <: Product] private (
    transport: ByteTransport[F],
    val driver: SingleProtocolConnection.Driver[F, S, M],
    val role: ChainSync.Role,
    config: SingleProtocolConnection.Config,
    ref: Ref[F, SingleProtocolConnection.Snapshot[S]],
    gate: Semaphore[F],
    stopped: Deferred[F, String],
    changed: Queue[F, Unit]
):
  import SingleProtocolConnection.*
  private val F = Async[F]
  private val direction =
    if role == ChainSync.Role.Client then Mux.Direction.Initiator else Mux.Direction.Responder
  private val remote =
    if role == ChainSync.Role.Client then ChainSync.Role.Server else ChainSync.Role.Client
  private def fail[A](message: String): F[A] = F.raiseError(
    if message == "whole request deadline expired" then
      new SingleProtocolConnection.WholeDeadlineExceeded
    else new IllegalStateException(message)
  )
  private def checked[A](value: Either[String, A]): F[A] =
    F.fromEither(value.leftMap(new IllegalArgumentException(_)))
  def status: F[Status[S]] = ref.get.map(s =>
    Status(
      s.active,
      s.state,
      s.closed,
      s.events,
      s.epoch,
      retained(s),
      s.peakIngressBytes,
      s.frames.size,
      s.peakFrames
    )
  )

  private def retained(s: Snapshot[S]): Int =
    s.pending.size + s.decoder.pendingBytes + s.frames.map(_.payload.size).sum

  private def terminate(reason: String, expected: Option[Long] = None): F[Unit] = F.uncancelable {
    _ =>
      ref
        .modify { s =>
          if s.closed.nonEmpty || expected.exists(_ != s.epoch) then (s, false)
          else (s.copy(closed = Some(reason), epoch = s.epoch + 1, expires = None), true)
        }
        .flatMap(won =>
          if won then
            stopped
              .complete(reason)
              .void *> changed.tryOffer(()).void *> transport.close.attempt.void
          else F.unit
        )
  }
  private[network] def expire(reason: String): F[Unit] = terminate(reason)
  def close: F[Unit] = F.uncancelable(_ => terminate("closed") *> transport.close.attempt.void)
  private def live(s: Snapshot[S], now: FiniteDuration): F[Unit] =
    s.closed match
      case Some(reason)                            => fail(reason)
      case None if s.wholeExpires.exists(now >= _) => fail("whole request deadline expired")
      case None if s.expires.exists(now >= _)      => fail("state deadline expired")
      case None                                    => F.unit
  private def current: F[Snapshot[S]] = for
    s <- ref.get
    now <- F.monotonic
    _ <- live(s, now)
  yield s

  // Admission is cancelable. Only a caller that acquired the permit can poison the stream.
  private def operation[A](body: F[A]): F[A] = gate.permit.use { _ =>
    F.uncancelable { poll =>
      poll(current *> body)
        .onCancel(terminate("operation canceled") *> transport.close.attempt.void)
        .onError { case e =>
          terminate(Option(e.getMessage).getOrElse("operation failed")).attempt.void
        }
    }
  }
  private def commit(s: Snapshot[S], next: Snapshot[S]): F[Unit] = F.uncancelable { _ =>
    F.monotonic.flatMap { now =>
      ref
        .modify { actual =>
          if actual.closed.nonEmpty || actual.epoch != s.epoch || actual.expires.exists(
              now >= _
            ) || actual.wholeExpires.exists(now >= _)
          then (actual, false)
          else
            (
              next.copy(
                peakIngressBytes = math.max(actual.peakIngressBytes, retained(next)),
                peakFrames = math.max(actual.peakFrames, next.frames.size)
              ),
              true
            )
        }
        .flatMap(ok =>
          if ok then changed.tryOffer(()).void
          else
            ref.get
              .flatMap(actual => live(actual, now) *> fail("closed, stale, or expired operation"))
        )
    }
  }
  private def transition(
      s: Snapshot[S],
      state: S,
      event: String,
      pending: Bytes,
      offered: Vector[ChainSync.Point]
  ): F[Unit] = for
    duration <- interruptible(driver.duration(state))
    _ <- F.raiseWhen(duration.exists(_ <= Duration.Zero))(
      new IllegalArgumentException("invalid state duration")
    )
    now <- F.monotonic
    _ <- commit(
      s,
      s.copy(
        active = true,
        state = state,
        pending = pending,
        offered = offered,
        epoch = s.epoch + 1,
        expires = duration.map(now + _),
        events = (s.events :+ event).takeRight(config.maxEvents)
      )
    )
  yield ()

  private[network] def watch: F[Unit] =
    def loop: F[Unit] = ref.get.flatMap { s =>
      if s.closed.nonEmpty then F.unit
      else
        (s.expires.toVector ++ s.wholeExpires.toVector).minOption match
          case None => changed.take *> loop
          case Some(end) =>
            F.monotonic.flatMap { now =>
              if now >= end then
                terminate(
                  if s.wholeExpires.exists(now >= _) then "whole request deadline expired"
                  else "state deadline expired",
                  Some(s.epoch)
                ) *> loop
              else F.race(F.sleep(end - now), changed.take) *> loop
            }
    }
    loop

  private def interruptible[A](io: F[A]): F[A] =
    F.race(stopped.get.flatMap(fail[A]), io).map(_.merge)

  private def write(bytes: Bytes, protocol: Int, max: Int): F[Unit] =
    checked(Mux.segmentBounded(bytes, 0L, direction, config.maxSduPayload, protocol, max))
      .flatMap(
        _.traverse_(frame =>
          checked(Mux.encode(frame)).flatMap { encoded =>
            encoded.value
              .grouped(config.maxChunkBytes)
              .toVector
              .traverse_(part => interruptible(transport.write(Bytes(part))))
          }
        )
      )

  private def more(s: Snapshot[S], protocol: Int, messageLimit: Int): F[Unit] =
    if s.pending.size >= messageLimit then fail("message byte limit exceeded")
    else
      s.frames.headOption match
        case Some(frame) =>
          if frame.protocol != protocol || frame.direction == direction then
            fail("unexpected mux protocol or direction")
          else
            commit(
              s,
              s.copy(
                frames = s.frames.tail,
                pending = Bytes(s.pending.value ++ frame.payload.value)
              )
            )
        case None =>
          interruptible(transport.read).flatMap {
            case None => checked(s.decoder.finish) *> fail("EOF before complete message")
            case Some(chunk) if chunk.size == 0 || chunk.size > config.maxChunkBytes =>
              fail("invalid transport chunk size")
            case Some(chunk) =>
              val retained = s.pending.size.toLong + s.decoder.pendingBytes
              if retained + chunk.size > config.maxIngressBytes then
                fail("ingress byte budget exceeded")
              else if chunk.size.toLong > config.maxWireBytes - s.wireBytes then
                fail("wire byte budget exceeded")
              else
                checked(s.decoder.feed(chunk)).flatMap { case (decoder, frames) =>
                  if frames.size > config.maxFrames then fail("frame backlog limit exceeded")
                  else if frames.size.toLong > config.maxTotalFrames - s.totalFrames then
                    fail("total frame limit exceeded")
                  else
                    commit(
                      s,
                      s.copy(
                        decoder = decoder,
                        frames = frames,
                        wireBytes = s.wireBytes + chunk.size,
                        totalFrames = s.totalFrames + frames.size
                      )
                    )
                }
          }

  private def handshakeMessage(state: Handshake.State): F[Handshake.Message] = current.flatMap {
    s =>
      Handshake.decodePrefix(driver.suite, state, s.pending) match
        case Right((message, rest)) =>
          if rest.size != 0 then fail("trailing handshake CBOR")
          else commit(s, s.copy(pending = rest)).as(message)
        case Left(error) if error.incomplete =>
          more(s, 0, Handshake.MaxMessageBytes) *> handshakeMessage(state)
        case Left(error) => fail(error.message)
  }

  /** The selected profile is checked against the actual negotiated result, never caller-asserted.
    */
  def negotiate(offers: Vector[(Int, Handshake.Data)]): F[Handshake.Result] = operation {
    for
      start <- current
      _ <- F.raiseWhen(start.active)(new IllegalStateException("already negotiated"))
      result <-
        if role == ChainSync.Role.Client then
          for
            pair <- checked(Handshake.clientStart(driver.suite, offers))
            _ <- checked(Handshake.encode(driver.suite, pair._2))
              .flatMap(write(_, 0, Handshake.MaxMessageBytes))
            reply <- handshakeMessage(Handshake.State.Confirm)
            result <- checked(pair._1.receive(reply)).map(_._2)
          yield result
        else
          for
            proposal <- handshakeMessage(Handshake.State.Propose)
            pair <- checked(Handshake.responder(driver.suite, offers, proposal))
            _ <- checked(Handshake.encode(driver.suite, pair._1))
              .flatMap(write(_, 0, Handshake.MaxMessageBytes))
          yield pair._2
      _ <- result match
        case Handshake.Result.Negotiated(version, data) if !data.query =>
          if version != driver.version then
            fail("negotiated version has no fixture application profile")
          else
            current.flatMap(s =>
              transition(s, driver.initial, "negotiated", s.pending, Vector.empty)
            )
        case _ => finishHandshake
    yield result
  }
  private def finishHandshake: F[Unit] = F.uncancelable { _ =>
    current.flatMap { s =>
      commit(
        s,
        s.copy(closed = Some("terminal handshake result"), epoch = s.epoch + 1, expires = None)
      ) *>
        stopped.complete("terminal handshake result").void
    }
  }

  private def active: F[Snapshot[S]] =
    current.flatMap(s => if s.active then F.pure(s) else fail("handshake not negotiated"))
  def send(message: M): F[Unit] = operation(sendMessage(message, false))
  private[network] def sendOnce(message: M): F[Unit] = operation(sendMessage(message, true))
  private def sendMessage(message: M, once: Boolean): F[Unit] = {
    for
      s <- active
      _ <- F.raiseWhen(once && s.requested)(
        new IllegalStateException("one request per fixture session")
      )
      next <- checked(driver.transition(s.state, role, message))
      offered <- checked(driver.semantics(s.offered, message))
      bytes <- checked(driver.encode(s.state, role, message))
      _ <- write(bytes, driver.protocol, driver.messageLimit(s.state))
      _ <- transition(
        s.copy(requested = s.requested || once),
        next,
        s"send:${message.productPrefix}",
        s.pending,
        offered
      )
    yield ()
  }
  def receive: F[M] = operation {
    def loop: F[M] = active.flatMap { s =>
      driver.decode(s.state, remote, s.pending) match
        case ChainSync.DecodeResult.NeedMore =>
          more(s, driver.protocol, driver.messageLimit(s.state)) *> loop
        case ChainSync.DecodeResult.Failed(error) => fail(error)
        case ChainSync.DecodeResult.Decoded(message, consumed) =>
          for
            next <- checked(driver.transition(s.state, remote, message))
            offered <- checked(driver.semantics(s.offered, message))
            _ <- transition(
              s,
              next,
              s"receive:${message.productPrefix}",
              Bytes(s.pending.value.drop(consumed)),
              offered
            )
          yield message
    }
    loop
  }

  /** Reject every buffered suffix, including incomplete mux headers, at the single-request
    * boundary.
    */
  def requireDrained: F[Unit] = operation {
    current.flatMap(s =>
      F.raiseWhen(retained(s) != 0)(
        new IllegalStateException("unsolicited buffered bytes after terminal batch")
      )
    )
  }

object SingleProtocolConnection:
  /** Distinguishes an overall useful-work budget from state/protocol failure. */
  final class WholeDeadlineExceeded extends IllegalStateException("whole request deadline expired")
  private[network] trait Driver[F[_], S, M <: Product]:
    def suite: Handshake.Suite
    def version: Int
    def protocol: Int
    def initial: S
    def handshake: FiniteDuration
    def valid: Boolean
    def duration(state: S): F[Option[FiniteDuration]]
    def messageLimit(state: S): Int
    def transition(state: S, sender: ChainSync.Role, message: M): Either[String, S]
    def encode(state: S, sender: ChainSync.Role, message: M): Either[String, Bytes]
    def decode(state: S, sender: ChainSync.Role, bytes: Bytes): ChainSync.DecodeResult[M]
    def semantics(
        offered: Vector[ChainSync.Point],
        message: M
    ): Either[String, Vector[ChainSync.Point]] = Right(offered)
  final case class Config(
      maxSduPayload: Int = 256,
      maxChunkBytes: Int = 1024,
      maxIngressBytes: Int = 131086,
      maxFrames: Int = 1024,
      maxEvents: Int = 256,
      maxWireBytes: Long = Long.MaxValue,
      maxTotalFrames: Long = Long.MaxValue,
      wholeRequest: Option[FiniteDuration] = None
  )
  final case class Status[S](
      active: Boolean,
      state: S,
      closed: Option[String],
      events: Vector[String],
      epoch: Long,
      ingressBytes: Int,
      peakIngressBytes: Int,
      frames: Int,
      peakFrames: Int
  )
  private final case class Snapshot[S](
      decoder: Mux.Decoder,
      frames: Vector[Mux.Sdu],
      pending: Bytes,
      active: Boolean,
      state: S,
      offered: Vector[ChainSync.Point],
      epoch: Long,
      expires: Option[FiniteDuration],
      closed: Option[String],
      events: Vector[String],
      peakIngressBytes: Int = 0,
      peakFrames: Int = 0,
      wireBytes: Long = 0L,
      totalFrames: Long = 0L,
      requested: Boolean = false,
      wholeExpires: Option[FiniteDuration] = None
  )
  private[network] def resource[F[_]: Async, S, M <: Product](
      transport: ByteTransport[F],
      driver: Driver[F, S, M],
      role: ChainSync.Role,
      config: Config = Config()
  ): Resource[F, SingleProtocolConnection[F, S, M]] =
    val F = Async[F]
    Resource
      .make {
        for
          _ <- F.raiseUnless(
            config.maxSduPayload > 0 && config.maxSduPayload <= 65535 &&
              config.maxChunkBytes > 0 && config.maxChunkBytes <= 65543 &&
              config.maxIngressBytes > 0 && config.maxIngressBytes <= 1114128 &&
              config.maxFrames > 0 && config.maxFrames <= 8192 &&
              config.maxEvents > 0 && config.maxEvents <= 4096 && config.maxWireBytes > 0 && config.maxTotalFrames > 0 &&
              config.wholeRequest
                .forall(_ > Duration.Zero) && driver.valid && driver.handshake > Duration.Zero
          )(new IllegalArgumentException("invalid session limits"))
          decoder <- F.fromEither(
            Mux.Decoder
              .create(Mux.Limits(65535, config.maxChunkBytes, config.maxFrames))
              .leftMap(new IllegalArgumentException(_))
          )
          now <- F.monotonic
          ref <- Ref.of[F, Snapshot[S]](
            Snapshot(
              decoder,
              Vector.empty,
              Bytes.empty,
              false,
              driver.initial,
              Vector.empty,
              0L,
              Some(now + driver.handshake),
              None,
              Vector.empty,
              wholeExpires = config.wholeRequest.map(now + _)
            )
          )
          gate <- Semaphore[F](1)
          stopped <- Deferred[F, String]
          changed <- Queue.bounded[F, Unit](1)
        yield new SingleProtocolConnection(
          transport,
          driver,
          role,
          config,
          ref,
          gate,
          stopped,
          changed
        )
      }(_.close)
      .flatMap(session => session.watch.background.as(session))
