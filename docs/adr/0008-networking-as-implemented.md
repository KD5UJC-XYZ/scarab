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

## Amendment: internal structure is our own, observable policy is mirrored with pins

Mirroring concerns what a peer or an operator can observe, not how the code is organised. The
internal structure (fs2 bearer, `Mux`, `MuxEgress`, the connection manager's table) stays our own.
Observable policy (timers, limits, defaults that change what a remote peer experiences) is taken
from the reference at the pin below (ouroboros-network `2fb69829`), cited where it is implemented,
and a difference is recorded here with its reason.

| Policy | Reference | Scarab |
|---|---|---|
| Inbound following | Clients run on connections the peer governor chose, up to its targets. | `--inbound-sync` is off by default; `on` is an explicit opt-in. |
| Protocol idle timeout | `defaultProtocolIdleTimeout = 5` s (`framework/lib/Ouroboros/Network/ConnectionManager/Core.hs`); the inbound governor starts an accepted connection in `RemoteIdle` with `registerDelay idleTimeout` and releases it on expiry (`InboundGovernor.hs`). | `NodeToNodeServer.Settings.protocolIdleTimeout = 5.seconds`, covering the first window after the handshake. The re-arm when the last responder terminates (`WaitIdleRemote`) is not implemented. |
| Time-wait | `defaultTimeWaitTimeout = 60` s keeps a closed connection's identity in `TerminatingState`. | Not adopted: the manager removes the entry on release. A recorded deviation. |
| Accepted-connection limit | `AcceptedConnectionsLimit`: hard 512, soft 384, delay 5 s (`Server/RateLimiting.hs`, `Diffusion/Configuration.hs`). | ~~`maxInbound` (default 64) is a hard limit only; aligning it is tracked in #339.~~ ([amended 2026-10-25](#amendment-2026-10-25-accepted-connection-limits-as-the-reference)) |
| Per-address limit | None. | `maxInboundPerAddress` (default ~~8~~ ([amended 2026-10-25](#amendment-2026-10-25-accepted-connection-limits-as-the-reference))), a typed `ConnectionError.AddressLimit`, counted per IP address inside the atomic admission. |
| Egress buffering | `egressSoftBufferLimit = 0x3ffff` bytes per channel (mini-protocol and direction), soft: `send` appends while fewer are buffered (`network-mux/src/Network/Mux.hs`, `muxChannel`). | `Mux.Config.egressBufferBytes = 0x3ffff`, per lane, released per SDU written. |

The per-address limit is a deliberate local addition. The reference limits accepted connections only
as a whole, so one remote address can hold every slot for as long as its connections stay open. The
protocol idle timeout bounds how long a silent connection holds one, and the per-address cap bounds
how many slots one address can hold while it speaks just often enough to stay. ~~Eight is below the
default of 64 so that several peers behind one address (a shared outbound address of a hosting
provider) still fit, and above what one peer needs.~~ It is
local policy; ~~when the global limit is aligned with the reference (#339) the value is revisited.~~
([amended 2026-10-25](#amendment-2026-10-25-accepted-connection-limits-as-the-reference))

## Amendment (2026-10-25): accepted-connection limits as the reference

The accepted-connection limit is the reference's `AcceptedConnectionsLimit` (ouroboros-network
2fb69829, `Server/RateLimiting.hs`): hard 512, soft 384, delay 5 s by default
(`Diffusion/Configuration.hs`, `defaultAcceptedConnectionsLimit`). The accept loop runs
`getRateLimitDecision` and `runConnectionRateLimits` before each accept (`Server.hs`, `acceptLoop`),
and the connection manager refuses a connection past the hard limit (`Core.hs`,
`includeInboundConnectionImpl`). The configuration's `AcceptedConnectionsLimit` (`hardLimit`,
`softLimit`, `delay`: `Word32`, `Word32` and `DiffTime`, decoded as the reference decodes them) sets
them, and `--max-inbound` overrides the hard limit. The three policy traces are `connection` log
lines. The count is of inbound connections only; the reference also counts the outbound duplex
connections its peer uses (`ConnectionManager/Core.hs:209-212`), which is left to the
connection-manager alignment (N-2 on #477).

The per-address limit stays, with a default of 64. Deviation approved by the owner on 2026-10-25: a
per-address inbound limit (default 64) on top of the reference's global accept limits, protecting
inbound slots from a single address; the reference has no per-address limit. It stays configurable
(`ConnectionManager.Limits.maxInboundPerAddress`).

## Consequences

- Era indexing does not reach the mini-protocols: they move bytes and points; decoders at the edge
  produce era-tagged values.
