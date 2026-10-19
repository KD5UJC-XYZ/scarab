# Domain and service errors

**Status: As built for the runtime migration and declared-channel guard; private integration gates are
recorded in [compatibility.md](../compatibility.md). Semantic contracts in
[#189](https://github.com/KD5UJC-XYZ/scarab/issues/189) merged in #190, following the runtime migration in #188
and the closed reorganisation issue [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82).**
This implements the error discipline of [ADR 0002](../adr/0002-tagless-final-and-cats-mtl.md).

## Scope and contracts

A failed operation returns its domain or service's closed error family. Cases distinguish the
reason for failure and retain inputs or nested causes needed to handle it. Strings remain useful
for paths, field names and presentation; they do not select failure policy. Rendering a typed
cause and storing the resulting message in another error loses the contract.

| Layer | Contract examples |
|---|---|
| Codec and crypto | `Cbor.Error`, `Bytes.HexError`, `BinaryError`; typed KES, VRF, verifier-contract and transaction-envelope failures |
| Plutus | Typed data decoding, `ScriptProblem`, `BackendFailure`, `EvalFailure`; script rejection and evaluator unavailability remain distinct |
| Protocol and network | Typed wire, framing, agency, handshake and payload failures; pure `NetworkError` vocabulary in `protocol`, aliased by `network` |
| Ledger and consensus | `LedgerDecodeError` and domain-specific Byron, parameter and bootstrap failures; closed header forecast, unchecked and chain-extension reasons |
| Storage and legacy | Typed binary and metadata decoding, snapshot differences and import causes; `StorageError.Corrupt` retains its cause and `Io` retains the exception |
| Node and adapters | Configuration, CLI, peer, query, validation, mempool and forging errors retain the originating cases; fixtures and fuzz adapters use the same contracts |

`Encoder[A]` has an associated `type Error <: DomainError`; `encode` returns
`Either[Error, Bytes]`. `Serde[A]` uses that same family for decoding. `Encoder.Aux[A, E]` and
`Serde.Aux[A, E]` name the refinement when needed. A generic consumer can preserve it:
`Hasher.encoded(value, encoder)` returns `Either[encoder.Error, Bytes]`, rather than widening or
rendering the failure. Protocol payload adapters return `ProtocolError` and retain specific
codec causes in its cases.

`DomainError` is an **open intent marker** for generic bridges between modules. For example,
`StorageError.Corrupt[E <: DomainError]` and generated-model `DecodeError.Kind.Domain[E <: DomainError]`
retain another service's cause without importing that service and creating a dependency cycle.
The marker excludes arbitrary products from those bridges; it does not prove that a family is
closed or semantically useful. Service APIs still expose their own closed families and narrow
`Either` types, not a universal `Either[DomainError, A]`.

Pure rules compose `Either`; effectful interpreters raise their declared errors through
cats-mtl and handle them in the owning scope. External I/O and backend defects retain their
original `Throwable` where applicable. A backend defect must not become an invalid script or
peer merely because both can be rendered as text. Fiber ownership and recovery policy remain
[separate G7 work](../adr/0017-fibers-end-with-values.md) under
[#180](https://github.com/KD5UJC-XYZ/scarab/issues/180).

## Preserved behavior and deliberate seams

This migration changes error contracts, not accepted ledger values, arithmetic, original CBOR
bytes, signing inputs, wire layouts or snapshot versions. Current and frozen binary readers
report semantic primitive failures without changing their encodings. Corrupt input must remain
bounded by its content length before buffering, so decoding cannot consume a following checksum. Collection counts must fit `Int`; readers build
only successfully decoded elements and stop at the first failure without allocating placeholders
for the declared length.

Functional, stack-safe composition remains the default. Local mutable cursors, byte buffers and
explicit heap stacks remain in the measured critical CBOR and framing paths; the existing
[receive-path measurements](../performance.md#what-was-found-the-receive-path) and
[indexed-decoding evidence](memory-lean-decoding.md#2-what-was-measured) motivate those exceptions.
Lexically scoped `scala.util.boundary` exits return typed failures from these loops; they are not
domain exceptions. This work supplies no new performance measurements or general exemption for
imperative code.

One foreign callback seam remains explicit: Scalus `BuiltinRuntime.f` returns a `CekValue` and
its CEK implementation turns thrown callback signals into a builtin error. It offers no
failure-value return. `ScalusEvaluator` records the typed `EvalFailure` in an evaluation-local
log, signals with the upstream `BuiltinException`, and recovers the recorded value before leaving
the worker. This is a foreign ABI limitation, not a custom domain exception or a performance
exception. A value-capable upstream integration is follow-up work.

## Review and remaining work

Review failure cases and payloads, including nested causes, rather than matching rendered text.
Check that narrow error types survive generic adapters, that unavailable verification remains
distinct from a rejected signature, and that backend defects remain distinct from script
rejection. Existing golden bytes, snapshot fingerprints, malformed-input tests and small-stack
checks protect the preserved behavior; integration results belong in the PR and
[compatibility evidence](../compatibility.md).

CI runs `scripts/check_error_channels.py` and its Python tests. This lexical guard checks declared
String-valued Scala `Either` channels, including ordinary aliases and alternate spellings. It
ignores comments and literals and treats opaque aliases as domain types. It does not prove error
semantics or fully resolve inferred `Left` types, type lambdas, implicit conversions,
path-dependent or match types. Compiler/AST checking and review of generic message wrappers
remain necessary follow-ups.

The semantic follow-up [#189](https://github.com/KD5UJC-XYZ/scarab/issues/189) completes the five
bounded contracts identified after #188:

- `PlutusFailure` retains the `EvalFailure` and reconstruction bytes inside
  `UtxosFailure.ValidationTagMismatch`; only the rejection encoder renders the cause. Byron
  proof components, lovelace contexts and attribute owners are closed enums, with the previous
  diagnostic labels retained at presentation.
- `ForgeSkip` retains the detailed reason, including an old tip, and `ForgeSkip.Kind` keys the
  counters. Metrics map those cases directly; the legacy `Other` series remains at zero for
  compatibility. Logs and JSON render the existing field names and keys.
- `ConnectionEnd` distinguishes normal release, transport closure, protocol failure, admission
  refusal and defects. The manager, inbound server, session state and events retain typed
  values and original causes. `InboundEnd` also retains local-client outcomes until logging.
  Recovery and fiber ownership policies are unchanged; #180 still owns their broader review.
- The model generator returns `Either[GenError, A]` from configuration, resolution, emission and
  sampling. `ConfigProblem` and `UnsupportedFeature` distinguish cases and retain source data;
  I/O errors retain `IOException`, and unexpected defects retain their original `Throwable`.
  `EitherT[Eval, ...]` trampolines recursive generation and immutable `StateT` carries emitter
  state. Unsupported syntax diagnostics use a bounded structural renderer, so presenting an error
  does not recurse through its full AST. After the sampler's depth cutoff, repeated references
  return `SamplingCycle` rather than looping. Optional fuzz seeds explicitly omit unsupported,
  unresolved, missing-module or cyclic samples; configuration, I/O and defects remain failures.
  There is no `GenException` domain exception. The parser grammar stays unchanged. All 21 files
  emitted from the pinned configuration match the previous generator byte for byte, and 384
  fixed-seed recursive/compound samples retain their baseline fingerprint.

Compiler/AST enforcement and the Scalus callback seam remain explicit M11-E follow-ups; absence
of declared `Either[String, ...]` alone does not establish semantic completeness. The original
reorganisation issue #82 is closed. Era-indexed consensus
[#85](https://github.com/KD5UJC-XYZ/scarab/issues/85) and generated-model node adoption
[#87](https://github.com/KD5UJC-XYZ/scarab/issues/87) remain separate work.

## Snapshot and listener scopes (G7-F2/G7-F4)

Worker scope allocation and program construction are deferred, including in the shared
`Fibers.scoped` helper. The pinned cats-mtl implementation invokes the supplied body before
installing its handler ([v1.7.0, Handle.scala:238–243](https://github.com/typelevel/cats-mtl/blob/v1.7.0/core/src/main/scala/cats/mtl/Handle.scala#L238));
without deferral, a synchronous constructor defect escapes before the adapter can classify it.
Strict-construction regressions cover the public TCP listener, snapshot adapter and shared worker.

`ChainStorage` runs each periodic snapshot and shutdown snapshot in a fresh `Handle[StorageError]`
scope. The snapshot call path takes the caller's `Raise`: immutable-store sync, snapshot write/list,
backing advance/settle/checkpoint/retain, and the UTxO write/stamp/checkpoint calls they use. These
operations no longer raise through the scope that opened the service. The broader rule/store
capability migration is described under G7-F1 below; the snapshot slice alone does not complete it.

`SnapshotFailure.Storage` retains the original `StorageError`, while `SnapshotFailure.Defect`
retains an unexpected exception. `StorageEvent.SnapshotFailed` carries that value; only the log
renderer produces text. Format refusals stay `SnapshotRefused`. Existing report-and-replay policy
is preserved: a failed snapshot does not alter the stored ledger, and the worker frees its busy
slot so another request can run. Cancellation remains cancellation. Shutdown no longer discards
an outer failure with `attempt.void`. Formats, checksums and migration versions are unchanged.

The reference queue also releases its request after completion or failure via `bracket`
([consensus `82ecba329`, Snapshots.hs:887–905](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/LedgerDB/Snapshots.hs#L887)).
This comparison establishes queue cleanup; it does not claim identical snapshot-failure policy.

`BearerServer.listen` and `listenUnix` require a failure reporter and a handler with a caller-local
`Handle[F, E]`. `HandlerFailure.Domain[E]` preserves an intentional service error;
`HandlerFailure.Defect` preserves an unexpected exception. The listener reports each failed handler
once, releases its connection and keeps accepting. Cancellation releases the handler without
calling the reporter. The node's reporter logs typed network endings and sends both raw handler
defects and `NetworkError.Defect` to the existing `Fatal.Defect` sink (exit 7), without peer blame.
The Unix handler no longer catches an unexpected exception merely to log and continue.

The reference traces non-cancellation server exceptions before rethrowing
([network `2fb69829`, Server.hs:169–175](https://github.com/IntersectMBO/ouroboros-network/blob/2fb69829a19b5c04e4fb212bdd78fb63f9d2d776/ouroboros-network/framework/lib/Ouroboros/Network/Server.hs#L169)).
Scarab expresses the node policy through typed values and its fatal reporter rather than thrown
domain errors. Listener reporters are effect boundaries: their own failures propagate rather than
being swallowed. Existing event fields, metrics and healthy-session wire behavior are preserved.

[#180](https://github.com/KD5UJC-XYZ/scarab/issues/180) tracks validation and independent review of the G7-F1/F3/F5 implementation below.

## Caller capabilities, forging and cached-block attribution (G7-F1/F3/F5)

Rule, store, chain-persistence and startup-migration operations take their caller's error capability.
Constructing a service does not retain the capability used to open it. Resource acquisition and
finalization still use the scope that owns the resource; one-shot offline filesystem helpers remain
within their tool's run scope. `SessionEnd.scoped` gives each follower session a fresh `Handle[NodeError]`
and keeps the whole-run handle as a lifetime backstop. Local handling of `NoIntersection` uses that
same wider error channel, so changing the capability does not change same-connection retry policy.

HTTP state projection, local state queries, local submission and relay callbacks own their storage
scopes. `StorageBoundary` preserves a `StorageError` as `Fatal.Storage` and an unexpected exception as
`Fatal.Defect`. After reporting, the callback waits cancelably for shutdown; it supplies neither a
ledger refusal nor peer blame. A streamed store read runs in a scoped producer with a one-element
queue of typed results. Its scope and iterator remain alive through consumption, and cancellation
releases both. Injecting this boundary into the state-query interpreter preserves its `Sync` API and
its small-stack `SyncIO` regression. Mempool `add` and `sync` instead use the caller's storage capability;
the escaping network adapters supply scopes of their own.

Forging `run`, `step` and `reload` finish with `Either[Fatal, Unit]`. Expected leadership, protocol,
credential and self-validation refusals remain recoverable domain outcomes. Storage failures,
implementation unavailability, internal failures and unexpected defects terminate through the node's
existing fatal policy. Candidate transaction selection propagates evaluator unavailability rather
than silently omitting transactions. Reload treats a missing replacement file (`NoSuchFileException`)
and malformed replacement credentials as operator refusals; other I/O failures are terminal.
KES persistence emits its existing typed failure event and metric, then stops. A terminal key state
prevents another run/reload fiber from signing while shutdown is being reported; repair requires a
restart. The pure on-disk KES replacement/recovery protocol is unchanged.

The reference links forging workers to their owner and recovers its explicit `CannotForge`,
`ForgeStateUpdateError` and `NotLeader` cases
([consensus `82ecba329`, NodeKernel.hs:549–568,660–669](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/NodeKernel.hs#L549)).
Its storage rethrow policy terminates the node
([RethrowPolicy.hs:54–84](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/Node/RethrowPolicy.hs#L54)).
Scarab's persisted evolving KES file is an additional mechanism; applying terminal storage policy to
its failures is a deliberate durability requirement, not a claim that the reference persists keys
with this protocol.

The shared block cache retains a typed source reporter after the producing session releases its
claims. Attribution is bounded to 8,192 released origins plus live session owners. Released origins
are available for ten minutes; access checks the deadline, and a resource-owned worker reaps idle
records at intervals of at most one minute. Thus physical idle cleanup can lag expiry by one minute.
Expiry or capacity eviction also drops untaken blocks from that origin. Already taken blocks retain
attribution only within the same bounds. Releasing the pool clears reports and wakes pending claims.
The maintenance resource surrounds the entire peer-network lifetime.

A refusal atomically claims the origin's notification once. The source peer receives a typed
`ValidationError`; only the governor observation renderer produces its reason text. A late cached-block
rejection can forget a cold peer and close a newer connection or a connection still being established.
Local roots retain their existing maximum-backoff policy. An innocent consumer still ends with the
existing transient foreign-block divergence. No session-scoped `Raise` is retained by the reporter.
The reference likewise associates invalid-block punishment with the sender
([InvalidBlockPunishment.hs:48–56](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/ChainDB/API/Types/InvalidBlockPunishment.hs#L48));
Scarab uses bounded peer attribution because its shared cache can deliver a block to a different
validating session. The retention bounds are Scarab policy, not reference deployment parameters.
