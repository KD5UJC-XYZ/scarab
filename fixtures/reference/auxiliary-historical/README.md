# Historical auxiliary data: Shelley, Allegra, Mary (#290 stage A)

**Current: 504 independent API observations captured 2026-10-21.**

The unchanged released Haskell `TxAuxData` decoder and `validateTxAuxData` predicate run at
`ShelleyEra`/PV2, `AllegraEra`/PV3 and `MaryEra`/PV4 (the pairing the node's era decoder gives
them). Of 504 rows, 435 are auxiliary-only (`mode: aux`, 145 per era) and 69 are whole
transactions (`mode: tx`, 23 per era) decoded with the era `Tx` decoder, then checked with the
Utxow rule's own `validateMetadata` (cardano-ledger-shelley `Rules/Utxow.hs` 427-443, byte-identical
at `f649f975` `eras/shelley/impl/...`). Verdicts are produced only by the Haskell executable; the
runner re-derives only what is ledger-independent (Blake2b-256 of the original bytes, and the
hash/presence failure set). These are API observations, **not signed transaction admissions**.

Source files at `f649f975` equal the released ones for `Shelley/Rules/Utxow.hs`,
`Shelley|Allegra|Mary/TxAuxData.hs`, `Cardano/Ledger/Metadata.hs`, the Shelley/Allegra/Mary
`Tx`/`TxBody`. **Two differences are on the decode path:** `Allegra/Scripts.hs` (the oracle links the
released `cardano-ledger-allegra-1.10.1.0`, which the node requires with `>= 1.10.1`; the checkout
declares 1.10.0.0 and decodes nested timelock lists differently, `decodeNoBytesTimelock`) and the
binary `Decoder.hs`/`Encoder.hs` (IPv4 byte order only). Native-script rows therefore observe 1.10.1.0.
`provenance.json` lists every compared file count and difference.

## What the reference does (observed)

* Shelley (PV2) accepts only a metadata map. A `[metadata, scripts]` list is refused; Allegra/Mary
  accept a definite two-element list (an indefinite outer array is refused).
* The 64-byte bytes/text bound (`Metadata.hs` 156, `dv > 2`) does not exist at PV2 and applies from
  PV3, for values, nested values and map keys, definite and chunked, text counted in UTF-8 bytes.
* Tag 259 (and 258) and Plutus-language keys are refused in all three eras. A bare map with label 2
  is just metadata.
* `InvalidMetadata` is never produced before Alonzo: Shelley's validator is `True` and
  Allegra/Mary's is `deepseq True` on already-decoded scripts.
* Duplicate top labels and nested map keys decode (bytes retained); nested
  duplicates with an invalid first value are refused.
* Structural decoding precedes the hash check: wrong hash plus an invalid structure is a decoding
  refusal; a 65-byte metadatum with a wrong hash is `ConflictingMetadataHash` at PV2 but a
  decoding refusal at PV3/4.
* The hash is over the original bytes: a hash of the canonical re-encoding of a non-canonical or
  indefinite-length map is `ConflictingMetadataHash`; the original bytes match.
* Pre-Alonzo transactions are three-element arrays; a four-element Alonzo shape is refused.
* Native scripts nested through indefinite inner lists decode; an invalid element inside a nested
  `all/any/mOf` is refused.

## Scarab (stage B/C)

Before #290 Scarab decoded auxiliary data only from Alonzo, so these rows diverged wherever the
reference refused structure with a matching hash (for example `tx-match-bytes-65`, `-tag259`,
`-list-form`) or where a wrong hash met a structural refusal. Now `AuxiliaryScripts.read` is
era-aware (decoder versions 2/3/4), shared by `Tx.fromBlock` and `TxChecks.auxiliaryChecks`, and
`HistoricalAuxiliarySuite` replays all 504 rows against it without exclusions. The four-element
and two-element envelopes are refused by `Tx.fromEnvelope`; blocks carry segregated parts, so those
two rows never occur in a block.

Reproduction: [oracle recipe](../../../scripts/oracle/plutus/historical-auxiliary/README.md).
Original Apache-2.0 thin observer/generator code; no signing keys or production data. This finite
matrix does not prove all auxiliary inputs or surrounding transaction admission.
