# Url length (decoder versions 2 to 9)

Status: Current. Evidence for [#550](https://github.com/KD5UJC-XYZ/scarab/issues/550).

A `Url` (a pool's metadata url, a governance anchor's url) and a `DnsName` are read with
`textDecCBOR`, which bounds the text by its length in bytes of UTF-8 (`textSizeN`, measuring
`lengthWord8`): 128 bytes from decoder version 9 and 64 below it (`instance DecCBOR Url`,
`instance DecCBOR DnsName`, `ifDecoderVersionAtLeast (natVersion @9) (textDecCBOR 128) (textDecCBOR 64)`,
`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:644-697`, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`). Text over the bound fails with
`Text exceeds <n> bytes`.

`cases.jsonl` holds 33 lines, a case each: its original encoding (`bytes`, written by
`scripts/oracle/conway-decoding/url_length_cases.py`) and the verdict of the reference. Each place
is written at its era's bound (accepted) and one byte over it (refused):

- the pool metadata url of a registration (`PoolMetadata.pmUrl`) in each era from Shelley to Conway.
  Below version 9 there are also a url of 128 bytes (refused there, taken at 9) and urls of two-byte
  characters at 64 and 66 bytes, so that the bound is shown to be of bytes, not of characters;
- the anchor url of a Conway DRep registration, of a proposal and of a vote.

The verdict (`accepted` and the complete `failure` text) is what the unchanged released decoders did
with each: `decodeFullAnnotator` at the era's decoder version (Shelley 2, Allegra 3, Mary 4, Alonzo 5,
Babbage 7, Conway 9). In all, 14 accepted and 19 refused. `provenance.json` records the package
versions and the hashes of the decoder source, harness, generator, the corpus, and the harness's
input and output.

`cases.jsonl` names for every refusal `rule` (the reference failure `Text exceeds 64 bytes` or
`Text exceeds 128 bytes`) and `expect` (the Scarab error case: `TextTooLong`).
`UrlLengthReferenceSuite` requires the same verdict, and for an acceptance that the retained bytes
(and so the transaction id) are the original ones.

## Regenerating

Build `scarab-conway-decoding:local` as [the oracle README](../../../scripts/oracle/conway-decoding/README.md)
describes, then:

```sh
python3 scripts/oracle/conway-decoding/url_length_cases.py --inputs /tmp/url-length-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-conway-decoding:local /fixtures/url-length-inputs.json > /tmp/url-length-reference.json
python3 scripts/oracle/conway-decoding/url_length_cases.py --inputs /tmp/url-length-inputs.json \
  --reference /tmp/url-length-reference.json --out fixtures/reference/url-length/cases.jsonl
```

## Not covered here

- **A relay DNS name** (`DnsName`) has the same bound, but a registration's relays are kept as their
  original bytes and are decoded with the body only under
  [#436](https://github.com/KD5UJC-XYZ/scarab/issues/436). `PoolRelays.decode` takes 128 bytes at
  every version (a decision recorded in its documentation; the reference's 64 below version 9 is put
  to the owner on #550).
