# Public verification profile

This export omits historical/private corpus data whose redistribution is not established. It starts a new public history; full research history and historical run output remain private. The two pinned licensed synthetic Conway vectors and their notices are retained.

At the audited base `c1e3dc99da88baf676c153045da78119b03f5f64`, use the complete CI command sequence below. `check` compiles and runs the eight aggregate public modules sequentially; `translator/test` is separate because the translator is not in that aggregate. The audited base contained 26 public gates. The later service source `af8c589` adds the 27th gate and fifth Python discovery command shown below. Neither claims the original research package's 1,294 tests or 31 gates. Actual public results are recorded separately after execution.

Twelve corpus-dependent suite source files live under `src/privateCorpus/scala` and are not compiled or executed by default `Test`. With a separately and lawfully obtained complete corpus, `bash scripts/sbtw checkPrivateCorpus` opts into them. A missing corpus fails before test execution; these tests are not marked skipped or passed. The six excluded Python gates also fail immediately when corpus directories are unavailable. No public command downloads the private corpus.

Public checks retain licensed synthetic protocol vectors, KeepAliveTcpDescriptorSuite's five self-contained cases, and durable restricted-replay tests. `docs/network-fixtures.json` remains required by the public network gate. Private corpus directories, setup inputs, logs and local execution artifacts are ignored, while licensed fixture extensions are not blanket-ignored.

The historical command scripts elsewhere in docs describe private full-corpus runs and are not the public acceptance command. Use the commands above. Historical Markdown summaries are prior research evidence, not a fresh public-profile claim.

The optional `Dockerfile.public-check` adds Python only inside a local image based on the pinned official JDK 21 image. No host tool installation or image publication is needed. Bound the public build to 4 CPUs, 4 GiB memory and 512 PIDs, with no simultaneous reference cluster. Dependency resolution requires Maven access; test sessions use controlled local endpoints. Keep build/cache output out of the published tree.

## dev-host public verification, 2026-10-08

Passed 711 tests in 50 suites: core 189, VM 13, network 100, network-runtime 52, ledger 96, ledger-runtime 150, fetcher 5, app 106. Zero failures/errors. All production code and public test code compiled. All 25 public serial gates passed. Production source bytes are unchanged from v0.23.0.

All 12 optional private suites compiled separately, but none ran. The sbt opt-in and six private Python gates reject absent/empty corpus with explicit failure. An initial lowercase sbt configuration invocation was rejected; the verified slash spelling is `PrivateCorpus`. An initial directory-only guard was strengthened to reject empty corpus directories too. These corrections did not change public production/test behavior.

Build: pinned official Temurin JDK 21.0.11, sbt 1.10.7, Scala 3.3.8; 4 CPU / 4 GiB / 512 PID container limits. Python gates ran network-disabled in a local-only image derived from that base. Build/dependency resolution preceded offline gate execution. No private cluster or public Cardano peer was contacted. No image was published.

## Public CI workflow

`.github/workflows/public-profile.yml` runs one Ubuntu 24.04 job on pull requests and pushes to main. It pins official checkout/setup-java actions to immutable commits and Temurin to Adoptium selector `21.0.11+10.0.LTS` (JDK 21.0.11+10), then uses the repository's SHA-pinned sbt 1.10.7 wrapper. Permissions are `contents: read`, checkout does not persist credentials, the job has a 20-minute timeout, and newer runs cancel older runs for the same PR/ref. There are no secrets, private-corpus downloads, live reference clusters, artifact publishing or deployment steps. The hosted OS image is an Ubuntu version label, not an immutable machine-image digest.

The current job runs the public Scala check/runtime-classpath task, separate translator tests, 27 public gates, and five Python discovery commands. Guard tests do not launch live Docker workloads. Optional retained-evidence environment variables are not set by the workflow; passing public CI does not establish captured-reference agreement or live acceptance.

```sh
bash scripts/sbtw check app/runtimeClasspathFile
bash scripts/sbtw translator/test
python3 scripts/check-public-gates.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_private_cluster*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_validated_checkpoint_restart.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_native_script_submission_fixture.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_plutus_submission_fixture.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_plutus_service_fixture.py'
```

Public Maven access is needed on a cold hosted runner. The workflow initially uses no dependency cache action. Local verification uses the existing inspected dependency cache with networking disabled in a fresh public source export; that does not establish fresh online resolution. The workflow is committed and has historical hosted results linked below. Local structure checks do not establish hosted success for a later revision; attribute each hosted result to its exact commit.

### Local CI-command acceptance, 2026-10-08

