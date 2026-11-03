# Block headers and the bytes their KES signature covers

Status: Current. Evidence for [#698](https://github.com/KD5UJC-XYZ/scarab/issues/698).

Babbage and Conway headers are Praos headers: `HeaderBody` and `HeaderRaw` are `RecD` records
(`libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:203-229`, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`), so a non-empty indefinite list is refused with
"Excess terms in array" and an empty one, `9f ff`, with the fields after it is the record
([the detached records corpus](../recd-detached/README.md)). The body's operational certificate
and protocol version are `CBORGroup` lists read by `decodeRecordNamed`
(`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Group.hs:27-35`), definite or indefinite;
a VRF certificate is read by `enforceSize`, definite only (`Decoding/DecCBOR.hs:568-573`), and its
output is a byte string of any length (`OutputVRF`, `:564-565`), which the VRF check refuses when it
is not the proof's (`verifyCertified`, cardano-crypto-class 2.5.1.0 `VRF/Class.hs:359-369`). Shelley
to Alonzo headers are TPraos headers, whose `BHBody` and `BHeaderRaw` read their fields inside
`decodeRecordNamed` (`libs/cardano-protocol/src/Cardano/Protocol/TPraos/BlockHeader.hs:193-241`):
an indefinite list with its fields before the break is accepted, a detached one is not. The Leios
header (`libs/cardano-protocol/src/Cardano/Protocol/Leios/BlockHeader.hs`) is the Praos one with a
certificate flag and an endorser block announcement, also a `RecD` record (`:92-104`).

The KES signature is checked over `getSignableRepresentation`, the decoded header body encoded
again at its major protocol version (`Praos/BlockHeader.hs:113-117`, `TPraos/BlockHeader.hs:170-171`,
`Leios/BlockHeader.hs:139-142`), while the header hash is over the bytes read. Every accepted case
records those signed bytes (`signable`): in each of them they are the body's canonical encoding,
definite lists and the shortest heads, and in the cases with an indefinite or detached body or a
non-minimal head they differ from the body's original bytes.

`cases.jsonl` holds 147 lines, one case each, written by
`scripts/oracle/header-decoding/header_cases.py`, for Shelley and Alonzo (TPraos), Babbage and
Conway (Praos) and Leios:
- a header (`kind` "header"): definite, with a genesis predecessor, indefinite, detached, its body
  indefinite or detached, both, with non-minimal heads everywhere; a VRF certificate written as an
  indefinite list; a major protocol version of 13 and 14, a minor and a body size over a `Word32`;
  a block number, slot, certificate counter and KES period at and above 2^63; a VRF output of no
  bytes and of 65 bytes;
  a detached header without its signature and a detached body one field short; for Praos and
  Leios the operational certificate and protocol version indefinite and detached; for Praos every
  list indefinite but the VRF certificate's; for Leios an announcement definite, indefinite, detached
  and with a size over a `Word32`, and an issuer key written as a chunked byte string, which
  Dijkstra's decoder version 12 reads;
- a header body alone (`kind` "headerbody"), definite, indefinite and detached;
- a ledger block (`kind` "block", `Block.hs:104-115`, without BlockFetch's era tag) holding a
  definite, indefinite or detached header or one with a VRF output of no bytes, and for Praos a
  detached body and both detached in an indefinite block;
- a VRF check (`kind` "vrfcheck", Alonzo and Conway): a Praos VRF key made of a 32-byte seed and its
  certificate of a message (the case's bytes, seed then message), with the output as evaluated,
  empty, one byte longer and one byte shorter; for each, whether the era's decoder reads the
  certificate and whether `verifyCertified` accepts it (`vrf`). Every one decodes, and only the
  evaluated output verifies (#712).

The verdict (`accepted` and the complete `failure`) is what the unchanged released decoders did with
each, at the era's decoder version (2, 5, 7, 9; 12 for Leios): `decodeFullAnnotator` for a header
and a block, `decodeFull` for a body. An accepted header or block also records the header hash
(`hash`), and a block its block body hash (`bodyHash`). In all, 87 accepted and 60 refused.
`HeaderDecodingReferenceSuite` requires the same verdict from `Header.fromRaw`, `LeiosHeader.decode`
or `Block.decodeShelley` (a body alone inside a definite header with a signature), the same hashes
and the same signed bytes, and signs a body in three non-canonical forms with a KES key of its own
to show that a signature over the canonical encoding checks and one over the original bytes does
not. It also requires the same verdicts of the VRF checks from `Header.vrfCert` and
`Verdicts.Direct`, whose refusals are never provider defects. `provenance.json` records the package versions, the hashes of the released sources against the
pin, of the harness, the generator, its input and output, and of the corpus.

## Regenerating

Build `scarab-header-decoding:local` as [the oracle README](../../../scripts/oracle/header-decoding/README.md)
describes, then:

```sh
python3 scripts/oracle/header-decoding/header_cases.py --inputs /tmp/header-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-header-decoding:local /fixtures/header-inputs.json > /tmp/header-reference.json
python3 scripts/oracle/header-decoding/header_cases.py --inputs /tmp/header-inputs.json \
  --reference /tmp/header-reference.json --out fixtures/reference/header-decoding/cases.jsonl
```

## Not covered here

- No KES signature, and no VRF proof of a header, is checked: their signatures and keys are filler
  bytes; the KES check over the signed bytes is `HeaderDecodingReferenceSuite`'s own key, and the VRF
  check is the "vrfcheck" cases'.
- Byron headers ([#574](https://github.com/KD5UJC-XYZ/scarab/issues/574)).
- A Leios block holding a detached header: Scarab reads the testnet's Leios blocks as trees
  ([#711](https://github.com/KD5UJC-XYZ/scarab/issues/711)).
