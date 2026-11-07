# Pre-Conway set decoding (decoder versions 2 to 7)

Status: Current. Evidence for [AUD3-03/#329](https://github.com/KD5UJC-XYZ/scarab/issues/329) and
[#374](https://github.com/KD5UJC-XYZ/scarab/issues/374).

`cases.json` holds 227 original encodings written by
`scripts/oracle/conway-decoding/pre_conway_cases.py`: for each era from Shelley to Babbage a
transaction body and a witness set whose set repeats an element (identical, or written with a wide
index or a wide array header, among other elements, in an indefinite-length array) or carries the
tag 258, each next to a twin without the fault.

`reference.json` is what the unchanged released decoders of cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5` did with each: `decodeFullAnnotator` at the era's
decoder version (Shelley 2, Allegra 3, Mary 4, Alonzo 5, Babbage 7), with the complete failure
text. Below version 9 the reference collapses a repeated element by decoded value and does not
read the tag (`decodeSet`, `Decoder.hs`): of the set cases 50 are accepted, the 10 tagged sets refused with
`expected list len or indef`. `provenance.json` records the package versions and the hashes of the
decoder source, harness, generator and both fixtures.

`cases.json` names for every refusal `rule` (a fragment of the reference failure) and `expect` (the
Scarab error case), and for every acceptance `size`, the elements that must remain after the repeats
collapse. `PreConwaySetsReferenceSuite` requires the same verdict and, for an acceptance, that the
retained bytes (and so the transaction id) are the original ones.

Each era also has a body whose update proposal (key 6) carries a `PParamsUpdate` read as the shared
sparse decoder reads it below decoder version 12 (`SparseKeyed`, `Core/PParams.hs:257-293`;
`applyField`, `Coders.hs:562-570`): every update key of the era (Shelley, Allegra and Mary 0 to 16,
`Shelley/PParams.hs`; Alonzo 0 to 14 and 16 to 24, `Alonzo/PParams.hs`; Babbage 0 to 11, 14 and 16
to 24, `Babbage/PParams.hs`; all f649f975), an empty and an indefinite update, keys outside the
era's set (`not a valid key`, Scarab `UnknownKey`) and a repeated key (`Duplicate key:`, Scarab
`DuplicateKey`): 15 accepted and 27 refused.

Each era also has, for #374 (measured on 2026-11-07 with the harness whose hash `provenance.json` records,
which reproduced the 102 earlier verdicts unchanged), 125 cases: 93 accepted and 32 refused.

- every collection of the body (inputs, outputs, certificates, collateral, required signers and reference
  inputs as lists; withdrawals and mint as maps) and every list of the witness set (vkeys, native scripts,
  bootstrap witnesses, Plutus scripts, datums and redeemers), empty and, for the witness lists, empty and
  indefinite: all accepted, because the non-empty rules apply from version 9 only
  (`ifDecoderVersionAtLeast (natVersion @9)`, `Alonzo/TxWits.hs:336-346, 549-560`);
- tag 258 on the native scripts and on the bootstrap witnesses, and the map form of Alonzo and Babbage
  redeemers: refused (`expected list len or indef`, Scarab `Expected`);
- a genesis key repeated in the proposers' map, with the same and with another update, and the last update
  alone: accepted. Below version 9 the map is `Map.fromList` (`decodeMapByKey`, `Decoder.hs:815-823`), so the
  last update is kept, which `PreConwaySetsReferenceSuite` also requires;
- the witness set's key set: Shelley, Allegra and Mary read keys 0 to 2 and every later era 0 to 7 (`not a
  valid key`, Scarab `UnknownFields`), and a repeated key is refused (`Duplicate key:`); the Plutus script
  lists of a language newer than the era's (Alonzo's keys 6 and 7, Babbage's 7) are accepted empty and refused
  with a script (`PlutusV2 isn't supported in the Alonzo era`, Scarab `UnsupportedLanguage`);
- a Babbage output's reference script of each tag: native, PlutusV1 and PlutusV2 accepted, PlutusV3 refused
  (`UnsupportedLanguage`), PlutusV4 and tag 5 refused (Scarab `UnknownTag`: the reference names PlutusV4 as an
  unsupported language, #796).

Scope: wire decoding only; no ledger rule, signature or script is run. Sets of the body and the
vkey witnesses are covered; the Conway rules are in the [conway-decoding corpus](../conway-decoding/README.md).

Regenerate with the [oracle recipe](../../../scripts/oracle/conway-decoding/README.md).
