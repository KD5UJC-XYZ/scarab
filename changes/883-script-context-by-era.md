### Fixed (script contexts, #883)

- Plutus script-context translation follows the ledger era, including reference-field restrictions,
  Byron-output handling, validity intervals and duplicate certificate pointers. Chains that start
  an era by epoch below its usual protocol version now collect that era's contexts. Complete
  context bytes at usual versions are preserved; the reference's genuine version checks remain.
