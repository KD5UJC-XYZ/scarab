# Fresh PV11 rule and bootstrap observations

Actual Haskell generation and formal execution, followed by production Scala rule
replay. Source remains the supported `cardano-node 11.1.3` ledger reference:
`cardano-ledger` **f649f9751074d2ab3de033fc3912f29c9862c1f5**, formal artifact
**b3a4fd47a1144ff1fb994f89b1045e78159e07ae**, constrained generators
**966f65f3c28cb22fc45220dbd1408baea93b53d8**. No native ledger rule, persisted
layout, V5 migration or operational database changes.

The [manifest](manifest.json) binds every input, native output, independent formal
verdict, patch, final archive/object/executable and exact runner invocation.
The Scala [replay suite](../../../scala/xyz/kd5ujc/scarab/node/UpstreamFreshLedgerConformanceSuite.scala)
uses the original signed body and witness bytes and compares complete native
states. Native inline MemPack does not retain `TxOut.raw` or Scala change-history
bookkeeping; every decoded output/cache/accounting field is compared independently
before comparing the remaining state. Rejections compare the complete typed
Scarab failure payload with native accumulated failures. Differing native/formal
rejection text is retained; it is not claimed equal.

## Results and bounded scope

Twenty-six fixed runner invocations passed. They produced **41 lawful observations,
39 unique typed STS input tuples**: 28 unique accepted outputs and 11 refusals.
Two repeated bootstrap funding hooks are retained and marked `duplicateOf`; they
do not add coverage. Separately, nine original incoherent-cache draws remain typed
domain-refusal controls, not native/Scarab parity passes. The focused Scala suite
checks these records and their provenance.

| Boundary | Cases |
|---|---|
| Actual typed UTXO / UTXOW | Three fixed seeds each, native and formal accepted with equal complete translated states |
| Actual typed LEDGER | Three fixed seeds: two accepts and one withdrawal-delegation refusal |
| Actual typed LEDGERS | Fixed seeds 274101–274108, all attempts retained: seven withdrawal-delegation refusals, one accept; no retry until a desired outcome |
| Bootstrap | Original upstream valid witness and invalid-signature tests plus missing-witness control; funding and spending judgments retained |
| Overlap | A genuine generated PlutusV2 overlap mismatch retained before formal repair; identical inputs/native output accepted after repair. Independent upstream V2 overlap accepts, V3 overlap rejects, V3 disjoint reference-script use accepts |
| Other language controls | No-Plutus overlapping reference input accepts; PlutusV1 without reference inputs accepts |
| DRep lifecycle | Native registration, staking registration, delegation and DRep unregistration; four complete accepted native/formal LEDGER states |

The V3 overlap repair is language-specific. It removes the old unconditional
UTXO disjointness requirement and removes V3 from UTXOW's allowed languages when
inputs overlap; V1/V2 are not globally disabled. V1 reference inputs independently
fail native context translation (`Babbage/TxInfo.hs:329`); this corpus does not
claim a successful V1 transaction with reference inputs. It does not claim all
formal proof obligations, all parameter combinations or every upstream generator.

## Generator repairs and lawful state construction

The [native exporter patch](../../../../../../scripts/oracle/ledger-govcert/pv11-generators-bootstrap.patch)
backports the actual upstream changes:

