// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.consensus.HeaderRules.{ForecastReason, Outcome, Prepared}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, ProtocolState}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronLedgerState}
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, Globals, Hfc}
import xyz.kd5ujc.scarab.ledger.state.{
  BlockNo,
  EraTag,
  ShelleyBasedEra,
  SlotNo,
  VocabError,
  Word64,
  WithOrigin
}
import xyz.kd5ujc.scarab.node.read.{ChainRead, EraRead}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32

/** The header state after a Shelley-based header: the protocol's, and the closure bound of the
  * ledger view it was validated against ([[LeiosBound]]).
  */
private[node] final case class ShelleyValidated(protocol: ProtocolState, leiosBound: LeiosBound)

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
  ): HeaderRules.Prepared[ShelleyValidated] =
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
          forecastLimit,
          genericEnvelope
        )
      )
      .getOrElse(
        prepareInEra(ledger, afterVoting, tip, prev, h, forecastLimit, genericEnvelope, cfg, glob)
          .map(ShelleyValidated(_, LeiosBound.WithoutLeios))
      )

  /** The closure bound of the view at the tip of `anchor` 's ledger ([[Praos2Headers.boundOfTip]]),
    * in the geometry [[prepareShelley]] validates with; a build without Praos2 has no Leios.
    */
  private[node] def boundOfTip[E <: EraTag](anchor: S, ledger: EraRead[E]): LeiosBound =
    val firstShelley = anchor.consensus.byron.firstShelleyEpoch
    val geometry = firstShelley.fold(config.geometry)(config.geometry.resolved(_))
    val glob = firstShelley.fold(globals)(first => globals.copy(firstEpoch = first))
    Praos2Headers
      .of(glob.referenceBuild)
      .fold(LeiosBound.WithoutLeios)(_.boundOfTip(Globals(glob, geometry), ledger, anchor.tipSlot))

  /** The bound on an endorser block's transactions forecast from the anchor for `slot`
    * ([[Praos2Headers.maxEbTxsSize]]), in the geometry [[prepareShelley]] validates with.
    */
  private[node] def maxEbTxsSize[E <: EraTag](
      rules: Praos2Headers,
      anchor: S,
      ledger: EraRead[E],
      afterVoting: Long,
      tip: Option[BlockRef],
      slot: BigInt
  ): Either[Praos2Headers.Unforecast, Word32] =
    val firstShelley = anchor.consensus.byron.firstShelleyEpoch
    val cfg =
      firstShelley.fold(config)(first => config.copy(geometry = config.geometry.resolved(first)))
    val glob = firstShelley.fold(globals)(first => globals.copy(firstEpoch = first))
    rules.maxEbTxsSize(cfg, Globals(glob, cfg.geometry), ledger, afterVoting, tip, slot)

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
  * `Cardano/Block.hs:277`). Every Dijkstra header of that build is checked here, never by the rules
  * of another era.
  *
  * The ledger view is forecast from the anchor as the hard-fork combinator forecasts it
  * (`oneForecast`, `HardFork/Combinator/Ledger.hs:555-620`): in the anchor's era, or once the
  * anchor's known end is passed in the next era alone, never further. Where that era is Dijkstra
  * the view is Conway's across the boundary ([[DijkstraCrossing.forecast]]) or Dijkstra's own
  * ([[DijkstraCrossing.forecastInEra]]), the chain-dependent state is translated into Praos2's
  * where it is still Praos's ([[DijkstraCrossing.chainDepState]]) and ticked by Praos2, then
  * `validateHeader` runs: the envelope checks of every era, then Praos2's envelope and protocol
  * rules ([[Praos2.validate]]; `HeaderValidation.hs:374-386, 518-526`), as the ChainSync client
  * runs them on each header (`MiniProtocol/ChainSync/Client.hs:1934-1990`). Where the view's era is
  * another, the header is refused as of the wrong era once the envelope checks of every era hold
  * (`HardFork/Combinator/Ledger.hs:395-405`).
  */
