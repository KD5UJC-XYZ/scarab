// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{BlockRef, BootstrapState, ByronEpochStats, FollowerEvent}
import xyz.kd5ujc.scarab.ledger.{Block, EpochGeometry, Header, Prepared, Verdicts}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.ledger.LeaderCheck
import xyz.kd5ujc.scarab.ledger.LedgerView
import xyz.kd5ujc.scarab.ledger.StakeView
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronLedgerState}
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.node.read.{ChainRead, InEra}

/** What the block rules of the node share: decoding a block for the rules, the events a block makes
  * visible between two states, the Byron prefix of a chain (its entry into Shelley and the Byron
  * step) and the header checks. The rules themselves are [[MirrorRules]] (the old rules, on the
  * node's flat state, were removed at J2.9).
  */
object NodeRules:
  /** Decode a Shelley-based block and its transactions; `None` when it does not decode (the rules
    * then report the error).
    */
  def prepare(header: Header.Shelley, raw: Bytes, verdicts: Verdicts): Option[Prepared] =
    verdicts
      .takePrepared(header.hash)
      .orElse(decodeShelley(raw))
      .filter(_.block.era == header.era)

  /** Decode a Shelley-based block and its transactions. */
  def decodeShelley(raw: Bytes): Option[Prepared] =
    Block.decodeShelley(raw).toOption.collect { case b: Block.Shelley =>
      Prepared(b, b.transactions.map(t => Tx.fromBlock(t, b.era)))
    }

  /** Era transitions and completed-epoch summaries visible between two states, read through
    * [[ChainRead]]: the epoch line is made from the ledger state of the era the chain is in, as the
    * mirror holds it (a state the mirror cannot hold has no line; the snapshot writer reports it,
    * `snapshotRefused`).
    */
  def observe[S](geometry: EpochGeometry)(using
      read: ChainRead[S]
  )(
      before: S,
      after: S,
      at: BlockRef
  ): List[FollowerEvent] =
    val was = before.consensus
    val now = after.consensus
    val era = now.boot.era
      .filter(e => !was.boot.era.contains(e))
      .map(to =>
        FollowerEvent.EraEntered(
          was.boot.era,
          to,
          at,
          now.byron.firstShelleyEpoch.fold(geometry)(geometry.resolved).epochOf(at.slot)
        )
      )
    val byron = now.byron.completed
      .filter(c => !was.byron.completed.contains(c))
      .map(c =>
        FollowerEvent.ByronEpochValidated(c.epoch, c.boundary, c.verified, c.offSchedule, c.txs)
      )
    val epoch = now.boot.completed
      .filter(c => !was.boot.completed.contains(c))
      .map(c => FollowerEvent.EpochValidated(c.epoch, c.era, c.threshold, c.overlay, c.unchecked))
    val ledger =
      if after.ledgerEpoch == before.ledgerEpoch then None
      else
        after.ledger(Some(at)).toOption.flatten.flatMap { case InEra.Of(_, l) =>
          l.scalars.toOption.map { scalars =>
            FollowerEvent.LedgerEpoch(
              l.epoch.toBigInt,
              l.utxo.size,
              l.accountCount,
              l.poolCount,
              scalars.chainAccount.treasury.value,
              scalars.chainAccount.reserves.value,
              l.params.version,
              after.plutus
            )
          }
        }
    era.toList ++ byron.toList ++ epoch.toList ++ ledger.toList

  /** From Alonzo on, the execution units of all the redeemers of a block (valid transactions or
    * not) stay within `maxBlockExUnits` of the parameters in force when the block starts.
    */
  private[node] def blockExUnits(
      params: ProtocolParams,
      units: ExUnits
  ): Either[BlockFailure, Unit] =
    Either.cond(
      params.protocolMajor < 5 || units.fits(params.maxBlockExUnits),
      (),
      BlockFailure.BlockExUnitsTooBig(units, params.maxBlockExUnits)
    )

  /** How a Shelley-based block meets the chain's Byron prefix, with the configuration and the
    * constants from then on: the epoch the chain enters Shelley is the configured one or, on a
    * public network, the one the Byron ledger's update state decided, and the configuration and the
    * globals are those of the resolved epoch history. Chains that start in Shelley pass through
    * untouched.
    */
  enum Entry:
    /** The chain is in Shelley already, its first epoch known (or it started there). */
    case Within(config: PraosConfig, globals: LedgerGlobals)

    /** This block is the first of Shelley: the Byron ledger it ends with, the epoch the chain
      * enters Shelley in, and the configuration and constants from then on.
      */
    case Entering(
        first: BigInt,
        ledger: ByronLedgerState,
        config: PraosConfig,
        globals: LedgerGlobals
    )

    def configuration: PraosConfig = this match
      case Within(c, _)         => c
      case Entering(_, _, c, _) => c

    def constants: LedgerGlobals = this match
      case Within(_, g)         => g
      case Entering(_, _, _, g) => g

  /** The entry of the chain into Shelley that the Shelley-based block `header` makes, from the
    * chain-follow state `byron` (the first Shelley epoch, once learned, and the Byron ledger).
    */
  def entry(
      byron: ByronState,
      header: Header.Shelley,
      config: PraosConfig,
      globals: LedgerGlobals,
      rules: Option[ByronRules.Config]
  ): Either[BlockFailure, Entry] =
    def resolve(first: BigInt) =
      (config.copy(geometry = config.geometry.resolved(first)), globals.copy(firstEpoch = first))
    byron.firstShelleyEpoch match
      case Some(first) =>
        val (cfg, glob) = resolve(first)
        Right(Entry.Within(cfg, glob))
      case None =>
        (rules, byron.ledger) match
          case (Some(r), Some(ledger)) =>
            for
              first <- r.configuredShelleyEpoch
                .orElse(ByronLedger.transition(r.ledger, ledger))
                .toRight(
                  BlockFailure.TransitionUndecided(header.era)
                )
              (cfg, glob) = resolve(first)
              epoch = cfg.geometry.epochOf(header.slot)
              _ <- Either.cond(
                epoch >= first && ledger.lastSlot < cfg.geometry.firstSlot(first),
                (),
                BlockFailure.ShelleyBeforeTransition(epoch, first, ledger.lastSlot)
              )
            yield Entry.Entering(first, ledger, cfg, glob)
          case _ => Right(Entry.Within(config, globals))

  /** The chain-follow state and the bootstrap state after a Byron block: what the old rules and the
    * era-indexed ones share (Byron has no new-epoch state).
    */
  def byronStep(
      state: ByronState,
      boot: BootstrapState,
      header: Header.Byron,
      raw: Bytes,
      byron: Option[ByronRules.Config]
  ): Either[BlockFailure, (ByronState, BootstrapState)] =
    for
      cfg <- byron.toRight(BlockFailure.NoByronGenesis)
      ledger <- state.ledger.toRight(BlockFailure.ByronAfterShelley)
      _ <- Either.cond(
        !boot.active && boot.era.forall(_ == Era.Byron),
        (),
        BlockFailure.ByronAfterShelley
      )
      decoded <- Block.decode(raw, cfg.ledger.epochSlots).leftMap(BlockFailure.Decode(_))
      block <- decoded match
        case b: Block.Byron => Right(b)
        case b              => Left(BlockFailure.EraAnnounced(b.era, Era.Byron))
      _ <- Either.cond(
        block.header.hash == header.hash,
        (),
        BlockFailure.HeaderDiffers
      )
      // once the transition is settled (known and stable) no Byron block may be in or after it
      _ <- cfg.configuredShelleyEpoch
        .orElse(ByronLedger.transition(cfg.ledger, ledger))
        .filter(_ <= header.epoch)
        .toLeft(())
        .leftMap { first =>
          BlockFailure.ByronAfterTransition(header.epoch, first)
        }
      validated <- ByronRules
        .update(ByronRules.State(state.tip, ledger, state.pbft), block, cfg)
        .leftMap(BlockFailure.Byron(_))
      (next, check) = validated
    yield
      val sameEpoch = state.stats.filter(_.epoch == header.epoch)
      val base = if sameEpoch.isEmpty then state.finish else state
      (
        base.copy(
          tip = next.tip,
          ledger = Some(next.ledger),
          pbft = next.pbft,
          stats = Some(
            sameEpoch.getOrElse(ByronEpochStats(header.epoch, 0, 0, 0, 0)).record(check)
          )
        ),
        boot.copy(era = Some(Era.Byron))
      )

  /** The header's own checks against the ledger as the block's slot finds it: the envelope (the
    * header and body sizes and the protocol version against the hard-fork configuration and the
    * parameters in force), then the leader check, the VRF, the operational certificate and the KES
    * signature of TPraos or Praos, with the pool distribution in force and, for TPraos, the overlay
    * schedule of the genesis delegations. What the old rules and the era-indexed ones share: the
    * ledger part is read from the state by the caller.
    */
  def headerChecks(
      protocol: ProtocolState,
      header: Header.Shelley,
      bodySize: Long,
      config: PraosConfig,
      globals: LedgerGlobals,
      params: ProtocolParams,
      genDelegs: Map[Bytes, GenDelegPair],
      pools: PoolDistribution
  ): Either[BlockFailure, (ProtocolState, LeaderCheck)] =
    val geometry = config.geometry
    val epoch = geometry.epochOf(header.slot)
    val nonceWindow =
      if header.era.ordinal >= Era.Conway.ordinal then
        geometry.randomnessStabilisationWindow.toBigInt
      else geometry.stabilityWindow.toBigInt
    for
      _ <- config.forks
        .check(
          header.era,
          epoch,
          header.protocolVersion._1,
          params.protocolMajor,
          header.raw.size,
          params.maxBlockHeaderSize,
          bodySize,
          params.maxBlockBodySize,
          mainnet = globals.network == 1
        )
        .leftMap(BlockFailure.Envelope(_))
      validated <-
        if header.era.usesTPraos then
          TPraos
            .update(
              Praos.tick(protocol, header.era, epoch, params.extraEntropy),
              header,
              LedgerView(params.effectiveD, params.extraEntropy, genDelegs, StakeView.Known(pools)),
              config
            )
            .map(v => v.state -> v.leader)
            .leftMap(BlockFailure.TPraos(_))
        else
          Praos
            .validate(
              Praos.tick(protocol, header.era, epoch),
              header,
              StakeView.Known(pools),
              config,
              nonceWindow
            )
            .leftMap(BlockFailure.Praos(_))
    yield validated
