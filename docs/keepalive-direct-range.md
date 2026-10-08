# Explicit KeepAlive-capable short-range TCP profile (0.18)

`chain-fetch-tcp-keepalive run` selects only
`ntn14-blockfetch-keepalive-short-v1`. The existing `chain-fetch-tcp` command,
`tcp-direct-range-v1` descriptor/canonical identity and strict protocol-3 behavior
are unchanged. There is no detection, auto-upgrade, fallback, reconnect or retry.
This is a bounded known-range downloader, not Cardano diffusion or a full node.

## Invocation and identity

Use the same required numeric endpoint, magic, descriptor/provenance, anchor,
first/last and output flags documented in [the strict command](tcp-direct-range.md),
but select `chain-fetch-tcp-keepalive`. The descriptor has the same selector fields
and additionally requires exactly these explicit schema/profile fields:

```text
format\ttcp-direct-range-v2
profile\tntn14-blockfetch-keepalive-short-v1
```

Here `\t` denotes one literal tab. The canonical identity begins with
`tcp-direct-range-v2`; it binds all existing selector, attribution, provenance,
optional byte-pin, indexer, TCP, useful-work and store policies. It also binds
routes/direction, codec/agency, cookie-strategy revision, immediate start,
97/60-second upstream metadata, local interval/reply/finish policies, every queue
and reservation, outgoing frame cap, mailbox size, fair writer policy, deterministic
Done ordering, completion contract and owner compatibility revision. Ephemeral
random cookies and downloaded hashes do not retrospectively change source identity.
Files at different local paths remain equivalent when their actual content matches.
A changed identity refuses resume before opening TCP. No store migration is supplied.

## Pure protocol and pinned provenance

Independent implementation from Apache-2.0 Ouroboros Network source commit
`c45735a56c567fa977969173d18943bac6bb3821`:

- `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/KeepAlive/Type.hs`,
  `Codec.hs`, `Client.hs`, `Server.hs`: protocol-8 codec-v2 messages `[0,cookie]`,
  `[1,cookie]`, `[2]`, UInt16 and agency; the client checks echoed-cookie equality.
- `ouroboros-network/api/lib/Ouroboros/Network/Protocol/Limits.hs`: upstream small
  message ceiling 65535 bytes. Codec time limits are client 97s/server 60s.
- `cardano-diffusion/lib/Cardano/Network/NodeToNode.hs`: protocol number 8 and mux
  ingress `addSafetyMargin 1280 = 1408` bytes; established-temperature registration.
- `ouroboros-network/lib/Ouroboros/Network/KeepAlive.hs`: immediate initial random
  cookie; interval starts after matched response; termination at client agency.
- `ouroboros-network/lib/Ouroboros/Network/PeerSelection/PeerStateActions.hs`:
  initiator startup eagerly starts the established bundle. Initiator-only does
  not disable its outgoing KeepAlive client.
- `network-mux/src/Network/Mux/Ingress.hs`: closed routing, direction checks,
  failure rather than silent drop/whole-demux blocking on queue overrun.

Original Scala code retains Apache-2.0 SPDX notices; no Haskell implementation is copied into the Scala code.
Canonical literals: request zero `820000`, response zero `820100`, request 65535
`820019ffff`, response 65535 `820119ffff`, Done `8102`. Definite non-shortest
unsigned numeric/list-length encodings are accepted, matching the pinned decoder's
noncanonical primitive. Indefinite lists, invalid arity/key/agency, negative or
out-of-range cookies reject. Prefix consumption never silently drops surplus.
Cookies correlate replies; they provide no authentication.

## Shared engine and closed transport ownership

Both profiles use the unchanged `SingleProtocolConnection`/`BlockFetchSession`
handshake and BlockFetch engine. The v2 facade adds a private routed bearer beneath
that engine, not a second implementation of handshake or BF session state. Only
its fixed reader touches the physical transport. The virtual BF transport retains
original SDU timestamp/protocol/direction and preserves fragments; it never
relabels protocol-8 traffic as protocol3. Exact NtN14 negotiation completes before
KA starts; the first ping write completes before BF RequestRange is sent.

During handshake, protocol0 and pre-read protocol3 SDUs may be staged. Their wire
order remains visible to the handshake engine, which rejects application bytes
before a complete accepted handshake and preserves application bytes after it.
Protocol8 before activation/outstanding request rejects. After activation only
remote-responder directions for protocols3 and8 are allowed; protocol0, unknown
protocols and initiator-direction traffic fail closed. No responder instance,
ChainSync, TxSubmission, peer sharing, listening or topology machinery is enabled.

Three fixed Resource-owned workers perform raw read/demux, SDU writing, and KA.
The existing BF deadline supervisor is retained. The reader has one stop/read race at a time. On graceful shutdown the owner closes
the bearer explicitly, joins that read, and admits any successful returned chunk
before the final suffix check. Deliberate close failure remains an acquisition
failure; only its expected asynchronous-close read exception is tolerated. The
KA worker has at most one
bounded response-timeout race or interval/termination race; race children are
canceled/joined by Cats Effect, not accumulated per frame/message. Per-protocol
state and BF/KA expirations remain independent. No global operation gate is held
across a protocol wait. Resource release cancels/joins all fixed workers; the
physical close decision is idempotent. Cancellation of a producer awaiting its
BF write permit leaves the admitted operation intact; cancellation after admission
closes the owner. Read/write errors publish failure to owner state before a final
success barrier can seal it.

