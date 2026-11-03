<!-- SPDX-License-Identifier: Apache-2.0 -->
# Decoding bounds: headers, blocks, transactions and node-to-client inputs read with the reference's bounds only (#749)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-decode-bounds` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented: the reference's decoders (cborg and `cardano-ledger-binary`) count no CBOR items, no string bytes and no nesting, and join a chunked string's chunks without counting them. What bounds what they read is the channel that delivered it and, after decoding, the rules:

| Input | Reference bound | Where |
|---|---|---|
| A header (ChainSync) | 65535 bytes a message (`smallByteLimit`); `maxBHSize` (a `Word16`) after decoding | `Ouroboros/Network/Protocol/ChainSync/Codec.hs:47-51`, `Protocol/Limits.hs:105-109` (ouroboros-network 2fb69829); `Shelley/Protocol/EnvelopeChecks.hs:86-88` (ouroboros-consensus 82ecba32) |
| A block (BlockFetch) | 2500000 bytes a message (`largeByteLimit`); `maxBBSize` after decoding | `Protocol/BlockFetch/Codec.hs:42-45`; `EnvelopeChecks.hs:89-91` |
| A transaction (LocalTxSubmission) | none but the mux ingress queue; `maxTxSize` after decoding | `runPeer` over `codecLocalTxSubmission`, `Ouroboros/Consensus/Network/NodeToClient.hs:455-465` |
| A query (LocalStateQuery) | none but the mux ingress queue | `Stateful.runPeer`, `NodeToClient.hs:466-478` |

Scarab's decoders of these inputs had budgets of their own, below what the reference accepts within those bounds: a header 4096 bytes, 256 items and 16 levels (`Header.headerLimits`, which a Byron header and a Leios header also took); a block 1,000,000 items (`Block`, `LeiosBlock`); a transaction body, witness set, auxiliary data and every sub-item read with the codec's default 100,000 items and 1 MiB (`Tx`, `CborRead.decode`, `AuxiliaryScripts`, `PoolRelays`, a stored output); a body read for its bootstrap effects 64 levels; a Byron body 128 levels and 2^21 items; a submitted transaction 1 MiB and 100,000 items, and a LocalTxSubmission message 1 MiB; a query 2,000,000 bytes and 200,000 items; Plutus `Data` 2^22 items. They now read with `Cbor.Limits.Unbounded`, no bound but the input's size: an item, and a chunk of a chunked string, takes at least a byte, so the size bounds the items, the string bytes and the nesting, and the nesting is kept on the heap ([ADR 0011](../adr/0011-stack-safety.md)). LocalTxSubmission takes the node-to-client bound of LocalStateQuery (`ProtocolWire.Bound.Local`). The codec's default limits bound the input at 1 MiB and nothing else.

Which of those budgets the reference's own bounds reach:

- **A Leios header** (decoder version 12, where a byte string may be chunked) whose issuer key follows 244 empty chunks is 1100 bytes, mainnet's `maxBHSize`, and holds more than 256 items; after 4000 it is over 4096 bytes. The reference reads both; Scarab refused both.
- **A Byron epoch boundary header** keeps the entries of its attributes map unparsed (`decCBORAttributes`, `Common/Attributes.hs:213-232`, cardano-ledger f649f975), and no rule bounds a boundary header's size: with 200 attributes it is 661 bytes and over 400 items. The reference reads it; Scarab refused it.
- **A valid Shelley-to-Conway header** cannot reach the former budget: below decoder version 12 a chunked string is refused ("expected bytes"), the header's fields have fixed shapes, every byte string but a VRF output has a fixed size, and a VRF output of any other length than the proof's 64 bytes decodes but fails the VRF check (`verifyCertified`, #712); with every head at its widest such a header stays far below 4096 bytes and 256 items. A header with a longer VRF output was refused at decoding and is now refused by the VRF check, as by the reference.
- **A block or transaction** reaches the item budgets only beyond today's mainnet `maxBBSize` and `maxTxSize` (a block of 90112 bytes cannot hold 1,000,000 items, nor a transaction of 16384 bytes 100,000), so only under parameters that governance can set; the nesting budgets of a body cannot be reached, as a body has no recursive field.
- **Node-to-client inputs** reach them today: a transaction over 1 MiB, which the reference decodes and its ledger refuses (`maxTxSize`), ended the connection; a `GetUTxOByTxIn` of 60,000 inputs (over 2,000,000 bytes and 200,000 items), which the reference answers, was refused.

## Evidence

`HeaderDecodingReferenceSuite` compares Scarab with the released decoders on the cases of [the header corpus](../../fixtures/reference/header-decoding/README.md): the Leios headers after 244 and 4000 empty chunks (accepted), the Alonzo and Conway headers with a chunked issuer key and after 244 empty chunks (refused, "expected bytes"), and Byron boundary headers with no attributes, with 200 (accepted) and of four fields (refused). With the former header budget the suite fails on the three accepted cases that exceed it. `DecodeBoundsSuite`: a Conway block whose transaction carries 1,100,000 metadata items is read, and a header nested 200,000 levels deep is refused with a typed error on a small stack, by the Conway and the Leios header readers. `LocalDecodeBoundsSuite`: a transaction of 1.1 MB and 1,100,000 items is read whole (on a small stack) and refused by the ledger, not as malformed; a LocalTxSubmission message carrying it is read; the 60,000-input query is read. `CborSuite`: `Limits.Unbounded` reads 1,200,000 items and a byte string after 300,000 empty chunks, and refuses a list that claims more items than its input holds because the input ends.

## Explicitly unchecked or unconfirmed

- The byte bounds themselves belong to the mini-protocol codecs and the multiplexer, which their own suites exercise (`ChainSyncSuite`, `BlockFetchSuite`, `IngressLimits`), not to these decoders; no oracle case is a block or transaction large enough to cross a former item budget, as such a fixture would be megabytes. The header corpus shows the released decoders counting nothing; the large cases are Scarab's own suites.
- TxSubmission2 still takes a transaction of at most 65536 bytes and replies of at most eight, Scarab's own profile, where the reference's message bound is 2500000 bytes: [#338](https://github.com/KD5UJC-XYZ/scarab/issues/338) aligns its limits.
- The forms of a Byron header's byte strings and the rest of its grammar: [#574](https://github.com/KD5UJC-XYZ/scarab/issues/574).
