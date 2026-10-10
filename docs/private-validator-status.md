# Private validator status — 2026-10-10

The Scala node is a bounded research implementation, not a complete private
Cardano validator. The latest published milestone is a successful isolated
Conway PV9 Plutus V3 spend through Scala HTTP admission, its volatile pool,
TxSubmission2 relay, original-byte follower inclusion, and reference endpoint
comparison. [Integration CI passed](https://github.com/ottobot-ai/cardano-scala-lab/actions/runs/38011863015)
at `02b8e7c24feff9b5b2c13840bc544e2709df7919`.

## Capabilities and remaining gaps

| Area | Demonstrated capability | Remaining boundary |
| --- | --- | --- |
| Running the software | Main CLI supports bounded codec, acquisition, replay and private-node research commands. | Submission acceptance entrypoints remain under `app/src/test`; no supported long-running submission CLI/API service. |
| Submission | Isolated ADA-vkey, native signature-script and one registered V3 Plutus spending profile use Scala admission/pool/relay. | Pool is volatile. Public API lifecycle, durable statuses, restart/rollback recovery and broader profile combinations are incomplete. |
| Networking | Live local ChainSync/BlockFetch original-byte acquisition and TxSubmission2 inclusion are observed. | Latest ingress acceptance explicitly sets reference initialization delay to zero. A separate relay-only default-delay observation does not establish full ingress interoperability under default settings. |
| Plutus | Exact original identity, signatures, bounded context/integrity, registered cost model, fees/budgets, datum-aware state and collateral reservation compose for the checked profile. | One registered V3 script, one inline script input and successful phase two only. General scripts, datum-hash/reference features, other purposes, invalid phase-two collateral consumption/return and broader languages remain unsupported. |
| Ledger/epochs | Restricted UTxO/fee/stake comparisons, bounded native boundary evidence and separate synthetic reward/governance components exist. | Plutus acceptance is same-epoch. Component differentials and native boundary observations do not establish general runtime epoch transitions or full ledger equivalence. |
| Persistence/forks | Separate bounded durable replay, same-epoch handoff/rollback and sustained recovery cases passed. | They do not establish durable recovery of the complete datum/stake/epoch/Plutus admission tuple or pool. Stake-bearing checkpoint formats retain explicit refusals. |
| Consensus | Scoped original-byte commitments, signatures, supplied-context eligibility, nonce observations and atomic branch research exist. | Independent complete chain selection, authenticated evolving consensus/ledger state and block production are not established. |

The [Plutus receipt](../reference/plutus-admission/live-receipt.json) records funded
bootstrap slot 292 and inclusion slot 616, exact body/witness inclusion, complete
supported UTxO/stake agreement, preserved collateral and a 300,000-lovelace fee
increase. Funding snapshots were separate queries bracketed under observed
quiescence. The endpoint was acquired at the requested historical full point
from the running node; no atomic cross-query snapshot is claimed. Full ledger,
governance and consensus validation are not claimed. The pre-funding slot-100 and bootstrap slot-300 timing windows are
strict; the successful bootstrap had only eight slots of margin. Failed attempts
and the original controller/source hashes remain preserved.

Local validation included 1,701 Scala tests, 25 public gates, 418 Python tests
run with two skips, and 28 separately mounted retained-evidence tests. These are
dated run counts, not a sum of unique tests or a claim that private data ships in
the public repository. Subsequent timing guard tests are a separate follow-up.

## Next phases and acceptance gates

1. **Make the existing profile operable.** Promote a reviewed service entrypoint
   into Main with explicit configuration, bounded resources, structured status,
   graceful shutdown and documented profile selection. Require packaged-CLI
   acceptance through HTTP, relay, inclusion and exact endpoint comparison.
2. **Establish default-delay interoperability.** Keep real protocol startup
   timing, use genesis-derived budgets, and exercise delayed readiness,
   cancellation, reconnect and exhausted windows. Require the complete ingress
   path under the default reference setting; do not infer it from relay-only tests.
3. **Expand ledger/Plutus semantics one versioned scope at a time.** Add paired
   positive/negative reference cases for budget/context/integrity boundaries,
   phase-two-invalid collateral behavior, total collateral/return, then datum-hash
   and reference features and additional script purposes. Each addition needs
   original-byte evidence and exact UTxO/fee/collateral outcomes.
4. **Unify epoch state and durable recovery.** Admit checked native epoch inputs,
   compose reward/stake/governance changes with the ledger tuple, and version
   checkpoints for the complete supported state. Require cross-boundary replay,
   rollback/reapply, crash/restart and stale-receipt rejection against independent
   endpoints before enabling runtime paths currently rejected.
5. **Close validator/consensus gaps.** Derive evolving eligibility from checked
   history, implement complete supported chain selection and remaining ledger
   rules, then consider block production as a separate milestone. Require fork,
   adversarial-input and sustained recovery evidence before a validator claim.

All live gates remain isolated local Docker tests with disposable keys and no
real funds or public-network submissions. Private keys, captures, logs and
cluster state stay outside Git.
