// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

/** How a family of state codecs reads the governance proposals of a Conway state. */
enum ProposalsReading derives CanEqual:
  /** As the reference reads its own `state` file (`decSharePlusCBOR`, cardano-ledger f649f975,
    * `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Proposals.hs:401-406`): of two
    * proposals with one id the first is kept, and `mkProposals` rebuilds the store, refusing a
    * proposal that attaches to neither its lineage's root nor an earlier proposal of its lineage
    * ([[xyz.kd5ujc.scarab.ledger.Proposals.rebuild]]).
    */
  case Reference

  /** As a native snapshot wrote them: the proposals and the roots as they are. The native format is
    * Scarab's own (ADR 0006), and its snapshots were written before the rebuild was the rule of any
    * reader, so one holding a store the rebuild refuses is read as it was written: it is not
    * refused, which the restore would answer by deleting it, nor rewritten, which keeping only the
    * first of a repeated id would do.
    */
  case AsWritten
