# Offline PV9 synthetic spending profile

This is a bounded local experiment, not reference conformance, a transaction validator,
or an exporter. It admits one authored, hash-pinned UPLC 1.0 script and constructs
synthetic V3 ScriptContext Data from bounded typed inputs. The existing 18-vector
PV11/E reference packet and its command remain unchanged.

| Capability | Status |
| --- | --- |
| Scalus 1.3.0 UPLC evaluation | Explicit V3 / PV9 / semantics C |
| Costs | Captured 251-entry PV9 model, exact signed Int64 parsing |
| Script | One closed textual UPLC artifact; arbitrary scripts and Flat rejected |
| Context | One spending input and ADA-only payment, bounded synthetic fields |
| Success | Must return Unit |
| Reference PV9 CPU/memory goldens | Pending; current numbers are local observations |
| Full ledger checks, balancing, signing, submission | Unsupported |
| Crypto/native provider | Existing narrow JVM BLAKE2b-256 provider; no changes |
| Live node, global transaction/script hash helpers | Not used |

## Admission and cost evidence

Both model and script bytes are SHA-256 checked before parsing. The model has exactly
251 decimal integer entries; decimals, exponents and signed overflow are rejected.
The frozen script uses 13 non-cryptographic builtins. Their availability is checked
against Scalus's PV9 builtin introduction table separately from cost coverage.
All 49 CEK/builtin cost coefficients are explicitly indexed and must be present
and not Long.MaxValue. Scalus padding absent later parameters is not evidence of
compatibility.

Pinned Haskell Plutus 1.70 declares **361** parameter names; Scalus declares 350.
The first 251 positions were cross-checked. Seven divideInteger names at indices
50–56 omit Haskell's model-arguments segment in Scalus; positions still agree.
The scope does not include the unpopulated suffix. See the provenance appendix for
exact source pins and artifact hashes.

## Script and synthetic context

The script verifies Spending purpose, a single input and output, matching purpose
and consumed-input reference, matching inline and purpose datum, matching payment
beneficiary, redeemer integer 7, and payment at least the datum's minimum. It
returns Unit or explicit error. The datum is Constr 0 [beneficiary, minimum].
The builder uses V3's 16-field TxInfo and single ScriptContext argument, V2 TxOut,
inline OutputDatum constructor 2, Some constructor 0 and None constructor 1.

Synthetic transaction IDs, script credentials and beneficiaries are explicit byte
markers. They are not hashes of serialized transactions/scripts and must not be
exported as real identities. Transaction.id, Script.scriptHash, implicit providers,
SlotConfig and network defaults are not called. The synthetic fee is 5,000,000
minus payout; this is not evidence of ledger validity. The script relies on the
builder's finite shape and does not validate arbitrary ScriptContext data.

## Reproduction and observations

On 2026-10-09, offline Docker tests passed: 40 VM tests plus 5 VmCommandSuite tests.
The default context returned Unit with **CPU 19,269,788 / memory 47,600**.
These are Scalus local observations, not Haskell reference expectations.
Tests derive exact-limit and CPU-minus-one/memory-minus-one checks from the
observed result, so they establish deterministic local charging and enforcement,
not cross-implementation agreement.

The PV9 profile has a separate explicit ceiling of CPU 30,000,000 / memory 100,000.
The original 10,000,000 CPU fixture ceiling is unchanged. Eight mutations cover
purpose, input ID/index, datum minimum/beneficiary, payout beneficiary, insufficient
payout and redeemer. Matching changed identities and exact minimum payment pass.
Tests also cover integer precision beyond binary64, all used cost slots, model
and script byte mutation, later builtin availability, and input bounds.

Use the repository's existing sbt runner for:
```text
scalafmtAll
vm/test
app/testOnly lab.VmCommandSuite
```
Execution used image cardano-public-v023-check:local, --network=none, --cpus=2,
--memory=2g, --memory-swap=2g, JVM ActiveProcessorCount=2 / Xmx1200m, and the
worktree-private local-evidence/cache. Raw logs remain in
local-evidence/pv9-tests.log. No dependency, launcher, provider policy, main
checkout or public remote was changed.

## Remaining boundary

An independently captured reference evaluation of this exact script and Data is
needed before claiming PV9 result/budget conformance. Context serialization and
ledger-context construction parity also need reference goldens. Full transaction
validation remains a different workstream. No live resource slot is assumed.

## Provenance appendix

