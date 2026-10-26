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

- Plutus conformance is measured continuously ~~(987 of 1,009 cases at the time of writing)~~ ([amended 2026-10-25](#amendment-2026-10-25-where-the-current-state-is-recorded)).
- Scalus versions move only with evidence (the conformance suite and the Preview and preprod replays).
- Owning the evaluator remains an open spike (#43).

## Amendment (2026-10-23): the trusted base and the remaining corrections

- **Trusted base.** From Scalus 1.4.0 the cryptographic builtins (Ed25519, secp256k1,
  BLS12-381) run on native C code: libsodium, libsecp256k1 and blst, linked into
  `libscalus_crypto` of scalus-crypto-jni. They are the reference node's native crypto:
  cardano-node 11.1.3's `flake.lock` pins, through iohk-nix, libsodium at the IOG fork
  `dbb48cce5429cb6585c9034f002568964f1ce567` and libsecp256k1 v0.3.2 (`acf5c55a`), the revisions
  scalus-crypto-jni links. Its blst is v0.3.15 with `-D__BLST_PORTABLE__`, the build ADR 0012
  cites for iohk-nix (the same lock's `blst` input names v0.3.14, `8c7db7fe`). They are part of
  the trusted computing base, pinned by hash and checked at start-up, but not rebuilt by this
  project ([ADR 0020](0020-plutus-native-crypto-through-scalus-crypto-jni.md)).
- **Corrections removed with Scalus 1.4.0.** The variant-A Ed25519 costing, the ECDSA
  replacement, the raw-DST `hashToGroup` adapter and the `NewConstitution` context deviation.
- **Corrections that remain in the wrapper** (as of 2026-10-23; not maintained here, see the [amendment of 2026-10-25](#amendment-2026-10-25-where-the-current-state-is-recorded)):
  - `DataSerialiser` (`serialiseData`);
  - `WideIndices`;
  - `ScarabProgramView`;
  - `PlutusScriptBytes`;
  - `FlatScan`.

## Amendment (2026-10-25): where the current state is recorded

This record keeps the decision; it is not the inventory. The conformance count in the consequences
is historical; current results and their evidence are in [plutus-conformance.md](../plutus-conformance.md).
The native cryptographic backend is [ADR 0020](0020-plutus-native-crypto-through-scalus-crypto-jni.md).
The builtin corrections the wrapper applies, with their reproducer suites, are documented on
`ScalusEvaluator.CorrectedVm` in
[ScalusEvaluator.scala](../../plutus/src/main/scala/xyz/kd5ujc/scarab/plutus/ScalusEvaluator.scala);
the list in the amendment above is not maintained as corrections change.
