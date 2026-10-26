<!-- SPDX-License-Identifier: Apache-2.0 -->
# Peer selection (governor)

Part of the [compatibility matrix](../compatibility.md); the register entry is `peer-selection-governor` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented G1.1–G1.5 and #142 network follow-ups; original parent acceptance evidenced: local/bootstrap roots, ledger and big-ledger discovery, separate ordinary/big populations and targets, twelve-step churn, peer metrics, bootstrap sensitivity and Genesis State Machine, PeerSharing discovery and advertised-peer publication; UseLedgerPeers and peer-snapshot selection wired through typed read interfaces

## Evidence

reference governor properties on TestControl, 40 in-memory peers, 100,000-pool small-stack tests; relay/governor scenarios; Preview official configuration with no --peer for 1,860 seconds: tip reached, big-ledger known/established/active15/10/5, ledger map preferred over older snapshot, one twelve-step churn round; real mainnet/Preview registrations pin IPv6 as four little-endian words; extended decoder fuzz and full check pass (evidence below)

## Explicitly unchecked or unconfirmed

New sharing has actual private reference proof below; historical Preview covers unchanged earlier paths only. Genesis density and ledger-dependent fork choice are covered by the G3 row; BlockFetch peer coordination (#148) is merged in PR #289 with [native decision evidence](../../fixtures/reference/block-fetch-scheduling/README.md), combined local gates passed; shared inbound/outbound metrics with accepted-header timing controls (#150); native mode-aware churn and target lifecycle (#311); retained-window reintersection is covered by the [recovery contract](../design/network-limits.md#same-connection-chainsync-recovery-152) and deterministic duplex/gate/fault controls; live churn steps11/12 timed out below their targets, only one live round observed
