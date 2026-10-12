// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.Monad
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.Bytes
import lab.network.Era
import lab.node.consensus.*
import lab.node.ledger.*
import lab.node.storage.UtxoStore

/** Verdicts of one epoch's headers in bootstrap mode. */
final case class EpochStats(
    epoch: BigInt,
    era: Era,
    threshold: Long,
    overlay: Long,
    unchecked: Long
):
  def total: Long = threshold + overlay + unchecked
  def record(leader: LeaderCheck): EpochStats = leader match
    case LeaderCheck.Threshold    => copy(threshold = threshold + 1)
    case LeaderCheck.Overlay(_)   => copy(overlay = overlay + 1)
    case LeaderCheck.Unchecked(_) => copy(unchecked = unchecked + 1)

/** Pre-Conway (bootstrap) follow mode: the ledger-lite state, the current era and per-epoch verdict
  * counters. `active` turns on with the first Alonzo-or-earlier block; from then on every block,
  * Conway included, is followed in bootstrap mode until a full ledger can take over.
  */
final case class BootstrapState(
    active: Boolean,
    era: Option[Era],
    lite: LiteLedger,
    stats: Option[EpochStats],
    completed: Option[EpochStats]
)

object BootstrapState:
  def initial(lite: LiteLedger): BootstrapState = BootstrapState(false, None, lite, None, None)

/** Verdicts of one Byron epoch's headers. */
final case class ByronEpochStats(epoch: BigInt, boundary: Long, verified: Long, unchecked: Long):
  def record(check: ByronCheck): ByronEpochStats = check match
    case ByronCheck.Boundary     => copy(boundary = boundary + 1)
    case ByronCheck.Verified     => copy(verified = verified + 1)
    case ByronCheck.Unchecked(_) => copy(unchecked = unchecked + 1)

/** Byron chain-follow state: the last header and the verdict counters of the current epoch. */
final case class ByronState(
    tip: Option[ByronTip],
    stats: Option[ByronEpochStats],
    completed: Option[ByronEpochStats]
):
  /** The Byron era is over (or the epoch changed): the current epoch's counters are final. */
  def finish: ByronState = copy(stats = None, completed = stats.orElse(completed))

object ByronState:
  val initial: ByronState = ByronState(None, None, None)

/** Consensus and ledger state after a block. */
final case class NodeState(
    praos: PraosState,
    ledger: LedgerState,
    boot: BootstrapState,
    byron: ByronState = ByronState.initial
)

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
    /** Called with each state the rules produce (the node uses it to tell the verification pipeline
      * which epoch nonce is current).
      */
    afterBlock: Option[NodeState => F[Unit]] = None
)(using
    Raise[F, ValidationError]
) extends BlockRules[F, NodeState]:

  def apply(state: NodeState, header: Header, raw: Bytes): F[NodeState] =
    def run(s: NodeState, prepared: Option[NodeRules.Prepared]): F[NodeState] =
      NodeRules
        .applyBlock(s, header, raw, config, globals, byron, bootstrap, phase2Observer, prepared)
        .fold(
          (rule, reason) => ValidationError(header.point, rule, reason).raise[F, NodeState],
          next => afterBlock.fold(next.pure[F])(f => f(next).as(next))
        )
    (utxoStore, header) match
      case (Some(store), h: Header.Shelley) if !bootstrap =>
        // The read set: what the block's transactions spend or reference, fetched before the pure
        // rules run (the rules then see a complete view of the entries they need).
        val prepared = NodeRules.prepare(h, raw, config.verdicts)
        val utxo = state.ledger.utxo
        val wanted = prepared.fold(Set.empty[TxIn])(p => utxo.missing(p.inputs))
        for
          fetched <- if wanted.isEmpty then Map.empty[TxIn, TxOut].pure[F] else store.read(wanted)
          next <- run(
            state.copy(ledger = state.ledger.copy(utxo = utxo.withResident(fetched))),
            prepared
          )
          flushed <- store.flushed
        yield next.copy(ledger = next.ledger.copy(utxo = next.ledger.utxo.settled(flushed)))
      case _ => run(state, None)

