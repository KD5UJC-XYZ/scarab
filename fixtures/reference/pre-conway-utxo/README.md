# Pre-Conway phase-1 predicate fixtures

Thirty-two synthetic public transaction bodies, measured on 2026-10-18 against the unchanged
released Haskell ledger libraries used by node 11.1.3. `predicates.json` retains each original
body as hex, protocol/network, and three independent pre-Conway API results and a Conway collateral-predicate result, with original
Haskell diagnostics. JSON whitespace was formatted for review; the body bytes are unchanged.
The oracle source and [offline recipe](../../../scripts/oracle/pre-conway-utxo/README.md) are
committed. `provenance.json` records hashes, package versions and source comparison.

Coverage per era includes three/four collateral inputs, 64/65-byte unknown and derivation-path
payloads (including their combined size), mainnet/testnet addresses and two testnet magic values,
and simultaneous attribute/network/count failures. Two bodies contain four distinct CBOR encodings
of one decoded collateral input: Haskell counts one. The harness also records `sumAllCoin` over
a map from the decoded collateral set to the first ordinary output. Scala exercises this same
coin model with redeemers and verifies one fee credit and one stake/UTxO removal, without
claiming complete phase-2 admission. Each case also calls the same actual collateral predicate on
a basic Conway-era body populated with that decoded set. This is API parity, not a claim that
Conway accepts the original pre-Conway duplicate wire encodings. Babbage adds collateral-return checks.
`PreConwayUtxoPredicatesSuite` decodes these same bodies, compares production UTXO validation's
typed first failure in the source-defined order, and checks that the original bytes remain intact.
Its additional cases cover both validity flags; `ShelleyLedgerSuite` exercises the collateral
check through the complete era-indexed LEDGER rule with genuine test signatures.

The measured APIs are the actual `validateOutputBootAddrAttrsTooBig`, `validateWrongNetwork`
and `validateTooManyCollateralInputs`, not copies of their predicates. Their released source files
match pinned cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5` byte for byte:
`Alonzo/Rules/Utxo.hs:470-481,543-571`, `Babbage/Rules/Utxo.hs:384-412`,
`Shelley/Rules/Utxo.hs:544-558`, and `Cardano/Ledger/Address.hs:175-180,334-340`.

Scope: independent predicate execution, not complete Haskell UTXO/LEDGER admission, phase-2
evaluation, or failure accumulation. The Scala validator retains its first-failure interface;
the reference returns every predicate failure. Bootstrap address roots are deterministic public
test data; no signing keys are retained. Before Shelley, Byron's output rule has a different
attribute limit; the Shelley/Allegra/Mary bootstrap-attribute gap remains [F7/#141](https://github.com/KD5UJC-XYZ/scarab/issues/141).
