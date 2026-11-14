<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network: servers (ChainSync, BlockFetch, KeepAlive, TxSubmission2, PeerSharing) and duplex

Part of the [compatibility matrix](../compatibility.md); the register entry is `network-servers-duplex` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: the first TxSubmission2 `Init` waits without a local deadline on an idle duplex bearer, matching the reference `StartOnDemand` behavior; active states remain bounded

## Evidence

unit/property (both directions, duplex, pipelining, timeouts, refusals; served-chain reconstruction property); devnet `relay` (Haskell relay synced solely from the Scala node, tip lag <= 1 in 59/59 samples)

## Explicitly unchecked or unconfirmed

on an inbound duplex connection the inbound governor runs our ChainSync, BlockFetch and KeepAlive clients as well (G2; devnet `relay` asserts it against the reference node); per-state byte/time, ingress and SDU bounds are recorded in [G5](../design/network-limits.md); PeerSharing runs on negotiated connections and publishes advertised, successful, zero-failure peers

## Differences decided on 2026-11-14 (#956)

- A responder that starts before the inbound governor records its connection is counted, as the counters exist from `NodeToNodeServer.Responders.create`. In ouroboros-network `c45735a5`, which cardano-node 11.1.3 links, the inbound governor's mux tracer finds no connection for such a responder and empties the connection's counters, after which its responders are no longer restarted nor its remote state updated. A race, fixed upstream by pull request [#5440](https://github.com/IntersectMBO/ouroboros-network/pull/5440), not reproduced ([#474 comment](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849), [decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).
