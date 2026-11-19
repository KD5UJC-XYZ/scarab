// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.ProtocolParams
import xyz.kd5ujc.scarab.ledger.state.*

/** Dijkstra's SNAP (cardano-ledger 9df9f98c `Dijkstra/Rules/Snap.hs:105-145`, `snapTransition`):
  * the stake distribution of the ledger state becomes the new mark snapshot, taken at the epoch the
  * chain enters with the parameters' Leios committee size (`ppLeiosCommitteeSize`) and the key age
  * the KES constants give ([[Globals.maxKeyAgeEpochs]]); the old mark rotates into the set position
  * with the committee it memoises, the old set into the go position, and the fees become the
  * snapshot fee ([[LeiosCommitteeSelection.rotate]]). The environment is `SnapEnv ls pp`:
  * Dijkstra's EPOCH passes the ledger state and the current parameters after enactment
  * ([[DijkstraEpoch]]).
  *
  * Dijkstra's state is of the Leios build's layout: a Dijkstra state of the 11.1 layout is not held
  * ([[xyz.kd5ujc.scarab.protocol.DijkstraNotYet.LedgerState]]), and its snapshots are refused so.
  */
object DijkstraSnap:
  val sts: Sts[(LedgerState[Dijkstra], ProtocolParams), SnapShots, EpochNo, TickFailure] =
    new Sts[(LedgerState[Dijkstra], ProtocolParams), SnapShots, EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: (LedgerState[Dijkstra], ProtocolParams), epoch: EpochNo)(
          using
          ask: Ask[F, Globals],
          snapshots: Stateful[F, SnapShots],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra
        val (ls, pp) = env
        val mark =
          StakeDistribution.snapshot(EraLedger.activeStake(ls), EraLedger.pState(ls).stakePools)
        for
          globals <- Sts.globals[F]
          age <- Sts.lift[F, TickFailure, EpochInterval](
            globals.maxKeyAgeEpochs.leftMap(TickFailure.Vocab(_))
          )
          size <- Sts.lift[F, TickFailure, LeiosCommitteeSize](
            LeiosCommitteeSize
              .fromBigInt(pp.leiosCommitteeSize)
              .leftMap(TickFailure.LeiosCommittee(_))
          )
          s <- snapshots.get
          rotated <- s.layout match
            case layout: SnapShots.Layout.Leios =>
              Sts.lift[F, TickFailure, SnapShots](
                LeiosCommitteeSelection
                  .rotate(s, layout, mark, epoch, size, age, ls.utxo.fees)
                  .leftMap(TickFailure.LeiosCommittee(_))
              )
            case SnapShots.Layout.Node11_1 =>
              failures.raise[TickFailure, SnapShots](
                TickFailure.NotYet(xyz.kd5ujc.scarab.protocol.DijkstraNotYet.LedgerState)
              )
          _ <- snapshots.set(rotated)
        yield ()
