# Bounded live in-memory runner acceptance

This adapter connects the existing bounded runner to an isolated local reference
cluster. It remains separate from the durable facade and process-restart work.
The source base is `14e3f54456f31a3d6db625f40efbbf86e6b2ab05`.

The fixture uses the existing explicit PV9/header11.2 development profile and two
independent disposable key transactions. It starts from seven source-bound supplied
prestate exports, not a trusted downloaded tip. Snapshot queries remain separate,
non-atomic acquisitions under observed quiescence. Full-ledger and consensus
validation remain false.

## Online ordering

Producers are paused for the pre snapshot. The context manifest is complete before
the observer starts. Readiness is emitted only after actual peer intersection and
the coordinator's initial anchor no-op; production resumes after that signal. One subsequent protocol rollback message may
repeat only the exact supplied anchor with unchanged revision and scoped state.
It is counted explicitly; later or repeated rollbacks fail this acceptance case.
The harness waits for an empty block to be committed by the running observer,
then briefly pauses producers to submit both prepared transactions to the relay.
The observer remains active and applies successors in order while production runs.

The live target is fixed at four retained blocks, within the unchanged eight-block,
same-epoch limit. The two transactions must occur in one actual original block;
relay admission alone is insufficient. The harness never extends the window or
automatically retries a failed grouping or endpoint race.

After TargetReached, all owned peer/transport resources must be finalized. Exact
original envelope/block pairs are preserved from the final applied prefix. The
harness promptly pauses producers and requires the reference post tip to equal
the runner's exact final point. If another block won that race, the case fails and
preserves its evidence. AwaitReply and budget/time exhaustion never mean caught up.

The adapter retains its final coordinator snapshot in the same process while the
harness captures poststate and publishes the post-oracle manifest. Only then does
it load the oracle. The existing offline sequence audit replays exact originals,
compares final UTxO/fees, complete certificate counters and known nonce fields with
the reference, and checks supported endpoint assumptions. The adapter compares
the audit's complete final tuple with its surviving online tuple. Offline replay
is an audit of online progress, not a substitute for the online execution trace.

## Evidence and limits

Records distinguish ChainSync announcements, completed downloads and scoped
applied progress. The supplied anchor is not an applied tip. Typed stop reason,
readiness/commit ordering, actual transaction grouping, exact bytes, final tuple
comparison and resource open/close counts are retained privately.

Scripted rollback/reconnect tests remain scripted evidence. This case does not
claim a live competing fork, durable runner operation, graceful process restart,
process-death recovery or power-loss safety. Same-epoch fixed stake/registration
assumptions are unchanged; equal endpoint exports do not prove continuity.

The existing slot permits one reference cluster at 3 CPU/6 GiB and one observer
at 1 CPU/1 GiB, internal Docker networking only, <=600 seconds overall. Pre/post producer
pauses are bounded at 20 seconds; submission pause at 8 seconds. Runner work is bounded
at 120 seconds and the post-oracle wait at 30 seconds; finalizers are awaited, not
abandoned to claim a hard timeout. No public peers, production keys or real funds.

Offline adapter/launcher tests and independent review of source plus the exact
command precede any live execution. Failed attempts are preserved; no acceptance
claim is made until the recorded case and cleanup pass independent audit.

## Preserved first attempt

The first bounded attempt applied four blocks with transaction counts 0, 2, 0, 0
and reached its target at revision 4. All one peer and five transports closed.
The harness rejected the initial unchanged-anchor rollback because its original
guard required zero rollback messages. Poststate comparison was not reached, so
this is a failed acceptance attempt. Owned containers and network were removed
after 144.8 seconds. The corrected guard retains the message and its count while
allowing only the exact initial no-op described above. Private originals and logs
remain outside Git.

## Reviewed second attempt

The fresh second case passed on 2026-10-09. The observer started from supplied
block 66 at slot 1503 and applied blocks 67–70 at slots 1573, 1589, 1669 and 1684
in epoch 3. Their transaction counts were 0, 2, 0, 0. The exact final online point
matched the reference post endpoint, and the surviving online tuple matched the
independent original-byte replay and reference comparison. Independent audit
confirmed full UTxO entries 6→8, unchanged bytes for four surviving original
entries, fees +400,000 lovelace, complete counters and all five known nonce fields.

The runner stopped with `TargetReached`, revision 4, nine events and 7,330 returned
payload bytes. Its one initial anchor-alignment rollback changed no scoped state;
there were no reconnects. One peer and five actual transports opened and closed.
The complete harness, including later epoch-growth checks, took 288.8 seconds and
verified removal of every owned container and network. The online four-block
window itself stayed within one epoch. The first failed case remains preserved.

The exact-original capture SHA-256 is
`0a0b158bc7cd542ccf8aa210a95472ec97581b15ca4dd6020b0937799ac49d77`.
Observer stdout SHA-256 is
`eebd8e61f4982ae0d8c4fc050b6c8fc694a396dac65bdfc2dc74af256f0d8b0b`.
Private source exports, keys and raw logs are not distributed. This establishes
the bounded in-memory combined path described above, with no live competing-fork,
durable-runner, process-restart, full-ledger or full-consensus claim.

Offline validation passed 1,061 root Scala tests plus 63 translator tests,
25 public gates and 142 Python tests in the compiler-free Docker environment
(one native-pidfd test class skipped). The retained-enabled focused run passed
78 Scala tests, including 14 new adapter cases.
