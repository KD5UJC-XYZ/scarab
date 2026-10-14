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
import lab.node.ledger.byron.{ByronLedger, ByronLedgerState}
import lab.node.storage.LedgerStore

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

/** Verdicts of one Byron epoch's blocks. */
final case class ByronEpochStats(
    epoch: BigInt,
    boundary: Long,
    verified: Long,
    offSchedule: Long,
    txs: Long
):
  def record(check: ByronCheck): ByronEpochStats = check match
    case ByronCheck.Boundary => copy(boundary = boundary + 1)
    case ByronCheck.Verified(onSchedule, n) =>
      copy(
        verified = verified + 1,
        offSchedule = offSchedule + (if onSchedule then 0 else 1),
        txs = txs + n
      )

/** Byron chain-follow state: the last header, the verdict counters of the current epoch, the Byron
  * ledger and PBFT window while the chain is in Byron, and the epoch in which the chain entered
  * Shelley once it has.
  */
final case class ByronState(
    tip: Option[ByronTip],
    stats: Option[ByronEpochStats],
    completed: Option[ByronEpochStats],
    ledger: Option[ByronLedgerState] = None,
    pbft: PBftState = PBftState.empty,
    firstShelleyEpoch: Option[BigInt] = None
):
  /** The Byron era is over (or the epoch changed): the current epoch's counters are final. */
  def finish: ByronState = copy(stats = None, completed = stats.orElse(completed))

object ByronState:
  val initial: ByronState = ByronState(None, None, None)

  /** The state of a chain that starts in Byron, with the ledger of the genesis. */
  def starting(ledger: ByronLedgerState): ByronState = initial.copy(ledger = Some(ledger))

/** Consensus and ledger state after a block. */
final case class NodeState(
    praos: PraosState,
    ledger: LedgerState,
    boot: BootstrapState,
    byron: ByronState = ByronState.initial
):
  /** The era history as this state knows it: a chain whose Byron span is derived learns the first
    * Shelley epoch when it enters Shelley.
    */
  def geometry(base: EpochGeometry): EpochGeometry =
    byron.firstShelleyEpoch.fold(base)(base.resolved)

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
    /** The backing store of the UTxO set and the ledger tables. Without one the state holds them
      * whole.
      */
    ledgerStore: Option[LedgerStore[F]] = None,
    /** Called with each state the rules produce (the node uses it to tell the verification pipeline
      * which epoch nonce is current).
      */
    afterBlock: Option[NodeState => F[Unit]] = None,
    /** Measures the pure rules (D8); without one they are not timed. */
    compute: Option[Compute[F]] = None
)(using
    Raise[F, ValidationError],
    Raise[F, StorageError]
) extends BlockRules[F, NodeState]:

  private val cpu: Compute[F] = compute.getOrElse(Compute.inline[F])

  def apply(state: NodeState, header: Header, raw: Bytes): F[NodeState] =
    def pure(s: NodeState, prepared: Option[NodeRules.Prepared]) =
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
    def finish(result: Either[(String, String), NodeState]): F[NodeState] =
      result.fold(
        (rule, reason) => ValidationError(header.point, rule, reason).raise[F, NodeState],
        next => afterBlock.fold(next.pure[F])(f => f(next).as(next))
      )
    (ledgerStore, header) match
      case (Some(store), h: Header.Shelley) if !bootstrap =>
        // The read set: what the block's transactions spend or reference, fetched before the pure
        // rules run (the rules then see a complete view of the entries they need). The rows of the
        // ledger tables they read are fetched on demand: a read the state cannot answer stops the
        // rules, the rows it names are fetched and the block is applied again (`completingF`).
        val prepared = NodeRules.prepare(h, raw, config.verdicts)
        val utxo = state.ledger.utxo
        val wanted = prepared.fold(Set.empty[TxIn])(p => utxo.missing(p.inputs))
        val crossing =
          state.geometry(config.geometry).epochOf(h.slot) > state.ledger.epoch
        for
          fetched <- if wanted.isEmpty then Map.empty[TxIn, TxOut].pure[F] else store.read(wanted)
          start = state.ledger.copy(utxo = utxo.withResident(fetched))
          // An epoch boundary reads whole tables: fetch them at once rather than by misses.
          loaded <- if crossing then LedgerStore.loadForBoundary(store, start) else start.pure[F]
          done <- LedgerStore.completingF(Some(store), loaded) { ledger =>
            val before = state.copy(ledger = ledger)
            cpu.timed(NodeRules.guardedLabel(before))(Table.guarded(pure(before, prepared)))
          }
          (_, result) = done
          next <- finish(result)
          flushed <- store.flushed
        yield next.copy(ledger = next.ledger.settled(flushed))
      case _ => cpu.timed(NodeRules.stepLabel(state))(pure(state, None)).flatMap(finish)

