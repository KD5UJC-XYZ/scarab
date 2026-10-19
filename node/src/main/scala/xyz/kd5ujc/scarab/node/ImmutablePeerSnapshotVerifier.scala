// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Temporal
import cats.mtl.Raise
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.ImmutableStore

/** The Genesis monitor's immutable point check (`jobVerifyPeerSnapshot`, ouroboros-network 1.2.0.0
  * `cardano-diffusion/lib/Cardano/Network/PeerSelection/Governor/ Monitor.hs:684-708`). The caller
  * owns the storage error scope; waiting is cancelable and no store gate is held while the
  * immutable tip advances.
  */
object ImmutablePeerSnapshotVerifier:
  def matches[F[_]: Temporal](
      store: ImmutableStore[F],
      slot: BigInt,
      hash: Bytes,
      pollEvery: FiniteDuration = 1.second
  )(using Raise[F, StorageError]): F[Boolean] =
    val F = Temporal[F]
    F.tailRecM(()) { _ =>
      store.tip.flatMap {
        case Some(tip) if tip.slot >= slot =>
          store.pointAtOrAfterSlot(slot).map { point =>
            Right(point.exists(found => found.slot == slot && found.hash == hash))
          }
        case _ => F.sleep(pollEvery).as(Left(()))
      }
    }
