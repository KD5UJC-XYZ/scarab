<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: url length, decoder versions 2 to 9 (#550)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-url-length` in [compatibility.json](../data/compatibility.json). The sections below record the implementation, evidence and remaining limits.

## Status as recorded

implemented: a `Url` is read with `textDecCBOR`, which bounds it by `textSizeN` at 64 bytes of UTF-8 below decoder version 9 and 128 from it (`instance DecCBOR Url`, `BaseTypes.hs:644-697`, cardano-ledger `f649f975`). `CborRead.url` applies that bound with the era's decoder version and refuses longer text with the typed `TextTooLong` of its place: the pool metadata url of a registration (`PoolParams.decodeMetadata`, every era) and a governance anchor url (`Anchor.decode`, reached by a DRep registration or update, a committee resignation, a proposal, a new constitution and a vote). Nothing is re-encoded: the original bytes are hashed and retained.

## Evidence

`UrlLengthReferenceSuite`: 33 original encodings (`fixtures/reference/url-length/cases.jsonl`, oracle `scripts/oracle/conway-decoding/url_length_cases.py`, harness unchanged): the pool metadata url of a registration in each era from Shelley to Conway at the era's bound (accepted) and one byte over (refused); below version 9 also a 128-byte url (refused) and urls of two-byte characters at 64 bytes (accepted) and 66 bytes (refused); the anchor url of a Conway DRep registration, proposal and vote at 128 and 129 bytes. 14 accepted, 19 refused, each refusal recorded with the `Text exceeds <n> bytes` failure of the reference and the Scarab error case it must be; the same suite checks `CborRead.url` at unit level.

## Explicitly unchecked or unconfirmed

A relay DNS name (`DnsName`, the same bound) at body decode: a registration's relays are kept as their original bytes and are decoded with the body only under #436. `PoolRelays.decode` bounds a name at 128 bytes in every version, a decision recorded in its documentation; the reference's 64 bytes below version 9 is put to the owner on #550. The anchors of a stored ledger state are read by the state codecs, not by this reader.
