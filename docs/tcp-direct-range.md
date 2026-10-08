# Live-capable direct-range TCP adapter

Version 0.16 adds a real addressed JVM asynchronous TCP interpreter and a separate
`chain-fetch-tcp` command. Acceptance execution is restricted to controlled
localhost scripts. No public endpoint, DNS lookup, remote relay, reference node,
HTTP fallback or endpoint discovery is part of the released evidence.

## Profile and boundary

This command’s only profile is `ntn14-blockfetch-short-strict-v1`: NtN14, initiator-only,
peer sharing off, query off, Cardano codec2, handshake protocol 0 followed by one
inclusive BlockFetch protocol 3 request. All other protocol/direction traffic,
including protocol 8 KeepAlive, fails closed. KeepAlive and a general mux
dispatcher are **not implemented in this strict profile**. Version 0.18 adds an
[explicit separate KeepAlive command](keepalive-direct-range.md); strict v1 does not upgrade. A short deadline does not establish that
omitting KeepAlive is compatible with a normal relay connection manager. Relay
interoperability remains unestablished.

The TCP adapter accepts numeric IPv4 and IPv6 literals, builds the socket address
from parsed bytes, and attempts exactly one connection. No name resolver or
host-resolving socket constructor is used. Hostnames, URLs, scope/zone IDs,
bracketed literals, ambiguous IPv4 forms, unspecified and multicast addresses,
and IPv4-mapped IPv6 are rejected. Canonical IPv6 identity uses expanded lowercase
hex groups. No retries, reconnect, failover, tip selection, ChainSync or peer
selection are provided.

## Explicit invocation

Run `chain-fetch-tcp --help` for the exact flags. Every selector below is required;
there is no default relay, magic, anchor, endpoint, source or output directory.

```sh
./scripts/sbtw 'app/run chain-fetch-tcp run --peer NUMERIC_IP --port PORT --network-magic UINT32 --source-descriptor claims.tsv --source-provenance claims.txt --after SLOT:HASH --first SLOT:HASH --last SLOT:HASH --output OUTPUT_DIR'
# Add --resume only for an already bound store.
```

This is a real command and can connect to the numeric address the user supplies.
Do not copy an arbitrary public endpoint into a release verification run. Slots
are UInt64 decimal values; hashes are exactly 32 bytes in lowercase hex.

The source descriptor is strict UTF-8 TSV, one occurrence of **every** field
below, LF line endings and a final LF; unknown/duplicate fields are rejected.
Replace placeholders with actual independently supplied endpoint claims.

```text
format\ttcp-direct-range-v1
peer\tNUMERIC_IP
port\tPORT
networkLabel\tDECLARED_LABEL
networkMagic\tUINT32
anchor\tEXCLUSIVE_SLOT:HASH
first\tINCLUSIVE_SLOT:HASH
last\tINCLUSIVE_SLOT:HASH
provenanceSha256\tSHA256_OF_ACTUAL_CLAIMS_FILE
attribution\tDESCRIPTION_OF_CLAIM_ORIGIN
expectedCount\t-
expectedPoints\t-
bytePins\t-
```

Here `\t` denotes a literal tab. The provided provenance file is read and hashed;
a claimed hash alone is insufficient. Descriptor/provenance bounds are 64 KiB and
256 KiB. Symlinks and unsafe paths are rejected. Attribution is bounded plain
ASCII text, not a remote URL to fetch. File names are not hashed as provenance.
The original descriptor's endpoint, magic, anchor and range must agree with the
CLI; flags never override source claims.

Ordinary acquisition uses `bytePins=-`: payload bytes, sizes and hashes need not
be known before connecting. Optional pins are `SLOT:HASH,SIZE,RAW_SHA256`, separated
by semicolons. Points are unique, sorted canonically for identity, in range, and
each supplied pin must match exactly one returned original. They may cover a
subset. `expectedPoints` is an optional comma-separated **complete ordered** list;
`expectedCount` is an optional exact positive count. Partial byte pins do not
silently become a complete point list.

## Admission, byte identity and resume

One shared `DirectRangeSource` owns acquisition for both fixture and TCP callers.
The fixture compatibility facade retains its exact `fixture-direct-range-v1`
canonical encoding and mandatory exact ordered original-byte policy. TCP has its
own `tcp-direct-range-v1` identity and optional-pin policy. Neither exposes its
private `BlockSource` for early count-based termination.

