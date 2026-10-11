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
final case class EpochStats(epoch: BigInt, threshold: Long, overlay: Long, unchecked: Long):
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

/** Consensus and ledger state after a block. */
final case class NodeState(praos: PraosState, ledger: LedgerState, boot: BootstrapState)

/** Full block validation: tick the ledger and Praos state to the block's epoch, validate the header
  * against the ticked pool distribution, then apply the body. Any failure rejects the block. Chains
  * that start before Conway are followed in bootstrap mode instead.
  */
final class NodeRules[F[_]: Applicative](config: PraosConfig, globals: LedgerGlobals)(using
    Raise[F, ValidationError]
) extends BlockRules[F, NodeState]:

  def apply(state: NodeState, header: Header, raw: Bytes): F[NodeState] =
    NodeRules
      .applyBlock(state, header, raw, config, globals)
      .fold(
        (rule, reason) => ValidationError(header.point, rule, reason).raise[F, NodeState],
        _.pure[F]
      )

object NodeRules:
  /** Era transitions and completed-epoch summaries visible between two states. */
  def observe(before: NodeState, after: NodeState, at: BlockRef): List[FollowerEvent] =
    val era = after.boot.era
      .filter(e => !before.boot.era.contains(e))
      .map(to => FollowerEvent.EraEntered(before.boot.era, to, at))
    val epoch = after.boot.completed
      .filter(c => !before.boot.completed.contains(c))
      .map(c => FollowerEvent.EpochValidated(c.epoch, c.threshold, c.overlay, c.unchecked))
    era.toList ++ epoch.toList

  /** The failure side carries the rule family and the reason. */
  def applyBlock(
      state: NodeState,
      header: Header,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals
  ): Either[(String, String), NodeState] =
    header match
      case h: Header.Shelley if h.era != Era.Conway || state.boot.active =>
        applyBootstrap(state, h, raw, config)
      case _ => applyConway(state, header, raw, config, globals)

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
        boot = BootstrapState(
          active = true,
          era = Some(header.era),
          lite = applied,
          stats = Some(previous.getOrElse(EpochStats(epoch, 0, 0, 0)).record(leader)),
          completed = finished
        )
      )

  private def applyConway(
      state: NodeState,
      header: Header,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals
  ): Either[(String, String), NodeState] =
    val geometry = config.geometry
    val epoch = geometry.epochOf(header.slot)
    for
      announced <- header match
        case h: Header.Shelley => Right(h)
        case h                 => Left("structure" -> s"${h.era} blocks are not supported")
      decoded <- Block.decode(raw).leftMap(e => "decode" -> e.message)
      block <- decoded match
        case b: Block.Shelley if b.era == Era.Conway => Right(b)
        case b => Left("structure" -> s"${b.era} blocks are not supported by the Conway rules")
      _ <- Either.cond(
        block.header.hash == header.hash,
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
      ticked = (state.ledger.epoch + 1 to epoch).foldLeft(state.ledger)((s, e) =>
        Epoch.boundary(s, e, globals)
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
      praos <- Praos
        .update(Praos.tick(state.praos, epoch), header, pools, config)
        .leftMap("praos" -> _)
      env = TxRules.Env(header.slot, epoch, globals.network)
      applied <- block.transactions.foldLeftM(ticked) { (s, t) =>
        Tx.fromBlock(t)
          .leftMap(r => "decode" -> s"tx ${t.id.hex.take(16)}: $r")
          .flatMap(tx => TxRules(s, tx, env).leftMap(f => "ledger" -> f.message))
      }
    yield
      val issuer = Blake2b.hash224.hash(announced.issuerVkey)
      state.copy(
        praos = praos,
        ledger = applied.copy(blocksCurrent =
          applied.blocksCurrent.updatedWith(issuer)(n => Some(n.getOrElse(BigInt(0)) + 1))
        )
      )
