// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{
  BlockRef,
  BlockRules,
  BootstrapState,
  ByronEpochStats,
  Compute,
  EpochStats,
  FollowerEvent,
  NodeState
}
import xyz.kd5ujc.scarab.ledger.{Block, EpochGeometry, Header, Prepared, ValidationError, Verdicts}
import cats.Monad
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.ledger.LeaderCheck
import xyz.kd5ujc.scarab.ledger.LedgerView
import xyz.kd5ujc.scarab.ledger.StakeView
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronLedgerState}
import xyz.kd5ujc.scarab.storage.UtxoStore
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.node.read.{ChainRead, InEra}

/** Full block validation: tick the ledger and Praos state to the block's epoch, validate the header
  * against the ticked pool distribution, then apply the body. Any failure rejects the block. Chains
  * that start before Conway are followed in bootstrap mode instead.
  */
final class NodeRules[F[_]: Monad](
    config: PraosConfig,
    globals: LedgerGlobals,
    byron: Option[ByronRules.Config] = None,
    bootstrap: Boolean = false,
    phase2Observer: Option[Phase2.Observation => Unit] = None,
    /** The backing store of the UTxO set. Without one the state's [[Utxo]] holds the whole set. */
    utxoStore: Option[UtxoStore[F]] = None,
    /** Called with each block's header and the state it produces (the node uses it to tell the
      * verification pipeline which epoch nonce is current, and to write state digests).
      */
    afterBlock: Option[(Header, NodeState) => F[Unit]] = None,
    /** Measures the pure rules (D8); without one they are not timed. */
    compute: Option[Compute[F]] = None
)(using
    Raise[F, ValidationError]
) extends BlockRules[F, NodeState]:

  private val cpu: Compute[F] = compute.getOrElse(Compute.inline[F])

  def apply(state: NodeState, header: Header, raw: Bytes): F[NodeState] =
    def run(s: NodeState, prepared: Option[Prepared]): F[NodeState] =
      cpu
        .timed(NodeRules.stepLabel(s))(
          NodeRules.applyBlock(
            s,
            header,
            raw,
            config,
            globals,
            byron,
            bootstrap,
            phase2Observer,
            prepared
          )
        )
        .flatMap(
          _.fold(
            failure => ValidationError(header.point, failure).raise[F, NodeState],
            next => afterBlock.fold(next.pure[F])(f => f(header, next).as(next))
          )
        )
    (utxoStore, header) match
      case (Some(store), h: Header.Shelley) if !bootstrap =>
        // The read set: what the block's transactions spend or reference, fetched before the pure
        // rules run (the rules then see a complete view of the entries they need).
        val prepared = NodeRules.prepare(h, raw, config.verdicts)
        val utxo = state.ledger.utxo
        val wanted = prepared.fold(Set.empty[TxIn])(p => utxo.missing(p.inputs))
        // The block that enters Allegra returns the redeem outputs to the reserves: the boundary
        // rule sees the whole set's, fetched here (one pass over the store, once per chain).
        val geometry = state.byron.firstShelleyEpoch.fold(config.geometry)(config.geometry.resolved)
        val entersAllegra = state.byron.ledger.isEmpty &&
          Epoch.entersAllegra(state.ledger, geometry.epochOf(h.slot), globals)
        for
          // The block that enters Shelley replaces the genesis placeholder by the Byron UTxO, built
          // as a whole at stamp 0: a store that still holds the placeholder (written by a build
          // before the backing skipped Byron states) would treat those entries as stored. Start
          // it empty, so that every entry is written (see `UtxoBacking`).
          _ <- if state.byron.ledger.isDefined then store.reset else ().pure[F]
          fetched <- if wanted.isEmpty then Map.empty[TxIn, TxOut].pure[F] else store.read(wanted)
          redeemers <-
            if entersAllegra then store.select(Epoch.isRedeemer)
            else Map.empty[TxIn, TxOut].pure[F]
          next <- run(
            state.copy(ledger = state.ledger.copy(utxo = utxo.withResident(fetched ++ redeemers))),
            prepared
          )
          flushed <- store.flushed
        yield next.copy(ledger = next.ledger.copy(utxo = next.ledger.utxo.settled(flushed)))
      case _ => run(state, None)

