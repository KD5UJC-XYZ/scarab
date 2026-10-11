// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.Applicative
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.Bytes
import lab.network.Era
import lab.node.consensus.*
import lab.node.ledger.*

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
final class NodeRules[F[_]: Applicative](
    config: PraosConfig,
    globals: LedgerGlobals,
    byron: Option[ByronRules.Config] = None,
    bootstrap: Boolean = false
)(using
    Raise[F, ValidationError]
) extends BlockRules[F, NodeState]:

  def apply(state: NodeState, header: Header, raw: Bytes): F[NodeState] =
    NodeRules
      .applyBlock(state, header, raw, config, globals, byron, bootstrap)
      .fold(
        (rule, reason) => ValidationError(header.point, rule, reason).raise[F, NodeState],
        _.pure[F]
      )

object NodeRules:
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
          l.unverified
        )
      }
    era.toList ++ byron.toList ++ epoch.toList ++ ledger.toList

  /** The failure side carries the rule family and the reason. */
  def applyBlock(
      state: NodeState,
      header: Header,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals,
      byron: Option[ByronRules.Config] = None,
      bootstrap: Boolean = false
  ): Either[(String, String), NodeState] =
    header match
      case h: Header.Byron                => applyByron(state, h, raw, byron)
      case h: Header.Shelley if bootstrap => applyBootstrap(state, h, raw, config)
      case h: Header.Shelley              => applyFull(state, h, raw, config, globals)

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
      _ <- Either.cond(
        header.raw.size <= lite.params.maxBlockHeaderSize,
        (),
        "structure" -> s"header size ${header.raw.size} exceeds ${lite.params.maxBlockHeaderSize}"
      )
      _ <- Either.cond(
        header.bodySize <= lite.params.maxBlockBodySize,
        (),
        "structure" -> s"body size ${header.bodySize} exceeds ${lite.params.maxBlockBodySize}"
      )
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
      globals: LedgerGlobals
  ): Either[(String, String), NodeState] =
    val geometry = config.geometry
    val epoch = geometry.epochOf(announced.slot)
    val boot = state.boot
    val nonceWindow =
      if announced.era.ordinal >= Era.Conway.ordinal then geometry.randomnessStabilisationWindow
      else geometry.stabilityWindow
    for
      decoded <- Block.decode(raw).leftMap(e => "decode" -> e.message)
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
      ticked = Epoch.tick(crossed, announced.slot, geometry.firstSlot(epoch), globals)
      _ <- Either.cond(
        announced.raw.size <= ticked.params.maxBlockHeaderSize || ticked.params.protocolMajor >= 9,
        (),
        "structure" -> s"header size ${announced.raw.size} exceeds ${ticked.params.maxBlockHeaderSize}"
      )
      _ <- Either.cond(
        block.bodySize <= ticked.params.maxBlockBodySize,
        (),
        "structure" -> s"body size ${block.bodySize} exceeds ${ticked.params.maxBlockBodySize}"
      )
      pools = PoolDistribution(
        ticked.poolDistribution.poolStake,
        ticked.poolDistribution.pools.map { case (id, p) => id -> p.vrfKeyHash }
      )
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
      applied <- block.transactions.zipWithIndex.foldLeftM(ticked) { case (s, (t, i)) =>
        val env = TxRules.Env(
          announced.slot,
          epoch,
          globals.network,
          firstSlotNextEpoch = geometry.firstSlot(epoch + 1),
          stabilityWindow = geometry.stabilityWindow,
          txIndex = i,
          updateQuorum = globals.updateQuorum
        )
        Tx.fromBlock(t, block.era)
          .leftMap(r => "decode" -> s"tx ${t.id.hex.take(16)}: $r")
          .flatMap(tx => TxRules(s, tx, env).leftMap(f => "ledger" -> f.message))
      }
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
