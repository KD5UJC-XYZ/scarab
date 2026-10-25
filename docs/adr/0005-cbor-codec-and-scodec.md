# 0005. Our own byte-preserving CBOR codec; scodec only for read-once fixed layouts

- **Status**: Accepted; the decode into "our own tree" is superseded by [0013](0013-cbor-documents-are-an-index-over-the-input.md)
- **Date**: 2026-10-15

## Context

Anything hashed or signed must be hashed over the bytes as received, which may use non-minimal or
indefinite-length encodings. Real chains contain items nested more than 10,000 levels deep (preprod,
B17). Fixed-layout binary formats (the Haskell ImmutableDB indices, our store files, the mux header)
are a separate concern.

## Decision

CBOR is decoded into our own tree that keeps each item's original bytes, without recursion on the call
stack, with typed, located errors; the CDDL generator (`models-gen`) targets the same runtime. scodec is
used only for fixed layouts read once per file or chunk (the Haskell ImmutableDB indices). Per-block and
per-frame paths stay hand-written: measured, scodec was 2 to 24 times slower there (numbers in
CONTRIBUTING, "Build tooling").

## Consequences

- No dependency on a general CBOR library's limits (borer refuses past 1,000 levels; scalus#398).
- The decoders are fuzzed (K3) and cross-checked against cuddle (K10).

## Amendment (2026-10-25): scodec in SCLS interchange

The SCLS interchange code (`SclsFraming`, `SclsRecord`, `SclsOutput` in
[storage/scls](../../storage/src/main/scala/xyz/kd5ujc/scarab/storage/scls)) also uses scodec, for
fixed-width integer fields that it reads or writes once per frame and once per entry, not once per
file or chunk. Each of the three has its own closed error cases (for example `SclsRecord.Error.Binary`), not
`Binary.Failure`. It runs only in the `export-scls` and `import-scls` tools (`SclsCommand`), never
on the node's per-block or per-frame paths, and the measurements in CONTRIBUTING ("Build tooling")
do not cover it.
