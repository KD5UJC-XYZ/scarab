// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.ProtocolError
import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.PeerSharing
import xyz.kd5ujc.scarab.protocol.PeerSharing.{Address, Amount}
import scala.concurrent.duration.*

import NetworkError.RuntimeViolation

/** PeerSharing client on mini-protocol 10: asks the peer for addresses of other peers. The
  * conversation is `ShareRequest`, then `SharePeers`, as often as wanted, and ends with `Done`
  * (`Type.hs:74-95`). A request waits for its answer at most `responseTimeout` (`StBusy` has
  * `longWait`, 60 s) and an answer with more peers than asked for is a protocol violation
  * (`PeerSharing.hs:126-131`). Calls are serialised, so one client may be shared.
  */
trait PeerSharingClient[F[_]]:
  /** Ask for up to `amount` peers. */
  def request(amount: Amount)(using Raise[F, NetworkError]): F[Vector[Address]]

  /** Say `Done`; no request follows. */
  def done(using Raise[F, NetworkError]): F[Unit]

object PeerSharingClient:
  final case class Settings(
      responseTimeout: FiniteDuration = PeerSharing.UpstreamTimeLimits.busy,
      limits: PeerSharing.Limits = PeerSharing.Limits()
  )

  def apply[F[_]: Temporal](
      channel: MuxChannel[F],
      settings: Settings = Settings()
  ): F[PeerSharingClient[F]] =
    val F = Temporal[F]
    val id = Protocols.PeerSharing
    def illegal[A](detail: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    def send(state: PeerSharing.State, message: PeerSharing.Message)(using
        Raise[F, NetworkError]
    ): F[Unit] =
      PeerSharing
        .encode(state, PeerSharing.Role.Client, message, settings.limits)
        .fold(e => illegal(ProtocolError.PeerSharing(e)), channel.send)

    val wire = new PeerSharingRuntime.ClientWire[F, Address]:
      type Reply = PeerSharing.Message
      def request(state: PeerSharing.State, amount: Amount)(using Raise[F, NetworkError]): F[Unit] =
        send(state, PeerSharing.Message.ShareRequest(amount))
      def receive(using Raise[F, NetworkError]): F[Reply] =
        channel.recv(settings.limits.maxMessageBytes)(
          PeerSharing.decoder(PeerSharing.State.Busy, PeerSharing.Role.Server, settings.limits)
        )
      def peers(amount: Amount, reply: Reply)(using Raise[F, NetworkError]): F[Vector[Address]] =
        reply match
          case PeerSharing.Message.SharePeers(peers) if peers.size > amount.value =>
            illegal[Vector[Address]](
              RuntimeViolation
                .PeerSharingPolicy(PeerSharing.Violation.TooManyPeers(amount, peers.size))
            )
          case PeerSharing.Message.SharePeers(peers) => F.pure(peers)
          case other => illegal[Vector[Address]](RuntimeViolation.PeerSharingUnexpected(other))
      def done(state: PeerSharing.State)(using Raise[F, NetworkError]): F[Unit] =
        send(state, PeerSharing.Message.Done) *> channel.flush
    PeerSharingRuntime.client(wire, settings.responseTimeout).map { client =>
      new PeerSharingClient[F]:
        def request(amount: Amount)(using Raise[F, NetworkError]): F[Vector[Address]] =
          client.request(amount)
        def done(using Raise[F, NetworkError]): F[Unit] = client.done
    }
