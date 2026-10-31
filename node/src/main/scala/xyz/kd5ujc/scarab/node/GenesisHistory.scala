// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{ByronRules, ForkTrigger, HardForkConfig, PraosConfig}
import xyz.kd5ujc.scarab.consensus.state.{
  Bound,
  EraHistory,
  EraLedgerState,
  MirrorState,
  Past,
  ShelleyLedgerState
}
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.byron.ByronLedger
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era

/** The immutable ledger's hard-fork history for GDD's slotToGenesisWindow query.
  *
  * Mirrors State/Infra.hs:199-312 reconstructSummary and History/Qry.hs EGenesisWindow: a known
  * transition exposes exactly one future era; an unknown transition exposes the current era only up
  * to the epoch-rounded safe horizon. The synthetic open Byron span is never used.
  */
object GenesisHistory:
  final case class Parameters(epochSlots: BigInt, safeZone: BigInt, genesisWindow: BigInt)
  final case class Current(era: Era, start: Bound, transitionEpoch: Option[BigInt])

  private val eras = Era.Byron +: HardForkConfig.Sequence.toVector

  /** `immutableTipSlot = -1` denotes Origin, whose successor is slot zero. */
  def window(
      state: MirrorState,
      immutableTipSlot: BigInt,
      slot: BigInt,
      setup: NodeSetup
  ): Option[BigInt] =
    val telescope = state.ext.ledgerState
    val transition = transitionEpoch(state, setup.praos, setup.byronRules)
    query(
      telescope.past,
      Current(telescope.era, telescope.current.start, transition),
      immutableTipSlot,
      slot,
      parameters(setup.geometry, setup.byronHistory)
    )

  private[node] def parameters(
      geometry: EpochGeometry,
      byron: EraHistory.ByronParams
  ): Map[Era, Parameters] =
    eras.map { era =>
      era -> (if era == Era.Byron then Parameters(byron.epochSlots, 2 * byron.k, 2 * byron.k)
              else
                Parameters(
                  geometry.epochLength,
                  geometry.stabilityWindow.toBigInt,
                  geometry.stabilityWindow.toBigInt
                ))
    }.toMap

  private[node] def transitionEpoch(
      state: MirrorState,
      config: PraosConfig,
      byron: Option[ByronRules.Config]
  ): Option[BigInt] =
    val telescope = state.ext.ledgerState
    telescope.state match
      case EraLedgerState.ByronCurrent(old) =>
        byron.flatMap(rules =>
          rules.configuredShelleyEpoch.orElse(
            old.ledger.flatMap(ByronLedger.transition(rules.ledger, _))
          )
        )
      case EraLedgerState.InShelleyEra(era, ledger) =>
        shelleyTransition(ledger, telescope.era, config)(using era)

  private def shelleyTransition[E <: EraTag](
      ledger: ShelleyLedgerState[E],
      current: Era,
      config: PraosConfig
  )(using ShelleyBasedEra[E]): Option[BigInt] =
    eras.lift(eras.indexOf(current) + 1).flatMap { next =>
      config.forks.triggers.get(next).flatMap {
        case ForkTrigger.AtEpoch(epoch)   => Some(epoch)
        case ForkTrigger.AtVersion(major) =>
          // ShelleyHFC.hs:194-227 and State/Governance.hs:131: potential parameters do not
          // establish a transition, even if evaluating them could predict the same version.
          EraLedger
            .futurePParams(ledger.nes.es.ls.utxo.gov)
            .definite
            .filter(p =>
              p.protocolMajor == major && BigInt(
                ledger.transition.afterVoting.toLong
              ) >= config.geometry.k
            )
            .map(_ => ledger.nes.epoch.toBigInt + 1)
      }
    }

  /** Pure reconstruction inputs, also consumed by the native history boundary corpus. */
  def query(
      past: Vector[Past],
      current: Current,
      immutableTipSlot: BigInt,
      slot: BigInt,
      parameters: Map[Era, Parameters]
  ): Option[BigInt] =
    if slot < 0 then None
    else
      val historical = past.zip(eras).collectFirst {
        case (p, era) if slot >= p.start.slot && slot < p.end.slot => era
      }
      historical.flatMap(parameters.get).map(_.genesisWindow).orElse {
        parameters.get(current.era).flatMap { params =>
          current.transitionEpoch match
            case Some(epoch) =>
              val end = current.start.slot + (epoch - current.start.epoch) * params.epochSlots
              if slot >= current.start.slot && slot < end then Some(params.genesisWindow)
              else
                eras.lift(eras.indexOf(current.era) + 1).flatMap(parameters.get).flatMap { next =>
                  Option.when(slot >= end && slot < horizon(end, end, next))(next.genesisWindow)
                }
            case None =>
              Option.when(
                slot >= current.start.slot && slot < horizon(
                  current.start.slot,
                  immutableTipSlot + 1,
                  params
                )
              )(params.genesisWindow)
        }
      }

  private def horizon(start: BigInt, from: BigInt, params: Parameters): BigInt =
    val slots = from + params.safeZone - start
    start + ((slots + params.epochSlots - 1) / params.epochSlots) * params.epochSlots
