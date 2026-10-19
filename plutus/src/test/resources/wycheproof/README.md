# Wycheproof ECDSA secp256k1 vectors

`ecdsa_secp256k1_sha256_test.json`: the ECDSA secp256k1/SHA-256 verify corpus from Google's
Wycheproof project (476 tests, 109 groups), used by `WycheproofSecp256k1Suite` as an
independent oracle for the reference semantics of `Secp256k1.verifyEcdsa`.

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
