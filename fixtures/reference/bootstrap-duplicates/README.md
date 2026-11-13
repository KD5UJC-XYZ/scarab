# Bootstrap duplicates: one bootstrap witness per address root

Status: Current.

## The rule

A witness set reads its bootstrap witnesses into a `Set`: with `Set.fromList <$> decodeList decCBOR`
in Shelley, Allegra and Mary (`eras/shelley/impl/src/Cardano/Ledger/Shelley/TxWits.hs:246-249`), and
in Alonzo, Babbage and Conway the same below decoder version 9 and
`Set.fromList . NE.toList <$> decodeNonEmptyList decCBOR` from 9 below 12
(`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:613-668`, `addrWitsSetDecoder`), at cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`. Only from version 12 is a repeated element refused
(`decodeNonEmptySetLikeEnforceNoDuplicates`,
`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs:1043-1107`, the same at
`048b78d2807ce9644dbe483c605625824f6be88f`). `Ord BootstrapWitness` compares `bootstrapWitKeyHash`
alone (`libs/cardano-ledger-core/src/Cardano/Ledger/Keys/Bootstrap.hs:108-109`), so bootstrap witnesses
with one address root are one element: they may differ in their signatures, or in how the chain code
and the attributes split the bytes the root hashes (`Bootstrap.hs:112-146`). `Data.Set.insert`
replaces an equal element, so the set keeps the last of them read, and holds its elements in the
order of their roots. `Ord (WitVKey kr)` compares the key's hash and then the signature's hash
(`libs/cardano-ledger-core/src/Cardano/Ledger/Keys/WitVKey.hs:58-68`): two vkey witnesses of one key
with different signatures are two elements.

`validateVerifiedWits` (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Utxow.hs:391-407`) checks
`verifyBootstrapWit` (`Bootstrap.hs:161-169`) of each bootstrap witness the set holds, and so of the
kept one alone; every era's UTXOW runs it (Shelley `Utxow.hs:309`, Alonzo `Alonzo/Rules/Utxow.hs:348`,
Babbage `Babbage/Rules/Utxow.hs:366`, which Conway's UTXOW runs, `Conway/Rules/Utxow.hs:198`). The
witnesses a transaction needs must be a subset of those it provides (`validateNeededWitnesses`,
`Shelley/Rules/Utxow.hs:409-423`), so a bootstrap witness no input needs is tolerated.

## The cases

`cases.jsonl` holds a line per case, written by
`scripts/oracle/conway-decoding/bootstrap_duplicate_cases.py`. The keys are Ed25519 keys derived from
fixed seeds, and each signature is real: `valid` and `valid2` sign the body's hash (the second with
another nonce), `invalid` and `invalid2` sign other bytes. `bootstraps` and `vkeys` list the witnesses
as written, `signer:signature`: `A` is one key with a 32-byte chain code and the attributes `a0`;
`Asplit` is the same key with a 33-byte chain code (the 32 bytes and `a0`) and no attributes, the same
root; `L` and `H` are keys whose roots sort below and above `A`'s; `K` signs vkey witnesses.

- In each era from Shelley to Conway, read at the era's version (`kind` "transaction"), 28 whole
  transactions: bootstrap witnesses of `A`'s root with valid and invalid signatures in each order,
  three of them (one repeated byte for byte around another), among a witness of a lower or a higher
  root, with the chain code and attributes split differently, and the controls: two roots, the same
  witness twice, and two vkey witnesses of `K`.
- In each era, the witness set alone (`kind` "witnesses") at each decoder version from the era's to
  12: `A:invalid, A:valid`, `A:valid, A:valid`, and the vkey witnesses `K:valid, K:invalid`.

A line records the reference's verdict (`accepted` and the complete `failure`) from the unchanged
released decoders, `decodeFullAnnotator` of the era's `TxWits` or `Tx` at the line's `version`; for an
accepted line, the `bootstrapWitKeyHash` of each bootstrap witness kept (`keyHashes`) and the `encCBOR`
of each bootstrap and vkey witness the sets kept, in the order of each set (`keptBootstraps`,
`keptVkeys`); and for a transaction the result of the released `validateVerifiedWits` on it
(`verified`) and the key of each witness it found invalid (`invalidKeys`). A refusal names `rule`, a
fragment of the reference failure. The counts are in `provenance.json`.

What the reference did: below version 12, in every era and at every version, a witness set with
several bootstrap witnesses of one root keeps the last of them (also when it repeats an earlier one
byte for byte), and the transaction's signature check passes exactly when that last witness's
signature is valid; two vkey witnesses of one key are both
kept and both checked. At version 12 the Alonzo witness set of Alonzo, Babbage and Conway refuses a
repeated root ("Final number of elements: 1 does not match the total count that was decoded: 2"),
and the Shelley witness set does not.

`BootstrapDuplicatesReferenceSuite` (ledger) requires the same of Scarab's decoders on every line read
at its era's version (`Witnesses.decode`, `Tx.fromEnvelope`): the same bootstrap witnesses in the same
order with the same address roots, the same vkey witnesses, and from `TxChecks.verifiedWits` the
reference's verdict and invalid keys. Versions no era of Scarab reads its witness sets at are the
reference's alone. `BootstrapDuplicateSubmissionSuite` (node) requires the mempool, through local and
remote submission, to admit a payment of the synthetic ledger carrying an invalid bootstrap witness
followed by a valid one of its root, and to refuse it with `InvalidWitnesses` the other way round.

Scope: decoding, and the reference's signature check run as a function on the decoded transaction;
no ledger state, no other rule and no Haskell node runs here. A Conway devnet of cardano-node 11.1.3
(revision `938cba990357ae7c4b7f95c8f75dd9d31174bbeb`) took a payment with an invalid then a valid
bootstrap witness of one root into its mempool and a block, and refused the valid then invalid one
with `InvalidWitnessesUTXOW` (2026-11-13); that run's artifacts are not published.

Regenerate with the [oracle recipe](../../../scripts/oracle/conway-decoding/README.md).
