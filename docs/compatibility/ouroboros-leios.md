<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ouroboros Leios (CIP-164)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ouroboros-leios` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

not implemented in the node; read-only decoders and header rules for the public testnet's chain (`HfcHeader`, `LeiosHeader`, `LeiosBlock`, `LeiosHeaderChain`, tools `LeiosCaptureMain` and `LeiosCheckMain`), design and results in [leios.md](../leios.md)

## Evidence

N3 against the public Leios testnet `musashi` (2026-10-16, read-only, owner-approved): the published configuration pinned with hashes (leios.md 11.1); all 154,698 headers of the chain (blocks 0 to 154,697, epochs 0 to 159) decoded and validated from the genesis: chain links, operational certificates, KES signatures, VRF proofs under nonces evolved through 160 epochs (a wrong nonce anywhere would fail every later header), leader threshold for the 2,112 headers of the genesis epochs; six blocks decoded and matched to their headers (body hash of a Dijkstra block is the digest of the whole body); the generated `dijkstra.Header` codec reads the headers byte for byte; N2 (`crypto` package `bls`, pure): BLS12-381 MinSig keys, proofs of possession (the three proto-devnet pool proofs regenerate byte for byte), votes, aggregation, certificate verification, strict decoding (cardano-base serialisation negatives, golden `LeiosVote`), 900-seat certificate verify about 2 ms; codecs of LeiosNotify/LeiosFetch (Blueprint CDDL and the prototype source, votes bounded at sixteen; LeiosFetch follows Blueprint `0460c5a6` since #174, the older `fe4cceb9` form of `MsgLeiosBlockTxs` still read; bitmaps checked as the reference decoder does); the relay closes the connection on the first message on protocol 18 or 19 in every attempt (leios.md 10 and 11.7)

## Explicitly unchecked or unconfirmed

the node itself cannot follow the chain (it stops at block 0: 12-element header in a Conway block); no stake-dependent leader check after epoch 1 (152,586 headers unchecked); no Leios certificate verified (no committee); no real vote, announcement message, endorser block or LeiosNotify/LeiosFetch message seen; protocols 18 and 19 never served; no Dijkstra era, ledger, transaction or Plutus V4 rule; EB storage, mempool sizing, forging

## Open items

X1 to X13 of leios.md 11.6, N4