object NodeRules:
  /** The label a block application is recorded under: a block that crossed an epoch boundary or ran
    * Plutus scripts says so, as those are the steps that take long.
    */
  private[node] def stepLabel(
      before: NodeState
  )(result: Either[(String, String), NodeState]): String =
    result match
      case Left(_) => "rejected"
      case Right(after) =>
        if after.ledger.epoch != before.ledger.epoch || after.boot.completed != before.boot.completed
        then "epochBoundary"
        else if after.ledger.plutus.scripts != before.ledger.plutus.scripts then "plutus"
        else "block"

  private[node] def guardedLabel(
      before: NodeState
  )(result: Either[Table.Miss, Either[(String, String), NodeState]]): String =
    result.fold(_ => "refetch", stepLabel(before))

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
  ): Either[(String, String), (NodeState, PraosConfig, LedgerGlobals)] =
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
                  "byron" -> s"${header.era} block, but the Byron ledger has not decided on the transition to Shelley"
                )
              (cfg, glob) = resolve(first)
              epoch = cfg.geometry.epochOf(header.slot)
              _ <- Either.cond(
                epoch >= first && ledger.lastSlot < cfg.geometry.firstSlot(first),
                (),
                "byron" -> s"Shelley block in epoch $epoch, the transition is to epoch $first after Byron slot ${ledger.lastSlot}"
              )
              shelley <- rules.handover(ledger, glob).leftMap("ledger" -> _)
            yield (
              state.copy(
                ledger = shelley,
                byron = state.byron.finish.copy(ledger = None, firstShelleyEpoch = Some(first))
              ),
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
  ): Either[(String, String), NodeState] =
    for
      cfg <- byron.toRight("byron" -> "no Byron genesis configured")
      ledger <- state.byron.ledger.toRight("structure" -> "Byron block after the Shelley era began")
      _ <- Either.cond(
        !state.boot.active && state.boot.era.forall(_ == Era.Byron),
        (),
        "structure" -> "Byron block after the Shelley era began"
      )
      decoded <- Block.decode(raw, cfg.ledger.epochSlots).leftMap(e => "decode" -> e.message)
      block <- decoded match
        case b: Block.Byron => Right(b)
        case b              => Left("structure" -> s"${b.era} block announced as Byron")
      _ <- Either.cond(
        block.header.hash == header.hash,
        (),
        "structure" -> "block header differs from announced header"
      )
      // once the transition is settled (known and stable) no Byron block may be in or after it
      _ <- cfg.configuredShelleyEpoch
        .orElse(ByronLedger.transition(cfg.ledger, ledger))
        .filter(_ <= header.epoch)
        .toLeft(())
        .leftMap { first =>
          "structure" -> s"Byron block in epoch ${header.epoch}, the chain enters Shelley in epoch $first"
        }
      validated <- ByronRules
        .update(ByronRules.State(state.byron.tip, ledger, state.byron.pbft), block, cfg)
        .leftMap("byron" -> _)
      (next, check) = validated
    yield
      val sameEpoch = state.byron.stats.filter(_.epoch == header.epoch)
      val base = if sameEpoch.isEmpty then state.byron.finish else state.byron
      state.copy(
        boot = state.boot.copy(era = Some(Era.Byron)),
        byron = base.copy(
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
  ): Either[(String, String), NodeState] =
    val geometry = config.geometry
    val epoch = geometry.epochOf(header.slot)
    val boot = state.boot
    // Randomness stabilisation window: 3k/f up to Babbage, 4k/f from Conway (verified on Preview).
    val nonceWindow =
      if header.era.ordinal >= Era.Conway.ordinal then geometry.randomnessStabilisationWindow
      else geometry.stabilityWindow
    for
      decoded <- Block.decodeShelley(raw).leftMap(e => "decode" -> e.message)
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
      decoded <- prepared.fold(Block.decodeShelley(raw).leftMap(e => "decode" -> e.message))(p =>
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
      // Changes made by this block (the boundary it crosses included) carry its number.
      crossed = (state.ledger.epoch + 1 to epoch).foldLeft(
        state.ledger.at(announced.blockNo.toLong)
      )((s, e) => Epoch.boundary(s, e, globals))
      _ <- Either.cond(
        crossed.epoch == state.ledger.epoch || crossed.params.protocolMajor >= 9 ||
          crossed.total == globals.maxSupply,
        (),
        "ledger" -> s"supply invariant broken entering epoch $epoch: ${crossed.total} != ${globals.maxSupply}"
      )
      ticked = Epoch.tick(crossed, announced.slot, geometry.firstSlot(epoch), globals)
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
