// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import NetworkError.RuntimeViolation

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.NtcVersion
import xyz.kd5ujc.scarab.protocol.LocalStateQuery
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.{Failure, Message, State, Target}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import scala.concurrent.duration.*

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
  trait Session[F[_]]:
    def answer(query: Bytes): F[Either[Refusal, Bytes]]

  trait Source[F[_]]:
    /** Bind subsequent acquisitions to the version agreed on this connection. Sources whose answers
      * do not depend on the version may keep their existing interpreter.
      */
    def forVersion(version: NtcVersion): Source[F] = this

    def acquire(target: Target): F[Either[Failure, Session[F]]]

  final case class Settings(idleTimeout: FiniteDuration = 3373.seconds)

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      source: Source[F],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val id = Protocols.LocalStateQuery
    def illegal[A](detail: NetworkError.Violation): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    def send(state: State, message: Message): F[Unit] =
      LocalStateQuery.encode(state, Role.Server, message).fold(illegal, channel.send)
    def receive(state: State, name: String): F[Message] =
      Timed.within(settings.idleTimeout, id, name)(
        channel.recv(LocalStateQuery.MaxMessageBytes)(
          LocalStateQuery.decodePrefix(state, Role.Client, _)
        )
      )

    /** Acquiring: answer with `Acquired` and continue in `Acquired`, or with the failure and go
      * back to `Idle`.
      */
    def acquire(target: Target): F[Boolean] =
      source.acquire(target).flatMap {
        case Right(session) =>
          send(State.Acquiring, Message.Acquired) *> acquired(session)
        case Left(failure) =>
          send(State.Acquiring, Message.Failed(failure)).as(true)
      }

    def acquired(session: Session[F]): F[Boolean] =
      receive(State.Acquired, "acquired").flatMap {
        case Message.Query(query) =>
          session.answer(query).flatMap {
            case Right(result) =>
              send(State.Querying, Message.Result(result)) *> acquired(session)
            case Left(refusal) => NetworkError.QueryRefused(refusal).raise[F, Boolean]
          }
        case Message.ReAcquire(target) => acquire(target)
        case Message.Release           => Temporal[F].pure(true)
        case other                     => illegal(RuntimeViolation.LocalStateQueryUnexpected(other))
      }

    val idle: F[Boolean] =
      receive(State.Idle, "idle").flatMap {
        case Message.Acquire(target) => acquire(target)
        case Message.Done            => Temporal[F].pure(false)
        case other                   => illegal(RuntimeViolation.LocalStateQueryUnexpected(other))
      }
    idle.iterateWhile(identity).void
