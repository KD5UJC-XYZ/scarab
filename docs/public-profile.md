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
