### Added (Plutus conformance in Flat form, #43)
- The upstream Plutus conformance corpus now also runs from its Flat files, as the upstream runner does
  with `--format flat`: the 906 cases that upstream gives a `.flat` program and `.flat.expected` result
  (all but the 103 text-parser cases) pass on the Scalus backend at plutus 1.70.0.0 and 1.71.0.0, and on
  the own evaluator at 1.70.0.0, with exact results and budgets and no exclusions. The reference data
  vendors those files (`plutus_reference.py refresh` lays the tree out again from the pinned archives).
