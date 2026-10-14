// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.{Async, Clock, Deferred, Ref, Resource}
import cats.effect.std.{Mutex, Queue}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Chunk
import lab.cbor.Bytes
import lab.network.ChainSync.DecodeResult
import lab.network.Mux as WireMux

/** One mini-protocol's view of the multiplexed connection. Use from a single fiber at a time. */
trait MuxChannel[F[_]]:
  def protocol: Int

  /** Queue one complete protocol message; it is split into SDUs by the writer. */
  def send(message: Bytes): F[Unit]

  /** Completes once everything queued on the connection so far has been written to the bearer (or
    * the connection has failed). Used before closing, so that a final message is not lost.
    */
  def flush: F[Unit]

  /** The next complete message. `decode` is a pure `decodePrefix` for the current protocol state;
    * more SDUs are awaited while it answers `NeedMore`, and bytes beyond the message are kept for
    * the next call. Fails with `IngressLimit` once `maxBytes` are buffered without a message.
    */
  def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A]): F[A]

/** Multiplexer over one [[Bearer]]: a reader fiber routes SDUs into bounded per-protocol queues (a
  * full queue stops socket reads) and a writer fiber drains one outbound queue.
  */
trait Mux[F[_]]:
  /** The channel of `protocol` in the connection's own role ([[Mux.Config.role]]). */
  def channel(protocol: Int): F[MuxChannel[F]]

  /** The channel of `protocol` for one side of the mini-protocol: `Initiator` is the client side
    * (it sends with the initiator mode bit), `Responder` the server side. A connection carries both
    * for the same protocol number only when it was opened with [[Mux.Config.duplex]].
    */
  def channel(protocol: Int, role: Mux.Role): F[MuxChannel[F]]

  /** Completes with the reason once the bearer has failed or been closed. */
  def closed: F[String]

  /** Close the connection locally: every pending and later operation raises `BearerClosed`. */
  def abort(reason: String): F[Unit]

