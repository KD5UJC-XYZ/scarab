// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  RefScripts,
  LedgerFailure,
  ProtocolParams,
  Tx,
  TxIn,
  TxOut,
  TxEnv,
  Utxo as NodeUtxo
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits

/** `BbodyEnv` (`Shelley/Rules/Bbody.hs`): the parameters in force, the treasury, and the
  * block-level context of its transactions (the slot, the clock, the evaluator).
  */
final case class BbodyEnv(ctx: TxEnv, pp: ProtocolParams, treasury: Coin)

/** `ShelleyBbodyState` (`Shelley/Rules/Bbody.hs:82`): the ledger state, and the blocks the pools
  * have made in the epoch (`nesBcur`).
  */
final case class BbodyState(ledger: ConwayLedger, blocksMade: BlocksMade)

/** What a block header commits to of the body: its size and its hash. */
final case class BodyCommitment(size: BigInt, hash: Bytes)

/** `BbodySignal`: the transactions of the block, the pool that made it (the hash of the issuer's
  * key), and what its header commits to of the body, with what the body is.
  */
final case class BbodySignal(
    txs: Vector[Tx],
    issuer: KeyHash[StakePool],
    declared: BodyCommitment,
    actual: BodyCommitment
)

/** BBODY (`Conway/Rules/Bbody.hs:268`, `conwayBbodyTransition` then `alonzoBbodyTransition` of
  * `Alonzo/Rules/Bbody.hs:186`, cardano-ledger f649f975), in the reference's order: the reference
  * scripts of the transactions against the limit of the block, the body against what its header
  * says, LEDGERS, the execution units of the transactions against the limit of the block, and the
  * block counted for its issuer. The header's protocol version (`HeaderProtVerTooHigh`, the first
  * check of `conwayBbodyTransition`) is checked with the header (`consensus.HardFork`).
  *
  *   - The reference scripts the transactions of a block use sum to at most one megabyte
  *     (`validateBodyRefScriptsSizeTooBig`, `Bbody.hs:314`). Up to protocol version 10 each
  *     transaction is measured against the UTxO set as the block meets it; from 11 against that set
  *     with the outputs of the transactions before it (`totalRefScriptSizeInBlock`).
  *   - The redeemers of all the transactions of a block (valid or not) use at most
  *     `maxBlockExUnits` of the parameters in force when the block starts (`validateExUnits`,
  *     `Alonzo/Rules/Bbody.hs:149-167`, after LEDGERS at line 212); the failure names the units of
  *     the whole block.
  *   - The slots of Conway have no overlay: every block counts for its issuer (`incrBlocks False`).
  *
  * The state is [[BbodyState]]; LEDGERS runs on its ledger part, its failures wrapped as the
  * block's (`BlockFailure.Tx`).
  */
object Bbody extends Sts[BbodyEnv, BbodyState, BbodySignal, BlockFailure]:
  given Embed[ConwayLedger, BbodyState, LedgerFailure, BlockFailure] =
    Embed(
      monocle.Lens[BbodyState, ConwayLedger](_.ledger)(l => s => s.copy(ledger = l)),
      BlockFailure.Tx(_)
    )

  /** The reference scripts of the block's transactions, in bytes (`totalRefScriptSizeInBlock`). */
  def refScriptsSize(pp: ProtocolParams, utxo: NodeUtxo, txs: Vector[Tx]): Long =
    val running = pp.protocolMajor > 10
    val (_, total) = txs.foldLeft((Map.empty[TxIn, TxOut], 0L)) { case ((added, sum), tx) =>
      val body = tx.body
      def lookup(in: TxIn): Option[TxOut] =
        if running then added.get(in).orElse(utxo.get(in)) else utxo.get(in)
      val size = (body.inputs.toSet ++ body.referenceInputs.toSet).iterator
        .flatMap(lookup)
        .map(_.referenceScriptSize)
        .sum
      val created =
        if !running then added
        else if tx.isValid then
          added ++ body.outputs.zipWithIndex.map { (out, i) => TxIn(body.id, i) -> out }
        else added ++ body.collateralReturn.map(r => TxIn(body.id, body.outputs.size) -> r)
      (created, sum + size)
    }
    total

  /** The execution units of all the transactions of the block when they exceed the limit, from
    * Alonzo (`validateExUnits`: `foldMap totExUnits txs`, compared point-wise).
    */
  def exUnitsExceeded(pp: ProtocolParams, txs: Vector[Tx]): Option[ExUnits] =
    if pp.protocolMajor < 5 then None
    else
      Some(txs.foldLeft(ExUnits.zero)(_ + _.witnesses.totalExUnits))
        .filterNot(_.fits(pp.maxBlockExUnits))

  def transition[F[_]: Monad](env: BbodyEnv, signal: BbodySignal)(using
      ask: Ask[F, Globals],
      st: Stateful[F, BbodyState],
      raise: Raise[F, BlockFailure]
  ): F[Unit] =
    val pp = env.pp
    for
      before <- st.get
      refBytes = refScriptsSize(pp, before.ledger.state.utxo.utxo.utxo, signal.txs)
      _ <- Sts.check[F, BlockFailure](
        pp.protocolMajor < 9 || refBytes <= RefScripts.MaxRefScriptSizePerBlock,
        BlockFailure.RefScriptsTooLarge(RefScripts.MaxRefScriptSizePerBlock.toLong)
      )
      _ <- Sts.check[F, BlockFailure](signal.declared == signal.actual, BlockFailure.BodyCommitment)
      _ <- Sts
        .trans[F, LedgersEnv, ConwayLedger, BbodyState, Vector[Tx], LedgerFailure, BlockFailure](
          Ledgers,
          LedgersEnv(env.ctx, env.treasury),
          signal.txs
        )
      _ <- Sts.require[F, BlockFailure](
        exUnitsExceeded(pp, signal.txs).map(BlockFailure.BlockExUnitsTooBig(_, pp.maxBlockExUnits))
      )
      _ <- st.modify(s =>
        s.copy(blocksMade =
          BlocksMade(
            s.blocksMade.blocks.updatedWith(signal.issuer)(n =>
              Some(Word64.fromUnsigned(n.fold(1L)(_.bits + 1)))
            )
          )
        )
      )
    yield ()
