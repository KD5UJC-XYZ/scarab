# Whole-transaction envelopes (decoder versions 2 to 9)

Status: Current. Evidence for [#688](https://github.com/KD5UJC-XYZ/scarab/issues/688) and
[#699](https://github.com/KD5UJC-XYZ/scarab/issues/699).

A whole transaction is what a client or a peer submits (`LocalTxSubmission`, `TxSubmission2`); a
block holds the parts of its transactions apart, so these decoders are reached through submission
only.

- Before Alonzo a transaction is the `RecD` record `ShelleyTx` of the body, the witness set and the
  auxiliary data or null (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Tx.hs:184-192`,
  cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`). `decodeCount (RecD cn) n` reads the
  list head around a decoder that reads nothing
  (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`), so a definite list
  of three items is the transaction, a non-empty indefinite list is refused ("Excess terms in
  array"), and an empty one `9f ff` is the detached record of the items after it (#687).
- From Alonzo it is `decodeRecordNamed "AlonzoTx"` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:532-553`)
  over the body, the witness set, a validity flag read only when the third item is a boolean, and the
  auxiliary data or null, in a definite or an indefinite list. Without the flag the transaction is
  valid and the list holds three items; with it, four.

`cases.jsonl` holds 91 lines, one case each, written by
`scripts/oracle/conway-decoding/tx_envelope_cases.py`:
- Shelley, Allegra and Mary: the definite list with null and with auxiliary data (and from Allegra
  its list form), the non-empty indefinite list, the detached record, and refusals of four items
  with a flag, two items and the empty list;
- Alonzo, Babbage and Conway: four items with the flag true and false, with and without auxiliary
  data, definite and indefinite; three items without the flag, with null and with each form of
  auxiliary data (map, list, tag 259), definite and indefinite, and in Conway with a detached record
  in the body and detached auxiliary data; and the refusals: a boolean third item with nothing after
  it, four items whose third is not a boolean, two and five items, the empty list, a head of four
  over three items and of three over four, and a detached envelope (`AlonzoTx` is not a `RecD`
  record).

The verdict (`accepted` and the complete `failure`) is what the unchanged released decoders did
with each: `decodeFullAnnotator` at the era's decoder version. Every case is read with `ids` and
`wire` set, so for an accepted transaction the harness also records the transaction id it hashes
(`txid`) and the transaction's `encCBOR` at the era's version (`wire`): the bytes a node relays
for a transaction it holds (`toCBOR (ShelleyTx _ tx) = wrapCBORinCBOR toCBOR tx`,
`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Mempool.hs:256-259`,
ouroboros-consensus `82ecba329d7d054340bf707d44fe6e9ac27cec40`) and whose size it measures
(`wireSizeTxF`, `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:667-679`). From Alonzo that is
`toCBORForMempoolSubmission`, a definite list of four with the flag written, whatever form was
submitted; before Alonzo a definite list of three. In all, 43 accepted and 48 refused.

`TxEnvelopeReferenceSuite` requires the same verdict and id from `Tx.fromEnvelope`, and from Alonzo
the relayed bytes from `Tx.envelope` of the parts `Tx.envelopeParts` read; `TxEnvelopeSubmissionSuite`
requires the same of the mempool's reading (`MempoolState.parseParts`) on the Conway lines.
`provenance.json` records the package versions and the hashes of the harness, the generator, its
input and output, and the corpus.

## Regenerating

Build `scarab-conway-decoding:local` as [the oracle README](../../../scripts/oracle/conway-decoding/README.md)
describes, then:

```sh
python3 scripts/oracle/conway-decoding/tx_envelope_cases.py --inputs /tmp/tx-envelope-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-conway-decoding:local /fixtures/tx-envelope-inputs.json > /tmp/tx-envelope-reference.json
python3 scripts/oracle/conway-decoding/tx_envelope_cases.py --inputs /tmp/tx-envelope-inputs.json \
  --reference /tmp/tx-envelope-reference.json --out fixtures/reference/tx-envelope/cases.jsonl
```

## Not covered here

- Detached records inside the parts of a transaction of every era: [the detached corpus](../recd-detached/README.md).
- The node-to-node transaction submission protocol's own check of a transaction before the mempool
  reads it ([#702](https://github.com/KD5UJC-XYZ/scarab/issues/702)).
- Scarab's mempool holds Conway transactions only; the relayed bytes of the other eras are recorded
  but not produced by Scarab.
