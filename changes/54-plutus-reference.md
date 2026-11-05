### Changed (tests, #54)

- The Plutus reference data the tests read (the plutus conformance cases, the cost model data and the parameter-name lists) is kept once, in `plutus/src/test/resources/plutus-reference`, for every pinned plutus release: the newest release whole, and only the files that differ for an older one. `scripts/plutus_reference.py check` (in CI) confirms each release against its recorded digest, `verify` compares each with its pinned upstream archive, and `add-release` adds a newer release.
