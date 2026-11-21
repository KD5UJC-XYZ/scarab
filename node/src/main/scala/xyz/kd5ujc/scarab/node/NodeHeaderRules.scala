// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.consensus.HeaderRules.{ForecastReason, Outcome, Prepared}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, PBftState, ProtocolState}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.byron.ByronLedger
import xyz.kd5ujc.scarab.ledger.rules.{FromByron, Handover}
import xyz.kd5ujc.scarab.ledger.state.{EpochNo, ShelleyBasedEra, WithOrigin}
import xyz.kd5ujc.scarab.node.read.{ChainRead, InEra, MirrorEraRead}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32

/** Header-first state across the Byron/Shelley boundary. The pending Shelley state is distinct from
  * the active PBFT state; a translated ledger forecast is shared until a real Shelley anchor
  * becomes available, without applying a missing block or inventing its transactions.
  */
enum NodeHeaderState:
  case Byron(
      pbft: PBftState,
      pendingShelley: ProtocolState,
      previous: Option[ByronTip],
      previousBlockNo: Option[BigInt]
  )
  case Shelley(
      state: ProtocolState,
      previous: Option[BlockRef],
      firstEpoch: Option[BigInt],
      translated: Option[InEra]
  )

  def protocol: Option[ProtocolState] = this match
    case Shelley(state, _, _, _) => Some(state)
    case _: Byron                => None

/** Header validation never counts an unchecked header. Its Byron ledger forecast is the native
  * exclusive NOW+1+2k bound (Byron/Ledger/Ledger.hs:268–320), and its first Shelley view uses the
  * same exact initial forecast as the reference hard-fork combinator (CanHardFork.hs:421–470).
  */
