Data from IntersectMBO/plutus tag 1.70.0.0 (Apache-2.0), unmodified except where noted.

- `builtinCostModel{A..E}.json`, `cekMachineCosts{A..E}.json`: `plutus-core/cost-model/data/`, the
  default builtin costing functions and machine step costs of each builtin semantics variant.
- `V{1,2,3}-param-names.txt`: the constructors of `data ParamName` in
  `plutus-ledger-api/src/PlutusLedgerApi/V{1,2,3}/ParamName.hs`, in declaration order (the order of
  the on-chain cost model parameter list), one per line.

Used by `BuiltinCostingSuite`.

The parameter-name lists were regenerated for `PlutusConformanceSuite` from all constructors of
`data ParamName`; the first version missed the first constructor after a comment (`| -- Plomin`).
`PlutusConformanceSuite` also uses `V3-param-names.txt`, `builtinCostModelE.json` and
`cekMachineCostsE.json`.
