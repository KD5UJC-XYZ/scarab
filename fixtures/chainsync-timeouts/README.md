# Native ChainSync deadlines

The unchanged `timeLimitsChainSync` function at network pin
`2fb69829a19b5c04e4fb212bdd78fb63f9d2d776` supplies all 384 active-state
observations here: eight seeds, both peer-trust values, default/no idle timeout,
and twelve state entries per combination. Every native output and RNG transition
is retained. Seven additional unsigned Word64 boundary inputs execute the pinned
`random-1.3.1` `scaleFloating` function directly. The observer cross-checks each
native random timeout against its corresponding native uniform Word64 sample and
resulting generator state.

[Manifest](manifest.json), [observer](../../scripts/oracle/chainsync-timeouts/Main.hs)
and [exporter](../../scripts/oracle/chainsync-timeouts/export.py) record provenance.
`NativeChainSyncTimeoutsSuite` compares every row: the three client receive states
use the production state selector; Idle uses the server's effective configured
idle deadline. It compares exact binary sample values and native picoseconds;
only the final host timer rounds up to nanoseconds. RNG advancement is required
only for untrustable MustReply entries. This does not equate Haskell and JVM seed
streams. Explicit fixed test profiles remain supported separately.

The native interval is **601–911 seconds**, despite stale shorter-interval comments
in the native sources. Trustable outbound local roots have no MustReply deadline;
inbound and other outbound peers are untrustable. A new timeout is sampled after
each AwaitReply hook, and fragmented reads do not renew it; runtime tests cover
these resource and state-transition contracts.

Regenerate in Docker using an existing pinned native build:

```sh
python3 scripts/oracle/chainsync-timeouts/export.py \
  --reference-build /tmp/scarab/148-fetch-oracle-final-2 \
  --probe-dir /tmp/scarab/native-chainsync-timeouts-regeneration \
  --output-dir fixtures/chainsync-timeouts
```

The reference build is mounted read-only. Remove the owned probe's generated
objects and executable after retaining the manifest and compact build result.
