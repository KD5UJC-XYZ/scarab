// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.consensus.{ConsensusExtras, FollowState}
import xyz.kd5ujc.scarab.ledger.Nonce

/** The sidecar of a snapshot version before V11 ([[FrozenSidecar]]) for a current one, so the
  * previous-version tests can write files of those versions from the states they make. Its
  * bootstrap state holds the follow state, the flag `active` (by default off: no writer since V5
  * set it, though one kept a flag its state already held) and the lite ledger `lite` (by default an
  * empty one: no lite ledger was read); the migration to V11 drops both.
  */
object PreviousSidecars:
  /** A lite ledger with nothing in it. */
  private[storage] val emptyLite: FrozenSidecar.LiteLedger =
    FrozenSidecar.LiteLedger(
      0,
      FrozenSidecar.LiteParams((0, 0), Rational.zero, Nonce.Neutral, 0, 0, 0),
      Map.empty,
      Vector.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      0
    )

  private[storage] def of(
      sidecar: SnapshotSidecar,
      active: Boolean = false,
      lite: FrozenSidecar.LiteLedger = emptyLite
  ): FrozenSidecar.SnapshotSidecar =
    def extras(c: ConsensusExtras) =
      FrozenSidecar.ConsensusExtras(boot(c.follow), c.byron, c.protocol)
    def boot(f: FollowState) =
      FrozenSidecar.BootstrapState(active, f.era, lite, f.stats, f.completed)
    FrozenSidecar.SnapshotSidecar(
      sidecar.anchor,
      sidecar.observed,
      sidecar.gaps,
      sidecar.chain match
        case SnapshotSidecar.Chain.InByron(pending, c) =>
          FrozenSidecar.Chain.InByron(pending, extras(c))
        case SnapshotSidecar.Chain.InShelley(utxo, params, prevParams, mirror) =>
          FrozenSidecar.Chain.InShelley(
            utxo,
            params,
            prevParams,
            FrozenSidecar.MirrorExtras.InShelley(mirror.ledger, extras(mirror.consensus))
          )
    )
