// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import cats.effect.IO
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.node.{EstablishmentError, PeerStateActions}

/** Handles for scripted action algebras that do not run a connection manager. */
private[governor] object ActionFixtures:
  def established[A](
      actions: PeerStateActions[IO, A],
      peer: A
  ): Either[EstablishmentError, PeerStateActions.Connection[IO]] =
    Right(
      PeerStateActions.Connection(
        BigInt(peer.hashCode),
        BigInt(0),
        IO.pure(PeerStateActions.Monitoring(PeerStateActions.Status.Warm, None)),
        trust => actions.activate(peer, trust),
        actions.deactivate(peer),
        actions.close(peer),
        IO.unit
      )
    )

  val unavailable: EstablishmentError =
    EstablishmentError.Failed(PeerError.Unavailable(PeerError.Unavailability.SessionEnded))
