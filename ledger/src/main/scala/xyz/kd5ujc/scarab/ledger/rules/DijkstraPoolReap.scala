// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.state.*

/** Dijkstra's POOLREAP (cardano-ledger 9df9f98c `Dijkstra/Rules/PoolReap.hs`,
  * `poolReapTransition`): Shelley's rule ([[PoolReap.step]]), but for how the VRF key hashes are
  * released. Every pool releases one reference to each hash it stops using, the active hash of a
  * pool whose future parameters switch to another as well as a retiring pool's
  * (`supersededVRFKeyHashes <> retiredVRFKeyHashes`, each `removeVRFKeyHashOccurrence`), so a hash
  * two pools share keeps the other's reference; Shelley's removes a superseded hash whatever its
  * count.
  *
  * A pool whose future parameters take effect holds the voting key they carry, keeping its
  * registration epoch when it is the key it already holds (`mkStakePoolState e (currentState ^.
  * spsBlsKeyL)`, `State/StakePool.hs:290-317`). Parameters with a voting key are not held yet
  * ([[xyz.kd5ujc.scarab.protocol.DijkstraNotYet.PoolParamsBlsKey]]), so a pool's future parameters
  * carry none and the pool holds none after they take effect, as in the reference.
  */
object DijkstraPoolReap:
  /** One occurrence of each superseded and each retired pool's hash. */
  val release: PoolReap.Release = (counts, superseded, retired) =>
    PoolReap.removeOccurrences(counts, superseded ++ retired)

  val sts: Sts[Unit, PoolreapState[Dijkstra], EpochNo, TickFailure] =
    new Sts[Unit, PoolreapState[Dijkstra], EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, epoch: EpochNo)(using
          ask: Ask[F, Globals],
          st: Stateful[F, PoolreapState[Dijkstra]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra
        Sts.modifyE[F, PoolreapState[Dijkstra], TickFailure](
          PoolReap.step(epoch, _, release).leftMap(TickFailure.Vocab(_))
        )
