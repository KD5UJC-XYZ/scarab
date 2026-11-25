// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.ProtVerParity
import xyz.kd5ujc.scarab.ledger.EbAnnouncement
import xyz.kd5ujc.scarab.ledger.era.{Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.protocol.{EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.LeiosEb

/** An endorser block the forger made, with its whole closure: `ForgedLeiosEb` (ouroboros-consensus
  * 2416493b `ouroboros-consensus/src/ouroboros-consensus/LeiosDemoTypes.hs:1262-1271`). The point
  * is the slot it was forged in and the body's hash; the closure is each transaction's hash and
  * bytes, in the body's order; the parity is that of the protocol version of the ledger state the
  * pool revalidated the transactions on, under which the transaction cache records them as
  * validated (`txsProtVerParity`). Only [[ForgedLeiosEb.forge]] makes one, so the point, the body
  * and the closure always agree.
  */
final case class ForgedLeiosEb private (
    point: EbPoint,
    body: LeiosEb,
    closure: NonEmptyVector[(LeiosTxHash, Bytes)],
    txsProtVerParity: ProtVerParity
):
  /** The header's announcement of this block, as `mkEb` makes it (`EbAnnouncement`, `mkEb`,
    * `Shelley/Ledger/Forge.hs:176-212` of ouroboros-consensus-cardano at 2416493b): its hash and
    * [[LeiosEb.encodedSize]], the length of its body's encoding.
    */
  def announcement: EbAnnouncement = EbAnnouncement(point.hash, body.encodedSize)

object ForgedLeiosEb:
  /** `forgeLeiosEb` (`LeiosDemoTypes.hs:1273-1295` at 2416493b, `:1257-1279` at d91b3557): the
    * endorser block of the era's transactions `txs` (`NonEmpty (Tx TopTx DijkstraEra)`), forged in
    * `slot` and revalidated under a protocol version of parity `parity`. Each transaction is
    * written as the block holds it (`serializeTxForLeios`, `toCBORForBlockInclusion`,
    * [[DijkstraTx.forBlockInclusion]]), which the body lists by its hash and length
    * ([[LeiosEb.of]]); the point's hash is the body's (`hashLeiosEb`).
    */
  def forge(
      slot: BigInt,
      parity: ProtVerParity,
      txs: NonEmptyVector[Tx[TxLevel.Top, Dijkstra]]
  ): ForgedLeiosEb =
    val bytes = txs.map(DijkstraTx.forBlockInclusion)
    val body = LeiosEb.of(bytes)
    ForgedLeiosEb(
      EbPoint(Word64.clamped(slot), LeiosEb.hashOf(body.transactions.toVector)),
      body,
      body.transactions.map(_._1).zipWith(bytes)((hash, tx) => hash -> tx),
      parity
    )
