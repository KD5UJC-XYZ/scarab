> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# Progress and verification

## Research correction 0.22.1, main-checkout verification passed

Fresh 0.22 archive verification exposed a 30-second timeout in the unchanged
4096-entry boundary test. One unchanged focused rerun passed 11.585s; the original
aggregate failure is preserved and its cause remains unproven. An isolated probe
identified 88,030 sort-key computations for 4096 entries; caching the same keys once
reduces this deterministically to 4096 with identical ordering. The patch changes
only that repeated work, retains all admission/failure/profile semantics and adds
count/order/identity regressions. No production deadline, ledger boundary or crypto change. The default check now
precompiles all modules and runs their tests explicitly in sequence. One scripted
budget test separates its five-second virtual protocol watchdog from actual Nio
checks under the existing MUnit 30-second guard; the changed test boundary and all
failed/intermediate runs are preserved explicitly.

The final normal check passed all 1,142 tests in the explicit seven-module
sequence; the 23 replay projector/tamper and 19 direct-JVM CLI cases passed.
Independent final review passed. Parent fresh-archive acceptance will run
all 30 scripts; the old 0.22 run is not reattributed to this patch.
See [diagnosis and evidence](restricted-replay-sort-key-fix.md).

## Research milestone 0.22.0, 2026-10-08 UTC

Added bounded pure Conway PV9 ADA-transfer UTxO/fee replay with same-state input
resolution, original-byte predicates, atomic batch rejection, exact undo and
in-memory revision fencing. Two genuine archived traces share one initial map;
both independently archived final output maps are retained byte-for-byte. A bounded
read-only trace CLI observes accepted/rejected events without persisted adoption.

Independent isolated source review found and closed one assembled-state decoder
item-cap bug; the exact near-limit regression is included. All 23 portable offline
projector/reproduction/tamper checks passed before integration. Formatting, first compile and all 39 focused tests passed without corrections. The
full aggregate passed all 1,138 tests. All 30 serial scripts passed on their first
uninterrupted attempt under APC4 with unchanged guards, including the replay CLI’s
19 cases. All 13 fixture checksum manifests pass; runtime remains 26 native-free
jars with five negative guards. Fresh-archive acceptance remains separate.

No complete ledger, tick/epoch, instant stake, cross-process revision owner, durable
store or crash/restart claim. A genuine two-success dependent trace remains a
separate future fixture gate. See [contract](restricted-replay.md) and
[verification record](restricted-replay-verification.md).

## Research milestone 0.21.0, 2026-10-08 UTC

Added one bounded, owned-parse full-block partial-evidence pipeline and read-only CLI.
Original body commitments, header-derived OpCert and source-candidate Sum6 inputs,
and supplied timing/optional hashed nonce are bound to original block/header,
profile and context identities. Missing nonce is explicitly unchecked. Existing
index/body/acquisition APIs and stored identities remain unchanged.

The final main-checkout aggregate passes **1,099 tests**. The new direct JVM CLI
passes **52 cases**; the old body-commitment CLI passes all 25 unchanged cases.
Independent source/corpus/resource audit and read-only review pass. Runtime remains
26 native-free jars with all five negative admission guards passing.

The first 27-script attempt passed 24 gates then timed out in the unchanged
body-commitment oversized-file preflight; the failure and an inconclusive successful
bounded reproduction are retained. Final-source aggregate and the remaining gates
25–27 plus runtime checks passed after a small Unsupported-envelope diagnostic
refinement. This is not an uninterrupted final-source serial pass. Fresh archive
acceptance is pending. See [the complete record](block-evidence-verification.md).

Evidence includes 13 source-labelled full-block positives and two network-unestablished
conditional cases (one Sum6 pass, one rejection) under explicit 129600/62 assumptions.
No epoch nonce was guessed and no full-block VRF positive fabricated. General reference
serializer/decoder parity, issuer authorization, counter state, nonce derivation,
leadership, ledger application and selected-chain validity remain unimplemented.
No signing/key creation, runtime dependency or public network request was added.

## Research milestone 0.15.0, 2026-10-08 UTC

Expanded the shared original-byte structural index to all six post-Byron eras:
Mary tag4/four-field TPraos, Alonzo tag5/five-field TPraos, and Babbage/Conway
tags6/7/five-field Praos with nested OCert/ProtVer and one VRF certificate.
Original header slices/hashes, parent, unsigned slot/height, raw bytes and SHA-256
remain preserved. Persisted record labels share the index whitelist; v1 manifests
remain compatible with existing Shelley/Allegra stores without migration.

Formatted aggregate check passes **414 tests**: 93 core, 61 ledger, 13 VM,
83 network, 91 app and 73 fetcher, preserving the prior 388-test baseline.
Coverage adds eleven independently expected originals, all-six-era structural
mutation boundaries, five linked preprod Babbage slices, concrete null-parent
rejection, original Praos encoding identity, body-only mutation limitations,
Babbage exact-point/failure batches, importer/store/inspection/full overlap resume,
interrupted range reacquisition and source network-label/magic identity binding.

The five-block Babbage corpus uses its independently indexed first block as the
exclusive anchor and selects the remaining four under unchanged direct-range caps.
No unknown predecessor slot is fabricated. Mary is mainnet-source-provenanced;
selected Alonzo/Babbage/Conway singles have unestablished networks. Portable
fixtures retain independent expectations, exact chunk reconstruction, Pallas Git
blob identities, source contract pins, licenses/notices and checksum verification.
See [scope and next gates](post-byron-indexing.md).

