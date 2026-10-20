// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred, Ref, Resource, Temporal}
import cats.effect.syntax.all.*
import cats.effect.std.{Mutex, Queue, Semaphore}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{BlockFetchEngine, NetworkError, ProtocolError}
import scala.concurrent.duration.*

import BlockFetchEngine.{Message, Role, State}
import NetworkError.RuntimeViolation

/** Generic BlockFetch peers over the shared typed protocol engine. The caller owns the point/body
  * codecs, range source, and byte-accounting policy; this layer does not interpret ledger hashes or
  * block payloads.
  */
object BlockFetchRuntime:
  /** Per-request transport observations; the pipeline invokes callbacks without its submission
    * lock.
    */
  enum Event:
    case Sent(at: FiniteDuration)
    case Block(bytes: Int, at: FiniteDuration)
    case Completed(at: FiniteDuration)

  trait Client[F[_], P, B]:
    def requestRange(from: P, to: P)(using Raise[F, NetworkError]): F[Option[Vector[B]]]
    def requestRangeObserved(from: P, to: P)(observe: Event => F[Unit])(using
        Raise[F, NetworkError]
    ): F[Option[Vector[B]]]
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
      request: BlockFetchEngine.RequestLimits = BlockFetchEngine.RequestLimits(maxBlocks = 128),
      maxInFlightRequests: Int = 10
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

      def stream(observe: Event => F[Unit])(using Raise[F, NetworkError]): F[Vector[B]] =
        F.tailRecM((BlockFetchEngine.Received.Empty, Vector.empty[B])) { case (received, blocks) =>
          receive.flatMap {
            case Message.Block(block) =>
              BlockFetchEngine.Received.add(received, sizeOf(block), settings.request) match
                case Left(_) =>
                  NetworkError
                    .IngressLimit(id, blocks.size + 1)
                    .raise[F, Either[(BlockFetchEngine.Received, Vector[B]), Vector[B]]]
                case Right(next) =>
                  F.monotonic
                    .flatMap(at => observe(Event.Block(sizeOf(block), at)))
                    .as(Left((next, blocks :+ block)))
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
        ): F[Option[Vector[B]]] = requestRangeObserved(from, to)(_ => F.unit)

        def requestRangeObserved(from: P, to: P)(observe: Event => F[Unit])(using
            Raise[F, NetworkError]
        ): F[Option[Vector[B]]] = lock.lock.surround {
          Timed
            .within(settings.timeLimits.wholeRequest, id, "request")(
              send(Message.RequestRange(from, to)) *>
                F.monotonic.flatMap(at => observe(Event.Sent(at))) *> receive.flatMap {
                  case Message.NoBlocks   => F.pure(None)
                  case Message.StartBatch => stream(observe).map(Some(_))
                  case other => illegal(RuntimeViolation.BlockFetchEngineUnexpected(other))
                }
            )
            .flatTap(_ => F.monotonic.flatMap(at => observe(Event.Completed(at))))
        }
    }

  /** A bounded FIFO client. Requests are submitted independently of reply collection, as in the
    * reference BlockFetch client pipeline (ouroboros-network 2fb69829,
    * ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/BlockFetch/Client.hs:111-132). The
    * collector has its own error scope (ADR 0017). Cancelling a submitted request aborts the
    * bearer: its replies cannot be silently reassigned to a later request. Releasing an idle,
    * cleanly finished activation leaves the bearer reusable.
    */
  def resource[F[_]: Async, P, B](
      channel: MuxChannel[F],
      pointCodec: BlockFetchEngine.PointCodec[P],
      payloadCodec: BlockFetchEngine.PayloadCodec[B],
      sizeOf: B => Int,
      abort: NetworkError => F[Unit],
      settings: ClientSettings = ClientSettings()
  )(using Raise[F, NetworkError]): Resource[F, Client[F, P, B]] =
    val F = Async[F]
    val id = Protocols.BlockFetch
    type Result = Either[NetworkError, Option[Vector[B]]]
    final case class Job(
        result: Deferred[F, Result],
        ready: Deferred[F, FiniteDuration],
        observe: Event => F[Unit]
    )
    def illegal[A](cause: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, cause).raise[F, A]
    val cancelled = NetworkError.BearerClosed(NetworkError.Closure.Aborted)
    for
      _ <- Resource.eval(
        illegal[Unit](ProtocolError.InvalidRequestLimits)
          .whenA(settings.maxInFlightRequests <= 0)
      )
      queue <- Resource.eval(Queue.bounded[F, Job](settings.maxInFlightRequests))
      credits <- Resource.eval(Semaphore[F](settings.maxInFlightRequests.toLong))
      submitting <- Resource.eval(Mutex[F])
      pending <- Resource.eval(Ref.of[F, Set[Deferred[F, Result]]](Set.empty))
      failed <- Resource.eval(Deferred[F, NetworkError])
      ended <- Resource.eval(Ref.of[F, Boolean](false))
      client <-
        def terminate(error: NetworkError): F[Unit] = F.uncancelable { _ =>
          failed.complete(error).flatMap { first =>
            abort(error)
              .guarantee(
                pending
                  .getAndSet(Set.empty)
                  .flatMap(
                    _.toVector.traverse_(_.complete(Left(error)).void)
                  )
              )
              .whenA(first)
          }
        }
        def healthy(using Raise[F, NetworkError]): F[Unit] =
          failed.tryGet.flatMap(_.traverse_(_.raise[F, Unit]))
        def send(message: Message[P, B])(using Raise[F, NetworkError]): F[Unit] =
          BlockFetchEngine
            .encode(State.Idle, Role.Client, message, pointCodec, payloadCodec, settings.limits)
            .fold(illegal(_), channel.send)
        def receive(state: State)(using Raise[F, NetworkError]): F[Message[P, B]] =
          val limit =
            if state == State.Busy then settings.timeLimits.busy
            else settings.timeLimits.streaming
          Timed.within(limit, id, state.toString)(
            channel.recv(settings.limits.messageBytes(state))(
              BlockFetchEngine.decodePrefix(
                state,
                Role.Server,
                _,
                pointCodec,
                payloadCodec,
                settings.limits
              )
            )
          )
        def collect(job: Job)(using Raise[F, NetworkError]): F[Option[Vector[B]]] =
          receive(State.Busy).flatMap {
            case Message.NoBlocks => F.pure(None)
            case Message.StartBatch =>
              F.tailRecM((BlockFetchEngine.Received.Empty, Vector.empty[B])) {
                case (received, blocks) =>
                  receive(State.Streaming).flatMap {
                    case Message.Block(block) =>
                      BlockFetchEngine.Received.add(received, sizeOf(block), settings.request) match
                        case Left(_) =>
                          NetworkError
                            .IngressLimit(id, blocks.size + 1)
                            .raise[F, Either[(BlockFetchEngine.Received, Vector[B]), Vector[B]]]
                        case Right(next) =>
                          F.monotonic
                            .flatMap(at => job.observe(Event.Block(sizeOf(block), at)))
                            .as(Left((next, blocks :+ block)))
                    case Message.BatchDone => F.pure(Right(blocks))
                    case other => illegal(RuntimeViolation.BlockFetchEngineUnexpected(other))
                  }
              }.map(Some(_))
            case other => illegal(RuntimeViolation.BlockFetchEngineUnexpected(other))
          }
        val worker: F[Unit] = (queue.take.flatMap { job =>
          job.ready.get
            .flatMap { sentAt =>
              Fibers
                .scoped[F, NetworkError, Option[Vector[B]]](NetworkError.Defect(_)) {
                  F.monotonic
                    .flatMap { now =>
                      Timed.within(
                        settings.timeLimits.wholeRequest - (now - sentAt),
                        id,
                        "request"
                      )(collect(job))
                    }
                    .flatTap(_ => F.monotonic.flatMap(at => job.observe(Event.Completed(at))))
                }
            }
            .flatMap {
              case Left(error) => terminate(error)
              case right =>
                F.uncancelable(_ =>
                  pending.update(_ - job.result) *> job.result.complete(right).void
                )
            }
        }).foreverM
        val shutdown = pending.get.flatMap(p => terminate(cancelled).whenA(p.nonEmpty))
        val scopedWorker = Fibers
          .scoped[F, NetworkError, Unit](NetworkError.Defect(_))(worker)
          .flatMap(_.fold(terminate, _ => F.unit))
        Resource.make(scopedWorker.start)(fiber => shutdown *> fiber.cancel).as {
          new Client[F, P, B]:
            def idle: F[Boolean] =
              (pending.get, failed.tryGet, ended.get)
                .mapN((p, f, e) => p.isEmpty && f.isEmpty && !e)
            def done(using Raise[F, NetworkError]): F[Unit] =
              submitting.lock.surround {
                healthy *> pending.get.flatMap { p =>
                  if p.nonEmpty then illegal(RuntimeViolation.ReplyNotExpected(State.Busy))
                  else
                    ended.get.flatMap {
                      case true  => illegal(ProtocolError.BlockFetchIsTerminal)
                      case false => send(Message.ClientDone) *> ended.set(true)
                    }
                }
              }
            def requestRange(from: P, to: P)(using
                Raise[F, NetworkError]
            ): F[Option[Vector[B]]] = requestRangeObserved(from, to)(_ => F.unit)
            def requestRangeObserved(from: P, to: P)(observe: Event => F[Unit])(using
                Raise[F, NetworkError]
            ): F[Option[Vector[B]]] =
              credits.permit.use { _ =>
                (Deferred[F, Result], Deferred[F, FiniteDuration])
                  .mapN(Job(_, _, observe))
                  .flatMap { job =>
                    val submitted = Fibers
                      .scoped[F, NetworkError, Unit](NetworkError.Defect(_)) {
                        submitting.lock.surround {
                          healthy *> ended.get.flatMap {
                            case true => illegal[Unit](ProtocolError.BlockFetchIsTerminal)
                            case false =>
                              pending.update(_ + job.result) *> healthy *>
                                send(Message.RequestRange(from, to)) *> queue.offer(job)
                          }
                        } *> F.monotonic.flatMap { at =>
                          observe(Event.Sent(at)) *> job.ready.complete(at).void
                        }
                      }
                      .flatMap {
                        case Left(error) =>
                          pending.update(_ - job.result) *> terminate(error) *>
                            Fibers.land[F, NetworkError, Unit](Left(error))
                        case Right(_) => F.unit
                      } *> job.result.get.flatMap(Fibers.land(_))
                    submitted.onCancel(
                      pending.get.flatMap(p => terminate(cancelled).whenA(p(job.result)))
                    )
                  }
              }
        }
    yield client

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