final class NodeHeaderRules[S](
    config: PraosConfig,
    globals: LedgerGlobals,
    byron: Option[ByronRules.Config] = None,
    handOver: Option[FromByron] = None
)(using read: ChainRead[S], history: HeaderTimeRead[S])
    extends HeaderRules[S, NodeHeaderState]
    with HeaderRules.Timing[S, NodeHeaderState]:
  private val shelleyRules = ShelleyHeaderForecast[S](config, globals)

  def tiebreaker(anchor: S): ChainSelection.Tiebreaker = ChainSelection.tiebreakerFor(anchor.era)

  def forecastTime(
      anchor: S,
      tip: Option[BlockRef],
      @scala.annotation.unused previous: NodeHeaderState,
      header: Header
  ): HeaderRules.TimeForecast =
    history.forecast(anchor, tip, header.slot, config, byron)

  def at(state: S): NodeHeaderState =
    if state.era == Era.Byron then
      val old = state.consensus.byron
      NodeHeaderState.Byron(old.pbft, state.protocol, old.tip, None)
    else
      NodeHeaderState.Shelley(state.protocol, None, state.consensus.byron.firstShelleyEpoch, None)

  def validate(
      anchor: S,
      tip: Option[BlockRef],
      previous: NodeHeaderState,
      header: Header
  ): Outcome[NodeHeaderState] =
    prepareValidation(anchor, tip, previous, header).result

  def prepareValidation(
      anchor: S,
      tip: Option[BlockRef],
      previous: NodeHeaderState,
      header: Header
  ): Prepared[NodeHeaderState] = (previous, header) match
    case (old: NodeHeaderState.Byron, h: Header.Byron)   => validateByron(anchor, tip, old, h)
    case (old: NodeHeaderState.Byron, h: Header.Shelley) => enterShelley(anchor, tip, old, h)
    case (_: NodeHeaderState.Shelley, _: Header.Byron) =>
      Prepared.Invalid(HeaderError.Structure(HeaderStructureProblem.MissingByronLedger))
    case (old: NodeHeaderState.Shelley, h: Header.Shelley) =>
      val ledgerTip = old.previous.orElse(tip)
      anchor.ledger(tip) match
        case Left(error) =>
          Prepared.Invalid(HeaderError.Structure(HeaderStructureProblem.LedgerRead(error)))
        case Right(Some(InEra.Of(_, ledger))) =>
          wrap(
            shelleyRules.prepareShelley(
              anchor,
              ledger,
              anchor.afterVoting,
              tip,
              ledgerTip,
              old.state,
              h,
              genericEnvelope = envelope(ledgerTip, h, previousBoundary = false)
            ),
            h,
            old.firstEpoch,
            None
          )
        case Right(None) =>
          fromByron(anchor, tip, h, envelope(ledgerTip, h, previousBoundary = false))
            .getOrElse(old.translated match
              case Some(InEra.Of(_, ledger)) =>
                val cfg = old.firstEpoch.fold(config)(first =>
                  config.copy(geometry = config.geometry.resolved(first))
                )
                val glob = old.firstEpoch.fold(globals)(first => globals.copy(firstEpoch = first))
                wrap(
                  ShelleyHeaderForecast[S](cfg, glob)
                    .prepareShelley(
                      anchor,
                      ledger,
                      0L,
                      tip,
                      ledgerTip,
                      old.state,
                      h,
                      forecastLimit = old.firstEpoch.map(first =>
                        cfg.geometry.firstSlot(first) + cfg.geometry.stabilityWindow.toBigInt
                      ),
                      genericEnvelope = envelope(ledgerTip, h, previousBoundary = false)
                    ),
                  h,
                  old.firstEpoch,
                  old.translated
                )
              case None => Prepared.Unknown(ForecastReason.ShelleyLedgerNotReady))

  /** The bound on an endorser block's transactions in the ledger view forecast from `anchor`, at
    * `tip`, for `slot`, as the Leios fetch reads it from the immutable tip (`forecastMaxEbTxsSize`,
    * `NodeKernel.hs:586-593` at ouroboros-consensus 2416493b): none in a build without Praos2
    * ([[Praos2Headers.of]]), which has no Leios fetch.
    */
  def leiosMaxEbTxsSize(
      anchor: S,
      tip: Option[BlockRef],
      slot: BigInt
  ): Option[Either[Praos2Headers.Unforecast, Word32]] =
    Praos2Headers.of(globals.referenceBuild).map { rules =>
      anchor.ledger(tip) match
        case Left(error) =>
          Left(Praos2Headers.Unforecast.Unread(HeaderStructureProblem.LedgerRead(error)))
        case Right(Some(InEra.Of(_, ledger))) =>
          shelleyRules.maxEbTxsSize(rules, anchor, ledger, anchor.afterVoting, tip, slot)
        case Right(None) =>
          (byron, anchor.consensus.byron.ledger) match
            case (Some(cfg), Some(ledger)) => rules.maxEbTxsSizeFromByron(config, cfg, ledger, slot)
            case _ =>
              Left(Praos2Headers.Unforecast.Unread(HeaderStructureProblem.MissingByronLedger))
    }

  private def wrap(
      outcome: Prepared[ProtocolState],
      header: Header.Shelley,
      first: Option[BigInt],
      translated: Option[InEra]
  ): Prepared[NodeHeaderState] = outcome.map(next =>
    NodeHeaderState.Shelley(
      next,
      Some(BlockRef(header.slot, header.hash, header.blockNo)),
      first,
      translated
    )
  )

  private def envelope(
      previous: Option[BlockRef],
      header: Header,
      previousBoundary: Boolean
  ): Either[HeaderError, Unit] =
    val boundary = header match
      case h: Header.Byron => h.boundary
      case _               => false
    val first = BigInt(0)
    val expected =
      previous.fold(first)(p => p.blockNo + (if boundary && !previousBoundary then 0 else 1))
    val minimum =
      previous.fold(BigInt(0))(p => p.slot + (if previousBoundary && !boundary then 0 else 1))
    val expectedHash = previous.map(_.hash)
    // a Byron boundary header that names the genesis has none, as consensus's `headerPrevHash`
    // reads it (`Header.Byron.namesGenesis`, #754)
    val actualHash = header match
      case h: Header.Byron if h.boundary => Option.unless(h.namesGenesis)(h.prevHash).flatten
      case _ =>
        header.prevHash.filterNot(h =>
          header.era == Era.Byron && byron.exists(_.ledger.genesis.hash == h) && previous.isEmpty
        )
    for
      _ <- Either.cond(
        header.blockNo == expected,
        (),
        HeaderError.Structure(HeaderStructureProblem.BlockNumber(header.blockNo, expected))
      )
      _ <- Either.cond(
        header.slot >= minimum,
        (),
        HeaderError.Structure(HeaderStructureProblem.SlotNumber(header.slot, minimum))
      )
      _ <- Either.cond(
        actualHash == expectedHash,
        (),
        HeaderError.Structure(HeaderStructureProblem.PreviousHash(actualHash, expectedHash))
      )
      _ <- config.checkpoints.validate(header).leftMap(HeaderError.Envelope(_))
    yield ()

  private def validateByron(
      anchor: S,
      tip: Option[BlockRef],
      previous: NodeHeaderState.Byron,
      header: Header.Byron
  ): Prepared[NodeHeaderState] =
    (byron, anchor.consensus.byron.ledger) match
      case (Some(cfg), Some(ledger)) =>
        val parent = previous.previous.map(p =>
          BlockRef(p.slot, p.hash, previous.previousBlockNo.orElse(tip.map(_.blockNo)).getOrElse(0))
        )
        val boundary = previous.previous.exists(_.boundary)
        val maxSlot = ledger.lastSlot + 1 + 2 * cfg.ledger.genesis.securityParam
        envelope(parent, header, boundary) match
          case _ if header.slot >= maxSlot =>
            Prepared.Unknown(ForecastReason.Horizon(header.slot, ledger.lastSlot))
          case Left(error) => Prepared.Invalid(error)
          case Right(_) if header.boundary && header.slot % cfg.ledger.epochSlots != 0 =>
            Prepared.Invalid(
              HeaderError.Structure(
                HeaderStructureProblem.UnexpectedBoundarySlot(header.slot, cfg.ledger.epochSlots)
              )
            )
          case Right(_)
              if cfg.configuredShelleyEpoch
                .orElse(ByronLedger.transition(cfg.ledger, ledger))
                .exists(header.slotEpoch >= _) =>
            Prepared.Invalid(
              HeaderError.Envelope(
                EnvelopeError.WrongEra(Era.Byron, Era.Shelley, header.slotEpoch, BigInt(2))
              )
            )
          case Right(_) =>
            val ticked = ByronLedger.tick(cfg.ledger, ledger, header.slot)
            Prepared.Check(() =>
              HeaderRules.outcome(
                ByronRules
                  .validateHeader(header, previous.pbft, ticked.delegation, cfg)
                  .leftMap[HeaderError | ProviderDefect] {
                    case defect: ProviderDefect => defect
                    case error: ByronChainError => HeaderError.Byron(error)
                  }
                  .map(next =>
                    NodeHeaderState.Byron(
                      next,
                      previous.pendingShelley,
                      Some(ByronTip(header.hash, header.epoch, header.slot, header.boundary)),
                      Some(header.blockNo)
                    )
                  )
              )
            )
      case _ => Prepared.Invalid(HeaderError.Structure(HeaderStructureProblem.MissingByronLedger))

  private def enterShelley(
      anchor: S,
      tip: Option[BlockRef],
      previous: NodeHeaderState.Byron,
      header: Header.Shelley
  ): Prepared[NodeHeaderState] =
    val parent = previous.previous.map(p =>
      BlockRef(p.slot, p.hash, previous.previousBlockNo.orElse(tip.map(_.blockNo)).getOrElse(0))
    )
    fromByron(
      anchor,
      tip,
      header,
      envelope(parent, header, previous.previous.exists(_.boundary))
    ).getOrElse(enterShelleyEra(anchor, tip, previous, header, parent))

  /** A Dijkstra header of the Leios build from an anchor still in Byron
    * ([[Praos2Headers.fromByron]]); none for any other header, or without the Byron rules and
    * ledger.
    */
  private def fromByron(
      anchor: S,
      tip: Option[BlockRef],
      header: Header.Shelley,
      genericEnvelope: Either[HeaderError, Unit]
  ): Option[Prepared[NodeHeaderState]] =
    for
      rules <- Praos2Headers.of(globals.referenceBuild)
      byronRules <- byron
      ledger <- anchor.consensus.byron.ledger
      prepared <- rules.fromByron(config, byronRules, ledger, tip, header, genericEnvelope)
    yield prepared.map(next =>
      NodeHeaderState.Shelley(
        next,
        Some(BlockRef(header.slot, header.hash, header.blockNo)),
        None,
        None
      )
    )

  private def enterShelleyEra(
      anchor: S,
      tip: Option[BlockRef],
      previous: NodeHeaderState.Byron,
      header: Header.Shelley,
      parent: Option[BlockRef]
  ): Prepared[NodeHeaderState] =
    val current = anchor.consensus.byron
    val entry = NodeRules.entry(current, header, config, globals, byron)
    val translated = for
      entering <- entry
      result <- entering match
        case NodeRules.Entry.Entering(first, _, cfg, glob) =>
          for
            context <- handOver.toRight(
              BlockFailure.Handover(HandoverProblem.MissingShelleyContext)
            )
            epoch <- EpochNo
              .fromBigInt(first)
              .leftMap(v => BlockFailure.Tick(xyz.kd5ujc.scarab.ledger.rules.TickFailure.Vocab(v)))
            // mkInitialShelleyForecast ignores the Byron UTxO: only genesis parameters,
            // delegations and an empty pool distribution enter this forecast.
            nes <- Handover
              .layoutOf(glob.referenceBuild)
              .flatMap(Handover.toShelley(context, epoch, Map.empty, _))
              .leftMap(BlockFailure.Tick(_))
          yield (
            first,
            cfg,
            glob,
            InEra.Of(
              ShelleyBasedEra.SShelley,
              new MirrorEraRead(nes)(using ShelleyBasedEra.SShelley)
            )
          )
        case NodeRules.Entry.Within(_, _) =>
          Left(BlockFailure.Handover(HandoverProblem.MissingByronRules))
    yield result
    translated match
      case Left(_: BlockFailure.TransitionUndecided) =>
        Prepared.Unknown(ForecastReason.ShelleyLedgerNotReady)
      case Left(error) =>
        Prepared.Invalid(HeaderError.Structure(HeaderStructureProblem.Transition(error)))
      case Right((first, cfg, glob, ledger @ InEra.Of(_, view))) =>
        val pending = previous.pendingShelley.dep match
          case ChainDepState.TPraos(state) =>
            previous.pendingShelley.copy(dep =
              ChainDepState.TPraos(
                state.copy(
                  lastSlot = previous.pbft.lastSignedSlot
                    .fold[WithOrigin[xyz.kd5ujc.scarab.ledger.state.SlotNo]](WithOrigin.Origin)(
                      WithOrigin.At(_)
                    )
                )
              )
            )
          case ChainDepState.PBft(_) | ChainDepState.Praos(_) | ChainDepState.Praos2(_) =>
            previous.pendingShelley
        wrap(
          ShelleyHeaderForecast[S](cfg, glob)
            .prepareShelley(
              anchor,
              view,
              0L,
              tip,
              parent,
              pending,
              header,
              forecastLimit =
                Some(cfg.geometry.firstSlot(first) + cfg.geometry.stabilityWindow.toBigInt),
              genericEnvelope = envelope(parent, header, previous.previous.exists(_.boundary))
            ),
          header,
          Some(first),
          Some(ledger)
        )
