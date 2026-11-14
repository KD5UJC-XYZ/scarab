# Generated UPLC programs judged by the pinned evaluator

Status: current; the differential oracle of the own evaluator's generated terms
([ADR 0027](../../../docs/adr/0027-own-uplc-evaluator-as-a-correctness-backend.md), issue
[#43](https://github.com/KD5UJC-XYZ/scarab/issues/43)).

On 2026-11-12, unchanged Plutus 1.70.0.0 (`507cab8cf6192e5143f12be5b2d3005a2928dc6d`) ran
**3,000** generated scripts through `deserialiseScript` and `evaluateScriptRestricting`: 200 for each
of the 15 legal ledger profiles (V1 at protocols 5 to 11, V2 at 7 to 11, V3 at 9 to 11, semantics
variants A to E), half with the profile's default cost-model list and half with a list drawn from it
(each builtin parameter kept, scaled, shifted, zeroed, made large or negative, each machine parameter
kept within a factor of two or so), so that parameters the defaults keep in proportion are told
apart. `GeneratedOracleSuite` replays every one through the own evaluator's ledger entry point,
the path the node runs (`ledger.LedgerEval.evaluateSerialised` with the `LedgerContext` of the
request's cost-model list, the request's budget, verbose), and requires the same admission and stage, the same class and
constructor of the error, the same budget on success **and on failure**, and the same log lines. No
case is excluded; a script the own evaluator gives no verdict on fails the suite.

| Stage | Scripts | Constructors |
|---|---:|---|
| `success` | 1,218 | |
| `evaluation` | 961 | operational: `CekEvaluationFailure` 596, `CekCaseBuiltinError` 67; structural: `UnliftingMachineError` 57, `MissingCaseBranchMachineError` 55, `NonPolymorphicInstantiationMachineError` 44, `BuiltinTermArgumentExpectedMachineError` 36, `NonFunctionalApplicationMachineError` 20, `UnexpectedBuiltinTermArgumentMachineError` 11, `NonConstrScrutinizedMachineError` 5, `OpenTermEvaluatedMachineError` 1; ledger: `InvalidReturnValue` 69 |
| `budget` | 554 | `CekOutOfExError` |
| `decode` | 188 | not admitted |
| `setup` | 63 | `CodecError` (the program's version) |
| `scope` | 16 | `DeBruijnError` |

Under the default lists 704 of 1,500 scripts succeed, under the drawn lists 514 (428 run out of
budget). 1,846 scripts take `Data` arguments (4,975 in all); 585 run within a budget small enough to
run out (255 of them do); 1,787 have logs (2,299 lines); 1,515 failures record the budget spent until
they failed. Every builtin of every profile is named by an admitted program in some profile, and all
but at most three of each profile's in that profile (the suite prints which).

## The programs

`TermGen` (`uplc/src/test/.../oracle/`) unfolds each program from a seed with `Schemes.ana`: a goal
(a type, the types in scope, a size) becomes a template of layers whose leaves are goals. Four
programs in five are about one builtin drawn from the profile's, applied to arguments of its
parameter types, three in five of them from the builtin's domain (key and signature lengths, point
encodings, valid UTF-8, the `Data` a destructor expects, indices and widths in range). Around it:
lambdas and applications, `force` and `delay`, partial applications bound to variables, builtins
bound before they are forced, `constr` and `case` (with tags that have no branch), `case` on
constants (refused before protocol 11), recursion through a strict fixed point over a counter of 0 to
12 (one in twenty counts up until the budget stops it), and constants near the bounds the builtins
and the costing care about (integers at the edges of 64 bits and beyond, byte strings around 32 and
64 bytes, `Data` of every shape with chunked byte strings, big integers and chains of up to 150
lists, `Value`s, BLS12-381 points through `uncompress` and `hashToGroup`, since they have no Flat
encoding). Three programs in ten may hold ill-formed terms and `error`; a few have a program version,
a builtin or a `constr` the ledger refuses. The program computes a value and traces whether a
condition on it holds, so a wrong value shows in the logs and the budget; a V3 program returns unit
nine times in ten.

## Files

- `corpus.json`: the corpus seed, the programs and the cost-model lists per profile, and the generator
  version.
- `models.jsonl`: two cost-model lists per profile, in profile order: the default, then the list
  drawn from it by the corpus seed. Program `n` of a profile uses the first when `n` is even.
- `requests.jsonl`: per script, its `id` (`V<language>-<protocol>/<n>`), `model` (index into
  `models.jsonl`), `seed`, `language`, `protocol`, `script` (the bytes the ledger holds, hex),
  `arguments` (CBOR hex), `cpu` and `memory` (the budget: 10^10 and 1.4 * 10^7 four times in five).
- `verdicts.jsonl`: per script, `admitted`, `stage`, `class` and `error` where there is an error,
  `cpu` and `memory` spent whenever the machine ran, and `logs`.
- `provenance.json`: the record of [schema 1](../../README.md#provenance-records): the plutus pin, the
  date, the request count, the generator, the images and the digests of the harness and the files.
- `manifest.json`: the plutus pin, the source commit of the generator, the oracle image and its base
  image, the stage and error counts, and the SHA-256 of every file and of the tool.

The suite checks those hashes, that the generator still writes every list, script, argument and
budget from its seed, and, as a negative control, that with every cost parameter doubled no success
spends the recorded budget.

## Larger runs (not retained)

On 2026-11-12 two corpora of 15,000 programs each (1,000 per profile, generator version 1) went
through the same oracle and `GeneratedOracleReplayMain` (the same entry point) with no disagreement in stage, error, budget
(on success and on failure) or logs: corpus seed 3001 with the default list of each profile
(arguments `3001 1000 1`: 6,958 successes, 1,358 out of budget), and corpus seed 2002 with four lists
per profile, the default and three drawn from it (arguments `2002 1000 4`: 6,206 successes, 2,671 out
of budget). Their requests and verdicts are not published; those arguments write them again.

## Limits

A finite sample: it does not show equality for every program, size or cost-model list. Diagnostic
text (the reference's `detail`) and the cause term
of a failure are not compared; log lines are. The programs are synthetic, not chain scripts.

Reproduce with the [oracle harness](../../../scripts/oracle/plutus/generated/README.md): the requests
are written by `tools/runMain xyz.kd5ujc.scarab.uplc.GeneratedOracleCorpusMain`, the verdicts by
`run.py` in the image `scarab-plutus-generated-oracle:local`. Raw oracle output is not published.
