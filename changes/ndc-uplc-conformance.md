### Added (tools)

- `UplcConformanceMain` (`tools/runMain xyz.kd5ujc.scarab.plutus.ndc.UplcConformanceMain <scalus|scarab> <corpus-dir> <expectations.json> <report.json>`) writes the `uplc-conformance` report of pragma-org's node-diversity-conformance site: every vector of the scenario's corpus admitted and evaluated by the chosen Plutus evaluator as the ledger evaluates a script, within the scenario's 10,000,000,000 CPU and 14,000,000 memory units. A text-only vector is read by the evaluator's own text parser, written in Flat and admitted like a script.

### Added (uplc)

- The `uplc` module reads the textual syntax of UPLC (`uplc.text.UplcParser`, the grammar of plutus 1.70.0.0's parser) and writes programs in Flat (`uplc.flat.FlatEncoder`, byte for byte the reference's encoding of the conformance corpus). The node itself reads neither text nor writes Flat.