object NodeRules:
  /** A block decoded once for both the read-set fetch and the rules. */
  final case class Prepared(block: Block.Shelley, txs: Vector[Either[String, Tx]]):
    /** Everything the transactions can read from the UTxO set (not what the block itself creates).
      */
    def inputs: Vector[TxIn] =
      val created = txs.collect { case Right(t) => t.body.id }.toSet
      txs.flatMap {
        case Right(t) =>
          (t.body.inputs ++ t.body.referenceInputs ++ t.body.collateral).filterNot(i =>
            created.contains(i.id)
          )
        case Left(_) => Vector.empty
      }

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
    Block.decode(raw).toOption.collect { case b: Block.Shelley =>
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
      .map(to => FollowerEvent.EraEntered(before.boot.era, to, at, geometry.epochOf(at.slot)))
    val byron = after.byron.completed
      .filter(c => !before.byron.completed.contains(c))
      .map(c => FollowerEvent.ByronEpochValidated(c.epoch, c.boundary, c.verified, c.unchecked))
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
  ): Either[(String, String), Unit] =
    Either.cond(
      params.protocolMajor < 5 || units.fits(params.maxBlockExUnits),
      (),
      "ledger" -> s"block execution units ${units.mem} mem / ${units.steps} steps exceed the maximum ${params.maxBlockExUnits.mem} / ${params.maxBlockExUnits.steps}"
    )

  /** The failure side carries the rule family and the reason. */
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
  ): Either[(String, String), NodeState] =
    header match
      case h: Header.Byron                => applyByron(state, h, raw, byron)
      case h: Header.Shelley if bootstrap => applyBootstrap(state, h, raw, config)
      case h: Header.Shelley => applyFull(state, h, raw, config, globals, phase2Observer, prepared)

  /** Byron: linkage, slot order, leader delegation and signature (see [[ByronRules]]). */
  private def applyByron(
      state: NodeState,
      header: Header.Byron,
      raw: Bytes,
      byron: Option[ByronRules.Config]
  ): Either[(String, String), NodeState] =
    for
      cfg <- byron.toRight("byron" -> "no Byron genesis configured")
      _ <- Either.cond(
        !state.boot.active && state.boot.era.forall(_ == Era.Byron),
        (),
        "structure" -> "Byron block after the Shelley era began"
      )
      decoded <- Block.decode(raw, cfg.epochSlots).leftMap(e => "decode" -> e.message)
      block <- decoded match
        case b: Block.Byron => Right(b)
        case b              => Left("structure" -> s"${b.era} block announced as Byron")
      _ <- Either.cond(
        block.header.hash == header.hash,
        (),
        "structure" -> "block header differs from announced header"
      )
      validated <- ByronRules.update(state.byron.tip, header, cfg).leftMap("byron" -> _)
      (tip, check) = validated
    yield
      val sameEpoch = state.byron.stats.filter(_.epoch == header.epoch)
      val base = if sameEpoch.isEmpty then state.byron.finish else state.byron
      state.copy(
        boot = state.boot.copy(era = Some(Era.Byron)),
        byron = base.copy(
          tip = Some(tip),
          stats = Some(
            sameEpoch.getOrElse(ByronEpochStats(header.epoch, 0, 0, 0)).record(check)
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
  ): Either[(String, String), NodeState] =
    val geometry = config.geometry
    val epoch = geometry.epochOf(header.slot)
    val boot = state.boot
    // Randomness stabilisation window: 3k/f up to Babbage, 4k/f from Conway (verified on Preview).
    val nonceWindow =
      if header.era.ordinal >= Era.Conway.ordinal then geometry.randomnessStabilisationWindow
      else geometry.stabilityWindow
    for
      decoded <- Block.decode(raw).leftMap(e => "decode" -> e.message)
      block <- decoded match
        case b: Block.Shelley if b.era == header.era => Right(b)
        case b => Left("structure" -> s"${b.era} block announced as ${header.era}")
      _ <- Either.cond(
        block.header.hash == header.hash,
        (),
        "structure" -> "block header differs from announced header"
      )
      _ <- Either.cond(
        block.bodySize == header.bodySize && block.bodyHash == header.bodyHash,
        (),
        "structure" -> "body does not match the header commitment"
      )
      _ <- Either.cond(
        epoch >= boot.lite.epoch,
        (),
        "structure" -> s"block epoch $epoch precedes ledger epoch"
      )
      lite <- LiteLedger.tick(boot.lite, header.slot, geometry).leftMap("ledger" -> _)
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
        .leftMap(e => "envelope" -> e.message)
      validated <-
        if header.era.usesTPraos then
          TPraos
            .update(
              Praos.tick(state.praos, epoch, lite.params.extraEntropy),
              header,
              lite.view,
              config
            )
            .map(v => v.state -> v.leader)
            .leftMap("tpraos" -> _.message)
        else
          Praos
            .validate(
              Praos.tick(state.praos, epoch),
              header,
              lite.view.stake,
              config,
              nonceWindow
            )
            .leftMap("praos" -> _)
      (praos, leader) = validated
      applied <- LiteLedger.applyBlock(lite, block, geometry).leftMap("ledger" -> _)
    yield
      val previous = boot.stats.filter(_.epoch == epoch)
      val finished = boot.stats.filter(_.epoch != epoch).orElse(boot.completed)
      state.copy(
        praos = praos,
        byron = state.byron.finish,
        boot = BootstrapState(
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
      phase2Observer: Option[Phase2.Observation => Unit] = None,
      prepared: Option[Prepared] = None
  ): Either[(String, String), NodeState] =
    val geometry = config.geometry
    val epoch = geometry.epochOf(announced.slot)
    val boot = state.boot
    val nonceWindow =
      if announced.era.ordinal >= Era.Conway.ordinal then geometry.randomnessStabilisationWindow
      else geometry.stabilityWindow
    for
      decoded <- prepared.fold(Block.decode(raw).leftMap(e => "decode" -> e.message))(p =>
        Right(p.block)
      )
      block <- decoded match
        case b: Block.Shelley if b.era == announced.era => Right(b)
        case b => Left("structure" -> s"${b.era} block announced as ${announced.era}")
      _ <- Either.cond(
        block.header.hash == announced.hash,
        (),
        "structure" -> "block header differs from announced header"
      )
      _ <- Either.cond(
        block.bodySize == announced.bodySize && block.bodyHash == announced.bodyHash,
        (),
        "structure" -> "body does not match the header commitment"
      )
      _ <- Either.cond(
        epoch >= state.ledger.epoch,
        (),
        "structure" -> s"block epoch $epoch precedes ledger epoch"
      )
      crossed = (state.ledger.epoch + 1 to epoch).foldLeft(state.ledger)((s, e) =>
        Epoch.boundary(s, e, globals)
      )
      _ <- Either.cond(
        crossed.epoch == state.ledger.epoch || crossed.params.protocolMajor >= 9 ||
          crossed.total == globals.maxSupply,
        (),
        "ledger" -> s"supply invariant broken entering epoch $epoch: ${crossed.total} != ${globals.maxSupply}"
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
        .leftMap(e => "envelope" -> e.message)
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
              Praos.tick(state.praos, epoch, ticked.params.extraEntropy),
              announced,
              view,
              config
            )
            .map(v => v.state -> v.leader)
            .leftMap("tpraos" -> _.message)
        else
          Praos
            .validate(
              Praos.tick(state.praos, epoch),
              announced,
              StakeView.Known(pools),
              config,
              nonceWindow
            )
            .leftMap("praos" -> _)
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
            .leftMap(r => "decode" -> s"tx ${t.id.hex.take(16)}: $r")
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
                  "ledger" -> s"reference scripts of the block exceed ${ConwayRules.MaxRefScriptSizePerBlock} bytes"
                )
                .flatMap { _ =>
                  // The block's execution units (every transaction's redeemers, valid or not) stay
                  // within the limit of the parameters in force when the block starts.
                  val units = spent + tx.witnesses.totalExUnits
                  blockExUnits(ticked.params, units)
                    .map(_ => ())
                    .flatMap(_ =>
                      TxRules(s, tx, env)
                        .leftMap(f => "ledger" -> f.message)
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
      state.copy(
        praos = praos,
        byron = state.byron.finish,
        ledger = counted,
        boot = boot.copy(
          era = Some(announced.era),
          stats =
            Some(previous.getOrElse(EpochStats(epoch, announced.era, 0, 0, 0)).record(leader)),
          completed = finished
        )
      )
