<!-- SPDX-License-Identifier: Apache-2.0 -->
# Byron genesis balances

175 cases measured on 2026-11-03 against the unchanged released Byron genesis reader
(cardano-ledger-byron 1.3.0.0 at cardano-ledger `f649f975`, cardano-crypto-wrapper 1.7.0.0,
cardano-prelude 0.2.2.0, canonical-json 0.6.0.1), for
[#728](https://github.com/KD5UJC-XYZ/scarab/issues/728) and
[#729](https://github.com/KD5UJC-XYZ/scarab/issues/729) and
[#730](https://github.com/KD5UJC-XYZ/scarab/issues/730). The oracle source and
[recipe](../../../scripts/oracle/byron-genesis-balances/README.md) are committed; `provenance.json`
records the pins, the hash of each reference source the reader runs through, of the harness and of
`cases.jsonl`.

`cases.jsonl` holds one case per line: its `name`, the bytes of a genesis object with
`nonAvvmBalances` and `avvmDistr` (as `text`, or as `hex` where they are not UTF-8), and the
reference's verdict. Where the bytes parse, `hash` is the genesis hash the reference takes of them.
An acceptance keeps the genesis UTxO `fromBalances` makes of each field (`result.nonAvvm`,
`result.avvm`: each output's id, index, address and amount); a refusal keeps the reader's error
(`failure`) and the field it names (`refusal`).

| group | cases | reference verdict |
|---|---|---|
| non-AVVM addresses | a minimal address, with a network magic, of the redeem type, with a derivation path and with an unknown attribute; the checksum, tag 24, the payload length, the root length, the attribute map head and the outer list head written wider than they need | accepted; the UTxO holds the address encoded again, minimal |
| | the type written wider; a wrong checksum or one over 32 bits; tag 25; an envelope of three items or indefinite; a byte after the address or after the payload; a payload not a byte string or of two items; a 27-byte root; type 1 or 3; descending attribute keys; a network magic written wider or over 32 bits; no bytes; a key that is not Base58, starts with `1` (a zero byte) or is empty | refused |
| one address twice | two encodings of one address, in each order of the text | the first in the text holds |
| AVVM keys | padded base64url, the standard alphabet, two spellings of one key in each order | accepted, the first in the text holds |
| | unpadded, nonzero padding bits, padding in the middle, two padding characters, 31 or 33 bytes, not base64, empty | refused |
| amounts | 32 texts in each field: zero, `45e15` and one over, `2^64-1`, `2^64` and over (taken modulo 2^64), `-1`, `-0`, `- 5`, `(-5)` (negative, taken modulo 2^64), surrounding spaces and parentheses, `0x`, `0X`, `0o`, `0O`, leading zeros, a 31-digit number; `+5`, `-(5)`, `0b101`, `5.0`, `5e3`, `5E0`, empty, `five`, `5_000` and a no-break or em space before the digit; and a non-AVVM amount written as a JSON number | as listed |

| canonical JSON (#730), 61 cases | whitespace of each kind between tokens, the byte `0xa0`, text or a second value after the value; strings with `\"` and `\\`, raw control bytes, raw newlines and tabs, the byte `0xff`, UTF-8; numbers of up to 15 digits, negative; `true`, `false`, `null`; arrays and objects, empty and nested, 20,000 levels deep; keys above ASCII (the hash sorts them by byte); a key twice | accepted, each with its hash |
| | a no-break space in UTF-8 or the byte `0x85` between tokens; nothing; only spaces; the escapes `\n`, `\t`, `\/`, `\u`; an unterminated string; 16 digits, `-0`, `01`, `1.5`, `1e3`, `+1`; `nul`, `True`; a trailing or leading comma; a key not a string; elements without commas | refused at parsing |
| fields | `nonAvvmBalances` twice, the first valid (accepted) or not an object (refused); a balance twice, both valid (the first holds) or the second's amount not a number (refused); either field left out; `nonAvvmBalances` an array; the whole value an array | as listed |

Scope: the parse of the whole file, its hash, and the two balance fields with the genesis UTxO made
of them. The other fields, as `FromJSON GenesisData` reads them, are the
[data corpus](../byron-genesis-data/README.md) (#734). `ByronGenesisBalancesReferenceSuite` runs
every case through Scarab's reader (`ByronGenesisFile.read` and `genesisBalances`) and
`ByronLedger.genesisUtxo`.