object NodeRules:
  /** The label a block application is recorded under: a block that crossed an epoch boundary or ran
    * Plutus scripts says so, as those are the steps that take long.
    */
  private[node] def stepLabel(
      before: NodeState
  )(result: Either[BlockFailure, NodeState]): String =
    result match
      case Left(_) => "rejected"
      case Right(after) =>
        if after.ledger.epoch != before.ledger.epoch || after.boot.completed != before.boot.completed
        then "epochBoundary"
        else if after.ledger.plutus.scripts != before.ledger.plutus.scripts then "plutus"
        else "block"

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

  /** The failure side is the closed [[BlockFailure]]: the rule family and the reason it renders. */
  def applyBlock(
      state: NodeState,
      header: Header,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals,
      byron: Option[ByronRules.Config] = None,
      bootstrap: Boolean = false,
      phase2Observer: Option[Phase2.Observation => Unit] = None,
      prepared: Option[Prepared] = None
  ): Either[BlockFailure, NodeState] =
    header match
      case h: Header.Byron => applyByron(state, h, raw, byron)
      case h: Header.Shelley =>
        enterShelley(state, h, config, globals, byron).flatMap { (entered, cfg, glob) =>
          if bootstrap then applyBootstrap(entered, h, raw, cfg)
          else applyFull(entered, h, raw, cfg, glob, phase2Observer, prepared)
        }

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

  /** The first Shelley-based block of a chain that has a Byron prefix: the Shelley ledger is built
    * from the final Byron UTxO (the old rules' hand-over, whose extras the reference's translation
    * does not have). Chains that start in Shelley pass through untouched.
    */
  private def enterShelley(
      state: NodeState,
      header: Header.Shelley,
      config: PraosConfig,
      globals: LedgerGlobals,
      byron: Option[ByronRules.Config]
  ): Either[BlockFailure, (NodeState, PraosConfig, LedgerGlobals)] =
    entry(state.byron, header, config, globals, byron).flatMap {
      case Entry.Within(cfg, glob) => Right((state, cfg, glob))
      case Entry.Entering(first, ledger, cfg, glob) =>
        byron.fold[Either[BlockFailure, (NodeState, PraosConfig, LedgerGlobals)]](
          Right((state, cfg, glob))
        ) { rules =>
          rules.handover(ledger, glob).leftMap(BlockFailure.Handover(_)).map { shelley =>
            (
              state
                .withByron(state.byron.finish.copy(ledger = None, firstShelleyEpoch = Some(first)))
                .copy(ledger = shelley),
              cfg,
              glob
            )
          }
        }
    }

  /** Byron: linkage, slot order, the PBFT rules and the ledger rules (see [[ByronRules]]). */
  private def applyByron(
      state: NodeState,
      header: Header.Byron,
      raw: Bytes,
      byron: Option[ByronRules.Config]
  ): Either[BlockFailure, NodeState] =
    byronStep(state.byron, state.boot, header, raw, byron).map((next, boot) =>
      state.withBoot(boot).withByron(next)
    )

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

  /** Pre-Conway eras: body commitment, TPraos or Praos header checks with the ledger-lite view,
    * then the lite effects of the transactions.
    */
  private def applyBootstrap(
      state: NodeState,
      header: Header.Shelley,
      raw: Bytes,
      config: PraosConfig
  ): Either[BlockFailure, NodeState] =
    val geometry = config.geometry
    val epoch = geometry.epochOf(header.slot)
    val boot = state.boot
    // Randomness stabilisation window: 3k/f up to Babbage, 4k/f from Conway (verified on Preview).
    val nonceWindow =
      if header.era.ordinal >= Era.Conway.ordinal then geometry.randomnessStabilisationWindow
      else geometry.stabilityWindow
    for
      decoded <- Block.decodeShelley(raw).leftMap(BlockFailure.Decode(_))
      block <- decoded match
        case b: Block.Shelley if b.era == header.era => Right(b)
        case b => Left(BlockFailure.EraAnnounced(b.era, header.era))
      _ <- Either.cond(
        block.header.hash == header.hash,
        (),
        BlockFailure.HeaderDiffers
      )
      _ <- Either.cond(
        block.bodySize == header.bodySize && block.bodyHash == header.bodyHash,
        (),
        BlockFailure.BodyCommitment
      )
      _ <- Either.cond(
        epoch >= boot.lite.epoch,
        (),
        BlockFailure.EpochPrecedesLedger(epoch)
      )
      lite <- LiteLedger.tick(boot.lite, header.slot, geometry).leftMap(BlockFailure.Lite(_))
      _ <- config.forks
        .check(
          header.era,
          epoch,
          header.protocolVersion._1,
          lite.params.protocolVersion._1,
          header.raw.size,
          lite.params.maxBlockHeaderSize,
          header.bodySize,
          lite.params.maxBlockBodySize,
          mainnet = false,
          // the bootstrap ledger does not follow Conway governance, nor does it model the header rule
          ledgerTracksVersion = false
        )
        .leftMap(BlockFailure.Envelope(_))
      validated <-
        if header.era.usesTPraos then
          TPraos
            .update(
              Praos.tick(state.protocol, header.era, epoch, lite.params.extraEntropy),
              header,
              lite.view,
              config
            )
            .map(v => v.state -> v.leader)
            .leftMap(BlockFailure.TPraos(_))
        else
          Praos
            .validate(
              Praos.tick(state.protocol, header.era, epoch),
              header,
              lite.view.stake,
              config,
              nonceWindow
            )
            .leftMap(BlockFailure.Praos(_))
      (praos, leader) = validated
      applied <- LiteLedger.applyBlock(lite, block, geometry).leftMap(BlockFailure.Lite(_))
    yield
      val previous = boot.stats.filter(_.epoch == epoch)
      val finished = boot.stats.filter(_.epoch != epoch).orElse(boot.completed)
      state
        .withProtocol(praos)
        .withByron(state.byron.finish)
        .withBoot(
          BootstrapState(
            active = true,
            era = Some(header.era),
            lite = applied,
            stats = Some(previous.getOrElse(EpochStats(epoch, header.era, 0, 0, 0)).record(leader)),
            completed = finished
          )
        )

  /** The ledger as a block meets it: the epoch boundaries between the ledger's epoch and the
    * block's (each one `Epoch.boundary`), then the per-block effects of the tick (`Epoch.tick`:
    * genesis delegations and the start of the reward update), with the block number stamped on the
    * changes the block makes to the UTxO set.
    */
  private def tickLedger(
      ledger: LedgerState,
      announced: Header.Shelley,
      geometry: EpochGeometry,
      globals: LedgerGlobals
  ): Either[BlockFailure, LedgerState] =
    val epoch = geometry.epochOf(announced.slot)
    for
      _ <- Either.cond(
        epoch >= ledger.epoch,
        (),
        BlockFailure.EpochPrecedesLedger(epoch)
      )
      // Stamped first: a boundary can change the set (the redeem outputs leave it on entering
      // Allegra), and a change stamped with the previous block may count as already stored.
      stamped = ledger.copy(utxo = ledger.utxo.at(announced.blockNo.toLong))
      crossed = (ledger.epoch + 1 to epoch).foldLeft(stamped)((s, e) =>
        Epoch.boundary(s, e, globals)
      )
      _ <- Either.cond(
        crossed.epoch == ledger.epoch || crossed.params.protocolMajor >= 9 ||
          crossed.total == globals.maxSupply,
        (),
        BlockFailure.SupplyInvariant(epoch, crossed.total, globals.maxSupply)
      )
    yield Epoch.tick(crossed, announced.slot, geometry.firstSlot(epoch), globals)

  /** The old side's tick of a block, for the differential harness: the state the block's header and
    * transactions are applied to, before them (design `ledger-mirror-step2.md` §2.5, stage `Tick`).
    *
    * @param entered
    *   the node state once the chain has entered Shelley (the Byron hand-over, on the first
    *   Shelley-based block), with the ledger as it was before the tick
    * @param ticked
    *   the ledger after the tick
    * @param epochsCrossed
    *   the epoch boundaries the tick ran
    * @param config
    *   the Praos configuration and the ledger constants once the first Shelley epoch is known
    */
  final case class OldTick(
      entered: NodeState,
      ticked: LedgerState,
      epochsCrossed: Int,
      config: PraosConfig,
      globals: LedgerGlobals
  )

  /** The tick of a Shelley-based block on the old rules; none for a Byron block, which has none. */
  def tick(
      state: NodeState,
      header: Header,
      config: PraosConfig,
      globals: LedgerGlobals,
      byron: Option[ByronRules.Config]
  ): Either[BlockFailure, Option[OldTick]] =
    header match
      case _: Header.Byron => Right(None)
      case h: Header.Shelley =>
        enterShelley(state, h, config, globals, byron).flatMap { (entered, cfg, glob) =>
          tickLedger(entered.ledger, h, cfg.geometry, glob).map(ticked =>
            Some(
              OldTick(
                entered,
                ticked,
                (cfg.geometry.epochOf(h.slot) - entered.ledger.epoch).toInt,
                cfg,
                glob
              )
            )
          )
        }

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
      if header.era.ordinal >= Era.Conway.ordinal then geometry.randomnessStabilisationWindow
      else geometry.stabilityWindow
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

  /** Full validation for every Shelley-based era: tick the ledger to the block's slot, validate the
    * header against the ticked pool distribution (so the leader threshold is always checked outside
    * the overlay schedule), then apply every transaction.
    */
  private def applyFull(
      state: NodeState,
      announced: Header.Shelley,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals,
      phase2Observer: Option[Phase2.Observation => Unit],
      prepared: Option[Prepared]
  ): Either[BlockFailure, NodeState] =
    val geometry = config.geometry
    val epoch = geometry.epochOf(announced.slot)
    val boot = state.boot
    for
      decoded <- prepared.fold(Block.decodeShelley(raw).leftMap(BlockFailure.Decode(_)))(p =>
        Right(p.block)
      )
      block <- decoded match
        case b: Block.Shelley if b.era == announced.era => Right(b)
        case b => Left(BlockFailure.EraAnnounced(b.era, announced.era))
      _ <- Either.cond(
        block.header.hash == announced.hash,
        (),
        BlockFailure.HeaderDiffers
      )
      _ <- Either.cond(
        block.bodySize == announced.bodySize && block.bodyHash == announced.bodyHash,
        (),
        BlockFailure.BodyCommitment
      )
      ticked <- tickLedger(state.ledger, announced, geometry, globals)
      validated <- headerChecks(
        state.protocol,
        announced,
        block.bodySize,
        config,
        globals,
        ticked.params,
        ticked.genDelegs,
        ticked.poolDistribution.distribution
      )
      (praos, leader) = validated
      applied0 <- block.transactions.zipWithIndex.foldLeftM((ticked, 0, ExUnits.zero)) {
        case ((s, refBytes, spent), (t, i)) =>
          val env = TxRules.Env(
            announced.slot,
            epoch,
            globals.network,
            firstSlotNextEpoch = geometry.firstSlot(epoch + 1),
            stabilityWindow = geometry.stabilityWindow,
            txIndex = i,
            updateQuorum = globals.updateQuorum,
            clock = Some(slot => BigInt(geometry.slotStart(slot).toEpochMilli)),
            observer = phase2Observer,
            verdicts = config.verdicts
          )
          prepared
            .flatMap(_.txs.lift(i))
            .getOrElse(Tx.fromBlock(t, block.era))
            .leftMap(BlockFailure.TxDecode(t.id, _))
            .flatMap { tx =>
              // Conway bounds the reference scripts of all transactions of a block.
              val used =
                if s.params.protocolMajor >= 9 then
                  refBytes.toLong + ConwayRules.refScriptsSize(
                    if s.params.protocolMajor <= 10 then ticked else s,
                    tx.body
                  )
                else 0L
              Either
                .cond(
                  used <= ConwayRules.MaxRefScriptSizePerBlock,
                  (),
                  BlockFailure.RefScriptsTooLarge(ConwayRules.MaxRefScriptSizePerBlock.toLong)
                )
                .flatMap { _ =>
                  // The block's execution units (every transaction's redeemers, valid or not) stay
                  // within the limit of the parameters in force when the block starts.
                  val units = spent + tx.witnesses.totalExUnits
                  blockExUnits(ticked.params, units)
                    .map(_ => ())
                    .flatMap(_ =>
                      TxRules(s, tx, env)
                        .leftMap(BlockFailure.Tx(_))
                        .map(next => (next, used.toInt, units))
                    )
                }
            }
      }
      applied = applied0._1
    yield
      val issuer = Blake2b.hash224.hash(announced.issuerVkey)
      // Blocks made in overlay slots are not counted towards pool performance.
      val counted = leader match
        case LeaderCheck.Overlay(_) => applied
        case _ =>
          applied.copy(blocksCurrent =
            applied.blocksCurrent.updatedWith(issuer)(n => Some(n.getOrElse(BigInt(0)) + 1))
          )
      val previous = boot.stats.filter(_.epoch == epoch)
      val finished = boot.stats.filter(_.epoch != epoch).orElse(boot.completed)
      state
        .withProtocol(praos)
        .withByron(state.byron.finish)
        .withBoot(
          boot.copy(
            era = Some(announced.era),
            stats =
              Some(previous.getOrElse(EpochStats(epoch, announced.era, 0, 0, 0)).record(leader)),
            completed = finished
          )
        )
        .copy(ledger = counted)
