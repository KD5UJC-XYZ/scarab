<!-- SPDX-License-Identifier: Apache-2.0 -->
# External security review brief

**Status: Proposal for scoping and quotations.** O1 / [#74](https://github.com/KD5UJC-XYZ/scarab/issues/74),
with independent cryptographic review under [#230](https://github.com/KD5UJC-XYZ/scarab/issues/230).
No reviewer, dates, budget or security conclusion is implied. The maintainer selects the engagement
and authorizes expenditure. The [threat model](threat-model.md) is the proposed boundary register;
[architecture](../architecture.md) explains the modules and [compatibility](../compatibility.md)
distinguishes existing evidence from gaps.

## Objective and scope

Determine which implemented paths could cause unauthorized signing, secret disclosure, consensus
or ledger divergence, corrupted recovery state, or attacker-driven loss of service. Deliver findings
that can be reproduced and fixed, and state the assurance limits in language an operator can use.
Scarab remains research software under [SECURITY.md](../../SECURITY.md).

Cover the runtime modules `blst-native`, `codec`, `crypto`, `protocol`, `network`, `plutus`, `ledger`,
`consensus`, `legacy`, `storage` and `node`, including their dependency boundaries and import/rotation
scripts. Inspect generated `models` runtime use and `models-gen` where it affects accepted bytes or
build integrity; use `fuzz` and existing test/oracle tools as review inputs, not substitutes for review.
Leios parsing/BLS primitives and developer import tools must be distinguished from supported full
ledger validation. The [module graph](../architecture/modules.md#modules) defines the dependency direction.

Protocols include the negotiated node-to-node handshake/mux, ChainSync, BlockFetch, KeepAlive,
TxSubmission2 and PeerSharing; node-to-client ChainSync, LocalStateQuery, LocalTxSubmission and
LocalTxMonitor; and the HTTP query/metrics interfaces. Include hostile files and startup configuration,
Haskell/SCLS import, Mithril bootstrap and signing-key reload. Review actual enabled paths and local
policy limits rather than assuming all exposed interfaces share peer-network constraints.

## Prioritized work packages

These priorities allocate review effort; they are not severity ratings for findings. Quote the packages
separately, identify dependencies, and explain any proposed reordering.

| Order | Concrete questions | Expected reviewer output |
|---|---|---|
| **1. Secret operations and signing lifecycle (B7; #230).** | Trace secret-derived scalars/seeds/nonces through VRF proving, KES generation/evolution/signing, dependencies, serialization and logs. Assess constant-time posture under the selected JVM/JIT/GC and observer models. Review `Draft03Math.java` and `Ed25519Point.java` public-input restriction against every caller, not only package membership. Review certificates, period/counter checks, reload/evolution locking, cancellation and terminal failure. | Secret-flow inventory; algorithm/acceptance-profile review; explicit timing and memory-retention limits; adversarial tests; a recommendation on retaining, hardening or replacing hand-ported code with an independently reviewed library. Preserve differential suites for any replacement and verify the library's exact profile and version. |
| **2. JNI and native dependencies (B6; #230).** | Review raw-DST allocation/free and exception paths, array lengths, ABI/export agreement, SWIG/native object ownership, point decoding and subgroup checks. Inspect portable library selection and rebuild inputs; identify which native operations receive secrets. | Native boundary/ownership map, reproducible memory-safety tests where applicable, artifact/platform scope, and dependency changes required. Known-answer tests or a successful static scan must not be reported as proof of memory safety. |
| **3. Consensus and persistence (B2, B5, B8, B9).** | Check original-byte commitments, invalid-tag/collateral behavior, ordered parallel verdicts, rollback and cross-fiber error ownership. Trace validated block → store/checkpoint → published state → restart and acquired query views. Review import trust anchors, ancillary authorization, identity preflight and incomplete-publication handling. | Independent refusal/acceptance controls, crash/cancellation matrix, counterexamples for invariant violations and retestable fixes. Separate raw import correctness from authentication and durability assumptions. |
| **4. Adversarial input and availability (B1, B3, B4).** | Exercise state/role errors, oversized/partial messages, malformed CBOR/Flat, integers/duplicates/deep nesting, connection/queue/work amplification and expensive local queries. Assess withholding, eclipse and long-range scenarios against the actual bootstrap/peer-selection/fork-choice assumptions. Review per-client fairness separately from bounded transport queues. | Reproducible hostile-input and scheduling cases with measured work/resource bounds; clear scope for existing G5/K3 controls and remaining G3/fairness gaps. |
| **5. Supply chain and operational exposure.** | Check dependency/native pins, generated outputs, downloader execution, release/build permissions, signing-file handling, socket/HTTP exposure and recovery instructions. Review reproducibility artifacts when available without treating provenance as a runtime-security proof. | Reviewed build/configuration assumptions and deployment restrictions, missing checks and separately priced follow-up. Repository protection settings remain the owner's #161 decision. |

For the KES file-backed path, specifically assess staged write/readback, file/directory force,
predecessor overwrite, rename, restart recovery and symlink-generation handling. Distinguish process
crash, filesystem errors and actual power loss. Examine plaintext strings/byte copies and retained
scalars as well as the mutable key buffer. [ADR 0015](../adr/0015-kes-key-written-back-and-checked-reload.md)
records the chosen policy; it does not promise erasure of backups, filesystem history or all JVM copies.
The proposed upstream KES agent ([#38](https://github.com/KD5UJC-XYZ/scarab/issues/38)) still leaves
node-local signing and a secret-memory boundary that require review.

## Known limits to carry into scoping

- Source inspection, differential vectors and regression schedules establish bounded properties.
  Static scans, including positive controls, do not validate cryptographic algorithms; timing probes
  do not establish constant-time behavior. Review #230 independently and document whether a more
  thoroughly reviewed implementation can replace the hand-ported code without changing acceptance.
- [G5 / #35](https://github.com/KD5UJC-XYZ/scarab/issues/35) records implemented transport limits;
  [K3 / #50](https://github.com/KD5UJC-XYZ/scarab/issues/50) records structured fuzzing. Its
  [registered targets](../../fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/Targets.scala) include native
  snapshots, MemPack, snapshot tables/metadata, SCLS, ancillary JSON and network identities;
  unregistered file layouts and node configuration JSON need separate coverage review. Valid early-era whole-transaction seed coverage
  remains [K3a / #179](https://github.com/KD5UJC-XYZ/scarab/issues/179). Full Ouroboros Genesis
  density and ledger-dependent fork choice remain [G3 / #33](https://github.com/KD5UJC-XYZ/scarab/issues/33);
  finite fork/withholding tests do not close that gap. Per-client mempool admission fairness remains G5-F1.
- Conformance gaps and historical query masks are review inputs, not accepted audit waivers.
  Apply the [threat-model policy](threat-model.md#evidence-limits-and-review-policy): remove
  Scarab-side ignores unless a specific evidenced JVM constraint justifies a narrowly documented
  exception. [PR #285](https://github.com/KD5UJC-XYZ/scarab/pull/285) removed the four debug-ledger
  masks and numeric projection rounding. Its retained private run on runtime `d9d089039` matched
  330/330 complete same-point query answers, including 20 debug-state pairs; see
  [compatibility evidence](../compatibility.md). Historical masked runs retain their qualifications.
  The [coverage register](../data/test-coverage-register.json) records remaining dataset, generator
  and sampling gaps; no authentic twelve-case external candidate campaign is established here.
  Operational provenance transitions remain [#129](https://github.com/KD5UJC-XYZ/scarab/issues/129).
  Review the integrated behavior on the agreed candidate; finite devnet equality is not universal
  chain equivalence.
- No independent review or review-ready candidate evidence bundle is asserted. Signed releases/SBOM
  remain the broader O2 / [#75](https://github.com/KD5UJC-XYZ/scarab/issues/75) work; preparation of
  build reproducibility artifacts is separate from creating the O5 reviewer bundle.

## Suggested stages and deliverables

1. **Scope checkpoint before the main engagement.** Agree on the candidate revision, reference pins,
   platforms/JVMs, observer models, boundary coverage, exclusions and access needs. Return an internal
   review checklist mapped to B1–B9 and a named reviewer plan or schedule with estimated effort.
   If substantive crypto review cannot yet be commissioned, record this checkpoint and its unresolved
   review requirements under #230; it must not be described as completed specialist review.
2. **Focused crypto/native assessment.** Prioritize packages 1 and 2, escalate exploitable findings
   privately as they are found, and return the secret-flow/constant-time posture and library-replacement
   decision record. State which properties remain untested and which need a different specialist.
3. **Node integration assessment.** Review packages 3–5 against the agreed candidate, adding failure,
   parser and adversarial-schedule controls. Provide a coverage matrix with reviewed code paths,
   assumptions, methods, findings and explicit unreviewed areas.
4. **Fix verification and closeout.** Retest agreed fixes on named revisions; classify each finding
   as fixed, mitigated, accepted risk or unresolved. Deliver private reproductions, regression tests,
   an executive summary and a technically reproducible report suitable for eventual redacted publication.

Each finding should name its boundary, attacker capability, trusted assumptions, violated invariant,
impact/severity reasoning, affected revisions/platforms, source locations, reproduction and acceptance
criteria. Distinguish a source hypothesis from an executed reproduction. Follow the
[audit issue process](../process.md#findings-from-an-independent-audit); keep exploit details private
under [SECURITY.md](../../SECURITY.md) and agree on disclosure and retesting before starting.

## Quote and budget questions for reviewers

- Which named reviewers cover cryptography/side channels, JVM internals, JNI/C memory safety and
  Cardano consensus? What relevant prior work and independence/conflict disclosures can be supplied?
- For each package and stage, what are the low/base/high person-day estimates, rate/currency,
  fixed-price or time-and-materials terms, assumptions, exclusions and delivery dates? Separate
  environment setup, source review, experiments, reporting, meetings and fix verification.
- What is the smallest useful first engagement if funding is limited, and which assurance claims
  would remain unsupported? What additional work triggers a change in price or schedule?
- Can each stage have a spending cap and acceptance checkpoint? How many retest rounds and what
  follow-up window are included; how are new code, delays and newly discovered scope priced?
- What hardware, native/JVM versions, private data access, licenses or travel cost extra? Which taxes,
  subcontracting, secure reproduction storage and publication/redaction rights affect the quote?

Request a written quotation and explicit assumptions before committing funds. This brief supplies no
market-price estimate or assurance that any scope fits a particular budget.

## Inputs requested when an audit candidate exists

O5 is **deferred until candidate selection**. At that point agree what reviewers need: immutable source
and dependency/native identities; exact build/test commands; public fixture provenance and secure access
to necessary private captures; reproducibility receipts; and an honest inventory of skips, masks and
remaining conformance gaps. Existing [compatibility evidence](../compatibility.md) belongs to its recorded
source revisions. Do not relabel it as evidence for a future candidate. This document requests those
inputs; it does not create a bundle or claim that they are already assembled.
