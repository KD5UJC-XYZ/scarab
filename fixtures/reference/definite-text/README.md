# Definite text (decoder versions 2 to 9)

Status: Current. Evidence for [#435](https://github.com/KD5UJC-XYZ/scarab/issues/435).

Every text field of a block body is read with `decodeString` — one instance decides it,
`instance DecCBOR T.Text where decCBOR = decodeString`
(`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs:356-358`, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`) — and cborg's `decodeString` takes a definite-length
string in every decoder version. An indefinite (chunked) string, whose head is `0x7f`, is not text
there: the released decoders fail it with `expected string`. `Url` and `DnsName` read that same
reader through `textDecCBOR` (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:644-668`,
the instances at `678-697`).

`cases.jsonl` holds 19 lines, a case each: its original encoding (`bytes`, written by
`scripts/oracle/conway-decoding/definite_text_cases.py`) and the verdict of the reference. Each
place is written twice, with a definite string and with the same characters as one indefinite chunk,
so a refusal is for the form of the string alone:

- the pool metadata url of a registration (`PoolMetadata.pmUrl`), in each era from Shelley to
  Conway — 6 accepted, 6 refused;
- the anchor url of a Conway DRep registration, of a proposal and of a vote (`Anchor.anchorUrl`) —
  3 accepted, 3 refused;
- one chunked metadata text in a whole Conway transaction, which the reference **accepts**
  (`decodeMetadatum`, `Metadata.hs:153-222`): metadata has its own reader and does not take this
  policy.

The verdict (`accepted` and the `failure` text) is what the unchanged released decoders did with
each: `decodeFullAnnotator` at the era's decoder version (Shelley 2, Allegra 3, Mary 4, Alonzo 5,
Babbage 7, Conway 9), with the complete failure text. In all, 10 accepted and 9 refused.
`provenance.json` records the package versions and the hashes of the decoder sources, harness,
generator, the corpus, and the two files of the harness (its input array and its output, which the
generator merges into `cases.jsonl`).

`cases.jsonl` names for every refusal `rule` (a fragment of the reference failure) and `expect` (the
Scarab error case: `IndefiniteLength`). `DefiniteTextReferenceSuite` requires the same verdict, and
for an acceptance that the retained bytes (and so the transaction id) are the original ones.

## Regenerating

Build `scarab-conway-decoding:local` as [the oracle README](../../../scripts/oracle/conway-decoding/README.md)
describes, then:

```sh
python3 scripts/oracle/conway-decoding/definite_text_cases.py --inputs /tmp/definite-text-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-conway-decoding:local /fixtures/definite-text-inputs.json > /tmp/definite-text-reference.json
python3 scripts/oracle/conway-decoding/definite_text_cases.py --inputs /tmp/definite-text-inputs.json \
  --reference /tmp/definite-text-reference.json --out fixtures/reference/definite-text/cases.jsonl
```

## Not covered here

- **A relay DNS name** (`DnsName`) is one of these fields. Its cases, read with the body since
  [#436](https://github.com/KD5UJC-XYZ/scarab/issues/436), are in the
  [pool-relays corpus](../pool-relays/README.md).
- **The length bound.** The same readers bound the field with `textSizeN`: 64 bytes of UTF-8 below
  decoder version 9, 128 from it. No case here is at or over the bound; the
  [url-length corpus](../url-length/README.md) holds those cases
  ([#550](https://github.com/KD5UJC-XYZ/scarab/issues/550)).
- Byron's application name (`ApplicationName`, `DecCBOR` over `Text`) takes the same reader and is
  definite-only in the code after this change; no Byron case is in this corpus.
