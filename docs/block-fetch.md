# Pure BlockFetch milestone 0.13.0

This document records the v0.13 pure-codec milestone. v0.14 adds a separate
[fixture-only direct-range source and shared runtime](direct-range-source.md);
the pure codec/CLI below remains unchanged and makes no transport claim.

This milestone adds a reusable, pure, non-pipelined protocol model. It does not
add a network source to the local chain fetcher. Existing `ConnectionSession`
remains ChainSync-only. No BlockFetch socket, handshake, mux routing, deadline,
cancellation or live-peer interoperability is implemented by these new APIs.

## Wire and raw-byte contract

`BlockFetch` exposes Idle, Busy, Streaming and Done states, Client/Server agency,
and exact definite-array messages: RequestRange `[0,from,to]`, ClientDone `[1]`,
StartBatch `[2]`, NoBlocks `[3]`, Block `[4,payload]`, BatchDone `[5]`.
`encode`, `decodePrefix` and exact `decode` enforce state, sender, arity and bounds.
Prefix parsing leaves surplus bytes to the caller. NeedMore is not EOF success.
There is no request ID, pipelining, cancel message or ClientDone acknowledgment.

Protocol metadata identifies NtN mini-protocol **3**, with default NtN14 and
Cardano codec version2. These constants are not negotiated capabilities. There
is no NtC BlockFetch profile. The generic wire codec permits Origin; Cardano
`SpecificPoint` rejects Origin and requires exactly 32 hash bytes with UInt64 slots.
`InclusiveRange.single(p)` requests `[p,p]`. Both range endpoints are inclusive.

`CardanoBlockFetch.payloadCodec` requires exactly tag24 and a definite byte string,
rejecting wrong wrappers and oversized advertised lengths before the full body
arrives. `RawNtNBlock` owns immutable original byte-string contents. Inner bytes
are opaque, including empty, malformed or toy CBOR. No inner normalization,
header hashing or ledger validation occurs. Nonminimal valid outer CBOR can be
canonically re-encoded; retain an external transcript if exact envelope bytes matter.

## Ordered bounded request model

`FetchPlan.from` admits a nonempty bounded vector of distinct specific points,
rejecting decreasing slots while allowing same-slot distinct hashes (Byron EBB
and regular blocks can share a slot). These checks alone cannot establish chain
order or ancestry. The endpoints become an inclusive request.

`Batch.begin(plan)` models Busy after the request. `accept(message)(identify)`
uses an injected independent original-byte identity extractor, checks exact expected
point order, block count and aggregate raw bytes, and returns Pending until exact
BatchDone. Empty/early BatchDone, extra blocks, identity errors, duplicate/wrong
points and wrong agency fail. NoBlocks yields Unavailable only before streaming;
it does not prove the requested blocks do not exist elsewhere. `endOfInput`
fails a pending request, including after the last block but before BatchDone.
Completion is terminal for that batch object. Treat any Left as terminal for the
request. Begin a new model only for a separately admitted request.

Complete certifies only count/order relative to the supplied identity extractor.
It is not a consensus/header/ledger certificate and does not check parent linkage.
A future concrete source must supply era-aware original-header hashing and parent
checks before committing a fetched segment. The offline CLI deliberately supplies
synthetic point observations and prints `blockIdentityVerified=false`.

## Bounds and preserved policies

Independent BlockFetch per-message ceilings are 65,535 bytes in Idle/Busy and
2,500,000 in Streaming. The default raw-block cap 2,499,991 leaves nine canonical
wrapper bytes; nonminimal framing may reduce the usable raw size. Defaults also
bound depth, items, strings, hashes, four blocks and 10,000,000 aggregate raw bytes.
Accounting uses checked subtraction to avoid overflow. TimeLimits records upstream
60-second Busy/Streaming waits and a local 120-second whole-request proposal;
these are metadata, not running timers.

`ProtocolWire` shares the original scanner/writer mechanics. `ChainSyncWire`
retains its original validated policy and 65,535-byte ceiling, including independent
non-default string/hash settings. BlockFetch's larger allowance does not enlarge
ChainSync or `ConnectionSession` ingress. A codec ceiling is not a transport claim.

## Evidence and reproduction

Eight fixed wire examples include synthetic literals and a source-derived envelope
around the genuine upstream Conway Block_Conway golden. This is not a captured
full-message oracle transcript. Portable fixture/source/license records are in
[`manifest.json`](../fixtures/network/block-fetch/manifest.json); pinned authority
and payload hashes are in [provenance](block-fetch-provenance.md). Sources and
Apache-2.0 notices are retained locally. No new download or reference execution was
needed to derive these bytes.

```sh
./scripts/sbtw scalafmtAll check app/runtimeClasspathFile
python3 scripts/verify-block-fetch-cli.py
# Pure offline selftest only:
./scripts/sbtw 'app/run block-fetch-selftest'
```

The verifier audits checksums, source/license files, exact retained golden derivation,
and CLI success/invalid-argument behavior. New tests cover all agency transitions,
uint64 endpoints, small-message split points, trailing bytes, raw-byte ownership,
wrong wrappers, declared huge bodies, full 2.5MB codec ceiling, preserved ChainSync
bounds and incomplete/extra/wrong-order batch outcomes. Existing transport regression
tests remain separately scoped localhost/in-memory simulation.

Next milestone: one exclusive negotiated connection owner, bounded protocol3
routing and a fixture-backed direct-node `BlockSource` with era-aware identity and
parent checks, complete-batch-only persistence, deadlines/cancellation and resource
regressions. Never create a second socket reader/session over the current transport.
Live transport or new provider access needs its own authorization and evidence gate.
