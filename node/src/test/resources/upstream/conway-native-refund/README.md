# Native Conway proposal-refund integration trace

Pinned source: `cardano-ledger` `f649f9751074d2ab3de033fc3912f29c9862c1f5`.
The unchanged registered integration test
`Conformance/Conway/Imp/EPOCH/Proposals/Expired proposal deposit refunded`, seed 498301,
passes with its original LEDGER and NEWEPOCH conformance hooks. An export-only observer retains
every judgment: three transactions followed by three consecutive epoch boundaries. All six native
and Agda outcomes are accepted and their formal projected outputs compare equal upstream.

`UpstreamNativeLedgerConformanceSuite` runs the same actual Scala LEDGER and NEWEPOCH rules used
by the node. It decodes all five LEDGER environment fields, the original complete transaction,
full native states and original execution contexts. The globals are those of the pinned
`Shelley.ImpTest.initGenesis`; this test changes protocol parameters, not those globals. The manifest
pins the source blobs, observer patches, dependency plan and all native bytes. No signing keys are
included. Upstream notices are retained under `fixtures/witness/licenses/`.

The integration hook writes its UTxO inline as MemPack entries. The test composes existing state
codecs with the production little-endian MemPack key and Conway output decoders. No entry is
omitted. For LEDGER comparison, every input and output field is checked through `TxOutMemPack.parts`,
alongside count, coin, stake and pointer-stake totals; then the rest of the typed state is compared.
Native MemPack does not retain Scala's change-history bookkeeping or `TxOut.raw`, so those local
representations are excluded. Signed transaction bodies and witnesses enter the rules as their
original bytes. NEWEPOCH compares its entire typed state directly, including the complete reward
update held before each boundary and its removal afterward.

This is one bounded integration trace, not complete coverage of either family. The upstream formal
translation completes reward updates and filters zero pool entries; the Scala comparison uses the
native states instead. The disabled or miswired constrained-generator properties are recorded
separately under `conway-epochs`. Remaining K2 coverage is tracked by
[#49](https://github.com/KD5UJC-XYZ/scarab/issues/49).

`derived-manifest.json` records a separate supplemental invocation of this input sequence. The
payment-only second transaction supplies genuine, correctly typed UTXO, UTXOW and singleton
LEDGERS judgments via the pinned native rule runner and formal adapters. Its execution contexts
come from the original native LEDGER context; its certificates and proposals are empty, so there
is no omitted CERTS/GOV pretransition. All five observed records are retained, including the two
byte-identical original LEDGER hook records; those duplicates are not new coverage.

UTXO and UTXOW accept in both implementations and compare equal upstream. LEDGERS accepts natively
and its full native output is retained, but formal evaluation terminates the process with SIGSEGV
(exit 139). Observation happens before that evaluation, and `agda-not-yet-observed` remains explicit.
Scarab matches all three native outputs. This is a singleton payment batch, not multi-transaction
LEDGERS coverage. The pinned formal UTXO adapter's fixed treasury and valid-script assumptions also
remain limits; this payment uses no phase-2 scripts. No ledger rule or original generator is changed.

## Later formal LEDGERS boundary repair (#270)

The separate [repair corpus](../formal-ledgers-repair/README.md) retains a narrowly patched
formal executable that converts foreign inputs using the existing conversion instances.
It accepts this unchanged singleton and matches the native translated output. This does
not alter the original SIGSEGV observation, manifests or CBOR above; the crashing process
was the formal conformance adapter, not a running cardano-node.
