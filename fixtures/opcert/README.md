# Public operational-certificate signature evidence

This directory admits only the cold-key signature over the raw 48-byte operational
certificate message. `certificates.tsv` contains four archived public positives
and six native-rejected mutations per original (28 rows). Columns are identifier,
cold key, hot KES key, unsigned counter, unsigned start period, signature, exact
message and expected signature result. Little-endian controls reverse both integer
byte orders by supplying the corresponding unsigned values to the BE8 adapter.
`serialization.tsv` has 25 independently Python-derived cross-product controls for
0, 1, 2^63-1, 2^63 and 2^64-1; these are byte-serialization controls, not signed
certificates or native verification observations.

## Provenance and licenses

Target Cardano node 11.1.3 commit `938cba990357ae7c4b7f95c8f75dd9d31174bbeb`.
The target ledger pin is `f649f9751074d2ab3de033fc3912f29c9862c1f5`;
`evidence/OCert.hs` defines the raw signable representation. Source URLs and hashes
are retained in `evidence/sources.json`; Apache-2.0 ledger LICENSE/NOTICE are kept.
The four unchanged headers originate in Amaru revision
`34a453005bcaaf837ee73bd996b99eab8ef92961`, with its Apache-2.0 LICENSE retained.
The upstream archive labels them preprod; historical chain inclusion is not
independently authenticated. No private signing keys, signing fixtures, signer,
prover or key generator are admitted.

`evidence/public-key-results.json` is the unchanged 2026-10-08 research observation
record. `evidence/check_public_keys.py` is the unchanged research probe, retained
for method transparency only. It is NOT an application dependency or reproduction
command: its external research-directory inputs and native library are not bundled.
The opcert observations came from `crypto_sign_ed25519_verify_detached` on a
self-built Cardano sodium source pin `dbb48cce5429cb6585c9034f002568964f1ce567`;
library SHA-256 `76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f`.
Only detached public signature verification was called for these observations.
This is source-build evidence, not demonstrated official release-binary equivalence.
No native oracle is rerun by the offline projector or JVM tests.

The unchanged research JSON/script also contain KES prototypes, convenience pool
hashes and period calculations. These are explicitly outside admission here. The
projector reads only header identity, certificate fields/message and opcert native
outcomes. It does not validate or rely on KES, pool IDs, registration, counters
from historical state, or protocol-period values.

## Offline admission

Run `python3 scripts/project-opcert-fixtures.py` from the repository. Its embedded
manifest digest pins all evidence and projections before parsing; each read is
limited to 64 KiB, with bounded fixture CBOR depth/array sizes and exact end checks.
The parser is for these four hash-admitted fixtures only, never general header
admission. `verify-opcert-projector.py` exercises corruption, bounds, evidence
semantics and an edited-manifest bypass attempt. The CLI separately embeds the
TSV SHA-256 before parsing. Editing a manifest cannot admit alternative CLI input.

Neither finite positive agreement nor these controls establish a cryptographic
security audit, universal acceptance equivalence, current/authorized certificates,
registered pools, KES signatures or full header validity.
