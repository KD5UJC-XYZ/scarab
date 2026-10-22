// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.EraTag
import xyz.kd5ujc.scarab.node.read.{ChainRead, EraRead}
import xyz.kd5ujc.scarab.protocol.Era

/** The Shelley ledger forecast shared by every header-validation mode. Parameters and envelope
  * checks come from the same forecast ledger view, including at an epoch boundary. The native
  * forecast horizon is exclusive; a missing forecast waits for ledger progress rather than
  * accepting an unchecked header.
  */
private[node] final class ShelleyHeaderForecast[S](config: PraosConfig, globals: LedgerGlobals)(
    using read: ChainRead[S]
):
  private[node] def prepareShelley[E <: EraTag](
      anchor: S,
      ledger: EraRead[E],
      tip: Option[BlockRef],
      prev: ProtocolState,
      h: Header.Shelley,
      forecastLimit: Option[BigInt] = None,
      genericEnvelope: Either[HeaderError, Unit] = Right(())
  ): HeaderRules.Prepared[ProtocolState] =
    val firstShelley = anchor.consensus.byron.firstShelleyEpoch
    val cfg =
      firstShelley.fold(config)(first => config.copy(geometry = config.geometry.resolved(first)))
    val glob = firstShelley.fold(globals)(first => globals.copy(firstEpoch = first))
    val geometry = cfg.geometry
    val ledgerEpoch = ledger.epoch.toBigInt
    val ledgerParams = ledger.params
    val epoch = geometry.epochOf(h.slot)
    val tipSlot = tip.fold(BigInt(0))(_.slot)
    if epoch < ledgerEpoch then
      HeaderRules.Prepared.Invalid(
        HeaderError.Structure(HeaderStructureProblem.EpochPrecedes(epoch, ledgerEpoch))
      )
    else if h.slot >= forecastLimit.getOrElse(
        tip.fold(geometry.stabilityWindow)(t => t.slot + 1 + geometry.stabilityWindow)
      ) || epoch > ledgerEpoch + 1
    then
      HeaderRules.Prepared.Unknown(
        HeaderRules.ForecastReason.Horizon(h.slot, tipSlot)
      )
    else
      val sameEpoch = epoch == ledgerEpoch
      val params =
        if sameEpoch then ledgerParams
        else ledger.paramsNextEpoch(glob)
      val pools = ledger.leaderPools(nextEpoch = !sameEpoch)
      val envelope =
        cfg.forks
          .check(
            h.era,
            epoch,
            h.protocolVersion._1,
            params.protocolMajor,
            h.raw.size,
            params.maxBlockHeaderSize,
            h.bodySize,
            params.maxBlockBodySize,
            mainnet = glob.network == 1
          )
          .leftMap(HeaderError.Envelope(_))
      val nonceWindow =
        if h.era.ordinal >= Era.Conway.ordinal then geometry.randomnessStabilisationWindow
        else geometry.stabilityWindow
      val delegations = ledger.genDelegsAt(h.slot)
      HeaderRules.Prepared.Check(() =>
        val checked: Either[HeaderError, ProtocolState] =
          genericEnvelope.flatMap(_ => envelope).flatMap { _ =>
            if h.era.usesTPraos then
              val view = LedgerView(
                params.effectiveD,
                params.extraEntropy,
                delegations,
                StakeView.Known(pools)
              )
              TPraos
                .update(Praos.tick(prev, h.era, epoch, params.extraEntropy), h, view, cfg)
                .map(_.state)
                .leftMap(HeaderError.TPraos(_))
            else
              Praos
                .validate(
                  Praos.tick(prev, h.era, epoch),
                  h,
                  StakeView.Known(pools),
                  cfg,
                  nonceWindow
                )
                .map(_._1)
                .leftMap(HeaderError.Praos(_))
          }
        checked.fold(Outcome.Invalid(_), Outcome.Valid(_))
      )
