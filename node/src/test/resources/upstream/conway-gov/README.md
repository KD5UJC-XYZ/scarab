# Pinned Conway GOV conformance sample

This corpus contains one generated row from cardano-ledger's `Conformance/Conway/Constrained Generators/Blocks transition graph/GOV/` property at the source and formal pins in `manifest.json`. The exporter observes the original generated judgment after the upstream implementation and Agda checks; it does not substitute a local hand-authored vector or alter the generator.

The sample has an empty GOV signal and an unchanged proposal state. The Scala consumer retains and hash-checks every exported trace component, decodes the exact six-field Haskell `GovEnv`, the separate full `EnactState` execution context, and the full proposal pre/poststate with Scarab codecs, then applies the typed `Gov` STS using the environment committee. The two committee values differ in this generated trace and are not substituted for one another. It proves one generated no-op case, not the complete GOV property family.
