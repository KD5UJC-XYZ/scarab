### Changed (correspondence map, #1263)

- `scripts/check_correspondence.py` refuses an evidence suite of a `structure: differs` entry that has no
  comparison contract in `docs/data/test-coverage-register.json`: a covered or bounded native-parity,
  reference-non-result or known-divergence entry that names the suite's file and the corpus, oracle or running
  node it compares with. The register gains 24 such contracts. Entries whose suites compared nothing with the
  reference drop them from their evidence, and the 41 Mirror entries left with none are Gaps, listed by area on
  #1263.
