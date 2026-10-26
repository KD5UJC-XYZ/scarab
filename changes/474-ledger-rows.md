### Changed (ledger, #474)

- LEDGERS enumerates the transactions of a block with the bounded `TxIx` of the reference (`zip [minBound ..]`, `Shelley/Rules/Ledgers.hs:201-211`): positions 0 to 65,535, and a transaction past them is not applied. The pointer DELEGS gives a certificate has the slot cut to its low 32 bits and the certificate index to 16, as the reference's `SlotNo32` (`Shelley/Rules/Delegs.hs:192-194`, `Credential.hs:233`); it only differs for a slot past 2^32. The persisted format is unchanged.
