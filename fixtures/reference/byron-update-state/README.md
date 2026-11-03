<!-- SPDX-License-Identifier: Apache-2.0 -->
# Byron update-interface state

12 cases measured on 2026-11-03 against the unchanged released Byron ledger
(cardano-ledger-byron 1.3.0.0 at cardano-ledger `f649f975`, cardano-crypto-wrapper 1.7.0.0,
cardano-ledger-binary 1.9.1.0), for [#726](https://github.com/KD5UJC-XYZ/scarab/issues/726): the
encoding of `UPI.State`, the update-interface state, with which the reference answers Byron's
`GetUpdateInterfaceState` (`encodeByronResult` is its `toByronCBOR`). The oracle source and
[recipe](../../../scripts/oracle/byron-update-state/README.md) are committed; `provenance.json`
records the pins, the hash of each reference source the encoding runs through, of the harness and of
`cases.jsonl`.

`cases.jsonl` holds one case per line: its `name`, the `state` field by field (`currentEpoch`, which
only `initialState` writes, is 0 in every case; a parameter set gives the fee summand in lovelace and
the multiplier in nano-lovelace) and the reference's encoding (`cbor`), which the harness decoded
again to the state it was built from.

| case | what it holds |
|---|---|
| `initial-mainnet`, `initial-preprod`, `initial-preview`, `initial-eras-devnet` | `initialState` of each Byron genesis's parameters: version 0.0.0, every container empty |
| `registered-and-confirmed` | a candidate, two application versions with installer metadata, protocol and software proposals (the latter with metadata), confirmations, votes (one an empty set), endorsements and registration slots |
| `candidates-kept-in-list-order` | three candidates, not in slot or version order |
| `text-keys-in-utf8-order` | application names and system tags `""`, `a`, `aa`, `ab`, `aé`, `b`, `Z`, `zz`, `é`, `�`, `\U0001f600` (UTF-8 order puts U+FFFD before U+1F600, UTF-16 order after) |
| `hash-keys-unsigned-order` | proposal ids and key hashes whose first byte is 0x00, 0x01, 0x7f, 0x80, 0xff |
| `endorsements-by-version-then-key` | endorsements of six versions by two keys each |
| `word-bounds` | each `Word16`, `Word32`, `Word64` and `Word8` at its maximum; portions 0 and 10^15 |
| `naturals-and-nano` | `Natural` sizes 0, 23, 2^64 − 1, 2^64 and 10^30; summands 0 to the lovelace maximum; multipliers 0, −1, −2^64 − 1, 2^64 − 1 and 2^70 |
| `twenty-four-entries` | 24 entries in each map and set, 24 metadata entries |

Scope: the encoding only. Which state the ledger rules reach is the rules' evidence (`ByronUpdateSuite`,
`ByronRegistrationFixtureSuite`). `ByronUpdateInterfaceSuite` builds every case's state in Scarab's
types (`UpdateState`) and requires `ByronUpdateInterface.encode` to write the reference's bytes; it
also requires the node's answer to the query at the genesis of `fixtures/eras/genesis` to be
`initial-eras-devnet`'s encoding.
