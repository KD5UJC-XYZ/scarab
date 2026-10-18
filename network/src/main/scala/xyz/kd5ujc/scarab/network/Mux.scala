// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Clock, Deferred, Ref, Resource}
import cats.effect.std.{Mutex, Queue}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.{Chunk, Pull}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult
import xyz.kd5ujc.scarab.protocol.Mux as WireMux
import scala.concurrent.duration.*

/** One mini-protocol's view of the multiplexed connection. Use from a single fiber at a time.
  *
  * A channel holds no error capability: `send` and `recv` raise through the `Raise` of the fiber
  * that calls them. A channel is used by the fibers of a connection (the client of a protocol, its
  * keep-alive, the pipeline), each in an error scope of its own; a `Raise` fixed when the channel
  * is made would belong to whichever fiber made it, and a failure raised through it in another
  * fiber would end that fiber with an exception nobody handles (issue 130).
  */
trait MuxChannel[F[_]]:
  def protocol: Int

  /** Queue one complete protocol message; it is split into SDUs by the writer. */
  def send(message: Bytes)(using Raise[F, NetworkError]): F[Unit]

  /** Completes once everything queued on the connection so far has been written to the bearer (or
    * the connection has failed). Used before closing, so that a final message is not lost.
    */
  def flush: F[Unit]

  /** The next complete message. `decode` is a pure `decodePrefix` for the current protocol state;
    * more SDUs are awaited while it answers `NeedMore`, and bytes beyond the message are kept for
    * the next call. Fails with `IngressLimit` if the first message exceeds `maxBytes`, including
    * when the decoder completes in one call.
    */
  def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A])(using
      Raise[F, NetworkError]
  ): F[A]

/** Multiplexer over one [[Bearer]]: a reader fiber routes SDUs into the ingress queue of their
  * mini-protocol and a writer fiber drains one outbound queue.
  *
  * An ingress queue is counted in bytes, not in SDUs, with the limit of the reference for its
  * protocol ([[IngressLimits]]); the reader never waits for a slow protocol, and a peer that has
  * more unread bytes queued on a protocol than its limit allows ends the connection with
  * [[NetworkError.IngressLimit]] (the reference's `IngressQueueOverRun`). What slows a fast peer
  * down is the protocol, not the queue: a peer may only send what the protocol's state gives it
  * agency for, and pipelines no more than the window, so a well behaved peer stays within the limit
  * however slowly we read. The writer's queue is bounded in messages and `send` waits for room in
  * it.
  *
  * Both fibers end by completing [[closed]] with the typed reason, which the channels raise.
  */
