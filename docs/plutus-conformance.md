# Plutus conformance: executed reference boundaries

Triage of [#155](https://github.com/KD5UJC-XYZ/scarab/issues/155) (AUD-03). The question for each
case that [`PlutusConformanceSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/PlutusConformanceSuite.scala)
lists as a known failure of Scalus 1.3.0 is whether a transaction on a network can reach it under the
ledger's own checks, and what the difference would then change. Trigger details are not written here
(see [SECURITY.md](../SECURITY.md)).

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

## 3. The 22 cases

Counts: **9 are reachable** by a transaction (6 `value` constants, 3 `hashToGroup`), **2 are not a
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
through phase2, LEDGER and BBODY; transaction-context evidence remains recorded separately.

| Case(s) | Class | Scalus 1.3.0 against the reference | Protocols where it exists | Reachable from a transaction? | Effect | Handling |
|---|---|---|---|---|---|---|
| `parser/value/` `currencyIDs-unordered`, `duplicate-currencyIDs`, `duplicate-tokenIDs`, `empty-tokens`, `tokenIDs-unordered`, `zero-asset` (6) | (ii), now (iii) in the suite | Scalus rebuilds a `value` constant as a normalised map when it decodes, so the malformed constant is accepted (and changed); the reference fails decoding (`PlutusCore/Value.hs` 637-694) | every protocol and language where a script exists: type tag 13 has no protocol gate, only the header size is checked (`SerialisedScript.hs` 199-215, `Versions.hs` 394-399) | yes: any Plutus script in a witness set or an output, whether or not it runs | decode: the reference makes the transaction invalid in phase 1 (Babbage on) or fails phase 2 (Alonzo); we accepted it | fixed on the ledger path by `FlatScan`, which validates the original flat bytes (also a key over 32 bytes and a quantity beyond 127 bits); tests `PlutusReachabilitySuite`, `PreConwayRulesSuite`; the suite now uses original Flat sidecars and removes these six exceptions |
| `semantics/bls12_381_G1_hashToGroup/hash-dst-len-255`, `..._G2_...`, `bls12_381-cardano-crypto-tests/signature/large-dst` (3) | (ii) | the domain separation tag reaches blst as a Latin-1 `String`; a tag with a byte of 0x80 or more (or 0) yields another point (the cases' tags are such bytes: the length 255 is not the cause) | batch 4 builtin: V3 from 9, V1 and V2 from 11 | yes, by a script that hashes with a tag it builds or is given; ASCII tags are right | result: a different point, so a different verdict if the script compares it | fixed on Linux/amd64 by the pinned upstream byte-array JNI backport into portable blst; the three exceptions are removed; other platforms retain explicit unavailable evaluation |
| `term/constant-case/data/data-01`, `data-02` (2) | (ii), now (iii) | Scalus refuses `case` on `Data` below protocol 12 and applies the fields from 12, as the reference ledger does; the suite's executable uses `caseBuiltin` with `Data` | `Data` case from 12 only | no: the reference ledger uses `caseBuiltinNoData` at 11 and refuses too | none at protocols 5 to 11 | none; regression test of the protocol boundaries |
| `parser/bls12-381/{G1,G2}/{zero,on-curve-bit3-clear,on-curve-bit3-set}` (6) | (iii) | the suite round-trips a BLS12-381 constant through flat; flat has no such constant, and Scalus's encoder writes an application of `uncompress`; a flat byte string that does carry the constant tag is refused by the reference (`Flat Element` instances) and by Scalus alike | batch 4 | no: the decode failure is the same | none | none; regression test |
| `semantics/dropList/dropList-09`, `-10`, `-14`, `-15`, `-16` (5) | (iii) | the suite counts the budget with saturating arithmetic (the maximum); in restricting mode both run out of budget; only the units reported after the failure wrap negative in Scalus; the cost never wraps to a small value | batch 6: V3, V1, V2 at 11 | the script fails in both; phase 2 failure ignores the units spent | none on the verdict; the reported units on a failure | none; regression test over counts that would wrap in 64-bit arithmetic |

## 4. Admission rules outside the allowlist

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

The portable native artifact backports the released dependency's upstream raw-byte DST fix from
Scalus commit `c132a2c8615db9cd27b464b7863863cd576d23be`; see
[`blst-native/native/README.md`](../blst-native/native/README.md). Scalus 1.3.0 remains the latest
Maven release checked on 2026-10-18, and this fix is unreleased. No unpublished Scalus dependency
is adopted. The adapter delegates hashing and compression to the same pinned portable blst0.3.15,
retains costing and the DST length check, and passes original upstream conformance assertions plus
independently exported G1/G2 point vectors. Library startup checks both new JNI entries against
reference known answers. The suite now passes996/1009 cases, with13 documented harness exceptions.

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
LEDGER and BBODY, using the same harness. The malformed Byron reference transaction gap in #171 belongs to that separate oracle;
this Plutus API matrix does not claim to resolve it. IN-74 to IN-77 are decided in
[implementer-notes.md](implementer-notes.md); filing upstream remains the owner's decision.
