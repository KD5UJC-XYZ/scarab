# Documentation credibility audit — 2026-10-10

## Revision and method

This audit is pinned to published base
`c1e3dc99da88baf676c153045da78119b03f5f64`. It changes documentation only.
Later sustained-service work is outside this baseline; do not interpret this
report as a status assessment or rejection of a later revision. Main had advanced
to `af8c589df14dee0342383f13abd3a8cf349bae15` when the audit worktree was created.

Inspected the README, public verification instructions, current validator status,
ADA/native acceptance descriptions, Plutus admission/CLI and ingress roadmap;
cross-checked the committed workflow, sbt aliases/runtime classpath task, Main
command routing, Plutus command/evidence source, published receipts and selected
retained offline logs. No build, test suite, live cluster or hosted CI was run.
External hosted-run links remain historical attributions, not freshly verified
remote statuses. No keys or raw private cluster state were accessed or copied.

## Capability and evidence matrix

| Profile or surface | Evidence at this base | Boundary |
| --- | --- | --- |
| [ADA vkey ingress](ada-submission-implementation.md#acceptance-record-2026-10-09) | Recorded Test-only harness acceptance: HTTP, Scala relay, original body/witness inclusion at slot 211; endpoint slot 1020 after epoch 1 crossing. | Specific restricted bootstrap/endpoint case; volatile pool, no general validator or durable pool recovery. Private receipt was not re-audited here. |
| [Native signature-script ingress](native-submission-implementation.md#recorded-isolated-acceptance) | Recorded Test-only harness acceptance: inclusion slot 459, endpoint slot 1004 after epoch 1 crossing. | One live signature-script positive; broader offline predicate coverage is not live negative oracle agreement. Private receipt was not re-audited here. |
| [Plutus Test-only milestone](../reference/plutus-admission/live-receipt.json) | Earlier same-epoch successful spend, bootstrap 292/inclusion 616. | Does not establish the later Compile-only CLI or standalone evaluation instrumentation. |
| [Plutus Compile-only CLI](plutus-research-cli.md) | [Published live receipt](../reference/plutus-admission/cli-live-receipt.json), source `35ea26e27c045317d479558621d2789f523fbac9`; bootstrap/admission/inclusion 201/423/492. | Epoch zero, testnet ID zero, fixed registered V3 successful spend, external diagnostic exchange, zero reference initialization delay; bounded operation, volatile pool. |
| [Checked evaluation receipt](../reference/plutus-admission/cli-evaluation-receipt.json) | Actual checked evaluation, declared memory/steps 100000/30000000, consumed 47600/19269788. Published file hash matches the live receipt. | Hash-bound recorded execution, not independent evaluator replay; context originals stay private. File sink is not a durable checkpoint. |
| [Replay/fork persistence](private-cluster-fork.md) and [sustained v2](adaptive-sustained-acceptance.md) | Separate bounded same-epoch handoff, rollback and continuation cases. | Not recovery of the complete Plutus/datum/stake/epoch/pool tuple. Stake-bearing checkpoint refusals remain. |
| [Synthetic epoch path](synthetic-successor-block.md) and [native differentials](synthetic-boundary-differential.md) | Finite component and supplied/synthetic-state comparisons. | Do not generalize ADA/native endpoint crossings into arbitrary runtime epoch admission, native reward parity or durable epoch recovery. |

All ingress acceptance above uses isolated disposable clusters. Default-delay
end-to-end ingress interoperability remains a separate gate. Full ledger and
consensus validation, independent complete chain selection and block production
are not established.

The [Plutus admission contract](plutus-admission.md) explicitly excludes arbitrary
scripts, additional purposes, datum-hash lookup, reference inputs/scripts, minting,
multiasset, certificates, withdrawals, governance transactions, collateral return
and phase-two-invalid collateral transitions. Generalizing the successful case
requires separately versioned semantics and paired reference evidence.

## Reproducible developer commands

Run the complete [public verification sequence](public-profile.md#public-ci-workflow)
from the repository root. It matches the workflow at this base: aggregate `check`
and runtime classpath, separate `translator/test`, 26 public gates, then four
Python discovery commands. `check` alone omits the translator. Private-corpus and
retained-reference suites require separately supplied data; absent opt-in evidence
must not be counted as reference agreement. Cold dependency resolution needs the
network; the Python guard commands do not launch live clusters.

The Compile-only CLI build/help contract is:

```sh
bash scripts/sbtw app/runtimeClasspathFile app/packageBin
java -cp "$(cat app/target/runtime-classpath.txt)" lab.Main plutus-research --help
```

`runtimeClasspathFile` uses `Runtime / fullClasspath`; it excludes Test classes.
The application jar alone is not a standalone fat jar. Real operation requires
all six explicit option pairs and the checked bundle/exchange described in the
[CLI contract](plutus-research-cli.md#invocation-and-external-contract). A help
invocation or packaging success does not establish live acceptance. These commands
were checked against source and recorded evidence, not rerun by this audit.

## Verification receipts examined

The following existing local logs were read and their SHA-256 values recomputed.
Paths identify private retained evidence, not files shipped in the public export.

| Retained log | Result | SHA-256 |
| --- | --- | --- |
| `/home/<user>/cardano-plutus-cli-phase-20261010-second/tests.log` | 1716 Scala/translator tests across nine module totals; result exit 0 | `be1f78767fec7a539d8221314db4c4240e82deca345b3db8d23b49dfed716b87` |
| `/home/<user>/cardano-plutus-cli-retained-20261010-second/tests.log` | 17 retained tests; result exit 0 | `2ffea35db9df16ca2a3de103e4967533b1551642d64d08e8c9f0b55fb20c9be7` |
| `/home/<user>/cardano-plutus-blockzero-final-guards-20261010-second/tests.log` | 386 + 28 + 14 + 14 = 442 Python tests, two skips; result exit 0 | `ceb9a3fb21bd41f3e14d2f8d702415b31bf067a9743499a423dd3e85b5e89136` |

The gate list contains 26 entries. The earlier recorded 26-gate pass is retained
as an attributed milestone claim in the CLI document; the three logs above do
not independently substantiate that gate run. No new aggregate or hosted pass is
claimed. Formatting and historical follow-up runs must remain tied to their own
source revisions, rather than presented as one fresh uninterrupted result.

Published evaluation receipt SHA-256:
`f30c3280e34a0fe6d7392a59b03a5185bf1f32415da8f01f177af9bcb8b06b1f`.
This was recomputed from the public file and agrees with `evaluationReceiptSHA256`
in the live receipt. This integrity check does not reproduce evaluation or audit
the private original context.

## Corrections and remaining debt

Corrected the README's blanket no-live-peer, Test-only-ingress and no-scoped-rollback
statements; updated public commands/count and removed proposed-workflow wording;
marked release totals historical; distinguished earlier Test-only and later CLI
Plutus receipts; replaced the native document's future-only Plutus wording; made
the roadmap script list explicitly prospective; documented the evaluation sink's
lack of directory fsync and crash-recovery guarantees.

Remaining work:

- The README mixes a current overview with a long versioned chronicle. Move the
  chronicle to a changelog in a future structural edit and retain dated links.
- Many older docs correctly describe a local milestone but use present-tense
  words such as “current”, “next” and “unsupported”. Add an explicit source/profile
  header when those documents are next revised; do not silently broaden old claims.
- Keep one capability matrix authoritative and link specialized profiles to it.
  Separate public synthetic tests, private retained tests, live positives, negative
  reference agreement and hosted CI in future evidence indexes.
- Add a machine-readable revision/command/result evidence index if recurring
  manual count drift becomes burdensome. A gate list length is not a gate pass.
- New sustained service and storage work must bring its own command, lifecycle,
  resource, shutdown/recovery and acceptance documentation at integration. This
  baseline audit neither validates nor blocks those later changes.
- Epoch admission, complete checkpoint state, default-delay ingress, broader
  Plutus semantics and independent consensus remain architectural work, not
  documentation omissions that prose can resolve.

All 127 repository-relative link targets and Markdown heading fragments in the
changed documentation were checked locally, and `git diff --check` passed. External URLs and private-corpus example paths were not
used as new execution evidence.
