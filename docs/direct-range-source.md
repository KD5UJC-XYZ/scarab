# Fixture-backed direct inclusive-range source (v0.14)

This is a reusable, bounded **fixture** acquisition path:

1. One owned byte transport negotiates the strict NtN14 application profile.
2. Protocol3 requests caller-supplied concrete first and last points, both inclusive.
3. The entire small batch is structurally indexed and verified through BatchDone.
4. The connection closes before a private BlockSource exposes original raw block bytes.
5. Existing Fetch.run and NioSegmentStore replay/append the verified bytes.

No ChainSync discovery is needed for these known endpoints. No live endpoint interpreter,
new sockets, KeepAlive, general mux dispatcher, network/consensus/ledger validation,
Mithril authentication or reference-node replay was added or exercised. Existing
localhost TCP regression tests remain separate. Generated literal byte peers are not
captured network traffic. The eight retained originals remain two independent four-block
Shelley and Allegra windows; they are not a contiguous cross-era chain.

## API and selection

`EndpointBatch.Spec.checked(anchor, range, expectedCount, expectedPoints, limits)`
requires a concrete exclusive `lab.fetcher.Point` predecessor and concrete inclusive
`CardanoBlockFetch.InclusiveRange` endpoints. Origin is unsupported. UInt64 slot values
stay BigInt-based; no signed-Long truncation. First/last must follow the anchor, and
supported returned slots strictly increase. Equal first and last means one block.

`EndpointBatch.begin(spec)` models an accepted request in Busy; `.accept(message)`
uses the existing BlockFetch transition and aggregate accounting model. Its public
result stays Pending until BatchDone. It derives header hash, parent hash and slot
from `CardanoBlockIndex.inspect` over original post-Byron disk tags 2–7. First
must exactly match first; its parent must match the anchor hash. Later blocks must
link to their predecessor and never pass or mismatch last. Optional expected count
and independent ordered point list are exact assertions, not early-stop budgets.
A child's parent hash cannot independently prove the asserted anchor slot.

`PinnedDirectRangeDescriptor.checked(...)` constructs canonical versioned UTF-8/LF
recipe bytes and a SHA256 SourceIdentity. It binds original manifest and provenance
hashes, ordered original lengths/raw hashes, anchor/endpoints, optional exact
count/point list, raw limits, asserted network label, strict NtN14 version/data and
Cardano codec2 profile, literal script/transcript digest and upstream pins. For compatibility, the existing result field `sourceManifestSha256` holds this
canonical descriptor digest; the original manifest digest is a separate descriptor
field. The
adapter verifies the completed raw batch against the descriptor's original hashes,
lengths and order. Callers are responsible for obtaining descriptor inputs from their
retained independent sources: a digest is an assertion, not chain authentication.
The script digest identifies the declared generated recipe; it is not a claim that
arbitrary transport fragmentation is a captured transcript.

`FixtureDirectRangeSource.resource[F](descriptor, fetchSpec, fixtureConnection, config)`
captures an immutable end-only FetchSpec: `after == anchor`, `end == last`, `count == None`.
It returns a wrapper exposing `identity`, cumulative `inputBytes`, `run(store)` and
`runOwned(storeResource)`. The underlying BlockSource is deliberately private so a
caller cannot substitute a count selection that stops before the requested endpoint.
No public BlockSource, Fetch.run or SegmentStore signature changed. `runOwned` also
bounds store allocation/recovery; use `run` only when the store is already acquired.
`ScriptedByteTransport.resource(script)` supplies a finite bounded read/expected-write
interpreter without encoders; scripts use independently assembled literal bytes.
The factory takes a fixture Resource of the existing ByteTransport seam, not an
endpoint, address, socket constructor or a promise of live-peer interoperability.

Every open takes a cancelable exclusive permit, allocates a fresh owned fixture
connection and re-requests the original inclusive range. Acquisition failure exposes
no cursor and advances no checkpoint. Valid BatchDone plus an empty buffered suffix
is required before ClientDone and cursor exposure. Already-buffered CBOR, mux frames
and partial mux headers after BatchDone are rejected; future unread peer bytes are
not observed after closing this single-request fixture connection.

## Shared owner and preserved ChainSync API

The new `network-runtime` module depends on pure `network` and Cats Effect. It owns
ByteTransport/Loopback, HandshakeSession, fixture peers and session deadlines under
their unchanged `lab.network` package. Existing `ConnectionSession` is a compatibility
facade over `SingleProtocolConnection`. Its public profiles, config, status, methods
and ChainSync semantics are preserved. Application selection is fixed once, after
handshake; there is no protocol-switching API and no competing reader.

