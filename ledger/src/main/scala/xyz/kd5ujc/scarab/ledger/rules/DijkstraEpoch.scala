// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.state.*

/** Dijkstra's EPOCH (cardano-ledger 9df9f98c `Dijkstra/Rules/Epoch.hs:153-233`, `epochTransition`):
  * Conway's rule with SNAP moved to the end. POOLREAP runs first ([[DijkstraPoolReap]]); the
  * ratification the DRep pulser reached is enacted and the hard fork rule runs if the protocol
  * version changed, as in Conway ([[ConwayEpoch.enact]]); then SNAP takes the new mark snapshot of
  * that ledger state with the parameters now current, seating the Leios committee of the next epoch
  * ([[DijkstraSnap]]); and the fresh DRep pulser starts over the new mark's pool distribution
  * ([[ConwayEpoch.withFreshPulser]]). Conway's EPOCH runs SNAP first, on the state the boundary
  * begins with.
  */
object DijkstraEpoch:
  val sts: Sts[Unit, EpochState[Dijkstra], EpochNo, TickFailure] =
    new Sts[Unit, EpochState[Dijkstra], EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, epoch: EpochNo)(using
          ask: Ask[F, Globals],
          es: Stateful[F, EpochState[Dijkstra]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra
        for
          es0 <- es.get
          globals <- Sts.globals[F]
          _ <- Epoch.within(DijkstraPoolReap.sts, (), epoch, PoolreapState.lens[Dijkstra])
          es1 <- es.get
          es2 <- Sts.lift[F, TickFailure, EpochState[Dijkstra]](
            ConwayEpoch.enact(globals.ledger.referenceBuild, epoch, es0, es1).leftMap(TickFailure.Vocab(_))
          )
          _ <- es.set(es2)
          params = EraLedger.curPParams(es2.ls.utxo.gov)
          _ <- Epoch.within(
            DijkstraSnap.sts,
            (es2.ls, params),
            epoch,
            EpochState.snapshots[Dijkstra]
          )
          es3 <- es.get
          _ <- es.set(ConwayEpoch.withFreshPulser(globals, es3, epoch))
        yield ()
