// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.state.EraTag
import xyz.kd5ujc.scarab.node.read.{ChainRead, EraRead, InEra}
import xyz.kd5ujc.scarab.ledger.Header
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.ledger.HeaderError
import xyz.kd5ujc.scarab.ledger.LedgerView
import xyz.kd5ujc.scarab.ledger.StakeView
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import xyz.kd5ujc.scarab.ledger.*

/** Header-first validation for the node's full ledger (G6): the checks [[NodeRules]] makes on a
  * header when it applies the block, made against a forecast of the ledger view instead of the
  * state after the block's parent, so they can run before the block is fetched.
  *
  * The forecast from a ledger state `anchor` is exact for any header up to the stability window
  * (3k/f slots) after the anchor's tip, the horizon within which the ledger view cannot be changed
  * by blocks not yet seen (the same bound as upstream's `forecastFor`):
  *
  *   - in the anchor's epoch the pool distribution, protocol parameters and genesis delegations are
  *     those of the anchor (a genesis delegation that becomes due on the way is adopted, as the
  *     ledger's TICK does);
  *   - in the next epoch the pool distribution is the anchor's newest stake snapshot (`mark`),
  *     which the boundary only rotates into place, and the epoch nonce follows from the header
  *     state. The TPraos parameters (`d`, extra entropy) are those the pending update proposals
  *     adopt at the boundary. The envelope checks that depend on the protocol parameters are left
  *     to the block's own validation for a header of a later epoch, because the boundary (which can
  *     enact a protocol-version change) is not run for a forecast.
  *
  * Byron headers and the first Shelley header after a Byron prefix are reported
  * [[Outcome.Unchecked]]: the Byron rules are applied with the block, and the Shelley ledger does
  * not exist before that block is applied.
  *
  * Verdicts of the signature checks come from `config.verdicts`; the node gives it a view that
  * keeps them for the block's own validation ([[VerificationCache.forHeaders]]), so nothing is
  * verified twice.
  */
final class NodeHeaderRules[S](config: PraosConfig, globals: LedgerGlobals)(using
    read: ChainRead[S]
) extends HeaderRules[S, ProtocolState]:

  def at(state: S): ProtocolState = state.protocol

  def validate(
      anchor: S,
      tip: Option[BlockRef],
      prev: ProtocolState,
      header: Header
  ): Outcome[ProtocolState] = header match
    case _: Header.Byron =>
      Outcome.Unchecked(HeaderRules.UncheckedReason.Byron, Some(prev))
    case h: Header.Shelley =>
      val byron = anchor.consensus.byron
      if byron.ledger.isDefined && byron.firstShelleyEpoch.isEmpty then
        // the anchor is a Byron state: no Shelley ledger to forecast from
        if tip.exists(t => h.prevHash.contains(t.hash)) then
          Outcome.Unchecked(
            HeaderRules.UncheckedReason.FirstShelley,
            None
          )
        else Outcome.Later(HeaderRules.ForecastReason.ShelleyLedgerNotReady)
      else
        anchor.ledger(tip) match
          case Left(refused) =>
            Outcome.Invalid(HeaderError.Structure(HeaderStructureProblem.LedgerRead(refused)))
          case Right(None) =>
            Outcome.Later(HeaderRules.ForecastReason.ShelleyLedgerNotReady)
          case Right(Some(InEra.Of(_, ledger))) => shelley(anchor, ledger, tip, prev, h)

  private[node] def shelley[E <: EraTag](
      anchor: S,
      ledger: EraRead[E],
      tip: Option[BlockRef],
      prev: ProtocolState,
      h: Header.Shelley,
      strictEnvelope: Boolean = false,
      forecastLimit: Option[BigInt] = None,
      genericEnvelope: Either[HeaderError, Unit] = Right(())
  ): Outcome[ProtocolState] =
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
      Outcome.Invalid(
        HeaderError.Structure(HeaderStructureProblem.EpochPrecedes(epoch, ledgerEpoch))
      )
    else if (strictEnvelope && h.slot >= forecastLimit.getOrElse(
        tip.fold(geometry.stabilityWindow)(t => t.slot + 1 + geometry.stabilityWindow)
      )) || (!strictEnvelope && tip.isDefined && h.slot > tipSlot + geometry.stabilityWindow) ||
      epoch > ledgerEpoch + 1
    then
      Outcome.Later(
        HeaderRules.ForecastReason.Horizon(h.slot, tipSlot)
      )
    else
      val sameEpoch = epoch == ledgerEpoch
      val params =
        if sameEpoch || (!strictEnvelope && ledgerParams.protocolMajor >= 9) then ledgerParams
        else ledger.paramsNextEpoch(glob)
      val pools = ledger.leaderPools(nextEpoch = !sameEpoch)
      val envelope =
        if sameEpoch || strictEnvelope then
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
        else Right(())
      val nonceWindow =
        if h.era.ordinal >= Era.Conway.ordinal then geometry.randomnessStabilisationWindow
        else geometry.stabilityWindow
      val checked: Either[HeaderError, ProtocolState] =
        genericEnvelope.flatMap(_ => envelope).flatMap { _ =>
          if h.era.usesTPraos then
            val view = LedgerView(
              params.effectiveD,
              params.extraEntropy,
              ledger.genDelegsAt(h.slot),
              StakeView.Known(pools)
            )
            TPraos
              .update(Praos.tick(prev, h.era, epoch, params.extraEntropy), h, view, cfg)
              .map(_.state)
              .leftMap(HeaderError.TPraos(_))
          else
            Praos
              .validate(Praos.tick(prev, h.era, epoch), h, StakeView.Known(pools), cfg, nonceWindow)
              .map(_._1)
              .leftMap(HeaderError.Praos(_))
        }
      checked.fold(Outcome.Invalid(_), Outcome.Valid(_))

object NodeHeaderRules:
  def apply[S: ChainRead](config: PraosConfig, globals: LedgerGlobals): NodeHeaderRules[S] =
    new NodeHeaderRules[S](config, globals)