Every original payload goes through the existing post-Byron Shelley–Conway
indexer. Header hash/slot/parent are derived from its actual original bytes;
inclusive endpoints, exclusive-anchor linkage, strict slot ordering and complete
BatchDone are mandatory, regardless of byte-pin policy. The adapter accounts raw
bytes before accepting each block, rejects incomplete/extra/reordered batches,
rejects buffered suffix traffic, sends ClientDone and closes before exposing a
cursor or appending. There is no ClientDone acknowledgement to await and no claim
about bytes arriving after close.

Raw SHA256 and sizes are **observations**, not proof that bodies satisfy header
commitments. Header signatures, body commitments, ledger validity, consensus,
genesis/network authentication and Mithril remain unvalidated. A structurally
parseable synthetic body mutation with unchanged header can pass first download
without an independent byte pin. The acceptance tests deliberately demonstrate
this limit and never label that mutation a valid Cardano block.

Source identity binds canonical address/family/port, all profile assertions,
selection, provenance contents/attribution, optional pins and every resource and
store budget. Unknown payloads are absent; the identity never changes after a
download. Store ownership and source/selection binding succeed before opening
TCP. Resume freshly acquires the entire original range, verifies BatchDone, and
compares every committed overlap Record (point, parent, raw hash, size, era) and
stored object. Changed committed body bytes under the same header are rejected.
Previously uncommitted bytes have no historical pin unless independently supplied.

## Bounds and lifecycle

Defaults are hard maxima and optional CLI flags can lower useful-work caps:
connect 5 s; read 10 s; write 5 s; handshake 5 s; Busy/Streaming 10 s; total
acquisition 30 s including connect; whole invocation 45 s including effectful
preflight, store acquisition/recovery, replay and append. Deadlines overlap, not
add. Four blocks, 1 MiB per block, 4 MiB raw aggregate, 5 MiB incoming mux bytes,
16 KiB separately accounted outgoing bytes, 65,543-byte transport chunks,
65,535-byte SDUs, 1,024 retained frames, 32 events, 16,384 total frames, 8 MiB stored
bytes and 256 files bound this profile.

Each connection owns a fixed two-thread executor and asynchronous channel group.
One reader and one writer have separate admission permits; cancellation while
waiting does not poison another operation, while admitted cancellation, timeout,
registration/I/O failure closes the channel. Partial writes retain buffer
position, zero completions yield without resetting the operation deadline, reads
never return empty chunks, and close does not wait for read/write permits.

Socket close precedes group/executor shutdown. Finalization observes termination
for up to 2 additional seconds and surfaces cleanup failure. Work deadlines can
trigger during finalization as well; they do not abort uncancelable cleanup.
Portable Java cannot guarantee hard real-time bounds for arbitrary native close,
executor shutdown or filesystem calls. Primary failures survive resource cleanup;
secondary finalizer failures are reported by Cats Effect. No global runtime or
pool is created by the adapter.

Exit mapping: config 2; unavailable/useful-work budget 3; connect/socket/protocol/
EOF 4; integrity 5; store 6. Cancellation remains effect cancellation. NoBlocks
means this peer did not provide the range now. `chain-fetch` retains its previous
parser/output contract. The new versioned JSON wrapper separates capability,
actual selected endpoint, independent-pin scope and false validation/interop flags
from the nested existing fetch result. Incomplete reports use `null` for external
execution when connection completion is not established; the configured endpoint
is never itself evidence of an executed connection.

## Provenance and verification

The portable [source manifest](../fixtures/network/tcp-direct-range/manifest.json)
and SHA256SUMS retain pinned NtN version and KeepAlive sources plus Apache-2.0
license/notice references. BlockFetch codec/agency provenance is unchanged in
[block-fetch-provenance.md](block-fetch-provenance.md). These are source-level
claims, not current public-network configuration or captured relay sessions.

Run `./scripts/sbtw check` and `python3 scripts/verify-tcp-direct-range.py` for the
local acceptance gates. Final pass counts and exact evidence are in
[tcp-direct-range-verification.md](tcp-direct-range-verification.md). No subsequent
external run is implied. A separately authorized user run can establish only the
bounded endpoint/profile it actually exercised, never universal relay support.
