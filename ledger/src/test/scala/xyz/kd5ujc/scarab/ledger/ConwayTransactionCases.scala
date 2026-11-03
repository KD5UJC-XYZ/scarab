// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** Every whole Conway transaction of the recorded reference corpora, with the verdict of the
  * reference's decoder (`decodeFullAnnotator` of `Tx TopTx ConwayEra` at version 9) and, where the
  * corpus records it, its id: for the suites outside the ledger that read transactions as the
  * reference does (the node-to-node transaction submission, #708).
  */
object ConwayTransactionCases:
  /** A transaction, where it was recorded, and the reference's verdict. */
  final case class Case(
      corpus: String,
      name: String,
      raw: Bytes,
      accepted: Boolean,
      txid: Option[String]
  )

  def all: Vector[Case] =
    val recorded = ReferenceCorpus.Corpus.values.toVector.flatMap { corpus =>
      ReferenceCorpus
        .load(corpus)
        .fold(e => sys.error(s"${corpus.directory}: $e"), identity)
        .filter(c => c.era == Era.Conway && c.kind == ReferenceCorpus.Kind.Transaction)
        .map(c => Case(corpus.directory, c.name, c.raw, c.accepted, None))
    }
    val detached = DetachedRecordsReferenceSuite.load
      .filter(r => r.era == Era.Conway && r.kind == DetachedRecordsReferenceSuite.Kind.Transaction)
      .map(r => Case("recd-detached", r.name, r.raw, r.accepted, r.txid))
    val envelopes = TxEnvelopeReferenceSuite.load
      .filter(_.era == Era.Conway)
      .map(r => Case("tx-envelope", r.name, r.raw, r.accepted, r.txid))
    recorded ++ detached ++ envelopes
