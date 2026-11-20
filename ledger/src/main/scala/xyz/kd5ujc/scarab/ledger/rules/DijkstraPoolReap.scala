// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.DijkstraNotYet

/** Dijkstra's POOLREAP (cardano-ledger 9df9f98c `Dijkstra/Rules/PoolReap.hs:104-212`,
  * `poolReapTransition`): Shelley's rule ([[PoolReap.step]]), but for how the VRF key hashes are
  * released and for the voting key a pool holds when its future parameters take effect.
  *
  * Every pool releases one reference to each hash it stops using, the active hash of a pool whose
  * future parameters switch to another as well as a retiring pool's (`supersededVRFKeyHashes <>
  * retiredVRFKeyHashes`, each `removeVRFKeyHashOccurrence`, `:154-160`), so a hash two pools share
  * keeps the other's reference; Shelley's removes a superseded hash whatever its count.
  *
  * A pool whose future parameters take effect holds the voting key they carry, keeping its
  * registration epoch when it is the key it already holds, and none when they carry none
  * (`mkStakePoolState e (currentState ^. spsBlsKeyL)`, `:122-141`; `State/StakePool.hs:288-320`);
  * Shelley's passes `SNothing` for the key the pool holds. Dijkstra's state is of the Leios build's
  * layout: a pool's state or parameters of the 11.1 layout are not held
  * ([[DijkstraNotYet.LedgerState]]), as in Dijkstra's SNAP ([[DijkstraSnap]]).
  */
object DijkstraPoolReap:
  /** One occurrence of each superseded and each retired pool's hash. */
  val release: PoolReap.Release = (counts, superseded, retired) =>
    PoolReap.removeOccurrences(counts, superseded ++ retired)

  /** `registerBlsKey <$> sppBlsKey futureParams` of `mkStakePoolState e (currentState ^.
    * spsBlsKeyL)` ([[BlsKeyState.register]]).
    */
  val adopt: PoolReap.Adopt = (current, params, epoch) =>
    (current.blsKey, params.blsKey) match
      case (PoolBlsKey.Leios(held), PoolParamsBlsKey.Leios(key)) =>
        Right(PoolBlsKey.Leios(key.map(BlsKeyState.register(epoch, held))))
      case (PoolBlsKey.Node11_1, _) | (_, PoolParamsBlsKey.Node11_1) =>
        Left(TickFailure.NotYet(DijkstraNotYet.LedgerState))

  val sts: Sts[Unit, PoolreapState[Dijkstra], EpochNo, TickFailure] =
    new Sts[Unit, PoolreapState[Dijkstra], EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, epoch: EpochNo)(using
          ask: Ask[F, Globals],
          st: Stateful[F, PoolreapState[Dijkstra]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra
        Sts.modifyE[F, PoolreapState[Dijkstra], TickFailure](
          PoolReap.step(epoch, _, release, adopt)
        )
