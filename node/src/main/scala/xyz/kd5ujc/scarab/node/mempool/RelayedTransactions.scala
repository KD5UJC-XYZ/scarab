// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.{DetachedRecords, Tx}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTx as FlatDijkstraTx
import xyz.kd5ujc.scarab.protocol.{Era, ProtocolError, ReferenceBuild, TxSubmission2}

/** The transactions of each Shelley-based era as the node-to-node transaction submission reads
  * them, in the replies it takes and the ones it sends (#702, #934).
  *
  * The reference reads a `GenTx` of the reply with the ledger's decoder of the era
  * ([[TxSubmission2.TransactionReader]]), from a stream of tokens: a transaction holding a `RecD`
  * record written as an empty indefinite list `9f ff` with its fields after it is read as one, its
  * body spanning those fields (`decodeCount (RecD cn) n`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`, cardano-ledger
  * f649f975), and its id is the hash of that span (`txIdTx`, `Shelley/Ledger/Mempool.hs:204-206`,
  * ouroboros-consensus 82ecba32). This reads the transaction as the mempool does
  * ([[DetachedRecords]] and `Tx.envelopeParts`: three parts, or four with the validity flag, #699)
  * and, for an id ([[body]]), checks them as far as it must, a body that is a map; a reply's
  * transaction is read whole ([[read]]).
  */
object RelayedTransactions extends TxSubmission2.TransactionReader:
  // every item is at least a byte, so the size of the transaction bounds the items and the
  // nesting, as in the reference, which has no bound of its own
  private val limits = Cbor.Limits(
    maxInputBytes = TxSubmission2.MaxOriginalBytes,
    maxItems = TxSubmission2.MaxOriginalBytes,
    maxStringBytes = TxSubmission2.MaxOriginalBytes
  )

  def body(original: Bytes): Either[ProtocolError, Bytes] = body(original, Era.Conway)

  /** The body of a transaction of `era`, read as [[read]] reads it, as far as its id needs. */
  override def body(original: Bytes, era: Era): Either[ProtocolError, Bytes] =
    DetachedRecords.read(original, limits, DetachedRecords.Shapes.transaction(era))(
      ProtocolError.Codec(_)
    ) { root =>
      Tx.envelopeParts(root, era)
        .leftMap(_ => ProtocolError.ExpectedConwayTransactionMembers)
        .flatMap(parts =>
          Either.cond(
            parts.body.headOption.exists(b => (b & 0xe0) == 0xa0),
            parts.body,
            ProtocolError.TransactionBodyIsNotAMap
          )
        )
    }

  def read(original: Bytes): Either[ProtocolError, Bytes] = read(original, Era.Conway)

  /** The transaction of `era` read whole, as the mempool reads one of that era
    * (`MempoolState.parseIn`: the parts by `Tx.envelopeParts`, each by the era's decoders through
    * `Tx.fromBlock`), so that the codec and the mempool give one verdict. A refusal is the codec's,
    * as in the reference (#708), whose codec reads a `GenTx` with the decoder of the era it names
    * (`dispatchDecoder`); a decoder that throws is not caught here, and the channel that runs the
    * codec ends the connection with a decoder defect, not as the peer's fault.
    */
  override def read(original: Bytes, era: Era): Either[ProtocolError, Bytes] =
    DetachedRecords.read(original, limits, DetachedRecords.Shapes.transaction(era))(
      ProtocolError.Codec(_)
    ) { root =>
      Tx.envelopeParts(root, era)
        .flatMap(parts => Tx.fromBlock(parts, era).as(parts.body))
        .leftMap(ProtocolError.UndecodableTransaction(_))
    }

  /** The reader of the selected build (REF-3): the Leios build's reads a Dijkstra transaction with
    * the decoder of its mempool form (`decodeDijkstraTopTx`, cardano-ledger 9df9f98c
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:146-169`, as the flat decoder that
    * decides what the era refuses reads it, [[FlatDijkstraTx.fromSubmission]]), its id the hash of
    * the body read; the release builds', whose node never enters Dijkstra, are this one. The
    * transaction's projection into the era's types is the mempool's ([[MempoolState.read]]), where
    * a failure of the node's own readings is no verdict and charges no peer.
    */
  def of(build: ReferenceBuild): TxSubmission2.TransactionReader = build match
    case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 => this
    case ReferenceBuild.LeiosB004fe0c                            => Leios

  private object Leios extends TxSubmission2.TransactionReader:
    def body(original: Bytes): Either[ProtocolError, Bytes] = RelayedTransactions.body(original)
    def read(original: Bytes): Either[ProtocolError, Bytes] = RelayedTransactions.read(original)

    override def body(original: Bytes, era: Era): Either[ProtocolError, Bytes] =
      read(original, era)

    override def read(original: Bytes, era: Era): Either[ProtocolError, Bytes] = era match
      case Era.Dijkstra =>
        Cbor
          .decode(original, limits)
          .leftMap(ProtocolError.Codec(_))
          .flatMap(n =>
            FlatDijkstraTx
              .submitted(n)
              .bimap(ProtocolError.UndecodableTransaction(_), _.body.raw)
          )
      case Era.Byron | Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage |
          Era.Conway =>
        RelayedTransactions.read(original, era)
