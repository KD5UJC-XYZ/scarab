// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Concurrent, Ref}
import cats.effect.std.Mutex
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.PeerSharing.{Address, Amount}

/** The PeerSharing client of one connection as peer selection reaches it. The client is registered
  * while it runs and unregistered once it has said `Done`: `bracketPeerSharingClient` holds a
  * peer's controller in the registry for the life of its client (`PeerSharing.hs` 81-93 at
  * ouroboros-network c45735a5). A request finding no registered client sends nothing and answers
  * [[PeerSharingController.Result.NotRegisteredYet]], as `requestPeerSharingResult` answers for a
  * peer with no controller (`PeerSelectionActions.hs` 104-123 at ouroboros-network c45735a5). It is
  * no failure of the connection or the peer.
  *
  * Requests and the `Done` are taken one at a time, in turn. A request taken before the `Done` is
  * answered first, as the reference's client takes a queued request before it reads `Terminate`
  * (`peerSharingClient`, `PeerSharing.hs` 112-133 at ouroboros-network c45735a5). Once the `Done`
  * is taken, no request reaches the client, which refuses one in `StDone`.
  */
trait PeerSharingController[F[_]]:
  /** Ask the registered client for up to `amount` peers. */
  def request(amount: Amount)(using Raise[F, NetworkError]): F[PeerSharingController.Result]

  /** Say `Done` and unregister the client; once done, this does nothing more. */
  def done(using Raise[F, NetworkError]): F[Unit]

object PeerSharingController:
  /** What a request answers (`PeerSharingResult`, `Type.hs` 38-40 at ouroboros-network c45735a5).
    */
  enum Result:
    case Peers(peers: Vector[Address])

    /** No client is registered: it has said `Done` (`PeerSharingNotRegisteredYet`). */
    case NotRegisteredYet

  /** `client`, registered until its `Done`. */
  def apply[F[_]: Concurrent](client: PeerSharingClient[F]): F[PeerSharingController[F]] =
    (Mutex[F], Ref.of[F, Option[PeerSharingClient[F]]](Some(client))).mapN { (lock, registry) =>
      new PeerSharingController[F]:
        def request(amount: Amount)(using Raise[F, NetworkError]): F[Result] =
          lock.lock.surround(registry.get.flatMap {
            case Some(registered) => registered.request(amount).map(Result.Peers(_))
            case None             => Concurrent[F].pure(Result.NotRegisteredYet)
          })

        // unregistered before the `Done` is sent, so a `Done` that raises leaves no client either,
        // as the bracket's release runs on an exception too
        def done(using Raise[F, NetworkError]): F[Unit] =
          lock.lock.surround(registry.getAndSet(None).flatMap(_.traverse_(_.done)))
    }
