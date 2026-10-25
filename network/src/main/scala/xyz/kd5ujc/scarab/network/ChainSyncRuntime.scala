// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.syntax.all.*
import cats.effect.{Async, Ref, Resource, Temporal}
import cats.effect.std.Random
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncEngine, NetworkError}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Role, State}
import xyz.kd5ujc.scarab.protocol.ChainSyncEngine.Message

import NetworkError.RuntimeViolation

/** Generic ChainSync runtime shared by the strict Cardano facade and mock-domain conformance
  * profiles. The client supports ordinary requests and the existing fixed-batch pipeline; point,
  * tip, and payload values remain codec-owned.
  */
object ChainSyncRuntime:
  /** Both intersection outcomes retain the advertised server tip (Client.hs:1310–1341). */
  enum Intersection[+Q, +T]:
    case Found(point: Q, remoteTip: T)
    case NotFound(remoteTip: T)

    def tip: T = this match
      case Found(_, remoteTip) => remoteTip
      case NotFound(remoteTip) => remoteTip

    def toOption: Option[(Q, T)] = this match
      case Found(point, remoteTip) => Some(point -> remoteTip)
      case NotFound(_)             => None

  enum Update[+Q, +T, +P]:
    case RollForward(payload: P, tip: T)
    case RollBackward(point: Q, tip: T)

  trait Client[F[_], Q, T, P]:
    def findIntersectResult(points: Vector[Q])(using Raise[F, NetworkError]): F[Intersection[Q, T]]
    def findIntersect(points: Vector[Q])(using Raise[F, NetworkError]): F[Option[(Q, T)]]
    def requestNext(using Raise[F, NetworkError]): F[Update[Q, T, P]]
    def requestNextBatch(n: Int)(using Raise[F, NetworkError]): F[Vector[Update[Q, T, P]]]
    def requestNextBatchObserved(n: Int)(onUpdate: Update[Q, T, P] => F[Unit])(using
        Raise[F, NetworkError]
    ): F[Vector[Update[Q, T, P]]]
    def done(using Raise[F, NetworkError]): F[Unit]
    def idle: F[Boolean]
    def idling: F[Boolean]
    def observe(observer: ChainSyncClient.Observer[F]): Resource[F, Unit]

  /** Single-caller rolling client. Outstanding requests include completed, uncollected replies.
    * Production submits per-request receiver hooks: AwaitReply runs immediately in the receiver,
    * even while the sender handles an earlier reply. Raw submission and caller Await callbacks
    * retain the legacy collector API; registered observers belong to legacy methods. Resource
    * cancellation with outstanding work terminates the connection rather than exposing an unaligned
    * reusable channel.
    */
  trait Pipeline[F[_], Q, T, P] extends Client[F, Q, T, P]:
    def submit(using Raise[F, NetworkError]): F[Unit]
    def submitObserved(hook: ChainSyncClient.ReceiverHook[F])(using Raise[F, NetworkError]): F[Unit]
    def tryCollect(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Option[Update[Q, T, P]]]
    def collect(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Update[Q, T, P]]
    def serviceAwait(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Unit]
    def outstanding: F[Int]

    /** Collect outstanding replies without legacy reply observers; the callback accounts for each
      * discarded final reply (for example, retiring its captured request context).
      */
    def drain(onAwait: F[Unit])(onUpdate: Update[Q, T, P] => F[Unit])(using
        Raise[F, NetworkError]
    ): F[Unit]

  /** @param abort
    *   as for `ChainSyncClient.resource`: prompt termination signal, completes before the failure
    *   is published, never waits for cleanup.
    */
  def resource[F[_]: Async, Q, T, P](
      channel: MuxChannel[F],
      point: ChainSyncEngine.PointCodec[Q],
      tip: ChainSyncEngine.TipCodec[T],
      payload: ChainSyncEngine.PayloadCodec[P],
      abort: NetworkError => F[Unit],
      settings: ClientSettings = ClientSettings(),
      maxOutstanding: Int = 300
  )(using Raise[F, NetworkError]): Resource[F, Pipeline[F, Q, T, P]] =
    ChainSyncPipeline.resource(channel, point, tip, payload, abort, settings, maxOutstanding)

  trait Source[F[_], Q, T, P]:
    def intersect(points: Vector[Q]): F[Either[T, (Q, T)]]
    def poll: F[Option[Update[Q, T, P]]]
    def await: F[Update[Q, T, P]]

  final case class Timeouts(
      intersect: ProtocolDeadline,
      canAwait: ProtocolDeadline,
      mustReply: ProtocolDeadline
  )

  final case class ClientSettings(
      limits: ChainSync.Limits = ChainSync.Limits(),
      intersectTimeout: FiniteDuration = 10.seconds,
      canAwaitTimeout: FiniteDuration = 10.seconds,
      mustReply: ChainSyncTimeouts.Policy =
        ChainSyncTimeouts.Policy.Reference(ChainSyncTimeouts.PeerTrust.Untrustable),
      timeouts: Option[Timeouts] = None
  )

  final case class ServerSettings(
      limits: ChainSync.Limits = ChainSync.Limits(),
      idle: ProtocolDeadline = ChainSyncRuntime.DefaultIdle,
      protocol: Int = Protocols.ChainSync
  )

  /** `StIdle` of the server in node-to-node:
    * `defaultChainSyncIdleTimeout = ChainSyncIdleTimeout 3373` (cardano-diffusion
    * `Cardano/Network/Diffusion/Configuration.hs`, ouroboros-network 2fb69829), the idle timeout of
    * `timeLimitsChainSync` (`Cardano/Network/Protocol/ChainSync/Codec/TimeLimits.hs:59-60`).
    */
  val DefaultIdle: ProtocolDeadline = ProtocolDeadline.After(3373.seconds)

  /** Node-to-client runs its protocols with `runPeer`, without time limits (ouroboros-consensus
    * 82ecba32, `Ouroboros/Consensus/Network/NodeToClient.hs`, `mkApps`).
    */
  val NodeToClient: ServerSettings =
    ServerSettings(
      ChainSync.Limits.NodeToClient,
      idle = ProtocolDeadline.Unbounded,
      protocol = Protocols.LocalChainSync
    )

  def client[F[_]: Async, Q, T, P](
      channel: MuxChannel[F],
      point: ChainSyncEngine.PointCodec[Q],
      tip: ChainSyncEngine.TipCodec[T],
      payload: ChainSyncEngine.PayloadCodec[P],
      settings: ClientSettings = ClientSettings()
  ): F[Client[F, Q, T, P]] =
    val F = Async[F]
    val id = Protocols.ChainSync
    def illegal[A](detail: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    def unexpected[A](message: Message[Q, T, P])(using Raise[F, NetworkError]): F[A] =
      illegal(RuntimeViolation.ChainSyncEngineUnexpected(message))

    (
      Ref.of[F, State](State.Idle),
      Ref.of[F, Boolean](false),
      Ref.of[F, Boolean](false),
      Ref.of[F, Option[ChainSyncClient.Observer[F]]](None),
      Random.scalaUtilRandom[F]
    ).mapN { (state, awaiting, busy, observer, random) =>
      def send(message: Message[Q, T, P])(using Raise[F, NetworkError]): F[Unit] =
        state.get.flatMap { current =>
          val next = ChainSyncEngine.transition(current, Role.Client, message)
          ChainSyncEngine
            .encode(current, Role.Client, message, point, tip, payload, settings.limits)
            .fold(illegal(_), bytes => channel.send(bytes))
            .productR(next.fold(illegal(_), state.set))
        }

      def receiveAt(current: State)(using Raise[F, NetworkError]): F[Message[Q, T, P]] =
        val limit = current match
          case State.Intersect | State.NextCanAwait | State.NextMustReply =>
            ChainSyncTimeouts.next(current, settings)(
              random.nextLong.map(ChainSyncTimeouts.sampledSeconds)
            )
          case other => illegal[ProtocolDeadline](RuntimeViolation.ReplyNotExpected(other))
        limit.flatMap { wait =>
          Timed.within(wait, id, current.toString)(
            channel.recv(settings.limits.maxMessageBytes)(
              ChainSyncEngine.decodePrefix(
                current,
                Role.Server,
                _,
                point,
                tip,
                payload,
                settings.limits
              )
            )
          )
        }

      def advance(current: State, message: Message[Q, T, P])(using
          Raise[F, NetworkError]
      ): F[State] =
        ChainSyncEngine.transition(current, Role.Server, message).fold(illegal(_), F.pure)

      def noted(message: Message[Q, T, P]): F[Unit] = message match
        case Message.AwaitReply =>
          awaiting.set(true) *> observer.get.flatMap(_.traverse_(_.awaitReply))
        case Message.RollForward(_, _) | Message.RollBackward(_, _) =>
          awaiting.set(false) *> observer.get.flatMap(_.traverse_(_.reply))
        case _ => awaiting.set(false)

      def receive(using Raise[F, NetworkError]): F[Message[Q, T, P]] =
        state.get.flatMap(current =>
          receiveAt(current).flatMap(message =>
            advance(current, message).flatMap(state.set) *> noted(message).as(message)
          )
        )

      def flight[A](operation: F[A]): F[A] =
        F.uncancelable(poll => busy.set(true) *> poll(operation).guarantee(busy.set(false)))

      new Client[F, Q, T, P]:
        def findIntersect(points: Vector[Q])(using Raise[F, NetworkError]) =
          findIntersectResult(points).map(_.toOption)

        def findIntersectResult(points: Vector[Q])(using
            Raise[F, NetworkError]
        ): F[Intersection[Q, T]] =
          flight {
            send(Message.FindIntersect(points)) *> receive.flatMap {
              case Message.IntersectFound(point, tip) if points.contains(point) =>
                F.pure(Intersection.Found(point, tip))
              case Message.IntersectFound(_, _) =>
                illegal(RuntimeViolation.IntersectionNotOffered)
              case Message.IntersectNotFound(tip) => F.pure(Intersection.NotFound(tip))
              case other                          => unexpected(other)
            }
          }

        def requestNext(using Raise[F, NetworkError]): F[Update[Q, T, P]] =
          flight(send(Message.RequestNext) *> collect)

        def requestNextBatch(n: Int)(using
            Raise[F, NetworkError]
        ): F[Vector[Update[Q, T, P]]] = requestNextBatchObserved(n)(_ => F.unit)

        def requestNextBatchObserved(n: Int)(onUpdate: Update[Q, T, P] => F[Unit])(using
            Raise[F, NetworkError]
        ): F[Vector[Update[Q, T, P]]] = flight {
          if n <= 1 then send(Message.RequestNext) *> collect.flatTap(onUpdate).map(Vector(_))
          else
            state.get.flatMap {
              case State.Idle =>
                ChainSyncEngine
                  .encode(
                    State.Idle,
                    Role.Client,
                    Message.RequestNext,
                    point,
                    tip,
                    payload,
                    settings.limits
                  )
                  .fold(illegal(_), bytes => List.fill(n)(bytes).traverse_(channel.send))
                  .productR(List.fill(n)(()).traverse(_ => collectOne.flatTap(onUpdate)))
                  .map(_.toVector)
              case other => illegal(RuntimeViolation.PipelinedRequest(other))
            }
        }

        private def collectOne(using Raise[F, NetworkError]): F[Update[Q, T, P]] =
          def go(current: State)(using Raise[F, NetworkError]): F[Update[Q, T, P]] =
            receiveAt(current).flatMap { message =>
              noted(message) *> advance(current, message).flatMap { next =>
                message match
                  case Message.AwaitReply => go(next)
                  case Message.RollForward(value, serverTip) =>
                    F.pure(Update.RollForward(value, serverTip))
                  case Message.RollBackward(serverPoint, serverTip) =>
                    F.pure(Update.RollBackward(serverPoint, serverTip))
                  case other => unexpected(other)
              }
            }
          go(State.NextCanAwait)

        private def collect(using Raise[F, NetworkError]): F[Update[Q, T, P]] =
          receive.flatMap {
            case Message.AwaitReply => collect
            case Message.RollForward(value, serverTip) =>
              F.pure(Update.RollForward(value, serverTip))
            case Message.RollBackward(serverPoint, serverTip) =>
              F.pure(Update.RollBackward(serverPoint, serverTip))
            case other => unexpected(other)
          }

        def done(using Raise[F, NetworkError]): F[Unit] = send(Message.Done)
        def idle: F[Boolean] =
          (state.get, busy.get).mapN((current, inFlight) => current == State.Idle && !inFlight)
        def idling: F[Boolean] = awaiting.get
        def observe(next: ChainSyncClient.Observer[F]): Resource[F, Unit] =
          Resource.make(observer.getAndSet(Some(next)))(observer.set).void
    }

  def run[F[_]: Temporal, Q, T, P](
      channel: MuxChannel[F],
      point: ChainSyncEngine.PointCodec[Q],
      tip: ChainSyncEngine.TipCodec[T],
      payload: ChainSyncEngine.PayloadCodec[P],
      source: Source[F, Q, T, P],
      settings: ServerSettings = ServerSettings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val id = settings.protocol
    def illegal[A](detail: NetworkError.Violation): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    def unexpected[A](message: Message[Q, T, P]): F[A] =
      illegal(RuntimeViolation.ChainSyncEngineUnexpected(message))
    def send(state: State, message: Message[Q, T, P]): F[State] =
      ChainSyncEngine
        .encode(state, Role.Server, message, point, tip, payload, settings.limits)
        .fold(illegal, channel.send)
        .productR(ChainSyncEngine.transition(state, Role.Server, message).fold(illegal, F.pure))
    def instruction(update: Update[Q, T, P]): Message[Q, T, P] = update match
      case Update.RollForward(value, serverTip) => Message.RollForward(value, serverTip)
      case Update.RollBackward(serverPoint, serverTip) =>
        Message.RollBackward(serverPoint, serverTip)

    val step: F[Boolean] =
      Timed
        .within(
          settings.idle,
          id,
          "idle"
        )(
          channel.recv(settings.limits.maxMessageBytes)(
            ChainSyncEngine
              .decodePrefix(State.Idle, Role.Client, _, point, tip, payload, settings.limits)
          )
        )
        .flatMap {
          case Message.FindIntersect(points) =>
            source.intersect(points).flatMap {
              case Right((found, serverTip)) =>
                send(State.Intersect, Message.IntersectFound(found, serverTip)).as(true)
              case Left(serverTip) =>
                send(State.Intersect, Message.IntersectNotFound(serverTip)).as(true)
            }
          case Message.RequestNext =>
            source.poll.flatMap {
              case Some(update) => send(State.NextCanAwait, instruction(update)).as(true)
              case None =>
                send(State.NextCanAwait, Message.AwaitReply).flatMap(waiting =>
                  source.await.flatMap(update => send(waiting, instruction(update))).as(true)
                )
            }
          case Message.Done => F.pure(false)
          case other        => unexpected(other)
        }
    step.iterateWhile(identity).void
