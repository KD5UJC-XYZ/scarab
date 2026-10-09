# Same-epoch candidate-freeze observation

This isolated profile seeds pure certificate and nonce state only from a pinned
pre-anchor protocol/ledger/genesis observation. All actual intervening original
ChainSync header envelopes and exact BlockFetch blocks must form a complete
parent-linked, consecutively numbered range ending at the independently observed
post tip. Post nonce/counter/ledger values are comparison oracles after derivation.

The fixture fixes Conway PV9.0/header11.2, network magic1082026, k=5, f=1/20 and
epochLength500, giving the exact randomness stabilization window ceil(4k/f)=400.
Candidate updates require relative slot<100; it freezes at relative slot>=100.
The test requires an actual candidate update before the cutoff and a later header
where evolving changes while candidate stays fixed. A block exactly at100 is not
assumed. Initial previousEpochNonce absence remains unknown.

The capture allows2..16 complete successors. This separate observation limit
accommodates sparse actual headers across the boundary; no intermediate header
may be dropped to fit it. The existing public eight-header collector and persisted
eight-block acquisition checkpoint remain unchanged. Overflow, gaps, changed
branches, nonempty transaction structures or an epoch crossing reject explicitly.

Each snapshot uses short producer pauses and separate CLI queries with stable tip
brackets. Producers resume before network acquisition and cryptographic replay.
The snapshots are not atomic or authenticated. Registration maps are supplied
same-epoch observations; matching endpoints do not derive registration continuity
or stake evolution. Replay checks all five exported nonce fields, lastSlot and
certificate counters, then rolls back and reapplies every prefix with exact IDs.

This is nonce-transition and VRF evidence only. Leadership, full ledger/consensus,
epoch ticks, per-epoch context expansion and CoherentBranch publication are outside
the profile. The source-linked nonce model retains the provenance limitations in
praos-nonce-evolution.md. Exporter provisioning does not alter JVM dependencies.

Run scripts/private_cluster_nonce_freeze.py with the explicit existing
conway-pv9-empty-byron-allocations-coherent-v1 fixture, pinned reference image,
private output directory and isolated compiled Scala checkout. The reference is
limited to3CPUs/6GiB and one observer to1CPU/1GiB. Workload420..540seconds is bounded
by the existing600-second lifecycle and owned cleanup. No transactions are
submitted. CLI: nonce-freeze PORT EVIDENCE_DIRECTORY.

## Completed same-epoch acceptance

The first reviewed isolated run completed in 182.040 seconds and verified owned
container/network cleanup. It retained anchor block 31/slot 513 and every successor:
block 32/slot 555 and block 33/slot 674, both in epoch 1. Relative slot 55 updated the
candidate; relative slot 174 changed evolving while leaving candidate fixed. This
is sparse-header evidence across the cutoff, not a block-at-relative-100 claim.

All five exported nonce fields and lastSlot matched after deriving both steps
from the pre-anchor snapshot. Original bytes, parent links, block numbers, empty
bodies and full certificate counters matched. Independent review recomputed the
N-prefixed double hash, evolving/candidate/LAB updates and counters from retained
bytes. Every prefix rollback/reapply restored exact paired certificate/nonce IDs.
The cross-attribution receipt test uses synthetic source attribution, not an
observed live fork. previousEpochNonce was absent in both exports and remains
unknown; no value was fabricated and no epoch tick was checked.

The initial nonce state ID was
`b5afdf5cb9f8775f7617e4e7162feb00682807d21ff0df3c02e909267eb1fee5`;
the final ID was
`0105040b33dce245ca65fb25b594f052f0a2070b6e45f67d44c5c2a2d041c62c`.
The observer stdout SHA-256 is
`e554dd3c94afaebe0be1415b337564ae2b8b73c343e14f40b6e168e5b0a2a238`.
Final convergence reached block 82/slot 1552/epoch 3.

Retained private evidence is /home/<user>/cardano-nonce-freeze-live1-20261009;
integration checks are /home/<user>/cardano-nonce-freeze-integration-20261009.
NONCE_FREEZE_EVIDENCE enables two offline replay/tamper tests against those owned
originals. Public tests separately verify 16 accepted/17 rejected capture bounds
and the unchanged legacy eight-header reader. Raw logs and cluster state remain
outside Git. The successful capture used two originals; the explicit 16 limit was
not reached. Actual epoch rotation and per-epoch context remain future work.
