### Added (verification, #401)

- A reference-refusal corpus: transactions the reference node refuses, each with its complete `ApplyTxErr` (every accumulated predicate failure, in order) and the ledger state, UTxO and genesis it was judged against. `ReferenceRefusalSuite` compares Scarab's refusal byte for byte in `check`; `devnet.py refusal-corpus` records cases in all six Shelley-based eras, including protocol-update failures and three-certificate accumulation. The two Plutus error-text comparisons tracked by #491 remain explicitly incomplete.

This records historical verification scope. The two text gaps and cancellations were removed by
[the 2026-11-04 refusal presentation fix](https://github.com/KD5UJC-XYZ/scarab/pull/782); complete remaining
diagnostic parity is required under #796.