All **21 serial regression scripts** passed (20 prior CLI/projector/corpus/runtime
gates plus the portable post-Byron audit); local chain-fetch CLI coverage is now
21 cases. Every fixture SHA256SUMS check passes. Runtime remains **26 native-free
jars** and the dependency inventory remains **193 artifacts**. No unsafeRunSync.
Build and regression evidence is retained in `post-byron-build-verification.log`
and `post-byron-regression-verification.log`.
Independent source review found no remaining blocker and separately re-ran the
portable fixture audit successfully.
No new acquisition, live endpoint, denied-route retry, native oracle, keys or
publication occurred. Indexing remains a concrete-parent-only bounded structural
subset, not exact reference numeric widths/protocol-version acceptance, body
commitment checking, ledger/consensus validation or replay. Byron stays unsupported
until reference hash/point conversion and kind-aware equal-slot continuity gates
are implemented across the complete pipeline. Strict slot ordering is unchanged.

## Research milestone 0.14.0, 2026-10-08 UTC

Added fixture-only direct inclusive-range acquisition through one owned NtN14
handshake/BlockFetch3 connection, pure original-header-derived EndpointBatch
verification and prevalidated small-batch composition with the existing resumable
segment store. Concrete exclusive anchor and inclusive wire endpoints stay distinct;
BatchDone and empty buffered remainder are required before any raw cursor exists.
A canonical pinned descriptor binds raw source provenance and returned byte hashes.
Shared owner/framing/admission/epoch/state+whole deadlines moved to `network-runtime`
with unchanged public ChainSync APIs; finite independent literal script transport is
reusable. No ChainSync discovery, simultaneous protocols or live endpoint was added.
See [API, resource bounds and recovery contract](direct-range-source.md).

Formatted full check passes **388 tests**: 74 core, 61 ledger, 13 VM,
83 network, 91 app and 66 fetcher, preserving all 359 prior tests. New evidence covers
both retained four-block windows, singleton, independent endpoint/list/count checks,
no checkpoint advancement on incomplete acquisition, full verified overlap replay,
raw provenance binding, exact four-block/4MiB and 1MiB boundaries, fragmented multi-SDU
messages with framing headroom, coalesced handshake remainders, state/whole virtual
time deadlines, queued/admitted cancellation, close-failure preservation and verified
prefix resume after append cancellation.

All **20 serial CLI/projector/corpus/dependency/runtime scripts** and fixture SHA256
checks passed. Runtime remains **26 jars**, with no bundled native libraries or
excluded native crypto artifacts; dependency inventory remains **193 artifacts**.
Independent source review found no remaining blocker after admission, whole-expiry
and failed-handshake capability fixes. Build and regression logs are retained in
`direct-range-build-verification.log` and `direct-range-regression-verification.log`.

No new raw blocks, live endpoint/contact, native oracle, keys, remote publication,
denied-route retry, network authentication, ledger/consensus verification, Mithril
verification or reference-node replay was performed. Existing localhost regressions
remain separate. Future real-relay support requires an authorized interpreter and
version/data/diffusion/KeepAlive compatibility evidence; general sync additionally
requires header selection, rollback and consensus. No persistent historical
BatchDone certificate or later-era indexer was added.

## Research milestone 0.13.0, 2026-10-08 UTC

Added pure non-pipelined NtN BlockFetch protocol3 state/codec, strict tag24 definite
raw-block wrapper with immutable original bytes, Cardano specific inclusive ranges,
and bounded ordered-point request completion only after BatchDone. The injected
identity extractor remains a trust boundary; no header/parent/ledger verification
is claimed. Existing ConnectionSession remains ChainSync-only and the local fetcher
still has no direct-node source. See [contract and next seam](block-fetch.md) and
[portable provenance](block-fetch-provenance.md).

Final formatted full check passes **359 tests**: 74 core, 61 ledger, 13 VM,
83 network, 91 app and 37 fetcher, preserving all 331 prior tests. Coverage includes
all agency/state combinations, exact source literals, prefix cuts, immutable raw
bytes, oversized advertised payloads, full 2.5MB codec ceiling, preserved ChainSync
65535-byte policy, same-slot distinct points, wrong-order/short/extra batches,
NoBlocks and terminal completion, plus offline CLI checksum/oversize rejection.

All **20 serial CLI/projector/corpus/dependency/runtime scripts** passed, including
17 existing local acquisition CLI cases and three new BlockFetch CLI cases. The
new portable audit verifies 17 fixture/source/license records and exact derivation
from the retained Conway golden. Runtime remains 26 jars with no bundled native
libraries. Existing localhost-only regression gates remain simulation, not live-peer
or reference-node evidence. No new sockets, external peer endpoint, native oracle,
key operation, dependency or denied-route retry was added.

Independent review found no blocking issue; its impossible-arity prefix observation
was fixed and regression-tested before the final check. Initial draft compilation
issues (inherited decode qualification and enum-case test type parameter) were
corrected before the final terminal success. Evidence: `block-fetch-build-verification.log`,
`block-fetch-regression-verification.log` and `cli-block-fetch-*.log`.

## Research milestone 0.12.0, 2026-10-08 UTC

Added pure checked original-byte Shelley/Allegra block indexing and a reusable
Cats Effect `fetcher` module: tagless-final source/store interfaces, pinned local
manifest source, bounded orchestration, exclusive directory locking, immutable
objects/manifest ancestry, atomic checkpoint publication, verified resume, and
thin `chain-fetch run/inspect` commands. Both retained four-block historical
windows run through the public library and CLI. See the [exact supported subset,
quotas, storage contract and evidence limits](local-chain-fetcher.md).

Final formatted full check passes **331 tests**: 74 core, 61 ledger, 13 VM,
57 network, 89 app and 37 fetcher, preserving all 286 v0.11 tests. New evidence
includes eight independently pinned original blocks, original header encoding
preservation, UInt64 extrema, malformed CBOR, strict configuration, exclusive
anchor/inclusive end selection, bounded source scanning, fail-on-fork behavior,
15 append write/force/install failure boundaries, interrupted initialization,
cancellation while reading and during an admitted commit, released-handle safety,
exclusive lock admission, corruption, incompatible identity, source/store binding,
quotas, unsafe paths, read-only inspection and JSON diagnostic escaping.

