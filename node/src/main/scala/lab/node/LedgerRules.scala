// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.Applicative
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.Bytes
import lab.network.Era
import lab.node.consensus.{PoolDistribution, Praos, PraosConfig, PraosState}
import lab.node.ledger.*

/** Consensus and ledger state after a block. */
final case class NodeState(praos: PraosState, ledger: LedgerState)

/** Full block validation: tick the ledger and the Praos state to the block's epoch, validate the
  * header against the ticked pool distribution, then apply the body. Any failure rejects the block.
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
  /** The failure side carries the rule family and the reason. */
  def applyBlock(
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
      NodeState(
        praos,
        applied.copy(blocksCurrent =
          applied.blocksCurrent.updatedWith(issuer)(n => Some(n.getOrElse(BigInt(0)) + 1))
        )
      )
