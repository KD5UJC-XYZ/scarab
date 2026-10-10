# Private validator status — 2026-10-10

The Scala node is a bounded research implementation, not a complete private
Cardano validator. The earlier published milestone was a successful isolated
Conway PV9 Plutus V3 spend through Scala HTTP admission, its volatile pool,
TxSubmission2 relay, original-byte follower inclusion, and reference endpoint
comparison. [Integration CI passed](https://github.com/ottobot-ai/cardano-scala-lab/actions/runs/38011863015)
at `02b8e7c24feff9b5b2c13840bc544e2709df7919`.


The newer [restricted Plutus CLI](plutus-research-cli.md) and standalone checked
evaluation evidence passed isolated acceptance at `35ea26e27c045317d479558621d2789f523fbac9`.
Bootstrap/admission/inclusion were slots 201/423/492. The [new receipt](../reference/plutus-admission/cli-live-receipt.json)
links the exact accepted state pin, context hash and consumed budget to original
transaction identities, separately from the earlier Test-only run below.
The runtime uses Compile artifacts only; the external HTTP test client remains
separate. This is still an explicitly selected, bounded same-epoch diagnostic.

## Capabilities and remaining gaps

| Area | Demonstrated capability | Remaining boundary |
| --- | --- | --- |
| Running the software | Main CLI supports bounded codec, acquisition, replay and private-node research commands. | Explicit Compile-only `plutus-research` CLI and managed loopback API passed isolated acceptance; external diagnostic exchange remains required. A separate bounded `plutus-service` now passed two-transaction acceptance; no durable or multi-epoch service is established. |
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
That earlier live run did not retain a standalone context-hash or consumed-budget VM
receipt, so those exact live values are not independently audited. The later
timing guards are offline-tested changes outside the captured successful run.

The earlier Test-only Plutus milestone validation included 1,701 Scala tests, 25 public gates, 418 Python tests
run with two skips, and 28 separately mounted retained-evidence tests. These are
dated run counts, not a sum of unique tests or a claim that private data ships in
the public repository. Subsequent timing guard tests are a separate follow-up. The later Compile-only
CLI milestone records 1,716 Scala/translator tests, 26 public gates, 442 Python
tests (two skips), and 17 separately mounted retained tests; see its
[verification history](plutus-research-cli.md#isolated-cli-acceptance--2026-10-10).
These milestone totals must not be added together.

## Next phases and acceptance gates

1. **Extend operation beyond the bounded diagnostic.** The explicit Main CLI,
   managed service/API lifecycle and packaged HTTP-to-inclusion acceptance now
   pass for the restricted profile. The [bounded service](plutus-service.md) no longer
   consumes an expected-transaction descriptor and continues after inclusion.
   Typed termination/evidence boundaries, durable recovery and multi-epoch
   operation remain separate acceptance work.
2. **Establish default-delay interoperability.** Keep real protocol startup
   timing, use genesis-derived budgets, and exercise delayed readiness,
   cancellation, reconnect and exhausted windows. Require the complete ingress
   path under the default reference setting; do not infer it from relay-only tests.
3. **Expand ledger/Plutus semantics one versioned scope at a time.** A bounded
   source-bound evaluation receipt is now retained and independently checked
   against transaction/source/state bindings. Independent evaluator comparison
   of the captured context and budget remains separate work. Add paired
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

## Sequential runner and restore prerequisites

The [typed sequential runner](sequential-devnet-runner.md) now has a bounded live acceptance with three completed and three explicitly blocked scenarios. Reviewed ledger/stake image codecs are present, but complete coherent-state restore and repeated-epoch operation remain unsupported. Its ten-minute target is a proposal gated on those semantics and explicit longer-run limits, not a completed soak.


The next prerequisite increment adds [source-bound empty DRep completion](conway-empty-drep-completion.md),
[coherent snapshot component export](coherent-stake-images.md), and an
[offline two-endpoint client boundary](multi-endpoint-client-boundary.md).
All are tested within their stated domains. They do not enable the blocked live
scenarios. The [remaining concrete tests and untested two-service budget](sequential-devnet-runner.md#next-tests-after-the-prerequisite-components)
cover exact likelihood arithmetic, complete fresh-owner restoration and concrete HTTP/topology integration.
