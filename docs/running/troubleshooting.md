<!-- SPDX-License-Identifier: Apache-2.0 -->
# Stalls and failures that stop the node

Part of [Running the node and the devnet scenarios](../running.md).

## Stall watchdog

A node that is up but no longer extends its chain looks healthy from outside (the process is alive and
idle, the metrics are served). The watchdog (`StallWatchdog`) compares three facts: when a block was
last extended, our tip, and the highest tip any peer announced (every ChainSync batch of every outbound
and inbound session reports its peer's tip). When no block has been extended for `--stall-minutes`
(default 10, `0` turns it off) while an announced tip is ahead of ours, it logs one `stalled` event
(`idleSeconds`, `ourBlock`, `peerBlock`, Warning), adds one to `scarab_follower_stalls_total` and sets
`scarab_follower_stalled` to 1 until the next block is extended. A node at the tip of every peer never
stalls however long the network takes to produce a block. Alert on
`scarab_follower_stalled == 1`, or on `increase(scarab_follower_stalls_total[1h]) > 0`.

A block the node holds without a verdict (`blockHeld`: the Plutus evaluator failed on the block's own
scripts, so the block is neither adopted nor recorded as invalid and no peer is blamed, ADR 0017) keeps
the node behind it. When the stall it causes begins, the watchdog also logs `stalledBehind` with the
held block's point, transaction and reason. Alert on `increase(scarab_blocks_held_total[1h]) > 0`.

A stall that cannot be reproduced on demand leaves its evidence (issue 371). Right after `stalled` the node
logs one `stallState` event with every session's pipeline: the coordinator's view (`busy`, the offers waiting for
a decision, the reservations in flight, the headers of its candidate) and, for a pipelined session, its batch
permits free of `permits`, the events queued for the consumer, the candidate's headers and those beyond the
newest point it shares with the published cursor (none when they share no point: a fork, told from lag by the
base and tip points), and the time since the last ChainSync reply was collected (before the capacity wait),
accepted (after it) and handled; and the shared block
admissions (`held` of `capacity` permits, `queued` works, and the kind of work the worker is on and for how long). The reads are `Ref` values and queue sizes behind
probes that do not block and are taken once per stall; what the block path pays is constant per item and takes no lock: three timestamps per ChainSync reply, three phase marks per BlockFetch reservation (waiting for the decision lock, holding it, waiting for the grant), a holder mark around each use of the decision lock, and, per admission, a mark while it waits for the publication lock and a work and stage mark for the worker (`reserving`, `decisionLock` and `admissions.worker`/`publishWaiting` in the event). The report is read first and logged from fibers of their own, so neither a wedged log lock nor a hung dump can keep the watchdog, a later stall check or the `--stall-exit-after` exit from happening, or the node from ending (an unfinished dump is abandoned at shutdown). At most one dump runs at a time, freed when its files are written: a stall that begins during one asks for none and says `skipped` in its `stallDump` event. The log lines of the reports go through a single-slot queue that never waits (one fiber, one waiting report, the newest), so a stuck log holds one fiber however many stalls come, and after a stall exit the final `finished` line is given 5 seconds before the exit code is returned. `--stall-dump <dir>` also writes the same line directly to `<dir>/stall-state-<epoch ms>-<n>.json` (no log lock involved) and adds the runtime's fiber dump, each in a fiber of its own so a hung file or JMX operation delays neither the `stallState` event nor later watchdog checks, written through the runtime's
`LiveFiberSnapshotTrigger` MBean (cats-effect 3.7.1 keeps `IORuntime.fiberMonitor` private); it lists the fibers
the runtime traces (`cats.effect.tracing.mode`, `cached` by default). `devnet.py e2e` passes the flag, and its
`e2e-summary.json` carries the `stalled` and `stallState` events and the dump files under `stallDiagnostics` when
the follower stalled. Copy `<run>/stall/` out before `down` removes the run directory.

A stalled process is alive, so a supervisor never restarts it. `--stall-exit-after 30m` (a number and `s`,
`m` or `h`; off by default) ends the node when the stall has lasted that long: one `stallExit` event, then the
same close-down as a signal (the stores close and the shutdown snapshot is taken), then exit code 4 (1 is an
error, 2 a bad command line; 3 was a halt on a rules divergence and is no longer used). With Docker's `unless-stopped` that is a clean
restart from the newest snapshot. It cannot loop on a healthy start: the watchdog starts counting only when the
node is following (not while it restores or replays), only a peer's announced tip ahead of ours counts, and
every block extended starts the count again. The time must be longer than `--stall-minutes`.

## Failures that stop the node (#154)

A peer session ends in one of a closed set of ways ([ADR 0017](../adr/0017-fibers-end-with-values.md)), and the
node does something different for each. A peer that breaks the protocol, sends an invalid header, an invalid
block (a block our rules refuse) or more unread bytes on a mini-protocol than its ingress limit allows is
forgotten by the governor (`reconnecting` with a `protocol:` or `invalid block` reason, `peerSelectionDemote`
with `forgotten`). A lost connection, a timeout, a peer behind us and a block that another peer delivered and our
rules refuse are tried again after the exit policy's delay. Four outcomes stop the node without attributing a failure to the peer:

| Outcome | `fatal` kind | Exit code |
|---|---|---|
| the storage failed under the session (a write, a read, a corrupt file) | `storage` | 5 |
| the node failed itself (a local chain model that does not hold, a forging fault, ...) | `internal` | 6 |
| an exception nobody expected (a bug, a library that threw) | `defect` | 7 |
| this build cannot evaluate a script or its encoding, during replay or live adoption; no ledger verdict exists | `unavailable` | 8 |

The run closes down as it does for a stall (the stores close, the shutdown snapshot is taken), logs one `fatal`
event, and exits with the code; a supervisor that restarts the node (Docker `unless-stopped`) brings it back from
the database, where the storage layer recovers as the reference's does (`consensusRethrowPolicy` makes a storage
failure `ShutdownNode`). Exit code 4 stays the stall's, 1 an error, 2 a bad command line. Fatal outcomes release the link and do not
produce peer-loss observations or reconnects. Exit 8 requires an implementation or configuration that can
evaluate the script; a restart alone does not add that capability. Expected transport `IOException`s remain
recoverable connection closures; unexpected network exceptions stop with exit 7.