All **17 new direct-JVM acquisition CLI cases** and the **18 preserved CLI,
projector, corpus, runtime and dependency audit scripts** pass. The application
runtime remains 26 jars with no bundled native libraries; the complete build/test
cache inventory records 193 artifact checksums. No new runtime provider, socket,
HTTP/archive fetch, native verifier, signer, prover or key operation was added.
Build and regression evidence is in `chain-fetch-build-verification.log` and
`chain-fetch-regression-verification.log`.

Independent review led to closed-handle protection, quota-before-initialization
writes, safe interrupted-empty-initialization recovery, read-only inspection,
explicit source/selection binding, a single terminal CLI print outside its
acquisition timer, and stronger cancellation/fault tests. The CLI timer includes
source/store initialization after bounded config loading; blocking file operations
remain cooperatively cancelable at documented boundaries.

This milestone deliberately uses strict TSV rather than the larger proposed JSON
network/genesis schema. Only Shelley and Allegra disk envelopes are supported.
Byte-preservation, header hash and parent linkage do not authenticate the source
network or validate the chain. Genesis, Mithril authentication, consensus, ledger
replay, Byron indexing, remote acquisition and large-scale heap benchmarking
remain absent. Admission accounting is conservative; immutable full manifests
have bounded quadratic metadata cost. Directory fsync/atomic rename support is
platform-dependent and the tool assumes stable, exclusively owned local paths.

## Research milestone 0.11.0, 2026-10-08 UTC

Added connection-owned Cats Effect/tagless-final handshake-to-ChainSync fixture
sessions with checked NtN14/header and NtC16/full-block profiles, bounded shared
mux framing, role-bound agency and offered-intersection checks, monotonic deadlines,
serialized operations and cancellation-safe terminal epochs. The distinct
`chain-sync-session-selftest` drives independently assembled byte scripts while
`chain-sync-selftest` remains pure. See [session contract](chain-sync-sessions.md).

Formatted full check passes **286 tests** (66 core, 61 ledger, 13 VM, 57 network,
89 app), preserving all 252 v0.10 tests. New TestControl/Deferred evidence covers
phase leftovers, message/buffer limits, 7807-byte NtC segmentation, state deadlines,
queue cancellation, late writes, bounded traces, terminal negotiation and resource
release. Finite byte peers compare two 14-transition synthetic fork traces to the
pure model and explicitly consume Done before closing. Two responder scenarios
exercise proposal/application coalescing. A paired Loopback test proves both typed
query/refusal outcomes are received before physical resource release.

Independent review corrected a terminal-result self-close race, made duration
sampling interruptible, strengthened queue/partial-send milestones, and replaced
terminal-response dropping with logical termination followed by Resource-owned
physical close. Scoped evidence does not claim an exhaustive fiber census.

The 18 serial CLI/projector/corpus/runtime/dependency audit scripts pass, including
new session command argument rejection. The runtime remains 26 jars without native
libraries; cats-effect-testkit 3.6.3 is test-only. Full build and regression logs are
`chain-sync-session-build-verification.log` and
`chain-sync-session-regression-verification.log`. No new endpoint, live peer,
reference socket, native code, signing or validation claim was added. Existing
localhost regression tests remain separate from the new in-memory milestone.

## Research milestone 0.10.0, 2026-10-08 UTC

Added the pure non-pipelined ChainSync envelope/agency/state codec, checked UInt64
point/tip API, byte-preserving distinct opaque NtN/NtC fixture adapters, and bounded
synthetic intersection/fork model. This intentionally defers the CE connection/session
handoff to a separate milestone. Existing handshake transports are unchanged.
See [contract, pins, provenance and deferred gates](chain-sync.md).

Full formatted check passes **252 tests** (66 core, 61 ledger, 13 VM, 56 network,
56 app), preserving all 215 previous tests. New coverage includes exact source/manual
literals, all agency/state combinations, definite/indefinite intersection forms,
Origin tip normalization, UInt64 endpoints, every truncated prefix of all four retained
payload envelopes, seeded fragments, suffix preservation, bounds and malformed CBOR,
first-match intersections, same-slot forks, failed intersections and explicit rollback
errors. Independent source/API review corrected standalone structural bounds and
adapter definite-shape checks before acceptance.

Retained typed serialization examples are distinguished from ASCII placeholder
serialization-envelope goldens; derived RollForward envelopes use synthetic tips.
Neither supplies valid-header, valid-chain or whole-runtime-transcript evidence.
The offline selftest reports zero transport exchanges and false runtime/header/block/
ledger-rollback/live-peer validation flags. Full build evidence is in
`chain-sync-build-verification.log`; portable fixture/CLI and prior regression evidence
is in `chain-sync-regression-verification.log`. No dependency, live endpoint, listener,
reference socket retry, signing, native runtime or publication was added.

## Research milestone 0.9.0, 2026-10-08 UTC

Added a pure explicit Praos alpha/certificate layer with checked unsigned Word64
slots, separate neutral/hash nonces, Blake2b-256 alpha, and mandatory comparison
to the separately claimed 64-byte output. Typed malformed/proof-rejected/output-
mismatch/internal/verified-certificate outcomes never claim header validity.
See [bounded contract and provenance](praos-certificates.md).

Full formatted Scala check passes **215 tests** (66 core, 61 ledger, 13 VM,
25 network, 50 app), preserving all 202 prior tests. Four public archived preprod
certificate positives pass using independently source-bound epoch165/166 nonces;
historical chain inclusion is unestablished. The 18-row pinned demo includes
16 original native mutations plus two additional epoch166 positives. Fifteen
independent Python/source-derived alpha controls cover unsigned endpoints and
neutral/zero/ascending nonces. The source-derived expected values are not
reference-Haskell goldens and are never generated by the Scala implementation.

