<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network: PeerSharing (mini-protocol 10, client and server)

Part of the [compatibility matrix](../compatibility.md); the register entry is `network-peersharing` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented (G1.4a/b): reference codec, 5760-byte/60-second wire limits, negotiated willingness, server publication of advertised successful zero-failure peers, 823-second salt and ten-peer cap; governor activation300/retry900/failure3600, at most two requests and objective-bounded 3/10-second completions; relay defaults on and producer defaults off, with explicit override

## Evidence

Unit/property, fixed-seed real-governor simulation, four-seed decoder fuzz, CDDL cross-check and actual nonempty two-way private Haskell discovery (evidence below and design7f). Original IPv4 words are converted to socket octets; IPv6 words retain network order

## Explicitly unchecked or unconfirmed

Decoder array arities are stricter than upstream; platform-specific address hashing uses Scala hashing with the same sticky-salt policy. Governor requests use the outbound live table; accepted-connection reuse for an outbound job remains a separate G2 question. Historical Preview predates the new policy; no public run of new sharing is claimed

## Differences decided on 2026-11-14 (#956)

- After a request that fails or times out, the peer is asked again 3600 s after the completion (`Policy.peerShareFailureRetryTime`). In ouroboros-network `c45735a5`, which cardano-node 11.1.3 links, the next request is due 900 s after the request starts, whatever its result. An approved deviation that follows ouroboros-network pull request [#5439](https://github.com/IntersectMBO/ouroboros-network/pull/5439) ([decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).
- A round's new peers are ordered by their hash under a fresh salt and cut to the objective (`PeerShare.Batch`, `PeerShare.completed`). `c45735a5` passes the objective where the salt goes and a random `Int` where the cap goes. A reference bug, fixed upstream by pull request [#5434](https://github.com/IntersectMBO/ouroboros-network/pull/5434), not reproduced ([#474 comment](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849), [decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).
