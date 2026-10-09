# Finite empty-governance native harness

Original Apache-2.0 synthetic harness using pinned Cardano ledger APIs. See the
[comparison and limitations](../../docs/empty-governance-differential.md),
[schema](OUTPUT.md), and [portable provenance](../../app/src/test/resources/empty-governance/native-provenance.json).

GovernanceMain.hs and BoundaryMain.hs preserve executed source bytes. The cabal
file uses the existing ../synthetic-reward-diff/Main.hs and records exact direct
dependencies (GHC 9.6.7). Building requires a matching separately provisioned
native dependency closure; this repository does not bundle native binaries or caches.
The executable accepts one case input path and writes the result to standard output. Default Scala tests
replay the recorded golden without invoking this executable.

Twelve positive normalized cases match; three tags exercise profile rejection
before native NEWEPOCH. No general governance, native STS negative agreement,
live cursor, valid-chain state provenance or runtime admission is established.
