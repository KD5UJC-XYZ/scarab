<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model

**Status: Current review scope, not a completed security assessment.** O1 / [#74](https://github.com/KD5UJC-XYZ/scarab/issues/74).
This maps the implemented node's trust boundaries to intended invariants and existing regression
controls. A listed test is a starting point for review, not evidence that every attack in that
boundary has been excluded. The [external review brief](external-review.md) proposes independent
work, including [#230](https://github.com/KD5UJC-XYZ/scarab/issues/230).

## Assets and adversaries

The assets are consensus and ledger correctness; original signed/hashed bytes; agreement between
published chain state and durable UTxO generations; KES/VRF secret material and authorized signing;
service availability; and the integrity of imported state, configuration and executable artifacts.

Assume peers can collude, send arbitrary messages, withhold data, equivocate, disconnect and choose
message timing. Eclipse and long-range/history-replacement attempts must be assessed against the
actual bootstrap and chain-selection assumptions, including incomplete Genesis work. Clients admitted to a local socket or HTTP endpoint can submit hostile inputs and
repeat expensive requests. Imported files, cached archives and downloader responses remain hostile
until the relevant parser, identity and authentication checks finish. Dependency or build compromise
can replace code inside the process; an in-process native defect can bypass JVM memory safety.

Reviewers should distinguish remote timing observers, local co-tenants and a reader of key files,
backups or process memory. These have different capabilities. Full control of the node's host,
JVM or signing process defeats the assumption that its executable and active keys are trustworthy;
review still covers how secrets are copied, exposed and retained before or after such compromise.
Do not infer secure erasure from an overwritten live path or array.

## Trusted assumptions

- Operators select the correct network/genesis inputs, trusted roots, interfaces, filesystem
  permissions and import policy. Locality is an access policy, not proof that client bytes are safe.
- The OS/JVM/runtime and cryptographic dependencies execute their intended algorithms. Native library
  selection, secret/public input separation and dependency replacement remain explicit review targets.
- Durability depends on filesystem, mount and hardware honoring file/directory force and atomic
  rename. Tests of injected process failures do not establish behavior under physical power loss.
- Reference compatibility is evaluated against pinned upstream behavior and bounded captures.
  Matching a projection or accepted chain does not prove complete state identity, hostile-input
  rejection, resource bounds or secrecy. [Compatibility](../compatibility.md) records these scopes.

## Boundary register

The invariants below are requirements to examine, not blanket claims that the current implementation
satisfies them. The source links identify maintained entry points; named symbols avoid unpinned line
references. Tests should be read together with their fixtures, optional-data prerequisites and skips.

| Boundary and assets | Assumptions and invariant to examine | Entry points and existing controls |
|---|---|---|
| **B1. Network bytes → protocol state.** Availability and framing integrity. | Treat all peers as hostile. Enforce negotiated role/state, framing, queue/response budgets and deadlines; release owned resources on refusal/cancellation. A slow or flooding channel must not silently starve others. Global connection/work amplification needs review in addition to per-channel limits (G5 / [#35](https://github.com/KD5UJC-XYZ/scarab/issues/35)). | [Mux][mux], [IngressLimits][ingress], [ConnectionManager][manager]; [IngressLimitsSuite][ingress-tests], [MuxEgressSuite][egress-tests], [network limits design](../design/network-limits.md). |
| **B2. Candidate chain → adopted ledger.** Consensus, balances and byte identity. | Peer count and apparent chain progress are not trust. Validate headers, original-byte commitments, era transitions and ledger rules before publication; keep rollback and competing candidates consistent. Peer selection must not substitute for validation; full Genesis density/fork-choice work remains [G3 / #33](https://github.com/KD5UJC-XYZ/scarab/issues/33). | [Follower][follower], [HeaderRules][headers], [EraBlock][era-block]; [HeaderGateSuite][header-tests], [AdversarialScheduleSuite][schedule-tests]. The [schedule design](../design/adversarial-schedules.md) bounds its finite-seed evidence. |
| **B3. Encoded values → domain objects.** Consensus and availability. | Input length, item count, integer width, duplicates, normalization and nesting interact. Bound work without changing reference acceptance. Hash/sign original input slices where required; decoded equality alone is insufficient. Check native and generated codecs and file/import limits as well as wire CBOR. Structured fuzzing K3 / [#50](https://github.com/KD5UJC-XYZ/scarab/issues/50) covers registered targets, including storage formats and ancillary JSON; unregistered file layouts and node configuration JSON need separate coverage review. | [Cbor][cbor], [Tx][tx], [Address][address]; [DecoderRobustnessSuite][decoder-tests], [WideTxStackSuite][wide-tests], [registered fuzz targets](../../fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/Targets.scala). |
| **B4. Local clients → submission/query services.** Ledger integrity, availability and state consistency. | Trust interface access only as configured by the operator. Submission cannot bypass ledger checks. Acquired query state must remain paired with its pinned backing generation across commits/rollbacks; release views on disconnect. Expensive queries and the intentionally large local ingress profile require explicit exposure policy and resource review. | [LocalSubmission][local-submit], [StateQuerySource][queries], [QueryServer][http-query]; [LocalBackendOutcomeSuite][local-outcomes], [LocalSubmissionSuite][submission-tests], [deep-query tests][query-tests]. |
| **B5. Plutus requests → backend outcomes.** Ledger verdict and execution budgets. | Script bytes, datums, cost models and contexts originate in hostile transactions. Context preparation and verdict landing must preserve transaction/script order and declared budgets. Unsupported backend behavior supplies no consensus verdict; defects must not masquerade as a peer fault or ordinary script failure. Local submission and live chain following have different failure policies. | [Phase2][phase2], [ScalusEvaluator][evaluator], [SessionEnd][session]; [ScriptResultBlockSuite][results-tests], [ParallelPlutusSuite][parallel-tests], [LocalBackendOutcomeSuite][local-outcomes]; [result contract](../design/script-results.md), [ADR 0017](../adr/0017-fibers-end-with-values.md). |
| **B6. JVM → native cryptography.** Process memory, verdicts and availability. | Native libraries run in the node process. Audit ABI/resource selection, array lengths, allocations/frees, JNI exceptions, point validation and object lifetimes across the raw-DST adapter and dependency bindings. Startup hashes/known answers do not prove memory safety or every dependency build's equivalence. | [RawDst JNI adapter][raw-dst], [Blst][blst], [ScalusEvaluator][evaluator]; [BlstSuite][blst-tests], [PlutusBoundaryOracleSuite][boundary-tests]; [native provenance](../../blst-native/native/README.md), [ADR 0012](../adr/0012-portable-blst.md). |
| **B7. Credentials → proofs, signatures and evolved keys.** Secret confidentiality and signing authority. | Review all secret-dependent computation, copying, rendering and retained material under JIT/GC and library behavior. Certificate/key/pool/period/counter checks must govern signing and reload. Serialize evolution, persistence and use; a terminal key-update failure must prevent later signing. File replacement does not erase snapshots, backups, heap copies or storage remnants. | [Draft03Prover][prover], [Sum6KesSigning][kes], [HotKeys][hotkeys], [KesFiles][kes-files]; [Draft03ProverSuite][prover-tests], [Sum6KesSigningSuite][kes-tests], [KesRotationSuite][rotation-tests]. [ADR 0015](../adr/0015-kes-key-written-back-and-checked-reload.md) states the file-backed policy and durability limits; the separate KES agent remains [H2 / #38](https://github.com/KD5UJC-XYZ/scarab/issues/38). |
| **B8. Validated state → storage → restart.** Durable ledger/UTxO agreement. | Serialize publication against reads; keep checkpoint, chain point and generation paired. Distinguish cooperative cancellation from I/O failure and abrupt death. Refuse incompatible identities/formats before mutation; recover or refuse incomplete writes without presenting unchecked state as validated. V1–V4 snapshot readers are retired; historical conversion is not a runtime upgrade promise. | [ChainStorage][chain-storage], [SnapshotMigration][snapshot-migration]; [SessionFailureSuite][session-tests], [SnapshotPolicySuite][snapshot-tests], [IdentitySnapshotPreflightSuite][identity-tests]; [storage migration procedure](../storage-migrations.md). |
| **B9. Imports/downloads → installed database.** Authenticity, network identity and publication integrity. | Decoding and checksums are not authentication. Distinguish a raw Haskell/SCLS import's operator assumptions from Mithril certificate verification, configured trust anchor and separately authorized ancillary data. Validate untrusted paths and tables; publish only complete imports into an allowed destination. | [LedgerSnapshotImport][import], [MithrilBootstrap][mithril]; [ReferenceSnapshotImportSuite][import-tests], [MithrilBootstrapSuite][mithril-tests], [SnapshotExportPublicationSuite][export-tests]; [Mithril design](../design/mithril-bootstrap.md), [SCLS profile](../design/scls-interchange.md). |

Builds, dependencies, release artifacts and operator configuration cut across B1–B9. Trace pins,
checksums, generated code, downloaded executables, native rebuilds and CI credential permissions to
the bytes actually executed. Signed/reproducible releases and an SBOM remain
[O2 / #75](https://github.com/KD5UJC-XYZ/scarab/issues/75); owner-managed merge protection is
[#161](https://github.com/KD5UJC-XYZ/scarab/issues/161). These controls do not replace runtime review.

## Current cryptographic posture

[Draft03Math][draft-math] and [Ed25519Point][point-math] are hand-adapted, variable-time
implementations declared for public inputs only. [Draft03Prover][prover] uses
`curve25519-elisabeth` scalar/point operations for secret multiplication and nonce arithmetic,
while calling the Java helpers for hash-to-curve, challenges and public outputs. This describes the
intended split; review must establish whether every input and intermediate respects it and whether
library/JVM behavior meets the selected observer model. Package containment does not prove that split.

[Sum6KesSigning][kes] uses Bouncy Castle Ed25519 key/signing operations and explicitly overwrites
its mutable key material. The VRF key's `forget` overwrites seed/nonce arrays; derived scalars,
serialization copies and provider objects need a separate lifetime analysis. No claim of complete
JVM erasure or constant-time node execution follows from those operations. The
[file-backed KES policy](../adr/0015-kes-key-written-back-and-checked-reload.md) likewise qualifies
what predecessor overwrite and crash tests establish.

## Evidence limits and review policy

Known-answer tests, differential/conformance tests, fuzzing, finite schedules and timing measurements
answer different questions. In particular, [TimingProbeMain][timing] and the [crypto guard][guard]
are diagnostic/containment controls, not a proof of constant-time execution or secret-flow isolation.
Independent review of these properties remains [#230](https://github.com/KD5UJC-XYZ/scarab/issues/230).
The native library, JVM versions and deployment assumptions must be named in any resulting claim.

The required conformance policy is to remove Scarab-side ignores, known-failure exemptions and
comparison exclusions unless a specific JVM constraint is demonstrated and documented with a
reproducer, narrow scope and an explicit residual risk. An upstream difference, inconvenient fixture
or historical classification alone does not justify an exemption. This is a requirement for the
remaining work, not a claim that current exceptions have been eliminated. Review the retained
[Plutus harness coverage](../plutus-conformance.md), [upstream adapter coverage](../design/upstream-conformance.md)
and [query comparison evidence](../compatibility.md) individually against that policy; tracked coverage
and state reconstruction remain K2 / [#49](https://github.com/KD5UJC-XYZ/scarab/issues/49) and
M11/J3.1 / [#129](https://github.com/KD5UJC-XYZ/scarab/issues/129).

The reviewer evidence bundle (O5) is deferred until an audit candidate is selected. No bundle or
candidate-wide passing receipts are asserted here. Preserve historical source identities and skips
in the meantime. Findings use the [audit process](../process.md#findings-from-an-independent-audit):
name the boundary, assumption, violated invariant, acceptance criteria and regression evidence;
report exploitable details privately under [SECURITY.md](../../SECURITY.md).

[mux]: ../../network/src/main/scala/xyz/kd5ujc/scarab/network/Mux.scala
[ingress]: ../../network/src/main/scala/xyz/kd5ujc/scarab/network/IngressLimits.scala
[manager]: ../../network/src/main/scala/xyz/kd5ujc/scarab/network/ConnectionManager.scala
[ingress-tests]: ../../network/src/test/scala/xyz/kd5ujc/scarab/network/IngressLimitsSuite.scala
[egress-tests]: ../../network/src/test/scala/xyz/kd5ujc/scarab/network/MuxEgressSuite.scala
[follower]: ../../node/src/main/scala/xyz/kd5ujc/scarab/node/Follower.scala
[headers]: ../../consensus/src/main/scala/xyz/kd5ujc/scarab/consensus/HeaderRules.scala
[era-block]: ../../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules/EraBlock.scala
[header-tests]: ../../node/src/test/scala/xyz/kd5ujc/scarab/node/HeaderGateSuite.scala
[schedule-tests]: ../../node/src/test/scala/xyz/kd5ujc/scarab/node/AdversarialScheduleSuite.scala
[cbor]: ../../codec/src/main/scala/xyz/kd5ujc/scarab/codec/Cbor.scala
[tx]: ../../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/Tx.scala
[address]: ../../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/Address.scala
[decoder-tests]: ../../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/DecoderRobustnessSuite.scala
[wide-tests]: ../../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/WideTxStackSuite.scala
[local-submit]: ../../node/src/main/scala/xyz/kd5ujc/scarab/node/mempool/LocalSubmission.scala
[queries]: ../../node/src/main/scala/xyz/kd5ujc/scarab/node/ntc/StateQuerySource.scala
[http-query]: ../../node/src/main/scala/xyz/kd5ujc/scarab/node/Query.scala
[local-outcomes]: ../../node/src/test/scala/xyz/kd5ujc/scarab/node/LocalBackendOutcomeSuite.scala
[submission-tests]: ../../node/src/test/scala/xyz/kd5ujc/scarab/node/mempool/LocalSubmissionSuite.scala
[query-tests]: ../../node/src/test/scala/xyz/kd5ujc/scarab/node/ntc/DeepQuerySuite.scala
[phase2]: ../../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/Phase2.scala
[evaluator]: ../../plutus/src/main/scala/xyz/kd5ujc/scarab/plutus/ScalusEvaluator.scala
[session]: ../../node/src/main/scala/xyz/kd5ujc/scarab/node/SessionEnd.scala
[results-tests]: ../../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/ScriptResultBlockSuite.scala
[parallel-tests]: ../../node/src/test/scala/xyz/kd5ujc/scarab/node/ParallelPlutusSuite.scala
[raw-dst]: ../../blst-native/native/raw-dst.c
[blst]: ../../blst-native/src/main/scala/xyz/kd5ujc/scarab/blst/Blst.scala
[blst-tests]: ../../blst-native/src/test/scala/xyz/kd5ujc/scarab/blst/BlstSuite.scala
[boundary-tests]: ../../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/PlutusBoundaryOracleSuite.scala
[prover]: ../../crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/vrf/Draft03Prover.scala
[kes]: ../../crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/kes/Sum6KesSigning.scala
[hotkeys]: ../../node/src/main/scala/xyz/kd5ujc/scarab/node/forge/HotKeys.scala
[kes-files]: ../../node/src/main/scala/xyz/kd5ujc/scarab/node/forge/KesFiles.scala
[prover-tests]: ../../crypto/src/test/scala/xyz/kd5ujc/scarab/crypto/vrf/Draft03ProverSuite.scala
[kes-tests]: ../../crypto/src/test/scala/xyz/kd5ujc/scarab/crypto/kes/Sum6KesSigningSuite.scala
[rotation-tests]: ../../node/src/test/scala/xyz/kd5ujc/scarab/node/forge/KesRotationSuite.scala
[chain-storage]: ../../storage/src/main/scala/xyz/kd5ujc/scarab/storage/ChainStorage.scala
[snapshot-migration]: ../../storage/src/main/scala/xyz/kd5ujc/scarab/storage/SnapshotMigration.scala
[session-tests]: ../../node/src/test/scala/xyz/kd5ujc/scarab/node/SessionFailureSuite.scala
[snapshot-tests]: ../../node/src/test/scala/xyz/kd5ujc/scarab/storage/SnapshotPolicySuite.scala
[identity-tests]: ../../node/src/test/scala/xyz/kd5ujc/scarab/storage/IdentitySnapshotPreflightSuite.scala
[import]: ../../storage/src/main/scala/xyz/kd5ujc/scarab/storage/LedgerSnapshotImport.scala
[mithril]: ../../storage/src/main/scala/xyz/kd5ujc/scarab/storage/mithril/MithrilBootstrap.scala
[import-tests]: ../../storage/src/test/scala/xyz/kd5ujc/scarab/storage/ReferenceSnapshotImportSuite.scala
[mithril-tests]: ../../node/src/test/scala/xyz/kd5ujc/scarab/storage/MithrilBootstrapSuite.scala
[export-tests]: ../../storage/src/test/scala/xyz/kd5ujc/scarab/storage/SnapshotExportPublicationSuite.scala
[timing]: ../../crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/vrf/TimingProbeMain.scala
[guard]: ../../scripts/check_crypto_guard.py

[draft-math]: ../../crypto/src/main/java/xyz/kd5ujc/scarab/crypto/vrf/Draft03Math.java
[point-math]: ../../crypto/src/main/java/xyz/kd5ujc/scarab/crypto/vrf/Ed25519Point.java
