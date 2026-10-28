### Fixed (Plutus budget arithmetic, #485)

- Script budgets are computed and spent as the reference does under any cost model the ledger
  accepts: costs and the remaining budget saturate at `Int64`'s bounds instead of wrapping, each
  builtin's cost is the reference's stream of costs over its measured arguments, machine steps are
  charged in batches of 200, and an argument the reference cannot unlift costs nothing. With every
  cost parameter at `Int64`'s minimum, or with parameters of mixed signs, scripts the reference
  evaluates were reported as out of budget, and the budgets differed; 5,661 reference observations
  now agree with none excluded. The published cost models are unaffected.
