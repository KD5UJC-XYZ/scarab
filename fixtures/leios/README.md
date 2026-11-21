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

Everything here is of the network as restarted on 2026-10-18 (`systemStart` 2026-10-18,
`TestDijkstraHardForkAtEpoch` 0, so every header is of era index 7 and every block of tag 8), read
from the relay `leios-node.play.dev.cardano.org:3001` (network magic 164) as a client that submits
nothing, on 2026-11-03, while the live `config.json` named `MinNodeVersion` `11.2.0.164-rc-2026w44`
(cardano-node `b004fe0c`, `docs/leios.md` section 12), except `headers-after-2168639.tsv` and
`blocks-after-2168639.tsv`, read from a following node's database as their rows say. The fixtures of
the network before the restart were replaced on that day (#743); its chain no longer exists.

| File | Provenance | Used by |
|---|---|---|
| `testnet/handshake-replies.tsv` | Four handshake replies of the relay: a version query reply and the acceptance of versions 14, 15 and 16 for an initiator-only client. First captured 2026-10-14 from the network before the restart; captured again 2026-11-03, byte for byte the same | `LeiosTestnetHandshakeSuite` |
| `testnet/config/*.json` | The eight files of `https://book.play.dev.cardano.org/environments-pre/leios/` (the deployment of `input-output-hk/cardano-playground` `docs/environments-pre/leios/`), downloaded 2026-11-03 unchanged. The Blake2b-256 hashes `config.json` lists are those of the files (Byron over the canonical JSON) | `LeiosTestnetConfigSuite`, `LeiosHeaderChainSuite`; `peer-snapshot.json` (a version-23 big ledger peer snapshot) also `PeerSnapshotSuite`, `PeerSnapshotUseSuite` and the `peer-snapshot` fuzz target; the Byron, Shelley, Alonzo and Conway genesis files also the genesis oracles (`fixtures/reference/byron-genesis-data`, `fixtures/reference/genesis-fields`); the Dijkstra genesis also `DijkstraGenesisSuite`, `DijkstraParamsSuite` and its oracle (`fixtures/reference/dijkstra-genesis`) |
| `testnet/headers-from-origin.tsv` | The first 1,100 ChainSync headers of the chain, from Origin (blocks 0 to 1,099, slots 16 to 21,870: epoch 0 and the start of epoch 1), as the relay sent them to an initiator-only client (version 14). Columns: HFC era index, block number, slot, header hash (Blake2b-256 of the header), the envelope `[era, tag24(header)]` in hex | `HfcHeaderSuite`, `LeiosHeaderChainSuite`, `LeiosTestnetHeaderSuite`, the fuzz corpus |
| `testnet/headers-notable.tsv` | Four headers: block 0 (`first`), and three of a ChainSync from the relay's tip: block 64,849 (`announcing`, an endorser block announcement and no certificate) and blocks 64,850 and 64,854 (`certifying`, `certifying-2`, the certificate flag and an announcement). Columns: label, then as above | the same, `LeiosBlockSuite`, `LeiosNotifyCddlSuite` |
| `testnet/headers-after-2168639.tsv` | The eight headers after block 97,273 (slot 2,168,639, the tip of `state/testnet-2168639`): blocks 97,274 to 97,281, slots 2,168,662 to 2,168,776, each with an endorser block announcement, block 97,276 also with the certificate flag. Read on 2026-11-18 from the ImmutableDB of the same following node (cardano-node `b004fe0c`, the testnet release's image), whose block 97,273 is the state's tip, hash for hash: each header is the bytes the chunk's secondary index places in its block, its Blake2b-256 the index's hash, put in the envelope `[7, tag24(header)]` a ChainSync server sends. The rest of that database is not published. Columns as in `headers-from-origin.tsv` | `DijkstraHeaderRulesSuite` |
| `testnet/blocks-after-2168639.tsv` | Block 97,276 (slot 2,168,714, epoch 100, the epoch of `state/testnet-2168639`), a certifying block: a Leios certificate of 154 seats and no transactions, certifying the endorser block its parent 97,275 (`headers-after-2168639.tsv`) announced. Read on 2026-11-21 from the ImmutableDB of the same following node as `headers-after-2168639.tsv`: the 975 bytes chunk `02008`'s secondary index places at offset 177,400, the CRC-32 `be769dab` recorded there, and a header whose Blake2b-256 is the index's hash, as the block `[tag, block]` the chunk holds (the BlockFetch form). Columns as in `blocks-rc-2026w44.tsv`. Added with the owner's approval of 2026-11-21 (#73) | `LeiosCertificateFixtureSuite` (node): the certificate against the committee of `state/testnet-2168639` |
| `testnet/blocks-rc-2026w44.tsv` | Five blocks fetched one at a time over BlockFetch: blocks 0, 1 and 8,562 (slot 174,310, after the peer snapshot's point), empty; block 64,850 (`certifying`, a Leios certificate and no transactions) and block 64,853 (`announcing-with-txs`, 83,896 bytes). Columns: label, block tag, block number, slot, header hash, the block `[tag, block]` in hex | `LeiosBlockSuite`, `LeiosTestnetBlockReferenceSuite` (through the corpus of `fixtures/reference/leios-testnet-blocks`), the fuzz corpus |
| `testnet/eb-1455869.tsv.gz` | The endorser block that block 64,853 (`announcing-with-txs` of `blocks-rc-2026w44.tsv`, slot 1,455,869) announces, hash `87b509be…f201`, 11,757 bytes, and its closure of 323 transactions (602,811 bytes), as the reference stored them: the rows of `ebs`, `ebBodies` and `ebClosures` for that point in the immutable LeiosDB (`leios.imm.db`) of a following node of the testnet's release (cardano-node `b004fe0c`) recorded from 2026-11-16, read on 2026-11-21 with SQLite in read-only, immutable mode. The body's Blake2b-256 is the hash, its length the recorded `ebBytesSize`, and the closure is cut at the body's `txBounds`. Gzipped TSV, one row each: `point` (slot, hash, size), `body` (hex), then `tx` (offset, hex) in body order; the rest of that database is not published. Added for #54 | `DijkstraEndorserBlockSuite` (node): the endorser block forged of the closure is the recorded one, byte for byte, and block 64,853's header body announces it |

Not captured: any LeiosNotify or LeiosFetch message. On 2026-10-14 and again on 2026-10-16 (a
LeiosNotify pull and a LeiosFetch request, after ChainSync from a known point to the tip, versions 14
and 16) the relay closed the connection on the first message on protocol 18 or 19
(docs/leios.md sections 10 and 11.7), so there are no raw announcement, offer, vote or endorser-block
messages (the one endorser block, `eb-1455869.tsv.gz`, is from a node's store); the Leios certificates are the ones in blocks 64,850 and 97,276, and the vote fixture is
the golden `LeiosVote` above.

The test files are plain TSV so that a diff shows a changed byte. The headers and blocks are public
chain data of a test network.

## `state/` (#826: the Leios build's `state` layout)

Two ledger snapshots a node of the Leios build wrote on a local devnet, the last Conway state and the
first Dijkstra state, with their tables, metadata and genesis files, and the `state` of a node
following the public Leios testnet at slot 2,168,639 (epoch 100, Dijkstra): [their README](state/README.md).
The `bls/` keys above are also the voting keys of the generated Leios-layout states
(`LeiosGen`, storage tests).
