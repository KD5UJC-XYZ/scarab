# Auxiliary scripts: decoder admission and validity (#286)

**Current: 490 independent API observations captured 2026-10-20.**

The unchanged released Haskell `TxAuxData` decoder and `validateTxAuxData` predicate execute separately
at `AlonzoEra`/PV5–6, `BabbageEra`/PV7–8 and `ConwayEra`/PV9–11. Of 490 cases,324 are decoder refusals,
136 decode and are valid, and 30 decode but contain invalid Plutus scripts. Original synthetic CBOR,
closed outcomes, exact generator, immutable observer image/executable and source hashes are retained.
These are auxiliary API observations, **not signed transaction admissions**. Signed normal-era-load
controls separately exercise the same script bytes with real transaction hashes/signatures/effects.

Cases include every language field and its availability gate (even empty lists), valid and malformed
Flat bytes, well-formed runtime-error terms, all-three-language contents, native scripts, metadata,
unknown fields/tags/shapes, duplicate sparse auxiliary fields, Word64 constructor tags and the UTF8
byte-size boundary. All repeated auxiliary sparse-map fields are decoder refused. Repeated *top
metadata labels* are accepted before PV9 and refused from PV9; repeated *nested Metadatum map keys*
remain accepted. Definite and chunked byte/text values obey the same combined 64-byte bound.

Normal era-load's exact auxiliary script bytes are retained: `46010000222499` (well-formed unused
program), `4401000061` (well-formed runtime-error program), `4100` (truncated Flat). Auxiliary scripts
are deserialized for validity; they are not executed and are not made available as transaction
witnesses. Runtime-error programs therefore remain valid auxiliary scripts.

`AuxiliaryScriptsSuite` compares every decoder/predicate result for both transaction validity flags,
and checks the public transaction decoder using era-correct original body/witness controls. It also
checks structural errors before hashes, hash errors before Flat validity, typed backend unavailability
in either script order, deep metadata on a 256KiB stack and late full-BBODY serial/prepared refusal
without publishing an earlier transaction prefix. The whole-transaction fuzz targets retain these
auxiliary bytes as structured seeds, with an explicit accepted/refused reachability guard.

Pins: cardano-node 11.1.3, cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`,
Plutus 1.70.0.0. The observer uses GHC 9.10.3; the official executable used GHC 9.6. Released auxiliary
and metadata source hashes equal the pinned source checkout. No implementation source was patched.
The [recipe](../../../scripts/oracle/plutus/auxiliary/README.md) reproduces offline measurements.
Original Apache-2.0 thin observer/generation code; upstream notices/licenses remain under
`fixtures/licenses` and fetched source distributions. No signing keys or production data.

This finite decoder/predicate matrix does not prove all auxiliary inputs, surrounding transaction
admission or historical diagnostic bytes. Diagnostic strings are outside the acceptance scope.
