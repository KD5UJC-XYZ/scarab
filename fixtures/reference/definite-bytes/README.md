# Definite byte strings (decoder versions 2 to 9)

Status: Current. Evidence for [#373](https://github.com/KD5UJC-XYZ/scarab/issues/373).

`cases.json` holds 444 original encodings written by
`scripts/oracle/conway-decoding/definite_bytes_cases.py`. For each era from Shelley to Conway, every
place of a transaction body and a witness set that holds a byte string (input ids, output addresses,
datum hashes, inline-datum and reference-script wrappers, the script inside a reference script,
policy ids and asset names, hashes of the body, certificate credentials, pool, genesis and governance
hashes, reward accounts, the extra entropy of a Shelley to Alonzo parameter update, vkeys, signatures, bootstrap witnesses and Plutus scripts) is written twice:
with a definite string and with the same bytes as one indefinite chunk. A whole Conway transaction
adds the auxiliary native and Plutus scripts, and chunked metadata. The chunked forms the reference
keeps are also present: Plutus `Data` in a witness datum, a redeemer and the content of an inline
datum, and metadata byte and text strings.

`reference.json` is what the unchanged released decoders of cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5` did with each: `decodeFullAnnotator` at the era's decoder
version (Shelley 2, Allegra 3, Mary 4, Alonzo 5, Babbage 7, Conway 9), with the complete failure text.
Below version 12 `decodeBytes` and `decodeByteArray` read a definite string only
(`ifDecoderVersionAtLeast (natVersion @12)`, `Decoder.hs:350-373, 1426-1448`), so the 206 chunked
strings are refused with cborg's `expected bytes` or `expected string` and their 206 definite twins
are accepted; an input id in two chunks, or after an empty chunk, is refused too (12 more). Plutus
`Data` and metadata (`decodeMetadatum`, `Metadata.hs:153-222`) read chunked strings in every
version: those 17 cases are accepted, and the metadata bound of 64 bytes holds for the joined string
(3 refusals with `bytestring exceeds 64 bytes` or `text exceeds 64 bytes`). In all, 223 accepted and
221 refused.
`provenance.json` records the package versions and the hashes of the decoder sources, harness,
generator and both fixtures.

`cases.json` names for every refusal `rule` (a fragment of the reference failure) and `expect` (the
Scarab error case: `IndefiniteLength` for a chunked string, `NumberOutOfRange` for the metadata
bound). `DefiniteBytesReferenceSuite` requires the same verdict, and for an acceptance that the
retained bytes (and so the transaction id) are the original ones.

Scope: wire decoding of transactions only; no ledger rule, signature or script is run. Headers,
node-to-client query arguments, the other values of a pre-Conway parameter update, pool relays and
text strings are outside this corpus.

Regenerate with the [oracle recipe](../../../scripts/oracle/conway-decoding/README.md).
