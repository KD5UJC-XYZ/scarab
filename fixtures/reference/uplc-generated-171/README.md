# Generated UPLC programs judged by plutus 1.71.0.0

Status: current; the differential oracle of the own evaluator at plutus 1.71.0.0, protocols 5 to 12
([ADR 0027, amendment of 2026-11-15](../../../docs/adr/0027-own-uplc-evaluator-as-a-correctness-backend.md#amendment-2026-11-15-plutus-17100-and-protocol-12),
issue [#1099](https://github.com/KD5UJC-XYZ/scarab/issues/1099)). It is the
[1.70.0.0 fixture](../uplc-generated/README.md) extended to protocol 12 and judged by the
[1.71.0.0 build](../../../scripts/oracle/plutus/generated-171/README.md) of the same harness.

On 2026-11-16, unchanged Plutus 1.71.0.0 (`261766ad1d582bce8c86bad1af7a3b0e5355b21c`) ran **3,800**
generated scripts through `deserialiseScript` and `evaluateScriptRestricting`: 200 for each of 19
profiles, the 15 of the 1.70.0.0 fixture (V1 at protocols 5 to 11, V2 at 7 to 11, V3 at 9 to 11,
variants A to E) and V1, V2, V3 and V4 at protocol 12 (variants F and G; V4 through plutus's own
PlutusV4 functions), half with the profile's default cost-model list and half with a list drawn from
it. `GeneratedOracle171Suite` replays every one through the own evaluator's ledger entry point
(`ledger.LedgerEval.evaluateSerialised`, the path the node runs) and requires the same admission and
stage, the same class and constructor of the error, the same budget on success and on failure, and
the same log lines. No case is excluded; a script the own evaluator gives no verdict on fails the
suite.

- **Protocols 5 to 11.** With the same corpus seed the generator gives the 3,000 requests of the
  1.70.0.0 fixture, and those profiles take 1.70.0.0's default lists (343 or 361 values; plutus
  1.71.0.0 names 351 or 369, so it fills the eight `keepPolicies` and `dropPolicies` parameters with
  `maxBound`). Plutus 1.71.0.0 gives each of the 3,000 the verdict plutus 1.70.0.0 gave (the suite
  compares the two fixtures' requests, lists and verdicts).
- **Protocol 12.** The four profiles take 1.71.0.0's default lists (D's values for V1 and V2, E's for
  V3 and V4). Of their 800 programs, 393 declare version 1.2.0, 329 version 1.1.0, 68 version 1.0.0
  and 10 version 2.0.0; `case` of a constant may scrutinise a `Data`.

| Stage | All | Protocol 12 | Constructors at protocol 12 |
|---|---:|---:|---|
| `success` | 1,424 | 206 | |
| `evaluation` | 1,396 | 435 | operational: `CekEvaluationFailure` 269, `CekCaseBuiltinError` 62; structural: `MissingCaseBranchMachineError` 45, `UnliftingMachineError` 14, `NonPolymorphicInstantiationMachineError` 7, `NonFunctionalApplicationMachineError` 4, `BuiltinTermArgumentExpectedMachineError` 4, `NonConstrScrutinizedMachineError` 2, `UnexpectedBuiltinTermArgumentMachineError` 1; ledger: `InvalidReturnValue` 27 |
| `budget` | 667 | 113 | `CekOutOfExError` |
| `decode` | 224 | 36 | not admitted |
| `setup` | 73 | 10 | `CodecError` (the program's version) |
| `scope` | 16 | 0 | `DeBruijnError` |

At protocol 12, 492 scripts have logs and 488 take `Data` arguments; 103 succeed under the default
lists and 103 under the drawn ones.

## The programs

The generator is the 1.70.0.0 fixture's (`TermGen`, generator version 1), whose programs for the
profiles of protocols 5 to 11 are unchanged. At protocol 12 it also writes program version 1.2.0
(the most likely version there), gives a V4 program one argument, as a V3 program, and lets
`case` of a constant scrutinise a `Data` with branches that take a `Constr`'s fields as a list.

## Files

As in the [1.70.0.0 fixture](../uplc-generated/README.md#files): `corpus.json` (with the release),
`models.jsonl` (two lists per profile, in profile order), `requests.jsonl`, `verdicts.jsonl`,
`provenance.json` and `manifest.json`, whose `sourceCommit` is the commit of the generator and whose
`oracleImage` and `oracleBaseImage` are the ids of `scarab-plutus171-generated-oracle:local` and
`scarab-plutus171-oracle:local`. The raw answers of the oracle are not published; the commands of
the [harness README](../../../scripts/oracle/plutus/generated-171/README.md) write the requests and
the verdicts again.

## A larger run (not retained)

On 2026-11-16 a corpus of 19,000 programs (1,000 per profile, generator version 1, corpus seed 3001 with
two lists per profile: arguments `3001 1000 2 1.71.0.0`) went through the same oracle and
`GeneratedOracleReplayMain` (the same entry point) with no disagreement in stage, error, budget (on
success and on failure) or logs: 7,625 successes and 2,569 out of budget in all, and at protocol 12
4,000 programs with 1,117 successes. Its requests and verdicts are not published; those arguments write
them again.

## What it does not claim

The fixture covers what the generator writes, with the defaults and the lists drawn from them; it is
not a proof for every program or every cost model. Its protocol 12 is the Leios testnet's plutus,
which the node does not reach (`MaxMajorProtVer` 11).
