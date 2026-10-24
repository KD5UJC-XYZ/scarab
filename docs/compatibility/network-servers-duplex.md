<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network: servers (ChainSync, BlockFetch, KeepAlive, TxSubmission2, PeerSharing) and duplex

Part of the [compatibility matrix](../compatibility.md); the register entry is `network-servers-duplex` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: the first TxSubmission2 `Init` waits without a local deadline on an idle duplex bearer, matching the reference `StartOnDemand` behavior; active states remain bounded

## Evidence

unit/property (both directions, duplex, pipelining, timeouts, refusals; served-chain reconstruction property); devnet `relay` (Haskell relay synced solely from the Scala node, tip lag <= 1 in 59/59 samples)

## Explicitly unchecked or unconfirmed

on an inbound duplex connection the inbound governor runs our ChainSync, BlockFetch and KeepAlive clients as well (G2; devnet `relay` asserts it against the reference node); per-state byte/time, ingress and SDU bounds are recorded in [G5](../design/network-limits.md); PeerSharing runs on negotiated connections and publishes advertised, successful, zero-failure peers
