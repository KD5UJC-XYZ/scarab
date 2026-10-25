// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Defer
import cats.effect.{Resource, Temporal}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSyncEngine, LocalStateQuery, LocalStateQueryEngine}
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.{Failure, State}
import xyz.kd5ujc.scarab.protocol.LocalStateQueryEngine.{Message, Target}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role

import NetworkError.RuntimeViolation

/** Generic acquired-state runtime shared by Cardano and upstream mock-point profiles. */
object LocalStateQueryRuntime:
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
    case Network(error: NetworkError)
    case Backend(error: E)

  trait Source[F[_], E, P]:
    def acquire(target: Target[P]): Resource[F, Either[AcquireFailure[E], Session[F, E]]]

  def run[F[_]: Temporal: Defer, E, P](
      channel: MuxChannel[F],
      source: Source[F, E, P],
      point: ChainSyncEngine.PointCodec[P]
  )(using Raise[F, NetworkError]): F[Either[E, Unit]] =
    val id = Protocols.LocalStateQuery
    def illegal[A](detail: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    def send(state: State, message: Message[P])(using Raise[F, NetworkError]): F[Unit] =
      LocalStateQueryEngine.encode(state, Role.Server, message, point).fold(illegal, channel.send)
    def receive(state: State)(using Raise[F, NetworkError]): F[Message[P]] =
      channel.recv(LocalStateQuery.MaxQueryBytes)(
        LocalStateQueryEngine.decodePrefix(state, Role.Client, _, point)
      )

    enum Loop:
      case Idle
      case Acquire(target: Target[P])
      case Done

    enum AcquiredExit:
      case Release
      case ReAcquire(target: Target[P])

    def acquired(session: Session[F, E])(using Raise[F, NetworkError]): F[Either[E, AcquiredExit]] =
      Temporal[F].tailRecM[Unit, Either[E, AcquiredExit]](()) { _ =>
        receive(State.Acquired).flatMap {
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
          case other           => illegal(RuntimeViolation.LocalStateQueryEngineUnexpected(other))
        }
      }

    Temporal[F].tailRecM[Loop, Either[E, Unit]](Loop.Idle) {
      case Loop.Done => Temporal[F].pure(Right(Right(())))
      case Loop.Idle =>
        receive(State.Idle).flatMap {
          case Message.Acquire(target) => Temporal[F].pure(Left(Loop.Acquire(target)))
          case Message.Done            => Temporal[F].pure(Left(Loop.Done))
          case other => illegal(RuntimeViolation.LocalStateQueryEngineUnexpected(other))
        }
      case Loop.Acquire(target) =>
        source
          .acquire(target)
          .use {
            case Left(AcquireFailure.Rejected(failure)) =>
              Fibers.scoped[F, NetworkError, Either[E, Loop]](
                NetworkError.fromThrowable("local-state-query", _)
              )(send(State.Acquiring, Message.Failed(failure)).as(Right(Loop.Idle)))
            case Left(AcquireFailure.Network(error)) =>
              Temporal[F].pure(Left(error))
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