private[node] final class Praos2Headers private ():
  /** The prepared check of `h`, or none where it is not a Dijkstra header.
    *
    * @param tip
    *   the anchor's tip, from which the ledger view is forecast
    * @param parent
    *   the header `h` extends, the tip of the chain-dependent state `prev`
    * @param forecastLimit
    *   the exclusive bound of a forecast from a Byron anchor's translated ledger, which replaces
    *   the anchor's own
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
      forecastLimit: Option[BigInt],
      genericEnvelope: Either[HeaderError, Unit]
  ): Option[Prepared[ShelleyValidated]] =
    Option.when(h.era == Era.Dijkstra) {
      val epoch = cfg.geometry.epochOf(h.slot)
      val tipSlot = tip.fold(BigInt(-1))(_.slot)
      forecast(cfg, globals, ledger, afterVoting, tip, h.slot) match
        case Left(unforecast) => unknown(unforecast, h, tipSlot)
        case Right(Praos2Headers.Forecast.Leios(view)) =>
          check(cfg, view, parent, prev, h, epoch, tipSlot, genericEnvelope)
        case Right(Praos2Headers.Forecast.WithoutLeios(viewEra, end)) =>
          wrongEra(globals, ledger, tip, h, epoch, viewEra, end, forecastLimit, genericEnvelope)
    }

  /** The ledger view forecast from the anchor for `slot`, as the hard-fork combinator forecasts it
    * (`oneForecast`, `HardFork/Combinator/Ledger.hs:555-620`, ouroboros-consensus 2416493b): in the
    * anchor's era, or once the anchor's known end is passed in the next era alone, never further.
    * Where that era is Dijkstra the view is Conway's across the boundary
    * ([[DijkstraCrossing.forecast]]) or Dijkstra's own ([[DijkstraCrossing.forecastInEra]]); where
    * it is another, the view has no Leios fields and only its era and the anchor's known end are
    * told, whose range [[inRange]] decides.
    */
  def forecast[E <: EraTag](
      cfg: PraosConfig,
      globals: Globals,
      ledger: EraRead[E],
      afterVoting: Long,
      tip: Option[BlockRef],
      slot: BigInt
  ): Either[Praos2Headers.Unforecast, Praos2Headers.Forecast] =
    import Praos2Headers.{Forecast, Unforecast}
    val geometry = cfg.geometry
    val epoch = geometry.epochOf(slot)
    val anchorEra = EraNes.eraOf(ledger.era)
    // the anchor's era ends before the slot's epoch: the view is of the next era
    val end = cfg.forks
      .transition(geometry.k)(
        Hfc.Pending(anchorEra, ledger.epoch.toBigInt, ledger.definiteUpdateMajor, afterVoting)
      )
      .filter(_ <= epoch)
    val viewEra = end.flatMap(_ => HardForkConfig.next(anchorEra)).getOrElse(anchorEra)
    val horizon = (f: CrossingForecastFailure) =>
      f match
        case _: CrossingForecastFailure.OutsideForecastRange => Unforecast.Horizon
        case other @ (_: CrossingForecastFailure.Translation |
            _: CrossingForecastFailure.Parameter | _: CrossingForecastFailure.Vocabulary) =>
          Unforecast.View(other)
    val unread = (e: HeaderStructureProblem) => Unforecast.Unread(e)
    ledger.era match
      case ShelleyBasedEra.SDijkstra =>
        for
          at <- forecastFor(slot, tip)
          (forSlot, anchorTip) = at
          nes <- ledger.nes.leftMap(e => unread(HeaderStructureProblem.LedgerRead(e)))
          view <- DijkstraCrossing.forecastInEra(globals, forSlot, anchorTip, nes).leftMap(horizon)
        yield Forecast.Leios(view)
      case ShelleyBasedEra.SConway =>
        end.fold[Either[Unforecast, Forecast]](Right(Forecast.WithoutLeios(viewEra, end))) {
          first =>
            for
              at <- forecastFor(slot, tip)
              (forSlot, anchorTip) = at
              transition <- vocab(SlotNo.fromBigInt(geometry.firstSlot(first)))
              nes <- ledger.nes.leftMap(e => unread(HeaderStructureProblem.LedgerRead(e)))
              view <- DijkstraCrossing
                .forecast(globals, globals, transition, forSlot, anchorTip, nes)
                .leftMap(horizon)
            yield Forecast.Leios(view)
        }
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        Right(Forecast.WithoutLeios(viewEra, end))

  /** The bound on an endorser block's transactions in the ledger view forecast from the anchor for
    * `slot` (`getLeiosMaxEbTxsSizeFromView` of the view `forecastFor` gives, as the fetch logic
    * reads it, `NodeKernel.hs:586-593` at ouroboros-consensus 2416493b): a Dijkstra view's
    * `plvMaxEbTxsSize`, and 0 for the view of an era without it (`ResolveLeiosBlock`'s default,
    * `Storage/LedgerDB/Forker.hs:975-987`), within its range; past the range none.
    */
  def maxEbTxsSize[E <: EraTag](
      cfg: PraosConfig,
      globals: Globals,
      ledger: EraRead[E],
      afterVoting: Long,
      tip: Option[BlockRef],
      slot: BigInt
  ): Either[Praos2Headers.Unforecast, Word32] =
    forecast(cfg, globals, ledger, afterVoting, tip, slot).flatMap {
      case Praos2Headers.Forecast.Leios(view) => Right(view.maxEbTxsSize)
      case Praos2Headers.Forecast.WithoutLeios(_, end) =>
        inRange(globals, tip, slot, end, None).flatMap(within =>
          Either.cond(within, Word32.wrap(0L), Praos2Headers.Unforecast.Horizon)
        )
    }

  /** The prepared check of `h` from an anchor still in Byron, or none where it is not a Dijkstra
    * header. The forecast's era and range are the Byron anchor's, as the hard-fork combinator's
    * (`oneForecast`, `HardFork/Combinator/Ledger.hs:555-620`, ouroboros-consensus 2416493b): before
    * a known Shelley boundary (configured, or decided by the Byron ledger's update) the Byron view,
    * up to `2k` slots past the Byron ledger's last slot, exclusive (`ledgerViewForecastAt` of
    * Byron, `Byron/Ledger/Ledger.hs:300-323`, whose forecast is always at `cvsLastSlot`, 0 at the
    * genesis); from the boundary on the initial Shelley view, up to Shelley's stability window past
    * the boundary (`crossEraForecastByronToShelleyWrapper`, `Cardano/CanHardFork.hs:433-487`), and
    * never an era beyond Shelley. Neither is Dijkstra's: past the range no verdict yet, within it
    * the envelope checks of every era and the era match, which refuses the header.
    *
    * @param tip
    *   the anchor's tip, none at the origin
    */
  def fromByron(
      cfg: PraosConfig,
      byron: ByronRules.Config,
      ledger: ByronLedgerState,
      tip: Option[BlockRef],
      h: Header.Shelley,
      genericEnvelope: Either[HeaderError, Unit]
  ): Option[Prepared[ProtocolState]] =
    Option.when(h.era == Era.Dijkstra) {
      val (viewEra, maxFor, epoch, boundary) = byronRange(cfg, byron, ledger, h.slot)
      if h.slot >= maxFor then
        Prepared.Unknown(
          ForecastReason
            .Horizon(h.slot, boundary.fold(ledger.lastSlot)(_ => tip.fold(BigInt(-1))(_.slot)))
        )
      else
        genericEnvelope.fold(
          Prepared.Invalid(_),
          _ =>
            Prepared.Invalid(
              HeaderError.Envelope(
                EnvelopeError
                  .WrongEra(h.era, viewEra, epoch, BigInt(ledger.update.adoptedVersion.major))
              )
            )
        )
    }

  /** The closure bound of `ledgerViewOfTip` (`HeaderStateHistory.hs:250-262` at 2416493b): of a
    * Dijkstra ledger, its view at its own tip ([[DijkstraCrossing.forecastInEra]] at the tip), or
    * unknown where the node cannot make it; another era's view has no Leios fields.
    */
  def boundOfTip[E <: EraTag](
      globals: Globals,
      ledger: EraRead[E],
      tip: WithOrigin[SlotNo]
  ): LeiosBound =
    ledger.era match
      case ShelleyBasedEra.SDijkstra =>
        val slot = tip match
          case WithOrigin.At(s)  => s
          case WithOrigin.Origin => SlotNo.fromUnsigned(0)
        ledger.nes.toOption
          .flatMap(DijkstraCrossing.forecastInEra(globals, slot, tip, _).toOption)
          .fold(LeiosBound.Unknown)(view => LeiosBound.Of(view.maxEbTxsSize))
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
        LeiosBound.WithoutLeios

  /** The era of the view a Byron anchor forecasts for `slot`, the exclusive bound of its range, the
    * slot's epoch, and the Shelley boundary once `slot` is past it ([[fromByron]]).
    */
  private def byronRange(
      cfg: PraosConfig,
      byron: ByronRules.Config,
      ledger: ByronLedgerState,
      slot: BigInt
  ): (Era, BigInt, BigInt, Option[(BigInt, EpochGeometry)]) =
    val k = byron.ledger.genesis.securityParam
    val boundary = byron.configuredShelleyEpoch
      .orElse(ByronLedger.transition(byron.ledger, ledger))
      .map(first => (first, cfg.geometry.resolved(first)))
      .filter((first, geometry) => slot >= geometry.firstSlot(first))
    boundary match
      case Some((first, geometry)) =>
        (
          Era.Shelley,
          geometry.firstSlot(first) + geometry.stabilityWindow.toBigInt,
          geometry.epochOf(slot),
          boundary
        )
      case None =>
        // the Byron forecast is at the ledger's last slot, 0 at the genesis
        // (`at = NotOrigin (cvsLastSlot st)`), so its bound is that slot + 1 + 2k there too
        (Era.Byron, ledger.lastSlot + 1 + 2 * k, slot / byron.ledger.epochSlots, None)

  /** [[maxEbTxsSize]] from an anchor still in Byron: its view is Byron's or Shelley's, never
    * Dijkstra's ([[fromByron]]), so 0 within the range.
    */
  def maxEbTxsSizeFromByron(
      cfg: PraosConfig,
      byron: ByronRules.Config,
      ledger: ByronLedgerState,
      slot: BigInt
  ): Either[Praos2Headers.Unforecast, Word32] =
    val (_, maxFor, _, _) = byronRange(cfg, byron, ledger, slot)
    Either.cond(slot < maxFor, Word32.wrap(0L), Praos2Headers.Unforecast.Horizon)

  /** No view for `h`: past the range no verdict yet; a view the node cannot make, or a slot its
    * vocabulary cannot hold, a defect of its own; a ledger that cannot be read, a refusal.
    */
  private def unknown(
      unforecast: Praos2Headers.Unforecast,
      h: Header.Shelley,
      tipSlot: BigInt
  ): Prepared[Nothing] = unforecast match
    case Praos2Headers.Unforecast.Horizon =>
      Prepared.Unknown(ForecastReason.Horizon(h.slot, tipSlot))
    case Praos2Headers.Unforecast.View(failure) =>
      Prepared.Undecided(HeaderDefect.DijkstraView(failure))
    case Praos2Headers.Unforecast.Unread(problem) =>
      Prepared.Invalid(HeaderError.Structure(problem))
    case Praos2Headers.Unforecast.Vocabulary(error) =>
      Prepared.Undecided(HeaderDefect.Praos2(Praos2Defect.Vocabulary(error)))

  private def vocab[A](read: Either[VocabError, A]): Either[Praos2Headers.Unforecast, A] =
    read.leftMap(Praos2Headers.Unforecast.Vocabulary(_))

  /** The slot to forecast for and the anchor's tip, as the forecast takes them. */
  private def forecastFor(
      slot: BigInt,
      tip: Option[BlockRef]
  ): Either[Praos2Headers.Unforecast, (SlotNo, WithOrigin[SlotNo])] =
    for
      forSlot <- vocab(SlotNo.fromBigInt(slot))
      at <- vocab(tip.traverse(t => SlotNo.fromBigInt(t.slot)))
    yield (forSlot, at.fold[WithOrigin[SlotNo]](WithOrigin.Origin)(WithOrigin.At(_)))

  /** Whether the view of another era than Dijkstra reaches `slot` from the anchor: the anchor's own
    * range, or across its known end `crossEraForecastBound` ([[DijkstraCrossing.forecastBound]]),
    * or `forecastLimit` where a Byron anchor's translated ledger replaces the anchor's own.
    */
  private def inRange(
      globals: Globals,
      tip: Option[BlockRef],
      slot: BigInt,
      end: Option[BigInt],
      forecastLimit: Option[BigInt]
  ): Either[Praos2Headers.Unforecast, Boolean] =
    val window = globals.stabilityWindow
    for
      at <- forecastFor(slot, tip)
      (forSlot, anchorTip) = at
      transition <- end.traverse(first =>
        vocab(SlotNo.fromBigInt(globals.geometry.firstSlot(first)))
      )
      successor = tip.fold(BigInt(0))(_.slot + 1)
      maxFor = forecastLimit.getOrElse(
        transition.fold(successor + window)(t =>
          DijkstraCrossing.forecastBound(anchorTip, t, window, window).toBigInt
        )
      )
    yield anchorTip == WithOrigin.At(forSlot) || forSlot.toBigInt < maxFor

  /** A Dijkstra header whose slot the anchor's forecast places in `viewEra`, another era: within
    * the forecast's range (the anchor's own, or across its known end `crossEraForecastBound`,
    * [[DijkstraCrossing.forecastBound]]), the envelope checks of every era and then the era match,
    * which refuses it; past the range, no verdict yet.
    */
  private def wrongEra[E <: EraTag](
      globals: Globals,
      ledger: EraRead[E],
      tip: Option[BlockRef],
      h: Header.Shelley,
      epoch: BigInt,
      viewEra: Era,
      end: Option[BigInt],
      forecastLimit: Option[BigInt],
      genericEnvelope: Either[HeaderError, Unit]
  ): Prepared[Nothing] =
    inRange(globals, tip, h.slot, end, forecastLimit).fold(
      unknown(_, h, tip.fold(BigInt(-1))(_.slot)),
      within =>
        if !within then
          Prepared.Unknown(ForecastReason.Horizon(h.slot, tip.fold(BigInt(-1))(_.slot)))
        else
          // the ledger's major version as the other eras' envelope reports it, at the header's epoch
          val params =
            if epoch == ledger.epoch.toBigInt + 1 then ledger.paramsNextEpoch(globals.ledger)
            else ledger.params
          genericEnvelope.fold(
            Prepared.Invalid(_),
            _ =>
              Prepared.Invalid(
                HeaderError.Envelope(
                  EnvelopeError.WrongEra(h.era, viewEra, epoch, params.protocolMajor)
                )
              )
          )
    )

  private def check(
      cfg: PraosConfig,
      view: Praos2.LedgerView,
      parent: Option[BlockRef],
      prev: ProtocolState,
      h: Header.Shelley,
      epoch: BigInt,
      tipSlot: BigInt,
      genericEnvelope: Either[HeaderError, Unit]
  ): Prepared[ShelleyValidated] =
    val config = Praos2.Config(
      cfg,
      Word64.fromUnsigned(cfg.forks.maxMajorProtVer.toLong),
      Praos2.EpochInfo.fromGeometry(cfg.geometry)
    )
    // an epoch query past its forecast waits for the chain; any other defect is the node's own
    val defect = (d: Praos2Defect) =>
      d match
        case _: Praos2Defect.SlotOutsideForecast | _: Praos2Defect.EpochOutsideForecast =>
          Outcome.Later(ForecastReason.Horizon(h.slot, tipSlot))
        case other @ (_: Praos2Defect.ZeroEpochSize | _: Praos2Defect.NonPositiveSlotLength |
            _: Praos2Defect.NonPositiveKesPeriod | _: Praos2Defect.SuccessorOverflow |
            _: Praos2Defect.Vocabulary) =>
          Outcome.Undecided(HeaderDefect.Praos2(other))
    // `translateChainDepState` of `TranslateProto (Praos c) (Praos2 c)`, and of TPraos through
    // Praos (`Protocol/Praos2.hs:189-209`, ouroboros-consensus 2416493b): the state of the era
    // before, carried over
    val translated = prev.in(Era.Conway)
    Prepared.Check(() =>
      val checked: Either[Outcome[ShelleyValidated], ShelleyValidated] =
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
            .leftMap(e => defect(Praos2Defect.Vocabulary(e)))
          slot <- SlotNo.fromBigInt(h.slot).leftMap(e => defect(Praos2Defect.Vocabulary(e)))
          ticked <- Praos2
            .tick(config, view, slot, Praos2.ChainState(at, state))
            .leftMap(defect)
          _ <- genericEnvelope.leftMap(Outcome.Invalid(_))
          next <- Praos2.validate(config, header, ticked).leftMap {
            case e: Praos2Error    => Outcome.Invalid(HeaderError.Praos2(e))
            case d: Praos2Defect   => defect(d)
            case p: ProviderDefect => Outcome.Unverifiable(p)
          }
        yield ShelleyValidated(
          ProtocolState(ChainDepState.Praos2(next.protocol), translated.extras.copy(epoch = epoch)),
          LeiosBound.Of(view.maxEbTxsSize)
        )
      checked.fold(identity, Outcome.Valid(_))
    )

