// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.ProtocolParams
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayGovState,
  FuturePParams,
  GovStateBases,
  GovStateReadError,
  GovStateRecord,
  ProposalsWriteError
}
import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraEpoch, EpochGovState}
import xyz.kd5ujc.scarab.ledger.rules.{
  Globals,
  NewEpoch,
  Rewards,
  Rupd,
  RupdEnv,
  Sts,
  Tick,
  TickFailure
}
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  ConwayGovState as StoredConwayGovState,
  Dijkstra,
  EpochNo,
  EpochState,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo
}

/** Dijkstra's TICK with the era's EPOCH ([[DijkstraEpoch]]) in the place of the node's, for the
  * suites that bind the era's EPOCH to the Leios build's TICK. It is the node's TICK ([[Tick.sts]],
  * [[NewEpoch.sts]]) with the governance state held as the era's record beside the node's state:
  * `solidifyFuturePParams` from the slot of no return; inside an epoch, `predictFuturePParams`; at
  * the boundary the reward update, the era's EPOCH and the new-epoch fields; then the genesis
  * delegations and RUPD. The node's rules that read the governance state, the reward update and
  * RUPD, read only its previous parameters, which [[synced]] writes from the era's record before
  * each reads them. Not the node's: wiring the tick is #85's next stage.
  */
object EraDijkstraTick:
  private given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra

  /** A Dijkstra state with its governance state as the era's record. */
  final case class State(nes: NewEpochState[Dijkstra], gov: ConwayGovState[Dijkstra])

  /** The node's record of the governance state of `es` with the current and previous parameters of
    * `gov`, each written over the record's own: what the node's rules of the tick read of it.
    */
  def synced(es: EpochState[Dijkstra], gov: ConwayGovState[Dijkstra]): EpochState[Dijkstra] =
    val flat: StoredConwayGovState = es.ls.utxo.gov
    def stored(pp: xyz.kd5ujc.scarab.ledger.era.PParams[Dijkstra], base: ProtocolParams) =
      GovStateRecord.dijkstra.storedPParams(pp, base)
    val g = flat.copy(
      curPParams = stored(gov.cgsCurPParams, flat.curPParams),
      prevPParams = stored(gov.cgsPrevPParams, flat.prevPParams)
    )
    es.copy(ls = es.ls.copy[Dijkstra](utxo = es.ls.utxo.copy[Dijkstra](gov = g)))

  /** The state as the node records it: the era's governance state written to the node's record over
    * `bases`, or the action the record cannot hold.
    */
  def stored(
      s: State,
      bases: GovStateBases
  ): Either[ProposalsWriteError, NewEpochState[Dijkstra]] =
    GovStateRecord
      .toStored[Dijkstra](s.gov, bases)
      .map(g =>
        s.nes.copy(es =
          s.nes.es
            .copy(ls = s.nes.es.ls.copy[Dijkstra](utxo = s.nes.es.ls.utxo.copy[Dijkstra](gov = g)))
        )
      )

  /** NEWEPOCH's boundary (`Conway/Rules/NewEpoch.hs:153-184`, cardano-ledger 9df9f98c), as
    * [[NewEpoch.sts]] runs it, with the era's EPOCH.
    */
  private def boundary(
      globals: Globals,
      before: State,
      epoch: EpochNo
  ): Either[TickFailure, State] =
    for
      rewarded <- before.nes.ru match
        case None => Right(before.nes.es)
        case Some(pending) =>
          Rewards
            .complete(pending)
            .flatMap(NewEpoch.applyRewardUpdate(synced(before.nes.es, before.gov), _))
            .leftMap(TickFailure.Vocab(_))
      after <- Sts(DijkstraEpoch.sts)(globals, (), EpochGovState(rewarded, before.gov), epoch)
    yield State(
      before.nes.copy(
        epoch = epoch,
        bprev = before.nes.bcur,
        bcur = BlocksMade.empty,
        es = after.es,
        ru = None
      ),
      after.gov
    )

  /** TICK (`Shelley/Rules/Tick.hs:146-170`, cardano-ledger 9df9f98c), as [[Tick.sts]] runs it. */
  def tick(globals: Globals, before: State, slot: SlotNo): Either[TickFailure, State] =
    val now = slot.toBigInt
    val epoch = globals.epochOf(now)
    val solid =
      if now < globals.slotOfNoReturn(epoch) then before.gov
      else
        before.gov.copy(cgsFuturePParams =
          FuturePParams.solidifyFuturePParams(before.gov.cgsFuturePParams)
        )
    for
      current <- EpochNo.fromBigInt(epoch).leftMap(TickFailure.Vocab(_))
      ticked <-
        if before.nes.epoch.next.contains(current) then
          boundary(globals, State(before.nes, solid), current)
        else Right(State(before.nes, ConwayGovState.predictFuturePParams(solid)))
      adopted = ticked.nes.copy(es =
        ticked.nes.es.copy(ls = Tick.adoptGenesisDelegs(ticked.nes.es.ls, now))
      )
      ru <- Sts(Rupd.sts[Dijkstra])(
        globals,
        RupdEnv(before.nes.bprev, synced(before.nes.es, before.gov)),
        adopted.ru,
        slot
      )
    yield State(adopted.copy(ru = ru), ticked.gov)

  /** The node's record of the governance state as the era's, read by the era's reader. */
  def read(nes: NewEpochState[Dijkstra]): Either[GovStateReadError, State] =
    GovStateRecord
      .fromStored[Dijkstra](nes.es.ls.utxo.gov)
      .map(State(nes, _))

  /** The flat record's parameter positions of `nes`, the bases its era record is written over. */
  def bases(nes: NewEpochState[Dijkstra]): GovStateBases = GovStateBases.of(nes.es.ls.utxo.gov)
