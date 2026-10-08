> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# KeepAlive short-range verification

This release's acceptance is local-only. Pure protocol cases use literal bytes;
runtime cases use finite bounded in-memory transports and Cats Effect TestControl;
TCP cases bind explicit 127.0.0.1 and use the existing asynchronous interpreter.
No public address lookup/connect, HTTP, relay, node startup or native reference
oracle was performed for this milestone. Source hashes are portable in
`fixtures/network/keepalive-direct-range/manifest.json`.

## Final aggregate

The frozen implementation passed **531/531 Scala tests**, including all 467 previous
cases and 64 new cases: 17 codec, 32 virtual/runtime, 4 descriptor/source-budget,
9 localhost TCP and 2 harness diagnostic controls. `scalafmtCheckAll` and runtime
classpath generation passed. Full output is retained in
`keepalive-build-verification.log`. The sbt wrapper uses its existing explicit
`-XX:ActiveProcessorCount=4` setting; no production deadline was widened.

## Focused checks

- `network/testOnly lab.network.KeepAliveSuite`: 17 literal codec/agency/cookie tests.
- `networkRuntime/testOnly lab.network.KeepAliveTransportSuite`: virtual-time
  independent deadlines, exact routing/fragment ownership, pause versus overflow,
  queue byte/frame bounds, physical wire totals, one reader, cancellation and
  joined release, admitted/waiting producer distinction, SDU fairness, graceful
  freeze deadline, pending response completion, atomic rejection/barrier race,
  coalesced handshake/application and whole deadline.
- `fetcher/testOnly lab.fetcher.KeepAliveTcpDescriptorSuite`: 4 explicit identity,
  policy change, no-upgrade and incomplete outgoing-budget cases.
- `app/testOnly lab.KeepAliveTcpChainFetchCommandSuite`: 9 controlled TCP cases,
  including full four-block Shelley/Babbage download and entire-range resume,
  pending KA reply after BatchDone, separately rejected BF suffix, optional pins,
  exact altered overlap, changed-profile/endpoint/limit preconnect spies, and
  injected Done-write/cleanup failures without committed records.
- `app/testOnly lab.LocalTcpOutcomeSuite`: 2 client/peer diagnostic preservation
  controls. The existing strict TCP suite continues to reject protocol8.

Full aggregate, direct-JVM CLI and provenance/runtime gates are recorded in the
release verification logs. These checks establish bounded local behavior, not
universal relay interoperability or JVM-native hard termination guarantees.

The original failed-run logs and isolated diagnostic logs referenced by hashes
or timings below are workspace-retained supplemental evidence, not bundled in
this repository. The two bundled KeepAlive verification logs record the final
accepted aggregate and serial audit run.

## Strict TCP fixture harness correction

The parent fresh 0.17 archive aggregate encountered a Shelley localhost peer-write
`Connection reset by peer` at 12.884s. The old `(client, peer).parTupled` harness
masked the client exit/stderr. Its original log SHA256 is
`56ecec08d2843842cfdff42d4393cb394f055379b76eea65755160f184075d90`.
The exact root cause remains unproven.

A frozen 0.17 diagnostic copy preserving both outcomes passed the isolated Shelley
case (3.72s; resume 1.6447s; client exit 0), then all 105 app tests (20s). Successful
repetitions do not establish that the original failure was harmless. The former
resume fixture issued 4072,9437 or21077 individual one-byte async writes, each with
an effect yield. TCP does not promise one read per write. Since an incomplete SDU
cannot publish StartBatch, excessive artificial fragmentation can consume the
existing 10s BF Busy/read budget. This is a plausible mechanism, not a proven
explanation of the original failure.

The approved correction changes tests only: bulk resume uses bounded 97-byte
fragments, while the existing singleton one-byte real-TCP control and pure
byte-by-byte parser tests remain. `LocalTcpScript.observeBoth` retains client
exit/stdout/stderr together with peer failure/cause; a peer failure still fails
the fixture even if the client succeeded. Two regression tests cover that rule.
No production deadline, strict descriptor, command default or protocol policy
was changed to conceal this event.


## CLI process-sizing evidence

Two baseline serial CLI-audit runs encountered unrelated existing 30-second guards:
`chain-fetch run --host example.invalid` and `vm /tmp/vm-negative-*`. Both are
local parser/fixture-negative paths, not endpoint connections. The first log's
SHA256 is `6199b5cf9ec2d9b3eb5e7f51c5dab14d2f8b4bf39f101c447cca2850e7f0da9b`.
The second log's SHA256 is
`586770555b1ed6b4b4f079f0702880a51bc8cc5ed2fb0cdfb486d4e0687b59f4`.
An exact retry of the first parser case exited 2 in 2.035s under the unchanged 30s
guard. Independent isolated diagnostics also passed both paths with default
processor sizing (1.62s/2.45s) and ActiveProcessorCount=2 (1.42s/2.23s). Default CPU/
IOApp compute worker counts were 9/9 versus 2/2 in that diagnostic comparison;
observed peak native thread counts were 30–31 versus 22. CPU quota/pressure data was
unavailable. Neither a host-scheduling cause nor a processor-cap cure was proved.

Final serial acceptance uses the explicitly recorded, per-process
`JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4`, matching the existing sbt sizing.
This is deterministic test sizing, not a production/system setting or timeout
change. All original case guards remain in force. The serial audit runner also
has one finite diagnostic watcher: a Java child still active at 20s receives a
thread-dump request before any existing 30s guard; a timeout stops the run and
preserves its captured output rather than silently rerunning the case.


## Terminal serial result

All 20 serial CLI/provenance/runtime audit scripts passed with the recorded
per-process 4-CPU profile and unchanged guards: 106 direct-JVM CLI cases including
4 new KeepAlive preflight cases, 9 new pinned source files and 1 retained source
license. Runtime remains 26 native-free jars; all 5 negative runtime checks and
23 operational-certificate negative admission cases passed. No child reached the
20s diagnostic threshold in this acceptance run. Full output is retained in
`keepalive-regression-verification.log`. Historical per-command CLI logs were
preserved unchanged; this release's aggregate evidence is recorded separately.
Fresh-archive verification is a separate packaging gate, not inferred from these
working-tree results.