## Exact bounded policies

The existing four-block, one-MiB-per-block, four-MiB original-batch, five-MiB incoming
wire, 30-second acquisition and 45-second invocation ceilings remain. Acquisition
starts before TCP allocation/connect. Local KA defaults: immediate first ping,
10-second interval after matched response, an absolute 10-second reply deadline
reserved before request write (including queue/write/scheduler delay), 5-second
finish budget starting at the first BatchDone freeze. The finish budget covers
both Done writes and final barrier, and never restarts. The shared whole-acquisition
deadline bounds all stages; pings and BF blocks cannot extend it. The transport read
bound is separate: default read 10s can expire while no protocol traffic arrives,
including near the next 10s KA interval. This short profile makes no long-idle
connection survival claim and does not widen existing TCP timeouts.

Incoming BF queue uses at most `maxRawBlockBytes + 9 + maxChunkBytes` encoded bytes
and `maxFrames` SDUs. KA queue uses 1408 payload bytes and 16 SDUs. Full queue means
immediate typed connection failure, never blocking the sole demux on capacity.
Zero-length SDUs reject; tiny SDUs still consume finite frame credits. The common
physical incoming counters include handshake and both routes, with finite total
frame cap. Declared mux lengths are checked before payload growth.

Canonical global logical ingress reservation is the sum of:

1. BF routed queue, KA routed queue, one physical incomplete SDU (65543), one
   incoming physical chunk, plus a separate virtual BF remainder reservation of
   `max(0, 65543 - maxChunkBytes)`;
2. the unchanged BF engine's independent message-plus-chunk reservation;
3. one KA pending-message reservation (1408) and one KA handoff reservation (1408).

These are conservative disjoint logical-byte reservations, not JVM heap-size or
allocation-peak guarantees. Immutable parser copies may coexist. The observed
routed-retention metric is explicitly only the routed subset, not a claim to
measure global memory. Original verified block bytes retain their separate batch
budget. Physical wire counts are never reset by negotiation or protocol traffic. A rejected
raw chunk is charged when observed; discovering exhaustion can read one final bounded
chunk beyond the accepted wire budget, and always fails the acquisition.

The single writer accepts one outstanding mailbox message per protocol; fixed
producer gates prevent unbounded acknowledgments. It alternates ready protocol
mailboxes at complete-SDU boundaries. A ready KA write waits at most one competing
SDU plus scheduling/transport delay. Headers/payload and transport fragments of one
SDU are never interleaved with another protocol. Partial/zero socket writes remain
owned by the existing TCP interpreter. All physical outgoing bytes, including
handshake/mux headers, count toward 16384 bytes and 256 complete frames, reserved
before first write. Partial writes are not retried from byte zero.

## Graceful-required completion and failure

`BatchDone` completes range admission, not connection success. The shared
acquisition loop freezes KA scheduling before optional byte-pin checks. Any
outstanding cookie must receive its exact response within original reply,
finish and whole budgets. Only client agency may send KA Done. Deterministic
successful order is KA Done then BF ClientDone. Neither has an acknowledgment.
A legal KA Done can already have been sent when later pin or BF suffix rejection
aborts the acquisition; this is not successful completion.

BF buffered suffixes, including partial CBOR, reject separately from valid KA
response bytes. The final owner barrier requires no retained application bytes,
partial mux header/payload, pending cookie or writes. It first quiesces/joins the
reader, including completed-read handoffs, and atomically seals against
recorded ingress rejection. Transport close and Resource cleanup precede cursor
exposure. Failed Done, cookie, timeout, suffix or cleanup cannot create a successful
batch/cursor/checkpoint. Failure/cancellation aborts promptly and does not wait for
server agency merely to send Done. Bytes not observed before the closing barrier
cannot be claimed absent; this is not an indefinite network-drain proof.

Exit classes remain configuration 2, unavailable/whole budget 3, protocol/socket 4,
integrity 5, store 6. KA mismatch, KA deadline and queue failures are protocol 4;
whole acquisition expiration and aggregate outgoing byte/frame budget exhaustion are 3. Effect cancellation remains cancellation.
Existing EndpointBatch checks, original bytes, optional pins and complete overlap
reacquisition are reused unchanged; same-header/different-original overlap rejects.

## Evidence and explicit limits

Separate flags: `keepAliveCodecImplemented=true`, `keepAliveRuntimeImplemented=true`,
`boundedDualProtocolRoutingTested=true`, `localhostTcpTested=true`.
All acceptance peers are literal in-memory scripts or explicit 127.0.0.1 listeners
through the existing TCP adapter. No public DNS, relay, HTTP, reference node,
Mithril or external endpoint was executed for this implementation.
`externalEndpointExecutedHere=false`, `relayInteropEstablished=false`,
`generalMuxDispatcher=false`, network/body/ledger/consensus/Mithril/reference
validation flags remain false. Local protocol conformance does not establish a
relay's admission, history/pruning policy or compatibility with BF-only hot work.

See [verification and harness history](keepalive-verification.md) for actual gates.
