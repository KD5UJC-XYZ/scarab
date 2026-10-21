# Wycheproof ECDSA secp256k1 vectors

`ecdsa_secp256k1_sha256_test.json`: the ECDSA secp256k1/SHA-256 verify corpus from Google's
Wycheproof project (476 tests, 109 groups), used by `WycheproofSecp256k1Suite` as an
independent source corpus projected to the reference contract of `Secp256k1.verifyEcdsa`.

- Source: https://github.com/google/wycheproof, `testvectors_v1/ecdsa_secp256k1_sha256_test.json`
- Commit: `7a325932231bd7a724c791c8b0d65ea4d6d2f435` (2026-10-12)
- License: Apache-2.0 (Wycheproof repository license)
- Schema: `ecdsa_verify_schema_v1.json` (same repository)

Semantics note: Wycheproof signs `SHA-256(msg)` (the messages are pre-images) and encodes
signatures as DER. The Plutus builtin takes the 32-byte message directly and a compact 64-byte
`r || s`, so the suite converts: the message to its SHA-256 digest, the signature to compact
form, and the uncompressed `04 || X || Y` group key to its compressed encoding. Malformed-DER
cases pin the harness's strictness rather than the builtin, which never receives DER signatures
on chain.

## Exact comparison scope and integrity

All 476 unique original tcIds are classified per case. The test's strict TLV parser must agree with
an independent Bouncy Castle ASN.1 decoder plus byte-exact DER re-encoding and non-negative INTEGER
checks. Of these inputs, 234 have invalid DER or no 256-bit compact representation; their original
invalid status and lack of compact input are asserted explicitly. They are **harness boundary
checks, not builtin executions**. The remaining 242 invoke the production builtin: 96 true,
102 false and 44 compact-input failures. Of the original valid signatures, 72 high-s values become
false under the documented Plutus/libsecp256k1 non-normalizing policy; this is an ABI/verification
policy difference, not a relaxed golden comparison.

`SHA256SUMS` records the unchanged upstream JSON hash. Verify from the repository root:

```sh
(cd plutus/src/test/resources/wycheproof && sha256sum -c SHA256SUMS)
```

To reproduce acquisition, retrieve `testvectors_v1/ecdsa_secp256k1_sha256_test.json` from the exact
commit above, compare its SHA-256 and original tcId/result set, and run the suite through the
Docker-only build instructions. No signatures or expected results are generated locally. Changes
to Wycheproof pins require separate review of the source and projection; keep this original corpus
rather than replacing invalid rows with compact approximations. This comparison is not a claim
that the compact-only Plutus ABI can parse DER or that the 234 out-of-domain signatures ran there.
