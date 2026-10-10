# Explicit repeated JVM service

The primary opt-in mode is `--epoch-mode repeated-jvm-v1`. It records source-bound
JVM generation inputs and raw outputs for later independent differential comparison.
It accepts no native executable options, launches no native generator process, and has
no fallback to native generation or runtime native checking.

The separate native-checked diagnostic mode is `--epoch-mode repeated-native-checked-jvm-v1`, with
`--native-likelihood-executable ABSOLUTE_FILE` and `--native-likelihood-sha256 HASH`.
Both repeated modes permit at most 600 seconds and 512 published blocks. Omitting
the repeated mode preserves the default 60-second, 128-block, epoch-zero service and its boundary margin.
The native executable and its dynamic library closure must be trusted immutable local
mounts; hashing the executable alone does not authenticate its libraries.

The native-checked mode is JVM-computed, native-checked, runtime-dependent research. Each actual frozen
request is checked against the pinned native process; mismatch or missing evidence fails
closed. Readiness and result metadata explicitly record this dependency and executable
pin. Request/response/execution evidence goes into the fresh `native-likelihood` directory.
No reference endpoint snapshot replaces the selected mutable ledger state.

The repeated bootstrap derives source components from the checked initial packet. An early
epoch-zero checkpoint continuation replays original blocks into that same checked path.
The service starts HTTP only after startup state is selected. It supports early restore,
not exporting a composed checkpoint or restoring a late repeated-epoch state.

Preparation may invoke the native oracle outside the admission publication gate. The
coordinator checks its captured source again afterward, and publication still passes through
the same admission owner, updating state pins and invalidating pending transactions. The
follower permits monotonic current or exact-successor epoch announcements; skipped epochs,
noninitial rollbacks, foreign intersections and unsupported effects fail closed. Actual
boundary admission remains the coordinator's responsibility. The bounded streaming path
retains eight originals with fenced compaction and accepts at most eight epoch transitions.

The requested duration is an operational deadline, not a transaction-success claim. Cleanup
owns the network sessions, HTTP requests, service fibers and native child processes. No
full-ledger validation, public-network admission or crash durability is claimed.

Repeated readiness has a separate 30-second peer-handshake deadline. Its active clock
starts after a valid `peer-ready.json`; `service-active.json` records the actual start and
end. The final requested/effective deadline uses that active start, so controller waiting
does not consume the requested 120/600-second active interval. Default timing is unchanged.

Each publication's `repeatedEpoch.checkedLikelihood` binds the actual selected frozen ID,
application epoch, observed slot and pre-tick tuple to `requestSHA256` and
`evidenceSHA256` of the exact generation input and output. JVM mode reports
`mode=pure-jvm`, computed raw32/raw64 word counts, zero native comparisons,
`nativeValidated=false`, and a null `nativeResponseSHA256`. Its fresh `jvm-likelihood`
directory records the actual input/output for posthoc comparison. Native-checked mode
reports `mode=checked-jvm`, actual comparison counts and a native response hash.
Neither mode infers parity from hashes alone. The active-start record carries the same startup component identities plus
its owner pin. Generation directory indices have no epoch authority of their own. A missing
active freeze is represented by null; unsupported or mismatching checked evidence rejects.

Only the follower-owned operational deadline is normal duration completion. Peer
timeouts and resource acquisition/release failures remain failures, including release
errors during deadline cancellation. External cancellation propagates; repeated
cancellation metadata does not claim that resource finalization succeeded.

This service wiring slice does not yet attach the repeated terminal-state serializer and
independent endpoint comparator. Its offline checks establish configuration, source replay,
publication binding and failure handling; they do not establish a completed live repeated
soak or terminal parity. The terminal hook and its independent checks remain an integration
gate before claiming that evidence.