A fresh public export of `99106992d39b3f48c8b5d7c7835260af246e5ff6` plus the proposed workflow passed the exact command sequence: **726 Scala tests**, all **25 public serial gates**, and **21 Python launcher guards**. Module counts: core 189, VM 13, network 100, network-runtime 52, ledger 99, ledger-runtime 150, fetcher 5, app 118. Zero failures/errors. The 26 opt-in captured-reference tests and the private corpus suites were not run or counted. A separate mocked failure at public gate 3 propagated its nonzero result and prevented subsequent gates and the success summary.

The run used the existing inspected Python/JDK test image derived from pinned Temurin 21.0.11+10, with 4 CPUs, 4 GiB memory, 512 PIDs and network disabled. Dependencies came from the existing ignored cache. Generated gate reports/logs stayed in the separate export, and the owned test container was removed. Local evidence remains outside Git at `/home/<user>/cardano-ci-public-check-20261008`. The independently reviewed workflow was published at `c248f4d423b047e344b35a00cb04dba8e9fe1d77`. Its [first hosted run](https://github.com/ottobot-ai/cardano-scala-lab/actions/runs/37839316882) failed during JDK setup because the provider exposes build 10 as `21.0.11+10.0.LTS`, while the original selector was `21.0.11+10`. The selector is corrected locally to the provider's exact version; no tests ran in that failed hosted run. The corrected commit `cec73075948a696cfe76313f80df0138535305f2` passed the [replacement hosted run](https://github.com/ottobot-ai/cardano-scala-lab/actions/runs/37840514425), including the public Scala build/tests, 25 serial gates and launcher guard discovery. The first failed run remains linked above.

## Integrated local profile

The reviewed follower/header/minimum-output integration passed 764 public Scala tests, all 25 public gates and 24 Python guards locally. A separate 28-test retained-data run is not part of public CI. See [the integration checkpoint](integration-20261008.md) for exact revisions, resource bounds and remaining live-evidence limits. This does not update the hosted-green revision reported above.


## Scenario integration acceptance, 2026-10-08

The follower/header/minimum-output revision `db88afbd2b7d86517d8d4f0f5176ca650d3a369a`
passed [hosted CI](https://github.com/ottobot-ai/cardano-scala-lab/actions/runs/37843955860).
The next local scenario integration passed **778 public Scala tests**, all **25
public serial gates**, and **42 Python guards**. Module counts are core 197, VM 13,
network 100, network-runtime 52, ledger 105, ledger-runtime 150, fetcher 5, app 156.
This includes 14 synthetic negative-observation tests, nine of which exercise
complete evidence directories. Optional captured-reference and private-corpus tests
were not enabled or counted. The existing CI Python discovery already includes
`test_private_cluster_scenarios.py`; no workflow modification was needed.

The reviewed scenario chain `1d785a1`, `81208bf`, `34c360c` was cherry-picked as
`7ea4a46`, `216906e`, `2ba824a`. One integration fixture adjustment adds
`utxoCostPerByte: 4310`, required by the current source-bound context loader; the
parameter hash is calculated from the complete fixture. A separate source review
approved the integration and this adjustment before publication.

Local acceptance used a fresh Git source export plus that fixture adjustment,
an independently copied cache, and the existing inspected JDK/Python image
`sha256:ce5dd881ba207fb485aaebd9bb065ac79a808f26ff52467eca938dd064994203`.
Docker limits were 2 CPUs, 2 GiB memory/swap and 512 PIDs, with network disabled,
read-only container root, user 1000, dropped capabilities and no-new-privileges.
Only the exported wrapper was reduced to a 1 GiB heap and two active processors;
the tracked wrapper and hosted workflow are unchanged. Generated reports, cache,
commands and logs remain outside Git in
`/home/<user>/cardano-scenario-integration-20261008`. The owned build container was
removed. These are offline test results, not live negative reference agreement.


### Corrected scenarios and follower follow-up

The reviewed integration `2eda20c098ad2897d770ed471e47e2b9c2b55662` passed
[hosted CI](https://github.com/ottobot-ai/cardano-scala-lab/actions/runs/37845836096).
The source-backed mempool correction then passed **779 public Scala tests**
(app 157; other module counts unchanged), all **25 serial gates**, and 44 Python
guards in the same bounded offline export. The subsequent Python-only startup
guard fix passed 46 guards. Integrating the independently reviewed follower launcher
and four guards brought the final Python discovery result to **50 passed**.
The existing workflow discovers these files without modification.

The scenario/runtime correction is `41184bd`; the follower follow-ups
`aeb5b1c` and `1be6fb1` were integrated as `9ed05a2` and `5498fd6`.
The final retained-data-only mutation fix changes test inputs derived from the
checked context, not production predicates. Its **33 retained tests** and three
standalone negative observations passed separately, with read-only evidence,
network disabled and 2 CPU / 2 GiB container limits. The first retained run's
32/33 result and an intermediate test-only compile error are preserved, not
counted as successful runs. See [scenario evidence](private-cluster-scenarios.md)
and [bounded follower evidence](bounded-follower-live.md). Source/artifact hashes
identify reviewed bytes; they do not establish reproducible compilation.


### Header-context integrated checkpoint

The independently reviewed header-context increment `4af584c` was integrated as
`34fa905`. The final integrated export passed **787 public Scala tests** (app 165;
other module counts unchanged), all **25 public serial gates**, and **50 Python
guards** in one run. The same isolated, network-disabled 2 CPU / 2 GiB setup and
private build/cache directory were used. Full logs remain outside Git under
`/home/<user>/cardano-scenario-integration-20261008/final-public-tests.log`.

A separate read-only test of the newly captured scenario packet passed the
standalone header-observation CLI and **nine header suite tests**, comprising
eight public regressions and one opt-in captured-evidence case. Both OpCert and
KES signature predicates passed using timing and fixture registration extracted
from the original hash-bound genesis. These nine are reported separately and
overlap eight public tests; they are not added to the 787 public total. The
separate 33 negative/transfer retained tests remain as reported above. Header
success still does not establish trusted registration, VRF/leadership, current
stake/counters, full header validity or consensus validity.


## Applied validity-interval checkpoint

The command now uses the explicit
`conway-pv9-cluster-ada-interval-transition-v1` composition, including the actual
containing-block slot and original unstripped identity/signatures/fee sizing.
Legacy Coverage, Balance and RestrictedReplay source remains unchanged. The
isolated reference run accepted an interval-bearing transaction at slot 1281
within `[928, 2028)` and rejected separated expired/not-yet-valid bodies. This is
not an exact-boundary, complete failure-order, full-ledger or consensus proof.
Validation: 800 public Scala tests, 25 public gates, 58 Python guards; separately,
39 retained-data checks. See [the interval evidence record](private-cluster-interval.md)
for scope, private receipt locations and the preserved initial failed run.


## Certificate-state capture checkpoint

The experimental certificate state and dedicated source-bound capture now verify
OpCert/KES signatures, required pool-to-VRF registration, exact Word64 counter
transitions, full final counters and every-prefix rollback/reapply over an
original four-block range (anchor slot 1024 to endpoint slot 1180, epoch 2).
Observed counters did not change. This remains narrower than full consensus;
VRF eligibility and stake/nonce evolution are not checked, and state exports are
separate non-atomic acquisitions. See [the certificate capture record](private-cluster-certificate.md).
Validation: 817 public Scala tests, 25 public gates and 66 Python guards; separately
four optional retained-data checks (11 suite tests including seven public reruns).


## Restart helper and native-script diagnostic checkpoint

The reviewed restart-only source packet is integrated, retaining its scoped
reference relay TERM/recovery evidence and explicit exclusions for Scala recovery,
atomic snapshots and power-loss durability. Native-script evaluation/witness
diagnostics are also additive; `credentialBound=false`, and the proposed adapter
patch remains unapplied. Script-spending and minting admission are not supported
by this increment. Existing transaction admission and historical source pins are
unchanged.

Validation passed 829 public Scala tests, 25 public gates and 95 native-inclusive
Python tests. The compiler-free Java image explicitly skipped native-helper setup;
the existing Linux compiler exercised that group separately. Docker-call sentinels
confirmed no container calls during guard execution. See
[restart scope](private-cluster-restart.md) and [native diagnostic scope](native-script.md).


## Coherent branch and independent transition checkpoint

The supplied-state eligibility, credential-bound native library and pure
`ClusterTransition` are integrated with a new atomic one-block coordinator.
Comparison adapters derive state independently before optional reference
post-state comparison. Their narrower whole-checkpoint restrictions explicitly
reject the retained interval capture's Byron leftovers; historical receipts are
not relabelled. No positive end-to-end reference acceptance is claimed.

Validation: 883 public Scala tests, 25 public gates and 95 native-inclusive Python
tests passed. The compiler-free Docker image ran 93 Python tests with one native
setup skip; the host's existing compiler ran all 95 under a Docker-call sentinel
with no container calls. Separate opt-in suites passed 26 checks: six public
reruns and 20 retained/synthetic checks. See [the exact contract and test scope](coherent-branch.md).


## Positive complete-state coordinator reference checkpoint

An explicit fresh private genesis profile omits Byron allocations before any
database is created, preserving all generated Shelley funds/staking. The original
Byron-containing capture still returns Unsupported. The new complete unfiltered
reference pre-state has six outputs. One original block/transaction passes the
coordinator, independently derives UTxO/fees, matches the external post-state and
verifies atomic full-tuple rollback/reapply with stale-operation fencing.

The live run passed in 226.836 seconds with complete owned-resource cleanup.
Generated, pre-start configured and operational genesis bytes remain separately
attributed; the reference launcher's timestamp adjustments are explicitly recorded.
Validation passed 891 public Scala tests, 25 public gates and 102 compiler-inclusive
Python guards. Separate opt-in suites passed 37 tests: 14 public reruns and
23 retained/synthetic checks, including the authentic positive observation and
oracle-ordering negatives. No full-ledger, consensus or atomic-snapshot claim.
See [the exact fixture contract and evidence](private-cluster-coherent.md).


## Stake and boundary foundation checkpoint, 2026-10-09

Reviewed source `21e377b4389fa38e97f7b42ebc1c3ff6c145d7ac` passed one batched
offline public regression: **1,177 Scala tests**, **63 translator tests**, all
**25 public serial gates**, **306 executed Python launcher tests** (308 collected,
two optional retained-data skips), and **28 checkpoint guards**. Public module
counts are core 219, VM 47, network 100, network-runtime 52, ledger 194,
ledger-runtime 150, fetcher 5 and app 410. Retained stake cases were not enabled
in this public run. A separate final integration check passed **29 focused tests**
with read-only private inputs, including the synthetic nonzero-stake publication
and exact undo case and eight pure boundary cases. The earlier focused durable
check passed all 58 unique selected tests after correcting an oversized retained
fixture selection; its initial 21 fixture-loading failures remain preserved.

The batch used a fresh Git source export, private copied dependency/build caches,
the existing pinned JDK/Python image, network disabled and 2 CPU / 2 GiB limits.
It completed in 140.16 seconds with owned-container cleanup confirmed. All source
hashes were checked; subsequent publication edits only clarify documentation and
record these results. This is offline local acceptance, not a hosted-CI result.

The [stake coordinator](atomic-stake-coordinator.md) remains opt-in and in-memory;
both checkpoint codecs reject stake-bearing state. The
[boundary preview](conway-epoch-boundary-preview.md) is synthetic and cannot publish
runtime state. Unknown/pulsing phases fail closed. No epoch runtime enablement,
native reward parity, branch-ancestry proof or new live acceptance is claimed.


## Supplied reward pipeline checkpoint, 2026-10-09

Reviewed source `36c964e2f591405dd2d3b74383c70a913290b973` passed one combined
offline public regression: **1,194 Scala tests**, **63 translator tests**, all
**25 public serial gates**, **306 executed Python launcher tests** (308 collected,
two optional retained-data skips), and **28 checkpoint guards**. Module counts
are core 219, VM 47, network 100, network-runtime 52, ledger 211, ledger-runtime
150, fetcher 5 and app 410. A separate retained-input integration check passed
**46 focused tests**: 29 pure ledger cases and 17 retained-enabled application
cases. Retained inputs were not mounted in the public regression.

The batch combines [reward-start allocation](conway-reward-start.md),
[completion equations](conway-reward-completion.md), and
[application-time recipient filtering](conway-reward-application.md). It uses
checked supplied parameter/global projections and supplied member/leader rewards.
Native input admission, branch ancestry, per-pool/member entitlement, pulser
execution, non-myopic updates and runtime epoch publication remain excluded.
Same-epoch runtime guards and stake-bearing checkpoint rejection are unchanged.

Tests used a fresh Git source export, private copied caches, the existing pinned
JDK/Python image, networking disabled and 2 CPU / 2 GiB limits. Owned-container
cleanup was verified. Source hashes remained unchanged; the subsequent edit
only records verification here. These are local results, not a hosted-CI claim.


## Pure monetary pulser checkpoint, 2026-10-09

Reviewed source `01165450445ed0f66f38ee6a7db1c5a3fa7d5397` passed one combined
offline public regression: **1,218 Scala tests**, **63 translator tests**, all
**25 public serial gates**, **306 executed Python launcher tests** (308 collected,
two optional retained-data skips), and **28 checkpoint guards**. Module counts
are core 219, VM 47, network 100, network-runtime 52, ledger 235, ledger-runtime
150, fetcher 5 and app 410. A separate retained-input integration check passed
**70 focused tests**: 53 pure ledger cases and 17 retained-enabled application
cases. Private inputs were not mounted in the public regression.

This batch adds [pool/leader calculation](conway-pool-reward.md),
[member distribution](conway-member-rewards.md), and the
[immutable monetary pulser](conway-reward-pulser.md). Tests cover native
script-before-key traversal, bounded chunks, exhaustion checked before work,
delayed completion after the final chunk, forced completion, opaque progress,
and application-time registration. Monotonic signals are a stricter local API;
old immutable states remain replayable. The supplied frozen snapshots and scoped
parameter/global projections are not native admission or ancestry evidence.
Native execution parity, full RUPD, events, non-myopic updates and runtime epoch
publication remain excluded. Runtime and checkpoint guards are unchanged.

The batch used a fresh Git export, private copied caches, the existing pinned
JDK/Python image, networking disabled and 2 CPU / 2 GiB limits. Owned cleanup
was confirmed and source hashes were checked. The subsequent edit only records
verification here. These are local results, not a hosted-CI claim. The next
evidence priority is a separately reviewed offline native differential.


## Recorded synthetic native reward differential, 2026-10-09

Reviewed integration `c09971289bdab48973039f6968ad748585da907d` passed one
combined offline public regression: **1,223 Scala tests**, **63 translator tests**,
all **25 public serial gates**, **306 executed Python launcher tests** (308
collected, two optional retained-data skips), and **28 checkpoint guards**.
Module counts are core 219, VM 47, network 100, network-runtime 52, ledger 235,
ledger-runtime 150, fetcher 5 and app 415. The five new default tests include
the exact hash-pinned native golden and retain the separate hand-derived
expectation and rejection tests.

Focused integration also passed those five tests, the explicit Test-classpath
native comparison command with no mismatches, and schema/conservation checks
for both native and hand-derived files. The emitted Scala projection preserves
the independently recorded SHA256 `a69664a16302f2113eac02eb305ac9294401fd3b3f85f1a17b436f5a81c0acce`.
No native executable was rebuilt or rerun during this integration.

The [finite comparison record](synthetic-reward-native-comparison.md) covers
11 self-generated synthetic cases, 33 steps (4 Absent, 17 Pulsing, 12 Complete),
11 initial probes, 20 pool projections and one registration application. The
38,557-byte native golden is unchanged. Licensed native source/schema/validator
and portable provenance are public; machine-specific launchers, build closures,
caches, logs and binaries remain private. No captured-chain or provider corpus,
private keys, production source, build dependency or runtime authority was added.

Tests used a fresh Git export, private copied caches, network-disabled Docker
and 2 CPU / 2 GiB limits, with no private fixture mounts. Owned cleanup was
confirmed; source hashes were checked and only this verification prose changed
after testing. These are local results, not a hosted-CI claim. Finite agreement
does not prove general reward parity, events/non-myopic equality, valid-chain
or native snapshot provenance, full RUPD/NEWEPOCH or runtime epoch safety.


## Internal synthetic successor integration, 2026-10-09

Reviewed source `54a819fa1511a791a6f194edc44cb4d5d45440a7` passed formatting
and **183 targeted tests**: 19 core, 68 ledger and 96 application tests. This
is a focused integration regression, not a new full-local-suite total. It covers
successor header contexts, ledger/stake selection, owned reward progression,
atomic boundary-plus-block publication, whole-tuple undo, certificate branches,
existing v1/v2 checkpoint/recovery and combined lifecycle paths, and the finite
native monetary golden. Existing captures were mounted read-only; successor
tests deliberately use synthetic anchor geometry and are not a native boundary
differential or valid-chain admission proof.

The [internal successor API](synthetic-successor-block.md) remains package-private.
Public prepare/CLI epoch guards and both stake-bearing checkpoint refusals remain.
Future recovery requires authenticated historical boundary provenance even when
rewards remain Absent, historical freezes within retained suffixes, distinct
ordinary/post-boundary frozen schemas, controller-authorized component identity
restoration and measured aggregate limits. Final revision minus retained depth
cannot reconstruct historical revisions. No recovery seam or wire codec is
part of this integration.

Tests used an isolated copied build/cache in network-disabled Docker with
2 CPU / 2 GiB limits and verified owned cleanup. All source pins were checked;
only this verification prose changed afterward. No live cluster, denied genesis
fixture, key generation or native rebuild was used. These are local targeted
results, not a hosted-CI result or runtime epoch-safety claim.


## Recorded native boundary projection, 2026-10-09

The [finite native boundary comparison](synthetic-boundary-differential.md) now
has a hash-pinned golden in the default app suite. All seven cases match every
declared field and array after changing only the root producer label. Scala
output is byte-for-byte identical to its independently prepared pre-native output.

Integration passed 40 targeted tests (30 ledger, ten app), formatting and the
explicit native-golden comparison command. The default public regression passed
1233 Scala tests, 63 restricted translator tests and all 25 public gates, plus
the launcher and checkpoint Python guards. Optional retained-input checks remain
outside this fixture-free default run. Containers used private caches/outputs,
network none, two CPUs and 2 GiB with verified owned cleanup. No live cluster or
native executable was launched for integration.

Scala composes pure APIs and attaches pre-tick reward computation to boundary
projection. This comparison does not exercise the coherent successor coordinator,
full-block validation, cryptography, governance/non-myopic fields, rollback or
persistence. Runtime guards and checkpoint refusals are unchanged. Hosted CI is
tracked separately from these local results.


## Opaque synthetic recovery handoff, 2026-10-09

The [internal handoff](synthetic-recovery-model.md), its original-byte/scalar
preflight fixes and the [historical-ID counterexample](synthetic-recovery-codec-blocker.md)
are integrated. Independent source review confirmed exact worker-source integration,
unchanged CLI/runner wiring and unchanged checkpoint guards.

Targeted integration passed 130 tests: 33 ledger and 97 app. Coverage includes
all recovery phases, ordinary and post-boundary frozen provenance, fresh owners,
continued publication and undo, controller denial/deadline/cancellation, original
payload/scalar budgets, exact historical signal-chain differences, coordinator
regressions, the boundary golden and existing durable checkpoint refusals.
Formatting passed; all 1009 source files remained unchanged during execution.
The offline container used two CPUs and 2 GiB with private cache/output, read-only
existing captures and verified owned cleanup. No full local regression or live
cluster was repeated for this separate slice; hosted CI is recorded separately.

This is a process-dependent opaque handoff authorized against the exact existing
envelope object. Its limits budget existing objects, not decoder allocations or
JVM heap use. It supplies no byte codec, disk/crash recovery, durable controller
authentication or runtime epoch admission. The historical-ID counterexample
limits one exact-identity contract, not durable recovery generally. A future
controller-attested semantic anchor may use fresh runtime identities and canonical
content/provenance commitments; discarded history is not independently replayed.
The ephemeral crossing path remains separate and requires complete native seed
admission and automatic governance/non-myopic effects to be carried and compared.

## Bounded service milestone — 2026-10-10

Source `af8c589` passed 1,728 Scala/translator tests, 27 public gates and 466 Python tests run with two skips. Separately, 20 retained-data tests passed. The second isolated service attempt passed; the first timing failure remains retained. See [exact acceptance and limits](plutus-service.md#isolated-service-acceptance--2026-10-10). These counts are a dated milestone and are not additive with older runs.

## Typed sequential milestone — 2026-10-10

Source `0424dd7cc6194ca01581884ab8222632c5e9af28` passed 1,765 Scala/translator tests, 27 gates and 486 Python tests run with two skips, plus 20 separate retained tests. See [live evidence and preserved failure](sequential-devnet-runner.md#bounded-sequential-acceptance--2026-10-10). The preceding service snapshot `08898c9` passed [hosted CI](https://github.com/ottobot-ai/cardano-scala-lab/actions/runs/38018444095); this link is not a CI claim for the newer source.


## Longer-run prerequisites — 2026-10-10

Source `66997e2968eb34188a53fda2f211572b37aa2253` passed **1,791 public Scala/translator tests**, formatting and packaging,
**27 public gates**, **486 Python tests run with two skips**, and **26 separate retained-data tests**.
The new corpus-dependent image tests remain opt-in; the public count includes three
image refusal/bounds tests, six empty DRep tests, eleven two-endpoint boundary tests
and six repeated-epoch invariants. No new live test was run for this increment.

| Exact verification log | SHA-256 |
| --- | --- |
| Public Scala/translator | `c90e3b608eb223a54fc3aa401e2898be0136e7225ecd2dff09ad0bf2793cf905` |
| Public gates/Python | `6aeed35ca369882c79679ac02bc060e62b911b0d4f70c2180b0f9d83c26e73f4` |
| Separate retained data | `e6d96c4181e5b3a3e3b446a9179e1c127ae6c8ef25c473ae16e84cc3b61e34b6` |

The preserved sequential acceptance publication `40c5ef271a74e16cbd8af336e8432726cc3dbe7c`
passed [hosted CI](https://github.com/ottobot-ai/cardano-scala-lab/actions/runs/38020455228).
That historical CI result is not a claim for this newer source. The original
live success and reference-stall failure remain bound to their own immutable sources.
See the [capability matrix and next tests](sequential-devnet-runner.md#ten-minute-target-and-prerequisites).


## Functional and restore checkpoint — 2026-10-10

Exact source `86088809097cb09b0ca9e2a644eedc43becdb622` passed 1,918 public Scala/translator tests,
41 tests in the separate retained-data invocation, 27 public gates and 500
Python tests (two skipped). The retained invocation includes three public
checkpoint parser tests alongside retained cases; these totals are separate
invocations, not a disjoint count of unique tests.

This adds checked opaque pin identifiers and quantities, typed construction,
Cats 2.13 alignment with Discipline laws, and actual service composition through
explicit read/fence capabilities. Read-only capabilities cannot authorize a
commit; the generic guarded program preserves four attempts and existing
owner serialization/masking. Wider typed errors and caller migrations remain
active work in the [functional audit](functional-scala-audit.md).

Authenticated bounded runtime replay and opt-in service checkpoint/restore
wiring are tested offline. The linear epoch-zero image accepts at most eight
retained blocks, starts a fresh owner and empty pool, and depends on an exact
external authorization. It is not crash-durable storage or a live restart
result. Checked Plutus successor binding is also an offline prerequisite;
repeated epoch publication and a valid successor-spend integration test remain
outstanding.

The separate [two-service live result](two-service-http-client.md) used earlier
exact source `d57d9796a75c4cbb1e5b31897c259ac7eb296932`, not this later source.
It proves two live ingress owners and cross-observed original transaction
body/witness bytes in the bounded same-epoch profile. No multi-epoch soak has
yet passed.


## Typed errors and compiler verification — 2026-10-10

Exact source `915ead2efd57889d3543f2fe2b5448ba22dc5451` passed 1,935 public Scala/translator tests,
41 tests in the separate retained invocation, all 27 public gates and 511
Python tests (two skipped). Retained and public invocation totals overlap as
described above. Log SHA256 values are respectively
`f16a2cf53557a612b9027d38c16b1c7e09797792e3eb46763953f301b3d9a670`,
`6a479e7c1e10ceef3f3d44f22ae282cb1eb3553289b6797c0c36ea6942498b91`
and `d44457571df5d3ff791ad6a4f55478d272e5560ba8ee3a2f28bbe4e03ae57e91`.

Admission view construction now preserves typed domain, pin and input causes
until the existing effect boundary. The large research runner is decomposed
into typed phases with unchanged resource and effect order. A preceding full
build hit a Scala 3.3.8 posttyper stack overflow; the preserved failure was not
reproduced by every isolated compile. The decomposed runner passed a clean
compile and the full aggregate using the default stack, without changing the
Scala version or adding unsafe effect execution.

This source also includes the offline early-restart controller and dynamic
JVM likelihood computation gated by exact native comparison. That mode has a
native runtime dependency. The registered-DRep and repeated-boundary changes
integrated later have their own focused evidence and are not covered by these
aggregate counts. Independent JVM-only generation and the measured 600-second
multi-epoch soak remain pending. The published `d8613b4` checkpoint separately
passed [hosted CI](https://github.com/ottobot-ai/cardano-scala-lab/actions/runs/38024507910).


## JVM-only repeated runtime prerequisites — 2026-10-10

Scala source `8b407d2e822c7387492a782833faa5106e8e96e3` passed 1,969 public Scala/translator tests and
55 tests in the separate retained invocation. Python source `3a8898f22f0d8eb32cbe3c5d036959c84a91c9ca` passed
all 27 public gates. The Python invocations ran 529 tests across the established
511-test run and the two new suites (18 tests): 527 passed, two were skipped,
and none failed. The
retained invocation overlaps public tests and is not an additional unique-test
count. The new Python suites are explicitly included in hosted CI.

| Verification | Log SHA256 |
| --- | --- |
| Scala/translator | `437dc37baa3f0b9fee4f948cb3994796431038909f264bb2c8ad7ffb6d4dbf58` |
| Retained invocation | `0856b30942337acff72e6a40f56bc77ed12e19a21ab4549ae12102b477f9a649` |
| Public gates and established Python suites | `c83c12790b1d84bfe618c2853122639873cd89da9b645a4ea4042346d2dfa214` |
| New Python suites | `0c02a4bd7c5b54c3a9167406889c5ee0b29ab351ab311d86263751ca25211e87` |

The primary [repeated service](plutus-repeated-service.md) now selects explicit
JVM-only likelihood generation with source-bound recorded arithmetic. The
separate native-checked mode retains its declared native runtime dependency.
Neither mode substitutes endpoint state for the selected mutable state. The
registered-DRep completion, repeated governance/reward coordinator, late-freeze
binding and Plutus successor binding have focused offline and retained evidence.
Successful service results are written only after generation resources close.

The [post-run differential verifier](likelihood-postrun-differential.md) is
independent of runtime generation. Its finite native smoke matched 100 raw32
words and one raw64 value, and detected exactly one deliberately changed bit
without replacing JVM output. That smoke used synthetic publication metadata;
it is not a live-service result or universal floating-point parity proof.

The [post-boundary client and fixture](plutus-soak-prerequisites.md) are tested
standalone prerequisites. The incomplete combined supervisor is excluded from
this checkpoint. The repeated terminal serializer/comparator remains unfinished;
in-progress reward pulsers require their own supported comparison, not an
assumption that elapsed time means completion. No new live restart, multi-epoch
soak, terminal parity or public-network fetch success is claimed. The prior
same-epoch two-service result remains bound to its original source.

The [functional audit](functional-scala-audit.md#implementation-checklist-2026-10-10)
records exact commits, real production before/after examples, law coverage and
remaining migrations. Wider raw-domain/error migration and rebuild composition
remain separate work.


## Checked rebuild pairing — 2026-10-10

Exact Scala source `07106996174a5619e59272a4f2cf2fe2d605ba51` passed 1,974 public Scala/translator tests,
55 tests in the separate retained invocation and all 27 public gates. Python
sources are unchanged from the preceding checkpoint: the established 511-test
run passed again with two skips; the separately pinned 18 new tests retain
their passing result. Combined coverage remains 527 passed and two skipped.

The [functional audit](functional-scala-audit.md#follow-on-checked-rebuild-context)
records the production change from an unchecked view/work tuple to a private
checked context. Complete pin and profile equality is established before work
is queued; original work identity and owner fencing remain in force. Five new
tests cover pairing and identity boundaries, alongside existing wake-up,
cancellation and rapid-generation regressions. No public wire format changed.

Scala log SHA256: `050282717995cbc6815601cc60941b0cfe5060630a93ce50cce07994e19e258d`.
Retained log SHA256: `3957cfd2fc3415098b4a9e27a17644aaa641cda1b888f0d1e0165e736d9aeaf3`.
Gate/Python log SHA256: `f6b73a949b6c3bcb86988a960f1af268ecc4bdb618cf9d752352717000325915`.

The preceding `910b8dd` prerequisite checkpoint passed
[hosted CI](https://github.com/ottobot-ai/cardano-scala-lab/actions/runs/38027107108). This later source requires its own CI
result. The repeated terminal comparator and combined live supervisor remain
unfinished; no new live restart, soak or terminal agreement is claimed.


## Independent early restart acceptance - 2026-10-10

The [bounded restart driver](plutus-early-restart-check.md) now has independently
reviewed local execution evidence at exact source `5db2ca87dd3224d805dfcf3d87c4495208f49015`. Initial
slot 188/block 1 advanced to checkpoint slot 360/block 2, then a replacement
with a fresh owner and empty pool checked slot 362/block 3. Each service
balanced two transport opens/closes, and owned cleanup was verified. The
70.628-second controller lifecycle included preparation and cleanup; service
intervals were 1.070 and 0.583 seconds. This is epoch-zero restart acceptance,
not the planned 600-second active soak or whole-state oracle agreement.

A preserved first attempt exposed a receipt/exit inspection race, fixed before
the successful retry. Equivalent successor-wait hardening followed that live
run and has separate regression coverage. The final Python run completed
544 tests: 542 passed and two skipped. All 27 public gates passed. Unchanged
Scala sources retain the preceding exact-source 1,974-test result and separate
55-test retained invocation. Final gate/Python log SHA256:
`4b2164792fbcaaa5c46dc8b185dc9e297f1f1daccb262e5111c39649eb7e6c1f`.

The [curated receipt](../reference/plutus-admission/early-restart-live-receipt.json)
contains source, points, counts and original-evidence hashes only. Keys, raw
checkpoint/state and logs remain private. The repeated-state comparator and
combined multi-epoch supervisor remain unfinished; no new tested-spend,
late-restore, crash-durability or multi-epoch claim is made here.
