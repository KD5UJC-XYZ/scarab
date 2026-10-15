# 0001. The Haskell node is the reference design

- **Status**: Accepted
- **Date**: 2026-10-15
- **Issue**: [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82)

## Context

Scarab must agree with the Haskell `cardano-node` on every block and on the ledger state, and should be
able to read and write the Haskell node's databases. Early on, the node had its own internal shapes and
was compatible only at its interfaces; adding a field to our own stake snapshot forced a 3-million-block
replay on preprod, and our database could not load a Haskell one.

## Decision

The Haskell node (ouroboros-consensus, cardano-ledger) is the reference design, not just the reference
behaviour. Internal types follow the Haskell types (names, fields, nesting, the era indexing), the
on-disk and on-wire formats are the Haskell formats, and each mirrored item cites the upstream file and
line at a pinned version. Where Scala allows a real improvement (a typed gap or residue instead of a
silent default, derived fields checked on decode, a stricter type), it is taken and the reason is
written down. In the owner's words: "use haskell as the reference and don't just be compatible with its
interface".

## Consequences

- Comparisons with the Haskell node become byte comparisons (ledger-state files, query answers).
- Importing and exporting Haskell snapshots and Mithril fast start become reachable.
- Upstream changes are followed by re-pinning sources and diffing, not by reverse engineering.
- Networking is the stated exception ([0008](0008-networking-as-implemented.md)).
