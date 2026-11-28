### Added (era types, #85)

- Babbage has its UTXO on the era types: its predicates, then its UTXOS as a sub-rule, then its change of the UTxO
  state, with Babbage's failure family and its injections, Shelley's chosen by reference build.

  On the recorded Babbage submissions, the refusal corpora's cases with their states, the forecast-horizon cases,
  the pre-Conway UTXO corpus's bodies and crafted cases, under each build and validation policy, it gives the flat
  UTXO's verdicts, failures in order and states, and the reference's refusals byte for byte where UTXO alone refused.
  The node does not run it yet, so neither behaviour nor the persisted format changes.

### Fixed (era types, #85)

- Babbage's script evaluation, which Conway's and Dijkstra's UTXOS share, is skipped by a judgment of `ValidateNone`
  as the reference's is. The node's one such judgment already skipped it, so its behaviour does not change.
