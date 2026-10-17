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

| `testnet/config/*.json` | The eight files of `https://book.play.dev.cardano.org/environments-pre/leios/` (the deployment of `input-output-hk/cardano-playground` `docs/environments-pre/leios/`), downloaded 2026-10-16 unchanged. SHA-256 and Blake2b-256 of each are in `docs/leios.md` section 11.1; the Blake2b-256 hashes equal those `config.json` lists (Byron over the canonical JSON) | `LeiosTestnetConfigSuite`, `LeiosHeaderChainSuite`, `LeiosBlockSuite`; `peer-snapshot.json` (a version-23 big ledger peer snapshot) also `PeerSnapshotSuite` and the `peer-snapshot` fuzz target |
| `testnet/headers-from-origin.tsv` | The first 1,100 ChainSync headers of the public Leios testnet chain, from Origin (blocks 0 to 1,099, slots 12 to 22,402: epoch 0 and the start of epoch 1), as the relay `leios-node.play.dev.cardano.org:3001` sent them to an initiator-only client (version 14, magic 164, `MinNodeVersion` `11.1.0.164-prototype-2026w38a`), read-only, 2026-10-16, owner-approved. Columns: HFC era index, block number, slot, header hash (Blake2b-256 of the header), the envelope `[era, tag24(header)]` in hex. The capture is of the whole chain (154,698 headers to block 154,697, tip 154,701), kept outside the repository (84 MB) | `HfcHeaderSuite`, `LeiosBlockSuite`, `LeiosHeaderChainSuite`, `LeiosTestnetHeaderSuite` |
| `testnet/headers-notable.tsv` | Six headers of the same capture: the last of era index 6 (block 4,254) and the first of index 7 (block 4,255, slot 86,463), the last before and the first announcing header (blocks 17,396 and 17,397), and the first two with `block_body_contains_leios_cert` (blocks 17,403 and 17,404). Columns: label, then as above | the same |
| `testnet/blocks.tsv` | Six blocks fetched one at a time over BlockFetch from the same relay, 2026-10-16: block 0 (empty) and 28 (transactions) of block tag 7, and of tag 8: block 4,255 (empty), 17,403 (a Leios certificate, no transactions), 22,446 (transactions), 34,085 (announces an endorser block, 24 kB). Columns: label, block tag, block number, slot, header hash, the block `[tag, block]` in hex | `LeiosBlockSuite` |

Not captured: any LeiosNotify or LeiosFetch message. On 2026-10-14 and again on 2026-10-16 (a
LeiosNotify pull and a LeiosFetch request, after ChainSync from a known point to the tip, versions 14
and 16) the relay closed the connection on the first message on protocol 18 or 19
(docs/leios.md sections 10 and 11.7), so there are no raw announcement, offer, vote or endorser-block
fixtures; the Leios certificate fixture is the one in block 17,403, and the vote fixture is the
golden `LeiosVote` above.

The test files are plain TSV so that a diff shows a changed byte. The headers and blocks are public
chain data of a test network.
