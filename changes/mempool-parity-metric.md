### Added (mempool metrics)

- `scarab_mempool_removed_total` counts the held transactions removed on request although they still apply, which happens when our own chain refuses a block we forged (see the mempool fix above).
