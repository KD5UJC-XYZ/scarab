# 0008. Networking mirrors ouroboros-network

- **Status**: Accepted
- **Date**: 2026-10-25
- **Issue**: [#477](https://github.com/KD5UJC-XYZ/scarab/issues/477) (alignment plan)

## Context

Wire compatibility alone is not enough for the network layer: a structure of its own lets behaviour
drift from the reference where the wire does not show it. Two structural divergences show this:
[#430](https://github.com/KD5UJC-XYZ/scarab/issues/430) (offer-driven fetch logic instead of
the `fetchDecisions` loop) and [#448](https://github.com/KD5UJC-XYZ/scarab/issues/448) (two loss
reporters instead of one `peerMonitoringLoop`). The alignment plan is #477.

## Decision

The networking layer mirrors ouroboros-network `2fb69829` in both structure and behaviour, as
[ADR 0001](0001-haskell-node-as-reference.md) requires everywhere else. Each component cites its
reference module at that pin:

| Component | Reference module |
|---|---|
| Mux | `network-mux/src/Network/Mux.hs` |
| ConnectionManager | `ouroboros-network/framework/lib/Ouroboros/Network/ConnectionManager/Core.hs` |
| InboundGovernor | `ouroboros-network/framework/lib/Ouroboros/Network/InboundGovernor.hs` |
| Server | `ouroboros-network/framework/lib/Ouroboros/Network/Server.hs` and `Server/RateLimiting.hs` |
| PeerSelection governor | `ouroboros-network/lib/Ouroboros/Network/PeerSelection/Governor.hs` and `Governor/Monitor.hs` |
| PeerStateActions | `ouroboros-network/lib/Ouroboros/Network/PeerSelection/PeerStateActions.hs` |
| BlockFetch | `ouroboros-network/lib/Ouroboros/Network/BlockFetch.hs`, `BlockFetch/Decision.hs`, `BlockFetch/ClientState.hs` |
| ChainSync | `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/{Type,Client,Server}.hs` |

Agents may not deviate on their own accord. A deviation needs the owner's explicit approval first,
as ADR 0001 says.

## Approved deviations

The only owner-approved network deviations (decisions of 2026-10-25, recorded in
[#474](https://github.com/KD5UJC-XYZ/scarab/issues/474)):

- **Per-address inbound limit** (row 20). Scarab keeps a limit on inbound connections per remote
  address (`maxInboundPerAddress`, default raised from 8 to 64), beyond the reference's global
  accepted-connection limits (`Server/RateLimiting.hs`). Trade-off: it protects inbound slots from
  a single address.
- **MurmurHash3 peer-share order** (row 29). The salted order of a peer-share answer is derived with
  MurmurHash3 instead of the reference's `hashWithSalt`. Trade-off: the reference's order is keyed by
  a secret random per-node salt, so peers cannot observe the hash function; mirroring it would mean
  reimplementing version- and platform-dependent `hashable` internals.
- **LocalStateQuery nesting bound** (row 35). A client's query is bounded in nesting depth
  (32,767); the reference has no bound. Trade-off: it guards the JVM against a stack overflow on a
  deeply nested query, and it applies to the local socket only.

## Consequences

- A difference from the reference in the network layer is a defect to align, tracked in #474 and #477,
  unless it is listed above.
- Era indexing does not reach the mini-protocols: they move bytes and points; decoders at the edge
  produce era-tagged values.
