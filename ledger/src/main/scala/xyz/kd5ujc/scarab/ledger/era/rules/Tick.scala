// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.era.{ConwayGovState, FuturePParams, StoredGov}
import xyz.kd5ujc.scarab.ledger.rules.{
  Embed,
  Globals,
  NewEpoch as NodeNewEpoch,
  Rewards,
  Rupd,
  RupdEnv,
  Sts,
  Tick as NodeTick,
  TickFailure
}
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  Dijkstra,
  EpochNo,
  EpochState,
  EraTag,
  NewEpochState,
  PulsingRewUpdate,
  ShelleyBasedEra,
  SlotNo
}

/** The new-epoch state the era's TICK and NEWEPOCH work on: the node's new-epoch state and the
  * era's governance state ([[ConwayGovState]]) beside it. What the rules read and write of the
  * governance state is `gov`; the one `nes` holds is the state the tick began with, read only
  * through [[StoredGov.synced]], by the node's rules that read it (the reward update and RUPD), and
  * replaced by `gov` after the tick ([[StoredGov.onRecord]]).
  */
final case class NewEpochGovState[E <: EraTag](nes: NewEpochState[E], gov: ConwayGovState[E])

object NewEpochGovState:
  def nes[E <: EraTag]: Lens[NewEpochGovState[E], NewEpochState[E]] =
    Lens[NewEpochGovState[E], NewEpochState[E]](_.nes)(v => _.copy(nes = v))

  def gov[E <: EraTag]: Lens[NewEpochGovState[E], ConwayGovState[E]] =
    Lens[NewEpochGovState[E], ConwayGovState[E]](_.gov)(v => _.copy(gov = v))

  def ru[E <: EraTag]: Lens[NewEpochGovState[E], Option[PulsingRewUpdate]] =
    nes[E].andThen(NewEpochState.ru[E])

  /** The epoch state with the governance state beside it, which the era's EPOCH works on. */
  def epoch[E <: EraTag]: Lens[NewEpochGovState[E], EpochGovState[E]] =
    Lens[NewEpochGovState[E], EpochGovState[E]](s => EpochGovState(s.nes.es, s.gov))(v =>
      s => NewEpochGovState(s.nes.copy(es = v.es), v.gov)
    )

  /** The governance state of a Dijkstra new-epoch state. */
  def record: Lens[NewEpochState[Dijkstra], ConwayGovState[Dijkstra]] =
    NewEpochState.es[Dijkstra].andThen(StoredGov.recordOf)

/** Dijkstra's NEWEPOCH, Conway's (`EraRule "NEWEPOCH" DijkstraEra = ConwayNEWEPOCH`,
  * `newEpochTransition`, `Conway/Rules/NewEpoch.hs:153-184`, cardano-ledger 9df9f98c), on the era's
  * governance state. For an epoch that is not the next one: `predictFuturePParams`, the pulser's
  * `pulseDRepPulsingState` being the identity on the complete pulser the node holds ([[DRepPulser]]
  * computes it where it starts). At the boundary: the reward update completed and applied
  * (`updateRewards`), with the era's previous parameters ([[StoredGov.synced]]); the era's EPOCH
  * ([[DijkstraEpoch]]); then the epoch, the blocks of the previous epoch, the empty blocks of the
  * new one and no reward update, the layout's own fields as the node's NEWEPOCH sets them
  * ([[NodeNewEpoch.boundaryLayout]]).
  */
