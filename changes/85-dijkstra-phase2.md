### Changed (era types, #85)

- A Dijkstra block's scripts now advance the node's phase-2 counters, as the other eras' do: the `phase2` object
  and `scriptIntegrityHashChecked` of `ledgerEpoch`, which count a sub-transaction's scripts and integrity hash
  with its batch's. A Dijkstra block that ran scripts is now timed under the `plutus` step.
- A Dijkstra block withheld for a script the evaluator gives no verdict for (`blockNoVerdict`, kind `evaluation`)
  now names the transaction and the redeemer, and the sub-transaction for a script of one. It is judged again on
  the same schedule as before.

### Fixed (metrics, #85)

- `scarab_validation_errors_total` no longer counts a block the rules have no verdict on (a script the evaluator
  could not judge, or a crypto provider's defect) as refused, in any era. Such a block is withheld, not refused.
