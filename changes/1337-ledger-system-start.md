### Fixed (ledger, #1337)

- The ledger's system start, from which the Plutus script contexts' validity-interval times count, is now the
  Shelley genesis's `systemStart`, as the reference's `Globals` take it, and no longer the Byron genesis's
  `startTime`. A time is the start plus the slot's offset cut to the millisecond, the cut made on the sum as the
  reference's `slotToPOSIXTime` makes it. The slot clock and `GetSystemStart` keep the Byron start, the hard-fork
  combinator's. On a network whose two starts differ (a devnet written with a fractional Shelley start) a Plutus
  script now sees the reference's times; mainnet, preprod, preview and the Leios testnet, where the two agree, are
  unchanged. A Shelley genesis without `systemStart`, or with one that is not a string, is refused at the field as
  the genesis check refuses it.
