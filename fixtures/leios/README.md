# Leios fixtures

Public data and throwaway test keys only (no keys of real pools). Read with `docs/leios.md`.

## `bls/` (N2: BLS12-381 MinSig voting keys, votes, certificates)

| File | Provenance | Used by |
|---|---|---|
| `bls/devnet-pools.tsv` | Three throwaway pool BLS key pairs of the Leios proto-devnet in `input-output-hk/ouroboros-leios` (`demo/proto-devnet/config/pools-keys/pool{1,2,3}/bls.{skey,vkey}`, main `614b10d2`, 2026-10-14), with the proof of possession each pool carries in that devnet's `genesis/shelley-genesis.json` (`blsKey.blsPossessionProof`, produced by `cardano-cli` on `cardano-base` BLS12-381 MinSig). The key files are CBOR byte strings; the `5820`/`5860` prefixes were stripped. All three keys are public in the upstream repository and protect nothing | `MinSigVectorSuite`: key derivation, proof verification, byte-exact regeneration of the proofs (this fixes the tag, the framing and the 48-byte size) |
| `bls/cardano-base-serde-test-vectors` | `IntersectMBO/cardano-base`, `cardano-crypto-class/bls12-381-test-vectors/test_vectors/serde_test_vectors` (master `9c964bec`, 2026-10-13, Apache-2.0): eight hex lines, G1 then G2, uncompressed/compressed, off-curve/not-in-subgroup (order in that directory's README) | strict-decode negatives |
| `bls/LeiosVote.golden.cbor` | `IntersectMBO/ouroboros-consensus` branch `leios-prototype`, `ouroboros-consensus-cardano/golden/cardano/leios/LeiosVote` (2026-10-14, Apache-2.0): `[hash 00..1f, 1000, 48-byte signature]` | `Vote.decode`/`encode` round trip |

The Rust reference `ouroboros-leios/crypto-benchmarks.rs` builds a proof of possession as a pair of
48-byte signatures (96 bytes) in an older fait-accompli construction. It is not the form in
`cardano-base` and the Dijkstra CDDL (`bls_key = [bytes .size 96, bytes .size 48]`); the devnet
genesis proofs above are 48 bytes and verify, which settles the question for N2.

Not used: `bls_sig_aug_test_vectors` (cardano-base) is the NUL/AUG ciphersuite for G2 signatures
(MinPk); the Leios scheme is the POP ciphersuite in MinSig, and cardano-base's API deliberately
does not expose augmentation. There is no seed-to-key golden vector upstream: key generation from a
seed is `blst_keygen` (checked indirectly: the devnet keys come from the same library).

## `testnet/` (N3 groundwork)

| File | Provenance | Used by |
|---|---|---|
| `testnet/handshake-replies.tsv` | Four handshake replies of the public Leios testnet relay `leios-node.play.dev.cardano.org:3001` (network magic 164, node version string `11.1.0.164-prototype-2026w38a` per its live config), read-only, 2026-10-14, owner-approved: a version query reply and the acceptance of versions 14, 15, 16 | `LeiosTestnetHandshakeSuite` |

No LeiosNotify or LeiosFetch message was captured: the relay closed the connection on the first
request on protocol 18 (docs/leios.md section 10), so there are no raw announcement, vote or
certificate fixtures yet.
