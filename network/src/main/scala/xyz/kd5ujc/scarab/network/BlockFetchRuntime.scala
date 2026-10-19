// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Ref, Temporal}
import cats.effect.std.Mutex
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{BlockFetchEngine, NetworkError, ProtocolError}
import scala.concurrent.duration.*

import BlockFetchEngine.{Message, Role, State}
import NetworkError.RuntimeViolation

/** Generic, non-pipelined BlockFetch peers over the shared typed protocol engine. The caller owns
  * the point/body codecs, range source, and byte-accounting policy; this layer does not interpret
  * ledger hashes or block payloads.
  */
object BlockFetchRuntime:
  trait Client[F[_], P, B]:
    def requestRange(from: P, to: P)(using Raise[F, NetworkError]): F[Option[Vector[B]]]
    def done(using Raise[F, NetworkError]): F[Unit]
    def idle: F[Boolean]

  trait Blocks[F[_], P, B]:
    /** Return `None` when the inclusive range is absent, noncontiguous, or exceeds `maxBlocks`. A
      * returned stream contains blocks in source chain order, matching the strict Cardano
      * `BlockFetchServer.Blocks` contract.
      */
    def range(from: P, to: P, maxBlocks: Int): F[Option[Stream[F, B]]]

  final case class ClientSettings(
      limits: BlockFetchEngine.Limits = BlockFetchEngine.Limits(),
      timeLimits: BlockFetchEngine.TimeLimits = BlockFetchEngine.TimeLimits(),
      request: BlockFetchEngine.RequestLimits = BlockFetchEngine.RequestLimits(maxBlocks = 128)
  )

  final case class ServerSettings(
      limits: BlockFetchEngine.Limits = BlockFetchEngine.Limits(),
      maxBlocks: Int = 2160,
      idleTimeout: FiniteDuration = 3373.seconds
  )

  def client[F[_]: Temporal, P, B](
      channel: MuxChannel[F],
      pointCodec: BlockFetchEngine.PointCodec[P],
      payloadCodec: BlockFetchEngine.PayloadCodec[B],
      sizeOf: B => Int,
      settings: ClientSettings = ClientSettings()
  ): F[Client[F, P, B]] =
    val F = Temporal[F]
    val id = Protocols.BlockFetch
    def illegal[A](detail: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]

    (Ref.of[F, State](State.Idle), Mutex[F]).mapN { (state, lock) =>
      def send(message: Message[P, B])(using Raise[F, NetworkError]): F[Unit] =
        state.get.flatMap { current =>
          BlockFetchEngine
            .encode(current, Role.Client, message, pointCodec, payloadCodec, settings.limits)
            .fold(illegal(_), channel.send)
            .productR(
              BlockFetchEngine
                .transition(current, Role.Client, message)
                .fold(illegal(_), state.set)
            )
        }

      def receive(using Raise[F, NetworkError]): F[Message[P, B]] =
        state.get.flatMap { current =>
          val wait = current match
            case State.Busy      => F.pure(settings.timeLimits.busy)
            case State.Streaming => F.pure(settings.timeLimits.streaming)
            case other => illegal[FiniteDuration](RuntimeViolation.ReplyNotExpected(other))
          wait.flatMap { limit =>
            Timed
              .within(limit, id, current.toString)(
                channel.recv(settings.limits.messageBytes(current))(
                  BlockFetchEngine.decodePrefix(
                    current,
                    Role.Server,
                    _,
                    pointCodec,
                    payloadCodec,
                    settings.limits
                  )
                )
              )
              .flatMap(message =>
                BlockFetchEngine
                  .transition(current, Role.Server, message)
                  .fold(illegal(_), state.set)
                  .as(message)
              )
          }
        }

      def stream(using Raise[F, NetworkError]): F[Vector[B]] =
        F.tailRecM((BlockFetchEngine.Received.Empty, Vector.empty[B])) { case (received, blocks) =>
          receive.flatMap {
            case Message.Block(block) =>
              BlockFetchEngine.Received.add(received, sizeOf(block), settings.request) match
                case Left(_) =>
                  NetworkError
                    .IngressLimit(id, blocks.size + 1)
                    .raise[F, Either[(BlockFetchEngine.Received, Vector[B]), Vector[B]]]
                case Right(next) => F.pure(Left((next, blocks :+ block)))
            case Message.BatchDone => F.pure(Right(blocks))
            case other             => illegal(RuntimeViolation.BlockFetchEngineUnexpected(other))
          }
        }

      new Client[F, P, B]:
        def done(using Raise[F, NetworkError]): F[Unit] =
          lock.lock.surround(send(Message.ClientDone))

        def idle: F[Boolean] = state.get.map(_ == State.Idle)

        def requestRange(from: P, to: P)(using
            Raise[F, NetworkError]
        ): F[Option[Vector[B]]] = lock.lock.surround {
          Timed.within(settings.timeLimits.wholeRequest, id, "request")(
            send(Message.RequestRange(from, to)) *> receive.flatMap {
              case Message.NoBlocks   => F.pure(None)
              case Message.StartBatch => stream.map(Some(_))
              case other              => illegal(RuntimeViolation.BlockFetchEngineUnexpected(other))
            }
          )
        }
    }

  def run[F[_]: Temporal, P, B](
      channel: MuxChannel[F],
      blocks: Blocks[F, P, B],
      pointCodec: BlockFetchEngine.PointCodec[P],
      payloadCodec: BlockFetchEngine.PayloadCodec[B],
      validateRange: (P, P) => Either[ProtocolError, (P, P)],
      settings: ServerSettings = ServerSettings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val id = Protocols.BlockFetch
    def illegal[A](detail: NetworkError.Violation): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]

    def send(state: State, message: Message[P, B]): F[Unit] =
      BlockFetchEngine
        .encode(state, Role.Server, message, pointCodec, payloadCodec, settings.limits)
        .fold(illegal, channel.send)

    def stream(source: Stream[F, B]): F[Unit] =
      source.evalMap(block => send(State.Streaming, Message.Block(block))).compile.drain

    val step: F[Boolean] =
      Timed
        .within(settings.idleTimeout, id, "idle")(
          channel.recv(settings.limits.messageBytes(State.Idle))(
            BlockFetchEngine.decodePrefix(
              State.Idle,
              Role.Client,
              _,
              pointCodec,
              payloadCodec,
              settings.limits
            )
          )
        )
        .flatMap {
          case Message.ClientDone => F.pure(false)
          case Message.RequestRange(from, to) =>
            validateRange(from, to).fold(illegal, F.pure).flatMap { case (first, last) =>
              blocks.range(first, last, settings.maxBlocks).flatMap {
                case None => send(State.Busy, Message.NoBlocks).as(true)
                case Some(source) =>
                  send(State.Busy, Message.StartBatch) *>
                    stream(source) *>
                    send(State.Streaming, Message.BatchDone).as(true)
              }
            }
          case other => illegal(RuntimeViolation.BlockFetchEngineUnexpected(other))
        }
    step.iterateWhile(identity).void
