// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import monocle.Lens
import xyz.kd5ujc.scarab.ledger.Mismatch
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayUTxO
import xyz.kd5ujc.scarab.ledger.rules.Test
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  ChainAccountState,
  EraTag,
  LedgerState,
  StakePool,
  Word64
}
import xyz.kd5ujc.scarab.plutus.ExUnits

// The functions of Shelley's, Alonzo's and Conway's BBODY that the BBODY of each era from Conway
// runs, as the reference writes them (cardano-ledger 9df9f98c, the `Rules/Bbody.hs` of Shelley,
// Alonzo and Conway, and `Shelley/BlockBody/Internal.hs`), each generic in its era over the block,
// header and parameter classes, with BBODY's environment and state.

/** `data BbodyEnv era` (`Shelley/Rules/Bbody.hs:94-97`, cardano-ledger 9df9f98c): the parameters in
  * force and the chain's account.
  */
final case class BbodyEnv[E <: EraTag](bbodyPp: PParams[E], bbodyAccount: ChainAccountState)

/** `data ShelleyBbodyState era = BbodyState (State (EraRule "LEDGERS" era)) BlocksMade`
  * (`Shelley/Rules/Bbody.hs:86-88`, cardano-ledger 9df9f98c): the ledger state and the blocks the
  * pools made in the epoch.
  */
final case class ShelleyBbodyState[E <: EraTag](ls: LedgerState[E], blocksMade: BlocksMade)

object ShelleyBbodyState:
  def ls[E <: EraTag]: Lens[ShelleyBbodyState[E], LedgerState[E]] =
    Lens[ShelleyBbodyState[E], LedgerState[E]](_.ls)(l => s => s.copy(ls = l))

/** Shelley's functions of BBODY (`Shelley/Rules/Bbody.hs`, `Shelley/BlockBody/Internal.hs`,
  * cardano-ledger 9df9f98c).
  */
object ShelleyBbody:
  /** `validateBlockBodySize` (`Shelley/Rules/Bbody.hs:251-272`, cardano-ledger 9df9f98c): the
    * body's size at the protocol version against the size its header declares, widened from
    * `Word32`.
    */
  def validateBlockBodySize[H, E <: EraTag](block: Block[H, E], protVer: ProtVer)(using
      h: EraBlockHeader[H, E],
      b: EraBlockBody[E]
  ): Test[ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[E]] =
    val actualSize = b.blockBodySize(protVer, block.blockBody)
    val sizeInBlockHeader = h.blockBodySizeBlockHeader(block)
    Test.failureUnless(
      actualSize == sizeInBlockHeader,
      new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[E](
        Mismatch(actualSize, sizeInBlockHeader)
      )
    )

  /** `validateBlockBodyHash` (`Shelley/Rules/Bbody.hs:274-295`, cardano-ledger 9df9f98c). */
  def validateBlockBodyHash[H, E <: EraTag](block: Block[H, E])(using
      h: EraBlockHeader[H, E],
      b: EraBlockBody[E]
  ): Test[ShelleyBbodyPredFailure.InvalidBodyHashBBODY[E]] =
    val actualHash = b.hashBlockBody(block.blockBody)
    val hashInBlockHeader = h.blockBodyHashBlockHeader(block)
    Test.failureUnless(
      actualHash.bytes == hashInBlockHeader.bytes,
      new ShelleyBbodyPredFailure.InvalidBodyHashBBODY[E](Mismatch(actualHash, hashInBlockHeader))
    )

  /** `incrBlocks` (`Shelley/BlockBody/Internal.hs:296-314`, cardano-ledger 9df9f98c): the block
    * counted for its issuer, taken as a pool (`coerceKeyRole`), unless its slot is of the overlay
    * schedule (`isOverlaySlot`, `Slot.hs:117-130`), which a decentralisation of zero never has.
    */
  def incrBlocks[H, E <: EraTag](
      block: Block[H, E],
      firstSlot: BigInt,
      d: UnitInterval,
      blocksMade: BlocksMade
  )(using h: EraBlockHeader[H, E]): BlocksMade =
    val bhSlot = h.slotNoBlockHeader(block).toBigInt
    val isOverlay =
      xyz.kd5ujc.scarab.ledger.rules.ShelleyBbody.isOverlaySlot(firstSlot, d.rational, bhSlot)
    if isOverlay then blocksMade
    else
      val hkAsStakePool = h.blockIssuerBlockHeader(block).coerceKeyRole[StakePool]
      BlocksMade(
        blocksMade.blocks.updatedWith(hkAsStakePool)(n =>
          Some(Word64.fromUnsigned(n.fold(1L)(_.bits + 1)))
        )
      )

