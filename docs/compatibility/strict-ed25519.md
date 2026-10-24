<!-- SPDX-License-Identifier: Apache-2.0 -->
# Strict Ed25519 witness verification (D7, #26)

Part of the [compatibility matrix](../compatibility.md); the register entry is `strict-ed25519` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: canonical encoding, small-order and scalar bounds retained; the canonical-y optimization in #147 reduces mean verification cost by 8.7%

## Evidence

`StrictEd25519Suite`, `StrictEd25519DifferentialSuite`; [performance.md](../performance.md) section 19; [pinned-source witness corpus](../../fixtures/witness/README.md): 2,207 fixed-size cases agree between the archived strict prototype and Cardano sodium `dbb48cce5429cb6585c9034f002568964f1ce567` built without `ED25519_COMPAT`; 12 malformed-length inputs excluded from native calls. Optimized versus prior JVM verifier differential tests pass. Historical Preview acceptance at `d05e38d8` below uses the byte-identical optimized verifier from `672ef090`

## Explicitly unchecked or unconfirmed

finite, transitive source-profile evidence; no claim of shipped Haskell binary equivalence or universal verdict equivalence; valid-chain following does not establish rejection or ledger-state identity; no native/batch production path
