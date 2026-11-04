### Added (Plutus, Dijkstra)
- **PlutusV4 and `case` on `Data`, as the Leios testnet build defines them, reachable from protocol 12 only.** The build links
  plutus 1.71.0.0 (`261766ad`). From protocol 12 a program of version 1.2.0 may `case` on a `Data` value
  (`defaultCaserBuiltinFor`), program version 1.2.0 is available in every language, and the machine uses the new builtin
  semantics variants F (PlutusV1, PlutusV2) and G (PlutusV3, PlutusV4), which drop the integer unlifting bounds and keep
  those on byte strings. PlutusV4 (language 3) exists from protocol 12 and is admitted, costed and evaluated as cardano-ledger
  9df9f98c does: through plutus's PlutusV3 functions and cost model parameter names. The complete plutus 1.71.0.0 evaluation
  corpus passes on the protocol 12 PlutusV4 machine. Below protocol 12 nothing changes, and the node does not reach protocol 12:
  the envelope check refuses every block once the ledger's major version passes `HardForkConfig.MaxMajorProtVer`, 11.
- **The PlutusV4 script context of a Dijkstra transaction** (`DijkstraScriptContexts`), as cardano-ledger 9df9f98c builds it
  with plutus-ledger-api 1.71.0.0: the transaction information with sub-transactions, guards, direct deposits and balance
  intervals, the purposes a body needs scripts for, and a guard's view of the whole transaction. The contexts of three
  transactions and five refusals equal those the Leios testnet's build collects (the oracle's new "context" kind). The
  ledger rules of the Dijkstra era do not use them yet.
