<!-- SPDX-License-Identifier: Apache-2.0 -->
# Diagnostic presentation inventory

**Status: implemented discovery and drift gate; native mappings are completed by the linked stages.**
The policy is the [ADR 0001 amendment](adr/0001-haskell-node-as-reference.md#amendment-2026-11-04-exact-diagnostic-and-refusal-parity).
The [implementation plan](design/diagnostic-parity.md) owns the sequence under
[#796](https://github.com/KD5UJC-XYZ/scarab/issues/796).

[diagnostic-presentation.json](data/diagnostic-presentation.json) is the single machine-readable
inventory. Its producer catalog covers every main Scala module, including protocol, network,
ledger, consensus, storage, node, crypto, generated-model runtime and generator tooling. A producer
is a candidate for review, not automatically a mirrored error or a conformance pass.

`python3 scripts/check_diagnostic_presentation.py` checks source discovery against the catalog,
closed error-constructor drift, boundary references and native source pins. It runs in preflight
and CI. `--discover` prints the current lexical candidates without accepting or classifying them.
Comments and literals cannot register a producer. A new method, explicit provider-message access,
removed producer or error constructor requires an inventory update and source review.

Each boundary records its source consumers, native counterpart, actual observations and tests,
exposure evidence and completion condition. An incomplete entry remains incomplete even when its
existing tests pass. `verified` requires independent observations and tests; source discovery
cannot promote an entry. A typed Scarab service defect without a native rejection counterpart is
recorded separately instead of being turned into invented rejection text. A candidate is not
excluded merely because its native counterpart has not yet been found.

Native families bind the exact source revision, file, line and full source SHA-256. Every native
constructor has an explicit Scala-capture slot and oracle-case slot. A pin change invalidates that
binding; an empty mapping or case list cannot satisfy a verified family. Source review and the
unchanged native observer establish those identities. The offline guard does not fetch upstream
or pretend that a recorded digest is a new source verification.

Discovery is intentionally a lexical backstop. It does not compute the Scala call graph, prove
that every implicit `Show` is a supported native boundary, prove runtime reachability, or replace
compiler exhaustiveness and native differential tests. Existing native JSON, injection, handshake,
system-error, context and refusal corpora retain their measured scopes. API-derived outputs and
actual signed node submissions remain separate evidence tiers.

The CI and decoder-fuzz workflows also run for a PR whose base is another feature branch. This
allows the dependent diagnostic stages to be reviewed as separate stacked PRs with the same gates;
the workflows' existing publication and release controls retain their own triggers.
