# 0008. Networking stays as implemented, not mirrored from Haskell

- **Status**: Accepted
- **Date**: 2026-10-15

## Context

[0001](0001-haskell-node-as-reference.md) makes the Haskell node the reference design. The networking
layer (fs2 bearer, multiplexer, mini-protocol state machines) already interoperates with Haskell nodes
in both directions and serves a Haskell relay; mirroring ouroboros-network's internal structure would be
a large rewrite with no compatibility gain.

## Decision

Networking keeps its own design. Compatibility is defined by the wire: the mini-protocol messages, the
handshake and the mux framing, checked against Haskell peers by the devnet scenarios. Peer governance
(G1) follows the Haskell behaviour where it is observable.

## Consequences

- Era indexing does not reach the mini-protocols: they move bytes and points; decoders at the edge
  produce era-tagged values.