trait Mux[F[_]]:
  /** The channel of `protocol` in the connection's own role ([[Mux.Config.role]]). */
  def channel(protocol: Int)(using Raise[F, NetworkError]): F[MuxChannel[F]]

  /** The channel of `protocol` for one side of the mini-protocol: `Initiator` is the client side
    * (it sends with the initiator mode bit), `Responder` the server side. A connection carries both
    * for the same protocol number only when it was opened with [[Mux.Config.duplex]].
    */
  def channel(protocol: Int, role: Mux.Role)(using Raise[F, NetworkError]): F[MuxChannel[F]]

  /** Completes with the reason once the bearer has failed or been closed, or the peer broke a limit
    * of the multiplexer. Every pending and later operation of a channel raises it.
    */
  def closed: F[NetworkError]

  /** Close the connection locally for `reason`: every pending and later operation of a channel
    * raises it. The first reason wins.
    */
  def abort(reason: NetworkError): F[Unit]

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
      /** The most unread bytes a mini-protocol may have queued, by protocol number (the
        * node-to-node limits of the reference by default, [[IngressLimits]]).
        */
      ingressLimit: Int => Long = IngressLimits.nodeToNode(),
      egressQueueMessages: Int = 64,
      maxMessageBytes: Int = 2500000,
      /** Reference ConnectionHandler.hs:94-102; idle between SDUs is indefinite. */
      sduTimeout: FiniteDuration = 30.seconds,
      handshakeSduTimeout: FiniteDuration = 10.seconds
  )

  /** The unread bytes of one mini-protocol and side: the payloads the reader has queued and the
    * channel has not taken, and how many bytes they are.
    */
  private final class Ingress[F[_]: Async](
      queue: Queue[F, Bytes],
      queued: Ref[F, Long],
      val limit: Long
  ):
    /** Queue a payload; `Some(bytes)` instead when it would take the queue past its limit, with the
      * size it would have had.
      */
    def offer(payload: Bytes): F[Option[Long]] =
      queued
        .modify { held =>
          val grown = held + payload.size
          if grown <= limit then (grown, None) else (held, Some(grown))
        }
        .flatTap(over => queue.offer(payload).whenA(over.isEmpty))

    private def taken(payload: Bytes): F[Bytes] = queued.update(_ - payload.size).as(payload)

    def tryTake: F[Option[Bytes]] = queue.tryTake.flatMap(_.traverse(taken))
    def take: F[Bytes] = queue.take.flatMap(taken)

    /** The bytes queued now (tests). */
    def held: F[Long] = queued.get

  /** Open the multiplexer. `Raise` is the scope of whoever opens it, used only while opening: the
    * resource's own fibers report through [[Mux.closed]], and the channels take the `Raise` of
    * their caller.
    */
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

    val setup = for
      done <- Resource.eval(Deferred[F, NetworkError])
      inbound <- Resource.eval(
        (for p <- protocols.toList; r <- roles yield (p, r))
          .traverse { key =>
            (Queue.unbounded[F, Bytes], Ref.of[F, Long](0L)).mapN((queue, queued) =>
              key -> new Ingress[F](queue, queued, config.ingressLimit(key._1))
            )
          }
          .map(_.toMap)
      )
      outbound <- Resource.eval(
        Queue.bounded[F, Either[Deferred[F, Unit], (Int, Role, Bytes)]](config.egressQueueMessages)
      )
      decoder <- Resource.eval(
        WireMux.Decoder
          .create(WireMux.Limits(maxPayloadBytes = config.maxSduPayload))
          .fold(e => NetworkError.MuxSetup(e).raise[F, WireMux.Decoder], F.pure)
      )
    yield (done, inbound, outbound, decoder)

    setup.flatMap { case (done, inbound, outbound, decoder) =>
      // Route decoded SDUs in order; the first protocol violation ends the connection.
      def route(sdus: List[WireMux.Sdu]): F[Option[NetworkError]] = sdus match
        case Nil => F.pure(None)
        case sdu :: rest =>
          if !protocols.contains(sdu.protocol) then
            F.pure(
              Some(
                NetworkError.IllegalTransition(
                  sdu.protocol,
                  NetworkError.RuntimeViolation.UnexpectedProtocol(sdu.protocol)
                )
              )
            )
          else
            inbound.get((sdu.protocol, receiverOf(sdu.direction))) match
              case None =>
                F.pure(
                  Some(NetworkError.IllegalTransition(sdu.protocol, NetworkError.RuntimeViolation.WrongModeBit))
                )
              case Some(ingress) =>
                ingress.offer(sdu.payload).flatMap {
                  case Some(bytes) =>
                    F.pure(Some(NetworkError.IngressLimit(sdu.protocol, bytes)))
                  case None => route(rest)
                }

      val closedByPeer = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
      // Socket.hs:72-102: wait indefinitely for the first header bytes, then one absolute SDU
      // deadline. Pull.timed owns its source and timer; continuing a partial SDU never resets it.
      // Pull's suspended bind, rather than the JVM stack, owns this externally sized loop.
      def read(
          current: WireMux.Decoder,
          input: Pull.Timed[F, Byte]
      ): Pull[F, NetworkError, Unit] =
        input.uncons.flatMap {
          case None =>
            Pull.output1(current.finish.fold(NetworkError.Framing(_), _ => closedByPeer))
          case Some((Left(_), _)) =>
            Pull.output1(NetworkError.SduTimeout(NetworkError.SduOperation.Read))
          case Some((Right(chunk), rest)) =>
            current.feed(Bytes.wrap(chunk.toArray)) match
              case Left(reason) => Pull.output1(NetworkError.Framing(reason))
              case Right((next, sdus)) =>
                val timer =
                  if next.pendingBytes == 0 then rest.timeout(Duration.Zero)
                  else if current.pendingBytes == 0 || sdus.nonEmpty then
                    rest.timeout(config.sduTimeout)
                  else Pull.done
                timer >> Pull.eval(route(sdus.toList)).flatMap {
                  case Some(reason) => Pull.output1(reason)
                  case None         => read(next, rest)
                }
        }
      val reader: F[NetworkError] =
        bearer.reads
          .chunkLimit(config.readChunkBytes)
          .unchunks
          .pull
          .timed(read(decoder, _))
          .stream
          .compile
          .last
          .map(_.getOrElse(closedByPeer))
          // a transport failure is the library's exception: it becomes a typed reason here
          .handleError(t => NetworkError.fromThrowable("bearer failed", t))

      def writeLoop: F[NetworkError] =
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
                case Left(reason)   => F.pure(NetworkError.MuxWrite(reason))
                case Right(encoded) =>
                  // Socket.hs:190-230 gives a batch one SDU budget per frame, not per byte or
                  // per progress notification. Handshake flushes are bounded on the writer too.
                  val perSdu =
                    if protocol == Protocols.Handshake then config.handshakeSduTimeout
                    else config.sduTimeout
                  F.timeoutTo(
                    bearer
                      .write(Chunk.array(concat(encoded)))
                      .as(
                        Right(()): Either[NetworkError, Unit]
                      ),
                    perSdu * encoded.size.toLong,
                    F.pure(Left(NetworkError.SduTimeout(NetworkError.SduOperation.Write)))
                  ).flatMap(_.fold(F.pure, _ => writeLoop))
            }
        }
      val writer: F[NetworkError] =
        writeLoop.handleError(t => NetworkError.fromThrowable("bearer failed", t))

      for
        _ <- F.background(reader.flatMap(done.complete))
        _ <- F.background(writer.flatMap(done.complete))
        channels <- Resource.eval(
          inbound.toList
            .traverse { case (key @ (protocol, role), ingress) =>
              for
                buffer <- Ref.of[F, Bytes](Bytes.empty)
                lock <- Mutex[F]
              yield key -> channel(protocol, role, ingress, outbound, done, buffer, lock, config)
            }
            .map(_.toMap)
        )
      yield new Mux[F]:
        def channel(protocol: Int)(using Raise[F, NetworkError]) = channel(protocol, config.role)
        def channel(protocol: Int, role: Role)(using Raise[F, NetworkError]) =
          channels.get((protocol, role)) match
            case Some(c) => F.pure(c)
            case None =>
              NetworkError
                .IllegalTransition(
                  protocol,
                  NetworkError.RuntimeViolation.NotRegistered(role)
                )
                .raise[F, MuxChannel[F]]
        def closed = done.get
        def abort(reason: NetworkError) = done.complete(reason).void
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
      ingress: Ingress[F],
      outbound: Queue[F, Either[Deferred[F, Unit], (Int, Role, Bytes)]],
      closed: Deferred[F, NetworkError],
      buffer: Ref[F, Bytes],
      lock: Mutex[F],
      config: Config
  ): MuxChannel[F] = new MuxChannel[F]:
    private val F = Async[F]
    def protocol = id

    def send(message: Bytes)(using Raise[F, NetworkError]): F[Unit] =
      if message.size == 0 || message.size > config.maxMessageBytes then
        NetworkError.IllegalTransition(id, NetworkError.RuntimeViolation.OutgoingMessageSize(message.size, config.maxMessageBytes)).raise
      else
        closed.tryGet.flatMap {
          case Some(reason) => reason.raise[F, Unit]
          case None =>
            F.race(closed.get, outbound.offer(Right((id, role, message)))).flatMap {
              case Left(reason) => reason.raise[F, Unit]
              case Right(_)     => F.unit
            }
        }

    def flush: F[Unit] =
      Deferred[F, Unit].flatMap { flushed =>
        F.race(closed.get, outbound.offer(Left(flushed)) *> flushed.get).void
      }

    // Queued SDUs are delivered before a close is reported; the reader closes only after enqueueing.
    private def nextPayload(using Raise[F, NetworkError]): F[Bytes] =
      ingress.tryTake.flatMap {
        case Some(payload) => F.pure(payload)
        case None =>
          closed.tryGet.flatMap {
            case Some(reason) => reason.raise[F, Bytes]
            case None => F.race(closed.get, ingress.take).flatMap(_.fold(_ => nextPayload, F.pure))
          }
      }

    def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A])(using
        Raise[F, NetworkError]
    ): F[A] =
      lock.lock.surround {
        def loop: F[A] =
          buffer.get.flatMap { held =>
            decode(held) match
              case DecodeResult.Decoded(value, consumed) =>
                if consumed > maxBytes then NetworkError.IngressLimit(id, consumed).raise[F, A]
                else buffer.set(held.drop(consumed)).as(value)
              case DecodeResult.Failed(reason) => NetworkError.Decode(id, reason).raise[F, A]
              case DecodeResult.NeedMore =>
                if held.size > maxBytes then NetworkError.IngressLimit(id, held.size).raise[F, A]
                else nextPayload.flatMap(more => buffer.set(held ++ more) *> loop)
          }
        loop
      }
