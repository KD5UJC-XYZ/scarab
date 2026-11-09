### Fixed (ledger, #825)

- The slot of no return, after which a protocol-parameter update counts for the epoch after next and the future parameters are settled, doubles the stability window as a 64-bit word, as the reference's `getTheSlotOfNoReturn` does. Only a Shelley genesis whose stability window is at least 2^63 slots is affected. Before, such a genesis settled updates and refused proposals at slots where the reference does not.