The same owner handles framing, protocol/direction-tagged queued frames, admission,
serialized writes, state epochs, closed signal and deadline watchdog. Coalesced
handshake/application frames and partial next mux headers survive negotiation.
Handshake CBOR suffixes are rejected rather than relabelled as protocol3 bytes.
Cancellation while waiting for admission leaves its current owner alive; admitted
cancellation or error poisons and closes the connection. Close/epoch/deadline checks
reject stale commits. State and whole-request deadlines are monotonic, checked by
both watchdog and operations; whole expiry is never reset by a Block. Cleanup is
best effort and cannot hide the original acquisition failure. Each BlockFetchSession
admits one RequestRange only, with its guard committed inside the shared owner.

## Independent bounds and accounting

- At most four retained blocks and 4,194,304 aggregate raw bytes, optionally stricter.
- At most 1,048,576 raw bytes per block, matching the current indexer ceiling.
- Streaming CBOR limit is raw limit + nine bytes for the canonical Block/tag24/bstr
  wrapper; an advertised oversized bstr is rejected before its body arrives.
- Mux SDUs are at most 65,535 bytes. Default transport chunks are at most 65,543 bytes.
- Ingress headroom is raw limit + nine wrapper bytes + one bounded transport chunk,
  at most 1,114,128 bytes. This is a retained-input bound, not a heap-size claim.
- Default read-wire budget is 5 MiB including handshake/mux bytes. Config may only
  lower it. Default total frame bound is 16,384 (hard cap 65,536), distinct from
  per-read/backlog frames (default 1,024, hard cap 8,192).
- Event history default 32; one request; handshake default 10 seconds; Busy and
  Streaming default 60 seconds each; whole session default 120 seconds.
- FetchSpec's independent invocation limits still govern input, storage, files and
  whole job time. `runOwned` includes store recovery; timeout before a snapshot
  exists raises FetchError3 rather than fabricating an incomplete snapshot.

`inputBytes` is cumulative raw bytes admitted from decoded Blocks across successful,
failed and repeated opens. It excludes descriptor bytes, CBOR/mux overhead and
outgoing requests, and is not exact wire bandwidth. Wire accounting belongs to each
fresh owner and is separately capped. Subtraction-before-addition avoids overflow.
Prevalidating four bounded objects intentionally repeats indexing in Fetch.run and
uses memory; this is not a streaming historical downloader.

## Failure and recovery contract

Descriptor/selection construction failures use FetchError2; pure unavailable,
incomplete range and raw/count/input budgets use FetchError3 and the existing
incomplete FetchResult path; session configuration, wire/transport/protocol failures (including EOF after
last but before BatchDone, deadlines and framing limits) use FetchError4; structural,
endpoint/parent/order/raw-provenance mismatches use FetchError5. Existing store binding
and output failures remain FetchError6. Effect cancellation stays cancellation.
NoBlocks means unavailable from this peer now, never ordinary completion or proof
that the requested blocks do not exist.

Empty/short BatchDone, extra/reordered/duplicated/wrong-endpoint blocks, malformed
inner bytes, unsupported eras and incorrect source hashes cannot commit any bytes
from that acquisition. After verified acquisition, existing atomic append behavior
applies: canceling append may retain a verified committed prefix. Resume verifies the
store, freshly acquires and completes the **full original batch**, and replays all
committed overlap; it never seeks past an unverified stream. NoBlocks/EOF on resume
leaves existing records inspectable but does not establish current completion.
The v0.12 inspect format is unchanged and has no historical BatchDone certificate.

## Deterministic evidence and remaining work

Tests use independent literal CBOR/mux assembly, both retained four-block windows,
singleton and pure exact boundaries, false authenticity flags, no checkpoint advance
on acquisition failure, full/partial overlap replay, append/acquisition cancellation,
close failure preservation, per-state and trickling whole deadlines under virtual
time, coalesced/fragmented phase boundaries, malformed/oversized declarations and a
1 MiB multi-SDU receive with framing headroom. Pure verifier padding fixtures preserve
original headers but alter other structural bytes and are explicitly not valid
ledger blocks. Existing ChainSync/resource tests remain unchanged.

Real relay work still needs an authorized endpoint interpreter, actual version/data
compatibility evidence, diffusion/KeepAlive policy and possibly one bounded dispatcher
within the same owner. General syncing separately needs header discovery/selection,
rollback, consensus, Byron/future-era indexing and trustworthy network/genesis evidence.
See [BlockFetch source provenance](block-fetch.md), original sample provenance (optional private corpus; not distributed),
[local store contract](local-chain-fetcher.md), and [ChainSync ownership](chain-sync-sessions.md).

## v0.15 era expansion

The shared index now supports Shelley through Conway. The four-block/4MiB range
cap and 1MiB block cap are unchanged. In addition to Shelley/Allegra regression
windows, the preprod Babbage test uses retained block 0 as an exclusive known anchor
and selects blocks 1–4, including interrupted store resume and full reacquisition.
The descriptor explicitly labels preprod; its handshake uses a synthetic fixture
network magic, not a claim of a real preprod peer session. See
[post-Byron boundaries](post-byron-indexing.md). Byron remains unsupported and
strict slot ordering is not weakened.
