### Fixed (ledger, #825)

- The slot of no return, after which a protocol-parameter update counts for the epoch after next and the future parameters are settled, is computed exactly. The reference's `getTheSlotOfNoReturn` doubles the stability window as a 64-bit word, which wraps for a window of 2^63 slots or more; Scarab does not reproduce that wrap, an approved deviation ([owner decision](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849)).
