<!-- SPDX-License-Identifier: Apache-2.0 -->
# G1.4 as built

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 7c. G1.4a as built

Branch `g1/4a-peersharing-protocol`. The protocol half of G1.4 ([#138](https://github.com/KD5UJC-XYZ/scarab/issues/138)): everything
up to the governor. G1.4b (below) wires it.

**Files.** `protocol/.../PeerSharing.scala` (states `Idle`/`Busy`/`Done`, messages, the codec, `Limits`, typed `Error` and
`Violation`, the opaque `Amount`, `Word32`, `Port` and `Address`), `network/.../PeerSharingClient.scala`,
`network/.../PeerSharingServer.scala`, `network/.../selection/PeerShare.scala` (the pure server policy). Shared files, each
edit additive: `NetworkError.scala` (`Protocols.PeerSharing = 10`), `NodeToNode.scala` (`Settings.offerPeerSharing`,
`WarmConnection.peerSharing`, the offer in the handshake, `warm`/`clients` take `peerSharing: Boolean = false`),
`NodeToNodeServer.scala` (`ServerHandlers.peerSharing`, `Settings.offerPeerSharing`, protocol 10 in the mux set, the server runs
when the connection agreed), `HandshakeServer.offers`, `Main.scala` (two settings), `NodeConfig.scala` (the text of the
`unsupported` entry), `models.conf` (module and root), the fuzz `Targets` and `Corpus`, the devnet `relay` scenario.

**Citations** (ouroboros-network at `2fb69829`, the commit of the vendored CDDL; the files are those of the 1.2.0.0 tag of section 1.1):
`ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/PeerSharing/Type.hs:29,45-58,74-95`; `.../PeerSharing/Codec.hs:42-93`
(messages), `127-128` (5760 bytes), `133-140` (size limits), `153-162` (time limits), `Protocol/Limits.hs:114-115`
(`longWait` 60 s); `ouroboros-network/api/lib/Ouroboros/Network/PeerSelection/PeerSharing/Codec.hs:29-65` (addresses);
`ouroboros-network/lib/Ouroboros/Network/PeerSharing.hs:126-131` (the client's check), `168-174`, `203-237` (the server);
`PS/State/KnownPeers.hs:580-592`; `cardano-diffusion/lib/Cardano/Network/NodeToNode.hs:310-325` (the protocol is an established
protocol, started on demand, only when the negotiated flag is on).

**What differs from the reference.**

1. The decoder checks the arity of the outer array (2, 2, 1) and of an address (3, 6); the reference ignores the declared length
   (`decodeListLen`). Everything else the reference takes is taken: unsigned integers in any definite width, a definite or an
   indefinite list of peers. The encoder writes what the reference writes (an empty list definite, otherwise indefinite).
2. Errors are the closed `PeerSharing.Error`; the multiplexer sees the message of the error (`DecodeResult.Failed`). A server cuts an
   answer to the amount asked for and to what fits 5760 bytes instead of failing, whatever its source returns.
3. The salt of the server policy mirrors the reference's quirk: the request that re-salts is answered under the draw the
   generator made, the generator is stored advanced, and the other requests of the 823 s read the first draw of the advanced
   generator (`PeerSharing.hs:219-227`), so the salt of an interval is read by the request that opens the next one. `PeerShare.Salting`
   is the pure state; the hash is MurmurHash3 of the address bytes under the salt (the reference's `hashWithSalt` of a `SockAddr`
   cannot be matched, and nothing depends on it: only that the order is a function of the salt). Deviation approved by the owner
   on 2026-10-25: MurmurHash3 for the salted peer-share order; trade-off: the reference's `hashWithSalt` order is keyed by a secret
   random per-node salt, so peers cannot observe the hash function, and mirroring it would mean reimplementing version- and
   platform-dependent `hashable` internals.
4. `PeerSharing: true` stays in `ConfigSettings.unsupported`, with a text that says what is missing: the flag is offered and the
   protocol answered, but the governor does not ask and the server has nothing to share (see G1.4b).

**Tests.** `protocol`: `PeerSharingSuite` (literals written from the reference codec, every truncation, ranges, arity, agency,
the byte limit both ways, a round-trip property and a totality property). `network`: `PeerSharingSuite` (in-memory bearers: the
answers, the cut to the amount and to 5760 bytes, `Done` and a second conversation, more peers than asked for, an oversize answer,
no answer within 60 s on `TestControl`, no timeout while idle, the flag as the conjunction on a whole connection),
`selection/PeerShareSuite` (the shareable set, `min(amount, 10)`, the order independent of the arrival order, one peer changes at
most one peer, the salt for 823 s). `models`: `PeerSharingEquivalenceSuite` (300 messages sampled from the CDDL decode the same in
the hand-written and the generated codec) and the root in `SampledSuite` and the cuddle cross-check. `fuzz`: target `peersharing`.

**Live check** (devnet `relay --seconds 300`, three Haskell pools, a Haskell relay whose only peer is the Scala node).

- The devnet configuration of `cardano-testnet` has `"PeerSharing": false`, so the relay ran with `ncPeerSharing = PeerSharingDisabled`
  (the reference's default for a node without producer credentials is on, `POM.hs:899-905`, but an explicit `false` wins) and the
  handshake agreed it off. The `relay` scenario now starts the relay on a copy of the configuration with `PeerSharing: true`
  and `advertise: true` on its local root, and the Scala node with `PeerSharing: true` (`--no-peer-sharing` leaves both off); the
  pools keep theirs. The relay's handshake then agrees `PeerSharingEnabled`.
- *Scarab asks the Haskell node.* `PeerSharingProbe` (test sources of `network`, a throwaway tool like `LeiosProbe`) connected to the
  relay, offered the flag, agreed it (version 14) and asked twice for 10 peers: each time the relay answered one address, its
  only known peer, the Scala node at `127.0.0.1:3350`. Byte order: the word arrived as `0x0100007f`, not `0x7f000001`. The
  reference writes the `HostAddress` of `network` as it is in memory, so an IPv4 word is little-endian on the usual hosts; an IPv6
  address is four words in order. The codec keeps the words as they are on the wire; G1.4b must swap the octets of an IPv4 word
  (and only IPv4) when it converts to and from a socket address.
- *The Haskell node asks Scarab.* With the relay's `Net.PeerSelection` traces at Debug, 300 s after it connected (the activation
  delay, `Policies.hs:169`) the relay logged `PeerShareRequests` (149 requested, target known 150, the Scala node selected) and
  `PeerShareResults` with `{"Right": []}`: our server decoded the request and its answer, an empty list, decoded in the relay. No decoder failure
  in the relay's log. The answer is empty because nothing supplies the peers yet (G1.4b); the relay's `known` did not grow, as expected.
- The gate itself passed with peer sharing on: 58 of 58 samples within one block, 239 transactions submitted through the relay, 240 in
  the Scala mempool, 237 included, no error event.



**G1.4a handoff: what G1.4b must wire.** Completed by section 7f.

- `PeerRecord` has no `advertise` field, so `PeerShare.shareable` cannot be fed from the governor state; add it (from `advertise`
  of the topology's roots; a peer learnt by peer sharing is not advertised unless the reference does so: check
  `KnownPeers.insert` there) and publish `Vector[Candidate[Address]]`, the reference's `PublicPeerSelectionState`, from the loop.
- `ServerHandlers.peerSharing` (a `PeerSharingServer.Source`) is `None` in `Main`: build it from that set, a `Salting` in a `Ref`
  and the clock, with `PeerShare.answer`.
- The conversion between `SocketAddress` and `PeerSharing.Address` (see the IPv4 note in the live check), and `advertise` honoured
  in the topology.
- `InboundGovernor` calls `NodeToNode.clients` without `peerSharing`; pass `agreed.data.peerSharing` so an inbound duplex connection
  has a client (the "inbound peers" of the reference's job). Outbound connections have it in `WarmConnection.peerSharing`.
- `KnownPeers.belowTarget` (coin flip, two stages, 900 s retry, 300 s activation delay, 2 in flight, `min 255 (max 8 ...)`) and
  the results filter, in `PeerSelection.decide` after G1.2. The client's errors must come back as `Either` at the fiber boundary
  (#132); `PeerSharingClient.request` raises `NetworkError` through `Raise` like the other clients.
- Defaults: with no `PeerSharing` key the reference is on for a node without producer credentials (`POM.hs:899-905`); Scarab is off
  until the above works. Remove the `unsupported` entry then.

## 7f. G1.4b governor and wiring

Issue [#138](https://github.com/KD5UJC-XYZ/scarab/issues/138); protocol G1.4a remains unchanged on the wire.
This stage completes the missing wiring listed in G1.4a. Reference: ouroboros-network
`2fb69829a19b5c04e4fb212bdd78fb63f9d2d776` (the existing pin),
`Governor/KnownPeers.hs:96–237,268–445`, `Governor/EstablishedPeers.hs:576–614`,
`State/KnownPeers.hs:178–200,580–592`, `Diffusion/Policies.hs:69–76,164–169`, and
`Cardano/Network/PeerSelection/Governor/Types.hs:170–171` (sharing disabled throughout the sensitive state).
The existing salt/hash deviation in G1.4a also applies to the client's salted subset: deterministic
MurmurHash3 ordering replaces Haskell's platform-dependent `SockAddr` hash; the bound and filtering agree.

**Pure policy.** `PeerRecord` retains configured advertisement and negotiated remote willingness.
A successful establishment sets the sharing eligibility time to completion plus 300 seconds. The
below-known-target family follows root requests, flips a coin between inbound discovery and eligible
established peers, takes at most ten unknown inbound peers (60-second retry), or asks at most two peers.
Requests split the discovery objective with `min(255,max(8,objective/requestCount))`; normal retry is
900 seconds, request failures 3600 seconds. A batch keeps its pending peers, salt and remaining objective
between the 3-second and 10-second completions. Existing peers (including the separate big-ledger
population) are removed before insertion; duplicates collapse so the unique discovery budget is used.
A new discovered peer gets `DoAdvertise`, existing configured flags remain unchanged. A late result
never restores a closed connection. Clean-state detection waits for outstanding sharing jobs too.

**Effects and ownership.** `PeerGovernor` starts total, exhaustively dispatched jobs. Per-peer requests
run in the owned supervisor and return closed `PeerShare.Result` values. A logical discovery timeout
ends the wait, while the at-most-60-second wire exchange drains its reply so the client's `Busy` state
cannot be reused mid-conversation. Supervisor shutdown cancels those workers; no wire failure escapes
through a captured MTL capability. `PeerSharingClient.request/done` take their caller's `Raise`;
`OutboundPeers` establishes `Fibers.scoped` for each request and routes unexpected defects to the fatal
sink without peer blame. Network failures close the failing mini-protocol's connection. A controller
not yet registered produces no answer, with the normal retry policy, as in the reference.

`PeerSharingRuntime` holds the public shareable vector, sticky salt, last successful DNS resolutions
and currently accepted duplex peers. A bounded one-element change notification wakes the governor;
resource release removes inbound candidates. Published answers use only advertised peers with a
successful connection and zero failures. Successful DNS roots publish their actual resolved IP.
The pure `PeerSharingAddress` conversion reverses IPv4 octets only; IPv6 retains its four network-order
words. Both incoming and outgoing negotiated flags reach the clients. Governor request dispatch uses
`OutboundPeers`' live connection table; accepting an inbound peer makes it known and subject to the
existing connection-manager rules. Reusing the accepted connection for an outbound governor job remains
a separate G2 connection-manager question. The actual client proof below uses an outbound connection; it does not prove a
governor request executed through the inbound client's instance. `Main` uses one resolved setting:
relay default on, producer default off, explicit configuration wins (`POM.hs:899–905`).
`PeerSharing` is removed from `configUnsupported`.

**Tests and evidence.** `PeerShareGovernorSuite` exercises activation/retry/cap/amount, both coin sides,
inbound willingness, privacy, big-ledger exclusion, remaining objective, failure and late-completion
rules. `PeerSharingLoopSuite` drives the actual effect loop on fixed-seed `TestControl`, including exact
3/4-second partial batches, 10-second timeout and a repeated one-hour run with results used and no
requests closer than 900 seconds per peer. `PeerSharingRuntimeSuite` tests publication, DNS provenance,
inbound resource lifetime and defaults; the protocol suite uses owned scopes and `FailsOnUncaught`,
including a client created in one scope and rejected in its request fiber's own scope. Address properties
cover arbitrary IPv4/IPv6 octets and boundary ports. Log samples and byte-for-byte golden cover all
three new sharing trace cases.

`devnet.py peer-sharing` creates only a fresh private cluster: Scarab has one advertised local root,
a pool-backed Haskell discovery relay. A second Haskell relay starts later with Scarab as its only
configured upstream; its advertisement of Scarab is disabled to avoid returning Scarab its own address. This
avoids depending on topology reload to stagger the 300-second activations. Its actual machine traces
must show a nonempty successful reply, at most `min(requested,10)`, all peers inside Scarab's published
eligible set. Scarab must also learn actual advertised pool endpoints beyond its sole root. The
validator rejects empty, refused, wrong-peer, unregistered, duplicated, unproven and oversized answers.
Every owned container is removed even if evidence archiving fails. Gate results are recorded in the
compatibility evidence log; initial excluded setup attempts are disclosed there.

At G1.4 acceptance, broader follow-ups were tracked in [#150](https://github.com/KD5UJC-XYZ/scarab/issues/150),
[#152](https://github.com/KD5UJC-XYZ/scarab/issues/152), and
[#180](https://github.com/KD5UJC-XYZ/scarab/issues/180). The shared metrics board described in section 7b
now addresses #150. The G1.4 evidence does not claim a new public-network
sync: earlier Preview evidence covers its unchanged target/churn/ledger-peer paths; the new sharing
behavior is checked on the private reference topology.

**Original parent #31 acceptance.** The implemented target sets, churn and PeerSharing have
reference-derived deterministic state-machine properties. Section 7b records Preview reaching and
following the tip from the sole official bootstrap root at `5f3b6174`, with no `--peer`; that run restored
an existing database copy and is not claimed as a fresh genesis sync. G1.3b repeats root-led discovery.
These historical runs cover the unchanged root/bootstrap behavior. Section 7f supplies the missing
actual nonempty two-way sharing proof on a private Haskell topology. Together they meet the original
parent criteria without a new public run. The explicit live-churn observation limits, full Genesis rules,
fetch/metrics and reintersection follow-ups above remain unchanged.
