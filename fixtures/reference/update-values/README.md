# Parameter-update values (decoder versions 2 to 9)

Status: Current. Evidence for [#470](https://github.com/KD5UJC-XYZ/scarab/issues/470) and the
second part of [#436](https://github.com/KD5UJC-XYZ/scarab/issues/436).

Below decoder version 12 the reference reads each value of a `PParamsUpdate` at the type of its
field when it decodes the transaction body (`SparseKeyed` and `mkField`,
`libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:257-293`, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`):
- `Word16`, `Word32` and `Word64` fields (`CompactForm Coin`, `Coin` and `EpochInterval` among them)
  read with `decodeWord16`, `decodeWord32` and `decodeWord64`;
- a `UnitInterval` or `NonNegativeInterval` as tag 30 over two integers, reduced and bounded over
  `Word64` (`BaseTypes.hs:386-427`);
- `Prices` and `ExUnits` as `RecD` records (`Plutus/ExUnits.hs:195-231`), definite lists only
  (`decodeCount`, `Binary/Decoding/Coders.hs:482`), each unit at most `maxBound :: Int64`;
- the extra entropy as a `Nonce`;
- the protocol version by `decodeProtVer` (`BaseTypes.hs:253-262`), a major at most one above the
  era's `ProtVerHigh`: Shelley 3, Allegra 4, Mary 5, Alonzo 7, Babbage 9.

`cases.jsonl` holds 450 lines, one case each, written by
`scripts/oracle/conway-decoding/update_value_cases.py`. For each era from Shelley to Babbage, a body
whose update sets one value, for every update key of the era except the cost models: the value at
the bound of its type and one over, a rational also inside its interval and without tag 30; and,
once for each type, a wide head, a negative integer, the wrong major type, the wrong length and a
zero denominator. The protocol version also gets majors 13 and 14, a minor at and over `Word32` and
an indefinite record; the extra entropy gets each arity. Conway bodies whose parameter change sets
`Prices` or `ExUnits`, definite and indefinite, show that Conway reads the records the same way.

The verdict (`accepted` and the complete `failure`) is what the unchanged released decoders did with
each: `decodeFullAnnotator` at the era's decoder version. In all, 161 accepted and 289 refused. Every
refusal names `rule`, a fragment of the reference failure, and `expect`, the Scarab error case.
`UpdateValuesReferenceSuite` requires the same verdict from `TxBody.decode` and, for an era with
PPUP, from the bootstrap ledger's `LiteLedger.effects`, and for an acceptance that the retained
bytes (and so the transaction id) are the original ones. `provenance.json` records the package
versions and the hashes of the harness, the generator, its input and output, and the corpus.

## Regenerating

Build `scarab-conway-decoding:local` as [the oracle README](../../../scripts/oracle/conway-decoding/README.md)
describes, then:

```sh
python3 scripts/oracle/conway-decoding/update_value_cases.py --inputs /tmp/update-values-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-conway-decoding:local /fixtures/update-values-inputs.json > /tmp/update-values-reference.json
python3 scripts/oracle/conway-decoding/update_value_cases.py --inputs /tmp/update-values-inputs.json \
  --reference /tmp/update-values-reference.json --out fixtures/reference/update-values/cases.jsonl
```

## Not covered here

- The cost models (key 18) are covered by the cost-model corpora.
- An update held in a stored ledger state is read as the builds before #470 stored it (ADR 0006):
  a pre-Conway update when it is applied, by `ProtocolParams.update`, and a Conway proposal by
  `ConwayParamUpdate.decodeStored`, which takes `Prices` and `ExUnits` as indefinite lists too.
- Other `RecD` records reachable from a block are
  [#668](https://github.com/KD5UJC-XYZ/scarab/issues/668).
