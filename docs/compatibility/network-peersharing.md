<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network: PeerSharing (mini-protocol 10, client and server)

Part of the [compatibility matrix](../compatibility.md); the register entry is `network-peersharing` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented (G1.4a/b): reference codec, 5760-byte/60-second wire limits, negotiated willingness, server publication of advertised successful zero-failure peers, 823-second salt and ten-peer cap; governor activation300/retry900/failure3600, at most two requests and objective-bounded 3/10-second completions; relay defaults on and producer defaults off, with explicit override

## Evidence

Unit/property, fixed-seed real-governor simulation, four-seed decoder fuzz, CDDL cross-check and actual nonempty two-way private Haskell discovery (evidence below and design7f). Original IPv4 words are converted to socket octets; IPv6 words retain network order

## Explicitly unchecked or unconfirmed

Decoder array arities are stricter than upstream; platform-specific address hashing uses Scala hashing with the same sticky-salt policy. Governor requests use the outbound live table; accepted-connection reuse for an outbound job remains a separate G2 question. Historical Preview predates the new policy; no public run of new sharing is claimed

## Inbound peers

The outbound governor's inbound peers are the inbound governor's mature duplex peers, so an address that no other
source made known is learnt through inbound discovery only once some registration of it still in force was handled at
least 900 s earlier. The inbound governor's steps register the peer of a duplex connection as fresh, and each step
moves the peers fresh for 900 s or more, by the time the step read before it waited, to the mature ones
(`DuplexPeers`, `PeerSharingRuntime.maturing`), with the fresh and mature sets, `maturedPeers`,
`inboundMaturePeerDelay` and `inactionTimeout` of ouroboros-network `c45735a5`, `4646039f` and `2f1c154f`
(`InboundGovernor.hs` and `InboundGovernor/State.hs`). A step runs on each registration or unregistration and, when
none comes, 31.415927 s after the last one began. A step's wait takes no event, so a timeout that ends it as an event
arrives loses none.

The timing of the steps differs from the reference's, an approved deviation
([decisions](../decisions.md#networking-and-local-clients), 2026-11-19). The reference's inbound governor also takes a
step on its connections' other events (their responders and the peer's activity), which Scarab's handles per
connection, outside these steps. And Scarab stamps a registration with the time its step handles it, read after the
wait, where the reference stamps it with the step's time read before the wait. So an address becomes mature only once
some registration of it still in force has been handled at least 900 s earlier, and a single registration that no
other replaces matures within one more inactivity timeout (31.415927 s) when the steps run promptly. Derived from the
source, not measured: for such a registration, with both nodes' steps prompt, Scarab matures the address less than one
inactivity timeout earlier than the reference or less than two later. When another connection from the address
registers while the address is fresh in one node and already mature in the other, the fresh one starts its 900 s
again, so the two can differ by up to about that delay.

The inbound governor tracks the connections the server accepts, of either data flow, once the manager has published
them, and, when the node listens, those it dials whose handshake agrees on duplex, as the reference's connection
manager announces them to its inbound governor in responder mode (`NewConnection`, `ConnectionManager/Core.hs` 982-995
and 1594-1630 at the three pins). A duplex one registers its peer. Scarab used to register only the connections it
accepted; dialled duplex connections are now registered as well (`OutboundPeers`). A dialled connection that the
accepting side of a connection to ourselves took is announced by that side only, as in the reference
(`Announcement`, 1622-1630). A second connection from the address of a fresh peer restarts its maturation, one from
the address of a mature peer leaves it mature, and the end of any tracked connection from the address, unidirectional
ones included, removes the peer (`unregisterConnection`, `InboundGovernor/State.hs` 208-222 at `c45735a5`, 201-215 at
the other two pins).

The delay governs that discovery only: an address known from another source is shared by the usual criteria of a
known peer, advertised, connected successfully and without failures (`getPeerSharingResponsePeers`,
`State/KnownPeers.hs` 580-592 at the same pins). Evidence: `DuplexPeersSuite`, `InboundMaturitySuite`,
`PeerSharingRuntimeSuite`, `InboundPeerMaturitySuite`, `OutboundDuplexPeerSuite`, `ConnectionManagerSuite` and
`AcceptPublicationSuite`.

## Differences decided on 2026-11-14 (#956)

- After a request that fails or times out, the peer is asked again 3600 s after the completion (`Policy.peerShareFailureRetryTime`). In ouroboros-network `c45735a5`, which cardano-node 11.1.3 links, the next request is due 900 s after the request starts, whatever its result. An approved deviation that follows ouroboros-network pull request [#5439](https://github.com/IntersectMBO/ouroboros-network/pull/5439) ([decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).
- A round's new peers are ordered by their hash under a fresh salt and cut to the objective (`PeerShare.Batch`, `PeerShare.completed`). `c45735a5` passes the objective where the salt goes and a random `Int` where the cap goes. A reference bug, fixed upstream by pull request [#5434](https://github.com/IntersectMBO/ouroboros-network/pull/5434), not reproduced ([#474 comment](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849), [decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).
