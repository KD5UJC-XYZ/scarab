<!-- SPDX-License-Identifier: Apache-2.0 -->
# Byron genesis data

204 cases measured on 2026-11-03 against the unchanged released Byron genesis reader
(cardano-ledger-byron 1.3.0.0 at cardano-ledger `f649f975`, cardano-crypto-wrapper 1.7.0.0,
cardano-prelude 0.2.2.0, canonical-json 0.6.0.1), for
[#734](https://github.com/KD5UJC-XYZ/scarab/issues/734): every field of the Byron genesis as
`FromJSON GenesisData` reads it. The oracle source and
[recipe](../../../scripts/oracle/byron-genesis-data/README.md) are committed; `provenance.json`
records the pins, the hash of each reference source the reader runs through, of the harness and of
`cases.jsonl`.

`cases.jsonl` holds one case per line: its `name`, the genesis (a `file` of the repository, or the
devnet genesis `base` with literal `edits`, each `[old, new]` with `old` occurring once), the genesis
`hash` the reference takes where the bytes parse, and the reference's verdict. An acceptance keeps
the fields `fromJSON` produced (`result` for a file; `changed`, the fields whose value differs from
the unedited base's, for an edit): the stakeholder hashes in order, each delegation with its key,
`startTime` in seconds, each parameter, `k`, `protocolMagic`, and the number of balances of each
kind. A refusal keeps the reader's error (`failure`) and, typed, the field it refused and why
(`refusal`). The reader's error names a missing field, which `cases.py` checks against the field
the case means; any other error names only the type it expected, so its field is the one the case
edits, and the order cases pin which of two edited fields is reported.

| group | cases | reference verdict |
|---|---|---|
| files | mainnet, preprod, preview, the devnet, the Leios testnet, a Byron test genesis, the trimmed mainnet genesis | accepted |
| top level | an unknown field; a field twice (the first holds) | accepted |
| | not an object; each field left out; `protocolConsts` looked up before every field, and when it is not an object refused only when `k` is read, after `blockVersionData`; of two wrong fields the first in `GenesisData`'s order is named | refused |
| `bootStakeholders` | empty; a value of 65536, −1, 0 or fifteen digits (a `Word16` modulo 2^16); a key in upper or mixed case; one key twice in two cases; keys out of order (the set is in byte order) | accepted |
| | an array; a value a string or `null`; a key twice, the second value a string; a key not hex with a value that is a string (the key is named); a key of odd length, of 27 or 29 bytes, not hex, with `0x`, empty | refused |
| `heavyDelegation` | empty; a key in upper case; two issuers with one delegate; a key twice, the second certificate valid (the first holds); `omega` 5, −1 (an `EpochNumber` modulo 2^64) or fifteen digits; an unknown field in a certificate; `cert` in upper case or all zero (the signature is not checked) | accepted |
| | an array; a certificate not an object; a key twice, the second certificate `null`; a key of 27 bytes; a key that is not its issuer's hash; one issuer under two keys; a delegate that is an issuer; an issuer delegating to itself; `omega` a string, `null` or missing; a key without padding, in the URL alphabet, with a space, not base64, with nonzero padding bits, of 0, 63 or 65 bytes, a number; `cert` of 63 or 65 bytes, of odd length, not hex, a number; of a certificate's wrong fields `omega` is named first; of two wrong certificates the first in the text is named, and of a key that is not its issuer's hash and a multi-layer delegation the first key is named, whatever their order in the text | refused |
| `startTime` | 0, −1, fifteen digits either sign | accepted |
| | a string, `null`, an object | refused |
| `blockVersionData` | `scriptVersion` 65536, −1 or 65535; the sizes `0`, `-0`, hex, octal, in parentheses, with spaces, with leading zeros, 2^64, thirty digits (a `Natural`); the thresholds 0, 10^15, 2^64 and 2^64 + 10^15 (a `Word64` modulo 2^64), hex; `updateImplicit` and `unlockStakeEpoch` 0, 2^64 − 1, 2^64, −1, hex; `thdDecrement` 2^64; `summand` 0, under 10^9, −1, 2^64 − 1, 2^64, hex; `multiplier` 0, −5, `(-5)`, hex, thirty digits, 2^64 (an `Integer`); an unknown field | accepted |
| | an array; `scriptVersion` a string or missing; each size a number or missing, `-1`, `(-1)`, `1e3`, `5.0`, `+5`, empty; each threshold a number, above 10^15, −1 (2^64 − 1), 2^64 + 10^15 + 1, `1e3`; `softforkRule` an array or without `minThd`; `txFeePolicy` an array or without either field; `summand` a number or `1e9`; `multiplier` a number, `1.5`, `+5`; of two wrong fields `scriptVersion` is named first | refused |
| `protocolConsts` | `k` 0, −1 (a `BlockCount` modulo 2^64), fifteen digits; `protocolMagic` 0, 2^32 − 1, 2^32 + 1 and −1 (a `Word32` modulo 2^32), fifteen digits | accepted |
| | `k` a string, `null` or missing; `protocolMagic` a string or missing; of the two wrong `k` is named first | refused |

Scope: the parse of the whole file, its hash and every field of `GenesisData`; the balances by their
count, which the [balances corpus](../byron-genesis-balances/README.md) covers entry by entry.
`ByronGenesisDataReferenceSuite` runs every case through Scarab's reader (`ByronGenesisFile.parse`).
A number the reference takes modulo 2^bits is refused by Scarab ([overflow improvements](../../../docs/compatibility/overflow-improvements.md#byron-genesis-words)):
the suite checks that it is outside the field's range and that what the reference read, or the
value its refusal names, is that number modulo 2^bits.
