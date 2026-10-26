<!-- SPDX-License-Identifier: Apache-2.0 -->
# G1.5 and the topology, DNS and duplex work as built

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 7g. G1.5 (#139) and topology/DNS/duplex (#142) as built

**#139 Genesis hooks.** The governor receives `ConsensusMode` and the Genesis State Machine judgement through
its typed configuration. While `GenesisMode` is `TooOld`, the sync targets are used by both churn and peer
selection; `YoungEnough` restores the deadline targets. The monitor emits trusted-state output from the configured
minimum of active big-ledger peers, and verifies the configured peer snapshot against the exact immutable slot
and hash when the Genesis judgement enters `TooOld`. The immutable metadata wait is cancelable, does not read block
bodies, and reports real storage failures through the node's typed fatal boundary. Genesis refuses `bootstrapPeers`
as required by the reference. `GenesisMode` remains a partial mode: chain density, ledger-dependent fork choice
and the trusted-state consensus consumer remain G3 (#33); Praos defaults are unchanged.

**#142 topology and outbound networking.** SIGHUP re-reads the original topology path and preserves the original
CLI roots separately from dynamic topology roots. Parse failure leaves the active configuration usable. Successful
reload installs runtime and governor roots under an acknowledged owner event; work from older generations cannot
publish roots, DNS answers, snapshot verdicts or outbound links after a newer generation is installed. Hostname and
SRV inputs, group valencies, advertise flags and alias provenance are retained across refresh. SRV queries for
topology and ledger relays follow the pinned policy in section 6.1. Outbound connections configured as duplex run
the responder side over the negotiated bearer; link-table registration and cleanup compare attempt identity so a
late old handshake cannot overwrite or remove the new link.

**Focused evidence.** `ConfigSuite`, `PeerSharingLoopSuite`, `PeerDnsSuite`, `LedgerPeersWiringSuite`,
`ImmutablePeerSnapshotVerifierSuite`, `OutboundLinkIdentitySuite`, `InboundGovernorSuite` and `LogGoldenSuite`
cover configuration, generation ordering, per-source DNS deadlines and failure retention, SRV provenance/TTL,
exact snapshot slot-and-hash comparison, stale link completion, duplex protocol service and the diagnostic event.
At source `0be9982a`, Docker `check` passed 4,270 tests with 16 optional skips and no failures; explicit Scalafix
also passed. Two private `relay --seconds 300` attempts passed sync, duplex, header-following and final-tip agreement,
but failed the transaction-inclusion assertions: three transactions reached the Scala mempool, none were adopted, and
the local submission was not included. Exact summaries are preserved under `/tmp/scarab/scarab-v02-139-142` and
`/tmp/scarab/scarab-v02-139-142-repeat`. No public sync or DNSSEC claim is made.

### Atomic local-root refresh (#240)

A completed DNS refresh installs configured and effective local roots, known-peer origins,
removed-hot-peer demotion jobs and bootstrap sensitivity in one pure governor decision. The
event boundary must satisfy `GovernorState.invariant` before the next monitor pass. This follows
`G/Monitor.hs:329–425` at the section 1.1 pin: insert newly resolved roots into known peers, retain
removed peers as non-roots and demote removed active roots so their replacements can activate.
Trustable-root filtering precedes the known-peer cap, as in
`CD/PeerSelection/Governor/Monitor.hs:159–169`. Target-transition behavior and generation fencing
for stale/in-flight completions are unchanged.

`PeerSelectionSuite` retains four direct controls: replacing an active root, retaining an address
in the new answer set, sensitive trustable filtering and resumption from zero targets. Three
controls fail on the previous source; the zero-target sequence already passed. The private relay
gate verifies normal operation, not forced DNS rotation; [compatibility evidence](../../compatibility.md)
records its scope and the integrated check.
