// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{BlockRef, PraosConfig}
import xyz.kd5ujc.scarab.consensus.HeaderRules.{ForecastReason, TimeForecast}
import xyz.kd5ujc.scarab.consensus.state.{EraHistory, MirrorState}
import xyz.kd5ujc.scarab.protocol.Era

/** HeaderInFutureCheck's ledger-local hard-fork-history query. Unlike the header ledger-view
  * forecast, history rounds an unknown safe horizon to the epoch boundary and includes the next era
  * when its transition is known (Client.hs:1798; State/Infra.hs:199-312 at 82ecba329).
  */
trait HeaderTimeRead[S]:
  def forecast(
      state: S,
      tip: Option[BlockRef],
      slot: BigInt,
      config: PraosConfig,
      byron: Option[xyz.kd5ujc.scarab.consensus.ByronRules.Config]
  ): TimeForecast

object HeaderTimeRead:
  given HeaderTimeRead[MirrorState] = mirror(None)

  def mirror(byronHistory: EraHistory.ByronParams): HeaderTimeRead[MirrorState] =
    mirror(Some(byronHistory))

  private def mirror(byronHistory: Option[EraHistory.ByronParams]): HeaderTimeRead[MirrorState] =
    new HeaderTimeRead[MirrorState]:
      def forecast(
          state: MirrorState,
          tip: Option[BlockRef],
          slot: BigInt,
          config: PraosConfig,
          byron: Option[xyz.kd5ujc.scarab.consensus.ByronRules.Config]
      ): TimeForecast =
        val telescope = state.ext.ledgerState
        val geometry = config.geometry
        val transition = GenesisHistory.transitionEpoch(state, config, byron)
        val history = byronHistory.getOrElse(
          EraHistory.ByronParams(
            byron.fold(geometry.byron.epochSlots)(_.ledger.epochSlots),
            BigInt(geometry.byron.slotLengthMillis),
            byron.fold(BigInt(geometry.securityParam))(r => BigInt(r.ledger.genesis.securityParam))
          )
        )
        val available = GenesisHistory.query(
          telescope.past,
          GenesisHistory.Current(telescope.era, telescope.current.start, transition),
          tip.fold(BigInt(-1))(_.slot),
          slot,
          GenesisHistory.parameters(geometry, history)
        )
        if available.isEmpty then
          TimeForecast.Unknown(ForecastReason.Horizon(slot, tip.fold(BigInt(-1))(_.slot)))
        else
          val firstShelley = telescope.past.headOption
            .map(_.end.epoch)
            .orElse(Option.when(telescope.era == Era.Byron)(transition).flatten)
          val resolved = firstShelley.fold(geometry)(geometry.resolved)
          if resolved.byron.derived && telescope.era != Era.Byron then
            TimeForecast.Unknown(ForecastReason.ShelleyLedgerNotReady)
          else
            val start = BigInt(resolved.systemStart.getEpochSecond) * BigInt(10).pow(12) +
              BigInt(resolved.systemStart.getNano) * 1000
            val offset =
              if firstShelley.isEmpty && telescope.era == Era.Byron then
                slot * geometry.byron.slotLengthMillis
              else resolved.slotOffsetMillis(slot)
            TimeForecast.Known(start + offset * 1000000000)
