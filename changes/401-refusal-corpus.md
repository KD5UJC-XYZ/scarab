### Added (verification, #401)

- A reference-refusal corpus: transactions the reference node refuses, each with its complete `ApplyTxErr` (every accumulated predicate failure, in order) and the ledger state, UTxO and genesis it was judged against. `ReferenceRefusalSuite` compares Scarab's refusal byte for byte in `check`; `devnet.py refusal-corpus` records the corpus again against a Conway devnet or an era-walking cluster stopped in Babbage (`up-eras --final-era babbage`).
