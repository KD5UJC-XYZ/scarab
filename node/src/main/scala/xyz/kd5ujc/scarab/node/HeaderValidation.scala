// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.consensus.HeaderRules.{ForecastReason, Outcome, Prepared}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, ProtocolState}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, Globals, Hfc}
import xyz.kd5ujc.scarab.ledger.state.{BlockNo, EraTag, ShelleyBasedEra, SlotNo, Word64, WithOrigin}
import xyz.kd5ujc.scarab.node.read.{ChainRead, EraRead}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}

/** The Shelley ledger forecast shared by every header-validation mode. Parameters and envelope
  * checks come from the same forecast ledger view, including at an epoch boundary. The native
  * forecast horizon is exclusive; a missing forecast waits for ledger progress rather than
  * accepting an unchecked header.
  */
private[node] final class ShelleyHeaderForecast[S](config: PraosConfig, globals: LedgerGlobals)(
    using read: ChainRead[S]
):
  /** @param afterVoting
    *   the blocks of the ledger's epoch after its voting deadline, which decide with its definite
    *   update whether the next epoch is of the next era ([[HardForkConfig.eraOf]])
    * @param parent
    *   the header the checked one extends, the tip of `prev` (none at the chain's origin)
    */
  private[node] def prepareShelley[E <: EraTag](
      anchor: S,
      ledger: EraRead[E],
      afterVoting: Long,
      tip: Option[BlockRef],
      parent: Option[BlockRef],
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
    Praos2Headers
      .of(glob.referenceBuild)
      .flatMap(
        _.prepare(
          cfg,
          Globals(glob, geometry),
          ledger,
          afterVoting,
          tip,
          parent,
          prev,
          h,
          genericEnvelope
        )
      )
      .getOrElse(
        prepareInEra(ledger, afterVoting, tip, prev, h, forecastLimit, genericEnvelope, cfg, glob)
      )

  /** The header rules of the eras before Dijkstra, and of Dijkstra in a build that does not run
    * Praos2 for it ([[Praos2Headers.of]]).
    */
  private def prepareInEra[E <: EraTag](
      ledger: EraRead[E],
      afterVoting: Long,
      tip: Option[BlockRef],
      prev: ProtocolState,
      h: Header.Shelley,
      forecastLimit: Option[BigInt],
      genericEnvelope: Either[HeaderError, Unit],
      cfg: PraosConfig,
      glob: LedgerGlobals
  ): HeaderRules.Prepared[ProtocolState] =
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
        tip.fold(geometry.stabilityWindow.toBigInt)(t =>
          t.slot + 1 + geometry.stabilityWindow.toBigInt
        )
      )
    then
      HeaderRules.Prepared.Unknown(
        HeaderRules.ForecastReason.Horizon(h.slot, tipSlot)
      )
    else
      // `futureForecast` ticks with TICKF (cardano-ledger f649f975, `Shelley/Rules/Tick.hs:185-242`,
      // `Conway/Rules/Tickf.hs:34-66`; forecast range in consensus 82ecba32
      // `Shelley/Ledger/SupportsProtocol.hs:51-77, 100-125`): only into the next epoch does it take
      // the mark distribution and the next parameters; for a later epoch the current ones stay
      // (`curEpochNo /= succ (nesEL nes)`), and the genesis delegations are adopted at the slot.
      val nextEpoch = epoch == ledgerEpoch + 1
      val params =
        if nextEpoch then ledger.paramsNextEpoch(glob)
        else ledgerParams
      val pools = ledger.leaderPools(nextEpoch = nextEpoch)
      val expected = cfg.forks.eraOf(geometry.k)(
        Hfc.Pending(
          EraNes.eraOf(ledger.era),
          ledgerEpoch,
          ledger.definiteUpdateMajor,
          afterVoting
        ),
        epoch
      )
      val envelope =
        cfg.forks
          .check(
            h.era,
            expected,
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
        if h.era.ordinal >= Era.Conway.ordinal then geometry.randomnessStabilisationWindow.toBigInt
        else geometry.stabilityWindow.toBigInt
      val delegations = ledger.genDelegsAt(h.slot)
      HeaderRules.Prepared.Check(() =>
        val checked: Either[HeaderError | ProviderDefect, ProtocolState] =
          genericEnvelope.flatMap(_ => envelope).flatMap { _ =>
            if h.era.usesTPraos then
              // TPraos runs through Alonzo, whose `ppDG` is the parameter at every version
              // (`Alonzo/PParams.hs:338`, cardano-ledger f649f975); Babbage runs Praos.
              val view = LedgerView(params.d, params.extraEntropy, delegations, pools)
              TPraos
                .update(Praos.tick(prev, h.era, epoch, params.extraEntropy), h, view, cfg)
                .map(_.state)
                .leftMap[HeaderError | ProviderDefect] {
                  case defect: ProviderDefect => defect
                  case error: TPraosError     => HeaderError.TPraos(error)
                }
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
                .leftMap[HeaderError | ProviderDefect] {
                  case defect: ProviderDefect => defect
                  case error: PraosError      => HeaderError.Praos(error)
                }
          }
        HeaderRules.outcome(checked)
      )

/** The header rules of a Dijkstra header in the build whose Dijkstra runs Praos2, the Leios build
  * (`type BlockProtocol (ShelleyBlock (Praos2 c) DijkstraEra)`, ouroboros-consensus 2416493b
  * `Cardano/Block.hs:277`): the ledger view forecast from the anchor, Conway's across the boundary
  * ([[DijkstraCrossing.forecast]]) or Dijkstra's own ([[DijkstraCrossing.forecastInEra]]), the
  * chain-dependent state translated into Praos2's where it is still Praos's
  * ([[DijkstraCrossing.chainDepState]]) and ticked by Praos2, then `validateHeader`: the envelope
  * checks of every era, then Praos2's envelope and protocol rules ([[Praos2.validate]];
  * `HeaderValidation.hs:374-386, 518-526`), as the ChainSync client runs them on each header
  * (`MiniProtocol/ChainSync/Client.hs:1934-1990`). The era match of the hard-fork combinator
  * (`HardFork/Combinator/Ledger.hs:395-405`) holds on this path: it is taken only where the
  * anchor's era history has the header's slot in Dijkstra; a Dijkstra header elsewhere goes the way
  * of every other era's, which refuses it as of the wrong era.
  */
private[node] final class Praos2Headers private ():
  /** The prepared check of `h`, or none where the header is not a Dijkstra header the anchor's era
    * history places in Dijkstra.
    *
    * @param tip
    *   the anchor's tip, from which the ledger view is forecast
    * @param parent
    *   the header `h` extends, the tip of the chain-dependent state `prev`
    */
  def prepare[E <: EraTag](
      cfg: PraosConfig,
      globals: Globals,
      ledger: EraRead[E],
      afterVoting: Long,
      tip: Option[BlockRef],
      parent: Option[BlockRef],
      prev: ProtocolState,
      h: Header.Shelley,
      genericEnvelope: Either[HeaderError, Unit]
  ): Option[Prepared[ProtocolState]] =
    val geometry = cfg.geometry
    val epoch = geometry.epochOf(h.slot)
    val tipSlot = tip.fold(BigInt(-1))(_.slot)
    val horizon = (f: CrossingForecastFailure) =>
      f match
        case _: CrossingForecastFailure.OutsideForecastRange =>
          Prepared.Unknown(ForecastReason.Horizon(h.slot, tipSlot))
        case other @ (_: CrossingForecastFailure.Translation |
            _: CrossingForecastFailure.Parameter | _: CrossingForecastFailure.Vocabulary) =>
          Prepared.Unknown(ForecastReason.DijkstraView(other))
    // the view of the header's slot, where the anchor's era history has it in Dijkstra
    val view: Option[Either[Prepared[ProtocolState], Praos2.LedgerView]] =
      Option.when(h.era == Era.Dijkstra)(ledger.era).flatMap {
        case ShelleyBasedEra.SDijkstra =>
          Some(
            forecastFor(h, tip).flatMap { (slot, at) =>
              ledger.nes
                .leftMap(e =>
                  Prepared.Invalid(HeaderError.Structure(HeaderStructureProblem.LedgerRead(e)))
                )
                .flatMap(nes =>
                  DijkstraCrossing.forecastInEra(globals, slot, at, nes).leftMap(horizon)
                )
            }
          )
        case ShelleyBasedEra.SConway =>
          cfg.forks
            .transition(geometry.k)(
              Hfc
                .Pending(Era.Conway, ledger.epoch.toBigInt, ledger.definiteUpdateMajor, afterVoting)
            )
            .filter(_ <= epoch)
            .map { first =>
              for
                at <- forecastFor(h, tip)
                (slot, anchorTip) = at
                transition <- SlotNo
                  .fromBigInt(geometry.firstSlot(first))
                  .leftMap(e =>
                    Prepared.Unknown(ForecastReason.Praos2Defect(Praos2.Defect.Vocabulary(e)))
                  )
                nes <- ledger.nes.leftMap(e =>
                  Prepared.Invalid(HeaderError.Structure(HeaderStructureProblem.LedgerRead(e)))
                )
                view <- DijkstraCrossing
                  .forecast(globals, globals, transition, slot, anchorTip, nes)
                  .leftMap(horizon)
              yield view
            }
        // a header two eras ahead of its anchor: every other era's path refuses it as of the
        // wrong era, or waits for the anchor's forecast range
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
            ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
          None
      }
    view.map(_.fold(identity, check(cfg, _, parent, prev, h, epoch, genericEnvelope)))

  /** The slot to forecast for and the anchor's tip, as the forecast takes them. */
  private def forecastFor(
      h: Header.Shelley,
      tip: Option[BlockRef]
  ): Either[Prepared[ProtocolState], (SlotNo, WithOrigin[SlotNo])] =
    val defect = (e: xyz.kd5ujc.scarab.ledger.state.VocabError) =>
      Prepared.Unknown(ForecastReason.Praos2Defect(Praos2.Defect.Vocabulary(e)))
    for
      slot <- SlotNo.fromBigInt(h.slot).leftMap(defect)
      at <- tip.traverse(t => SlotNo.fromBigInt(t.slot)).leftMap(defect)
    yield (slot, at.fold[WithOrigin[SlotNo]](WithOrigin.Origin)(WithOrigin.At(_)))

  private def check(
      cfg: PraosConfig,
      view: Praos2.LedgerView,
      parent: Option[BlockRef],
      prev: ProtocolState,
      h: Header.Shelley,
      epoch: BigInt,
      genericEnvelope: Either[HeaderError, Unit]
  ): Prepared[ProtocolState] =
    val config = Praos2.Config(
      cfg,
      Word64.fromUnsigned(cfg.forks.maxMajorProtVer.toLong),
      Praos2.EpochInfo.fromGeometry(cfg.geometry)
    )
    // `translateChainDepState` of `TranslateProto (Praos c) (Praos2 c)`, and of TPraos through
    // Praos (`Protocol/Praos2.hs:189-209`): the state of the era before, carried over
    val translated = prev.in(Era.Conway)
    Prepared.Check(() =>
      val checked: Either[Outcome[ProtocolState], ProtocolState] =
        for
          state <- translated.dep match
            case ChainDepState.Praos2(p) => Right(p)
            case ChainDepState.Praos(p)  => Right(DijkstraCrossing.chainDepState(p))
            case ChainDepState.TPraos(_) | ChainDepState.PBft(_) =>
              Left(Outcome.Invalid(HeaderError.Praos(PraosError.WrongState(h.era))))
          header <- LeiosHeader
            .decode(h.raw)
            .leftMap(e =>
              Outcome.Invalid(HeaderError.Structure(HeaderStructureProblem.LeiosHeaderUnread(e)))
            )
          at <- parent
            .traverse(p =>
              (BlockNo.fromBigInt(p.blockNo), SlotNo.fromBigInt(p.slot))
                .mapN(Praos2.Tip(p.hash, _, _))
            )
            .leftMap(e => Outcome.Later(ForecastReason.Praos2Defect(Praos2.Defect.Vocabulary(e))))
          slot <- SlotNo
            .fromBigInt(h.slot)
            .leftMap(e => Outcome.Later(ForecastReason.Praos2Defect(Praos2.Defect.Vocabulary(e))))
          ticked <- Praos2
            .tick(config, view, slot, Praos2.ChainState(at, state))
            .leftMap(d => Outcome.Later(ForecastReason.Praos2Defect(d)))
          _ <- genericEnvelope.leftMap(Outcome.Invalid(_))
          next <- Praos2.validate(config, header, ticked).leftMap {
            case e: Praos2Error    => Outcome.Invalid(HeaderError.Praos2(e))
            case d: Praos2.Defect  => Outcome.Later(ForecastReason.Praos2Defect(d))
            case p: ProviderDefect => Outcome.Unverifiable(p)
          }
        yield ProtocolState(
          ChainDepState.Praos2(next.protocol),
          translated.extras.copy(epoch = epoch)
        )
      checked.fold(identity, Outcome.Valid(_))
    )

private[node] object Praos2Headers:
  /** The rules of a Dijkstra header in `build`: Praos2's in the Leios build, whose Dijkstra header
    * is the Leios header (`ShelleyProtocolHeader (Praos2 c) = Leios.Header c`,
    * `Protocol/Praos2.hs:57`); none in the release lines, whose Dijkstra runs Praos on Conway's
    * header and which this node never enters (their Dijkstra headers keep the refusal of every
    * other era's path).
    */
  def of(build: ReferenceBuild): Option[Praos2Headers] = build match
    case ReferenceBuild.LeiosB004fe0c                            => Some(Praos2Headers())
    case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 => None
