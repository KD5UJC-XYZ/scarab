# Native rolling ChainSync decisions

These seven stateful sequences contain 798 exact outputs from the unchanged
`pipelineDecisionLowHighMark` and `runPipelineDecision` in network pin
`2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`. The Apache-2.0 source hash, observer,
compiled executable and package/build identities are in [manifest.json](manifest.json).
The [observer](../../../scripts/oracle/chainsync-pipeline/Main.hs) retains the opaque native
continuation after every decision; it does not independently reproduce native hysteresis.

These explicit boundary inputs are not upstream-generated protocol schedules. The low/high
pairs include the native production defaults 200/300, native peer-simulator defaults 10/20,
and small/zero-mark controls. Each sequence includes zero/nonzero outstanding requests,
Origin, equal and reversed tip heights, both hysteresis boundaries, reset through zero,
and native Word64 block-number overflow. Grid rows are decision-function inputs, not a
claim that every consecutive row represents a lawful transport execution.

`client` and `server` are optional unsigned block numbers: JSON null means Origin. At Origin,
native `bnoPlus Origin n` is `At (1+n)`; an ordinary block number addition wraps Word64. A
nonzero request stays outstanding through AwaitReply until its RollForward/RollBackward
arrives. Those transport callbacks and optional-collection readiness are outside this pure
function corpus and require separate runtime tests.

The native decisions are Request, Pipeline, CollectOrPipeline and Collect. In the third case
the actual driver collects a completed response when available and otherwise sends another
request; it must not poll in a busy loop. On jump, restart or orderly termination, all submitted
replies must be drained before FindIntersect/Done. A matching pure decision test alone does
not establish those runtime obligations or exact thread schedules.

Regenerate offline with existing pinned packages mounted read-only:

```sh
python3 scripts/oracle/chainsync-pipeline/export.py \
  --reference-build /tmp/scarab/148-fetch-oracle-final-2 \
  --probe-dir /tmp/scarab/chainsync-native-pipeline-regeneration \
  --output-dir fixtures/network/chainsync-pipeline
```

The exporter makes no source/dependency-cache copies. Preserve compact evidence and remove
its owned compiler output after review. No decision fields or observations are excluded.
