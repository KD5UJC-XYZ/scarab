# Complete Plutus boundary transactions

The generator and replay tooling extend [#166](https://github.com/KD5UJC-XYZ/scarab/issues/166),
[#167](https://github.com/KD5UJC-XYZ/scarab/issues/167) and
[#175](https://github.com/KD5UJC-XYZ/scarab/issues/175) beyond the standalone API evidence.
Actual reference submissions belong in `transactions/`, separately from the context-only fixtures:
decoder-refused scripts must fail phase 1 for either validity flag.

The private reference runs on 2026-10-18 query the actual protocol version before funding. The
original sixteen probes produce 32 submissions per version: protocol 10 includes 15 and refuses 17;
protocol 11 includes 13 and refuses 19. Supplemental depth-two controls add four submissions per version: two invalid-tag transactions
include and spend collateral; two valid-tag transactions refuse and spend neither input. These
arrays preserve the original runs unchanged. Together the 72 submissions include 32 and refuse 40. Every case checks both funding and collateral inputs.
Protocol 11 was set in a fresh genesis before the first node startup and confirmed by the running
reference's protocol-parameters query; it is not a relabelled protocol-10 fixture.
`provenance.json` records node/CLI versions, the reference image and configuration/genesis hashes.

`scripts.jsonl` preserves eighteen V3 scripts with an ignored outer context lambda, their unwrapped
source bytes and every full variable index as a decimal string. `BoundaryTransactionScripts` first
checks the fourteen canonical source ASTs byte for byte against the original boundary corpus. Two
constructor-scrutinee case controls distinguish an unused wide branch from an evaluated one at both
protocols. The original integer-scrutinee case is also retained: it fails evaluation at protocol 10
and succeeds at protocol 11, independently of its unused branch.
Two additional controls evaluate a wide index under two binders, where narrowing it to index 2
would bind a value and let the ignored result return unit. The reference instead fails evaluation.

`api-verdicts.jsonl` contains 36 actual pinned Haskell API observations, executed 2026-10-18 with the
complete crypto image `scarab-plutus175-oracle:local`, image ID
`e061568cd4971962854e85f26910eb5094ff1c416567a547feb3ba6eff59c5da`.
Source/dependency pins are in [the oracle README](../../../scripts/oracle/plutus/README.md).
The exact request program applies the ignored context lambda to unit, so deferred runtime failures
execute in the API oracle, whose argument list is empty. The transaction itself receives the ledger's
real context. Golden rows bind the applied program and cost model by SHA-256. These API rows are
script admission/evaluation evidence; they do not claim complete-node transaction admission.

Export in the Docker JDK container:

```sh
bash scripts/sbtw 'plutus/Test/runMain xyz.kd5ujc.scarab.plutus.BoundaryTransactionScripts /oracle/scripts.jsonl /oracle/requests.jsonl'
```

Run the existing pinned oracle image offline on `requests.jsonl`. For complete submissions, use an
already running, uniquely named private cluster at the required **observed** protocol version:

```sh
python3 scripts/oracle/plutus/boundary_transactions.py --name NAME --protocol 10 --scripts /tmp/scripts.jsonl --out /tmp/conway-pv10.json
```

The driver checks the queried major version before funding anything, creates independent inputs for
both flags of each probe and records both funding and collateral disposition. Accepted valid
transactions spend funding only; accepted invalid transactions spend collateral only; refusals spend
neither. Accepted cases wait for the ledger UTxO change, proving inclusion. No signing key is retained.
Before supplemental submissions, setup funding must remain present while all three reference nodes
agree on a tip and then advance by `k+1` blocks; observing split tips or missing funding resets the
window. Only an unobserved setup transaction may be resubmitted, with identical signed bytes and its
original input still unspent, at most three times within 180 seconds. Script submissions are never
retried or accepted under a different predicate family.

Two failed setup attempts are excluded from semantic coverage: the first protocol-10 main run did
not observe setup within its original 90-second bound; the first protocol-10 supplement observed
setup, then a reference rollback removed it before the first case. That case refused spent inputs,
which is not a script verdict. The subsequent fresh runs retain successful cases independently;
private archives retain the failed attempts and diagnostics. The successful protocol-11 supplement
also refused one duplicate setup resubmission while it was pending; only the later shared UTxO
observation and `k+1` blocks establish its setup confirmation.
`BoundaryTransactionsSuite` checks original witness bytes, phase-1 admission, the applied evaluation
stage, LEDGER, BBODY and both input roles. Remaining reference coverage is tracked in #175; the
observed versions and actual inclusion results must accompany any retained transaction arrays.
