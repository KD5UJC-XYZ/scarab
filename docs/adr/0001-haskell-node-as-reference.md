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
line at a pinned version. ~~Where Scala allows a real improvement (a typed gap or residue instead of a
silent default, derived fields checked on decode, a stricter type), it is taken and the reason is
written down.~~ ([superseded](#amendment-2026-10-25-no-deviation-without-prior-owner-approval)) In the owner's words: "use haskell as the reference and don't just be compatible with its
interface".

## Consequences

- Comparisons with the Haskell node become byte comparisons (ledger-state files, query answers).
- Importing and exporting Haskell snapshots and Mithril fast start become reachable.
- Upstream changes are followed by re-pinning sources and diffing, not by reverse engineering.
- ~~Networking is the stated exception ([0008](0008-networking-mirrors-ouroboros-network.md)).~~
  ([superseded](#amendment-2026-10-25-no-deviation-without-prior-owner-approval))

## Amendment (2026-10-25): no deviation without prior owner approval

This supersedes the struck-through sentence of the decision, "Where Scala allows a real improvement
… it is taken and the reason is written down". That wording was applied as permission to deviate,
which was never intended. Behaviour is mirrored exactly. Agents may not deviate from the reference
on their own accord: a deviation, even for a measured performance benefit or another strong reason,
needs the owner's explicit approval first, requested with clearly defined trade-offs and reasoning.
Only after that approval is it implemented, with a written reason that records the approval.
Existing code, earlier PRs, review notes and design documents are not approval. If exact fidelity
seems impossible, stop and ask the owner.

Networking is mirrored in structure and behaviour too
([0008](0008-networking-mirrors-ouroboros-network.md)).
