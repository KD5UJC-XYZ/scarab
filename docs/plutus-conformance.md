# Plutus conformance: executed reference boundaries

**Status: Current.** All 1,009 pinned upstream evaluation cases pass with exact recorded results
and budgets, without exclusions. Ledger admission remains a separate tested boundary; recorded
experiments remain tied to their source revisions.

The original [#155](https://github.com/KD5UJC-XYZ/scarab/issues/155) (AUD-03) triage established
which dependency differences reached ledger validation. That historical classification is retained
below; it no longer excuses an upstream test failure.
[`PlutusConformanceSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/PlutusConformanceSuite.scala)
now runs the complete original corpus with the reference executable's execution profile. Trigger
details are not written here (see [SECURITY.md](../SECURITY.md)).

The profile follows `PlutusConformance/CekEvaluator.hs:46-61` at Plutus commit
`507cab8cf6192e5143f12be5b2d3005a2928dc6d`: check the whole term for free variables, then evaluate
the original term with variant E and saturating counting. The machine is the one production builds:
`ScalusEvaluator.corrected` makes the evaluation context of the request's language, protocol and
cost model, and `CorrectedVm.cek` the CEK machine, with the corrected builtin runtimes. Two
settings differ from the ledger's, both because the reference executable differs from the ledger
there. The budget is the counting one (`counting`) where the ledger restricts. `case` on `Data` is
allowed (the executable's default caser is `caseBuiltin`) where the ledger refuses it from protocol
11 until Dijkstra (`caseBuiltinNoData`, `PlutusLedgerApi/Common/Eval.hs:142-146`); the two
`term/constant-case/data` cases are the cases that rest on it, and the suite checks that they fail
with the ledger's setting. No Flat serialization or unit-return wrapper is
inserted before term evaluation. Existing original-byte sidecars still test malformed Value
constants before the dependency text parser can normalize their entries.

All 1,009 upstream cases retain their original expectations; the former six BLS constants, five
saturating `dropList` cases and two Data cases now execute and pass. A differential regression runs
those same thirteen cases through the previous ledger/Flat harness, reproduces its mismatches,
and requires the exact original term result and budget from the corrected profile. Separate
`PlutusReachabilitySuite` tests retain the ledger's Flat admission, protocol gates and restricting
budgets. A counting-profile success is not a claim that a transaction with that term is admissible.

## 1. The reference evaluator

Node 11.1.3 (release source revision `938cba990357ae7c4b7f95c8f75dd9d31174bbeb`, archive pinned in
[reference-image.md](reference-image.md)) contains these Plutus packages. The pin is the package
identifiers inside the pinned `cardano-node` executable (SHA-256 `ee396604...a336c3`, the value
`scripts/prepare_reference_image.py` checks), not the cabal index state, which only fixes what the
solver could choose:

| Package | Version in the executable | Source |
|---|---|---|
| `plutus-core` | 1.70.0.0 | IntersectMBO/plutus tag `1.70.0.0`, commit `507cab8cf6192e5143f12be5b2d3005a2928dc6d` |
| `plutus-ledger-api` | 1.70.0.0 | same commit |
| `cardano-ledger-api` / `-core` / `-conway` | 1.14.0.0 / 1.21.0.0 / 1.23.0.0 | cardano-ledger `f649f975` |

The conformance suite we run is `plutus-conformance` of that same commit, so the suite and the node
evaluate with one evaluator. Cost model parameters are protocol parameters (the on-chain lists), not
part of the evaluator; the evaluator selects the *shape* by builtin semantics variant:

| Language | Protocol 5 to 8 | 9 and 10 | 11 |
|---|---|---|---|
| V1, V2 | A (`PlutusLedgerApi/V1/EvaluationContext.hs` 53-57, the same in V2) | B | D |
| V3 (from 9) | n/a | C (`V3/EvaluationContext.hs` 50-53) | E |

The parameter-name lists of 1.70.0.0 (the order of the on-chain list) have 343 names for V1 and for
V2 and 361 for V3 (`plutus/src/test/resources/plutus-1.70.0.0`). Language and builtin availability
(`PlutusLedgerApi/Common/Versions.hs`, 109-110 and 320-356, transcribed in
[`PlutusAvailability`](../plutus/src/main/scala/xyz/kd5ujc/scarab/plutus/PlutusAvailability.scala) and
checked by `PlutusAvailabilitySuite`):

| Protocol | V1 gains | V2 gains | V3 gains |
|---|---|---|---|
| 5 (Alonzo) | batch 1 (the Alonzo builtins) | | |
| 7 (Vasil) | | batch 1 and 2 (`serialiseData`) | |
| 8 (Valentine) | | batch 3 (secp256k1) | |
| 9 (Chang) | | | batches 1 to 4 (BLS12-381, Keccak, Blake2b-224, integer/bytestring conversion) |
| 10 (Plomin) | | `integerToByteString`, `byteStringToInteger` | batch 5 (bitwise, Ripemd-160) |
| 11 (van Rossem) | batches 2 to 6 | the rest of batch 4 (BLS12-381, Keccak, Blake2b-224), batches 5 and 6 | batch 6 (`dropList`, arrays, `value` builtins, `expModInteger`) |

`case` on a constant of a built-in type is refused before protocol 11, allowed from 11 except on
`Data`, and allowed on `Data` from 12 (`Common/Eval.hs` `defaultCaserBuiltinFor`, 142-146).
UPLC program version 1.1.0 (`constr`, `case`) is V3 from 9 and V1/V2 from 11.

## 2. Where a script is decoded and checked

1. `deserialiseScript` (`Common/SerialisedScript.hs` 195-263) is called from the ledger in phase 1 from
   Babbage on, for every Plutus script of the witness set and of the outputs, the collateral return
   included (cardano-ledger `f649f975`, `eras/babbage/impl/.../Rules/Utxow.hs` 234-243,
   `MalformedScriptWitnesses`, `MalformedReferenceScripts`). In Alonzo an undecodable script that a
   transaction needs fails phase 2. It checks the language, the flat encoding with the builtin table,
   the constant type header (at most 32 tags from protocol 11) and the fields of `constr` (at most 1024
   from 11), and decodes `value` constants through `buildValueWith`.
2. `mkTermToEvaluate` (`Common/Eval.hs` 107-130) is phase 2: the UPLC version, then the scope check of
   the applied term (`UntypedPlutusCore/Check/Scope.hs` 28-45). A failure there is a failed script.
3. The machine runs under the cost model and the budget of the redeemer.

### Auxiliary-data admission (#286)

Auxiliary data is decoded structurally before ledger predicates. Its original bytes remain the
input to the auxiliary-data hash; decoding never replaces them with a normalized encoding.
Alonzo and later validate every contained Plutus script with the active language/protocol decoder,
even if the transaction has no phase-2 script or advertises the invalid validity flag. Auxiliary
scripts do not become witness scripts and are not evaluated. A deserializable script containing
`error` therefore differs from malformed Flat bytes.

The correspondence is `Shelley/Rules/Utxow.hs:427–443` (`validateMetadata`) and
`Alonzo/TxAuxData.hs:220–306,369–373` (`decodeAlonzoTxAuxData`, `validateAlonzoTxAuxData`) at ledger
`f649f975`. Structural auxiliary failures stay typed decoding failures. Once decoding succeeds,
hash/presence checks precede script well-formedness; malformed contained Plutus produces the existing
`InvalidMetadata` predicate. Backend unavailability supplies no script verdict and remains typed
unavailability. The same phase-1 check precedes validity-tag/UTXOS checks on serial and prepared
paths, so a refusal discards tentative state before any collateral effects can be published.

The [independent auxiliary corpus](../fixtures/reference/auxiliary-scripts/README.md) contains 490
observations: 324 decoding refusals, 136 valid auxiliary values and 30 invalid Plutus-script values.
It separates structural decoding from the unchanged reference validity predicate. Duplicate sparse
auxiliary fields are refused; top metadata labels become unique from protocol 9, while nested
metadata map keys retain their historical duplicate behavior. The metadata walk is stack safe. The normal era-load driver
also retains signed-reference auxiliary transactions and observed input/output effects separately
from the API corpus. Exact diagnostic prose is outside both comparisons.

The [historical corpus](../fixtures/reference/auxiliary-historical/README.md) (#290) records
the same unchanged-reference observations for Shelley, Allegra and Mary at protocols 2-4 (504
rows): the 64-byte metadatum bound starts at protocol 3 (Shelley keeps accepting longer values),
only Allegra and Mary read the definite `[metadata, native scripts]` list, tag 259 and Plutus
fields are refused before Alonzo, structure precedes the hash check and the hash covers the
original bytes. Scarab reads auxiliary data with one era-aware reader (`AuxiliaryScripts.read`,
decoder versions 2/3/4 are `eraProtVerLow`, Core/Internal/Definition/Era.hs 95-109) from both
`Tx.fromBlock` and `TxChecks.auxiliaryChecks`; `HistoricalAuxiliarySuite` replays all 504 rows
without exclusions.

## Independent costing and context matrix (2026-10-20)

The remaining admission/context work is at Scarab's ledger integration and verification boundary;
it is not a demonstrated missing Scalus language implementation. The VM dependency remains unchanged
by the expanded costing and context campaigns. Counting-term conformance, script deserialization,
context construction and signed transaction admission are distinct claims.

[#287](https://github.com/KD5UJC-XYZ/scarab/issues/287) adds an unchanged-Haskell
[costing corpus](../fixtures/reference/plutus-costing/README.md): 1,858 original-byte scripts across
all fifteen legal V1/V2/V3 language/protocol profiles from 5 through 11, each under the default
parameters and a second valid model with every parameter doubled. The 55,740 primary observations
agree on decoding, evaluation stage and 37,184 exact successful CPU/memory budgets. Each successful
case also agrees at its exact budget and with either CPU or memory reduced by one: 111,552 additional
restricting observations. Inputs mention all 101 released builtins and sample arithmetic sizing and
diagonal branches, byte-word boundaries, structured Data/list/array/Value sizes, and real crypto.
The cost-shape inventory explicitly identifies unreleased names. These finite grids establish neither
universal equality for all parameter vectors nor consumed-budget equality after failure; the reference
restricting API does not expose failure consumption. Diagnostic prose remains outside the comparison.

[#288](https://github.com/KD5UJC-XYZ/scarab/issues/288) adds an era-dispatched unchanged-Haskell
[context corpus](../fixtures/reference/context-matrix/README.md): 309 cases, including 98 original
signed transaction inputs and 211 clearly labelled API-only derivations. Every legal era/language/
protocol cell is represented: Alonzo 5/6, Babbage 7/8 and Conway 9/10/11. The four historical purposes
and Conway voting/proposing have accepted controls or explicit language refusals. All 214 complete
ordered requests from 174 successful collections match, including full context arguments, clocks,
cost models, scripts and declared budgets. The 135 collection refusals match their ordered typed
categories; Conway also matches complete error CBOR. Evaluation matches 189 successful budgets and
25 failures. Historical reference error bytes are retained without asserting historical wire parity; that separate contract remains [#123](https://github.com/KD5UJC-XYZ/scarab/issues/123).
The observer calls the actual era's API; it does not translate every input as Conway or compare a
Scalus-built context against another Scalus-built context.

Neither API campaign establishes signature checking, complete UTXOW/BBODY admission or collateral
effects for its derived probes. Original signed reference corpora retain those separate claims.
Routine era load [#44](https://github.com/KD5UJC-XYZ/scarab/issues/44) now requires twenty V1/V2
spending/minting controls across Alonzo, Babbage and Conway: successful execution, invalid-tag
collateral consumption and both opposite-validity rejections, with observed normal/collateral inputs
and complete resulting outputs/minted assets. This remains a bounded admission matrix rather than
a Cartesian enumeration of every transaction shape and protocol parameter.

The fixture manifests pin source, input, output, tool and image identities. Both campaigns use
Plutus `507cab8cf6192e5143f12be5b2d3005a2928dc6d` / ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`, GHC 9.10.3 and complete Linux/amd64 crypto.
They are correctness measurements, not latency or throughput benchmarks.

### Integrated admission gates (2026-10-21)

Measured source `5b290879b8ca8fe12350696e8ad7cc102d4d61f8`, rebased onto main
`2fcd309047c7935de653fd6fd0f22ec95364c2dd`, passes Docker `check` (6,449 passed,17 optional skips),
331 Python tests and the complete private ledger e2e. All twenty legacy and twenty-four auxiliary
signed controls pass at individually acquired reference era/protocol/points. All 66 Shelley-through-
Conway ledger projections match across 1,691 block applications through epoch17, with zero comparison
mismatches/errors/skips or validation/fatal errors. The [compact admission fixture](../fixtures/reference/plutus-admission/README.md)
retains original signed bytes, effects, all comparison points, commands and full image identities.

The first era-load attempt exposed a bootstrap defect: an extra V2 model in Alonzo genesis was not
adopted at the hard fork. The PV6-to-7 update now installs V1 and V2 together, preserving V1; the pinned
CLI encodes the expected 166/175 parameters and the final live Babbage V2 cases verify adoption.
The first integrated scenario then passed all 44 transaction cases but failed three Alonzo comparisons:
reference JSON normalized pending cost-model CBOR to definite arrays, whereas Scarab's JSON retained
original indefinite arrays. Every one of the 341 parameters already agreed. The driver now strictly
compares complete language-to-ordered-Int64-vector values for only PPUP field18, preserving both raw
encodings as evidence. It refuses duplicate languages, malformed/trailing encodings and changed values,
order or length; other fields remain unchanged. Counterfactual tests fail against the old comparator
and pass the correction. Comparison errors now fail the scenario even when other points match.
The successful run retains all nine affected proposer/point observations, rather than avoiding them.

Extended fuzz passed 84 targets / 33,600,000 cases on `b991c9413`; the subsequent correction changed
only Python comparison/tests and a changelog fragment, leaving all Scala decoders/fuzz sources
identical. The frozen scenario runtime matched all 12,725 build files and 64 dependency jars before
and after check. Fixture manifests' earlier preparation source revisions identify their capture
bases; separately hashed observer/generator tools and this measured source identify the actual tests.
These results add no latency, allocation or throughput claim.

The scenario retained 45 handled rollbacks and seven KeepAlive reconnects. Source/timing evidence
identifies the responder's 97-second idle deadline; an eager reverse-direction responder versus
upstream demand-driven startup is a hypothesis tracked in [#292](https://github.com/KD5UJC-XYZ/scarab/issues/292),
not a newly proved transport root cause or a claim of continuous network health. Pre-Alonzo auxiliary
structure remains [#290](https://github.com/KD5UJC-XYZ/scarab/issues/290). This stage is ready for review;
issue completion still requires main landing and green main CI.

After main advanced with BlockFetch coordination (#289), source `0c94e76a4` was rebased onto
`0a6693fb0` and passed fresh Docker check (6,781 passed/17 optional skips), Python331/331, source-head
CI and the complete e2e:1,656 applications,66 matched projections,44 signed cases,36 handled rollbacks
and seven reconnects; zero comparison/validation/fatal errors. The [separate rebased receipts](../fixtures/reference/plutus-admission/README.md#rebased-integration-gate)
retain new signed bytes/points rather than relabeling the earlier run. All12,804 actual runtime files
and64 jars match the build. The unchanged auxiliary/transaction/fuzz decoder implementation retains
its historical extended-fuzz evidence; new upstream fetch lifecycle/configuration/sampling is covered
by the fresh integration gates, not by claiming an exact-head extended-fuzz rerun.

### Platform boundary

Platform support is separate from these semantic results. The JVM build already uses JNI;
a native executable is not required to support another operating system or CPU. Each target needs
the corrected portable blst library and raw-byte-DST JNI bridge built for its OS/architecture,
packaged with deterministic selection, and exercised on that target with startup known answers,
BLS/DST boundary cases and the reference corpus. An absent or incompatible library must retain the
typed unavailable outcome. Current Linux/amd64 evidence does not certify another target.

A future GraalVM Native Image experiment adds JNI reachability, library packaging/loading and
initialization constraints; it does not remove the native crypto dependency. It is outside this
correctness stage. See the official [JNI design](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html)
and [Native Image JNI requirements](https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/JNI/).
Exact diagnostic string parity remains optional [#208](https://github.com/KD5UJC-XYZ/scarab/issues/208).

## 3. Historical classification of the original 22 cases

The original class labels were (ii), a dependency deviation, and (iii), a harness-profile difference.
They describe the earlier investigation, not exclusions from the current suite.

Original triage counts: **9 are reachable** by a transaction (6 `value` constants, 3 `hashToGroup`), **2 are not a
divergence** (`case` on `Data`), **11 are artefacts of the suite harness** (6 BLS12-381 constants,
5 `dropList`). This agrees with the second audit's split. "Reachable" means that the ledger-level
checks above admit a script that behaves differently from the reference; every reachable script fits
the transaction and block size limits (a few hundred bytes). The pinned Haskell ledger API has now executed every documented boundary at protocols 9, 10 and
11 in V1, V2 and V3: 53 original-byte probes, 477 decoder/evaluator verdicts. Its complete crypto
backend is enabled; no crypto stubs supply evidence. The retained
[oracle recipe and provenance](../scripts/oracle/plutus/README.md),
[original scripts and verdicts](../fixtures/reference/plutus-boundaries/README.md), and
`PlutusBoundaryOracleSuite` bind each row to its script bytes and cost model. All rows agree on
admission and evaluation stage. This API matrix is complemented by four actual reference-node Conway/protocol10 transactions
covering binary-DST success/failure against both validity flags. The same assertions are replayed
through phase2, LEDGER and BBODY. A separate
[complete boundary transaction corpus](../fixtures/reference/plutus-boundary-transactions/README.md)
adds actual reference submissions at observed protocols 10 and 11 for decode limits, applied scope
and wide indices, with both validity flags and both funding/collateral dispositions. Its 36 pinned
API observations apply the exact ignored-context wrapper before evaluation; they are distinct from
the original 477-row API matrix. Transaction-context evidence remains recorded separately.

| Case(s) | Class | Scalus 1.3.0 against the reference (as triaged) | Protocols where it exists | Reachable from a transaction? | Effect | Handling |
|---|---|---|---|---|---|---|
| `parser/value/` `currencyIDs-unordered`, `duplicate-currencyIDs`, `duplicate-tokenIDs`, `empty-tokens`, `tokenIDs-unordered`, `zero-asset` (6) | (ii), now (iii) in the suite | Scalus rebuilds a `value` constant as a normalised map when it decodes, so the malformed constant is accepted (and changed); the reference fails decoding (`PlutusCore/Value.hs` 637-694) | every protocol and language where a script exists: type tag 13 has no protocol gate, only the header size is checked (`SerialisedScript.hs` 199-215, `Versions.hs` 394-399) | yes: any Plutus script in a witness set or an output, whether or not it runs | decode: the reference makes the transaction invalid in phase 1 (Babbage on) or fails phase 2 (Alonzo); we accepted it | fixed on the ledger path by `FlatScan`, which validates the original flat bytes (also a key over 32 bytes and a quantity beyond 127 bits); tests `PlutusReachabilitySuite`, `PreConwayRulesSuite`; the suite now uses original Flat sidecars and removes these six exceptions |
| `semantics/bls12_381_G1_hashToGroup/hash-dst-len-255`, `..._G2_...`, `bls12_381-cardano-crypto-tests/signature/large-dst` (3) | (ii) | the domain separation tag reaches blst as a Latin-1 `String`; a tag with a byte of 0x80 or more (or 0) yields another point (the cases' tags are such bytes: the length 255 is not the cause) | batch 4 builtin: V3 from 9, V1 and V2 from 11 | yes, by a script that hashes with a tag it builds or is given; ASCII tags are right | result: a different point, so a different verdict if the script compares it | fixed in Scalus 1.4.0, whose scalus-crypto-jni passes the tag to blst as bytes on every platform; the earlier Linux/amd64 JNI backport and the other platforms' refusal are removed; the three exceptions remain removed |
| `term/constant-case/data/data-01`, `data-02` (2) | (ii), now (iii) | Scalus refuses `case` on `Data` below protocol 12 and applies the fields from 12, as the reference ledger does; the suite's executable uses `caseBuiltin` with `Data` | `Data` case from 12 only | no: the reference ledger uses `caseBuiltinNoData` at 11 and refuses too | none at protocols 5 to 11 | executes and passes under the upstream Data-case profile; separate regression tests retain the ledger protocol boundaries |
| `parser/bls12-381/{G1,G2}/{zero,on-curve-bit3-clear,on-curve-bit3-set}` (6) | (iii) | the previous Scarab harness round-tripped a BLS12-381 constant through flat; flat has no such constant, and Scalus's encoder writes an application of `uncompress`; a flat byte string that does carry the constant tag is refused by the reference (`Flat Element` instances) and by Scalus alike | batch 4 | no: the decode failure is the same | none | direct parsed-constant evaluation passes the original result and budget; Flat admission remains separately tested |
| `semantics/dropList/dropList-09`, `-10`, `-14`, `-15`, `-16` (5) | (iii) | the suite counts the budget with saturating arithmetic (the maximum); in restricting mode both run out of budget; only the units reported after the failure wrap negative in Scalus; the cost never wraps to a small value | batch 6: V3, V1, V2 at 11 | the script fails in both; phase 2 failure ignores the units spent | none on the verdict; the reported units on a failure | saturating counting now passes the original result and budget; separate restricting-budget tests retain ledger failure checks |

## 4. Ledger admission rules

[#166](https://github.com/KD5UJC-XYZ/scarab/issues/166) (protocol-11 decode limits) and
[#167](https://github.com/KD5UJC-XYZ/scarab/issues/167) (closedness of the applied term, variable
indices kept at 64 bits) are the same kind of difference. They are fixed by the same pass over the
flat bytes that handles the `value` constants:

- A constant whose type header exceeds 32 tags, or a `constr` with more than 1024 fields, does not
  decode from protocol 11 (below it there is no bound). A header is always odd, so the boundary is 31
  against 33.
- The scope check is phase 2 (`EvalFailure.OpenTerm`) and mirrors `checkScope`: it applies to
  variables, lambdas, applications, `force` and `delay`, and does not look inside `constr` fields or
  `case` terms. Original variable indices are checked as Word64 values: 65-bit naturals fail decoding. Wide
  indices under the checked syntax fail scope setup. Under skipped `constr`/`case` subterms, an
  internal open-variable view preserves deferred evaluation: ignored variables succeed, evaluated
  ones fail at runtime, exactly as measured in the reference. Original bytes still supply hashes
  and the scope check. The repair walks 20,000 levels on a 256 KiB stack.

## 5. Implementation and remaining scope

Scalus 1.4.0 evaluates the cryptographic builtins on `libscalus_crypto` of scalus-crypto-jni 0.1.0
(libsodium, libsecp256k1 and portable blst 0.3.15) and passes `hashToGroup`'s tag as bytes, so the
earlier raw-byte DST backport into Scarab's portable libblst is no longer used by Plutus
([ADR 0020](adr/0020-plutus-native-crypto-through-scalus-crypto-jni.md)). Costing and the 255-byte
DST length check are Scalus's own, and the original upstream conformance assertions plus the
independently exported G1/G2 point vectors pass through the production evaluator. The start-up
check of the library computes the `hashToGroup` known answers for a non-ASCII tag. A builtin the
library backs is a typed `BuiltinUnavailable` when it did not load. The complete upstream suite
passes 1,009/1,009 cases with zero exceptions under its own term/counting profile; the obsolete
known-failure resource is removed.

An implementation limit is `Phase2.Outcome.Unavailable` and `LedgerFailure.Unavailable`, regardless
of the transaction validity flag. It supplies no script verdict and no consensus predicate failure.
Unavailable decoder attempts are not retained by the bounded program cache. VM memoisation
shares one lazy construction per key, retaining successful values; a failed attempt removes only
its own cache entry atomically, so recovery remains retryable and cannot evict a newer success.
Known malformed input remains memoised. The node's supervision boundary handles unavailability as
unavailable validation. This remains distinct from
reference-proven setup, scope, budget and runtime script failures.

Whole-transaction context findings REF-01/02/03 have their own recorded reference transactions and
regressions. Four binary-DST validity-tag reference transactions additionally agree at phase2,
LEDGER and BBODY, using the same harness. `BoundaryTransactionsSuite` separately replays complete
V3 boundary submissions, including phase-1 decode refusals that cannot supply a collateral verdict.
The malformed Byron reference transactions are covered by the separate #171 oracle;
this Plutus API matrix does not replace it. IN-74 to IN-77 are decided in
[implementer-notes.md](implementer-notes.md); filing upstream remains the owner's decision.

## Spending-datum admission and arguments (#177)

Context construction preserves the reference's optional datum. An absent datum witness becomes
two V1/V2 arguments or V3 spending info with `Nothing`, matching `getAlonzoSpendingDatum`
(`Alonzo/UTxO.hs:229–240`), `toLegacyPlutusArgs` (`Alonzo/Plutus/TxInfo.hs:184–194`) and
`toPlutusArgs` (`Conway/TxInfo.hs:754–766`) at cardano-ledger `f649f975`.
The phase1 required-hash check remains independent of translation and of the validity flag.

The [retained actual reference corpus](../fixtures/reference/spending-datum/README.md) has18
complete Conway/protocol10 transactions: V1/V2/V3 missing/supplied/no-datum cases with both flags.
All six missing-witness cases are refused with `MissingRequiredDatums`; V1/V2 no-datum cases
are unspendable, while the valid V3 no-datum control enters a block. Four controls are included
with shared-chain confirmation and 14 cases are refused; original signed bytes, raw refusal
CBOR and funding/script/collateral effects are retained. `SpendingDatumSuite` checks standalone
context arguments separately from phase1 and complete LEDGER/BBODY verdicts. Translation-error
payload parity is covered by the context-collection corpus below (#176).

## Context collection and failure payloads (#176)

`CollectError` retains the reference's closed `NoRedeemer`, `NoWitness`, `NoCostModel` and
`BadTranslation` envelope. Translation errors retain input/output sources, the four distinct
Conway governance fields with full payloads, reference-input sets and invalid redeemer pointers.
Voting procedures retain the ballot and optional anchor; ledger governance projects only the ballot.
Original `TxBody.raw` remains the transaction-ID source, and the persisted vote/snapshot format is unchanged.

The collector mirrors `Alonzo/Plutus/Evaluate.hs:141–185` at `f649f975`: visit required scripts in
reference purpose order, prepend successes and missing-redeemer errors, and skip subsequent model/context
preparation after any error. Missing redeemers still prepend after a setup error. Cost-model lookup is
per prepared purpose, including multiple languages; there is no global model preflight. Missing script
witnesses are filtered by the reference resolver and rejected separately by phase1; `NoWitness` is not
reachable through this collector. Every context is prepared before evaluation. Implementation
unavailability remains a separate typed outcome and cannot become a script or predicate failure.

The [reference corpus](../fixtures/reference/context-errors/README.md) records 24 actual signed PV10
submissions (12 included/12 refused), plus 145 unchanged-source API cases including the original 24 and
42 retained Conway transactions. The 79 derived probes are API-only and explicitly do not claim signature
revalidation or ledger admission. All 91 measured collection failures and 14 direct certificate-purpose
constructor encodings agree byte-for-byte; ten actual full context refusals agree with LocalTxSubmission.
Noncanonical pool relay/metadata and proposal parameter-update controls establish typed diagnostic
normalization while preserving original signed bytes and body hashes. Reference lists keep the measured
nonempty-list framing. Small-stack regressions exercise 20,000 errors, distinct pointers and anchored votes.
Broader CEK aggregation/log behavior is separate from this context-collection boundary.

## Original CBOR byte-string chunks

`PlutusData.decode` and `fromNode` enforce the original per-chunk 64-byte bound for byte strings
and tag 2/tag 3 bignum payloads. The joined value may be larger: chunks of 64 and 1 are valid;
a definite 65-byte value or an indefinite string containing one 65-byte chunk is refused.
`ByteStringChunks` examines retained original framing after generic CBOR syntax validation,
so it does not confuse the joined payload size with an individual chunk's length. Construction
of an in-memory `PlutusData.B` remains independent of wire admission, and `DataSerialiser`
writes longer payloads in bounded chunks.

This follows `PlutusCore/Data.hs:232-265` at the pinned Plutus `507cab8c` revision. The
[native CLI corpus](../fixtures/reference/plutus-data-chunks/README.md) retains twelve exact
parser/hash verdicts for bytes and positive/negative bignums. `PlutusDataChunkSuite` reproduces
six incorrect pre-fix admissions, checks the typed refusals and both public decoder entry points,
and preserves all six accepted native hashes. The same original inputs seed the existing
`plutus-data` fuzz target. These are parser/hash observations, not signed transaction verdicts.

Validation on the combined `edb5eef7` main baseline: Docker `check` passes 5,575 tests with
17 optional-data skips and no failures; the affected four-seed campaigns pass 3.6 million inputs
across whole transactions, block preparation, Leios blocks and Plutus Data. The private seven-era
e2e reaches Conway epoch 17 with 1,678 applied blocks and 65 equal ledger comparisons. One Conway
comparison has no stable sampling bracket; none differs. The run records 43 rollbacks and seven
reconnects, with no validation, fatal or CPU-starvation errors. These operational observations
are separate from the twelve exact parser/hash cases above.