The multiplexer's ingress limits are the reference's and are not configurable
(`IngressLimits`, ouroboros-network 1.2.0.0, network-mux 0.11.0.0): ChainSync 462,000 bytes, BlockFetch
23,068,694, TxSubmission2 721,424, KeepAlive 1,408, Handshake/PeerSharing 5,760; each node-to-client queue has the reference local-interface ceiling of 4,294,967,295 bytes. A
`--chain-sync-high` above the reference's pipelining high mark of 300 raises the ChainSync ingress limit with it.

An established connection may wait indefinitely between mux SDUs. Once header bytes arrive, that SDU
must complete within 30 s; trickled bytes do not restart the timer. A write gets 30 s per SDU in its batch,
or 10 s for handshake SDUs. Expiry is a typed transport failure handled by the normal reconnect policy.
The existing 10 s handshake receive phase also bounds an entirely silent peer. The outbound queue holds
at most 64 messages and sending waits for room.

Message byte limits apply to complete messages too, including when multiple messages arrive in one
SDU. TxSubmission keeps at most 10 unacknowledged IDs; repeated nonblocking requests cannot grow it
without acknowledgement. Duplicate/unadvertised body requests and excess/duplicate offers are protocol
violations. Body replies have 10 s; blocking ID waits remain indefinite. The existing local profile still
limits each reply to eight items/524,400bytes and each original transaction to 65,536 bytes. The
[network-limit matrix](../design/network-limits.md) gives every protocol state, the reference pin, queue
budgets and existing stricter local timeout policies.