- [f11490a9](https://github.com/IntersectMBO/cardano-ledger/commit/f11490a9d7a947c1a26b0c49700f9bb0ab380501): break the `initialLedgerState` deposit-pot self-reference; select explicit PV11.
- [bbc1844c](https://github.com/IntersectMBO/cardano-ledger/commit/bbc1844c0602de558d5eeebbde2c5c3d44cce3e8): reuse the context already used to generate each transaction.
- [d7297028](https://github.com/IntersectMBO/cardano-ledger/commit/d7297028d3ffc910e49183070cb1b33259dc579c) and [f8636b4c](https://github.com/IntersectMBO/cardano-ledger/commit/f8636b4c84bb070da67431f51993930b6d162007): translate bootstrap addresses and actual bootstrap witness hashes. The pinned formal address ABI stores `KeyHashObj`, so its representation is retained.

Upstream's four property labels all call the bundle typed `"UTXO"`; a label does
not change the STS type. This exporter explicitly calls `@"UTXO"`, `@"UTXOW"`,
`@"LEDGER"` and `@"LEDGERS"`, constructing the latter contexts with native types.
LEDGER/LEDGERS context construction is supplemental; it is not advertised as four
unchanged upstream property registrations.

The original generator leaves cached instant stake unrelated to the actual UTxO.
All **17** original generated states had this inconsistency; nine accepted
native/formal outputs reached a precise `Negative(CompactCoin, amount)` in Scala.
The first Scala run was **28 passed / 9 failed**. The other eight records rejected
before reaching the bad cache. Original observations are retained under
[original-generated](original-generated), and the nine underflows are also explicit
suite controls. Native accepted/formal accepted on an incoherent cached state is
not counted as Scarab parity, and no CompactCoin bound was relaxed.

The lawful supplement applies native `smartUTxOState` to the exact original UTxO,
computes deposits using native `totalObligation`, and supplies the same current
parameters as the environment. It does not filter or redraw seeds. All 17 original
contexts, environments and signals are byte-identical; their complete native UTxO
maps, fees and donations are unchanged. Only explicitly constructed state fields
change. Native/formal execution runs again on those full lawful states before
Scala replay. The suite independently recomputes instant stake from the complete
UTxO, asserts the original cache precondition fails, and checks each exact typed
underflow rather than accepting any `Left`.

## Formal runtime repair and historical failures

The [overlap patch](../../../../../../scripts/oracle/ledger-govcert/formal-overlap-runtime.patch)
backports published
[Conway UTXO](https://github.com/IntersectMBO/formal-ledger-specifications/blob/aba53bf3716b9829849b34b11556a5f65b7f4d22/src/Ledger/Conway/Conformance/Utxo.lagda.md)
and [allowedLanguages](https://github.com/IntersectMBO/formal-ledger-specifications/blob/aba53bf3716b9829849b34b11556a5f65b7f4d22/src/Ledger/Conway/Specification/Utxow.lagda.md)
semantics into the pinned generated Haskell runtime, preserving its record ABI and
erased proof tuple shape. This is **not a newly generated or proved Agda artifact**.
The previous [LEDGERS conversion repair](../formal-ledgers-repair/README.md) is also
required; it runs the formal computation, not the native implementation.

The original generated UTXOW seed 274103 accepted natively but failed formally
because V2 spending/reference inputs overlap. Its exact four inputs and native
output are retained in [original-formal-overlap](original-formal-overlap).
The corresponding `original-incoherent-utxow-274103` record has those exact five
files after only the formal repair, with native/formal accepted/equal. The later
lawful-cache supplement is a separate state construction and is not presented as
byte-identical to that original state.

Initial executions also retain three genuine formal SIGSEGV/139 results when the
original archive accidentally masked the repaired archive, and two native LEDGER
assertion failures before obligations were constructed coherently. These are
failed executions, not passes. Private logs and their hashes remain recorded in
`initialRuns`; the complete earliest executable was overwritten during iterative
builds, so its hash is not retrospectively inferred from the final runner.

The final formal archive replaces exactly three original modules: the previous
foreign LEDGERS conversion and the two overlap modules. Duplicate `ar` member names
were resolved by symbol inspection: Conway `Conformance/Utxo.o` and
`Specification/Utxow.o` are each occurrence 2 of their respective basename. Only
those occurrences were removed; uniquely named replacement objects were appended.
Independent review compared every archive member and verified every Dijkstra
object remains byte-identical. The final `lawfulRunner` hash binds only the final
26 invocations, not every earlier attempt. Its executable is frozen privately.

## Reproduction

Use a disposable copy of the three exact repositories above and the existing
[exporter dependencies](../conway-govcert/README.md), then apply the combined native
patch. Retain the previous formal LEDGERS conversion repair and apply the overlap
runtime patch. Build Haskell in Docker only (GHC 9.10.3, `-O1`, `-N2`, two CPUs).
The cached image contains the Cardano libsodium fork under `/usr/local/lib`; ensure
the linker and runtime resolve that fork rather than system libsodium.

Either rebuild the disposable formal package under a new local source pin, or
compile the two changed generated modules against the original interfaces with the
recorded unit ID and replace only their identified static-archive members.
The latter optimization reuses a package ID but changes source/object/archive and
runner hashes; the manifest binds those actual contents, not merely the ID. Native
module sources and all unmodified formal archive objects stay pinned.

For each manifest `runs` row, create an empty export directory, set its recorded
`exportVariable`, and invoke the exact argument vector. Every generator call uses
one success, fixed size zero, no shrinking and its fixed seed. The native helper's
internal `GenSize.small` remains unchanged. Integration hooks export each actual
judgment separately. Preserve original failures and separate state constructions;
do not overwrite expected files to make a comparison pass.
