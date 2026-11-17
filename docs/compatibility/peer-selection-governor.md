<!-- SPDX-License-Identifier: Apache-2.0 -->
# Peer selection (governor)

Part of the [compatibility matrix](../compatibility.md); the register entry is `peer-selection-governor` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented G1.1–G1.5 and #142 network follow-ups; original parent acceptance evidenced: local/bootstrap roots, ledger and big-ledger discovery, separate ordinary/big populations and targets, twelve-step churn, peer metrics, bootstrap sensitivity and Genesis State Machine, PeerSharing discovery and advertised-peer publication; UseLedgerPeers and peer-snapshot selection wired through typed read interfaces

## Evidence

reference governor properties on TestControl, 40 in-memory peers, 100,000-pool small-stack tests; relay/governor scenarios; Preview official configuration with no --peer for 1,860 seconds: tip reached, big-ledger known/established/active15/10/5, ledger map preferred over older snapshot, one twelve-step churn round; real mainnet/Preview registrations pin IPv6 as four little-endian words; extended decoder fuzz and full check pass (evidence below)

The retained PeerStateActions monitor and result/control handles follow ouroboros-network
`c45735a5` and the node exit policy at ouroboros-consensus `82ecba329`; the source correspondence
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

## Differences decided on 2026-11-14 (#956)

- Warm-to-hot promotion quarters the random draw of each peer that is not tepid (`Picks.warmPromotion`). ouroboros-network `c45735a5`, which cardano-node 11.1.3 links, promotes uniformly. An approved deviation that follows ouroboros-network pull request [#5437](https://github.com/IntersectMBO/ouroboros-network/pull/5437) ([decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).
- The hot-demotion log lines carry each candidate's score (`scores`); in `c45735a5` the hot-demotion traces carry none, and the ranking is the same. An approved deviation that follows pull request [#5437](https://github.com/IntersectMBO/ouroboros-network/pull/5437) ([decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).
- A topology reconfiguration keeps the last local-root DNS answer of each name the new topology still has (`LocalRootDnsCache.reconfigure`). `c45735a5` starts every domain with no addresses. An approved deviation that follows pull request [#5455](https://github.com/IntersectMBO/ouroboros-network/pull/5455), in neither `c45735a5` nor `2fb69829` ([decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).
- A reload publishes the topology's IP roots and the kept DNS answers at once (`PeerNetwork`); in `c45735a5` a reconfiguration publishes nothing until a domain's lookup answers, so a topology with no DNS names is not applied. A bug of the linked packages, not reproduced: fixed upstream by pull request [#5455](https://github.com/IntersectMBO/ouroboros-network/pull/5455) ([decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).

## Reading the topology (#924)

The topology is read as cardano-node 11.1.3's `readTopologyFile` reads it
(`cardano-node/src/Cardano/Node/Configuration/TopologyP2P.hs` 53-140), at start-up and at each SIGHUP reload, checked
by reading the pinned source: `TopologyNotice.read` gives its advice (lines 66-100) and, in Genesis mode, drops its
`bootstrapPeers` (103-108, 139-140) with the reference's `NetworkConfigUpdateWarning` text, logged as
`networkConfigUpdateWarning` or `networkConfigUpdateInfo`. A topology with no root is not refused, as the reference
does not refuse one; an empty `bootstrapPeers` needs a trustable local root group that names an access point
(`isValidTrustedPeerConfiguration`, cardano-diffusion 1.1.1.0 `Cardano/Network/Diffusion/Topology.hs` 19-40). In
Genesis mode an unreadable `peerSnapshotFile` stops the node at start, as `updateLedgerPeerSnapshot` does at start-up
(`Run.hs` 715-736). `TopologyNoticeSuite` and `ConfigSuite` (the official mainnet, preprod and preview topologies in
Genesis mode) cover it. The start-up was also compared by running both nodes with no network on the official mainnet
configuration and topology, with and without the `peer-snapshot.json` it names, and on the reference's
`testnet-template-topology-empty-p2p.json` (2026-11-16): the same warnings, the same stop without the snapshot.
At start-up only, a block producer with `PeerSharing` on gets the reference's `NetworkConfigUpdateWarning` of
`handleSimpleNode` (`Run.hs` 360-366), after the topology's notices and before the snapshot is read (checked by reading,
and by running cardano-node 11.1.3 as a producer on 2026-11-17); `NetworkConfigSuite` covers it.
A SIGHUP reload installs the new topology, then reads the peer snapshot on its own, as the reference's handlers do
(`Run.hs` 413-430 before consensus has initialised, 596-614 after, 673-757; checked by reading; what the runtime handler
traces and writes to standard error checked by running cardano-node 11.1.3 on 2026-11-17,
[#924](https://github.com/KD5UJC-XYZ/scarab/issues/924#issuecomment-6933331883); the first handler was not run).
The new topology is installed once, a root generation of its own, before the file is read, as the reference writes the
topology's variables (697-702) before it reads the file (725-731). The snapshot read is then written, `None` included,
to what the ledger-peers worker reads (757): the worker takes it at its next rebuild of the stake maps, as the
reference's thread reads `wlpGetLedgerPeerSnapshot` only once its cache has aged out (ouroboros-network c45735a5
`PeerSelection/LedgerPeers.hs` 277-287); the write makes no root generation and no new worker. In Genesis mode the
write arms the immutable-chain check again for a snapshot at another block point than the one taken last, and not for
`None` or the same point, as `ledgerPeerSnapshotChange` takes a snapshot (`Governor/Monitor.hs` 432-459,
cardano-diffusion `Governor/Types.hs` 172-173). The topology installed before it arms the check once per root
generation, as every installation did before, for the snapshot taken last; so a reload that changes the snapshot
checks the point taken before, then the new one. The reference launches `jobVerifyPeerSnapshot` only when the
judgement changes (cardano-diffusion `Governor/Monitor.hs` 515-533), not when the roots do. `snapshotLoaded` or
`snapshotIgnored` is said where the file is read, at start-up and at a reload, not for a snapshot kept or dropped.
`NetworkConfigSuite` and `LedgerPeersWiringSuite` show one generation per reload, a worker taking a new snapshot at its
next rebuild, the write arming the check again only for another point, and an installation arming it once.
At runtime a snapshot file it cannot read in Genesis mode, or one of a legacy version, at the origin or of another
network, is dropped: the new topology stays with no snapshot, and nothing is said, since the reference's
`NetworkConfigUpdateError` there is discarded (`Run.hs` 720-722, 734-736). In Praos mode a file it cannot read is
ignored by both, at start-up and at either handler's reload, written as no snapshot (757); Scarab says so
(`snapshotIgnored`), the reference does not (736). A snapshot older than `useLedgerAfterSlot` keeps the snapshot in
force under the new topology. Before consensus has initialised, a file it cannot read in Genesis mode, one of a legacy
version, at the origin, of another network or older than `useLedgerAfterSlot` keeps the snapshot in force, and the
handler stops before `BlockForgingUpdate NotEffective` (by reading); after an unreadable file in Praos mode
`NotEffective` follows. A topology that cannot be read changes nothing, and the snapshot is not read again: the
reference's `error` there escapes the `try` meant to catch it (`Run.hs` 684-689). `NetworkConfigSuite` and
`PeerSnapshotUseSuite` cover each case. The reference writes a refusal it throws to standard error; Scarab logs it as
a warning; this difference, and the reference's `NetworkConfigUpdate`, `NetworkConfig` and `LedgerPeerSnapshot` traces
Scarab does not give, are a gap ([#924](https://github.com/KD5UJC-XYZ/scarab/issues/924#issuecomment-6958258243)).
