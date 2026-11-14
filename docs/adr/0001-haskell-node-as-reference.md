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

## Amendment (2026-11-04): exact diagnostic and refusal parity

The owner's direction supersedes the diagnostic-wording waiver recorded in
[#208](https://github.com/KD5UJC-XYZ/scarab/issues/208), including its folded continuation in
[#491](https://github.com/KD5UJC-XYZ/scarab/issues/491). Complete diagnostic and refusal presentation
is required reference fidelity, rather than optional work contingent on a consumer.

At each supported reference-facing interface, Scarab mirrors the pinned reference's complete
encoded refusal, embedded diagnostic text and corresponding CLI presentation for the same inputs
and configuration. Constructor names, field and failure order, escaping, Unicode, numeric forms,
whitespace and line layout are part of that comparison. This applies across mirrored subsystems;
there is no separate wording waiver for another printer. Existing explicitly approved deviations
outside this diagnostic-wording waiver retain their authority.

A staged implementation declares its exact coverage and records the remaining categories under
[#796](https://github.com/KD5UJC-XYZ/scarab/issues/796). Incomplete categories, missing captures and
provider-message fallbacks are not conformance passes. Comparisons have no diagnostic masks,
expected divergences or tolerance. Historical evidence retains the scope actually measured.

Scarab-specific service defects without a native rejection counterpart remain typed defects.
A presentation defect ends the affected connection under [ADR 0017](0017-fibers-end-with-values.md),
retaining its typed cause; it must not fabricate substitute rejection bytes. This does not excuse
an unimplemented reference diagnostic. If a required reference output is demonstrably variable or
otherwise impossible to reproduce, retain a minimal reproducer and proof of exposure, keep that
category incomplete, and bring the precise blocker to the owner. No agent may normalize it or
create another exception unilaterally.

[The implementation plan](../design/diagnostic-parity.md) defines the inventory, oracle and closure
work; this amendment is the authoritative policy.

## Amendment (2026-11-09): checked arithmetic and compatibility

The owner clarified the application of the [standing decision on reference bugs](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849)
during review of [#849](https://github.com/KD5UJC-XYZ/scarab/pull/849). This procedure applies the
interoperability condition; it does not grant a blanket exception for inputs that trigger a bug.

Use checked or exact arithmetic to avoid accidental overflow and narrowing. At each affected
boundary, establish the reference behavior, reachability, and effect on consensus, persisted state,
wire data, or configuration. Preserve supported behavior unless a specific owner decision authorizes
the difference. Existing decisions apply within their recorded scope; they do not authorize unrelated
refusal, saturation, or no-verdict behavior.

Record the evidence and any applicable owner decision in the compatibility register and the pull
request's deviations table. If the effect on interoperability or the scope of an existing decision
is unclear, ask the owner before changing the boundary behavior. The approval requirement in the
2026-10-25 amendment continues to apply.

## Amendment (2026-11-14): the newest release, re-pinned in stages

The owner decided on 2026-11-14 which release of `cardano-node` is the reference, and how Scarab
moves to it. The decision above names no release, so no words of it are superseded.

- **The newest release.** The reference is the newest release of `cardano-node`, a pre-release
  included, and never a release with known bugs: Scarab takes upstream's fixes and improvements.
  The target is therefore cardano-node 11.2.1 in place of 11.1.3. Its release on GitHub is marked
  as a pre-release, which its release notes say is not yet recommended for use on mainnet; the
  owner accepted that.
- **Re-pinned in stages, area by area.** The pins move one area at a time (networking; consensus and
  the node; ledger and Plutus), each in a stage of its own that amends the ADRs of its area that name
  11.1.3 or its packages (ADR 0008 for networking and ADR 0027 for Plutus among them). Until an
  area's stage lands, that area's pins stay at 11.1.3's, and its code, citations, fixtures, and the
  ADRs and decisions that name 11.1.3, stay in force. [`docs/data/reference-pins.json`](../data/reference-pins.json)
  registers both sets of pins.
- **Both reference builds are kept.** `--reference-build` keeps `11.1` (cardano-node 11.1.3), which
  reads 11.1.3 databases and state files and works with 11.1.3 peers, and gains `11.2` (cardano-node
  11.2.1), following [the playbook](../playbooks/add-a-reference-build.md). The default stays `11.1`
  until the later stages land; moving it is the owner's decision. Until then `11.2` is named, parsed
  and logged with its pins, and every command refuses it at start, because its `state` layout is
  not read or written yet.
- **Node-to-client versions 16 to 22 stay offered.** cardano-node 11.2.1 offers node-to-client
  version 23 only: `NodeToClientVersion` has the one constructor `NodeToClientV_23`
  (cardano-diffusion 1.3.0.1, ouroboros-network 4646039f
  `cardano-diffusion/api/lib/Cardano/Network/NodeToClient/Version.hs` 70-75, and its codec at 84-95),
  and its release notes list the removal of versions 16 to 22 among the changes of 11.2.0. Scarab
  keeps offering 16 to 22 beside 23, for backwards compatibility. This is an approved deviation from
  11.2.1, in force once node-to-client follows 11.2.1.

The target's revisions are those of the packages cardano-node 11.2.1 links, at the versions its
binary's link line names and the commits the CHaP metadata of each package records (typed-protocols,
which is released on Hackage only, at the commit of its version bump):

| Area | Packages | Revision |
|---|---|---|
| Node | cardano-node 11.2.1 | tag `11.2.1` at `90bc1cd9` |
| Ledger | the cardano-ledger packages | `29a7d656`, where each has the source it was released from |
| Consensus | ouroboros-consensus 5.4.0.0 | `808e4005` |
| Networking | network-mux 1.0.0.0, ouroboros-network 2.0.0.0, cardano-diffusion 1.3.0.1 | `4646039f` |
| Networking | typed-protocols 1.2.2.0 | `4493962e` |
| Plutus | plutus 1.71.0.0 | `261766ad` |
| Cryptography | cardano-base 0.1.7.1, cardano-crypto-class 2.6.0.0, cardano-crypto-leios 0.3.0.0 | `5813d211`, `12168e4b`, `242f1ebd` |
| Configuration | cardano-config 2.2.3.0 | `6ab54503` |
| KES agent | kes-agent 1.4.0.0 | `44fdcec8` |
