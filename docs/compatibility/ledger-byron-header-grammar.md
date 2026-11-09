<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: the Byron header grammar (#634)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-byron-header-grammar` in [compatibility.json](../data/compatibility.json). The sections below record the implementation, evidence and remaining limits.

## Status as recorded

implemented: `Header.byronFromRaw` reads a Byron main header as `decCBORAHeader` reads it and a boundary header as `decCBORABoundaryHeader` does (`Block/Header.hs:351-395, 613-626`, cardano-ledger `f649f975`), at decoder version 1. Every list is definite and of its exact length (`enforceSize`): the header, its consensus data, the slot (`Slotting/EpochAndSlotCount.hs:65-68`), the difficulty (`Common/ChainDifficulty.hs:47-50`), the block signature and its proxy (`Block/Header.hs:691-700`), the delegation certificate (`Delegation/Certificate.hs:209-217`), the extra data, and a boundary header's consensus data (`Block/Boundary.hs:33-38`). A main header's magic is a `Word32` (`ProtocolMagicId`, `Crypto/ProtocolMagic.hs:58-62`); a boundary header's is dropped with `dropInt32` (`Block/Header.hs:617`), so any `Int32` is read, a negative one included. Epochs, slot counts, difficulties and the delegation epoch are `Word64`, the signature's kind a `Word8`. A main header's extra data is read by `ByronTransport.blockVersions` as `decCBORBlockVersions` reads it (`Block/Header.hs:392-395`): the protocol version (`Word16`, `Word16`, `Word8`), the software version (definite text of any length and a `Word32`), empty attributes (`dropEmptyAttributes`, `Common/Attributes.hs:241-244`, a definite map) and a definite byte string of any length.

Only a main block's magic is compared with the genesis (`updateBlock`, `Block/Validation.hs:520-525`); `ByronRules.update` no longer compares a boundary block's, which the reference has dropped by the time `updateChainBoundary` (`:271-297`) runs.

No persisted format changes, and a header's hash is over its original bytes, so a header both decoders read keeps every stored value. A store of a public chain holds only headers its Haskell nodes accepted. A store an earlier build wrote from a devnet or a test, holding a header the reference refuses, now fails to replay at that header, as the reference's would.

## Evidence

`HeaderDecodingReferenceSuite` on the 54 Byron cases of [the header corpus](../../fixtures/reference/header-decoding/README.md) added for #634, each the retained mainnet main header or a boundary header with one part rewritten, against the released `cardano-ledger-byron` 1.3.0.0 decoders, whose sources match the pin. `ByronHeaderBytesSuite` gives the typed refusal of each part on real mainnet headers: every list written indefinite, the magics at and past their bounds, the `Word64` fields as a bignum of 2^64, the extra data's arity, attributes and software version. `ByronBoundaryPredecessorSuite`: a boundary block of another magic, and of magic -1, is applied.

## Explicitly unchecked or unconfirmed

The slot a main header names is not compared here. The reference flattens the epoch and slot count with `Word64` arithmetic (`toSlotNumber`, `Slotting/EpochAndSlotCount.hs:71-81`) and checks no slot count against the epoch's length; Scarab computes it exactly and refuses a slot count at or past the epoch's length (`ByronChainError.SlotOutsideEpoch`). The check runs before the signature's, but the verdicts differ only for a header a genesis delegate signed; any other the reference refuses at its signature: [#818](https://github.com/KD5UJC-XYZ/scarab/issues/818).
