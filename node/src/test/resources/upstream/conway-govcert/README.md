# Conway GOVCERT reference traces

These are 21 actual generated judgments from the `GOVCERT` property in
`libs/cardano-ledger-conformance/test/Test/Cardano/Ledger/Conformance/Spec/Conway.hs` at
`cardano-ledger` commit `f649f9751074d2ab3de033fc3912f29c9862c1f5`. That property runs the real
Conway rule and the formal Agda oracle. The test-only observer patch in
[`exporter.patch`](../../../../../../scripts/oracle/ledger-govcert/exporter.patch) writes the
original generated context, environment, state, signal, and implementation state after the normal
conformance computation. It does not replace or change the generator, rule, or oracle. The exact
observer and run pins, seed list, file hashes, and per-case verdicts are in `manifest.json`.

The test decodes the full pre- and post-`ConwayCertState` with Scarab's Haskell-state codec at
protocol version 9. For all 21 generated DRep and committee signals, the consumer decodes the actual certificate and
the complete actual GOVCERT environment using Scarab's Conway codecs. DRep rules consume PParams
and epoch; committee rules consume the current committee and the actual purpose-id / proposal map.
The typed rule adapters share their state transitions with the production GOVCERT rule. Each
resulting full `ConwayCertState` is compared with the exported Haskell implementation state, and
untouched state components are checked separately. The complete raw context and environment remain
in each case. No missing input or committee proposal is replaced with a default.

These 21 seeded judgments are a bounded probe, not a claim of full GOVCERT or full ledger
conformance coverage. Other enabled ledger conformance rule families remain outside this corpus. Upstream property outcomes are retained for
all 21 rows; no upstream failure or missing case is silently dropped.