Portable admission validates file/source hashes, public header field extraction,
nonce-to-slot/epoch source binding and independent alpha. Fifteen negative
projector cases and seven direct-JVM CLI checks pass. Independent source/API/
fixture-boundary review passed; runtime dependencies remain unchanged.
Full formatted build evidence is retained in `praos-build-verification.log`.
No TPraos, leader threshold, pool-key binding, KES/opcert, nonce evolution, full
header decoder, native runtime, signing/proving, live network or publication.
A prior network-demo CLI check timed out at its existing three-second limit
while build/regression JVMs overlapped. The serial rerun passed all four network
CLI checks; this remains a timing-sensitive test observation, not evidence of
changed network behavior. No network code or timeout was changed.
The earlier two wrong-nonce header rejections are explicitly corrected by the
source-pinned epoch166 evidence, rather than labeled invalid headers.

## Research milestone 0.8.0, 2026-10-08 UTC

Added pure closed Conway PV9 exact memo-byte transfer fee and size predicates,
with checked script/datum-free resolved outputs and separate evaluated outcomes.
Original witness-map bytes now accompany original body bytes. Parameter bounds
are uint64 coefficients and uint32 maximum size; arithmetic stays BigInt.
See [scope, provenance and stronger deferred gates](fee-size-predicates.md).

Full formatted Scala check passes **202 tests** (55 core, 61 ledger, 13 VM,
25 network, 48 app), preserving all 183 prior tests. The bounded fee-size demo
reports both originals passing fee/size; the second independently fails balance
by −3 lovelace, consistent with its separate archived whole-ledger rejection.
No fresh Haskell fee/size predicate execution or transaction-validity claim.
Historical CLI estimator observations are explicitly normalization evidence,
not arbitrary raw-byte sizing or genuine negative conformance.

The portable pinned importer regenerates all six derived artifacts, rechecks
exact source/output ranges and full final UTxO bytes after applying only the
accepted event. Eleven portable/reproduction/tamper checks pass, along with
seven direct-JVM CLI admission checks. All 11 source-provenance hashes and the
fee-size checksum manifest verify. Resolved runtime remains 26 audited jars
without excluded native artifacts or bundled native libraries. No keys,
signing, native runtime dependency, live network or publication was introduced.

Verification: `./scripts/sbtw scalafmtAll check 'app/run fee-size-demo'
app/runtimeClasspathFile`, `python3 scripts/verify-fee-size-projector.py`,
`python3 scripts/verify-fee-size-cli.py`, and `python3 scripts/runtime-inventory.py`.
Full build evidence is retained in `fee-size-build-verification.log`.
Independent final review passed API/closure/arithmetic/provenance boundaries,
all 34 fixture checksums, current importer check, seven direct-JVM cases and
`git diff --check`, without running a competing build.

## Research milestone 0.7.0, 2026-10-08 UTC

Added an explicitly experimental public-only draft03 VRF verification primitive and pinned `vrf-demo`. Immutable inputs/output, typed malformed/rejected/internal outcomes, a 1 MiB alpha bound, 8 MiB CLI file bounds and package-private Java math keep the API narrow. Canonical s, native modulo-L scalar-negation multiplication, negative-zero Gamma decoding and original Gamma transcript bytes are preserved. No unchecked public proof-to-hash, signing, proving, key generation, protocol-alpha builder, native runtime or live network was introduced.

Full Scala check passes **183 tests** (55 core, 45 ledger, 13 VM, 25 network, 45 app), preserving all 163 prior tests and adding 20. The actual resolved BC 1.85.2 runtime matches all 7,812 retained differential rows: 2,048 original; 2,338 additional, of which 2,021 intentionally repeat originals; 3,426 instrumented native helper/trace comparisons. Three generic published VRF positives are the only authentic positive fixtures. A separately implemented Python affine model contributes 104 additional public arithmetic/decoder controls, all matching. Neither row totals nor helper instrumentation imply independent accepted proofs or official sodium APIs.

Portable fixture verification validates 25 pinned files and deterministic native-oracle projections. Five temporary-copy checks exercise mutation/missing/unmanifested failure and exact independent affine regeneration. Seven direct-JVM VRF CLI checks pass. Existing direct-JVM CLI regressions also pass: 13 general/VM, 10 witness, 7 coverage, 6 ledger and 4 network. Resolved runtime audit passes 26 jars with no excluded or bundled native libraries; five audit negatives include missing/modified BC1.85.2. Source/license provenance is retained, with CCL MIT adaptation attribution and sodium ISC notices. Independent source/arithmetic/API review found no blocking issue. Initial test-only cwd and fatal-error interception mistakes were fixed before this final aggregate pass.

See [VRF contract, reproduction, evidence and limitations](vrf-verification.md). Accepted mixed-order proof coverage, authentic Cardano/header positives, protocol alpha/claimed-output integration, universal equivalence, cryptographic audit and official-node binary equivalence remain unestablished. This is an offline research milestone, not completed consensus VRF integration. No upload, release, remote push or publication was performed.

## Research milestone 0.6.0, 2026-10-08 UTC

Added closed Conway PV9 required payment-key coverage from actual spending references, resolved address-bearing outputs and raw VKeys. Body fields are exactly 0/1/2; address kinds 0/6 only. Unsupported fields and contexts fail closed, including field 14, scripts, certificates, withdrawals, mint and collateral. Empty/missing inputs are explicit prerequisites; immutable privately constructed projections preserve original bytes and reuse existing core witness types and ledger input references without dependency cycles.

