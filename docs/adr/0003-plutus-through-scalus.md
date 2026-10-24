# 0003. Plutus evaluation through Scalus, behind our own interface

- **Status**: Accepted
- **Date**: 2026-10-11
- **Issue**: [#43](https://github.com/KD5UJC-XYZ/scarab/issues/43) (whether to own the CEK machine later)

## Context

Plutus V1 to V3 evaluation needs a CEK machine, the builtins and the cost models, matching the Haskell
evaluator to the unit. Writing one is a large, risky project; Scalus is a maintained Scala
implementation.

## Decision

Evaluate scripts through Scalus behind our own `ScriptEvaluator` algebra. The script context, the
phase-1/phase-2 rules and the cost-model handling are ours. Deviations found against the conformance
suite or real chains are corrected in our wrapper (for example `DataSerialiser`, `Secp256k1.verifyEcdsa`)
and reported upstream with reproductions.

## Consequences

- Plutus conformance is measured continuously: `PlutusConformanceSuite` passes 1,009 of 1,009 pinned cases with
  no exclusions ([plutus-conformance.md](../plutus-conformance.md)). The wrapper is `ScalusEvaluator.CorrectedVm`: it
  routes `hashToGroup` through blst-native (`rawDst`) and supplies its own corrected builtin runtimes (for example
  `SerialiseData` and `verifyEcdsaSecp256k1Signature`).
- Scalus versions move only with evidence (the conformance suite and the Preview and preprod replays).
- Owning the evaluator remains an open spike (#43).
