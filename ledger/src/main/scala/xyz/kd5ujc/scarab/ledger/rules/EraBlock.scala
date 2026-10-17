// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockFailure,
  Header,
  Phase2,
  PlutusStats,
  Tx,
  TxEnv,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.{ScalusEvaluator, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.Era

/** A block of any Shelley-based era applied to the ledger state, on the era-indexed rules: the
  * hard-fork combinator's tick of the state to the block's slot ([[Hfc]]), then BBODY on the ticked
  * state ([[Bbody]] of Conway, [[ShelleyBbody]] of the eras before, one instance over the witness).
  * What the node does of a block in the ledger (`NodeRules.applyFull`) minus the header's own
  * checks (the envelope, TPraos, Praos), which are not the ledger's.
  *
  * The block is applied as a pure function of the state before it, the decoded block and what the
  * rules read of the network, so the harness can run it beside the old rules and compare.
  */
object EraBlock:
  /** What the rules read beyond the state: the network's constants and era history, which era each
    * epoch is, the signature verdicts, the Plutus evaluator, and a hook on phase-2 evaluations.
    */
  final case class Context(
      globals: Globals,
      eraAt: Hfc.EraAt,
      verdicts: Verdicts = Verdicts.Direct,
      evaluator: ScriptEvaluator[cats.Id] = ScalusEvaluator.default,
      observer: Option[Phase2.Observation => Unit] = None
  )

  /** What became of the block. */
  enum Outcome:
    /** The block is valid: the new-epoch state after it, in the era of the block, and the node's
      * counters as they were advanced by its scripts.
      */
    case Applied(nes: EraNes, plutus: PlutusStats)

    /** The state, ticked to the block's slot, is that of another era than the block's: the
      * combinator has not brought the state into the block's era (a state of an earlier era that
      * the era history does not move yet), which the rules do not take on.
      */
    case NotInEra(era: Era)

    /** The block is refused. */
    case Refused(failure: BlockFailure)

  /** The state at the block's slot: the checks that need no ledger state (the header is the
    * block's, the era the header announces is the block's, the epoch does not precede the
    * ledger's), then the hard-fork combinator's tick ([[Hfc.tick]]). The node validates the header
    * against this state (the envelope, the leader check) before the body is applied to it
    * ([[body]]).
    */
  def ticked(ctx: Context)(
      from: EraNes,
      header: Header.Shelley,
      block: Block.Shelley
  ): Either[BlockFailure, EraNes] =
    val globals = ctx.globals
    val epoch = globals.epochOf(header.slot)
    for
      _ <- Either.cond(
        block.era == header.era,
        (),
        BlockFailure.EraAnnounced(block.era, header.era)
      )
      _ <- Either.cond(block.header.hash == header.hash, (), BlockFailure.HeaderDiffers)
      _ <- Either.cond(epoch >= from.epoch.toBigInt, (), BlockFailure.EpochPrecedesLedger(epoch))
      slot <- SlotNo.fromBigInt(header.slot).leftMap(e => BlockFailure.Tick(TickFailure.Vocab(e)))
      ticked <- Hfc.tick(globals, ctx.eraAt)(from, slot).leftMap(BlockFailure.Tick(_))
    yield ticked

  /** The block `block` (the decoded `raw`, whose header is `header`) applied to `from`. */
  def apply(ctx: Context)(
      from: EraNes,
      header: Header.Shelley,
      block: Block.Shelley,
      txs: Vector[Either[String, Tx]],
      plutus: PlutusStats
  ): Outcome =
    ticked(ctx)(from, header, block) match
      case Left(failure) => Outcome.Refused(failure)
      case Right(t)      => body(ctx)(t, header, block, txs, plutus)

  /** BBODY on `ticked`, the state at the block's slot in the era of the block: the transactions of
    * the block, its commitment and the blocks made. A state of another era than the block's is
    * [[Outcome.NotInEra]].
    */
  def body(ctx: Context)(
      ticked: EraNes,
      header: Header.Shelley,
      block: Block.Shelley,
      txs: Vector[Either[String, Tx]],
      plutus: PlutusStats
  ): Outcome =
    block.transactions
      .zip(txs)
      .traverse((t, tx) => tx.leftMap(BlockFailure.TxDecode(t.id, _))) match
      case Left(failure) => Outcome.Refused(failure)
      case Right(decoded) =>
        ticked match
          case i: EraNes.InEra[e] =>
            if EraNes.eraOf(i.era) != header.era then Outcome.NotInEra(EraNes.eraOf(i.era))
            else
              i.era match
                case ShelleyBasedEra.SConway =>
                  conway(ctx, i.nes, header, block, decoded, plutus)
                case other =>
                  PreConway.of(other) match
                    case Some(pre) =>
                      given PreConway[e] = pre
                      shelley[e](ctx, i.nes, header, block, decoded, plutus)
                    case None => Outcome.NotInEra(EraNes.eraOf(other))

  /** The environment of the transactions of a block (and of BBODY's). */
  private def environment(ctx: Context, header: Header.Shelley): TxEnv =
    val globals = ctx.globals
    val geometry = globals.geometry
    val epoch = globals.epochOf(header.slot)
    TxEnv(
      header.slot,
      epoch,
      globals.networkId,
      firstSlotNextEpoch = geometry.firstSlot(epoch + 1),
      stabilityWindow = geometry.stabilityWindow,
      txIndex = 0,
      updateQuorum = globals.quorum,
      clock = Some(slot => BigInt(geometry.slotStart(slot).toEpochMilli)),
      evaluator = ctx.evaluator,
      observer = ctx.observer,
      verdicts = ctx.verdicts
    )

  /** The pool that made the block (the hash of the issuer's key). */
  private def issuer(header: Header.Shelley): Either[BlockFailure, KeyHash[StakePool]] =
    KeyHash
      .fromBytes[StakePool](Blake2b.hash224.hash(header.issuerVkey))
      .leftMap(e => BlockFailure.Tick(TickFailure.Vocab(e)))

  private def signal(
      header: Header.Shelley,
      block: Block.Shelley,
      txs: Vector[Tx],
      pool: KeyHash[StakePool]
  ): BbodySignal =
    BbodySignal(
      txs,
      pool,
      BodyCommitment(header.bodySize, header.bodyHash),
      BodyCommitment(BigInt(block.bodySize), block.bodyHash)
    )

  /** BBODY on the ticked state of an era before Conway. */
  private def shelley[E <: EraTag](
      ctx: Context,
      ticked: NewEpochState[E],
      header: Header.Shelley,
      block: Block.Shelley,
      txs: Vector[Tx],
      plutus: PlutusStats
  )(using pre: PreConway[E]): Outcome =
    val ls = ticked.es.ls
    val pp = pre.curPParams.get(ls.utxo)
    // the changes the block makes to the UTxO set carry its number
    val stamped = ls.copy[E](utxo =
      ls.utxo.copy[E](utxo = UtxoTables(ls.utxo.utxo.utxo.at(header.blockNo.toLong)))
    )
    issuer(header).flatMap { pool =>
      Sts(ShelleyBbody.sts[E])(
        ctx.globals,
        ShelleyBbodyEnv(environment(ctx, header), pp, ticked.es.chainAccount),
        ShelleyBbodyState(Counted(stamped, plutus), ticked.bcur),
        signal(header, block, txs, pool)
      )
    } match
      case Left(failure) => Outcome.Refused(failure)
      case Right(after) =>
        Outcome.Applied(
          EraNes.InEra(
            pre.era,
            ticked.copy[E](
              bcur = after.blocksMade,
              es = ticked.es.copy[E](ls = after.ledger.state)
            )
          ),
          after.ledger.plutus
        )

  private def conway(
      ctx: Context,
      ticked: NewEpochState[Conway],
      header: Header.Shelley,
      block: Block.Shelley,
      txs: Vector[Tx],
      plutus: PlutusStats
  ): Outcome = conwayBody(ctx, ticked, header, block, txs, plutus) match
    case Left(failure) => Outcome.Refused(failure)
    case Right((nes, counters)) =>
      Outcome.Applied(EraNes.InEra(ShelleyBasedEra.SConway, nes), counters)

  /** BBODY on the ticked state of Conway. */
  private def conwayBody(
      ctx: Context,
      ticked: NewEpochState[Conway],
      header: Header.Shelley,
      block: Block.Shelley,
      txs: Vector[Tx],
      plutus: PlutusStats
  ): Either[BlockFailure, (NewEpochState[Conway], PlutusStats)] =
    val ls = ticked.es.ls
    val pp = ls.utxo.gov.curPParams
    // the changes the block makes to the UTxO set carry its number
    val stamped = ls.copy[Conway](utxo =
      ls.utxo.copy(utxo = UtxoTables(ls.utxo.utxo.utxo.at(header.blockNo.toLong)))
    )
    issuer(header)
      .flatMap { pool =>
        Sts(Bbody)(
          ctx.globals,
          BbodyEnv(environment(ctx, header), pp, ticked.es.chainAccount.treasury),
          BbodyState(Counted(stamped, plutus), ticked.bcur),
          signal(header, block, txs, pool)
        )
      }
      .map { after =>
        (
          ticked.copy(
            bcur = after.blocksMade,
            es = ticked.es.copy(ls = after.ledger.state)
          ),
          after.ledger.plutus
        )
      }

  /** The transactions of a block, decoded as the node decodes them. */
  def decode(block: Block.Shelley): Vector[Either[String, Tx]] =
    block.transactions.map(t => Tx.fromBlock(t, block.era))
