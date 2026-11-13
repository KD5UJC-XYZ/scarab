# Bootstrap chain codes: a chain code of any length below decoder version 12

Status: Current.

## The rule

A bootstrap witness is `[vkey, signature, chain code, attributes]`, read by `DecCBOR BootstrapWitness`
(`libs/cardano-ledger-core/src/Cardano/Ledger/Keys/Bootstrap.hs:102-106`, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`; the file is the same at
`048b78d2807ce9644dbe483c605625824f6be88f` and in the released cardano-ledger-core 1.21.0.0 the
harness runs). The reference reads the chain code as a byte array and checks its length only from
decoder version 12 (`instance DecCBOR ChainCode`, `Bootstrap.hs:72-78`,
`whenDecoderVersionAtLeast (natVersion @12)`). A block and a submitted transaction of an era are read
at the era's `eraProtVerLow` (`eraDecoder`, `libs/cardano-ledger-core/src/Cardano/Ledger/Core/Era.hs:259-260`;
`decodeShelleyBlock` and `FromCBOR (GenTx (ShelleyBlock proto era))`,
`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Block.hs:313-320` and
`Mempool.hs:261-265`, ouroboros-consensus `82ecba329d7d054340bf707d44fe6e9ac27cec40`): 2 for Shelley
to 9 for Conway, whatever the protocol version in force, so the chain code of a Shelley to Conway
bootstrap witness has any length. `bootstrapWitKeyHash` (`Bootstrap.hs:112-146`) hashes the chain
code as it is, after a fixed prefix that names a 64-byte key; it is the address root the witness
provides (`keyHashWitnessesTxWits`, `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:538-545`).
`verifyBootstrapWit` (`Bootstrap.hs:161-169`) checks the key's signature of the body and reads no
chain code.

## The cases

`cases.jsonl` holds a line per case, written by
`scripts/oracle/conway-decoding/bootstrap_chain_code_cases.py`, each with chain codes of 0, 31, 32, 33
and 64 bytes (`lengths`):

- in each era from Shelley to Conway (`era`, read at its `version`): a witness set holding one
  bootstrap witness (`kind` "witnesses"), a whole transaction with a vkey witness and that bootstrap
  witness ("transaction"), and a block body, the parts of a block after its header, holding that
  transaction ("blockbody");
- in Conway, a witness set holding two bootstrap witnesses that differ only in their chain codes, of
  32 and 33 and of 31 and 33 bytes;
- one bootstrap witness read alone with `DecCBOR BootstrapWitness` ("bootstrap") at each decoder
  version from 2 to 12; `era` names the era read at that version where there is one (12 is
  Dijkstra's `eraProtVerLow`).

The keys, signatures and attributes are fixed filler bytes: no signature verifies and no ledger rule
runs. A line records the reference's verdict (`accepted` and the complete `failure`) from the
unchanged released decoders, `decodeFullAnnotator` of the era's `TxWits`, `Tx` or `BlockBody` at its
version and `decodeFull` of a `BootstrapWitness` at the version given, and, for an accepted witness
set, transaction or witness alone, the `bootstrapWitKeyHash` of each witness it holds in the order
of its set (`keyHashes`). Every refusal names `rule`, a fragment of the reference failure. The counts
are in `provenance.json`: every line below version 12 is accepted, and at version 12 only the
32-byte chain code is.

`BootstrapChainCodesReferenceSuite` (ledger) requires the same verdict of Scarab's decoders, the ones
a block's transactions and a submission go through: `Witnesses.decode`, `Tx.fromEnvelope`, and
`Block.decodeShelley` with `Tx.fromBlock` for a block body under a fixed header. An accepted line keeps
each chain code as written and has the reference's address roots (`BootstrapWitness.addressRoot`). A
witness alone is read in a witness set `{2: [witness]}` of the era read at its version, and at
version 12 by the Dijkstra witness reader, which must refuse it with `ByteLength` of the chain code,
32 and its length; versions 6, 8, 10 and 11, which no era reads at, are the reference's alone.
`BootstrapChainCodeSubmissionSuite` (node) requires the mempool, through local and remote submission,
to read each Conway transaction and reach the ledger rules, and to admit a payment of the synthetic
ledger that carries an extra bootstrap witness, signing the body, with each chain code length.

Scope: decoding, and in the node suite Scarab's own ledger rules; the reference's ledger rules are
not run on these transactions, and nothing here is executed by a Haskell node. Dijkstra's own witness
set at version 12 is covered by the Leios testnet corpus (`fixtures/reference/leios-testnet-blocks`,
a different build).

Regenerate with the [oracle recipe](../../../scripts/oracle/conway-decoding/README.md).
