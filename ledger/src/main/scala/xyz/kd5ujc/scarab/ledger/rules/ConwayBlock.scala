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
  TxRules,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.{ScalusEvaluator, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.Era

/** A block of Conway applied to the ledger state, on the era-indexed rules: the hard-fork
  * combinator's tick of the state to the block's slot ([[Hfc]]), then BBODY ([[Bbody]]) on the
  * ticked state. What the node does of a block in the ledger (`NodeRules.applyFull`) minus the
  * header's own checks (the envelope, TPraos, Praos), which are not the ledger's.
  *
  * The block is applied as a pure function of the state before it, the decoded block and what the
  * rules read of the network, so the harness can run it beside the old rules and compare.
  */
object ConwayBlock:
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
    /** The block is valid: the new-epoch state after it, and the node's counters as they were
      * advanced by its scripts.
      */
    case Applied(nes: NewEpochState[Conway], plutus: PlutusStats)

    /** The state, ticked to the block's slot, is that of another era: the block is of Conway, and
      * the combinator has not brought the state into it (a state of an earlier era that the era
      * history does not move yet), which the rules do not take on.
      */
    case NotConway(era: Era)

    /** The block is refused. */
    case Refused(failure: BlockFailure)

  /** The block `block` (the decoded `raw`, whose header is `header`) applied to `from`. */
  def apply(ctx: Context)(
      from: EraNes,
      header: Header.Shelley,
      block: Block.Shelley,
      txs: Vector[Either[String, Tx]],
      plutus: PlutusStats
  ): Outcome =
    val globals = ctx.globals
    val epoch = globals.epochOf(header.slot)
    val checked: Either[BlockFailure, (Vector[Tx], SlotNo)] =
      for
        _ <- Either.cond(
          block.era == header.era,
          (),
          BlockFailure.EraAnnounced(block.era, header.era)
        )
        _ <- Either.cond(block.header.hash == header.hash, (), BlockFailure.HeaderDiffers)
        _ <- Either.cond(
          epoch >= from.epoch.toBigInt,
          (),
          BlockFailure.EpochPrecedesLedger(epoch)
        )
        decoded <- block.transactions.zip(txs).traverse { (t, tx) =>
          tx.leftMap(BlockFailure.TxDecode(t.id, _))
        }
        slot <- SlotNo.fromBigInt(header.slot).leftMap(e => BlockFailure.Tick(TickFailure.Vocab(e)))
      yield (decoded, slot)
    checked.flatMap { (decoded, slot) =>
      Hfc
        .tick(globals, ctx.eraAt)(from, slot)
        .leftMap(BlockFailure.Tick(_))
        .map((decoded, _))
    } match
      case Left(failure) => Outcome.Refused(failure)
      case Right((decoded, ticked)) =>
        ticked match
          case i: EraNes.InEra[e] =>
            i.era match
              case ShelleyBasedEra.SConway => body(ctx, i.nes, header, block, decoded, plutus)
              case other                   => Outcome.NotConway(EraNes.eraOf(other))

  /** BBODY on the ticked state of Conway. */
  private def body(
      ctx: Context,
      ticked: NewEpochState[Conway],
      header: Header.Shelley,
      block: Block.Shelley,
      txs: Vector[Tx],
      plutus: PlutusStats
  ): Outcome =
    val globals = ctx.globals
    val geometry = globals.geometry
    val epoch = globals.epochOf(header.slot)
    val ls = ticked.es.ls
    val pp = ls.utxo.gov.curPParams
    val env = TxRules.Env(
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
    // the changes the block makes to the UTxO set carry its number
    val stamped = ls.copy[Conway](utxo =
      ls.utxo.copy(utxo = UtxoTables(ls.utxo.utxo.utxo.at(header.blockNo.toLong)))
    )
    val issuer: Either[VocabError, KeyHash[StakePool]] =
      KeyHash.fromBytes[StakePool](Blake2b.hash224.hash(header.issuerVkey))
    val result = issuer.leftMap(e => BlockFailure.Tick(TickFailure.Vocab(e))).flatMap { pool =>
      Sts(Bbody)(
        globals,
        BbodyEnv(env, pp, ticked.es.chainAccount.treasury),
        BbodyState(Counted(stamped, plutus), ticked.bcur),
        BbodySignal(
          txs,
          pool,
          BodyCommitment(header.bodySize, header.bodyHash),
          BodyCommitment(BigInt(block.bodySize), block.bodyHash)
        )
      )
    }
    result match
      case Left(failure) => Outcome.Refused(failure)
      case Right(after) =>
        Outcome.Applied(
          ticked.copy(
            bcur = after.blocksMade,
            es = ticked.es.copy(ls = after.ledger.state)
          ),
          after.ledger.plutus
        )

  /** The transactions of a block, decoded as the node decodes them. */
  def decode(block: Block.Shelley): Vector[Either[String, Tx]] =
    block.transactions.map(t => Tx.fromBlock(t, block.era))
