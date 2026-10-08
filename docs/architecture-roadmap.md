# Cardano node architecture and implementation roadmap

## Current bounded replay milestone

The 0.22 implementation adds only a pure reversible Conway PV9 scalar-coin transfer
UTxO/fee projection and read-only trace observation. It uses exact archived initial
and final outputs, deterministic atomic batches, checked undo and in-memory revision
fencing. Complete ledger/block/consensus validation, genuine successful dependent
batch fixtures, a concurrent effectful owner and durable crash/restart recovery
remain future gates. See [the precise contract](restricted-replay.md).

The earlier roadmap below remains historical planning context.

Prepared for James Aman · 7 October 2026

## 1 Recommended approach

Build a rigorous research prototype in Scala 3 with a pure deterministic ledger/consensus core, Cats Effect runtime ownership and tagless-final effect boundaries. The immediate purpose is learning through a runnable, independently checked validation slice. Full historical validation and eventual SPO production define the longer-term architecture; production hardening is not a prerequisite for experiments.

Start with a scoped compatibility manifest, executable oracle and narrow codec/crypto/Plutus slice. Make decisions reproducible outside a running node, retain independent evidence and label unsupported behavior. Expand toward private-validator operation before considering real producer credentials or live stake.

Prefer all-JVM operation and evaluate Scalus behind a conformance boundary. Target ordinary cloud infrastructure. Prioritize Cardano work; extract DiLF4S code only when easily separated and useful. A whole-library migration or integration is not a prerequisite.

### Decisions already established

- Scala 3, Cats Effect and tagless-final form the stack; ledger, consensus and protocol transitions remain pure and deterministic.
- This is a thorough learning exercise. The first operating target is a private validator; production/SPO capability is a later graduation target.
- Prefer JVM operation without application-level native dependencies, including crypto and storage. Identify implementations, audit gaps and any explicit exceptions; audited Scala candidates are acceptable.
- Expose the current selected NtN/NtC surface while retaining historical ledger and consensus verification from genesis.
- Support standard trusted-source bootstrap approaches with named trust roots and an explicit validation boundary.
- Own the storage format if useful. Haskell byte-level database compatibility is desirable, but does not block the node.
- Use the license giving practical flexibility; Apache-2.0 is the proposed default for new code, with reused obligations retained. James and the team own any later production release.
- Keep reusable Serde/Hasher composition, with library extraction optional. This roadmap includes no implementation skeleton.

### Working baseline

