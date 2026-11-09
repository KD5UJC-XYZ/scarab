// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.state.*

/** SNAP (`Shelley/Rules/Snap.hs:75`, `snapTransition`): the stake distribution at the epoch
  * boundary becomes the new `mark` snapshot, `mark` becomes `set`, `set` becomes `go`, and the fees
  * of the ledger state are kept as the snapshot fee (`ssFee`). The environment is the ledger state
  * (`SnapEnv ls pp`; the parameters are not read), the state the snapshots.
  *
  * The stake is the one resolved from the instant stake and the accounts
  * ([[StakeDistribution.snapshot]], `snapShotFromInstantStake`), so it never walks the UTxO.
  * Haskell memoises the per-pool distribution (`ssStakeMarkPoolDistr`, ADR 7); here it is
  * `markPoolDistr`, derived from the mark snapshot.
  */
object Snap:
  def sts[E <: EraTag](using
      ShelleyBasedEra[E]
  ): Sts[LedgerState[E], SnapShots, Unit, TickFailure] =
    new Sts[LedgerState[E], SnapShots, Unit, TickFailure]:
      def transition[F[_]: Monad](ls: LedgerState[E], signal: Unit)(using
          ask: Ask[F, Globals],
          snapshots: Stateful[F, SnapShots],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        // the reference's stake sums wrap; here one that would is no verdict
        // (overflow-improvements.md#compact-coin-arithmetic)
        val mark = EraLedger
          .activeStake(ls)
          .flatMap(StakeDistribution.snapshot(_, EraLedger.pState(ls).stakePools))
          .leftMap(TickFailure.Vocab(_))
        Sts
          .lift[F, TickFailure, SnapShot](mark)
          .flatMap(m => snapshots.modify(s => SnapShots(m, s.mark, s.set, ls.utxo.fees)))
