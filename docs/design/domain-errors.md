# Domain and service errors

**Status: As built for the runtime migration and declared-channel guard; private integration gates are
recorded in [compatibility.md](../compatibility.md). Semantic contracts are being completed under
[#189](https://github.com/KD5UJC-XYZ/scarab/issues/189), following the runtime migration in #188
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
