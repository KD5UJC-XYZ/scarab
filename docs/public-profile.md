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
