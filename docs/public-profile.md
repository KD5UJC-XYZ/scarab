# Public verification profile

This export omits historical/private corpus data whose redistribution is not established. It starts a new public history; full research history and historical run output remain private. The two pinned licensed synthetic Conway vectors and their notices are retained.

Run `bash scripts/sbtw check app/runtimeClasspathFile`, then `python3 scripts/check-public-gates.py`. The first command compiles all production code and public test sources, then executes public module tests sequentially. The second runs exactly 25 explicit public gates. Neither claims the original research package's 1,294 tests or 31 gates. Actual public results are recorded separately after execution.

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

The job runs the public Scala check/runtime-classpath task, the explicit 25-gate runner, and Python launcher guard discovery. Guard tests do not launch Docker. `CLUSTER_TRANSFER_EVIDENCE` is not set, so the 26 opt-in captured-reference tests are not counted as executed. The six standalone JSON-reader tests remain in the default public profile.

```sh
bash scripts/sbtw check app/runtimeClasspathFile
python3 scripts/check-public-gates.py
python3 -m unittest discover -s scripts -p 'test_private_cluster*.py'
```

Public Maven access is needed on a cold hosted runner. The workflow initially uses no dependency cache action. Local verification uses the existing inspected dependency cache with networking disabled in a fresh public source export; that does not establish fresh online resolution. The workflow must pass independent review before publication; local YAML structure checks do not replace an actual GitHub Actions run.

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
