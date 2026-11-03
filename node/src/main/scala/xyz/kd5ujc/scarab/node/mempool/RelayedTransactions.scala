// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.{DetachedRecords, Tx}
import xyz.kd5ujc.scarab.protocol.{Era, ProtocolError, TxSubmission2}

/** Conway's transactions as the node-to-node transaction submission reads them, in the replies it
  * takes and the ones it sends (#702).
  *
  * The reference reads a `GenTx` of the reply with the ledger's decoder of the era
  * ([[TxSubmission2.TransactionReader]]), from a stream of tokens: a transaction holding a `RecD`
  * record written as an empty indefinite list `9f ff` with its fields after it is read as one, its
  * body spanning those fields (`decodeCount (RecD cn) n`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`, cardano-ledger
  * f649f975), and its id is the hash of that span (`txIdTx`, `Shelley/Ledger/Mempool.hs:204-206`,
  * ouroboros-consensus 82ecba32). This reads the transaction as the mempool does
  * ([[DetachedRecords]] and `Tx.envelopeParts`: three parts, or four with the validity flag, #699)
  * and checks them as far as the protocol must to know its id, a body that is a map. The ledger's
  * decoders of the parts run in the mempool.
  */
object RelayedTransactions extends TxSubmission2.TransactionReader:
  // every item is at least a byte, so the size of the transaction bounds the items and the
  // nesting, as in the reference, which has no bound of its own
  private val limits = Cbor.Limits(
    maxInputBytes = TxSubmission2.MaxOriginalBytes,
    maxItems = TxSubmission2.MaxOriginalBytes,
    maxStringBytes = TxSubmission2.MaxOriginalBytes
  )

  def body(original: Bytes): Either[ProtocolError, Bytes] =
    DetachedRecords.read(original, limits, DetachedRecords.Shapes.transaction(Era.Conway))(
      ProtocolError.Codec(_)
    ) { root =>
      Tx.envelopeParts(root, Era.Conway)
        .leftMap(_ => ProtocolError.ExpectedConwayTransactionMembers)
        .flatMap(parts =>
          Either.cond(
            parts.body.headOption.exists(b => (b & 0xe0) == 0xa0),
            parts.body,
            ProtocolError.TransactionBodyIsNotAMap
          )
        )
    }