The genuine pinned Haskell MissingVKeyWitnessesUTXOW pair demonstrates the important distinction: both provided signatures verify and both values conserve, but one transaction lacks Alice’s payment key. Its exact missing hash is reconstructed from raw addresses and keys; the archive stores a rejection Boolean and pinned generator code asserts the failure, not a serialized hash diagnostic. Exact original body hashes resolve the second event’s inputs, and applying only the accepted event reproduces final UTxO addresses and values. No full ledger transition or fresh Haskell test execution is claimed.

Final Scala verification passes 163 tests (39 core, 45 ledger, 13 VM, 25 network, 41 app), retaining all 133 prior tests and adding 27 synthetic boundary tests plus three genuine-pair integration tests. A real signature-only mutation retains coverage but fails signature verification. Reproducible pinned fixture projection, source/parameter/archive/license admission, negative importer checks, separate three-predicate coverage CLI, and public-key CLI hash reproduction are included. See [coverage scope](required-key-coverage.md), [fixture provenance](../fixtures/coverage/README.md), and [verification record](coverage-verification.md).

JVM runtime dependencies remain unchanged. No productive key material, signing, key generation, external peers, node process, socket retry, or publication. This is bounded archived Conway PV9 coverage evidence, not general Cardano transaction validation.

## Research milestone 0.5.0, 2026-10-08 UTC

Added a public-only experimental strict Ed25519 predicate over the actual pinned Weavechain curve25519-elisabeth 0.1.3 Maven artifact. Exact-size immutable key/signature/body-hash values, narrow input errors, rejected-signature results and typed unexpected implementation failures remain distinct. The verified composition enforces canonical encodings, rejects small-order points and S >= L, hashes original R/A/message bytes with SHA-512 and checks the uncofactored equation. Mixed-order points are not categorically rejected. The Cardano adapter verifies the 32 raw BLAKE2b-256 bytes of the exact original body; no signing, secret-key, timing or production claim is introduced.

All 2,219 public-input fixtures match: 2,207 fixed-size differential cases and 12 malformed signature lengths. All eight genuine ledger signatures verify against original-body hashes, including five from ledger-rejected transactions. Three published cases expose direct BC cofactored acceptance differences; the required accepted mixed-order case remains accepted. The separate pinned-only fixture projection can extract vkeys from archived script-bearing envelopes without claiming generic support or script validation. The public envelope parser stays deliberately vkey-only and reports unsupported context before cryptography. Fixtures preserve source status, offsets, public-only inputs, immutable admission pins, licenses and offline reproduction; see [verifier scope](witness-verification.md) and [provenance](witness-provenance.md).

A separate research build of exact Cardano sodium fork commit dbb48cce5429cb6585c9034f002568964f1ce567, with ED25519_COMPAT demonstrably disabled, now agrees with every fixed-size expected result and all eight witnesses. This strengthens the earlier system-libsodium-only evidence. It is a separately built source oracle, **not the shipped node binary**; official release compiler/flags and binary equivalence remain unestablished. Native binaries are not distributed or included in runtime dependencies. No full ledger or witness validation, exhaustive encoding proof, reproducible arithmetic-jar build, or security audit is claimed.

Final formatted build passes **133 JVM tests** (39 core, 13 VM, 25 network, 18 ledger, 38 app), retaining all previous 103 and adding 30. Boundary tests include array aliasing/equality, concurrent calls, canonical/off-curve/small-order points, scalar limits, BC differences, mixed-order acceptance, exact-body reordering/alternate integer widths, malformed/unsupported envelopes, message-domain mistakes, local genuine-witness mutations and unexpected-failure discrimination. All CLI demos pass: six codec checks, five evaluator vectors, both localhost-network command names, four ledger predicates, witness corpus and help. Direct-JVM regressions pass 13 VM/codec, four network, six ledger and ten witness cases. The optional offline Haskell CLI's six decode/txid checks and 14 Python harness tests were rerun unchanged; those are not signature or ledger-transition evidence.

Runtime audit records 26 jars and enforces the inspected Weavechain binary SHA256, with no excluded native artifacts or bundled native libraries. Three isolated audit regressions reject a synthetic native file, wrong arithmetic artifact hash and missing arithmetic artifact without ever executing a synthetic jar. The source artifact hash matches the inspected published-source pair. No unsafe synchronous effect runner is present. A preexisting stale checksum for the project-authored fixture-license README was corrected when adding witness attribution; upstream fixture bytes are unchanged.

## Research milestone 0.4.0, 2026-10-08 UTC

Added a project-owned pure `ledger` module implementing the Conway PV9 transfer/mint-only `ValueNotConservedUTxO` predicate. Strict complete-body decoding admits fields 0/1/2/9 only and establishes that unmodeled certificates/refunds/deposits, withdrawals, proposals, donation, collateral and other contexts are absent. Exact immutable values, byte-keyed identities, zero normalization and signed mint/burn splitting compute totals from real resolved UTxO inputs, never fixture totals. Unsupported context and missing inputs are typed failures; `PredicateSatisfied` is not transaction validity.

The four genuine Haskell-derived cases match: two accepted setup predicates, an exact 3-lovelace overproduction, and a burn of two tokens with only one available. Both complete raw sequences, PV9 parameter bytes, source snippets, upstream notices and the full pinned Blueprint archive are preserved. The dependency-free projector verifies raw/archive byte equality, source/license hashes, strict CBOR structure, exact amounts, complete input resolution and final UTxO value projection; its generated JSON includes byte ranges and provenance. It does not execute a full ledger transition. Node 11.1.3 source review supports this equation, but these archived PV9 cases are not newly generated target-version differential evidence.

