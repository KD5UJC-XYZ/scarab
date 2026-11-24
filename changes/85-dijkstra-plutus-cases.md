### Added (era types, #85)

- Under `--reference-build leios-rc-2026w47`, `--plutus-cases <dir>` also records a Dijkstra block's
  transactions whose scripts run: one case per transaction, marked `"era": "dijkstra"`, with its bytes and each
  script's evaluation request (script, arguments, cost model, protocol version and budget) and result,
  sub-transactions' scripts named by the sub-transaction's id; a transaction whose scripts cannot be collected is
  recorded with the failures. The cases are the same whatever `--plutus-workers` is. The cases of earlier eras are
  written exactly as before, and no verdict, state or counter changes.
