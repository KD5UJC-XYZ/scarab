> Public export: see [public verification profile](docs/public-profile.md). The default check covers the public corpus only. Historical/private corpus results below are not the public test count; optional private suites require separately supplied data.

# Cardano Scala Lab 0.23.0

A runnable research prototype for **byte-preserving CBOR and Cardano transaction-ID conformance**. It also runs eighteen pinned Plutus evaluator conformance vectors and bounded handshake/mux source-conformance checks over localhost TCP. It also checks one narrowly scoped Conway PV9 value-conservation predicate and an experimental strict public-input Ed25519 witness predicate. It also composes a bounded, reversible in-memory Conway PV9 ADA-transfer UTxO/fee projection. It also includes a bounded durable interpreter for that research projection. It does not fully validate transactions, run a complete Cardano ledger, sync a chain, or produce blocks.

## Ordinary local node milestones

The [bounded durable fork acceptance](docs/private-cluster-fork.md) now demonstrates
graceful process handoff, exact receipt resume, a nonempty rollback to a shared
anchor, and following a peer-selected replacement branch with complete supported
reference-state comparison. It is a local, same-epoch research profile; it does
not establish independent chain selection or full ledger/consensus validation.

The separate [sustained-durable v2 mode](docs/private-node.md#sustained-durable-v2-create-and-resume) uses journal-bound full claims and acknowledged anchor compaction. Its [adaptive isolated acceptance](docs/adaptive-sustained-acceptance.md) now demonstrates twelve same-epoch original blocks with capacity two, strict A-to-B recovery and continuation, exact transaction-body inclusion, and complete supported reference-projection equality. Later reference-chain growth is a separate observation; Scala epoch transitions, full ledger/consensus validation and monetary-conservation claims remain outside this case.

## Atomic restricted branch candidate

The new [coherent branch API](docs/coherent-branch.md) prepares original-byte
acquisition, certificate/KES, supplied-state eligibility and independent UTxO/fee
transition as one atomic in-memory tuple, with revision-fenced rollback. This is
a one-block/supplied-epoch research profile, not full ledger or consensus validity.
The old reference snapshot contains unsupported Byron outputs; its historical
receipt remains unchanged. The explicit [fresh-genesis reference fixture](docs/private-cluster-coherent.md)
now demonstrates one positive complete-state coordinator transition and atomic
rollback/reapply. Separate synthetic unit tests retain their original attribution.

## Stake and epoch foundations

The [atomic stake coordinator](docs/atomic-stake-coordinator.md) adds opt-in
in-memory stake publication and exact rollback alongside the checked ledger tuple.
Both durable checkpoint formats reject stake-bearing state. The separate
[boundary preview](docs/conway-epoch-boundary-preview.md) checks synthetic reward
effects before snapshot rotation and preserves pre-transition leadership inputs.
It has no runtime publication operation. Same-epoch runtime guards remain;
neither foundation establishes native reward parity or runnable epoch transitions.

The pure reward pipeline composes [supplied-projection allocation](docs/conway-reward-start.md),
[pool/leader calculation](docs/conway-pool-reward.md),
[member distribution](docs/conway-member-rewards.md),
[completion equations](docs/conway-reward-completion.md), and
[application-time recipient filtering](docs/conway-reward-application.md).
An [immutable monetary pulser](docs/conway-reward-pulser.md) advances the same
calculation in bounded chunks from supplied frozen inputs. Native input admission,
branch ancestry, native execution parity, events, non-myopic updates and runtime
epoch publication remain unproved or excluded.

The [recorded native differential](docs/synthetic-reward-native-comparison.md)
now shows exact monetary/progression agreement for eleven synthetic cases and
33 steps. A pinned self-generated native golden runs in the public test suite;
no native compiler or captured-chain input is required. This finite result does
not establish general reward parity, valid-chain history or runtime epoch safety.

An [internal synthetic successor-block path](docs/synthetic-successor-block.md)
now composes owned rewards, old-mark leadership, nonce/header checks and the body
transition with atomic publication and whole-tuple undo. Tests use an explicitly
synthetic anchor with retained signed bytes. Public prepare/CLI epoch guards and
both checkpoint refusals remain; native boundary equivalence, valid-chain
admission and durable recovery are not established.

## Opt-in local reference testing

The local Docker harness now exercises a verified Cardano node 11.1.3: real Scala NtN14 handshake, ChainSync/BlockFetch capture, and a restricted ADA-transfer comparison against original reference inclusion bytes, whole-UTxO changes and actual fee-pot exports. A separate relay-only scenario observes hot/full-duplex peers and transaction-ID requests after the configured startup delay before submitting through the relay. It uses disposable private-cluster keys, an internal Docker network and bounded cleanup; no public peers or real funds. See [setup and resource contract](docs/private-cluster.md), [byte capture](docs/reference-capture.md), and [transfer/context/relay scope](docs/private-cluster-transfer.md).

Scala checks v2 context numerics directly against hashed reference JSON, with duplicate-field and numeric-form rejection. State queries are still separately acquired under observed quiescence, not atomic. Header signatures, consensus/leadership, complete minimum-output checks and full ledger validity remain outside this scenario. Historical RestrictedReplay profiles are unchanged. The latest affected regression passed 295 Scala tests and 21 Python guards; this is not a replacement full-public-suite count. Real-reference evidence tests are opt-in and their captured files are not distributed in Git.

Public acceptance remains:

```sh
bash scripts/sbtw check app/runtimeClasspathFile
python3 scripts/check-public-gates.py
python3 -m unittest discover -s scripts -p 'test_private_cluster*.py'
```

The proposed [public CI workflow](.github/workflows/public-profile.yml) runs these commands on pull requests and main pushes with pinned actions/JDK and read-only permissions; see [verification status](docs/public-profile.md). The Python unit tests check launcher guards without starting Docker. Live reference scenarios are separate, opt-in commands in the linked documentation. Use [the public profile](docs/public-profile.md) for the dated full-public baseline and private-corpus exclusions.

Version 0.23 adds a separate Cats Effect-owned, all-JVM local replay store with atomic head publication, checked restart reconstruction, rollback and owner/session revision fencing. Original bytes and the pure profile remain unchanged. The final durable module passes 150 tests together, and its complete direct-JVM CLI gate passes all 19 cases. All 1,294 project tests and 31 scripts have passing coverage across preserved runs with disclosed timing failures; no clean uninterrupted aggregate/script pass is claimed. Fresh-archive acceptance remains pending. [Verification history](docs/restricted-replay-store-verification.md). It requires a trusted dedicated directory and does not claim hardware power-loss guarantees. See the [store contract and CLI](docs/restricted-replay-store.md).

Version 0.22.1 caches UTxO sorting keys once per entry after a fresh-archive boundary test exposed avoidable repeated work. Original limits, failure priority, output/state identities and profile remain unchanged. The default check also precompiles all seven test modules and serializes test tasks/suites under the unchanged APC4/2GiB runner. One scripted-budget test now separates a five-second virtual watchdog from real filesystem checks under the existing MUnit guard. The final normal check passes 1,142 tests and the replay projector/CLI gates pass; fresh-archive/full-serial acceptance remains separate. See [preserved failure, diagnosis and acceptance](docs/restricted-replay-sort-key-fix.md).

Version 0.22 adds pure checked replay/undo and a bounded read-only trace CLI. Two genuine archived accepted-then-rejected sequences resolve inputs from evolving state and compare exact independent final UTxO output bytes. Atomic batches reject without publishing partial state; in-memory revision fencing rejects stale preparations after rollback. Fees are separately source-derived arithmetic. No tick, persistence, full ledger or consensus claim is added. [Contract, evidence and future gates](docs/restricted-replay.md). The formatted aggregate passes 1,138 tests and all 30 serial CLI/provenance/runtime gates passed in one uninterrupted run. Fresh-archive acceptance remains separate.

Version 0.21 adds a bounded read-only same-block evidence pipeline: original body commitments, header-derived OpCert and candidate-message Sum6 checks, with explicitly bound supplied timing context. Missing epoch nonce remains explicitly unchecked; supplied hashed nonce must pass VRF/output verification. This is partial evidence, not issuer authorization, leadership, ledger or consensus validity. [Contract and evidence](docs/block-evidence.md).

Current profiles include strict public-input Ed25519/Draft03, supplied-context Praos/OpCert/Sum6 checks, original-byte body commitments, bounded ChainSync/BlockFetch/KeepAlive and resumable raw acquisition. The source-profile header serializer is an explicit shortest-definite candidate, with no general Haskell decoder/serializer parity. Earlier milestone sections and test counts below are historical evidence with their original narrower claims.

Version 0.20's body commitments preserve structural fetch/store admission and identities. Its fresh archive passed 1,042 tests and all 25 serial checks; the prior 0.19.1 batching/timeout history remains in [verification history](docs/corpus-batch-verification.md).

## Run

Requires JDK 21, bash, curl, sha256sum, and HTTPS access to Maven Central. Dependencies and the launcher are downloaded into the ignored local `.cache` directory; `LAB_CACHE_DIR` can override its location. The launcher is SHA-256 pinned. Tested JDK/build coordinates are in `compatibility.json`.

```sh
./scripts/sbtw check
./scripts/sbtw 'app/run'
./scripts/sbtw 'app/runMain lab.Main vm'
./scripts/sbtw 'app/run network-demo'
./scripts/sbtw 'app/run ledger-demo'
./scripts/sbtw 'app/run witness-demo'
./scripts/sbtw 'app/run coverage-demo'
./scripts/sbtw 'app/run vrf-demo'
./scripts/sbtw 'app/run fee-size-demo'
./scripts/sbtw 'app/run praos-demo'
./scripts/sbtw 'app/run opcert-demo'
./scripts/sbtw 'app/run sum6-demo'
# The following historical evidence examples require their separately supplied corpus files.
./scripts/sbtw 'app/run body-commitment fixtures/body-commitment/blocks/mainnet-conway-0.cbor'
./scripts/sbtw 'app/run block-evidence fixtures/block-evidence/blocks/original-08.cbor fixtures/block-evidence/contexts/original-08.cbor.tsv'
# This genuine accepted-then-rejected trace intentionally returns exit 1
./scripts/sbtw 'app/run restricted-replay fixtures/restricted-replay/value-conservation.trace.tsv'
./scripts/sbtw 'app/run chain-sync-selftest'
./scripts/sbtw 'app/run block-fetch-selftest'
./scripts/sbtw 'app/run chain-sync-session-selftest'
./scripts/sbtw 'app/run --help'
# Optional custom four-column TSV; sbt reports any application failure as build failure
./scripts/sbtw 'app/run fixtures/cardano-golden.tsv'
```

The application returns exit 1 for a hash mismatch and 2 for input errors; the sbt wrapper may collapse either to build failure status 1. The CLI prints six checks against three genuine Haskell golden transactions, each checked both as its original body and its full transaction envelope. Expected transaction IDs are extracted from precomputed upstream TxInfo records. The default Scala command uses those precomputed expectations and does not invoke Haskell. A separate optional pinned native Haskell CLI has now decoded all three untouched full transactions and matched their transaction IDs; see [offline reference evidence and reproduction](docs/offline-reference-checks.md). No reference node or ledger validation was run. See [fixture provenance](docs/fixture-provenance.md).

## Implemented

- `core`: immutable bytes; bounded, strict-UTF-8 CBOR subset retaining original bytes for every node; unsigned 64-bit arguments; definite/indefinite strings and containers
- Explicit `Encoder[A]`, `Serde[A]`, and byte-first `Hasher`; Bouncy Castle Java Blake2b with independently parameterized 224/256-bit digests
- Four-element Conway envelope body extraction; Blake2b-256 over original body bytes
- `app`: Cats Effect 3 `IOApp`, narrow `FixtureSource[F]`/`ReportSink[F]` ports, pure parsing/checking, structured exit status
- `vm`: Scalus 1.3.0 arithmetic/control-flow/Data/BLAKE2b fixture harness, pinned reference-E parameters, typed outcomes, exact result/CPU/memory and budget-boundary checks
- `network`: pure pinned NtN/NtC handshake and mux SDU codecs/state transitions; CE-owned bounded in-memory and localhost TCP transport adapters, literal fixture scripts and cancellation/timeout tests
- `ledger`: pure Conway PV9 transfer/mint-only value-conservation predicate, four archived Haskell corpus cases, strict unsupported-context boundary and exact per-asset arithmetic; [scope and evidence](docs/ledger-conservation.md)
- `core/lab.witness`: public-only strict Ed25519 research candidate, exact-byte body-hash adapter, 2,219 pinned public inputs including eight genuine ledger witnesses; [scope and evidence](docs/witness-verification.md)
- `ledger-runtime`: tagless-final Resource-owned local replay store; immutable checked journal, atomic head, exact-byte recovery, rollback and stale-session fencing; [contract and verification limits](docs/restricted-replay-store.md)
- `ledger/RestrictedReplay`: pure scalar-coin transfer projection, exact-output immutable checkpoints, atomic batches, checked undo and in-lineage revision fencing; two archived traces and a read-only CLI; [scope and evidence](docs/restricted-replay.md)
- `ledger/Coverage`: closed Conway PV9 required payment-key coverage from actual inputs, resolved addresses and raw VKeys; a genuine missing-key rejection whose supplied signature verifies; [scope and evidence](docs/required-key-coverage.md)
- `core/lab.vrf`: experimental public-only draft03 verification, immutable input/output, typed malformed/rejected/internal outcomes; [exact scope and evidence](docs/vrf-verification.md)
- `ledger/FeeSize`: exact memo-byte Conway PV9 closed-transfer fee/size predicates, independent outcomes and checked spending-output closure; [scope and evidence](docs/fee-size-predicates.md)
- `core/lab.vrf/PraosVrfCertificate`: checked unsigned slot and explicit neutral/hash nonce, protocol alpha and claimed-output gate; four archived public certificate positives with supplied source-pinned epoch nonces, historical chain inclusion unestablished; [scope and evidence](docs/praos-certificates.md)
- `network/ChainSync`: pure non-pipelined envelope/state codec, checked UInt64 points/tips, distinct opaque NtN/NtC fixture adapters, bounded synthetic intersection/fork model; [scope and provenance](docs/chain-sync.md). Bounded fixture ConnectionSession is implemented; no live peer evidence
- `core/lab.opcert/OperationalCertificate`: checked immutable public cold-key signature predicate over exact raw 48-byte messages; four archived positives, 24 native-rejected mutations and 25 independent serialization controls; [scope and evidence](docs/operational-certificates.md)
- `core/lab.kes/Sum6Kes`: experimental supplied-message signature predicate, six commitments over the existing strict leaf; the frozen primitive corpus has four archive positives at periods 28/29/35 plus 17,332 synthetic controls; v0.21 separately adds the documented full-block supplied-context observations; [contract, limits and provenance](docs/sum6-supplied-message.md)
- `core/lab.chain/CardanoBlockEvidence`: one owned parse and typed same-block partial receipt, strict local profile, bound supplied timing/optional nonce and read-only CLI; [scope and evidence](docs/block-evidence.md)
- Upstream fixture attribution/checksums and negative/malformed/limit tests

## Boundaries and known gaps

The codec command provides structural codec/hash conformance only. A hash match is **not transaction validity**. The envelope reader checks array length and body map shape only; it deliberately does not validate fields, witness signatures, the validity flag, auxiliary data or ledger rules. No general Cardano protocol-version-specific decoder equivalence is implemented. The block-evidence command has its separately documented narrower local source/candidate admission profile. Duplicate map keys and non-shortest integer encodings are retained and accepted structurally; historical ledger acceptance remains future work. Semantic tags are preserved without validating their ledger meaning. Floats, undefined and unassigned simple values are rejected. Encoding normalizes integer/length widths and indefinite containers but preserves map insertion order and duplicates, so it is not RFC 8949 deterministic map ordering. Hash original bytes, never a re-encoding.

Decoder defaults: 1 MiB input, depth 64, 100,000 items (including string chunks), 1 MiB string bytes. These are research resource bounds, not Cardano consensus limits. Copies of original nested bytes may consume input-size times nesting-depth memory. Caller-supplied limits are trusted configuration; do not lift them indiscriminately for hostile inputs. Fixture index input is limited to 8 MiB.

The VM command accepts only the eighteen vendored fixture names and exact source/result/budget bytes. Even whitespace edits and alternate registered vectors at the wrong filename are rejected. SHA-256 admission happens before parsing, with an exhaustive post-parse capability gate. This avoids parser paths that could touch Scalus's global native BLS backend. It is a fixture harness, not an arbitrary-script evaluator or general native-free parser. Unsupported capabilities never count as ordinary evaluation failures. [VM scope, provenance and limitations](docs/vm-conformance.md).

Complete script context construction, full transaction/header rules, native-script validation, ledger/consensus transitions, rollback of validated state, staking and block production remain unimplemented. The later sections describe bounded acquisition/storage/protocols and supplied-message crypto research; those narrower implementations do not fill these gaps. Runtime uses Java/Scala artifacts with Scalus native crypto artifacts excluded; see dependency inventory. No DiLF4S source was reused or migrated.

See [network conformance and local simulation](docs/network-conformance.md) for exact version sets, resource limits, provenance and remaining reference-runtime gate. No real Cardano node was contacted.

## License and versioning

Original code is Apache-2.0. Fixtures retain upstream Apache-2.0 and ISC licenses/notices. The narrow public VRF point/math source adaptation retains CCL MIT attribution. Weavechain arithmetic retains its full MIT/CC0/BSD notices. Dependencies retain their own licenses; Bouncy Castle uses its own permissive license. `0.23.0` is an experimental research version, with no stable public API promise. Publication is disabled. Nothing is pushed, deployed, or submitted to a Cardano network.

The [architecture roadmap](docs/architecture-roadmap.md) describes future work, not completed features. Progress, evidence and deferred acceptance gates are recorded in [progress](docs/progress.md).

## v0.11 bounded in-memory ChainSync sessions

Run `./scripts/sbtw 'app/run chain-sync-session-selftest'` for checked NtN14/header and NtC16/full-block fixture profiles on finite independent byte peers. The existing pure self-test remains separate. See [session ownership, limits and evidence](docs/chain-sync-sessions.md). This is source conformance and fixture-peer simulation, not live interoperability or header/block/ledger validation.

## Reusable local chain acquisition (v0.12)

The independent `fetcher` module provides tagless-final `BlockSource[F]`,
`SegmentStore[F]`, bounded acquisition, a hash-pinned local-file source, locked
atomic checkpoint storage, verified resume and `chain-fetch run/inspect` commands.
The retained four-block Shelley and Allegra windows are real original-byte inputs.
v0.15 supports post-Byron disk tags 2–7 (Shelley through Conway); network/genesis authentication,
ledger validation, Byron support and remote acquisition are not claimed.
See [the exact local fetcher contract](docs/local-chain-fetcher.md).

## Pure BlockFetch library

Protocol3 codec/state, strict original-byte tag24 adapter and bounded ordered-point
batch completion are implemented. [Contract and evidence](docs/block-fetch.md).
In v0.13 the local fetcher had no direct-node source and connection sessions were
ChainSync-only. The v0.14 fixture integration below adds no live, consensus, header
or ledger-validation claim.

## Fixture-backed direct range acquisition (v0.14)

The reusable end-only `FixtureDirectRangeSource` negotiates strict NtN14 on one owned
fixture connection, requests known inclusive endpoints over BlockFetch3, verifies
a complete bounded post-Byron batch through BatchDone, then composes with the
existing local resumable segment store. It does not run ChainSync discovery or
contact a live endpoint. Shared connection ownership moved into `network-runtime`
without changing the ChainSync API. See [API, limits, failure and provenance contract](docs/direct-range-source.md).

## Post-Byron original-block indexing (v0.15)

Mary/Alonzo TPraos and Babbage/Conway Praos layouts now share the original-byte
index, local importer, fixture range verifier and resumable store. Eleven retained
fixtures include a five-block preprod Babbage chain; the last four are selectable
with `fixtures/chain-fetch/babbage.tsv` after the independently indexed first anchor.
The Alonzo/Babbage/Conway single fixtures have unestablished networks.
No Byron, null-parent, numeric reference-width or protocol-version acceptance,
body commitment or ledger-validation claim is added. See [exact scope and provenance](docs/post-byron-indexing.md).

## Explicit-endpoint TCP direct ranges (0.16)

`chain-fetch-tcp` is a real numeric-address TCP downloader for one known, bounded
post-Byron range. All selectors/provenance are explicit; unknown payload bytes do
not require an original hash manifest. Optional independent byte pins add checks.
The shared full-batch verifier derives header identities, preserves original bytes
and checks exact committed overlap on resume. Read [the profile, descriptor format
and limitations](docs/tcp-direct-range.md) before use.

Release acceptance is **controlled localhost only**. No public relay was contacted.
The strict NtN14/protocol3 profile has no KeepAlive or general mux dispatcher, and
relay interoperability is unproven. Header/body commitments, signatures, ledger,
consensus and chain authentication are not validated. Existing local `chain-fetch`
commands and pinned fixture descriptor identities remain unchanged.

## Explicit KeepAlive TCP profile (0.18)

`chain-fetch-tcp-keepalive run` requires a separate `tcp-direct-range-v2` descriptor
and explicit `ntn14-blockfetch-keepalive-short-v1` profile. It reuses the existing
full-batch verifier/store and BF session engine with one physical reader, bounded
closed protocol3/8 routing, immediate initiator KeepAlive and graceful-required
completion. Strict v1 behavior and identities remain unchanged, with no fallback.
See [the exact contract, source provenance and bounds](docs/keepalive-direct-range.md)
and [verification](docs/keepalive-verification.md). Acceptance is controlled localhost;
no public relay interoperability, network authentication or ledger validation is claimed.

## Original-byte body commitments (v0.20)

`CardanoBodyCommitment.inspect` derives an immutable observation from the same
bounded original-byte parse as structural indexing. It checks the supplied
header's Word32 body size and BLAKE2b-256 hash-of-component-hashes for all six
post-Byron eras. The CLI reads one bounded local raw file and reports size/hash
outcomes separately. No structural fetch/store policy or source identity changes.

The independent retained packet has 36 hash matches and 35 size matches; the
Conway serialization toy is an intentional size negative. Synthetic coordinated
changes demonstrate that matching commitments do not establish ledger validity
or authenticate a header. The next integration gate is same-block, evidence-typed
ingestion with bound receipts, followed by restricted reversible replay.
See [API, limits and exact claims](docs/body-commitments.md) and
[verification status](docs/body-commitment-verification.md).


The [funded private native-script scenario](docs/private-cluster-native.md) now
compares an accepted signature-script spend against an independently derived
complete prestate, original included body/witness bytes and the reference fee pot.
Three separate rejected witness variants have exact-byte diagnostics and valid
controls. Funding is reference-only setup; this remains restricted ADA native
spending, not full ledger/consensus or Plutus validation.


The [same-epoch nonce freeze observation](docs/private-cluster-nonce-freeze.md)
derives candidate/evolving nonce changes from a pinned pre-anchor and complete
original headers, compares independent poststate, and checks rollback/reapply.
It establishes scoped nonce/VRF evidence, not leadership, epoch rotation, derived
registration continuity or full consensus.


The [bounded epoch-rotation observation](docs/private-cluster-nonce-epoch.md)
derives one real epoch tick from pre-anchor nonce state and every original header.
It compares reference exports and restores unknown fields on rollback. Verification
uses explicitly supplied keys; registration continuity, stake evolution, leadership
and full consensus remain outside this profile.


The [atomic coordinator](docs/coherent-branch.md) now includes nonce state in the
same immutable publication/undo tuple as acquisition, certificates, eligibility
and the restricted ledger. Its one-block same-epoch profile derives eligibility's
nonce from the checked transition and retains the supplied stake/context limits.
Bounded multi-block advancement and durable validated-state storage remain separate.

The [bounded coherent sequence API](docs/coherent-sequence.md) extends atomic
composition to empty blocks and up to sixteen supported transactions per block,
with runtime-owned rollback history for at most eight same-epoch successors.
Supplied anchors remain distinct from locally scoped applied tips; full ledger
and consensus validation remain outside this experimental profile.
