// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import xyz.kd5ujc.scarab.ledger.{ProtocolParams, TxIn, Utxo as NodeUtxo}
import xyz.kd5ujc.scarab.plutus.ExUnits

/** The sizes and sums of a Dijkstra transaction or block over the flat decoder's types, as the flat
  * Dijkstra rules computed them: the sums the suites compare the era rules' with, and the measures
  * of the forger's and the mempool's suites.
  */
object DijkstraTxMeasures:
  def txIn(in: DijkstraTxIn): TxIn = TxIn(in.id, BigInt(in.index))

  /** `originalBytesSize` of a reference script: a Plutus script's program bytes, or a native
    * script's encoding.
    */
  def scriptSize(script: DijkstraScript): Long = script match
    case DijkstraScript.Native(raw)        => raw.size.toLong
    case DijkstraScript.Plutus(_, program) => program.size.toLong

  /** `txNonDistinctRefScriptsSize` (`Conway/UTxO.hs:166-170`, cardano-ledger 9df9f98c): the
    * reference scripts of the outputs that the set of a body's inputs and reference inputs spends
    * or reads, each counted once per input. `size` gives the size of the reference script of the
    * output an input names, if the set holds it.
    */
  def refScriptsSize(size: TxIn => Option[Long], body: DijkstraTxBody): Long =
    (body.inputs.toSet ++ body.referenceInputs.toSet).iterator
      .flatMap(in => size(txIn(in)))
      .sum

  /** The size of the reference script of an output of the UTxO set, if the set holds it. */
  def inUtxo(utxo: NodeUtxo): TxIn => Option[Long] =
    in => utxo.get(in).map(_.referenceScriptSize)

  /** `batchNonDistinctRefScriptsSize` (`Dijkstra/UTxO.hs:301-314`, cardano-ledger 9df9f98c): the
    * transaction's and each of its sub-transactions', `size` as for [[refScriptsSize]].
    */
  def batchRefScriptsSize(size: TxIn => Option[Long], tx: DijkstraTx): Long =
    refScriptsSize(size, tx.body) +
      tx.body.subTransactions.map(s => refScriptsSize(size, s.body)).sum

  private def redeemers(w: DijkstraWitnesses): ExUnits =
    w.redeemers.foldLeft(ExUnits.zero)((acc, r) => acc + ExUnits(r.memory, r.steps))

  /** `getTotalExUnits` of a Dijkstra transaction (`Dijkstra/Tx.hs:417-421`, cardano-ledger
    * 9df9f98c): its redeemers' units and those of its sub-transactions.
    */
  def totalExUnits(tx: DijkstraTx): ExUnits =
    tx.body.subTransactions.foldLeft(redeemers(tx.witnesses))((acc, s) =>
      acc + redeemers(s.witnesses)
    )

  /** `totalRefScriptSizeInBlock` (`Conway/Rules/Bbody.hs:371-384`, cardano-ledger 9df9f98c) of a
    * block's transactions: the outputs created are `txouts` 's ([[TxIn.txouts]]) and the collateral
    * return `collOuts` 's ([[TxIn.collOuts]]).
    */
  def blockRefScriptsSize(pp: ProtocolParams, utxo: NodeUtxo, txs: Vector[DijkstraTx]): Long =
    if pp.protocolMajor <= 10 then txs.map(tx => refScriptsSize(inUtxo(utxo), tx.body)).sum
    else
      txs
        .foldLeft((Map.empty[TxIn, Long], 0L)) { case ((added, sum), tx) =>
          val body = tx.body
          val created =
            if tx.isValid then TxIn.txouts(body.id, body.outputs)
            else TxIn.collOuts(body.id, body.outputs.size, body.collateralReturn).toVector
          val size = refScriptsSize(in => inUtxo(utxo)(in).orElse(added.get(in)), body)
          val sizes = created.map((in, o) => in -> o.referenceScript.fold(0L)(scriptSize))
          // The reference's Map.union keeps the existing entry
          // (Conway/Rules/Bbody.hs:379-384, cardano-ledger 9df9f98c).
          (sizes.toMap ++ added, sum + size)
        }
        ._2
