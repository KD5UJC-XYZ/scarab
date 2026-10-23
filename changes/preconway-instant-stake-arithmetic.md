### Fixed

- Match native pre-Conway instant-stake Word64 arithmetic, missing-entry insertion and ordered input and collateral deletion for the credential and pointer maps (#299). Checked CompactCoin bounds on output amounts remain. The 32 captured results of the released `addShelleyInstantStake` and `deleteShelleyInstantStake` are replayed against the updater.
