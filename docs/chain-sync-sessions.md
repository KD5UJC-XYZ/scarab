# Bounded fixture connection sessions (v0.11)

In v0.14 these unchanged public session APIs live in `network-runtime` and delegate
to a shared single-protocol connection owner. The separate fixture BlockFetch driver
uses that owner too; see [direct-range ownership and limits](direct-range-source.md).

Acceptance boundary: **Pinned ChainSync envelope/state source conformance and deterministic fixture-peer simulation.** Run `./scripts/sbtw 'app/run chain-sync-session-selftest'` from the project root. The existing `chain-sync-selftest` stays pure and reports `transportExchanges=0`. Neither accepts an endpoint.

## Ownership and profiles

`ConnectionSession[F, P]` owns one exclusive `ByteTransport[F]` for its Resource lifetime, including the handshake and exactly one non-pipelined application protocol. Its sealed profiles bind NtN14/protocol2 to `OpaqueNtNHeaderFixture` and NtC16/protocol5 to `OpaqueNtCBlockFixture`; caller-supplied negotiated assertions or mismatched codecs/directions cannot activate a profile. Actual handshake negotiation checks offer membership, network magic and data agreement. Query (including negotiated query=true) and refusal return their typed terminal result and become logically terminal without an application view; physical closure follows at Resource release. Recognized later handshake versions do not imply application support.

One semaphore serializes admitted operations. A caller canceled while waiting for that permit leaves the active operation alone. Admitted cancellation, errors, EOF and deadline expiry poison the connection. Close atomically marks a terminal epoch and signals before closing the transport; it does not wait for the operation gate. Reads, writes and duration sampling race the signal. Every state/stream commit checks the live epoch and monotonic expiry (`now >= expiry` is expired). There are no callbacks or detached consumers. A commit that linearizes before close/cancellation is valid; cancellation cannot retroactively undo it.

One Resource-owned supervisor watches a bounded one-entry change signal. Fragment arrivals do not reset expiry. Defaults: handshake 10s, Idle 3373s, Intersect/NextCanAwait 10s, untrusted NextMustReply uniformly sampled integer 601..911s once per transition, locally configured trusted NextMustReply indefinite. Sampling is injectable and interruptible. TestControl advances virtual time; no new real-time test sleeps are required.

## Framing and limits

One immutable state retains the mux decoder, ordered frames and active CBOR prefix across handshake acceptance. Frames are routed one at a time only when more bytes are needed. Protocol-0 CBOR suffix is an error, while complete next-protocol frames and partial mux frames remain framing state. Application agency is checked at interpretation time; coalescing does not grant unsolicited server agency.

Defaults are 256-byte outbound SDU payloads, 1024-byte transport chunks, 131086 combined retained ingress bytes, 1024 frames, 256 bounded event names, 65535 bytes per ChainSync message, and 64 candidates. Configuration is validated before allocation. Ingress budget is checked before feeding a new chunk and includes partial mux state; frame counts are independently bounded. Prefix decode consumes exactly one message, so aggregate valid messages do not falsely trip a per-message cap. The general `Mux.segmentBounded` accepts a distinct message cap, allowing the 7807-byte NtC fixture; existing handshake segmentation retains 5760. Encoded frames are split again for transport chunk limits. Status exposes current/peak ingress and frame counts, state, epoch, bounded event names and terminal reason.

Wire Done is client-only in Idle and has no acknowledgment. It changes state to Done but does not immediately close/drop queued data. The owner must wait for local fixture-peer observation before releasing a nondraining transport. The independent scripted peer consumes Done during write; duplex tests explicitly receive Done before closing. Abort/close never synthesizes Done.

Query/refusal atomically makes the session logically terminal and stops its deadline supervisor after the terminal write completes. The owned transport remains open until explicit close or Resource release, allowing paired terminal results to be consumed even on nondraining Loopback. Further session operations fail immediately. Paired client/server fixture scopes await both terminal outcomes before either finalizer runs. Failure, deadline expiry and admitted cancellation still poison and physically close immediately. A terminal write is not an on-wire acknowledgment, and releasing a sender resource early can still discard queued bytes in a nondraining adapter.

## Evidence and limits

The finite peer independently assembles CBOR/mux bytes without production encoders. Two client scripts compare a 14-transition synthetic intersection/fork/await/rollback trace to the pure model, preserve exact opaque payload bytes, and observe Done. Two responder probes coalesce proposal plus RequestNext. Typed fixture and placeholder checksums remain verified before running. Derived envelopes and synthetic points are not reference-runtime transcripts.

Focused tests cover partial phase boundaries, agency, offered intersections, EOF, wrong protocol/direction, terminal negotiation, message/ingress/frame bounds, large segmented NtC sends, cancellation before/after admission, full-queue close, failed/late writes, state expiry, stale timer epochs, trusted indefinite waits and blocked sampling. Existing pure model/corpus tests retain candidate preference, no-common/Origin retry and unknown/pruned rollback checks.

No live peer, native/reference socket, new cryptography, header/block validity, chain selection, ledger rollback, pipelining, reconnect or production node claim is made. Network and consensus provenance/pins remain in `fixtures/chain-sync/*manifest.json` and the prior ChainSync documentation.
