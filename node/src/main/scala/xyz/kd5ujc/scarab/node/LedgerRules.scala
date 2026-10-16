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
import xyz.kd5ujc.scarab.ledger.byron.ByronLedger
import xyz.kd5ujc.scarab.storage.UtxoStore

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
        for
          // The block that enters Shelley replaces the genesis placeholder by the Byron UTxO, built
          // as a whole at stamp 0: a store that still holds the placeholder (written by a build
          // before the backing skipped Byron states) would treat those entries as stored. Start
          // it empty, so that every entry is written (see `UtxoBacking`).
          _ <- if state.byron.ledger.isDefined then store.reset else ().pure[F]
          fetched <- if wanted.isEmpty then Map.empty[TxIn, TxOut].pure[F] else store.read(wanted)
          next <- run(
            state.copy(ledger = state.ledger.copy(utxo = utxo.withResident(fetched))),
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

  /** Era transitions and completed-epoch summaries visible between two states. */
  def observe(geometry: EpochGeometry)(
      before: NodeState,
      after: NodeState,
      at: BlockRef
  ): List[FollowerEvent] =
    val era = after.boot.era
      .filter(e => !before.boot.era.contains(e))
      .map(to =>
        FollowerEvent.EraEntered(before.boot.era, to, at, after.geometry(geometry).epochOf(at.slot))
      )
    val byron = after.byron.completed
      .filter(c => !before.byron.completed.contains(c))
      .map(c =>
        FollowerEvent.ByronEpochValidated(c.epoch, c.boundary, c.verified, c.offSchedule, c.txs)
      )
    val epoch = after.boot.completed
      .filter(c => !before.boot.completed.contains(c))
      .map(c => FollowerEvent.EpochValidated(c.epoch, c.era, c.threshold, c.overlay, c.unchecked))
    val ledger = Option
      .when(after.ledger.epoch != before.ledger.epoch) {
        val l = after.ledger
        FollowerEvent.LedgerEpoch(
          l.epoch,
          l.utxo.size,
          l.accounts.size,
          l.pools.size,
          l.treasury,
          l.reserves,
          l.params.version,
          l.plutus
        )
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

  /** The first Shelley-based block of a chain that has a Byron prefix: the epoch the chain enters
    * Shelley is the configured one or, on a public network, the one the Byron ledger's update state
    * decided; the Shelley ledger is built from the final Byron UTxO. From then on the configuration
    * and the globals are those of the resolved epoch history. Chains that start in Shelley pass
    * through untouched.
    */
  private def enterShelley(
      state: NodeState,
      header: Header.Shelley,
      config: PraosConfig,
      globals: LedgerGlobals,
      byron: Option[ByronRules.Config]
  ): Either[BlockFailure, (NodeState, PraosConfig, LedgerGlobals)] =
    def resolve(first: BigInt) =
      (config.copy(geometry = config.geometry.resolved(first)), globals.copy(firstEpoch = first))
    state.byron.firstShelleyEpoch match
      case Some(first) =>
        val (cfg, glob) = resolve(first)
        Right((state, cfg, glob))
      case None =>
        (byron, state.byron.ledger) match
          case (Some(rules), Some(ledger)) =>
            for
              first <- rules.configuredShelleyEpoch
                .orElse(ByronLedger.transition(rules.ledger, ledger))
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
              shelley <- rules.handover(ledger, glob).leftMap(BlockFailure.Handover(_))
            yield (
              state
                .withByron(state.byron.finish.copy(ledger = None, firstShelleyEpoch = Some(first)))
                .copy(ledger = shelley),
              cfg,
              glob
            )
          case _ => Right((state, config, globals))

  /** Byron: linkage, slot order, the PBFT rules and the ledger rules (see [[ByronRules]]). */
  private def applyByron(
      state: NodeState,
      header: Header.Byron,
      raw: Bytes,
      byron: Option[ByronRules.Config]
  ): Either[BlockFailure, NodeState] =
    for
      cfg <- byron.toRight(BlockFailure.NoByronGenesis)
      ledger <- state.byron.ledger.toRight(BlockFailure.ByronAfterShelley)
      _ <- Either.cond(
        !state.boot.active && state.boot.era.forall(_ == Era.Byron),
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
        .update(ByronRules.State(state.byron.tip, ledger, state.byron.pbft), block, cfg)
        .leftMap(BlockFailure.Byron(_))
      (next, check) = validated
    yield
      val sameEpoch = state.byron.stats.filter(_.epoch == header.epoch)
      val base = if sameEpoch.isEmpty then state.byron.finish else state.byron
      state
        .withBoot(state.boot.copy(era = Some(Era.Byron)))
        .withByron(
          base.copy(
            tip = next.tip,
            ledger = Some(next.ledger),
            pbft = next.pbft,
            stats = Some(
              sameEpoch.getOrElse(ByronEpochStats(header.epoch, 0, 0, 0, 0)).record(check)
            )
          )
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
    val nonceWindow =
      if announced.era.ordinal >= Era.Conway.ordinal then geometry.randomnessStabilisationWindow
      else geometry.stabilityWindow
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
      _ <- Either.cond(
        epoch >= state.ledger.epoch,
        (),
        BlockFailure.EpochPrecedesLedger(epoch)
      )
      crossed = (state.ledger.epoch + 1 to epoch).foldLeft(state.ledger)((s, e) =>
        Epoch.boundary(s, e, globals)
      )
      _ <- Either.cond(
        crossed.epoch == state.ledger.epoch || crossed.params.protocolMajor >= 9 ||
          crossed.total == globals.maxSupply,
        (),
        BlockFailure.SupplyInvariant(epoch, crossed.total, globals.maxSupply)
      )
      ticked0 = Epoch.tick(crossed, announced.slot, geometry.firstSlot(epoch), globals)
      // Changes to the UTxO set made by this block are stamped with its number.
      ticked = ticked0.copy(utxo = ticked0.utxo.at(announced.blockNo.toLong))
      _ <- config.forks
        .check(
          announced.era,
          epoch,
          announced.protocolVersion._1,
          ticked.params.protocolMajor,
          announced.raw.size,
          ticked.params.maxBlockHeaderSize,
          block.bodySize,
          ticked.params.maxBlockBodySize,
          mainnet = globals.network == 1
        )
        .leftMap(BlockFailure.Envelope(_))
      pools = ticked.poolDistribution.distribution
      view = LedgerView(
        ticked.params.effectiveD,
        ticked.params.extraEntropy,
        ticked.genDelegs,
        StakeView.Known(pools)
      )
      validated <-
        if announced.era.usesTPraos then
          TPraos
            .update(
              Praos.tick(state.protocol, announced.era, epoch, ticked.params.extraEntropy),
              announced,
              view,
              config
            )
            .map(v => v.state -> v.leader)
            .leftMap(BlockFailure.TPraos(_))
        else
          Praos
            .validate(
              Praos.tick(state.protocol, announced.era, epoch),
              announced,
              StakeView.Known(pools),
              config,
              nonceWindow
            )
            .leftMap(BlockFailure.Praos(_))
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
