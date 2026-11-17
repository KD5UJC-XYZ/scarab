### Added (era types, #85)

- Conway's UTXO forecast check runs on the era types: the end of a validity interval with redeemers must translate to a time through the era history extended from the current slot. It passes where the reference passed a recorded end past the horizon, and each branch is checked on crafted histories; the node still judges with the existing rule, so nothing changes in behaviour.
