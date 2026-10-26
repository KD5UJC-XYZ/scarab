<!-- SPDX-License-Identifier: Apache-2.0 -->
# Peer selection (governor)

Part of the [compatibility matrix](../compatibility.md); the register entry is `peer-selection-governor` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented G1.1–G1.5 and #142 network follow-ups; original parent acceptance evidenced: local/bootstrap roots, ledger and big-ledger discovery, separate ordinary/big populations and targets, twelve-step churn, peer metrics, bootstrap sensitivity and Genesis State Machine, PeerSharing discovery and advertised-peer publication; UseLedgerPeers and peer-snapshot selection wired through typed read interfaces

## Evidence

reference governor properties on TestControl, 40 in-memory peers, 100,000-pool small-stack tests; relay/governor scenarios; Preview official configuration with no --peer for 1,860 seconds: tip reached, big-ledger known/established/active15/10/5, ledger map preferred over older snapshot, one twelve-step churn round; real mainnet/Preview registrations pin IPv6 as four little-endian words; extended decoder fuzz and full check pass (evidence below)

The retained PeerStateActions monitor and result/control handles follow ouroboros-network
`2fb69829` and the node exit policy at ouroboros-consensus `82ecba329`; the source correspondence
and API boundaries are in [the peer-selection design](../design/peer-selection/peer-state-actions.md).
`ProtocolResultsSuite` covers reference first/all-result selection, complete typed error aggregates,
and activation handle replacement. `OutboundLossReportSuite` checks one loss report at each release
stage across connection cancellation. `OutboundLinkIdentitySuite` covers successful normal return,
repromotion delay reset, stale commands after reconnect, immediate close of a Cooling connection,
and overlapping close/deactivate. `ConnectionMonitorSuite` checks retained-status reads, stale
notifications/completions, loss before establishment acceptance, and full error propagation
through the governor. Acquisition and resolution defects retain their original cause at both
the establishment caller and fatal sink. Controlled connected/report callback failures verify
partial-installation cleanup; cancellation before and after ready verifies the ownership transfer.
Loss-before-completion controls preserve both transient retry and misbehaviour-forgetting policy.
Three controlled teardown regressions cover normal close, installed-acquisition cancellation and
partial-acquisition cancellation, including exactly-once fatal publication after cleanup.
`ConnectionResourcesSuite` preserves ordinary/nested exit cases, synchronous release throws and
50,000 resource binds on a 256 KiB stack. A controlled runtime drains queued caller cancellation
while acquisition-error cleanup is held, then verifies the original fatal cause survives exactly once.

## Explicitly unchecked or unconfirmed

New sharing has actual private reference proof below; historical Preview covers unchanged earlier paths only. Genesis density and ledger-dependent fork choice are covered by the G3 row; BlockFetch peer coordination (#148) is merged in PR #289 with [native decision evidence](../../fixtures/reference/block-fetch-scheduling/README.md), combined local gates passed; shared inbound/outbound metrics with accepted-header timing controls (#150); native mode-aware churn and target lifecycle (#311); retained-window reintersection is covered by the [recovery contract](../design/network-limits.md#same-connection-chainsync-recovery-152) and deterministic duplex/gate/fault controls; live churn steps11/12 timed out below their targets, only one live round observed

These regressions do not establish the cause of #371. The full native governor monitor/job loop,
Mux and ConnectionManager alignment, and missing ChainSync producer result constructors/tip
context remain tracked in #477. The result handles preserve all values the current producer emits;
this is not a whole-ChainSync conformance claim.