Final `scalafmtAll check` passed 103 JVM tests (16 core, 13 VM, 25 network, 18 ledger, 31 app), preserving all prior 82 and adding 21. All CLI demonstrations passed: six codec checks, five evaluator vectors, four localhost handshake exchanges, four ledger predicates and help. The 18 ledger tests include 32-seed permutation/splitting metamorphic loops, fee mutations, exact >2^53/uint64 arithmetic, per-asset cancellation traps, typed unsupported/missing-input cases, duplicate semantic keys including alternate wire widths, malformed/range boundaries and strict resolved-input decoding. The offline projector passes normal and Python `-O` reproduction, including parser negative cases, signed-mint boundaries and every retained checksum. Serial direct-JVM regressions passed 13 existing VM/codec cases, four existing network cases and six new ledger admission/error cases. Fixture digests are pinned in code and cannot be replaced by an edited adjacent manifest. No production dependency was added; the runtime archive inventory remains 25 jars without excluded native artifacts or bundled native libraries. The optional native Haskell CLI and its 14 Python regression tests were rerun successfully, retaining separate decode/txid-only claims. Ledger `oracleRunHere=false` remains correct. See [ledger scope and evidence](ledger-conservation.md).

## Additional offline reference evidence, 2026-10-08 UTC

The optional `scripts/reference-cli-checks.py` executed the official native Haskell cardano-cli 11.2.3.0 from node release 11.1.3 against all three original full-transaction fixtures. Six checks passed: exact transaction-ID agreement with existing upstream expectations, plus Conway JSON decoding/views. `scripts/reference-cli-tests.py` passes 14 dependency-free tests covering intact byte wrapping, corrupt manifest/fixtures/binary, unsupported platform, wrong version/hash/view, nonzero exit, stderr, timeout, execution/encoding errors and stale output protection. Failures are harness errors, never ledger rejection evidence.

The CLI binary is optional, explicitly selected and pinned by SHA-256/full version; neither it nor its archive is bundled or downloaded by the harness. No Scala/build/runtime dependency changed. Default Scala tests retain their existing offline behavior except dependency fetching (network-module tests use localhost simulation). No node startup, socket retry, network query, key use, ledger transition or script validation occurred. [Reproduction, provenance and limits](offline-reference-checks.md); [captured evidence](../fixtures/reference-cli/README.md).

The historical milestone statements below describe their original verification state. This new evidence supersedes only the earlier broad absence of native Haskell decode/hash checks, not the ledger, VM or networking oracle gates.

## Research milestone 0.3.0, 2026-10-08 UTC

Added a pure `network` module for pinned NtN/NtC protocol-0 handshake and mux SDU codecs, bounded incremental framing and negotiation transitions. Cats Effect resources own bounded in-memory streams and real ephemeral IPv4 localhost TCP pairs. The demo uses literal fixture-scripted peers, covering NtN14, NtC16 preview/mainnet and NtC23 query. No external peer, chain sync or application mini-protocol is exposed.

Final acceptance: `./scripts/sbtw scalafmtAll check 'app/run' 'app/run vm' 'app/run network-demo' 'app/run --help' app/runtimeClasspathFile` passed 82 tests (16 core, 13 VM, 25 network, 28 app), six existing codec checks, five existing VM checks and four actual TCP loopback exchanges. Malformed, fragmented, coalesced, truncated, oversized, illegal-state, unoffered-version, magic, whole-phase timeout, cancellation and resource-release paths are covered. `verify-cli.py` retains 13 direct-Java cases; `verify-network-cli.py` adds four CLI cases and checks all ten fixture byte hashes.

No new production dependencies. Refreshed application archive audit remains 25 jars with no excluded native artifact or bundled native library. Pure network code depends on core only; all effects remain in app. Source review corrected indefinite refusal-text acceptance and explicitly retained stricter local refusal-arity and buffer limits. Review also checked source-pinned version sets, suite/direction bits and map ordering. See [network scope/evidence](network-conformance.md), [fixture manifest](network-fixtures.json), `build-verification.log` and `cli-network-*.log`.

Evidence is source-conformance plus local transport simulation. `referenceRuntimeChecked=false`: no Haskell-generated fixture or actual-node handshake was completed in this release. A pinned reference exporter or separately documented local node exchange remains the next stronger gate. No general node interoperability, historical ledger compatibility or production-readiness claim.

## Research milestone 0.2.0, 2026-10-08 UTC

Added a separate Scalus 1.3.0 fixture-only evaluator module and Cats Effect CLI. Four official Plutus 1.63 success vectors match result/CPU/memory exactly, and one official division-by-zero vector matches evaluator failure. Twelve derived exact/CPU-minus-one/memory-minus-one runs check budget boundaries. Reference machine/builtin parameters and all named fixture files are byte-pinned. Unsupported crypto/parser/backend paths remain distinct from ordinary evaluator failure.

Final acceptance: `./scripts/sbtw scalafmtAll check 'app/run' 'app/run vm' 'app/run --help' app/runtimeClasspathFile` passed 38 tests (16 core, 9 app, 13 VM), six codec checks, five VM checks, and help. Thirteen direct-Java CLI cases passed, including changed sources/expectations/budgets, swapped triples, oversized input, BLS ingress, missing paths, malformed input, mismatch and usage errors. Reproduce with `python3 scripts/verify-cli.py` after exporting the runtime classpath. A synthetic jar containing `native/test.so` was rejected by the runtime audit; the real classpath was restored and re-audited. Independent reviewer confirmed five VM and six codec cases, swap/oversize/BLS rejection, 17 source/parameter hashes, upstream licenses, and no native runtime artifacts.

Final evidence: `build-verification.log`, `cli-vm-*.log`, `runtime-dependencies.json` and `vm-provenance.json`. Runtime archive audit: 25 jars, no excluded native artifacts or bundled native libraries. Original 20 tests and six codec/hash CLI cases retained. See [VM boundaries](vm-conformance.md) for explicit admitted scope and the Plutus 1.63 versus roadmap 1.70 mismatch. Full ledger/node capability remains future work.

The 0.1.0 statements below describe the earlier delivered milestone; its deferred VM work is superseded only by the narrow scope above.

## Historical research milestone 0.1.0, 2026-10-08 UTC

