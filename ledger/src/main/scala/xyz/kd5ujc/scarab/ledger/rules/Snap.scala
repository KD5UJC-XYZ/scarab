// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.state.*

/** SNAP (`Shelley/Rules/Snap.hs:75`, `snapTransition`): the stake distribution at the epoch
  * boundary becomes the new `mark` snapshot, `mark` becomes `set`, `set` becomes `go`, and the fees
  * of the ledger state are kept as the snapshot fee (`ssFee`). The environment is the ledger state
  * (`SnapEnv ls pp`; the parameters are not read before Dijkstra), the state the snapshots, the
  * signal the epoch the chain enters.
  *
  * The stake is the one resolved from the instant stake and the accounts
  * ([[StakeDistribution.snapshot]], `snapShotFromInstantStake`), so it never walks the UTxO.
  * Haskell memoises the per-pool distribution (`ssStakeMarkPoolDistr`, ADR 7); here it is
  * `markPoolDistr`, derived from the mark snapshot.
  *
  * The snapshots' layout follows the reference build. The 11.1 layout rotates the bare snapshots,
  * and its SNAP takes no signal (cardano-ledger f649f975 `Shelley/Rules/Snap.hs:59`). The Leios
  * layout's SNAP takes the epoch (cardano-ledger 9df9f98c `Shelley/Rules/Snap.hs:59, 75-102`): the
  * new mark records it with a committee size and key age of zero before Dijkstra, and the old
  * mark's Leios committee rotates into the set position ([[LeiosCommitteeSelection.rotate]]).
  * Dijkstra's SNAP seats a committee of the parameters' size with the key age the KES parameters
  * give (`Dijkstra/Rules/Snap.hs:105-179`); it is not implemented, as Dijkstra's EPOCH is not.
  */
object Snap:
  def sts[E <: EraTag](using
      era: ShelleyBasedEra[E]
  ): Sts[LedgerState[E], SnapShots, EpochNo, TickFailure] =
    new Sts[LedgerState[E], SnapShots, EpochNo, TickFailure]:
      def transition[F[_]: Monad](ls: LedgerState[E], epoch: EpochNo)(using
          ask: Ask[F, Globals],
          snapshots: Stateful[F, SnapShots],
          failures: Raise[F, TickFailure]
      ): F[Unit] = era match
        case ShelleyBasedEra.SDijkstra =>
          failures.raise(TickFailure.NotYet(xyz.kd5ujc.scarab.protocol.DijkstraNotYet.Rules))
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
            ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
          val mark =
            StakeDistribution.snapshot(EraLedger.activeStake(ls), EraLedger.pState(ls).stakePools)
          val fee = ls.utxo.fees
          snapshots.get.flatMap { s =>
            s.layout match
              case SnapShots.Layout.Node11_1 =>
                snapshots.set(s.copy(mark = mark, set = s.mark, go = s.set, fee = fee))
              case layout: SnapShots.Layout.Leios =>
                LeiosCommitteeSelection
                  .rotate(
                    s,
                    layout,
                    mark,
                    epoch,
                    LeiosCommitteeSize.zero,
                    EpochInterval.zero,
                    fee
                  )
                  .fold(e => failures.raise(TickFailure.LeiosCommittee(e)), snapshots.set)
          }