```json
{
  "modelHash": "6ab455d588e186649a6aae2761fec85ae5a2647cb002a2acb737604f698b21a2",
  "scriptHash": "1129132bf56b79e2492e88a096d816d695e28131b29ff7218d2362078146062e",
  "builtins": [
    "equalsData",
    "equalsInteger",
    "fstPair",
    "headList",
    "ifThenElse",
    "lessThanEqualsInteger",
    "nullList",
    "sndPair",
    "tailList",
    "unConstrData",
    "unIData",
    "unListData",
    "unMapData"
  ],
  "required": [
    [
      17,
      "cekApplyCost-exBudgetCPU"
    ],
    [
      18,
      "cekApplyCost-exBudgetMemory"
    ],
    [
      19,
      "cekBuiltinCost-exBudgetCPU"
    ],
    [
      20,
      "cekBuiltinCost-exBudgetMemory"
    ],
    [
      21,
      "cekConstCost-exBudgetCPU"
    ],
    [
      22,
      "cekConstCost-exBudgetMemory"
    ],
    [
      23,
      "cekDelayCost-exBudgetCPU"
    ],
    [
      24,
      "cekDelayCost-exBudgetMemory"
    ],
    [
      25,
      "cekForceCost-exBudgetCPU"
    ],
    [
      26,
      "cekForceCost-exBudgetMemory"
    ],
    [
      27,
      "cekLamCost-exBudgetCPU"
    ],
    [
      28,
      "cekLamCost-exBudgetMemory"
    ],
    [
      29,
      "cekStartupCost-exBudgetCPU"
    ],
    [
      30,
      "cekStartupCost-exBudgetMemory"
    ],
    [
      31,
      "cekVarCost-exBudgetCPU"
    ],
    [
      32,
      "cekVarCost-exBudgetMemory"
    ],
    [
      68,
      "equalsData-cpu-arguments-intercept"
    ],
    [
      69,
      "equalsData-cpu-arguments-slope"
    ],
    [
      70,
      "equalsData-memory-arguments"
    ],
    [
      71,
      "equalsInteger-cpu-arguments-intercept"
    ],
    [
      72,
      "equalsInteger-cpu-arguments-slope"
    ],
    [
      73,
      "equalsInteger-memory-arguments"
    ],
    [
      78,
      "fstPair-cpu-arguments"
    ],
    [
      79,
      "fstPair-memory-arguments"
    ],
    [
      80,
      "headList-cpu-arguments"
    ],
    [
      81,
      "headList-memory-arguments"
    ],
    [
      84,
      "ifThenElse-cpu-arguments"
    ],
    [
      85,
      "ifThenElse-memory-arguments"
    ],
    [
      96,
      "lessThanEqualsInteger-cpu-arguments-intercept"
    ],
    [
      97,
      "lessThanEqualsInteger-cpu-arguments-slope"
    ],
    [
      98,
      "lessThanEqualsInteger-memory-arguments"
    ],
    [
      128,
      "nullList-cpu-arguments"
    ],
    [
      129,
      "nullList-memory-arguments"
    ],
    [
      165,
      "sndPair-cpu-arguments"
    ],
    [
      166,
      "sndPair-memory-arguments"
    ],
    [
      171,
      "tailList-cpu-arguments"
    ],
    [
      172,
      "tailList-memory-arguments"
    ],
    [
      177,
      "unConstrData-cpu-arguments"
    ],
    [
      178,
      "unConstrData-memory-arguments"
    ],
    [
      179,
      "unIData-cpu-arguments"
    ],
    [
      180,
      "unIData-memory-arguments"
    ],
    [
      181,
      "unListData-cpu-arguments"
    ],
    [
      182,
      "unListData-memory-arguments"
    ],
    [
      183,
      "unMapData-cpu-arguments"
    ],
    [
      184,
      "unMapData-memory-arguments"
    ],
    [
      193,
      "cekConstrCost-exBudgetCPU"
    ],
    [
      194,
      "cekConstrCost-exBudgetMemory"
    ],
    [
      195,
      "cekCaseCost-exBudgetCPU"
    ],
    [
      196,
      "cekCaseCost-exBudgetMemory"
    ]
  ],
  "profile": "synthetic-conway-pv9-v3-c-spend-v1",
  "classification": "authored script plus private synthetic-cluster protocol parameters; not public-chain corpus",
  "capture": {
    "path": "/home/<user>/cardano-reference-capture2-20261008/protocol-parameters.md",
    "sha256": "4da86f5b8de5c3f0911eefb2ccc20bf84ef106ac31b6fbdf2a5e3eba76771e6e",
    "extraction": "fenced JSON costModels.PlutusV3; preserve positional order; JSON array indent=2 plus newline",
    "count": 251
  },
  "sources": {
    "scalus": "https://github.com/scalus3/scalus/tree/31531c14d4e556fb38c984d702ee60dd82b6453f",
    "plutus": "https://github.com/IntersectMBO/plutus/tree/83fb488e05046a3698d0a80013c5fa76be2c0cf2",
    "ledger": "https://github.com/IntersectMBO/cardano-ledger/tree/f649f9751074d2ab3de033fc3912f29c9862c1f5",
    "node": "https://github.com/IntersectMBO/cardano-node/tree/938cba990357ae7c4b7f95c8f75dd9d31174bbeb"
  },
  "scriptProvenance": "Original authored synthetic UPLC; Apache-2.0 repository license; no upstream golden",
  "referenceParameterCount": 361,
  "referenceBudgets": null,
  "localObservation": {
    "cpu": 19269788,
    "memory": 47600,
    "result": "Unit"
  },
  "dockerImage": "sha256:ce5dd881ba207fb485aaebd9bb065ac79a808f26ff52467eca938dd064994203"
}
```

## Independent review

Read-only review found no blocking issues in this finite input surface. It compared
the retained capture and all 49 cost positions with pinned
[Scalus parameters](https://github.com/scalus3/scalus/blob/31531c14d4e556fb38c984d702ee60dd82b6453f/scalus-core/shared/src/main/scala/scalus/uplc/PlutusParams.scala),
[Plutus ordering](https://github.com/IntersectMBO/plutus/blob/83fb488e05046a3698d0a80013c5fa76be2c0cf2/plutus-ledger-api/src/PlutusLedgerApi/V3/ParamName.hs),
and context fields with [V3 Contexts](https://github.com/IntersectMBO/plutus/blob/83fb488e05046a3698d0a80013c5fa76be2c0cf2/plutus-ledger-api/src/PlutusLedgerApi/V3/Contexts.hs).
The reviewer confirmed the ADA-entry extraction limitation. Review did not run
reference evaluations and does not turn local observations into goldens.
