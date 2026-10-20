### Added (Plutus era load, #44)

- Exercise Plutus V1 in Alonzo and V1/V2 in Babbage and Conway during normal era-load runs. Require both validity-flag rejection controls and included spending/minting transactions, checking successful outputs and failed-script collateral effects against the reference ledger.
- Install the generated V2 cost model through the pre-Vasil protocol update, retaining V1; the real era transition does not read the Conway-only testnet `extraConfig` bootstrap.
