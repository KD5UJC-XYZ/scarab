### Fixed (strict reference comparisons)

- Require complete same-point ledger-state answers and bracketed CLI ledger-state output to match the reference without historical field exclusions. Unsupported queries now fail the node-to-client comparison gate. Remove oracle numeric rounding tolerances and reward-update null substitutions; differences remain visible instead of being accepted.
