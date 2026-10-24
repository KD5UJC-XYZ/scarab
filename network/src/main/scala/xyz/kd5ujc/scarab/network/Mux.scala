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
  * mini-protocol and a writer fiber rotates complete SDUs between admitted outbound channels.
  *
  * An ingress queue is counted in bytes, not in SDUs, with the limit of the reference for its
  * protocol ([[IngressLimits]]); the reader never waits for a slow protocol, and a peer that has
  * more unread bytes queued on a protocol than its limit allows ends the connection with
  * [[NetworkError.IngressLimit]] (the reference's `IngressQueueOverRun`). What slows a fast peer
  * down is the protocol, not the queue: a peer may only send what the protocol's state gives it
  * agency for, and pipelines no more than the window, so a well behaved peer stays within the limit
  * however slowly we read. The writer's queue is bounded in messages and `send` waits for room in
  * it, including staged work awaiting a later turn.
  *
  * Both fibers end by completing [[closed]] with the typed reason, which the channels raise.
  */
trait Mux[F[_]]:
  /** Native StartOnDemandAny: wait for the first nonempty remote-initiated mini-protocol SDU.
    * Handshake traffic and responses to our own initiators do not activate responders.
    */
  def awaitResponderActivity(using Raise[F, NetworkError]): F[Unit]

  /** Finish the handshake mux and install exactly the negotiated application and directions. */
  def installApplication(initiators: Set[Int], responders: Set[Int])(using
      Raise[F, NetworkError]
  ): F[Unit]

  /** Captured transport identity; in-memory and Unix bearers have no TCP identity. */
  def connectionId
      : Option[Either[xyz.kd5ujc.scarab.protocol.ConnectionIdentityError, ConnectionId]] = None

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
  type Role = NetworkError.ConnectionRole
  val Role = NetworkError.ConnectionRole

  /** `role` is the side of the connection (who opened it) and the default side of every
    * mini-protocol. With `duplex` both sides of every mini-protocol are available on the one
    * bearer: SDUs carry the mode bit of the sending side, so a client and a server instance of the
    * same protocol number never see each other's traffic.
    *
    * @param maxSduPayload
    *   The largest SDU payload accepted from the peer; what is sent is split at the smaller of this
    *   and [[Config.ReferenceSduPayload]] ([[Config.egressSduPayload]]).
    * @param ingressLimit
    *   The most unread bytes a mini-protocol may have queued, by protocol number (the node-to-node
    *   limits of the reference by default, [[IngressLimits]]).
    * @param sduTimeout
    *   Reference ConnectionHandler.hs:94-102; idle between SDUs is indefinite.
    */
  final case class Config(
      role: Role = Role.Initiator,
      duplex: Boolean = false,
      maxSduPayload: Int = WireMux.WireMaxPayloadBytes,
      readChunkBytes: Int = 16384,
      ingressLimit: Int => Long = IngressLimits.nodeToNode(),
      egressQueueMessages: Int = 64,
      maxMessageBytes: Int = 2500000,
      sduTimeout: FiniteDuration = 30.seconds,
      handshakeSduTimeout: FiniteDuration = 10.seconds,
      receiveBufferLimit: Long = ReceiveBuffer.MaxArray
  ):
    /** The payload size SDUs are sent in. */
    def egressSduPayload: Int = maxSduPayload.min(Config.ReferenceSduPayload)

  object Config:
    /** The payload the reference splits what it sends into, on node-to-node and node-to-client
      * sockets alike (`makeSocketBearer'` and `makeLocalBearer` give `socketAsBearer` an `SDUSize`
      * of 12,288: `Network.Mux.Bearer`, `Ouroboros.Network.Snocket`). What it reads is bounded only
      * by the 16-bit length of the header, which is [[Config.maxSduPayload]] here.
      */
    val ReferenceSduPayload: Int = 12288

    /** The responder of a node-to-client connection. The reference gives every node-to-client
      * mini-protocol `maximumMiniProtocolLimits` and runs its codecs without byte limits, so a
      * message is bounded only by what a byte array can hold: a LocalStateQuery answer may be tens
      * of megabytes (`GetCBOR DebugEpochState` on preprod).
      */
    val nodeToClient: Config = Config(
      role = Role.Responder,
      ingressLimit = IngressLimits.nodeToClient,
      maxMessageBytes = xyz.kd5ujc.scarab.protocol.LocalStateQuery.MaxResultBytes
    )

  /** The unread bytes of one mini-protocol and side: the payloads the reader has queued and the
    * channel has not taken, and how many bytes they are.
    */
  private final class Ingress[F[_]: Async](
      queue: Queue[F, (BigInt, Bytes)],
      queued: Ref[F, Long],
      received: Ref[F, BigInt],
      consumed: Ref[F, BigInt],
      read: F[Unit],
      val limit: Long
  ):
    /** Queue a payload; `Some(bytes)` instead when it would take the queue past its limit, with the
      * size it would have had.
      */
    def offer(payload: Bytes): F[Option[Long]] =
      received.updateAndGet(_ + 1).flatMap { sequence =>
        // Native ingress counts bytes. Empty SDUs do not create unbounded queue items.
        if payload.size == 0 then Async[F].pure(None)
        else
          queued
            .modify { held =>
              val grown = held + payload.size
              if grown <= limit then (grown, None) else (held, Some(grown))
            }
            .flatTap(over => queue.offer(sequence -> payload).whenA(over.isEmpty))
      }

    private def taken(value: (BigInt, Bytes)): F[Bytes] =
      queued.update(_ - value._2.size) *> consumed.set(value._1) *> read.as(value._2)

    def tryTake: F[Option[Bytes]] = queue.tryTake.flatMap(_.traverse(taken))
    def take: F[Bytes] = queue.take.flatMap(taken)
    def pendingFrames: F[Boolean] = (received.get, consumed.get).mapN(_ > _)

    /** The bytes queued now (tests). */
    def held: F[Long] = queued.get

  private final class HandshakeInput[F[_]: Async](
      pending: Ref[F, Option[(Deferred[F, Unit], Boolean)]],
      val finished: Deferred[F, Unit]
  ):
    private val F = Async[F]
    def consumed: F[Unit] = pending.update(_.map((ack, _) => ack -> true))
    def continue: F[Unit] = F.uncancelable(_ =>
      pending
        .modify {
          case Some((ack, true)) => None -> Some(ack)
          case current           => current -> None
        }
        .flatMap(_.traverse_(_.complete(()).void))
    )
    def complete: F[Unit] = F.uncancelable(_ => finished.complete(()).void *> continue)
    def feed(payload: Bytes)(enqueue: F[Option[Long]]): F[Option[NetworkError]] =
      if payload.size == 0 then F.pure(None)
      else
        Deferred[F, Unit].flatMap { ack =>
          F.uncancelable { poll =>
            pending.set(Some(ack -> false)) *> enqueue.flatMap {
              case Some(bytes) =>
                F.pure(Some(NetworkError.IngressLimit(Protocols.Handshake, bytes)))
              case None => poll(ack.get).as(None)
            }
          }
        }

  private type Key = (Int, Role)
  private final case class DispatchState(
      application: Option[Set[Key]],
      early: Vector[(Key, Boolean)]
  )
  private final class Dispatch[F[_]: Async](
      state: Ref[F, DispatchState],
      lock: Mutex[F],
      val installed: Deferred[F, Unit],
      val active: Deferred[F, Unit]
  ):
    private val F = Async[F]
    private def denied(allowed: Set[Key], key: Key): Option[NetworkError] =
      Option.unless(allowed(key)) {
        NetworkError.IllegalTransition(
          key._1,
          if allowed.exists(_._1 == key._1) then NetworkError.RuntimeViolation.WrongModeBit
          else NetworkError.RuntimeViolation.UnexpectedProtocol(key._1)
        )
      }
    def check(key: Key): F[Option[NetworkError]] =
      state.get.map(_.application.flatMap(denied(_, key)))
    def configured: F[Boolean] = state.get.map(_.application.isDefined)
    def route(key: Key, payload: Bytes)(enqueue: F[Option[Long]]): F[Option[NetworkError]] =
      lock.lock.surround(
        F.uncancelable(_ =>
          state.get.flatMap { current =>
            current.application.flatMap(denied(_, key)) match
              case Some(error) => F.pure(Some(error))
              case None =>
                enqueue.flatMap {
                  case Some(bytes) => F.pure(Some(NetworkError.IngressLimit(key._1, bytes)))
                  case None =>
                    val responder = key._2 == Role.Responder && key._1 != Protocols.Handshake
                    current.application match
                      case Some(_) =>
                        active
                          .complete(())
                          .void
                          .whenA(responder && payload.size > 0)
                          .as(None)
                      case None if key._1 != Protocols.Handshake =>
                        val seen = current.early.exists(_._1 == key)
                        val updated =
                          if seen then
                            current.early.map { case (k, nonempty) =>
                              k -> (nonempty || (k == key && payload.size > 0))
                            }
                          else current.early :+ (key -> (payload.size > 0))
                        state.set(current.copy(early = updated)).as(None)
                      case None => F.pure(None)
                }
          }
        )
      )
    def install(allowed: Set[Key], handshakePending: F[Boolean]): F[Either[NetworkError, Unit]] =
      lock.lock.surround(
        F.uncancelable(_ =>
          state.get.flatMap { current =>
            current.application match
              case Some(existing) if existing == allowed => F.pure(Right(()))
              case Some(_) =>
                F.pure(
                  Left(
                    NetworkError.IllegalTransition(
                      Protocols.Handshake,
                      NetworkError.RuntimeViolation.ApplicationAlreadyInstalled
                    )
                  )
                )
              case None =>
                handshakePending.flatMap { pending =>
                  val rejected = Option
                    .when(pending)(
                      NetworkError.IllegalTransition(
                        Protocols.Handshake,
                        NetworkError.RuntimeViolation.UnexpectedProtocol(Protocols.Handshake)
                      )
                    )
                    .orElse(
                      current.early.iterator.flatMap((key, _) => denied(allowed, key)).nextOption()
                    )
                  rejected match
                    case Some(error) => F.pure(Left(error))
                    case None =>
                      val awaken = current.early.exists { case ((protocol, role), nonempty) =>
                        role == Role.Responder && protocol != Protocols.Handshake && nonempty
                      }
                      state.set(DispatchState(Some(allowed), Vector.empty)) *>
                        installed
                          .complete(())
                          .void *> active.complete(()).void.whenA(awaken).as(Right(()))
                }
          }
        )
      )

  /** Open the multiplexer. `Raise` is the scope of whoever opens it, used only while opening: the
    * resource's own fibers report through [[Mux.closed]], and the channels take the `Raise` of
    * their caller.
    */
  def resource[F[_]: Async](bearer: Bearer[F], protocols: Set[Int], config: Config = Config())(using
      Raise[F, NetworkError]
  ): Resource[F, Mux[F]] = open(bearer, protocols, config, handshake = false)

  /** Native ConnectionHandler.hs:335–381: raw handshake SDUs precede application dispatch. */
  def handshakeResource[F[_]: Async](
      bearer: Bearer[F],
      protocols: Set[Int],
      config: Config
  )(using Raise[F, NetworkError]): Resource[F, Mux[F]] =
    if protocols(Protocols.Handshake) then open(bearer, protocols, config, handshake = true)
    else
      Resource.eval(
        NetworkError
          .IllegalTransition(
            Protocols.Handshake,
            NetworkError.RuntimeViolation.UnexpectedProtocol(Protocols.Handshake)
          )
          .raise[F, Mux[F]]
      )

  private def open[F[_]: Async](
      bearer: Bearer[F],
      protocols: Set[Int],
      config: Config,
      handshake: Boolean
  )(using Raise[F, NetworkError]): Resource[F, Mux[F]] =
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
      connectionIdentity <- Resource.eval(bearer.connectionId.sequence)
      done <- Resource.eval(Deferred[F, NetworkError])
      handshakeInput <- Resource.eval(
        Option
          .when(handshake)(())
          .traverse(_ =>
            (Ref.of[F, Option[(Deferred[F, Unit], Boolean)]](None), Deferred[F, Unit])
              .mapN(new HandshakeInput[F](_, _))
          )
      )
      dispatch <- Resource.eval(
        (
          Ref.of[F, DispatchState](DispatchState(None, Vector.empty)),
          Mutex[F],
          Deferred[F, Unit],
          Deferred[F, Unit]
        ).mapN(new Dispatch[F](_, _, _, _))
      )
      inbound <- Resource.eval(
        (for p <- protocols.toList; r <- roles yield (p, r))
          .traverse { key =>
            (
              Queue.unbounded[F, (BigInt, Bytes)],
              Ref.of[F, Long](0L),
              Ref.of[F, BigInt](0),
              Ref.of[F, BigInt](0)
            ).mapN((queue, queued, received, consumed) =>
              val rawHandshake = key == (Protocols.Handshake -> config.role)
              key -> new Ingress[F](
                queue,
                queued,
                received,
                consumed,
                handshakeInput.filter(_ => rawHandshake).traverse_(_.consumed),
                if handshake && rawHandshake then WireMux.WireMaxPayloadBytes
                else config.ingressLimit(key._1)
              )
            )
          }
          .map(_.toMap)
      )
      outbound <- Resource.eval(
        MuxEgress.create[F](config.egressQueueMessages, config.egressSduPayload)
      )
      _ <- Resource.eval(
        WireMux.Decoder
          .create(WireMux.Limits(maxPayloadBytes = config.maxSduPayload))
          .fold(e => NetworkError.MuxSetup(e).raise[F, WireMux.Decoder], F.pure)
      )
    yield (done, inbound, outbound, connectionIdentity, dispatch, handshakeInput)

    setup.flatMap { case (done, inbound, outbound, transportId, dispatch, handshakeInput) =>
      def routeApplication(sdu: WireMux.Sdu): F[Option[NetworkError]] =
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
                Some(
                  NetworkError.IllegalTransition(
                    sdu.protocol,
                    NetworkError.RuntimeViolation.WrongModeBit
                  )
                )
              )
            case Some(ingress) =>
              dispatch.route((sdu.protocol, receiverOf(sdu.direction)), sdu.payload)(
                ingress.offer(sdu.payload)
              )

      def route(sdu: WireMux.Sdu): F[Option[NetworkError]] = handshakeInput match
        case None => routeApplication(sdu)
        case Some(input) =>
          input.finished.tryGet.flatMap {
            case None =>
              // Native network-mux Types.hs:305–308 bearerAsChannel receives msBlob only;
              // protocol and direction checks begin with the negotiated application.
              input.feed(sdu.payload)(
                inbound((Protocols.Handshake, config.role)).offer(sdu.payload)
              )
            case Some(_) => dispatch.installed.get *> routeApplication(sdu)
          }

      val closedByPeer = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
      // Socket.hs:74–110 reads exactly one SDU. Decoder acknowledgement may pause the
      // handshake; no following SDU's deadline starts until the next logical read begins.
      def readHeader(
          held: Bytes,
          available: Bytes,
          input: Pull.Timed[F, Byte]
      ): Pull[F, NetworkError, Unit] =
        val installed =
          if held.size != 0 then F.unit
          else
            handshakeInput.traverse_(input =>
              input.finished.tryGet.flatMap(_.fold(F.unit)(_ => dispatch.installed.get))
            )
        Pull.eval(installed) >> readHeaderReady(held, available, input)

      def readHeaderReady(
          held: Bytes,
          available: Bytes,
          input: Pull.Timed[F, Byte]
      ): Pull[F, NetworkError, Unit] =
        if available.nonEmpty then
          val count = (WireMux.HeaderBytes - held.size).min(available.size)
          val header = held ++ available.take(count)
          val rest = available.drop(count)
          val deadline =
            if held.size != 0 then Pull.done
            else
              Pull
                .eval(handshakeInput.traverse(_.finished.tryGet).map { status =>
                  if status.exists(_.isEmpty) then config.handshakeSduTimeout else config.sduTimeout
                })
                .flatMap(input.timeout)
          deadline >> (if header.size < WireMux.HeaderBytes then readHeader(header, rest, input)
                       else
                         WireMux.decodeHeader(header, config.maxSduPayload) match
                           case Left(error) => Pull.output1(NetworkError.Framing(error))
                           case Right(value) =>
                             readPayload(value, Nil, value.payloadLength, rest, input))
        else
          input.uncons.flatMap {
            case None =>
              Pull.output1(
                if held.size == 0 then closedByPeer
                else NetworkError.Framing(WireMux.Error.TruncatedHeader(held.size))
              )
            case Some((Left(_), _)) =>
              Pull.output1(NetworkError.SduTimeout(NetworkError.SduOperation.Read))
            case Some((Right(chunk), rest)) => readHeader(held, Bytes.wrap(chunk.toArray), rest)
          }

      def readPayload(
          header: WireMux.Header,
          parts: List[Bytes],
          remaining: Int,
          available: Bytes,
          input: Pull.Timed[F, Byte]
      ): Pull[F, NetworkError, Unit] =
        if remaining == 0 then
          val payload = Bytes(parts.reverseIterator.flatMap(_.value).toVector)
          input.timeout(Duration.Zero) >> Pull
            .eval(route(WireMux.Sdu(header.timestamp, header.protocol, header.direction, payload)))
            .flatMap {
              case Some(error) => Pull.output1(error)
              case None        => readHeader(Bytes.empty, available, input)
            }
        else if available.nonEmpty then
          val count = remaining.min(available.size)
          readPayload(
            header,
            available.take(count) :: parts,
            remaining - count,
            available.drop(count),
            input
          )
        else
          input.uncons.flatMap {
            case None =>
              Pull.output1(
                NetworkError.Framing(
                  WireMux.Error
                    .TruncatedPayload(header.payloadLength, header.payloadLength - remaining)
                )
              )
            case Some((Left(_), _)) =>
              Pull.output1(NetworkError.SduTimeout(NetworkError.SduOperation.Read))
            case Some((Right(chunk), rest)) =>
              readPayload(header, parts, remaining, Bytes.wrap(chunk.toArray), rest)
          }

      val reader: F[NetworkError] =
        bearer.reads
          .chunkLimit(config.readChunkBytes)
          .unchunks
          .pull
          .timed(readHeader(Bytes.empty, Bytes.empty, _))
          .stream
          .compile
          .last
          .map(_.getOrElse(closedByPeer))
          .handleError(t => NetworkError.fromThrowable("bearer failed", t))

      val writer: F[NetworkError] =
        outbound
          .run { (lane, payload) =>
            dispatch.check((lane.protocol, lane.role)).flatMap {
              case Some(error) => F.pure(Left(error))
              case None =>
                Clock[F].monotonic.flatMap { now =>
                  val frames = WireMux
                    .segmentProtocolMessage(
                      payload,
                      now.toMicros & 0xffffffffL,
                      sending(lane.role),
                      config.egressSduPayload,
                      lane.protocol,
                      config.maxMessageBytes
                    )
                    .flatMap(_.traverse(WireMux.encode))
                  frames match
                    case Left(reason)   => F.pure(Left(NetworkError.MuxWrite(reason)))
                    case Right(encoded) =>
                      // Egress.hs:189–213 rotates after one SDU. Socket.hs:190–230 gives each
                      // actual write one budget per SDU: a stalled first SDU gets no later SDU's time.
                      val perSdu =
                        if lane.protocol == Protocols.Handshake then config.handshakeSduTimeout
                        else config.sduTimeout
                      F.timeoutTo(
                        bearer
                          .write(Chunk.concat(encoded.map(b => Chunk.array(b.toArray))))
                          .as(Right(()): Either[NetworkError, Unit]),
                        perSdu * encoded.size.toLong,
                        F.pure(Left(NetworkError.SduTimeout(NetworkError.SduOperation.Write)))
                      )
                }
            }
          }
          .handleError(t => NetworkError.fromThrowable("bearer failed", t))

      for
        _ <- F.background(reader.flatMap(done.complete))
        _ <- F.background(writer.flatMap(done.complete))
        channels <- Resource.eval(
          inbound.toList
            .traverse { case (key @ (protocol, role), ingress) =>
              for
                buffer <- Async[F].delay(new ReceiveBuffer(config.receiveBufferLimit))
                lock <- Mutex[F]
              yield key -> (
                channel(
                  protocol,
                  role,
                  ingress,
                  outbound,
                  done,
                  buffer,
                  lock,
                  config,
                  dispatch,
                  handshakeInput.filter(_ => (protocol, role) == (Protocols.Handshake, config.role))
                ),
                buffer
              )
            }
            .map(_.toMap)
        )
      yield new Mux[F]:
        override val connectionId = transportId
        def awaitResponderActivity(using Raise[F, NetworkError]): F[Unit] =
          F.race(done.get, dispatch.active.get).flatMap(_.fold(_.raise[F, Unit], F.pure))
        def installApplication(initiators: Set[Int], responders: Set[Int])(using
            Raise[F, NetworkError]
        ): F[Unit] =
          val allowed = initiators.map(_ -> Role.Initiator) ++ responders.map(_ -> Role.Responder)
          val invalid = allowed.toVector.sortBy((p, r) => (p, r.ordinal)).find { key =>
            key._1 == Protocols.Handshake || !channels.contains(key)
          }
          val handshakePending = inbound.toVector
            .filter(_._1._1 == Protocols.Handshake)
            .traverse { (key, ingress) =>
              ingress.pendingFrames.map(_ || channels(key)._2.size > 0)
            }
            .map(_.contains(true))
          val validation = invalid.fold(dispatch.install(allowed, handshakePending)) { (p, role) =>
            F.pure(
              Left(
                NetworkError.IllegalTransition(
                  p,
                  if p == Protocols.Handshake || !protocols(p) then
                    NetworkError.RuntimeViolation.UnexpectedProtocol(p)
                  else NetworkError.RuntimeViolation.NotRegistered(role)
                )
              )
            )
          }
          handshakeInput.traverse_(input =>
            F.race(done.get, input.finished.get).flatMap(_.fold(_.raise[F, Unit], F.pure))
          ) *> F
            .race(done.get, outbound.flush)
            .flatMap(
              _.fold(
                _.raise[F, Unit],
                _ =>
                  validation.flatMap(
                    _.fold(error => done.complete(error).void *> error.raise[F, Unit], F.pure)
                  )
              )
            )
        def channel(protocol: Int)(using Raise[F, NetworkError]) = channel(protocol, config.role)
        def channel(protocol: Int, role: Role)(using Raise[F, NetworkError]) =
          dispatch.check((protocol, role)).flatMap {
            case Some(error) => error.raise[F, MuxChannel[F]]
            case None =>
              channels.get((protocol, role)) match
                case Some((c, _)) => F.pure(c)
                case None =>
                  NetworkError
                    .IllegalTransition(
                      protocol,
                      NetworkError.RuntimeViolation.NotRegistered(role)
                    )
                    .raise[F, MuxChannel[F]]
          }
        def closed = done.get
        def abort(reason: NetworkError) = done.complete(reason).void
    }

  private def channel[F[_]: Async](
      id: Int,
      role: Role,
      ingress: Ingress[F],
      outbound: MuxEgress.Outbox[F],
      closed: Deferred[F, NetworkError],
      buffer: ReceiveBuffer,
      lock: Mutex[F],
      config: Config,
      dispatch: Dispatch[F],
      handshakeInput: Option[HandshakeInput[F]]
  ): MuxChannel[F] = new MuxChannel[F]:
    private val F = Async[F]
    def protocol = id

    private def permitted(using Raise[F, NetworkError]): F[Unit] =
      dispatch.check((id, role)).flatMap(_.fold(F.unit)(_.raise[F, Unit]))

    def send(message: Bytes)(using Raise[F, NetworkError]): F[Unit] = permitted *>
      (if message.size == 0 || message.size > config.maxMessageBytes then
         NetworkError
           .IllegalTransition(
             id,
             NetworkError.RuntimeViolation.OutgoingMessageSize(message.size, config.maxMessageBytes)
           )
           .raise
       else
         closed.tryGet.flatMap {
           case Some(reason) => reason.raise[F, Unit]
           case None =>
             F.race(closed.get, outbound.send(id, role, message)).flatMap {
               case Left(reason) => reason.raise[F, Unit]
               case Right(_)     => F.unit
             }
         })

    def flush: F[Unit] = F.race(closed.get, outbound.flush).void

    // Queued SDUs are delivered before a close is reported; the reader closes only after enqueueing.
    private def nextPayload(using Raise[F, NetworkError]): F[Bytes] = permitted *>
      ingress.tryTake.flatMap {
        case Some(payload) => F.pure(payload)
        case None =>
          closed.tryGet.flatMap {
            case Some(reason) => reason.raise[F, Bytes]
            case None =>
              val waiting = dispatch.configured.flatMap {
                case true => ingress.take
                case false =>
                  F.race(dispatch.installed.get, ingress.take)
                    .flatMap(_.fold(_ => nextPayload, F.pure))
              }
              F.race(closed.get, waiting).flatMap(_.fold(_ => nextPayload, F.pure))
          }
      }

    // An SDU that did not fit beside the buffered bytes while batching: held for the next round,
    // after the buffered message has been decoded and consumed. Used only under `lock`.
    private var pending: Option[Bytes] = None

    def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A])(using
        Raise[F, NetworkError]
    ): F[A] =
      lock.lock.surround {
        def decodeNow: F[A] = permitted *>
          F.delay(buffer.view).flatMap { held =>
            decode(held) match
              case DecodeResult.Decoded(value, consumed) =>
                if consumed > maxBytes then NetworkError.IngressLimit(id, consumed).raise[F, A]
                else
                  F.delay(buffer.consume(consumed)) *>
                    handshakeInput
                      .traverse_(input => F.delay(buffer.clear()) *> input.complete)
                      .as(value)
              case DecodeResult.Failed(reason) => NetworkError.Decode(id, reason).raise[F, A]
              case DecodeResult.NeedMore =>
                if held.size > maxBytes then NetworkError.IngressLimit(id, held.size).raise[F, A]
                else handshakeInput.traverse_(_.continue) *> await(held.size)
          }

        // Wait for an SDU, then take all that are already queued before decoding again, unless
        // the buffer has doubled since the last attempt: a decoder that rereads the buffer from
        // its start (a long array of items) is then run a logarithmic number of times for a
        // message that arrives in many SDUs, and in time proportional to its size overall (when
        // the queue runs dry after every SDU the decoder runs after each, so one that rescans its
        // prefix is quadratic: the batching bounds only SDUs that are already queued).
        def await(attempted: Int): F[A] =
          F.delay { val held = pending; pending = None; held }.flatMap {
            case Some(payload) => append(payload) *> more(attempted)
            case None          => nextPayload.flatMap(append(_) *> more(attempted))
          }

        def more(attempted: Int): F[A] =
          F.delay(buffer.size).flatMap { held =>
            // The handshake mux decodes after every SDU: it must not read ahead into SDUs that
            // belong to the application protocols.
            if handshakeInput.isDefined || held > maxBytes || held >= 2L * attempted then decodeNow
            else
              permitted *> ingress.tryTake.flatMap {
                case None       => decodeNow
                case Some(next) =>
                  // Batching is an optimisation: an SDU that would overflow the buffer waits until
                  // the complete message already held has been decoded.
                  F.delay(buffer.append(next)).flatMap { fits =>
                    if fits then more(attempted)
                    else F.delay { pending = Some(next) } *> decodeNow
                  }
              }
          }

        def append(payload: Bytes): F[Unit] =
          F.delay(buffer.append(payload)).flatMap { fits =>
            if fits then F.unit
            else NetworkError.IngressLimit(id, Int.MaxValue).raise[F, Unit]
          }

        decodeNow
      }
