// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.state.{EraTag, NewEpochState}

/** The leader schedule's view of a ledger state: the stake and the VRF key hash of each pool for
  * the epoch the state is in, `nesPd` (`Shelley/LedgerState/Types.hs:302`, the distribution the
  * leader check of the epoch uses). What the forger reads when it asks whether the pool leads a
  * slot.
  *
  * The ledger state is a type parameter: the forger crosses the epoch boundary on the node's flat
  * ledger state until the rules are the era-indexed ones (J2.8), and then on a `NewEpochState`.
  */
trait LeaderPools[L]:
  extension (ledger: L) def leaderPools: flat.PoolDistribution

object LeaderPools:
  /** The flat state keeps `nesPd` as a snapshot, whose distribution is built once. */
  given LeaderPools[flat.LedgerState] with
    extension (ledger: flat.LedgerState)
      def leaderPools: flat.PoolDistribution = ledger.poolDistribution.distribution

  /** The mirror's `nesPd` as it is. */
  given [E <: EraTag]: LeaderPools[NewEpochState[E]] with
    extension (nes: NewEpochState[E])
      def leaderPools: flat.PoolDistribution = EraRead.leaderPools(nes.pd)