The reference is cardano-node 11.1.3 at revision 938cba990357ae7c4b7f95c8f75dd9d31174bbeb. Its release identifies ouroboros-consensus 4.2.1.0, ouroboros-network 1.2.0.0 and Plutus 1.70.0.0. The exact deployed network, active protocol version, wire-version set and configuration hashes still need to be frozen. [Node release](https://github.com/IntersectMBO/cardano-node/releases/tag/11.1.3) · [Pinned build configuration](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/cabal.project)

Statements about upstream behavior below are source observations. Module boundaries, gates and operating policies are recommendations. Existing capabilities that have not been demonstrated against the selected target are marked as candidates or open checks. No implementation tests, benchmark campaign or security audit are claimed by this roadmap.

<!-- pagebreak -->

## 2 Freeze the compatibility contract

A useful compatibility claim names a release, network, history, protocol surface and trust model. “Cardano compatible” without those qualifiers gives agents no stable acceptance criterion and leaves reviewers unable to distinguish a missing feature from a bug.

Create a version-controlled manifest with the following fields before implementing consensus-sensitive code.

| Area | Required contents |
| --- | --- |
| Reference build | Node commit and release; resolved ledger, consensus, network, Plutus and crypto revisions; package hashes; generated Cabal plan; compiler and native-library versions |
| Chain identity | Network magic; all relevant genesis files and hashes; hard-fork configuration; starting point; independently checked chain checkpoints |
| Ledger semantics | Required historical eras; activated protocol versions; Plutus languages and semantics; protocol parameters and cost-model changes across history |
| Interoperability | Current selected NtN/NtC versions and mini-protocol roles; local query/response coverage; historical ledger/consensus coverage recorded separately; supported CLI integrations |
| Trust and storage | Bootstrap policy and exact roots; checkpoint/snapshot provenance; rollback retention; own snapshot format; optional Haskell database interchange scope |
| Evidence | Fixture and oracle versions; replay ranges; expected observations; known exclusions; release-blocking divergence policy |

The pinned cabal.project fixes Hackage to 2026-08-14T13:38:07Z and CHaP to 2026-09-23T18:57:23Z. Those timestamps and a package-version table are insufficient as a complete lockfile. Preserve the resolved build plan and artifact hashes so the oracle can be rebuilt later. Consensus and network source revisions linked from the release are 82ecba329d7d054340bf707d44fe6e9ac27cec40 and c45735a56c567fa977969173d18943bac6bb3821 respectively. [Pinned configuration](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/cabal.project) · [Release provenance](https://github.com/IntersectMBO/cardano-node/releases/tag/11.1.3)

### Compiled support and network activation

The pinned node contains Dijkstra-related assembly and chooses supported protocol 11.2 normally versus 12.0 when experimental hard forks are enabled. That establishes compiled paths, not activation on a chosen network. Likewise, an experimental wire feature appearing in a package or specification does not require advertising it in the initial compatible release. Freeze the actual target configuration and test both negotiation and refusal of unsupported versions. [Protocol assembly](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/cardano-node/src/Cardano/Node/Protocol/Cardano.hs)

The full-node target is complete historical validation through the network's active era. Keep future-era and experimental support behind a separate compatibility manifest. A later upstream upgrade should produce a reviewed semantic diff, regenerated fixtures and an explicit change in the supported claim.

Use the current NtN/NtC API chosen for the release, including pinned query response encodings. Full historical validation requires historical block/transaction decoding and ledger/consensus rules; it does not require serving every retired wire API. Preserve that distinction in the coverage matrix. Internal storage may differ from Haskell. Treat Haskell database-file interchange as an optional importer/exporter workstream; full CLI parity, wallet/indexer APIs and experimental protocols remain separately scoped.

<!-- pagebreak -->

## 3 Proposed dependency graph

The graph should enforce the pure core in the build, rather than relying on a convention that agents may break. An arrow below means “depends on.” Production runtime dependencies point inward; the conformance harness can depend on all public modules but must never become a runtime dependency.

```text
node-app
  -> node-services -> node-ports -> pure domain contracts
  -> runtime-network / runtime-storage / runtime-crypto

node-services
  -> diffusion-rules / consensus-rules / ledger-rules
  -> mempool-rules / forge-rules / era-history

consensus-rules -> ledger-view contracts + crypto-verification
ledger-rules    -> script-contracts + binary + protocol-types
script-scalus   -> script-contracts + pinned Scalus
network-rules   -> wire-codecs + protocol-types
binary         -> protocol-types
crypto adapters -> crypto contracts + vetted implementations
```

“Pure” describes observable semantics. Prefer deterministic JVM implementations for hashing and verification, but isolate provider selection behind contracts. Pure rules must not see I/O, key mutation or environment-dependent defaults. Keep verification separate from effectful signing and credential management. A native fallback needs a documented exception, deployment support and independent conformance evidence; Scala source alone is not an audit.

### Contracts that prevent dependency cycles

Protocol types own bounded quantities, opaque identifiers, era tags and byte representations. Do not let a generic transaction type erase era-specific meaning. Binary codecs return typed values together with retained bytes where hashes depend on the original representation.

Ledger-view contracts define exactly the information consensus requires at a point in the chain. Ledger rules implement those views. Consensus owns header/protocol state and selection rules without importing storage or application configuration. Era-history owns hard-fork summaries, time conversions and forecasting boundaries; an era is always an explicit input.

Script-contracts define deterministic evaluation inputs and outputs. The Scalus adapter implements that contract. Ledger code owns context construction and the rule deciding whether execution is required, what budget is available and how the result affects state.

Node-ports define domain operations such as acquiring a consistent read view, committing a chain transition or requesting peer bytes. They should not expose a collection of unrelated key-value writes that encourages partially committed state. Runtime adapters interpret those operations, while node-services own sequencing and supervision.

Use one Scala node application with explicit Validator and Producer assembly modes; a transport sidecar remains an alternative deployment boundary assessed in Section 10. Validator mode constructs no signing capability and cannot acquire hot producer credentials. Relay-facing exposure is an independent deployment choice; a non-producing validator can use restricted peers while the public relay gate is still closed.

The reference similarly separates application assembly, consensus, ledger and networking, though the Scala boundaries need not reproduce Haskell packages one for one. [Node assembly](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/cardano-node/src/Cardano/Node/Run.hs) · [Consensus architecture](https://ouroboros-consensus.cardano.intersectmbo.org/pdfs/report.pdf)

<!-- pagebreak -->

## 4 Map Scala modules to the reference

Use this map to assign source-reading and implementation ownership. The Haskell names identify responsibility families, not a promise of API stability. Resolve exact files and dependencies in the pinned oracle checkout before opening work packets.

| Scala responsibility | Haskell reference family | Compatibility obligation |
| --- | --- | --- |
| node-app | Cardano.Node.Run and Cardano.Node.Protocol.Cardano | Configuration, genesis identity, protocol assembly, role selection and startup failures |
| protocol-types and binary | cardano-ledger core/binary; era block types; serialise instances | Era/version-sensitive decoding, byte retention, hashing preimages and wire envelopes |
| crypto contracts and adapters | cardano-crypto-class and cardano-crypto-praos | Exact hash/signature/VRF/KES behavior and malformed-input handling |
| ledger-rules | Cardano.Ledger era rules and STS transitions | Transactions, blocks, epoch processing, rewards, governance and hard-fork translation |
| script-contracts and script-scalus | Plutus ledger API, evaluation and cost-model code | Context Data, language/semantics selection, execution result and exact budget |
| consensus-rules | Ouroboros.Consensus.Protocol and ledger/header validation | Header validation, protocol state, ledger views and chain comparison |
| era-history | Ouroboros.Consensus.HardFork and Cardano composition | Era boundaries, forecasting, slot/time conversion and state translation |
| network-rules and wire-codecs | Ouroboros.Network.Protocol and typed-protocols | Message agency, codec versions, legal transitions and pipelining |
| runtime-network and diffusion | network-mux, cardano-diffusion and consensus Genesis integration | Framing, peer governors, fetch policy, timeouts and bootstrap protections |
| storage and recovery | ChainDB, ImmutableDB, VolatileDB and LedgerDB | Selected-chain consistency, forks, followers, snapshots, rollback and restart |
| mempool and local APIs | Consensus Mempool; LocalTxSubmission, LocalStateQuery, LocalTxMonitor | Revalidation, acquisition consistency, size/cost limits and versioned responses |
| forge-rules and producer runtime | Consensus forging and Cardano leader credentials | Eligibility, certificates, KES evolution, block assembly and signing |

Primary entry points: [Pinned Node.Run](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/cardano-node/src/Cardano/Node/Run.hs), [protocol assembly](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/cardano-node/src/Cardano/Node/Protocol/Cardano.hs), [pinned consensus tree](https://github.com/IntersectMBO/ouroboros-consensus/tree/82ecba329d7d054340bf707d44fe6e9ac27cec40), [pinned network tree](https://github.com/IntersectMBO/ouroboros-network/tree/c45735a56c567fa977969173d18943bac6bb3821), [ledger repository](https://github.com/IntersectMBO/cardano-ledger), [network specification](https://ouroboros-network.cardano.intersectmbo.org/pdfs/network-spec/network-spec.pdf).

The consensus report is useful architectural orientation but includes historical and unfinished sections. Current ChainDB documentation and the pinned changelog must resolve storage details; LedgerDB V1 and its LMDB backend have been removed in the current design. Avoid translating an obsolete storage sketch as if it were the release contract. [ChainDB API](https://ouroboros-consensus.cardano.intersectmbo.org/haddocks/ouroboros-consensus/src/Ouroboros.Consensus.Storage.ChainDB.API.html) · [Pinned consensus changelog](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/CHANGELOG.md)

<!-- pagebreak -->

## 5 Pure transitions and effectful ownership

A ledger transition receives an explicit environment, consistent pre-state and input. It returns a typed rejection or a post-state changeset with deterministic events. Consensus can be a reducer from explicit observations to new protocol state and commands. Neither path reads the wall clock, fetches missing state, generates entropy or chooses a “current” protocol parameter set implicitly.

A large ledger does not need to be copied into memory for every transaction. Begin with a simple immutable reference model, then expose immutable read views or preloaded rule inputs and deterministic changesets. The storage interpreter acquires a view at a named state version; the pure evaluator cannot read a mixture of tips. Performance refinements must preserve the same observational contract.

### Cats Effect and tagless final

Use narrow algebras for transport, chain storage, ledger views, snapshots, clock observations, entropy, telemetry and producer signing. Services request only the capabilities they need. Concrete IO, Resource allocation and supervision belong at application assembly. FS2 is suitable for bounded streams, but a stream topology does not replace a protocol state machine.

Every socket, worker group, database handle and subscription needs one Resource owner. Classify failures as peer-local, retryable storage/network faults or fatal invariant violations. Define whether a service restart reconstructs state, resumes durable work or terminates the node. Never restart an invariant failure into a loop that keeps advertising a stale tip.

### Concurrency rules

One ordered authority commits selected-chain changes. Parallelize independent signature checks, script evaluation or decoding where dependencies allow it, then recombine in protocol order. Tag every asynchronous result with the pre-state, era, parameters and candidate branch it used. Discard stale work after rollback or parameter changes.

A set of independent Ref values is not a substitute for a transaction. The selected tip, ledger version, consensus state, rollback retention and publication of adoption events need one explicit commit boundary. Mask cancellation only around the short part that must finish atomically; expensive computation and network waits should remain interruptible.

Distinguish decoded, header-valid, body-valid, ledger-applied and selected states in types or explicit state machines. An unvalidated candidate cannot enter a type accepted by block serving or forging merely because it came from a trusted transport.

Bound in-flight bytes as well as element counts. Put limits on fetch windows, validation workers, per-peer queues, retained forks and mempool demand. Fairness and overload behavior need deterministic tests. Use monotonic time for operational deadlines and explicit slot/era observations for consensus decisions.

A simulation interpreter should record external events, virtual time, scheduling choices and injected faults. Replaying that trace must reproduce decisions and domain events. This is especially important when porting Haskell code that relies on STM transactions or simulated concurrency to Cats Effect ownership and queue boundaries.

<!-- pagebreak -->

## 6 Serialization and cryptography

Establish byte-level compatibility before broad ledger work. Cardano hashes can commit to original serialized bytes; decoding into a Scala value and re-encoding it is not a generally safe substitute. Retain byte slices for hash-bearing objects and define their lifetime so parsing a small field cannot retain an unbounded input buffer. [Ledger MemoBytes](https://cardano-ledger.cardano.intersectmbo.org/cardano-ledger-core/Cardano-Ledger-MemoBytes.html)

Version decoders by the applicable era and protocol. Differential fixtures should cover definite and indefinite containers, set tags, duplicate keys, ordering, trailing bytes, integer ranges, UTF-8 byte limits and malformed nested structures. A permissive general-purpose CBOR library must not silently broaden reference acceptance. Conversely, rejecting every noncanonical encoding may reject historically valid data. [Decoder API](https://cardano-ledger.cardano.intersectmbo.org/cardano-ledger-binary/Cardano-Ledger-Binary-Decoding.html)

Test identifiers and commitments independently: transaction IDs, block/header/body hashes, script hashes with language prefixes, datum and script-integrity hashes, address hashes and ledger-specific encodings. Maintain fixtures for historical language-view quirks, including V1 language-ID encoding and cost-model list treatment. Compare context ordering explicitly rather than inheriting map iteration order. [Conway CDDL](https://github.com/IntersectMBO/cardano-ledger/blob/master/eras/conway/impl/cddl/data/conway.cddl)

### Public verification and JVM providers

Prefer a runtime with no application-level native crypto dependencies. This is a goal with unresolved audit/integration work, not a demonstrated all-JVM node. Generic Ed25519 is insufficient. The reference uses libsodium-vrf, secp256k1 and libblst; these are useful differential oracles without becoming production runtime dependencies. Keep deterministic verification separate from effectful signing and key mutation. [Reference native configuration](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/nix/haskell.nix#L194-L196)

| Primitive | JVM candidate | Status and acceptance gate |
| --- | --- | --- |
| BLAKE2b and other hashes | Direct Bouncy Castle primitives | Exact algorithm/output size, including 224/256/512; distinguish SHA3 from Keccak; no truncation substitute |
| Ed25519 | Strict public-only composition over pinned Weavechain 0.1.3 arithmetic | Experimental v0.5 candidate; 2,207 fixed-size system-sodium and separately built pinned Cardano-source comparisons, 12 malformed lengths; official release-binary equivalence remains unestablished. Direct BC differs on three adversarial cases |
| Cardano VRF verification | CCL BcVrfVerifier and EcVrfVerifier | Java candidates; audit exact Cardano suite/transcript, malformed points/scalars and TPraos/Praos inputs |
| Sum6KES verification | CCL Sum6KesVerifier | Java candidate; check every period, signature bytes, opcert offsets/counters and exact header preimage |
| VRF and KES secret operations | CCL implementations as references | Exclude unchanged from production; secret timing and KES evolution blockers described below |
| Plutus secp256k1 | BC ECDSA primitives; purpose-built BIP-340 adapter | ECDSA acceptance adapter and Schnorr integration/audit remain; Weavechain NIZK is not BIP-340 |
| Plutus BLS12-381 | Recent BC Java BLS primitives | Candidate source; pin an available release and implement/audit exact encoding, DST, subgroup and Miller-loop semantics |

The Cardano Client Lib crypto-ext inspection is pinned to 45906b783973b4c44ac6883253af26bdd07bc1b4. It is labeled experimental/devnet-oriented; its narrow module path declares Java dependencies, while other CCL modules include native components. The inspected BC BLS source is c314b9cdffa3958a0eff5344f8fdcdb0181ed830; available primitive classes do not establish a drop-in Plutus provider. [CCL scope and algorithms](https://github.com/bloxbean/cardano-client-lib/blob/45906b783973b4c44ac6883253af26bdd07bc1b4/crypto-ext/README.md) · [CCL build](https://github.com/bloxbean/cardano-client-lib/blob/45906b783973b4c44ac6883253af26bdd07bc1b4/crypto-ext/build.gradle) · [BC BLS source](https://github.com/bcgit/bc-java/tree/c314b9cdffa3958a0eff5344f8fdcdb0181ed830/core/src/main/java/org/bouncycastle/crypto/bls)

<!-- pagebreak -->

### Crypto candidates and production blockers

CCL BcVrfProver calls scalarMultiply with private-key and nonce scalars although that method explicitly warns it is variable-time and for public scalars only. Sum6KesSigner signs arbitrary periods from a caller-supplied initial key; the inspected interface provides no evolution/erasure API or production forward-secure key lifecycle. These are concrete source-observed blockers to unchanged production adoption, not demonstrated exploits. Correct signatures alone do not establish side-channel safety or KES forward security. Public verifiers can be assessed independently. [VRF prover](https://github.com/bloxbean/cardano-client-lib/blob/45906b783973b4c44ac6883253af26bdd07bc1b4/crypto-ext/src/main/java/com/bloxbean/cardano/client/crypto/vrf/bc/BcVrfProver.java#L45-L73) · [Timing warning](https://github.com/bloxbean/cardano-client-lib/blob/45906b783973b4c44ac6883253af26bdd07bc1b4/crypto-ext/src/main/java/com/bloxbean/cardano/client/crypto/vrf/bc/Ed25519Point.java#L305-L331) · [KES signer](https://github.com/bloxbean/cardano-client-lib/blob/45906b783973b4c44ac6883253af26bdd07bc1b4/crypto-ext/src/main/java/com/bloxbean/cardano/client/crypto/kes/Sum6KesSigner.java#L14-L67)

The following paragraph records the earlier source-only candidate inspection. The implemented v0.5 strict verifier instead pins the actual 0.1.3 Maven binary/source pair, whose sources differ materially from that git head; see [witness scope and remaining gate](witness-verification.md).

Weavechain curve25519-elisabeth at 51d65293890efc56293839bfe376c4000e47526a is an optional Java Edwards25519/Ristretto arithmetic source, not a complete Cardano crypto provider. Its documentation warns about JVM timing assumptions. No independent audit report was located in the inspected material. Its threshold-signature verifier's scalar handling requires adversarial review; its Schnorr NIZK uses Curve25519 rather than secp256k1 BIP-340. The whole weave-java-api includes native bindings and is unsuitable unchanged for the preferred runtime. Prefer narrow direct providers over importing that API. [Curve arithmetic](https://github.com/weavechain/curve25519-elisabeth/blob/51d65293890efc56293839bfe376c4000e47526a/README.md) · [Threshold verifier](https://github.com/weavechain/threshold-sig/blob/c6e94f64943a6b68feb24a68cdd46bc52cbe647b/src/main/java/com/weavechain/sig/ThresholdSigEd25519.java#L229-L246) · [Schnorr scope](https://github.com/weavechain/schnorr-nizk/blob/9c5003e63ec70256445d8ea550d35144b5279ffe/README.md) · [API dependencies](https://github.com/weavechain/weave-java-api/blob/2daa8b97c371f11599f1c5dd34e1b370033225ef/build.gradle.kts#L61-L68)

BC's public pair/multiPair already perform final exponentiation. Any Plutus MLResult representation must establish equivalent multiplication/finalVerify observations, rejection behavior, budgets and performance; neither a direct mapping nor a source fork is assumed. Audit G1/G2 encodings, subgroup checks, caller DSTs, infinity and scalar rules against the activated builtin set. BIP-340 also remains explicit adapter/audit work. These are opportunities for audited JVM/Scala implementation, not reasons to relax the acceptance contract. [Pairing implementation](https://github.com/bcgit/bc-java/blob/c314b9cdffa3958a0eff5344f8fdcdb0181ed830/core/src/main/java/org/bouncycastle/crypto/bls/BLS12_381Pairing.java)

### Acceptance evidence

Pin each provider commit/artifact, license and status: inspected, tested, differentially conformant, independently audited, and producer-approved are separate claims. The original source-only review performed no crypto tests. Subsequent v0.5 work adds public-input differential tests described in [witness evidence](witness-verification.md), without timing measurements or a security audit. Inspect the resolved runtime classpath, bundled libraries and dynamic loads on supported platforms before claiming native-free deployment.

Require independent goldens, cross-sign/verify where relevant, malformed and mutation cases, fuzzing and bounded resource behavior. Cover canonicality, scalar/point ranges, subgroups, all KES periods, exact VRF domains, hash preimages, Plutus false-versus-error behavior and exact budgets. Scala round trips and positive vectors alone are insufficient.

Secret-bearing Scala/Java implementations need specialist review of arithmetic timing on supported JDKs, entropy, secret copies/GC exposure and durable KES evolution/destruction. Use opaque secret types and a separate signer; validator mode acquires no producer keys. If a native fallback is proposed, approve it per primitive with reproducible builds, ABI/OS coverage and boundary-fuzzing evidence rather than changing the all-JVM claim silently.

<!-- pagebreak -->

## 7 Ledger coverage and era transitions

Full validation from genesis requires all historical semantics on the selected chain, even if the eventual producer only forges in the active era. Implement rule families in dependency order and attach each to an explicit coverage matrix. The official ledger specifications are often era deltas; reading only the latest era misses inherited behavior. [Ledger specifications and implementations](https://github.com/IntersectMBO/cardano-ledger)

| Era or boundary | Work that must be accounted for |
| --- | --- |
| Byron | Legacy addresses, witnesses and transactions; genesis initialization; delegation/update history; epoch-boundary behavior |
| Shelley | Certificates, stake delegation and pools; deposits/refunds; withdrawals; snapshots, rewards, monetary pots and epoch transitions |
| Allegra and Mary | Validity intervals and timelocks; multi-asset values; mint/burn accounting; era-specific minimum-UTxO rules |
| Alonzo | Phase 1/2 validation; collateral; redeemer pointers and datums; script integrity; V1 context and execution budgets |
| Babbage | Reference inputs/scripts; inline datums; collateral return and total collateral; output encodings; V2 context |
| Conway | DRep/committee/governance certificates; voting/proposals; ratification, enactment and expiry; deposits/treasury; V3 context; bootstrap-period rules |
| Every transition | Block checks, ledger tick, parameter activation, epoch state, era translation, consensus ledger views and rollback |

A rule packet should identify its STS environment, state, signal, failure predicates, event output and dependencies. Preserve exact arithmetic domains and rounding. Conservation properties need the era's fees, minting/burning, deposits, withdrawals and rewards; “inputs equal outputs” is too weak and sometimes wrong.

Historical consensus coverage is also required: Byron header/delegation and epoch-boundary-block validation, then the Shelley-family TPraos-to-Praos behavior at the correct transition points. Pin the exact reference rules in the manifest. Implementing only current Praos would leave a gap in the genesis-validation claim. [Pinned Cardano composition](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/cardano-node/src/Cardano/Node/Protocol/Cardano.hs)

### Boundary ordering is protocol behavior

Specify when ticking occurs relative to header validation, block application, snapshot creation and epoch/era changes. Carry the parameters and ledger view applicable to that point, including delayed stake information. A transaction's result may change when a parameter or language-semantic boundary is crossed even when its bytes are unchanged.

Hard-fork translation must include all state later consulted by validation, selection and forging. Test an epoch change and an era change both independently and together. Exercise rollbacks across those boundaries, then replay an alternate branch with different transactions and parameter outcomes. A cache built for the abandoned branch must never survive merely because its slot number matches.

Compare checkpoint state deeply: UTxO, fees/deposits and other pots, delegation/pool state, stake snapshots, reward state, governance state, protocol parameters and relevant consensus state. Counts and total lovelace can remain equal while a consensus-critical key or ordering is wrong.

Use historical replay to expose omissions, then generated valid and invalid cases to cover states history did not exercise. The ledger repository contains CDDL/golden tests, generators and Haskell/Agda conformance work, but some comparisons are disabled. Treat each suite as evidence with a defined coverage boundary. [Contribution and test guidance](https://github.com/IntersectMBO/cardano-ledger/blob/master/CONTRIBUTING.md)

<!-- pagebreak -->

## 8 Scalus reuse boundary

Pin the initial evaluation candidate to Scalus v1.3.0 at 31531c14d4e556fb38c984d702ee60dd82b6453f, dated 28 September 2026. Its CEK-based VM exposes language, protocol version, machine parameters, builtin semantics and platform selection. These are useful integration points, but its emulator documentation explicitly marks the complete ledger rule set absent. Reuse ledger components only after rule-by-rule assessment. [Pinned VM](https://github.com/scalus3/scalus/blob/31531c14d4e556fb38c984d702ee60dd82b6453f/scalus-core/shared/src/main/scala/scalus/uplc/eval/PlutusVM.scala) · [Emulator coverage](https://github.com/scalus3/scalus/blob/31531c14d4e556fb38c984d702ee60dd82b6453f/scalus-site/content/testing/emulator.mdx)

### Concrete conformance obligations

Scalus 1.3.0 pins its upstream Plutus corpus to 1.63.0.0; the node 11.1.3 release identifies Plutus 1.70.0.0. This difference does not establish incompatibility, but prevents treating the existing suite as sufficient evidence for the selected target. Run the target corpus and historical language/protocol/cost-model matrix independently. The current JVM conformance source also excludes three blst large-DST cases; resolve their applicability and behavior before accepting the backend. [Shared corpus](https://github.com/scalus3/scalus/blob/31531c14d4e556fb38c984d702ee60dd82b6453f/scalus-core/shared/src/test/scala/scalus/uplc/eval/PlutusConformanceTest.scala) · [JVM exclusions](https://github.com/scalus3/scalus/blob/31531c14d4e556fb38c984d702ee60dd82b6453f/scalus-core/jvm/src/test/scala/scalus/uplc/eval/PlutusConformanceJvmTest.scala)

The 1.3.0 changelog documents fixes where PV11 emulator/tests accepted scripts that nodes rejected, a 332-parameter cost-model truncation, replacement of an explicit governance-set cost, and unsigned 64-bit constructor-tag behavior that affected hashes and branch selection. These are specific regression fixtures to preserve. Supplied incomplete models no longer fall back to reference costs; supply the complete target model and reject invalid configuration at the boundary. PV12/Dijkstra/V4 support is compiled capability, not evidence of network activation. [Pinned changelog](https://github.com/scalus3/scalus/blob/31531c14d4e556fb38c984d702ee60dd82b6453f/CHANGELOG.md)

### Evaluation contract

Every request carries original script bytes, language, major protocol version, semantics variant, complete cost model, arguments/context and available budget. Return structured success/failure and exact spent CPU/memory. Select the script-evaluation API that applies the required ledger restrictions; raw term evaluation is a different contract. Never let emulator defaults, ambient Future execution or bundled current-network constants determine consensus behavior.

Ledger code constructs context Data and compares it field by field with the reference before testing execution. Cover input/certificate/withdrawal ordering, redeemer pointers, governance votes/proposals, datums and reference scripts. Test isValid=false collateral paths separately from ordinary success. Exercise exact budget exhaustion, integer division/modulo signs, conversions, overflow and malformed crypto inputs.

The stock Scalus JVM backend is not native-free: it declares BLST and secp256k1 JNI, and some BLS representation types import BLST. An all-JVM integration must replace the relevant provider/types and remove native runtime dependencies, or record an explicit exception; avoiding one native call is insufficient. [Pinned dependencies](https://github.com/scalus3/scalus/blob/31531c14d4e556fb38c984d702ee60dd82b6453f/build.sbt#L536-L538) · [JVM provider](https://github.com/scalus3/scalus/blob/31531c14d4e556fb38c984d702ee60dd82b6453f/scalus-core/jvm/src/main/scala/scalus/uplc/builtin/JVMPlatformSpecific.scala)

Keep evaluation deterministic and bound its runtime scheduling outside the VM. Accept reuse only when target-specific result, budget and context comparisons have no unexplained divergence, with every excluded case documented. Scalus is Apache-2.0; inspect transitive/native obligations separately. [Upstream Plutus conformance](https://github.com/IntersectMBO/plutus/blob/master/plutus-conformance/README.md)

<!-- pagebreak -->

## 9 Bootstrap and network operation

Define acquisition, validation depth and trust anchors separately. Full historical validation reconstructs state from genesis while checking historical rules, signatures and scripts. Fast reapplication reconstructs state from blocks already considered valid and may skip checks. Ouroboros Genesis is a synchronization/chain-selection design; neither phrase is its synonym. Ordinary db-analyser replay must not be counted as full validation. [Reference storage requirements](https://ouroboros-consensus.cardano.intersectmbo.org/assets/files/utxo-db-lsm-1f1ffaa7c42ba448665a3dbca4a9f554.pdf) · [Replay tools](https://ouroboros-consensus.cardano.intersectmbo.org/docs/references/consensus_tools/)

### Explicit policies

The default is restricted synchronization from explicitly trusted reference peers, with full validation. Standard authenticated chain imports or named-source state snapshots are acceptable alternatives; select exact keys, endpoints and provenance in the manifest. Permissionless Genesis bootstrap is a separately gated capability.

- Full historical validation from genesis, acquiring blocks through trusted peers or a separately verified Genesis selection implementation.
- Authenticated immutable-chain import followed by an explicit choice of full revalidation or trusted-chain reapplication.
- Trusted ledger-state import from a named source/key at a recorded chain point, followed by ordinary validation of subsequent data.

The pinned mainnet configuration selects PraosMode. Trusted bootstrap peers provide honest-chain sourcing and availability assumptions while behind; they do not remove block-validation rules. A malicious sole source can stall, censor or supply an alternative valid history. A ledger-peer snapshot supplies discovery information, not UTxO/governance/reward state. [Pinned mainnet configuration](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/configuration/cardano/mainnet-config.json) · [Topology guidance](https://developers.cardano.org/docs/operate-a-stake-pool/node-operations/topology/)

Genesis adds density-based disconnection, eagerness/patience limits and sync coordination under availability/density assumptions. It can still use trusted checkpoints. Implement and test those coupled mechanisms before claiming equivalent permissionless bootstrap security. A checkpoint constrains historical block hashes; it does not prove an arbitrary accompanying ledger state. [Genesis design](https://ouroboros-consensus.cardano.intersectmbo.org/docs/references/miscellaneous/genesis_design/) · [Pinned checkpoint loader](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/cardano-node/src/Cardano/Node/Protocol/Checkpoints.hs)

### Mithril and imported state

Mithril immutable-data authentication requires the certificate chain, file digests and snapshot message/signature checks under the configured genesis key and signer assumptions. Current ancillary files, including the ledger-state snapshot and last immutable file, use a separate Ed25519 verification key and are not threshold-certified by the Mithril protocol. Omitting them requires reconstruction, which still needs an explicit full-revalidation policy. [Bootstrap procedure](https://mithril.network/doc/manual/getting-started/bootstrap-cardano-node/) · [Threat model](https://mithril.network/doc/mithril/advanced/threat-model/)

Haskell/Mithril disk files are not automatically Scala state. Define an import/conversion contract covering network/genesis identity, format version, point/hash and complete semantic ledger/consensus state. Compare converted state to oracle-exported projections; a matching header hash alone is insufficient.

Persist the validation boundary and provenance. Later normal validation does not erase trust below an imported snapshot. Optional independent genesis revalidation can promote that boundary only after complete semantic state agrees. Test wrong networks/keys, mutated files/state, interrupted import, corruption, stale formats and rollback crossing the boundary. Fail closed or rebuild from a known valid point; disable forging during import or unresolved state mismatch.

<!-- pagebreak -->

## 10 Network state machines and diffusion

Separate transport from versioned state machines. NtN uses header ChainSync, BlockFetch, transaction submission and KeepAlive; NtC uses block ChainSync, LocalTxSubmission, LocalStateQuery and LocalTxMonitor. Freeze roles, versions, agency, pipelining, mux IDs, limits and query encodings. Historical payloads do not require retired wire versions. [Network specification](https://ouroboros-network.cardano.intersectmbo.org/pdfs/network-spec/network-spec.pdf)

Cats Effect owns sockets, mux/framing, bounded queues, cancellation and deadlines. Fragmentation/coalescing must preserve results. Distinguish header-valid, fetched, ledger-applied and adopted states. Reference chain choice uses ledger views; peer ChainSync rollback changes its candidate, not the selected ledger by command.

### Recommended process boundary

Start with one JVM, separate Resource owners and process-independent ports. A second JVM sidecar is an alternative when measured isolation/reuse outweighs IPC/deployment costs; Go needs an explicit exception. Reference network/consensus separation supports this design inference, but supplies no certified distributed API. [Pinned network layers](https://github.com/IntersectMBO/ouroboros-network/tree/c45735a56c567fa977969173d18943bac6bb3821)

The prior Go/libp2p/streaming-protobuf design offers boundary experience. Cardano peers still require Ouroboros bearer/mux/handshake/mini-protocol bytes; libp2p cannot replace them. Protobuf may envelope original bounded CBOR internally. JVM decoding/hashes remain authoritative; semantic re-encoding cannot preserve every hash-bearing representation. [MemoBytes](https://cardano-ledger.cardano.intersectmbo.org/cardano-ledger-core/Cardano-Ledger-MemoBytes.html)

JVM services own validation, candidate branches, selected-chain commits, consensus-dependent diffusion/fetch policy, mempool and forging authority. Transport may own mini-protocol sessions, negotiation, connection mechanics and command execution. It serves authorized views only, holds no signing keys and needs explicit authority for tentative-header publication. A compromised sidecar can censor/fabricate peer observations; isolation supplies no eclipse protection.

### Required sidecar contract

- Handshake schema/capabilities, manifest/network identity and configuration generation. Fence process incarnations, controllers and peer sessions; reject unknown required operations.
- Preserve ordering, request IDs, original bytes, full chain points and branch/state-generation tokens. Fence stale results; distinguish received, persisted, validated, adopted and announced.
- LocalStateQuery uses JVM-owned immutable view handles across tip changes. Failed reacquire invalidates the old handle. Bound pinned views; mempool monitors use consistent snapshots.
- Enforce end-to-end byte/request/view credits, fairness and reserved control capacity. Rollback, release, cancellation and KeepAlive must progress under saturation.
- Distinguish IPC/queue budgets from protocol deadlines. Use process-local monotonic durations, state-aware cancellation and terminal outcomes; never invent wire cancellation or blindly replay commands.
- Lost authority tears down affected sessions, releases views and suppresses publication. Recover state before a new incarnation reconnects/re-intersects. Authenticate IPC and fence old controllers; remote IPC requires separate security review.

### Diffusion and acceptance

Start with trusted reference peers. Before SPO use, validate required serving/diffusion roles, propagation deadlines, operational NtC queries and hostile-peer limits; public exposure remains optional. One authority owns peer trust/fetch/sync policy. Genesis additionally couples jumping, patience/eagerness, density disconnection and fetch/state transitions. [Genesis integration](https://ouroboros-consensus.cardano.intersectmbo.org/docs/references/miscellaneous/genesis_observability/)

Require reference negotiation/transcripts, illegal agency, oversized/truncated messages, pipelining, forks/rollback, reacquire and historical payloads. Sidecar gates add byte/hash identity, lost IPC, crash-after-commit, duplicate/stale commands, half-open/split-controller connections, saturated queues and GC/storage stalls. Adopt only when conformance and measured resource/latency benefits justify the boundary.

<!-- pagebreak -->

## 11 Durable storage and rollback

Storage owns an invariant: the published selected tip names exactly the committed ledger and consensus state. A block becoming available on disk does not by itself mean that adoption is committed. Make prepared, durable and published states distinguishable during both normal operation and recovery.

Use an append/replay log, WAL or transactional store with a documented recovery protocol. A commit should bind the adopted chain segment, ledger changeset, consensus checkpoint, rollback material and tip/version publication. Publish external adoption events only after the promised durability boundary. Exactly-once effects outside the database require a separate design; do not imply them from one local transaction.

### Chain and state stores

Keep immutable history, volatile forks and ledger-state access conceptually distinct, even if one engine implements them initially. Store exact block bytes where serving or hashing requires them. Indexes can be rebuilt, but corruption detection, partial-write handling and rebuild cost need explicit tests. Use an owned internal schema by default. Haskell byte-level interchange is desirable but optional: specify immutable files, indexes and ledger snapshots independently, with versioned import/export and conformance gates before claiming compatibility. [ChainDB API](https://ouroboros-consensus.cardano.intersectmbo.org/haddocks/ouroboros-consensus/src/Ouroboros.Consensus.Storage.ChainDB.API.html)

Rollback must restore the full state required to validate and choose the replacement chain. That includes protocol nonces and ledger views, era/epoch state, parameter-dependent caches and mempool assumptions. Tie retained undo information or snapshots to the selected chain's rollback policy, and define what happens if a requested point has already been discarded.

Snapshots need chain identity, point/hash, era, protocol context, format/version, integrity metadata and provenance. Validate a snapshot's declared identity before admitting it. Loading a locally generated snapshot, importing a trusted external state and reconstructing state by replay are different operations with different trust evidence.

### Crash and cancellation campaign

Inject process termination, failed writes, short reads, disk-full conditions and cancellation around append, fsync, snapshot publication, index update, chain adoption and rollback. After every restart, establish which commit is authoritative and compare recovered state with a clean oracle replay. Include repeated crashes during recovery itself.

Queries acquire a consistent versioned view and release it explicitly. LocalStateQuery responses must not mix results from before and after a fork change. Followers and iterators need documented behavior when their point rolls back. Mempool admission and block selection use a named state; after adoption or rollback, revalidate retained transactions and invalidate cached script results whose context changed.

Choose the first storage backend for verifiable recovery and measurable replay throughput, preferring a JVM implementation without JNI. RocksDB JNI, native LevelDB or another native engine is an explicit storage exception, separate from any crypto exception; existing Scala wrappers do not make the dependency all-JVM. Benchmark only after the recovery protocol is fixed. Record maximum resident memory, allocation/GC pressure, state write amplification, snapshot latency and restart time under a specified chain and hardware profile. Do not choose a complicated incremental state layout solely to reproduce Haskell's internal implementation.

### Proposed benchmark profiles

Use ordinary cloud VMs and publish exact machine/disk/JDK/GC details. Start with three workloads: private-validator genesis replay and steady-state tracking; rollback/restart/snapshot recovery under disk and peer faults; and later SPO block assembly/propagation while validation is loaded. Compare a smaller and larger general-purpose VM, plus SSD I/O limits representative of the intended deployment. These are experiment profiles, not hardware minimums. Measure wall time, CPU, total RSS/heap/direct/native memory, disk growth/IOPS, validation lag, recovery time and tail latency. Set supported sizing and producer headroom only from repeatable results.

<!-- pagebreak -->

## 12 Producer security and forging

Production-capable producer support requires the same binary to validate, recover and track competing chains without unresolved divergence. Earlier isolated devnet experiments may use disposable keys to learn forging behavior, with gaps recorded. Reference acceptance on a private network is useful evidence; real production additionally requires eligibility, key-lifecycle and deadline assurance.

The pure forging plan derives from an explicit parent point, ledger view, slot, stake/nonce context and mempool snapshot. It checks eligibility and constructs the candidate block with exact ordering, size and execution limits. The effectful producer obtains a signing capability, verifies the plan is still applicable, signs and publishes under a documented race policy. [Reference protocol and credentials assembly](https://github.com/IntersectMBO/cardano-node/blob/938cba990357ae7c4b7f95c8f75dd9d31174bbeb/cardano-node/src/Cardano/Node/Protocol/Cardano.hs)

### Signing and key lifecycle

- Keep cold-key and operational-certificate issuance workflows outside the online node where feasible. Define required hot material and its filesystem/process access precisely.
- Track certificate identity, counters and KES period constraints explicitly. Test expiry, incorrect periods, missing credentials, restart and key rotation.
- KES evolution is a durable secret-state operation. Define ordering so a crash cannot silently restore forbidden old signing material or discard the only usable state. Review backup and restore procedures against that model.
- Prevent accidental concurrent producer instances using the same credential through deployment fencing and an auditable ownership policy. Do not rely solely on a warning in logs.
- Keep keys, script arguments containing private operator data and raw signing requests out of logs and crash reports. Restrict administrative interfaces and debug endpoints.

Any duplicate-signing guard must reproduce the target's allowed behavior and operational policy; it must not invent a new consensus rule. Bind requests to the parent/slot and identify stale plans after a tip change. Decide whether the system abandons, recomputes or may complete a plan in each race, then test that decision against reference behavior.

Use explicit clock-quality and sync-state conditions for enabling production. Monitor slot conversion, forecast limits, KES lifetime, peer health, validation lag and missed deadlines. If the node cannot establish the required context, the safe operational response is to stop forging while continuing validation and diagnostics.

### Producer acceptance evidence

Run isolated mixed-implementation networks with real reference validation. Cover valid Scala-forged blocks, deliberately wrong eligibility/certificates/signatures, exhausted budgets, boundary block sizes, epoch and era changes, rollback, partitions, restart during KES evolution and a tip change during assembly. Verify that rejection happens at the expected boundary and that malformed inputs cannot induce a signer call.

Public testnet operation and specialist protocol/security review precede the production release decision owned by James and the team. Assign named protocol and producer-security reviewers before closing that gate. The roadmap does not authorize key provisioning, live stake-pool changes or mainnet production. Validator mode remains deployable without any producer secret dependency.

<!-- pagebreak -->

## 13 Conformance oracle and evidence

Build the oracle before distributing broad implementation. A node CLI may not expose enough state, so provide a small adapter against pinned Haskell libraries for decoding, rule transitions, script context/evaluation and selected consensus traces. Freeze its request/response schema and keep the adapter minimal enough to review independently.

Every case records reference/build identities, chain configuration, exact input bytes, pre-state or replay recipe, era/protocol context, expected acceptance/rejection, post-state observations and deterministic seed. Generated failures retain the original bytes, both outputs and a minimized reproducer. A fixture change requires semantic review; implementation workers cannot update expected answers simply to make tests pass.

### Compare the right observations

Compare decoding acceptance and retained hashes; transaction/block validity; exact script success/failure and CPU/memory; context Data field order; post-state changes; epoch/era translation; chain-selection and rollback traces; negotiated network behavior; and recovered state after faults. Normalize only documented representation differences. Error wording need not match, but categorize rejections well enough to expose a failure at the wrong stage.

A diagnostic state fingerprint is useful if its serialization is specified. It is a test artifact, not an invented Cardano consensus state root. When fingerprints disagree, produce field-level diffs and trace the first divergent transition rather than debugging only the final tip.

### Reuse upstream test tools carefully

The consensus gen-header tool produces context-bearing valid and intentionally invalid Praos header cases, including KES/VRF mutations. immdb-server can serve a fixed immutable chain; db-analyser and db-synthesizer support replay and controlled chains. Check that generated output is nonempty, since a process can succeed without forging blocks when credentials are absent. [Consensus tools](https://ouroboros-consensus.cardano.intersectmbo.org/docs/references/consensus_tools/)

Network test libraries include protocol execution and codec/transcript material. Export stable fixtures from the pinned build rather than importing unstable internal libraries directly into release infrastructure. Locate the exact pinned equivalents of test inventories discovered on main. [Network test inventory](https://github.com/IntersectMBO/ouroboros-network/blob/main/ouroboros-network/ouroboros-network.cabal) · [Consensus test inventory](https://github.com/IntersectMBO/ouroboros-consensus/blob/main/ouroboros-consensus.cabal)

Include the documented GetGenesisConfig regression: consensus 4.2.1 restores a 15-field response after a 16-field encoding had changed under the same NtC version. Version negotiation alone would not catch that incompatibility. [Pinned changelog](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/CHANGELOG.md)

The evidence ladder is goldens and mutation tests, property tests, differential rule tests, model/state-machine tests, deterministic schedule/fault tests, historical replay, mixed-node networks and live non-producing shadow operation. No finite suite proves equivalence or consensus security. Release claims must include the tested scope, unresolved gaps and independent review status.

<!-- pagebreak -->

## 14 Implementation stages and exit gates

The immediate unattended research milestone is a runnable, narrowly scoped codec/crypto/Plutus validation slice with pinned fixtures, reproducible commands and an explicit coverage/gap report. Use disposable devnet keys only for experimental signing; no real credentials, live stake or production claim. Stages 0–4 guide expansion toward a private validator. Later networking, producer and release gates are graduation criteria, not prerequisites to this learning exercise.

| Stage | Deliverable | Exit evidence |
| --- | --- | --- |
| 0 Research contract | Scoped manifest, source/license provenance, monorepo boundaries and oracle schema | Reproduce independent fixtures; document coverage, candidate providers and runtime exceptions |
| 1 Offline foundations | Bounded types, era envelopes, CBOR retention, hashing and crypto adapters | Bidirectional reference vectors; malformed inputs; exact hashes; resource limits; no unexplained divergence |
| 2 Restricted sync | Handshake/mux, header ChainSync and BlockFetch against reference peers | Identical bytes/IDs from fixed chain; legal transcripts; intersections, rollback, reconnect and fragmentation |
| 3 Complete validation | Historical ledger rules, script boundary, header checks, era history and selection | Agreed historical replay plus generated rejects and boundary cases; deep state checkpoints agree |
| 4 Private validator | Atomic adoption, recovery, snapshots, mempool and current scoped NtC services | Fault-injected restart/rollback convergence; consistent queries; mempool revalidation; cloud-profile measurements |
| 5 SPO networking | Required relay-facing roles, peer policy, bootstrap defenses and operational limits; public relay exposure optional | Mixed-node partitions and hostile peers; bounded memory/queues; propagation and shadow evidence for the deployment |
| 6 Producer | Eligibility, block construction, signer boundary, certificates and KES lifecycle | Reference acceptance of valid forged blocks; expected rejection of invalid cases; crash/boundary tests; specialist review |
| 7 SPO release decision | Public testnet evidence, release artifacts, operator procedures and maintenance policy | Team approval; scoped compatibility claim; reviewed security; benchmark and recovery evidence; rollback plan |

Stage 2 is a downloader milestone until Stage 3 establishes independent validation. Trusted-state import does not substitute for historical rule implementation when genesis replay remains a required capability. A restricted private validator is the first operational deliverable. Public relay exposure is not required before private production; the required relay-facing serving/diffusion roles and operational NtC surface must still pass before SPO use.

### Critical path and parallel work

The critical path is target definition, oracle, binary/crypto semantics, ledger and era coverage, then durable chain selection. DiLF4S migration, generic library publication, a sidecar and Haskell storage interchange are not prerequisites. Producer integration depends on correct ledger views, consensus, mempool and recovery. Networking can develop in parallel against fixtures and a reference peer after byte contracts stabilize. Storage can progress against a model before the complete ledger exists.

Run a shadow validator before enabling production. Compare selected tips and validation decisions while retaining divergent candidates and reason traces. Define the observation corpus by relevant conditions: epoch and parameter boundaries, reconnection, rollback and representative load. An arbitrary number of quiet days cannot compensate for missing conditions.

Estimate effort after Stage 0 and the first vertical slice expose fixture extraction, historical coverage and crypto conformance/audit costs. Track remaining rule families, oracle coverage and unexplained divergences rather than a percentage inferred from file count.

<!-- pagebreak -->

## 15 Agent work packets and independent review

One integration owner controls shared interfaces, the manifest and gate decisions. Assign bounded packets only after the relevant contracts stabilize. The useful unit is a transition family, codec or recovery behavior with independent fixtures; “implement the ledger” is too broad for reliable parallel work.

Each packet must name the pinned reference files and specification; owned modules; allowed dependencies; input/output types; invariants; failure categories; ordering and atomicity requirements; resource bounds; excluded behavior; and exact acceptance commands. Deliver code, tests, fixture provenance, known gaps and a short explanation of semantic choices. No worker may broaden acceptance, alter shared contracts or waive a failing gate without review.

### Initial packet sequence

| Packet | Bounded output | Independent acceptance |
| --- | --- | --- |
| Oracle extraction | Reference wrapper and versioned fixture schema | Reviewer rebuilds and reproduces cases from pristine source |
| Optional component reuse | One easily separated codec/hash/type component only when it saves node work | Pinned provenance/license; independent vectors; no dependency on a broad DiLF4S port |
| Binary and crypto | One era envelope or primitive family at a time | Frozen goldens plus independently generated malformed/mutation cases |
| Script integration | Explicit evaluation contract and Scalus adapter | Exact result/budget/context comparison against pinned Plutus |
| Ledger rule family | State transition and dependent state projections | Separate reference fixtures; state diffs; era-specific accounting laws |
| Chain runtime | Candidate-state ownership and adoption transaction | Deterministic schedules, rollback and crash-injection model |
| Network protocol | One mini-protocol and version/role matrix | Reference-peer transcripts, illegal agency and resource tests |
| Producer boundary | Pure forge plan and narrow signing lifecycle | Separate consensus review, signer misuse tests and mixed-node validation |

Use separate implementer and verifier roles for the highest-risk packets. The verifier should obtain expected behavior from pinned reference sources and independent generators rather than merely reading the implementer's tests. Review the oracle and fixture exporter themselves; agreement with a faulty adapter can hide a shared mistake.

Integrate small changes on a continuously tested baseline. Interface changes first update the contract and fixtures, then downstream packets. Preserve failed seeds and original bytes in the evidence archive. A discovered upstream bug or ambiguous specification becomes an explicit compatibility decision with a regression case, not an undocumented workaround.

Human protocol/security review is especially important for hash preimages and decoder acceptance, arithmetic and script budgets, hard-fork ordering, fork choice/bootstrap assumptions, rollback/crash consistency and producer key lifecycle. Agent parallelism can accelerate implementation and testing; it does not remove those review obligations.

<!-- pagebreak -->

## 16 Optional DiLF4S component reuse

Cardano implementation takes priority. Do not schedule a full DiLF4S migration, require it as a dependency, or make generic library design a node gate. Reuse only a small, easily separated component whose adaptation and verification cost is lower than implementing the needed contract directly in the monorepo.

The inspected revision is c49a56df927e9960c0831245ef3f489da38fdda7, committed 17 December 2024: one Scala 2.13.11 project already using Cats Effect 3.4.2, F-parameterized storage and Resource. Reusing source may need a focused Scala 3 adaptation; it does not imply a CE2-to-CE3 rewrite or a whole-project port. No build or tests were run for this assessment. [Pinned build](https://github.com/scasplte2/dilf4s/blob/c49a56df927e9960c0831245ef3f489da38fdda7/build.sbt)

### Keep the agreed composition

Serde[A] combines Encoder[A] and Decoder[A] with typed errors; JSON and CBOR supply format-specific instances. Hasher consumes bytes with an explicit algorithm and digest size. Value hashing composes an encoder and a hasher, without requiring decoding. CborHasher, JsonHasher or JsonBinaryHasher may be convenience wrappers. Preserve direct raw-byte hashing for Cardano commitments and keep era/version acceptance policy in Cardano modules. These contracts can start inside the node monorepo and become a library only when a real consumer justifies extraction.

### Candidate reuse and exclusions

Review immutable-byte/digest designs, bytes-first hashing adapters and useful laws. CBOR and Cardano VRF/KES/signature implementations are absent from the inspected code. Do not expand into accumulator or general storage work without a concrete Cardano need. Existing JSON sorts keys and drops nulls; Merkle/MPT commitments use JSON and custom prefixes, which cannot substitute for Cardano commitments. Bouncy Castle supplies existing BLAKE2b/SHA3 implementations; Cardano's BLAKE2b-224 requires exact parameterization, not truncation. [Hash definitions](https://cips.cardano.org/cip/CIP-0005) · [Serializer](https://github.com/scasplte2/dilf4s/blob/c49a56df927e9960c0831245ef3f489da38fdda7/src/main/scala/xyz/kd5ujc/binary/JsonSerializer.scala) · [Hasher](https://github.com/scasplte2/dilf4s/blob/c49a56df927e9960c0831245ef3f489da38fdda7/src/main/scala/xyz/kd5ujc/hash/JsonHasher.scala)

Digest retains/exposes mutable arrays: immutable storage, algorithm identity and total constructors are adoption requirements. VersionedStore runs effects after Ref.modify; separate main/undo/meta writes lack a visible atomic recovery protocol, and some decode failures become missing/partial results. A mutex alone does not fix durability. Do not adopt this interpreter as ChainDB unchanged. [Digest](https://github.com/scasplte2/dilf4s/blob/c49a56df927e9960c0831245ef3f489da38fdda7/src/main/scala/xyz/kd5ujc/hash/Digest.scala) · [Versioned store](https://github.com/scasplte2/dilf4s/blob/c49a56df927e9960c0831245ef3f489da38fdda7/src/main/scala/xyz/kd5ujc/storage/versioned_store/package.scala)

### Acceptance for a selected component

Record the precise source and license, isolate the component, preserve any intentionally retained legacy behavior with fixtures, and run independent Cardano vectors through its adapter. Require an independent consumer build only when publishing a reusable artifact. If correctness repair, Scala migration, provenance or dependencies dominate the saving, implement the narrow contract directly and leave extraction for later. Retain MPL-2.0 obligations for reused covered source and review adapted storage-code provenance. [License](https://github.com/scasplte2/dilf4s/blob/c49a56df927e9960c0831245ef3f489da38fdda7/LICENSE)

<!-- pagebreak -->

## 17 Reuse choices and remaining decisions

### Audit existing Scala code first

Scalus-node at 79a056d0db66eae034d0aa0120f4566bce7d298b contains substantial reusable networking, rollback-aware streaming, RocksDB chain storage and Mithril restoration. Source includes LocalStateQuery and LocalTxMonitor drivers despite a stale README statement. Its core uses Future/cps async, with FS2 and ox adapters; it is not already the requested Cats Effect-native full-node runtime. Its build pins an old Scalus 0.17.0 snapshot. Include a modernization spike to Scalus 1.3.0 and an effect-ownership audit before adopting modules. [Pinned repository](https://github.com/scalus3/scalus-node/tree/79a056d0db66eae034d0aa0120f4566bce7d298b) · [Build](https://github.com/scalus3/scalus-node/blob/79a056d0db66eae034d0aa0120f4566bce7d298b/build.sbt)

Inspection found no Praos/VRF/KES/leader-election implementation there, and its tests were not executed for this roadmap. Choose component reuse, adaptation or replacement from demonstrated contracts. Do not assume either a greenfield start or a completed full node.

### Use alternatives as comparators

Amaru offers validating/relay and ledger-conformance work; its September beta's producer target does not establish completed production. Dingo documents mixed-node production but its September SPO guide recommends testnets and marks mainnet unsupported. Yano supplies relevant JVM/Scalus and local-devnet producer integration while remaining prerelease. Their fixtures and harnesses can supplement the pinned Haskell oracle. [Amaru release](https://github.com/pragma-org/amaru/releases/tag/v10.11.20260925) · [Dingo SPO guidance](https://docs.blinklabs.io/guides/dingo/spo-guides/000-spo-guide/) · [Yano](https://github.com/bloxbean/yano)

Dolos explicitly trusts upstream consensus and is a data-node comparator. Acropolis has modular full-node ambitions; TurboCardano emphasizes chain consumption/revalidation; Dugite's cited production interoperability is a local devnet with explicit readiness warnings. This assessment did not establish sustained adopted public-mainnet block production for any alternative. Connecting, replaying, forging locally and producing adopted public-network blocks are distinct evidence levels. [Dolos](https://github.com/txpipe/dolos) · [Acropolis](https://github.com/input-output-hk/acropolis) · [TurboCardano](https://github.com/r2rationality/turbocardano) · [Dugite](https://github.com/michaeljfazio/dugite)

### Remaining engineering work and approvals

Resolve the research slice first, using pinned sources and explicit assumptions. Inventory current interfaces and historical coverage as engineering work. Platform/performance refinement, production audits and funded publication/support are later decisions, not blockers to a private experiment.

1. Freeze the first network, active protocol version, current NtN/NtC version window and required local query/response surface.
2. Choose supported JDK, operating systems and CPU architectures; resolve per-primitive JVM conformance/audit gaps and any permitted native fallback.
3. Name trusted peers, checkpoint/snapshot suppliers and exact certificate/genesis keys; state which imported data is revalidated.
4. Select benchmark profiles and acceptable replay time, memory/storage cost, steady-state lag and producer deadlines from measurements.
5. Retain in-process JVM networking unless a bounded comparison justifies a sidecar; any change must approve IPC ownership, restart semantics and language/native exceptions.
6. Resolve obligations for code actually reused now. Before product graduation, assign reviewers and approve publication/support policy; production authority remains with James and the team.

The immediate sequence is to freeze the manifest, build the oracle and run a narrow codec/crypto/Plutus vertical slice. Assess existing Scala code and Weavechain crypto against those contracts. Reuse a DiLF4S component only when it reduces that work. Use the results to choose implementations, refine estimates and open independently reviewable Cardano packets.

<!-- pagebreak -->

## 18 Licensing versioning and publication

Propose Apache-2.0 for new project-owned code as a flexible default, retaining applicable licenses for reused material. The research prototype needs provenance and reproducible build records now. The coordinated release train, public artifacts and supply-chain policy below apply when graduating to a maintained product; they do not block a private experimental build.

### Licensing and provenance

Keep licenses, attribution and applicable NOTICE content for reused Apache code and mark changes as required. Scalus is Apache-2.0, but its transitive/native dependencies need their own inventory. Copied, adapted or Scala-ported DiLF4S MPL source retains covered-source obligations; a file or package move does not make it Apache-only. Independent new files and MPL components can coexist in a larger work. Distributed covered executables need matching source availability and notices. [Apache terms](https://www.apache.org/licenses/LICENSE-2.0) · [MPL terms and FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/)

Separate retained MPL material visibly, archive exact modified sources/source JARs and give retrieval instructions. Record origin, commit/path, rights/license, notices and changes for code, fixtures, generated assets and bundled dependencies. Do not assume project ownership grants relicensing rights over others' contributions; resolve uncertain provenance before shipping. DiLF4S storage credits an Ergo implementation and needs upstream provenance review. [Source attribution](https://github.com/scasplte2/dilf4s/blob/c49a56df927e9960c0831245ef3f489da38fdda7/src/main/scala/xyz/kd5ujc/storage/versioned_store/VersionedLevelDbStore.scala)

### Version contracts

Use one MAJOR.MINOR.PATCH for the node and first-party published monorepo artifacts; pin intermodule versions together. Declare public library APIs, CLI/config schemas, local services, machine-readable output and promised upgrade paths. Mark experimental/internal surfaces. During 0.x, put intentional breaks in a new minor; after 1.0, use major for incompatible public changes, minor for compatible additions and patch for compatible fixes. Use alpha/beta/rc prereleases, immutable artifacts/tags and migration notes. [SemVer](https://semver.org/spec/v2.0.0.html)

SemVer does not authorize consensus changes. A fix restoring documented acceptance may be a patch, but any change to hashes, validity, script budgets or chain selection still needs protocol/security review, differential evidence and an activation assessment. Do not silently enable new semantics under an experimental flag on the same network.

Give the compatibility manifest its own schema version and immutable digest, embedded in artifacts and logs. Record network/genesis identity, reference revisions, historical/current semantics, current NtN/NtC roles/queries, trust roots, capability status and evidence. Product version numbers are not Cardano protocol numbers. Version database, snapshot and configuration schemas separately; each upgrade pair declares read/write compatibility, migration, disk/downtime costs and a tested recovery path.

### Publication and supply chain

Release from a reviewed immutable commit with pinned Scala/JDK/build tools, resolved dependency hashes and no floating production inputs. Use a controlled Maven namespace for useful libraries and a JVM distribution or digest-pinned container for the node. Include sources/docs, licenses/notices, manifest, artifact checksums, actual-distribution SBOM and build provenance. Verify current registry requirements when implementing publication. [Maven requirements](https://central.sonatype.org/publish/requirements/) · [SPDX](https://spdx.dev/learn/overview/) · [SLSA provenance](https://slsa.dev/spec/v1.1/provenance)

Protect publishing/signing authority from untrusted PR code. Sign artifacts/attestations and publish the expected key or signer identity/issuer plus digest verification steps. Independently rebuild specified unsigned payloads byte-for-byte and record exceptions; a successful rebuild is not a reproducibility claim. Stage publication, verify every coordinate and installation, retry only missing identical artifacts after a partial release, and issue a new version for wrong bytes. Never overwrite released content. [Signature verification](https://docs.sigstore.dev/cosign/verifying/verify/) · [Reproducibility](https://reproducible-builds.org/docs/definition/)

<!-- pagebreak -->

## 19 Maintenance upgrades and release authority

These are future product-graduation criteria. James and the team own production go/no-go; a release maintainer and independent protocol/security reviewers assemble evidence. Passing CI grants no production approval. A private research prototype may proceed with explicit gaps; establish the support policy before offering a maintained public product.

### Future support lifecycle

Proposed for team approval and staffing: during 0.x, support the latest minor/patch without a production/LTS promise. For stable releases, maintain the current minor and propose 90 days of critical/security fixes for its predecessor. Publish EOL dates and migration instructions. Fund and announce longer transitions or LTS separately.

Propose stable-API deprecation for two minor releases and 90 days before major-release removal. Security/network changes may shorten this with explicit warnings. Publish minimum safe versions, activation deadlines and backport limitations; support cannot preserve network compatibility after a hard fork. Maintain SECURITY, SUPPORT, CONTRIBUTING and release-policy documents with owners, version tables and report routes; promise no unstaffed SLA.

### Production candidate acceptance

Require clean builds/API checks, independent codec/crypto goldens and malformed inputs, differential regressions, exact script/context/budgets, historical/boundary replay, current wire transcripts, fault/rollback/upgrade tests and capability-appropriate mixed-node/shadow evidence. Unexplained consensus divergence blocks release. Record tested ranges, workloads and gaps.

Add measured CPU/memory/disk/latency evidence on declared cloud profiles, including hostile inputs and storage/GC stalls. Review vulnerabilities, secrets, licenses and artifact provenance; scanners do not replace security review. Verify downloads, sources/docs, installation and operator runbooks. Producer promotion additionally needs audited secret operations, KES/opcert lifecycle, clock/lag fail-safes, forging/propagation deadlines, reference block acceptance and specialist review. Ordinary CI holds no producer keys.

### Safe upgrade and recovery

Before authorized deployment, verify artifact/manifest identity, network/era support, config diff, space and snapshot integrity. Rehearse on a representative copy; use a validator/canary before approved producer promotion. Migration must not silently enable forging or change trust.

Enforce database reader/writer compatibility and old-binary refusal before mutation; test migration interruption/retry. Where old binaries lack checks, block launch against migrated stores. Downgrade may fail on storage or hard-fork compatibility. Specify tested compatible downgrade, verified restore/replay, or forward-fix/resync. Chain rollback differs from software rollback.

SPO recovery must not restore old KES secret state or activate duplicate producers. Fence the old producer before replacement and keep secret-state monotonicity separate from normal database backups. Stop forging when safe context is uncertain while continuing diagnostics/validation where safe.

### Incident and release closure

Assign an incident owner, preserve evidence and assess affected networks/versions plus safe mitigation. Prepare a minimal reviewed fix, retain all applicable safety gates and obtain the relevant publication/production decision. Publish a new signed immutable version with an advisory naming affected/fixed versions, operator actions, migration hazards and safe recovery options. Backport only to supported lines where correctness is demonstrated; add regression coverage and a post-incident review.

