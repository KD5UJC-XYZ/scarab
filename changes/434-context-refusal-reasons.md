### Fixed (Plutus context refusals, #434, #781)

- Render the reference's complete explicit-error script description, including its V1/V2/V3 context,
  script/hash and protocol version. Compare every recorded context refusal as complete `ApplyTxErr`
  bytes and remove the two older refusal-corpus text masks and canceled tests. Native Haskell
  diagnostics and deep-context controls verify layout, constructors and stack safety.
- Share GHC string, character and byte escaping across node, network and ledger diagnostics.
  A malformed context now retains its typed presentation defect and original evaluator cause,
  closes only the submitting connection without rejection bytes, and permits a fresh client.
- Refusals without recorded raw reason bytes remain bounded to their recorded cause until the
  re-recording tracked by #434; complete remaining CEK, term and refusal parity is required by #796.