private[node] object Praos2Headers:
  /** The ledger view forecast for a slot ([[Praos2Headers.forecast]]). */
  enum Forecast:
    /** Dijkstra's, with the Leios fields. */
    case Leios(view: Praos2.LedgerView)

    /** Of `viewEra`, an era without Leios: the anchor's, or the next after its known `end`. */
    case WithoutLeios(viewEra: Era, end: Option[BigInt])

  /** Why there is no view for a slot. */
  enum Unforecast:
    /** The slot is past the forecast's range: no view yet (`PastHorizon`). */
    case Horizon

    /** The view could not be made of the anchor's state: a defect of the node's own. */
    case View(failure: CrossingForecastFailure)

    /** The anchor's ledger could not be read. */
    case Unread(problem: HeaderStructureProblem)

    /** A slot outside the vocabulary's words. */
    case Vocabulary(error: VocabError)

  /** The rules of a Dijkstra header in `build`: Praos2's in the Leios build, whose Dijkstra header
    * is the Leios header (`ShelleyProtocolHeader (Praos2 c) = Leios.Header c`,
    * `Protocol/Praos2.hs:57`, ouroboros-consensus 2416493b); none in the release lines, whose
    * Dijkstra runs Praos on Conway's header and which this node never enters (their Dijkstra
    * headers keep the refusal of every other era's path).
    */
  def of(build: ReferenceBuild): Option[Praos2Headers] = build match
    case ReferenceBuild.LeiosB004fe0c                            => Some(Praos2Headers())
    case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 => None
