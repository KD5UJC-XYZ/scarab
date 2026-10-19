// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Ref, Temporal}
import cats.effect.std.Mutex
import cats.mtl.Raise
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.protocol.PeerSharing.{Amount, State}

/** The peer-polymorphic conversation (`PeerSharing/Client.hs`, `Server.hs` at 2fb69829). Address
  * encoding and Cardano's response policy belong to the wire interpreters.
  */
private[network] object PeerSharingRuntime:
  enum Command:
    case Request(amount: Amount)
    case Done

  trait ClientWire[F[_], P]:
    type Reply
    def request(state: State, amount: Amount)(using Raise[F, NetworkError]): F[Unit]
    def receive(using Raise[F, NetworkError]): F[Reply]
    def peers(amount: Amount, reply: Reply)(using Raise[F, NetworkError]): F[Vector[P]]
    def done(state: State)(using Raise[F, NetworkError]): F[Unit]

  trait ServerWire[F[_], P]:
    def command(using Raise[F, NetworkError]): F[Command]
    def peers(amount: Amount, values: Vector[P])(using Raise[F, NetworkError]): F[Unit]

  trait Client[F[_], P]:
    def request(amount: Amount)(using Raise[F, NetworkError]): F[Vector[P]]
    def done(using Raise[F, NetworkError]): F[Unit]

  def client[F[_]: Temporal, P](
      wire: ClientWire[F, P],
      responseTimeout: FiniteDuration
  ): F[Client[F, P]] =
    (Mutex[F], Ref.of[F, State](State.Idle)).mapN { (lock, state) =>
      new Client[F, P]:
        def request(amount: Amount)(using Raise[F, NetworkError]): F[Vector[P]] =
          lock.lock.surround {
            for
              current <- state.get
              _ <- wire.request(current, amount)
              _ <- state.set(State.Busy)
              reply <- Timed.within(responseTimeout, Protocols.PeerSharing, "StBusy")(wire.receive)
              peers <- wire.peers(amount, reply)
              _ <- state.set(State.Idle)
            yield peers
          }

        def done(using Raise[F, NetworkError]): F[Unit] =
          lock.lock.surround {
            state.get.flatMap(current => wire.done(current) *> state.set(State.Done))
          }
    }

  def server[F[_]: Temporal, P](
      wire: ServerWire[F, P],
      source: Amount => F[Vector[P]]
  )(using Raise[F, NetworkError]): F[Unit] =
    val step = wire.command.flatMap {
      case Command.Request(amount) => source(amount).flatMap(wire.peers(amount, _)).as(true)
      case Command.Done            => Temporal[F].pure(false)
    }
    step.iterateWhile(identity).void
