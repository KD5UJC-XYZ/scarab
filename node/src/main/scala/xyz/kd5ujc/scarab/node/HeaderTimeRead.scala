// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{BlockRef, ForkTrigger, HardForkConfig, PraosConfig}
import xyz.kd5ujc.scarab.consensus.HeaderRules.{ForecastReason, TimeForecast}
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState, ShelleyLedgerState}
import xyz.kd5ujc.scarab.ledger.byron.ByronLedger
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
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
  given HeaderTimeRead[MirrorState] with
    def forecast(
        state: MirrorState,
        tip: Option[BlockRef],
        slot: BigInt,
        config: PraosConfig,
        byron: Option[xyz.kd5ujc.scarab.consensus.ByronRules.Config]
    ): TimeForecast =
      val telescope = state.ext.ledgerState
      val geometry = config.geometry
      val eras = Era.Byron +: HardForkConfig.Sequence
      val transition = telescope.state match
        case EraLedgerState.ByronCurrent(old) =>
          byron.flatMap(r =>
            r.configuredShelleyEpoch.orElse(old.ledger.flatMap(ByronLedger.transition(r.ledger, _)))
          )
        case EraLedgerState.InShelleyEra(era, ledger) =>
          nextTransition(ledger, telescope.era, config)(using era)
      val parameters = eras
        .map(era =>
          era -> (if era == Era.Byron then
                    GenesisHistory.Parameters(
                      byron.fold(geometry.byron.epochSlots)(_.ledger.epochSlots),
                      2 * byron
                        .fold(BigInt(geometry.securityParam))(r =>
                          BigInt(r.ledger.genesis.securityParam)
                        ),
                      2 * byron.fold(BigInt(geometry.securityParam))(r =>
                        BigInt(r.ledger.genesis.securityParam)
                      )
                    )
                  else
                    GenesisHistory.Parameters(
                      geometry.epochLength,
                      geometry.stabilityWindow,
                      geometry.stabilityWindow
                    ))
        )
        .toMap
      val available = GenesisHistory.query(
        telescope.past,
        GenesisHistory.Current(telescope.era, telescope.current.start, transition),
        tip.fold(BigInt(-1))(_.slot),
        slot,
        parameters
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

  private def nextTransition[E <: EraTag](
      ledger: ShelleyLedgerState[E],
      current: Era,
      config: PraosConfig
  )(using ShelleyBasedEra[E]): Option[BigInt] =
    val eras = Era.Byron +: HardForkConfig.Sequence
    eras
      .lift(eras.indexOf(current) + 1)
      .flatMap(next =>
        config.forks.triggers.get(next).flatMap {
          case ForkTrigger.AtEpoch(epoch) => Some(epoch)
          case ForkTrigger.AtVersion(major) =>
            EraLedger
              .futurePParams(ledger.nes.es.ls.utxo.gov)
              .definite
              .filter(p =>
                p.protocolMajor == major && ledger.transition.afterVoting.toLong >= config.geometry.securityParam
              )
              .map(_ => ledger.nes.epoch.toBigInt + 1)
        }
      )
