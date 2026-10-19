// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Defer
import cats.effect.{Resource, Temporal}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.NtcVersion
import xyz.kd5ujc.scarab.protocol.LocalStateQuery
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.{Failure, Message, State, Target}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import scala.concurrent.duration.*

import NetworkError.RuntimeViolation

/** Node-to-client LocalStateQuery server. The interpreter of the ledger sits behind [[Source]]:
  * `acquire` fixes a state (a [[Session]]) for as long as the client keeps it, and each query is
  * answered against that one state, however far the chain has moved meanwhile.
  */
object LocalStateQueryServer:
  /** Why a query is not answered. The connection ends, as it does on the reference when its codec
    * cannot decode a query, and the reason is logged with the close.
    */
  type Refusal = xyz.kd5ujc.scarab.protocol.QueryRefusal[?]
  val Refusal = xyz.kd5ujc.scarab.protocol.QueryRefusal

  /** One acquired ledger state. `answer` gets a query (one CBOR item) and returns the result item,
    * or the reason the query is not answered.
    */
  trait Session[F[_], E]:
    def answer(query: Bytes): F[Either[E, Either[Refusal, Bytes]]]

  /** Acquisition can fail through the existing wire failure, or report an internal view invariant
    * that the owning server must retain as a typed error without inventing a protocol tag.
    */
  enum AcquireFailure[+E]:
    case Rejected(failure: Failure)
    case Incoherent(
        point: xyz.kd5ujc.scarab.protocol.ChainSync.Point,
        flushed: Long,
        pinned: Long,
        selected: BigInt
    )
    case Backend(error: E)

  trait Source[F[_], E]:
    /** Bind subsequent acquisitions to the version agreed on this connection. Sources whose answers
      * do not depend on the version may keep their existing interpreter.
      */
    def forVersion(version: NtcVersion): Source[F, E] = this

    def acquire(target: Target): Resource[F, Either[AcquireFailure[E], Session[F, E]]]

  final case class Settings(idleTimeout: FiniteDuration = 3373.seconds)

  def run[F[_]: Temporal: Defer, E](
      channel: MuxChannel[F],
      source: Source[F, E],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Either[E, Unit]] =
    val id = Protocols.LocalStateQuery
    def illegal[A](detail: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    def send(state: State, message: Message)(using Raise[F, NetworkError]): F[Unit] =
      LocalStateQuery.encode(state, Role.Server, message).fold(illegal, channel.send)
    def receive(state: State, name: String)(using Raise[F, NetworkError]): F[Message] =
      Timed.within(settings.idleTimeout, id, name)(
        channel.recv(LocalStateQuery.MaxMessageBytes)(
          LocalStateQuery.decodePrefix(state, Role.Client, _)
        )
      )

    enum Loop:
      case Idle
      case Acquire(target: Target)
      case Done

    enum AcquiredExit:
      case Release
      case ReAcquire(target: Target)

    def acquired(session: Session[F, E])(using Raise[F, NetworkError]): F[Either[E, AcquiredExit]] =
      Temporal[F].tailRecM[Unit, Either[E, AcquiredExit]](()) { _ =>
        receive(State.Acquired, "acquired").flatMap {
          case Message.Query(query) =>
            session.answer(query).flatMap {
              case Left(error)          => Temporal[F].pure(Right(Left(error)))
              case Right(Right(result)) => send(State.Querying, Message.Result(result)).as(Left(()))
              case Right(Left(refusal)) =>
                NetworkError.QueryRefused(refusal).raise[F, Either[Unit, Either[E, AcquiredExit]]]
            }
          case Message.ReAcquire(target) =>
            Temporal[F].pure(Right(Right(AcquiredExit.ReAcquire(target))))
          case Message.Release => Temporal[F].pure(Right(Right(AcquiredExit.Release)))
          case other           => illegal(RuntimeViolation.LocalStateQueryUnexpected(other))
        }
      }

    Temporal[F].tailRecM[Loop, Either[E, Unit]](Loop.Idle) {
      case Loop.Done => Temporal[F].pure(Right(Right(())))
      case Loop.Idle =>
        receive(State.Idle, "idle").flatMap {
          case Message.Acquire(target) => Temporal[F].pure(Left(Loop.Acquire(target)))
          case Message.Done            => Temporal[F].pure(Left(Loop.Done))
          case other                   => illegal(RuntimeViolation.LocalStateQueryUnexpected(other))
        }
      case Loop.Acquire(target) =>
        source
          .acquire(target)
          .use {
            case Left(AcquireFailure.Rejected(failure)) =>
              Fibers.scoped[F, NetworkError, Either[E, Loop]](
                NetworkError.fromThrowable("local-state-query", _)
              )(send(State.Acquiring, Message.Failed(failure)).as(Right(Loop.Idle)))
            case Left(failure @ AcquireFailure.Incoherent(_, _, _, _)) =>
              Temporal[F].pure(
                Left(
                  NetworkError.StateAcquireFailure(
                    failure.point,
                    failure.flushed,
                    failure.pinned,
                    failure.selected
                  )
                )
              )
            case Left(AcquireFailure.Backend(error)) =>
              Temporal[F].pure(Right(Left(error)))
            case Right(session) =>
              Fibers.scoped[F, NetworkError, Either[E, Loop]](
                NetworkError.fromThrowable("local-state-query", _)
              ) {
                send(State.Acquiring, Message.Acquired) *> acquired(session).map(_.map {
                  case AcquiredExit.Release         => Loop.Idle
                  case AcquiredExit.ReAcquire(next) => Loop.Acquire(next)
                })
              }
          }
          .flatMap {
            case Left(error) =>
              error.raise[F, Either[Loop, Either[E, Unit]]]
            case Right(Left(error)) => Temporal[F].pure(Right(Left(error)))
            case Right(Right(loop)) => Temporal[F].pure(Left(loop))
          }
    }