object DijkstraNewEpoch:
  private given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra

  val sts: Sts[Unit, NewEpochGovState[Dijkstra], EpochNo, TickFailure] =
    new Sts[Unit, NewEpochGovState[Dijkstra], EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, eNo: EpochNo)(using
          ask: Ask[F, Globals],
          st: Stateful[F, NewEpochGovState[Dijkstra]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        st.get.flatMap { before =>
          if !before.nes.epoch.next.contains(eNo) then
            st.set(before.copy(gov = ConwayGovState.predictFuturePParams(before.gov)))
          else
            for
              es1 <- Sts.lift[F, TickFailure, EpochState[Dijkstra]](
                before.nes.ru match
                  case None => Right(before.nes.es)
                  case Some(pending) =>
                    Rewards
                      .complete(pending)
                      .flatMap(
                        NodeNewEpoch.applyRewardUpdate(
                          StoredGov.synced(before.nes.es, before.gov),
                          _
                        )
                      )
                      .leftMap(TickFailure.Vocab(_))
              )
              _ <- st.set(before.copy(nes = before.nes.copy(es = es1)))
              _ <- {
                given Embed[EpochGovState[Dijkstra], NewEpochGovState[
                  Dijkstra
                ], TickFailure, TickFailure] =
                  Embed(NewEpochGovState.epoch[Dijkstra], identity[TickFailure])
                Sts.trans[
                  F,
                  Unit,
                  EpochGovState[Dijkstra],
                  NewEpochGovState[Dijkstra],
                  EpochNo,
                  TickFailure,
                  TickFailure
                ](DijkstraEpoch.sts, (), eNo)
              }
              _ <- st.modify(s =>
                s.copy(nes =
                  s.nes.copy(
                    epoch = eNo,
                    bprev = before.nes.bcur,
                    bcur = BlocksMade.empty,
                    ru = None,
                    layout = NodeNewEpoch.boundaryLayout(before.nes)
                  )
                )
              )
            yield ()
        }

  /** [[sts]] on the node's new-epoch state, its governance state taken out before and put back
    * after ([[StoredGov.onRecord]]).
    */
  val onRecord: Sts[Unit, NewEpochState[Dijkstra], EpochNo, TickFailure] =
    StoredGov.onRecord[NewEpochState[Dijkstra], NewEpochGovState[Dijkstra], EpochNo](
      sts,
      NewEpochGovState.record,
      NewEpochGovState(_, _),
      s => (s.nes, s.gov)
    )

/** Dijkstra's TICK, Shelley's (`bheadTransition` and `validatingTickTransition`,
  * `Shelley/Rules/Tick.hs:163-185, 258-278`, cardano-ledger 9df9f98c), on the era's governance
  * state: `solidifyNextEpochPParams` from the slot of no return (`:147-159`,
  * `getTheSlotOfNoReturn`); NEWEPOCH for the slot's epoch ([[DijkstraNewEpoch]]);
  * `adoptGenesisDelegs` ([[NodeTick.adoptGenesisDelegs]]); and RUPD for the slot with the blocks of
  * the previous epoch and the epoch state the tick began with (`RupdEnv bprev es`), its previous
  * parameters the era's ([[StoredGov.synced]]) even where the tick crosses the boundary.
  */
object DijkstraTick:
  private given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra

  val sts: Sts[Unit, NewEpochGovState[Dijkstra], SlotNo, TickFailure] =
    new Sts[Unit, NewEpochGovState[Dijkstra], SlotNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, slot: SlotNo)(using
          ask: Ask[F, Globals],
          st: Stateful[F, NewEpochGovState[Dijkstra]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        for
          before <- st.get
          globals <- Sts.globals[F]
          now = slot.toBigInt
          epoch = globals.epochOf(now)
          _ <-
            if now < globals.slotOfNoReturn(epoch) then Monad[F].unit
            else
              st.modify(s =>
                s.copy(gov =
                  s.gov.copy(cgsFuturePParams =
                    FuturePParams.solidifyFuturePParams(s.gov.cgsFuturePParams)
                  )
                )
              )
          current <- Sts.lift[F, TickFailure, EpochNo](
            EpochNo.fromBigInt(epoch).leftMap(TickFailure.Vocab(_))
          )
          _ <- DijkstraNewEpoch.sts.transition[F]((), current)
          _ <- st.modify(s =>
            s.copy(nes =
              s.nes.copy(es = s.nes.es.copy(ls = NodeTick.adoptGenesisDelegs(s.nes.es.ls, now)))
            )
          )
          _ <- {
            given Embed[Option[PulsingRewUpdate], NewEpochGovState[
              Dijkstra
            ], TickFailure, TickFailure] =
              Embed(NewEpochGovState.ru[Dijkstra], identity[TickFailure])
            Sts.trans[
              F,
              RupdEnv[Dijkstra],
              Option[PulsingRewUpdate],
              NewEpochGovState[Dijkstra],
              SlotNo,
              TickFailure,
              TickFailure
            ](
              Rupd.sts[Dijkstra],
              RupdEnv(before.nes.bprev, StoredGov.synced(before.nes.es, before.gov)),
              slot
            )
          }
        yield ()

  /** [[sts]] on the node's new-epoch state, its governance state taken out before the tick and put
    * back after it ([[StoredGov.onRecord]]): Dijkstra's tick on the node's path.
    */
  val onRecord: Sts[Unit, NewEpochState[Dijkstra], SlotNo, TickFailure] =
    StoredGov.onRecord[NewEpochState[Dijkstra], NewEpochGovState[Dijkstra], SlotNo](
      sts,
      NewEpochGovState.record,
      NewEpochGovState(_, _),
      s => (s.nes, s.gov)
    )
