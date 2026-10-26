### Added (verification, #401)

- A reference-refusal corpus: transactions the reference node refuses, each with its complete `ApplyTxErr` (every accumulated predicate failure, in order) and the ledger state, UTxO and genesis it was judged against. `ReferenceRefusalSuite` compares Scarab's refusal byte for byte in `check`; `devnet.py refusal-corpus` records cases in all six Shelley-based eras, including protocol-update failures and three-certificate accumulation. The two Plutus error-text comparisons tracked by #491 remain explicitly incomplete.
