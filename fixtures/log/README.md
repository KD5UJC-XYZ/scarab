# The log's rendering golden

`golden.jsonl`: one JSON line per sample of `node/src/test/scala/xyz/kd5ujc/scarab/node/log/LogSamples.scala`
(every kind of event, every case of every event type the node logs), with the time stamp fixed at
`2026-10-16T12:00:00Z` and a fixed build. `LogGoldenSuite` renders the samples through the node's log adapters and
compares the lines byte for byte.

It was first written by the `JsonLog` that the typed log replaced (#92); the differences since are listed in
[ADR 0014](../../docs/adr/0014-typed-log.md). The devnet scenarios and the run watchers parse these lines, so a
change here is a change of the log format. On a mismatch the suite writes what it rendered to
`target/log-golden.actual.jsonl`; review the diff and copy it over this file only for an intended change.