object Mux:
  enum Role:
    case Initiator, Responder

  /** `role` is the side of the connection (who opened it) and the default side of every
    * mini-protocol. With `duplex` both sides of every mini-protocol are available on the one
    * bearer: SDUs carry the mode bit of the sending side, so a client and a server instance of the
    * same protocol number never see each other's traffic.
    */
  final case class Config(
      role: Role = Role.Initiator,
      duplex: Boolean = false,
      maxSduPayload: Int = WireMux.WireMaxPayloadBytes,
      readChunkBytes: Int = 16384,
      ingressQueueSdus: Int = 16,
      egressQueueMessages: Int = 64,
      maxMessageBytes: Int = 2500000
  )

  def resource[F[_]: Async](bearer: Bearer[F], protocols: Set[Int], config: Config = Config())(using
      Raise[F, NetworkError]
  ): Resource[F, Mux[F]] =
    val F = Async[F]
    val roles: List[Role] =
      if config.duplex then List(Role.Initiator, Role.Responder) else List(config.role)
    def sending(role: Role): WireMux.Direction = role match
      case Role.Initiator => WireMux.Direction.Initiator
      case Role.Responder => WireMux.Direction.Responder
    // An SDU sent by the initiator side of a mini-protocol is for the responder side here.
    def receiverOf(direction: WireMux.Direction): Role = direction match
      case WireMux.Direction.Initiator => Role.Responder
      case WireMux.Direction.Responder => Role.Initiator

    def failure[A](reason: String): F[A] = NetworkError.BearerClosed(reason).raise[F, A]
    def describe(t: Throwable): String = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)

    val setup = for
      done <- Resource.eval(Deferred[F, String])
      inbound <- Resource.eval(
        (for p <- protocols.toList; r <- roles yield (p, r))
          .traverse(key => Queue.bounded[F, Bytes](config.ingressQueueSdus).map(key -> _))
          .map(_.toMap)
      )
      outbound <- Resource.eval(
        Queue.bounded[F, Either[Deferred[F, Unit], (Int, Role, Bytes)]](config.egressQueueMessages)
      )
      decoder <- Resource.eval(
        WireMux.Decoder
          .create(WireMux.Limits(maxPayloadBytes = config.maxSduPayload))
          .fold(r => failure[WireMux.Decoder](r), F.pure)
      )
    yield (done, inbound, outbound, decoder)

    setup.flatMap { case (done, inbound, outbound, decoder) =>
      // Route decoded SDUs in order; the first protocol violation ends the connection.
      def route(sdus: List[WireMux.Sdu]): F[Option[String]] = sdus match
        case Nil => F.pure(None)
        case sdu :: rest =>
          if !protocols.contains(sdu.protocol) then
            F.pure(Some(s"data on unexpected mini-protocol ${sdu.protocol}"))
          else
            inbound.get((sdu.protocol, receiverOf(sdu.direction))) match
              case None        => F.pure(Some("SDU with the wrong mode bit"))
              case Some(queue) => queue.offer(sdu.payload) *> route(rest)

      type Feed = Either[String, WireMux.Decoder]
      def step(state: Feed, chunk: Chunk[Byte]): F[(Feed, Unit)] = state match
        case Left(_) => F.pure((state, ()))
        case Right(current) =>
          current.feed(Bytes.wrap(chunk.toArray)) match
            case Left(reason) => F.pure((Left(reason), ()))
            case Right((next, sdus)) =>
              route(sdus.toList).map(failed => (failed.toLeft(next), ()))

      val reader: F[String] =
        bearer.reads
          .chunkLimit(config.readChunkBytes)
          .evalMapAccumulate[F, Feed, Unit](Right(decoder))(step)
          .takeThrough(_._1.isRight)
          .compile
          .last
          .map {
            case Some((Left(reason), _)) => reason
            case Some((Right(last), _))  => last.finish.fold(identity, _ => "closed by peer")
            case None                    => "closed by peer"
          }
          .handleError(t => s"bearer failed: ${describe(t)}")

      def writeLoop: F[String] =
        outbound.take.flatMap {
          case Left(flushed) => flushed.complete(()) *> writeLoop
          case Right((protocol, role, message)) =>
            Clock[F].monotonic.flatMap { now =>
              val frames = WireMux
                .segmentProtocolMessage(
                  message,
                  now.toMicros & 0xffffffffL,
                  sending(role),
                  config.maxSduPayload,
                  protocol,
                  config.maxMessageBytes
                )
                .flatMap(_.traverse(WireMux.encode))
              frames match
                case Left(reason) => F.pure(s"mux write: $reason")
                case Right(encoded) =>
                  bearer.write(Chunk.array(concat(encoded))) *> writeLoop
            }
        }
      val writer: F[String] = writeLoop.handleError(t => s"bearer failed: ${describe(t)}")

      for
        _ <- F.background(reader.flatMap(done.complete))
        _ <- F.background(writer.flatMap(done.complete))
        channels <- Resource.eval(
          inbound.toList
            .traverse { case (key @ (protocol, role), queue) =>
              for
                buffer <- Ref.of[F, Bytes](Bytes.empty)
                lock <- Mutex[F]
              yield key -> channel(protocol, role, queue, outbound, done, buffer, lock, config)
            }
            .map(_.toMap)
        )
      yield new Mux[F]:
        def channel(protocol: Int) = channel(protocol, config.role)
        def channel(protocol: Int, role: Role) =
          channels.get((protocol, role)) match
            case Some(c) => F.pure(c)
            case None =>
              NetworkError
                .IllegalTransition(
                  protocol,
                  s"mini-protocol is not registered as $role on this connection"
                )
                .raise[F, MuxChannel[F]]
        def closed = done.get
        def abort(reason: String) = done.complete(reason).void
    }

  /** The parts one after the other in one array. */
  private def concat(parts: Vector[Bytes]): Array[Byte] =
    val out = new Array[Byte](parts.map(_.size).sum)
    var at = 0
    parts.foreach { part =>
      part.copyTo(out, at)
      at += part.size
    }
    out

  private def channel[F[_]: Async](
      id: Int,
      role: Role,
      queue: Queue[F, Bytes],
      outbound: Queue[F, Either[Deferred[F, Unit], (Int, Role, Bytes)]],
      closed: Deferred[F, String],
      buffer: Ref[F, Bytes],
      lock: Mutex[F],
      config: Config
  )(using Raise[F, NetworkError]): MuxChannel[F] = new MuxChannel[F]:
    private val F = Async[F]
    def protocol = id

    private def bearerClosed[A](reason: String): F[A] =
      NetworkError.BearerClosed(reason).raise[F, A]

    def send(message: Bytes): F[Unit] =
      if message.size == 0 || message.size > config.maxMessageBytes then
        NetworkError.IllegalTransition(id, s"message of ${message.size} bytes cannot be sent").raise
      else
        closed.tryGet.flatMap {
          case Some(reason) => bearerClosed(reason)
          case None =>
            F.race(closed.get, outbound.offer(Right((id, role, message)))).flatMap {
              case Left(reason) => bearerClosed(reason)
              case Right(_)     => F.unit
            }
        }

    def flush: F[Unit] =
      Deferred[F, Unit].flatMap { flushed =>
        F.race(closed.get, outbound.offer(Left(flushed)) *> flushed.get).void
      }

    // Queued SDUs are delivered before a close is reported; the reader closes only after enqueueing.
    private def nextPayload: F[Bytes] =
      queue.tryTake.flatMap {
        case Some(payload) => F.pure(payload)
        case None =>
          closed.tryGet.flatMap {
            case Some(reason) => bearerClosed(reason)
            case None => F.race(closed.get, queue.take).flatMap(_.fold(_ => nextPayload, F.pure))
          }
      }

    def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A]): F[A] =
      lock.lock.surround {
        def loop: F[A] =
          buffer.get.flatMap { held =>
            decode(held) match
              case DecodeResult.Decoded(value, consumed) =>
                buffer.set(held.drop(consumed)).as(value)
              case DecodeResult.Failed(reason) => NetworkError.Decode(id, reason).raise[F, A]
              case DecodeResult.NeedMore =>
                if held.size > maxBytes then NetworkError.IngressLimit(id, held.size).raise[F, A]
                else nextPayload.flatMap(more => buffer.set(held ++ more) *> loop)
          }
        loop
      }