/** Alonzo's functions of BBODY (`Alonzo/Rules/Bbody.hs`, cardano-ledger 9df9f98c). */
object AlonzoBbody:
  /** `validateExUnits` (`Alonzo/Rules/Bbody.hs:157-175`, cardano-ledger 9df9f98c): the units of the
    * block's transactions (`foldMap getTotalExUnits`) within the parameters' limit, point-wise.
    */
  def validateExUnits[E <: EraTag](txs: Vector[Tx[TxLevel.Top, E]], ppMax: ExUnits)(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[TxLevel.Top]
  ): Test[AlonzoBbodyPredFailure.TooManyExUnits[E]] =
    val txTotal = txs.foldLeft(ExUnits.zero)((acc, tx) => acc + t.getTotalExUnits[TxLevel.Top](tx))
    Test.failureUnless(
      txTotal.fits(ppMax),
      new AlonzoBbodyPredFailure.TooManyExUnits[E](Mismatch(txTotal, ppMax))
    )

/** Conway's functions of BBODY (`Conway/Rules/Bbody.hs`, cardano-ledger 9df9f98c). */
object ConwayBbody:
  /** `totalRefScriptSizeInBlock` (`Conway/Rules/Bbody.hs:371-384`, cardano-ledger 9df9f98c): up to
    * version 10 each transaction's reference scripts against the UTxO set; from 11 against it with
    * the outputs of the transactions before it, a valid one's (`txouts`) or an invalid one's
    * collateral return (`collOuts`), the set's own entry kept where both have one (`Map.union`).
    */
  def totalRefScriptSizeInBlock[E <: EraTag](
      protVer: ProtVer,
      txs: Vector[Tx[TxLevel.Top, E]],
      utxo: UTxO[E]
  )(using
      t: AlonzoEraTx[E],
      b: BabbageEraTxBody[E],
      lt: t.bodyOf.Level[TxLevel.Top],
      lb: b.Level[TxLevel.Top]
  ): Long =
    if protVer.pvMajor.value <= 10 then
      txs.foldLeft(0L)((sum, tx) => sum + ConwayUTxO.txNonDistinctRefScriptsSize(utxo, tx))
    else
      txs
        .foldLeft((utxo.unUTxO, 0L)) { case ((accUtxo, accSum), tx) =>
          val txBody = t.body[TxLevel.Top](tx)
          val toAdd = t.isValid(tx) match
            case IsPhase2Valid.Phase2Valid   => UTxOs.txouts[E, TxLevel.Top](txBody)
            case IsPhase2Valid.Phase2Invalid => BabbageUtxo.collOuts[E](txBody)
          val updatedUtxo = toAdd.unUTxO ++ accUtxo
          (updatedUtxo, accSum + ConwayUTxO.txNonDistinctRefScriptsSize(UTxO(accUtxo), tx))
        }
        ._2

  /** `validateBodyRefScriptsSizeTooBig` (`Conway/Rules/Bbody.hs:344-369`, cardano-ledger 9df9f98c):
    * the block's reference scripts within `maxRefScriptSizePerBlock`, widened from `Word32`.
    */
  def validateBodyRefScriptsSizeTooBig[E <: EraTag](
      pp: PParams[E],
      txs: Vector[Tx[TxLevel.Top, E]],
      utxo: UTxO[E]
  )(using
      t: AlonzoEraTx[E],
      b: BabbageEraTxBody[E],
      p: ConwayEraPParams[E],
      lt: t.bodyOf.Level[TxLevel.Top],
      lb: b.Level[TxLevel.Top]
  ): Test[ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[E]] =
    val totalSize = totalRefScriptSizeInBlock(p.ppProtocolVersionL.get(pp), txs, utxo)
    val maxSize = p.ppMaxRefScriptSizePerBlockG.get(pp).value.toLong
    Test.failureUnless(
      totalSize <= maxSize,
      new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[E](Mismatch(totalSize, maxSize))
    )