Delivered a new local Scala 3 monorepo, original Apache-2.0 implementation, pinned upstream fixtures, bounded byte-preserving CBOR, explicit byte-first serialization/hash contracts, Java Blake2b, and Cats Effect tagless-final fixture verification CLI. No prior project migration was required. The wider architecture roadmap is included as future design.

### Verified on the final implementation

- `./scripts/sbtw check 'app/run' 'show app / Runtime / fullClasspath'`: PASS. Scalafmt check and 20 tests (16 core, 4 application), then six CLI matches.
- Compiler options include deprecation, feature, unchecked and warnings-as-errors.
- Direct Java CLI: valid corpus exit 0; deliberately mismatched expected hash exit 1; malformed and missing fixture input exit 2. Evidence is in `cli-*.log`. sbt may normalize nonzero application statuses to build failure status 1.
- `python fixtures/extract_verify.py /tmp/cardano-ledger-translations.cbor`: verified six original byte slices, six TSV rows, source offsets and three expected transaction IDs from Haskell-produced TxInfo. Source corpus download/reproduction commands are in fixture provenance.
- `(cd fixtures && sha256sum -c SHA256SUMS)`: all 13 files passed.
- Independent reviewer parsed source corpus/selected TxInfo, compared fixture raw/TSV bytes and SHA256, and separately recalculated Blake2b-256: all passed.
- `bash -n scripts/sbtw`: passed. Source scan found no unsafe synchronous effect runner.
- Runtime classpath contains nine dependency jars (Scala libraries, Cats/Cats Effect, Bouncy Castle). Archive scan found no bundled `.so`, `.dll`, `.dylib`, or `.jnilib` files. Exact versions/hashes in runtime-dependencies.json. This is a scoped inventory, not a formal native-code/license audit.

### Engineering findings corrected during the milestone

- Cloud home is read-only and Unix boot sockets are restricted. Wrapper keeps all caches under the project and allows sbt's supported optional boot-socket failure fallback; application server is disabled. No security setting changed.
- A new orchestration test initially collided with MUnit's own Fixture type alias; fully qualified lab.Fixture fixed it. Full tests rerun.
- Forked sbt CLI initially resolved fixtures relative to app/. Its configured run working directory now resolves to the monorepo root. Full checks and real CLI rerun.
- A scratch extraction parser's definite-length-31 ambiguity was identified during independent review. It is not shipped. The shipped independent extractor retains initial additional-information separately, supports chunked strings and reproduces all selected data exactly.

### Acceptance intentionally not reached

No live Haskell/node oracle execution, Plutus VM, budgets or contexts; no signature/VRF/KES validation; no protocol-specific CBOR acceptance; no transaction/ledger validity or state transitions; no persistence/rollback, chain sync, consensus, network interoperability, or producer operation. Corpus cases are Conway protocol major 10 synthetic golden data, not frozen network/genesis inputs and not proof of roadmap node 11.1.3 compatibility. Scalus runtime remains deferred pending native dependency and target-version conformance decisions.

Next bounded research packet: increase upstream corpus coverage and introduce era/protocol acceptance policy with differential Haskell oracle evidence, then independently gated script/ledger work. No unattended remote publication or ongoing network process was started.

## 0.16.0 — explicit-endpoint TCP direct-range adapter

Implemented reusable owned async JVM TCP transport, strict numeric-only endpoint
parsing, typed optional-byte-pin TCP descriptors and `chain-fetch-tcp`. Extracted
a shared full-batch acquisition core without changing fixture descriptor canonical
bytes or the existing local fetch CLI. Unknown payload bytes need no preexisting
raw-hash manifest; observed original hashes bind committed resume overlap.

Final aggregate: **455/455 Scala tests passed**, including all 414 prior tests.
New evidence includes deterministic callback races/admission, owned-group cleanup,
real local nonreading-peer backpressure, IPv4 and IPv6 loopback, independent literal
wire scripts, whole/partial resume, preflight connector spies, optional pins and
same-header altered-body negative controls. See [verification](tcp-direct-range-verification.md).

This is live-capable code with **local-only acceptance evidence**. No public
endpoint, DNS, HTTP fallback or reference-node startup was executed. NtN14
initiator-only protocol3 is short-lived and strict; KeepAlive/general mux and relay
interoperability remain unimplemented/unproven. Header/body commitments, signatures,
ledger, consensus, network authentication and Mithril validation are not claimed.

## 0.17.0 — public operational-certificate signature predicate

Implemented checked immutable certificate/uint64 inputs and exact raw 48-byte
signable serialization over the unchanged strict public Ed25519 primitive. Four
original archived public certificates verify; all 24 retained native mutations
reject. Independent Python serialization controls cover 25 unsigned64 value pairs.
No signer, private fixture, full crypto provider or native dependency was added.

Final aggregate: **467/467 Scala tests passed**, including all 455 prior tests.
Twelve new tests cover the fixture outcomes, full unsigned range, malformed/null
and implementation/fatal failure separation, immutable ownership, concurrency,
one-primitive-call gating and bounded pinned CLI admission. The projector's 23
negative cases pass. Independent API, evidence and scope review had no blockers.
See [contract and reproduction](operational-certificates.md) and the retained
`opcert-build-verification.log`. All 16 prior CLI/provenance/runtime gates and
the three new opcert gates passed serially (102 direct-JVM CLI cases total,
including seven new opcert cases). Runtime remains 26 native-free jars, with all
five negative dependency-admission checks passing. Full output is retained in
`opcert-regression-verification.log`.

Success means only that the supplied cold key signed the supplied certificate.
Registered pools, current counters, KES, complete header validation and historical
chain authenticity remain unsupported. This milestone made no new external
connection and did not rerun a native oracle; separate fetch experiments do not
broaden this predicate's acceptance evidence.

