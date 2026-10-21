### Fixed (mempool, forging)

- When our own chain refuses a block we forged, the forger now removes that block's transactions from the mempool, together with the held transactions that depended on them, as the reference node does (`removeTxsEvenIfValid`). Before, the next leader slot repeated the same refusal.
- Add the `mempoolRemoved` log event for a held transaction removed on request while still valid.
