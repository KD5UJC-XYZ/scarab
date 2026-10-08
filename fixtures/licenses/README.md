# Fixture licenses

The Cardano golden CBOR slices and extracted expected TxIds originate in
IntersectMBO/cardano-ledger, commit 226b002d5b5e83e24355f8a28ab214f3259eabda.
The original Apache-2.0 LICENSE and copyright NOTICE are retained verbatim.
The Conway package declares Apache-2.0 and includes golden/*.cbor as package data.

Scalus supplied the independently pinned redistributable copy inspected during
selection; its Apache-2.0 LICENSE is retained as well. No Scalus implementation
source is vendored. The slices have not been re-encoded; the TSV and extraction
manifest are new indexing/packaging made for this lab. See docs/fixture-provenance.md.

Plutus evaluator fixtures are from IntersectMBO/plutus commit ac40fae4dea9dcad51b38a9c5c48148655cb13a2.
Its Apache-2.0 LICENSE.md and NOTICE.md are retained verbatim. Reference cost
JSON is copied from Scalus commit 31531c14d4e556fb38c984d702ee60dd82b6453f, under the retained Scalus Apache-2.0 license.

Conway PV9 ledger sequences under `fixtures/ledger` originate in the Haskell
cardano-ledger-conformance-tests fork at dc8494b30741824fcd2ff90d8b4755bfdc10b03d,
distributed via rrruko/cardano-blueprint at 1b230262024a992cfb9e9bc47970fd3fafca8dac
and pragma-org/amaru at 34a453005bcaaf837ee73bd996b99eab8ef92961. Their verbatim
Apache-2.0 licenses are cardano-ledger-conformance-LICENSE,
blueprint-conformance-LICENSE, and amaru-LICENSE; the original Haskell NOTICE is
cardano-ledger-conformance-NOTICE. Source excerpts and the complete original archive
retain the same provenance. New extraction code and projected indexes are project
work; see fixtures/ledger/provenance.json for the chain and exact source hashes.

The public-only strict Ed25519 corpus under `fixtures/witness` preserves sodium's
ISC license, speccheck/Wycheproof and Cardano Apache-2.0 licenses/notices, and the
complete Weavechain arithmetic MIT/CC0/BSD attribution. See the separate witness
licenses directory and docs/witness-provenance.md. No private seed corpus is
included, and no native oracle binary is distributed.
