# Native Genesis historicity and failure policy

The observer calls consensus `82ecba329d7d054340bf707d44fe6e9ac27cec40`'s unchanged
`HistoricityCheck.mkCheck` and `consensusRethrowPolicy`. The source, observer, executable,
image, build-plan and native sodium hashes are recorded in [manifest.json](manifest.json).

All 72 cases are retained: both rollback and AwaitReply, every GSM state, genesis and an
applied `TestBlock` header, future/zero age, and the configured 37-hour cutoff minus one,
exactly equal, plus one picosecond, and twice the cutoff. Scala compares the complete
historical failure attribution and whether the native implementation read the clock.
CaughtUp skips the clock and the check. A rollback is judged using the oldest state removed;
a zero-depth rollback has no such state and skips the check.

The companion error observations exercise `EmptyBucket`, `DensityTooLow` and
`InvalidJumpResponse` under both inbound and outbound consensus rethrow policies. All return
`ShutdownPeer`. This retains eligibility for the network's delayed re-promotion rather than
permanently forgetting the peer; local CSJ invariant failures remain local node failures.

Prepare the shared native build with `scripts/oracle/genesis-density/export.py`, then export:

```sh
python3 scripts/oracle/genesis-historicity/export.py \
  --container scarab-33-native-genesis \
  --source /tmp/scarab/33-genesis-density/source \
  --upstream /path/to/pinned/ouroboros-consensus \
  --probe-dir /tmp/scarab/33-genesis-density \
  --output-dir fixtures/reference/genesis-historicity
```

Native consensus license and notice: [LICENSE](../../licenses/block-fetch-consensus-LICENSE),
[NOTICE](../../licenses/block-fetch-consensus-NOTICE).
