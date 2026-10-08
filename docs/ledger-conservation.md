# Conway PV9 value conservation, bounded research slice

Version 0.4.0 implements one pure predicate, not a transaction validator or ledger transition. `PredicateSatisfied` means only that the admitted value-conservation equation holds. Witness signatures, addresses, script execution, minimum fees, output bounds, native scripts, validity intervals and every other ledger rule remain unchecked.

## Arithmetic and boundary

The standard-library `ledger` module depends only on `core`. Values use exact `BigInt`, immutable byte-based policy/name and input identities, and normalized maps with zero quantities removed. It computes totals itself from resolved spending inputs and decoded transaction outputs, fee and signed mint:

- Consumed = sum of resolved input values + positive mint
- Produced = sum of output values + fee + absolute negative mint
- Failure delta = consumed minus produced, per asset and lovelace

No upstream calculated totals are inputs to the predicate. A missing input is a typed `UnresolvedInputs`, never zero. Negative resolved input/output values, invalid identity lengths/index, duplicate keys/inputs, malformed wire shapes and out-of-range mint are rejected. CBOR wire integers are bounded by the core uint64 representation; mint is signed int64. Intermediate totals and model arithmetic are arbitrary precision. Zero normalization follows upstream canonical Mary value arithmetic (`canonicalMapUnion` and `pruneZeroMultiAsset` remove zero assets); it does not claim that every zero-containing encoding is consensus-admissible.

Only the complete-transaction decoder can construct the `TransferMintBody` evidence. It requires a four-field envelope with `isValid=true`, a witness-map shape, body fields 0/1/2 and optionally 9, and address/value-only outputs. Conway and protocol major 9 are the only admitted context. Every extra body field is typed unsupported, including certificates and their deposits/refunds, withdrawals, proposals, donations, collateral, reference inputs and governance fields. Their absent monetary contributions are established from the whole decoded body, not caller-provided absence flags. Unsupported output extensions are also rejected. Witnesses and auxiliary data are not validated; they are not inputs to this predicate.

There is no IO, clock, database, VM, native crypto, network or hidden protocol defaults in the predicate. Cats Effect owns only file loading and reporting in `ledger-demo`. The value-only resolved UTxO decoder is not a general ledger-state importer and does not use Scalus's lossy test MemPack helper.

## Independent evidence and version mismatch

Four cases come from two byte-preserved Haskell Conway integration-test sequences vendored by Amaru at `34a453005bcaaf837ee73bd996b99eab8ef92961`. The source Haskell generator is `dc8494b30741824fcd2ff90d8b4755bfdc10b03d`; Blueprint distribution commit is `1b230262024a992cfb9e9bc47970fd3fafca8dac`. The parameter record concretely says protocol version `[9,0]`. Historical Shelley/Mary directory labels name the rule's origin, not the fixture era.

Each sequence supplies an accepted setup and a rejected transaction. Haskell test source asserts `ValueNotConservedUTxO`: one output adds 3 lovelace; the other transaction burns two tokens where only one exists. Archived booleans are independent expectations. Calculated consumed/produced/delta values are our diagnostics, not Haskell golden error payloads. The extraction cross-checks only the final UTxO value projection, not the full final state. It does not execute tick or complete ledger effects.

Target node 11.1.3 uses ledger source `f649f9751074d2ab3de033fc3912f29c9862c1f5`; Conway delegates to Babbage's use of Shelley value-not-conserved validation, with Mary mint/burn arithmetic and Conway donations. Source review supports this absent-context equation. It does not make these older PV9 fixtures a fresh target-release differential run. No Haskell ledger oracle ran here. The separate optional official cardano-cli execution only decodes/hashes the original codec fixtures and cannot strengthen ledger predicate evidence.

## Reproduction and remaining gates

Run `python3 scripts/project-ledger-fixtures.py` to reproduce the pinned lossless projection, then `./scripts/sbtw check 'app/run ledger-demo'`. Raw fixtures, parameter bytes, source/member pins, checksums, extraction evidence and upstream license notices are preserved under `fixtures/ledger` and `fixtures/licenses`.

Project-generated adversarial and metamorphic tests are labeled separately from upstream cases. Full target-version differential generation, broader protocol coverage, contextual deposits/refunds/withdrawals/governance/collateral, all other predicates, full state transitions and node interoperability remain future work. No new external peers, keys, node process or publication are involved.

### Reviewed source anchors

- [Target Conway UTXO rule](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxo.hs)
- [Target Babbage rule](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxo.hs)
- [Target Mary consumed/produced calculations](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/mary/impl/src/Cardano/Ledger/Mary/UTxO.hs)
- [Target Conway donations](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/src/Cardano/Ledger/Conway/UTxO.hs)
- [Haskell ADA rejection test](https://github.com/SundaeSwap-finance/cardano-ledger-conformance-tests/blob/dc8494b30741824fcd2ff90d8b4755bfdc10b03d/eras/shelley/impl/testlib/Test/Cardano/Ledger/Shelley/Imp/UtxoSpec.hs)
- [Haskell excess-burn rejection test](https://github.com/SundaeSwap-finance/cardano-ledger-conformance-tests/blob/dc8494b30741824fcd2ff90d8b4755bfdc10b03d/eras/mary/impl/testlib/Test/Cardano/Ledger/Mary/Imp/UtxoSpec.hs)
- [Target multi-asset equality/canonical union](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/mary/impl/src/Cardano/Ledger/Mary/Value.hs#L158-L195) and [zero pruning](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/mary/impl/src/Cardano/Ledger/Mary/Value.hs#L863-L879)
