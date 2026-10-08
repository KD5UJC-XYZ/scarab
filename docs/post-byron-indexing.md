# Post-Byron original-block index (v0.15)

One shared structural index accepts exact disk tags 2 Shelley, 3 Allegra, 4 Mary,
5 Alonzo, 6 Babbage and 7 Conway. Shelley/Allegra/Mary require four block fields;
Alonzo/Babbage/Conway require five including an unsigned invalid-index array.
Tags 2–5 require the flattened 15-field TPraos body and two VRF certificates;
tags 6–7 require the 10-field Praos body, one VRF certificate, four-field nested
operational certificate and two-field nested protocol version. Every header has
two fields and a 448-byte KES signature. Era is never inferred from protocol major.

This is a **concrete-parent-only** subset. Reference null GenesisHash parents
fail explicitly; they are never replaced by zeros. All numeric extraction uses
unsigned BigInt and a uint64 structural ceiling. This does not implement reference
Word32 body size/minor widths, bounded Version acceptance or historical protocol
version rules. Arrays of transaction/witness maps, auxiliary index maps and invalid
indices are checked shallowly, without uniqueness, index correspondence, schemas,
commitments, signatures, KES/VRF, scripts, ledger state or consensus validity.

The exact original header span is hashed with BLAKE2b-256; outer disk envelope and
body are excluded. Full raw bytes and SHA-256 are preserved. Non-shortest and
indefinite container encodings accepted by the bounded CBOR profile retain their
original header hash identity. They are not certified reference-accepted encodings.
A body-only mutation can preserve header hash; tests demonstrate this limitation.
No header transport wrapper or tag24 wrapper is automatically unwrapped here.

## Reuse, bounds and backward compatibility

Local source, EndpointBatch, fixture-only direct range and store/resume all call
the same indexer. Record parsing uses its shared six-era label whitelist. The
existing v1 checkpoint/manifest layout is unchanged and old Shelley/Allegra
manifests are explicitly compatible. Resume still rechecks original raw objects,
all recorded metadata, parent links and strictly increasing slots. No metadata is
invented on migration, and no EBB same-slot exception has been introduced.

Hard ceilings remain 1MiB input/string, depth 32, 100000 CBOR items; direct-range
batch remains four blocks and 4MiB raw bytes. New runtime dependencies: none.

## Portable evidence and next gates

Corpus provenance (optional private corpus; not distributed) identifies eleven retained
original fixtures and independent expected spans, hashes, parents, slots and heights.
Mary is mainnet-source-provenanced; selected Alonzo/Babbage/Conway singles remain
network-unestablished. All five linked Babbage slices are preprod-source-provenanced.
The known slice 0 point anchors importer and fixture direct-range tests over slices
1–4, within the existing cap. The unknown predecessor slot is never manufactured.
The original Shelley/Allegra fixtures remain regression coverage.

Run `python scripts/verify-post-byron-fixtures.py` for an offline portable independent
span/table/copy/checksum audit. It is not the Scala implementation or a reference
runtime. Existing CLI acceptance now exercises Babbage run/inspect/resume too.
No new public endpoint, original bytes acquisition, denied-route retry, native
oracle, signing, publication, network authentication or historical replay occurred.

Byron remains unsupported pending pinned reference hash/point-conversion evidence,
explicit epoch configuration and kind-aware EBB→regular same-slot continuity across
all endpoint/source/store/resume paths. There is no all-era contiguous mainnet corpus.

## v0.20 companion observation

The optional pure [body commitment inspector](body-commitments.md) now reuses the
same package-private validated parse. It enforces Word32 only for its own declared
body-size comparison and exposes digest-bound immutable observations. The public
structural index, all acquisition paths and persisted identities retain their
previous acceptance and behavior, including uint64 structural sizes and body-only
mutations. No stored object silently inherits a body-commitment receipt.