## 0.18.0 — explicit bounded KeepAlive direct-range profile

Added pure pinned KeepAlive codec-v2/UInt16 agency and an explicit separate
`chain-fetch-tcp-keepalive` command with canonical `tcp-direct-range-v2` identity.
The unchanged handshake/BlockFetch engine runs over one closed bounded protocol3/8
bearer with a single physical reader, fair SDU writer and fixed KeepAlive worker.
Full BatchDone verification, optional pins, exact overlap and store binding are
reused; graceful-required completion and successful cleanup precede any cursor.
Strict v1 remains unchanged, with no auto-upgrade/fallback. See the
[contract and source provenance](keepalive-direct-range.md) and
[verification](keepalive-verification.md).

Acceptance is finite in-memory/virtual-time and controlled localhost only. The
implementation does not establish relay admission/interoperability or network,
body, header-signature, ledger, consensus, Mithril or reference-replay validation.
A separate public fetch request still requires its independently authorized
numeric endpoint; no public attempt is part of this release evidence.

The final frozen aggregate passed **531/531 Scala tests**, preserving 467 previous
cases and adding 64 local controls. The retained build log and verification page
separate this result from serial CLI gates and fresh-archive acceptance. The
strict TCP test harness now preserves client diagnostics on peer failure and
uses bounded bulk fragments; its earlier transient reset remains of unproven
cause. New-profile whole/state/finish and outgoing-budget behavior is explicitly
covered without changing the strict source identity or command behavior.

The 20 prior/new serial CLI, provenance and dependency gates passed under an
explicit per-process 4-CPU acceptance profile, with original guards unchanged:
106 direct-JVM CLI cases, 26 native-free runtime jars and 5 negative dependency
checks. See `keepalive-regression-verification.log`; earlier baseline CLI startup
stalls are retained as unproven-cause observations, not reclassified as success.

## v0.19: supplied-message Sum6KES predicate

Added a bounded pure six-level Sum6KES tree over the existing experimental strict
Ed25519 leaf. Checked immutable root32/signature448/relative-period/message inputs
admit only the primitive contract; success authenticates supplied bytes and does
not serialize or validate a header. At most six pair hashes and one leaf call;
malformed input, rejection, internal errors and fatal propagation stay distinct.

Four public archive positives remain limited to authentic periods 28, 29 and 35.
All 17,332 compact synthetic controls and exact tree/leaf work observations match
the JVM candidate. Historical expectations use a source-derived tree and pinned
native Ed25519 leaf, not an independent full KES oracle. Portable offline source
and archive admission includes retained licenses and parameter context; no native
binary or secret-bearing fixture is bundled. See [contract](sum6-supplied-message.md)
and [executed verification](sum6-verification.md).

The full aggregate passed **543/543 tests**, preserving all 531 previous cases and
adding 12. Formatting, runtime classpath generation and all 23 serial old/new
CLI/projector/dependency gates passed under explicit per-process APC4 sizing.
Existing timeout guards and production behavior remain unchanged; this is not a
claim to cure earlier timing failures. No new network acquisition or public relay
attempt was made. Header serialization, lifetime/counter/registration context and
broader authentic period coverage remain separate future gates.

## v0.19.1: deterministic bounded corpus tests

Fresh v0.19 archive acceptance failed two 30-second MUnit budgets: original VRF
corpus 34.081s and Sum6 corpus 50.888s. The failure is preserved separately from
the earlier passing main-checkout aggregate; the broader timing variation remains
unproven. See [failure and test-only patch](corpus-batch-verification.md).

Partitioned all 17,336 Sum6 observations into 68 tests of at most 256 rows, original
2,048 VRF rows into 16 tests and additional 2,338 VRF rows into 19 tests, each at
most 128 rows. Exact contiguous coverage, output/classification counts and Sum6
per-row commitment/leaf traces are preserved; batch-local sums plus exhaustive
coverage retain exactly 3,752 actual Sum6 leaf calls. No production code, fixture,
expected output, protocol deadline or MUnit timeout changed.

The patched main checkout passed **646/646 tests**, focused 129/129 tests,
formatting/runtime classpath generation and all 23 serial CLI/projector/runtime
gates under explicit APC4 sizing. The increased test count reflects batch and
coverage registrations, not new independent crypto evidence. Fresh v0.19.1 archive
acceptance remains a separate gate.

## v0.20: original-byte body hash and size observations

Prepared a pure six-era commitment inspector sharing the bounded structural parse,
with private immutable raw-digest/header-identity-bound observations. It checks the
header's Word32 size and hash-of-original-component-hashes; structural indexing,
fetch/store identities and admission remain unchanged. The read-only CLI accepts
one bounded local raw file and reports separate outcomes and unchecked claims.

Independent Python projection confirms 36 original hashes and 35 sizes, including
16 new source-provenanced mainnet originals; the Conway toy remains a deliberate
size negative. The portable matrix includes 347 synthetic cases and exact resource
boundaries. No real sample has nonempty invalid indices and no fresh Haskell body
decoder oracle is claimed. Source and regression verification status are recorded
in [body commitment verification](body-commitment-verification.md).

Next is evidence-typed same-block ingestion with bound receipts and then restricted
reversible replay. Successful predicates are not implicitly combined into header,
ledger, consensus or source-authenticity claims.

Main-checkout formatting, compilation and **423/423 focused** plus **1042/1042
aggregate tests** passed. All 25 serial gates now have passing completions across
the original ten-gate prefix and resumed fifteen-gate suffix, including 25 new
CLI cases. The original serial attempt timed out at the existing chain-fetch
path-traversal rejection; its failed record, one instrumented isolated retry and
successful full-gate rerun remain separate, with no root-cause/cure claim and no
deadline change. Runtime remains 26 native-free jars with five negative checks.
Fresh v0.20 archive acceptance remains separate and pending.
