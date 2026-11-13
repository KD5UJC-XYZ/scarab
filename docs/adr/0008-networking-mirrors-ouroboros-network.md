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

~~The only~~ ([superseded](#amendment-2026-11-13-later-approved-deviations)) owner-approved network deviations (decisions of 2026-10-25, recorded in
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
  unless it is listed ~~above~~ ([superseded](#amendment-2026-11-13-later-approved-deviations)).
- Era indexing does not reach the mini-protocols: they move bytes and points; decoders at the edge
  produce era-tagged values.

## Amendment (2026-11-13): later approved deviations

The approved network deviations are those of [Approved deviations](#approved-deviations) and these, each
taken by the owner after 2026-10-25 and indexed in
[decisions.md](../decisions.md#networking-and-local-clients):

- **The local socket's bind and path edges** (#541, 2026-10-27): two deviations of the node-to-client
  socket, one where the JDK binds and listens in one call and one at the length and encoding edges of its
  path, as the [decisions register](../decisions.md#networking-and-local-clients) states them and #474
  comments of 2026-10-28 record them ([bind and listen](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6458538736),
  [path edges](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6459436297)).
- **`ECONNABORTED` on accept** (#944, 2026-11-13, recorded in a
  [#944 comment](https://github.com/KD5UJC-XYZ/scarab/issues/944#issuecomment-6855601945) and
  [its scope correction](https://github.com/KD5UJC-XYZ/scarab/issues/944#issuecomment-6856168855)). The JDK's native
  accept restarts on `ECONNABORTED` (OpenJDK jdk-25+36, `src/java.base/unix/native/libnio/ch/Net.c`
  417-427, which `UnixAsynchronousServerSocketChannelImpl` calls for TCP). So the node-to-node server neither
  traces it (`TrAcceptError`) nor waits `server_CONNABORTED_DELAY` (500 ms) before the next accept, as the
  reference's accept loop does (`ouroboros-network/framework/lib/Ouroboros/Network/Server.hs` 81-84 and
  218-231). Every other accept failure is traced and stops the node, as the reference's does. The
  node-to-client socket's accept does not restart (`UnixDomainSockets.c` 162-169), so that server traces it
  and waits as the reference does. Trade-off: after an `ECONNABORTED` the server keeps accepting, as the
  reference's does. What is missing for the aborted attempt is that trace, that pause, and the rate-limit
  check the reference runs again before its next accept (`runConnectionRateLimits`, `Server.hs` 209-215
  and 226-228; in Scarab, `beforeAccept`). Calling `accept(2)` through the foreign-function
  interface would reproduce them; that is not taken for now.
