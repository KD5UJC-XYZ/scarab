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

- **The local socket's bind and path edges** (#541, 2026-10-27, recorded in #474 comments of 2026-10-28:
  [bind and listen](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6458538736),
  [path edges](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6459436297)). They were
  restated on 2026-11-13 in #949 ([its record](https://github.com/KD5UJC-XYZ/scarab/issues/949#issuecomment-6864911271)
  and [its correction](https://github.com/KD5UJC-XYZ/scarab/issues/949#issuecomment-6866875724)), after the
  sources and runs of cardano-node 11.1.3 found their first wording wrong at these edges. They are two
  deviations of the node-to-client socket:
  - **Bind and listen.** The JDK binds and listens in one call (OpenJDK jdk-25+36,
    `src/java.base/share/classes/sun/nio/ch/ServerSocketChannelImpl.java` 319-323). The reference binds,
    checks the socket file, and only then listens (`ouroboros-network/lib/Ouroboros/Network/Diffusion/Utils.hs`
    121-129). So a client may queue in the backlog during that check. A listen that fails is reported as the
    bind's failure, straight after `ConfiguringLocalSocket`. Whenever the listen succeeds, the traces and
    the failure handling are the reference's.
  - **Path length.** network 3.2.8.0 binds up to 107 characters (`Network/Socket/Types.hsc` 1159-1164).
    The JDK binds at most 106 bytes of path (`src/java.base/unix/native/libnio/ch/nio_util.h` 45-49,
    `UnixDomainSockets.c` 85-86), counted after it collapses repeated `/`
    (`src/java.base/unix/classes/sun/nio/fs/UnixPath.java` 75-123). So an ASCII path of 107 characters
    with no repeated `/` is refused where the reference binds it, as a run of cardano-node 11.1.3 shows.
    From 108 characters on, both refuse the path with `pokeSockAddr`'s error.

  Not taken for now: calling `socket`, `bind`, `listen` and `accept` through the foreign-function
  interface, which would remove both deviations.
- **A local socket path beyond ASCII** (#949, 2026-11-13, [recorded](https://github.com/KD5UJC-XYZ/scarab/issues/949#issuecomment-6866875724)):
  - **What the reference does.** Runs of cardano-node 11.1.3 with `café` paths show it.
    - Its bind makes the socket under the path's characters truncated to a byte each (`castCharToCChar`,
      `Types.hsc` 1160).
    - Its check of the socket at the path's own name then finds nothing.
    - It stops after `ConfiguringLocalSocket` with `<path>: getFileStatus: does not exist (No such file or
      directory)` and leaves the mis-named file.
    - Its length and trailing-`/` refusals come before all this. Runs show a 108-character path beyond
      ASCII stopping with `pokeSockAddr`'s error, and one ending in `/` failing at the bind.
  - **What Scarab does.** The owner decided that Scarab refuses every path beyond ASCII, an improvement on
    the reference. After the same length and trailing-`/` checks, it stops at that step with that error
    and binds nothing.
  - **Trade-off.** Scarab never makes the mis-named file, so on a second start, and in a directory that does
    not exist, it gives that first error. The reference gives its bind's: `Address in use` from the file its
    first start left, and `does not exist`.
- **`SocketPath` in the configuration file** (#949, 2026-11-13, same record):
  - **What the reference does.** It parses `SocketPath` (`cardano-node/src/Cardano/Node/Configuration/POM.hs`
    328, cardano-node 11.1.3) and never uses it: its command line's `SocketConfig`
    (`Cardano/Node/Parsers.hs` 90-95) replaces the file's in the merge (`Cardano/Node/Run.hs` 217). So its
    socket path comes from `--socket-path` only.
  - **What Scarab does.** `SocketPath` is the default of `--local-socket`, and the flag overrides it.
  - **A path that holds NUL.** It can reach Scarab only this way. The stale-socket removal refuses it before
    calling `unlink`, which would stop at the NUL and remove another file, with the `IOError` of unix
    2.8.6.0's `removeLink` (`useAsCStringSafe`, `System/Posix/PosixPath/FilePath.hsc` 154-171). So nothing
    is removed and the start is refused.
- **`ECONNABORTED` on accept** (#944, 2026-11-13, recorded in a
  [#944 comment](https://github.com/KD5UJC-XYZ/scarab/issues/944#issuecomment-6855601945) and
  [its scope correction](https://github.com/KD5UJC-XYZ/scarab/issues/944#issuecomment-6856168855)). The JDK's native
  accept restarts on `ECONNABORTED` (OpenJDK jdk-25+36, `src/java.base/unix/native/libnio/ch/Net.c`
  417-427, which `ServerSocketChannelImpl.implAccept` calls for the node-to-node listener's blocking TCP
  channel, `TcpSockets`). So the node-to-node server neither
  traces it (`TrAcceptError`) nor waits `server_CONNABORTED_DELAY` (500 ms) before the next accept, as the
  reference's accept loop does (`ouroboros-network/framework/lib/Ouroboros/Network/Server.hs` 81-84 and
  218-231). Every other accept failure is traced and stops the node, as the reference's does. The
  node-to-client socket's accept does not restart (`UnixDomainSockets.c` 162-169), so that server traces it
  and waits as the reference does. Trade-off: after an `ECONNABORTED` the server keeps accepting, as the
  reference's does. What is missing for the aborted attempt is that trace, that pause, and the rate-limit
  check the reference runs again before its next accept (`runConnectionRateLimits`, `Server.hs` 209-215
  and 226-228; in Scarab, `beforeAccept`). Calling `accept(2)` through the foreign-function
  interface would reproduce them; that is not taken for now.
