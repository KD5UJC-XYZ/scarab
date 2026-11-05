# Plutus diagnostic contexts

The [native observer](../../../scripts/oracle/plutus/diagnostics/Main.hs) calls the unchanged
`Pretty` instances of Plutus1.70.0.0 and ledger-core1.21.0.0. Each row keeps its complete input
`Data` CBOR and complete output text. [Provenance](provenance.json) binds the frozen package plan,
observer, deterministic input generator, executable, image and input/output hashes. The dependency
base and released-source verification are documented in the
[context matrix](../../../scripts/oracle/plutus/context-matrix/README.md). The oracle uses GHC9.10.3;
this is library API evidence, not identity with the official node executable.

The 2026-11-04 capture covers V1/V2 purposes and two/three-argument layouts; V3 script-info variants;
all legacy certificates and V3 certificate constructors; governance actions, proposals and voters;
inline/hash/absent datums and reference scripts; staking hashes/pointers; finite/infinite interval
bounds; duplicate data-map keys, large/negative integers, byte escaping and long data layouts.
Every row is asserted without masks or expected failures by `PlutusContextTextSuite`. Its deep-data
control separately checks stack safety on a small-stack thread. The original whole refusals remain
in their existing corpora and are compared unconditionally by `ContextRefusalReasonSuite` and
`ReferenceRefusalSuite`.

This finite corpus does not establish every possible layout, ledger admission, arbitrary UPLC term
printing or every CEK error constructor. This change renders explicit UPLC `error` descriptions;
remaining error printers are tracked in [#491](https://github.com/KD5UJC-XYZ/scarab/issues/491).
The raw-reason recordings still missing from the other context corpora are tracked in
[#434](https://github.com/KD5UJC-XYZ/scarab/issues/434).

Reference definitions:

- [Full failure description, Evaluate.hs:405-419](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs#L405-L419).
- [Legacy argument layout, Language.hs:418-442](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs#L418-L442).
- [V1 context instances](https://github.com/IntersectMBO/plutus/blob/507cab8cf6192e5143f12be5b2d3005a2928dc6d/plutus-ledger-api/src/PlutusLedgerApi/V1/Contexts.hs#L74-L169),
  [V2 context instances](https://github.com/IntersectMBO/plutus/blob/507cab8cf6192e5143f12be5b2d3005a2928dc6d/plutus-ledger-api/src/PlutusLedgerApi/V2/Contexts.hs),
  [V3 context instances](https://github.com/IntersectMBO/plutus/blob/507cab8cf6192e5143f12be5b2d3005a2928dc6d/plutus-ledger-api/src/PlutusLedgerApi/V3/Contexts.hs).
- [Data layout, Data.hs:51-60](https://github.com/IntersectMBO/plutus/blob/507cab8cf6192e5143f12be5b2d3005a2928dc6d/plutus-core/plutus-core/src/PlutusCore/Data.hs#L51-L60)
  and [prettyprinter1.7.2 algebra/layout](https://hackage.haskell.org/package/prettyprinter-1.7.2/docs/src/Prettyprinter.Internal.html).

Regenerate/verify with the [oracle recipe](../../../scripts/oracle/plutus/diagnostics/README.md).
Raw build and test artifacts are not published.
