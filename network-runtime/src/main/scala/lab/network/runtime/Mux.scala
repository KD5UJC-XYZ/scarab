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

  /** The next complete message. `decode` is a pure `decodePrefix` for the current protocol state;
    * more SDUs are awaited while it answers `NeedMore`, and bytes beyond the message are kept for
    * the next call. Fails with `IngressLimit` once `maxBytes` are buffered without a message.
    */
  def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A]): F[A]

/** Multiplexer over one [[Bearer]]: a reader fiber routes SDUs into bounded per-protocol queues (a
  * full queue stops socket reads) and a writer fiber drains one outbound queue.
  */
trait Mux[F[_]]:
  def channel(protocol: Int): F[MuxChannel[F]]

  /** Completes with the reason once the bearer has failed or been closed. */
  def closed: F[String]

  /** Close the connection locally: every pending and later operation raises `BearerClosed`. */
  def abort(reason: String): F[Unit]

object Mux:
  enum Role:
    case Initiator, Responder

  final case class Config(
      role: Role = Role.Initiator,
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
    val (sending, receiving) = config.role match
      case Role.Initiator => (WireMux.Direction.Initiator, WireMux.Direction.Responder)
      case Role.Responder => (WireMux.Direction.Responder, WireMux.Direction.Initiator)

    def failure[A](reason: String): F[A] = NetworkError.BearerClosed(reason).raise[F, A]
    def describe(t: Throwable): String = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)

    val setup = for
      done <- Resource.eval(Deferred[F, String])
      inbound <- Resource.eval(
        protocols.toList
          .traverse(p => Queue.bounded[F, Bytes](config.ingressQueueSdus).map(p -> _))
          .map(_.toMap)
      )
      outbound <- Resource.eval(Queue.bounded[F, (Int, Bytes)](config.egressQueueMessages))
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
          if sdu.direction != receiving then F.pure(Some("SDU with the wrong mode bit"))
          else
            inbound.get(sdu.protocol) match
              case None        => F.pure(Some(s"data on unexpected mini-protocol ${sdu.protocol}"))
              case Some(queue) => queue.offer(sdu.payload) *> route(rest)

      type Feed = Either[String, WireMux.Decoder]
      def step(state: Feed, chunk: Chunk[Byte]): F[(Feed, Unit)] = state match
        case Left(_) => F.pure((state, ()))
        case Right(current) =>
          current.feed(Bytes(chunk.toVector)) match
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
        outbound.take.flatMap { case (protocol, message) =>
          Clock[F].monotonic.flatMap { now =>
            val frames = WireMux
              .segmentProtocolMessage(
                message,
                now.toMicros & 0xffffffffL,
                sending,
                config.maxSduPayload,
                protocol,
                config.maxMessageBytes
              )
              .flatMap(_.traverse(WireMux.encode))
            frames match
              case Left(reason) => F.pure(s"mux write: $reason")
              case Right(encoded) =>
                bearer.write(Chunk.array(encoded.flatMap(_.value).toArray)) *> writeLoop
          }
        }
      val writer: F[String] = writeLoop.handleError(t => s"bearer failed: ${describe(t)}")

      for
        _ <- F.background(reader.flatMap(done.complete))
        _ <- F.background(writer.flatMap(done.complete))
        channels <- Resource.eval(
          inbound.toList
            .traverse { case (protocol, queue) =>
              for
                buffer <- Ref.of[F, Bytes](Bytes.empty)
                lock <- Mutex[F]
              yield protocol -> channel(protocol, queue, outbound, done, buffer, lock, config)
            }
            .map(_.toMap)
        )
      yield new Mux[F]:
        def channel(protocol: Int) =
          channels.get(protocol) match
            case Some(c) => F.pure(c)
            case None =>
              NetworkError
                .IllegalTransition(protocol, "mini-protocol is not registered on this connection")
                .raise[F, MuxChannel[F]]
        def closed = done.get
        def abort(reason: String) = done.complete(reason).void
    }

  private def channel[F[_]: Async](
      id: Int,
      queue: Queue[F, Bytes],
      outbound: Queue[F, (Int, Bytes)],
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
            F.race(closed.get, outbound.offer((id, message))).flatMap {
              case Left(reason) => bearerClosed(reason)
              case Right(_)     => F.unit
            }
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
                buffer.set(Bytes(held.value.drop(consumed))).as(value)
              case DecodeResult.Failed(reason) => NetworkError.Decode(id, reason).raise[F, A]
              case DecodeResult.NeedMore =>
                if held.size > maxBytes then NetworkError.IngressLimit(id, held.size).raise[F, A]
                else
                  nextPayload.flatMap(more => buffer.set(Bytes(held.value ++ more.value)) *> loop)
          }
        loop
      }
